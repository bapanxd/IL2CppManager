#include "direct_call_analyzer.h"

#include <capstone/arm64.h>
#include <capstone/capstone.h>
#include <capstone/x86.h>

#include <algorithm>
#include <array>
#include <cstring>
#include <iomanip>
#include <limits>
#include <optional>
#include <queue>
#include <sstream>
#include <tuple>
#include <unordered_set>
#include <utility>

namespace il2cppmanager {
namespace {

constexpr std::size_t kMaximumMethodBytes = 512U * 1024U;
constexpr std::size_t kMaximumMethodInstructions = 128U * 1024U;
constexpr std::size_t kMaximumMethodBlocks = 32U * 1024U;
constexpr std::size_t kCallerScanChunkBytes = 4U * 1024U * 1024U;
constexpr std::size_t kMaximumCallerScanBytes = 512U * 1024U * 1024U;
constexpr std::size_t kMaximumCallerCandidates = 1024U * 1024U;
constexpr std::size_t kMaximumPageSize = 64;
constexpr std::size_t kMaximumInstructionBytes = 24;

struct DecodedInstruction {
    NativeInstruction instruction;
    std::uint64_t nextAddress = 0;
    std::optional<std::uint64_t> directCallTarget;
    std::optional<std::uint64_t> directBranchTarget;
    bool indirectCall = false;
    bool jump = false;
    bool unconditionalJump = false;
    bool returns = false;
};

NativeAnalysisStatus mergeStatus(
    NativeAnalysisStatus current,
    NativeAnalysisStatus incoming) noexcept {
    return static_cast<std::int32_t>(incoming) > static_cast<std::int32_t>(current)
               ? incoming
               : current;
}

std::uint64_t methodIdentity(
    std::int32_t classIndex,
    std::int32_t methodIndex) noexcept {
    return static_cast<std::uint64_t>(static_cast<std::uint32_t>(classIndex)) << 32U |
           static_cast<std::uint32_t>(methodIndex);
}

std::optional<std::uint64_t> addSigned(
    std::uint64_t base,
    std::int64_t displacement) noexcept {
    if (displacement >= 0) {
        const auto positive = static_cast<std::uint64_t>(displacement);
        return positive <= std::numeric_limits<std::uint64_t>::max() - base
                   ? std::optional(base + positive)
                   : std::nullopt;
    }
    const auto magnitude = static_cast<std::uint64_t>(-(displacement + 1)) + 1;
    return magnitude <= base ? std::optional(base - magnitude) : std::nullopt;
}

const ProcessMapRegion* findRegion(
    const std::vector<ProcessMapRegion>& regions,
    std::uint64_t address) noexcept {
    const auto found = std::upper_bound(
        regions.begin(),
        regions.end(),
        address,
        [](std::uint64_t value, const ProcessMapRegion& region) {
            return value < region.start;
        });
    if (found == regions.begin()) {
        return nullptr;
    }
    const auto& candidate = *std::prev(found);
    return address >= candidate.start && address < candidate.end ? &candidate : nullptr;
}

bool validInstructionAddress(
    NativeCodeArchitecture architecture,
    std::uint64_t address) noexcept {
    return address != 0 &&
           (architecture != NativeCodeArchitecture::Arm64 ||
            address % sizeof(std::uint32_t) == 0);
}

std::string formatBytes(const cs_insn& instruction) {
    const auto byteCount = std::min<std::size_t>(instruction.size, kMaximumInstructionBytes);
    std::ostringstream stream;
    stream << std::uppercase << std::hex << std::setfill('0');
    for (std::size_t index = 0; index < byteCount; ++index) {
        if (index != 0) {
            stream << ' ';
        }
        stream << std::setw(2) << static_cast<unsigned int>(instruction.bytes[index]);
    }
    return stream.str();
}

std::optional<std::uint64_t> arm64Immediate(
    const cs_insn& instruction,
    std::size_t operandIndex) noexcept {
    if (instruction.detail == nullptr ||
        operandIndex >= instruction.detail->arm64.op_count) {
        return std::nullopt;
    }
    const auto& operand = instruction.detail->arm64.operands[operandIndex];
    return operand.type == ARM64_OP_IMM && operand.imm >= 0
               ? std::optional(static_cast<std::uint64_t>(operand.imm))
               : std::nullopt;
}

std::optional<std::uint64_t> arm64BranchTarget(
    const cs_insn& instruction) noexcept {
    switch (instruction.id) {
        case ARM64_INS_CBZ:
        case ARM64_INS_CBNZ:
            return arm64Immediate(instruction, 1);
        case ARM64_INS_TBZ:
        case ARM64_INS_TBNZ:
            return arm64Immediate(instruction, 2);
        default:
            return arm64Immediate(instruction, 0);
    }
}

std::optional<std::uint64_t> x86Immediate(const cs_insn& instruction) noexcept {
    if (instruction.detail == nullptr || instruction.detail->x86.op_count == 0) {
        return std::nullopt;
    }
    const auto& operand = instruction.detail->x86.operands[0];
    return operand.type == X86_OP_IMM && operand.imm >= 0
               ? std::optional(static_cast<std::uint64_t>(operand.imm))
               : std::nullopt;
}

class CapstoneDecoder final {
public:
    explicit CapstoneDecoder(NativeCodeArchitecture architecture)
        : architecture_(architecture) {
        const auto error = architecture_ == NativeCodeArchitecture::Arm64
                               ? cs_open(CS_ARCH_ARM64, CS_MODE_ARM, &handle_)
                               : cs_open(CS_ARCH_X86, CS_MODE_64, &handle_);
        if (error != CS_ERR_OK ||
            cs_option(handle_, CS_OPT_DETAIL, CS_OPT_ON) != CS_ERR_OK) {
            close();
            return;
        }
        if (architecture_ == NativeCodeArchitecture::X86_64 &&
            cs_option(handle_, CS_OPT_SYNTAX, CS_OPT_SYNTAX_INTEL) != CS_ERR_OK) {
            close();
            return;
        }
        instruction_ = cs_malloc(handle_);
        if (instruction_ == nullptr) {
            close();
        }
    }

    ~CapstoneDecoder() {
        close();
    }

    CapstoneDecoder(const CapstoneDecoder&) = delete;
    CapstoneDecoder& operator=(const CapstoneDecoder&) = delete;

    bool available() const noexcept {
        return handle_ != 0 && instruction_ != nullptr;
    }

    bool decode(
        const std::uint8_t* code,
        std::size_t size,
        std::uint64_t address,
        DecodedInstruction& result) {
        if (!available() || code == nullptr || size == 0) {
            return false;
        }
        const auto* cursor = code;
        auto remaining = size;
        auto nextAddress = address;
        if (!cs_disasm_iter(handle_, &cursor, &remaining, &nextAddress, instruction_) ||
            instruction_->address != address || instruction_->size == 0 ||
            instruction_->size > size) {
            return false;
        }

        result = {};
        result.instruction = {
            instruction_->address,
            formatBytes(*instruction_),
            instruction_->mnemonic,
            instruction_->op_str,
            NativeInstructionFlow::None,
            {},
            -1,
        };
        result.nextAddress = nextAddress;
        result.returns = cs_insn_group(handle_, instruction_, CS_GRP_RET);

        const auto call = cs_insn_group(handle_, instruction_, CS_GRP_CALL);
        if (call) {
            if (architecture_ == NativeCodeArchitecture::Arm64) {
                if (instruction_->id == ARM64_INS_BL) {
                    result.directCallTarget = arm64Immediate(*instruction_, 0);
                } else {
                    result.indirectCall = true;
                }
            } else {
                if (instruction_->id == X86_INS_CALL) {
                    result.directCallTarget = x86Immediate(*instruction_);
                    result.indirectCall = !result.directCallTarget.has_value();
                } else {
                    result.indirectCall = true;
                }
            }
        }

        result.jump = !call && cs_insn_group(handle_, instruction_, CS_GRP_JUMP);
        if (result.jump) {
            if (architecture_ == NativeCodeArchitecture::Arm64) {
                result.directBranchTarget = arm64BranchTarget(*instruction_);
                const auto condition = instruction_->detail == nullptr
                                           ? ARM64_CC_INVALID
                                           : instruction_->detail->arm64.cc;
                result.unconditionalJump =
                    instruction_->id == ARM64_INS_B &&
                    (condition == ARM64_CC_INVALID ||
                     condition == ARM64_CC_AL ||
                     condition == ARM64_CC_NV);
            } else {
                result.directBranchTarget = x86Immediate(*instruction_);
                result.unconditionalJump = instruction_->id == X86_INS_JMP;
            }
        }
        return true;
    }

private:
    void close() noexcept {
        if (instruction_ != nullptr) {
            cs_free(instruction_, 1);
            instruction_ = nullptr;
        }
        if (handle_ != 0) {
            cs_close(&handle_);
            handle_ = 0;
        }
    }

    NativeCodeArchitecture architecture_;
    csh handle_ = 0;
    cs_insn* instruction_ = nullptr;
};

std::optional<std::uint64_t> arm64CallTarget(
    std::uint64_t instructionAddress,
    std::uint32_t instruction) noexcept {
    if ((instruction & 0xFC000000U) != 0x94000000U) {
        return std::nullopt;
    }
    auto immediate = static_cast<std::int64_t>(instruction & 0x03FFFFFFU);
    if ((immediate & 0x02000000LL) != 0) {
        immediate -= 0x04000000LL;
    }
    return addSigned(instructionAddress, immediate * 4);
}

std::optional<std::uint64_t> x86CallTarget(
    std::uint64_t instructionAddress,
    const std::uint8_t* bytes,
    std::size_t size) noexcept {
    if (bytes == nullptr || size < 5 || bytes[0] != 0xE8U ||
        instructionAddress > std::numeric_limits<std::uint64_t>::max() - 5) {
        return std::nullopt;
    }
    std::int32_t displacement = 0;
    std::memcpy(&displacement, bytes + 1, sizeof(displacement));
    return addSigned(instructionAddress + 5, displacement);
}

template <typename Value>
std::vector<Value> pageItems(
    const std::vector<Value>& values,
    std::size_t offset,
    std::size_t limit) {
    const auto begin = std::min(offset, values.size());
    const auto end = std::min(values.size(), begin + std::min(limit, kMaximumPageSize));
    return std::vector<Value>(values.begin() + begin, values.begin() + end);
}

}

struct DirectCallAnalyzer::MethodBodyAnalysis {
    struct DirectCall {
        std::uint64_t instructionAddress = 0;
        std::uint64_t targetAddress = 0;
        std::int32_t instructionIndex = -1;
    };

    NativeAnalysisStatus status = NativeAnalysisStatus::Unavailable;
    std::int32_t indirectCallCount = 0;
    std::vector<NativeInstruction> instructions;
    std::vector<DirectCall> calls;
};

struct DirectCallAnalyzer::CallerAnalysis {
    NativeAnalysisStatus status = NativeAnalysisStatus::Unavailable;
    std::int32_t indirectCallCount = 0;
    std::vector<NativeMethodReference> callers;
};

DirectCallAnalyzer::DirectCallAnalyzer(
    std::shared_ptr<const NativeCodeSnapshot> snapshot)
    : snapshot_(std::move(snapshot)) {
    if (snapshot_ == nullptr) {
        return;
    }
    for (const auto& method : snapshot_->methods) {
        if (method.address == 0) {
            continue;
        }
        addressesByIdentity_.emplace(
            methodIdentity(method.classIndex, method.methodIndex),
            method.address);
        aliasesByAddress_[method.address].push_back(method);
        methodStarts_.push_back(method.address);
    }
    std::sort(methodStarts_.begin(), methodStarts_.end());
    methodStarts_.erase(std::unique(methodStarts_.begin(), methodStarts_.end()), methodStarts_.end());
    for (auto& [address, aliases] : aliasesByAddress_) {
        static_cast<void>(address);
        std::sort(aliases.begin(), aliases.end(), [](const auto& left, const auto& right) {
            return std::tie(left.classIndex, left.methodIndex) <
                   std::tie(right.classIndex, right.methodIndex);
        });
    }
}

std::uint64_t DirectCallAnalyzer::methodAddress(
    std::int32_t classIndex,
    std::int32_t methodIndex) const noexcept {
    if (snapshot_ == nullptr || classIndex < 0 || methodIndex < 0) {
        return 0;
    }
    const auto found = addressesByIdentity_.find(methodIdentity(classIndex, methodIndex));
    return found == addressesByIdentity_.end() ? 0 : found->second;
}

NativeMethodSymbol DirectCallAnalyzer::uniqueMethodSymbol(
    std::uint64_t address) const noexcept {
    const auto aliases = aliasesByAddress_.find(address);
    return aliases != aliasesByAddress_.end() && aliases->second.size() == 1
               ? aliases->second.front()
               : NativeMethodSymbol{-1, -1, address};
}

std::shared_ptr<const DirectCallAnalyzer::MethodBodyAnalysis>
DirectCallAnalyzer::analyzeMethod(std::uint64_t address) const {
    {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        const auto cached = methodAnalysisCache_.find(address);
        if (cached != methodAnalysisCache_.end()) {
            return cached->second;
        }
    }

    auto result = std::make_shared<MethodBodyAnalysis>();
    const auto* region = snapshot_ == nullptr
                             ? nullptr
                             : findRegion(snapshot_->executableRegions, address);
    const auto nextMethod = std::upper_bound(methodStarts_.begin(), methodStarts_.end(), address);
    if (address == 0 || region == nullptr ||
        !validInstructionAddress(snapshot_->architecture, address)) {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        return methodAnalysisCache_.emplace(address, result).first->second;
    }

    auto naturalEnd = region->end;
    if (nextMethod != methodStarts_.end() && *nextMethod < naturalEnd) {
        naturalEnd = *nextMethod;
    }
    if (naturalEnd <= address) {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        return methodAnalysisCache_.emplace(address, result).first->second;
    }

    const auto naturalBytes = naturalEnd - address;
    const auto byteCount64 = std::min<std::uint64_t>(naturalBytes, kMaximumMethodBytes);
    if (byteCount64 == 0 || byteCount64 > std::numeric_limits<std::size_t>::max()) {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        return methodAnalysisCache_.emplace(address, result).first->second;
    }
    const auto byteCount = static_cast<std::size_t>(byteCount64);
    std::vector<std::uint8_t> code(byteCount);
    if (!readProcessMemory(snapshot_->pid, address, code.data(), code.size())) {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        return methodAnalysisCache_.emplace(address, result).first->second;
    }

    CapstoneDecoder decoder(snapshot_->architecture);
    if (!decoder.available()) {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        return methodAnalysisCache_.emplace(address, result).first->second;
    }

    const auto analysisEnd = address + byteCount;
    result->status = naturalBytes > byteCount
                         ? NativeAnalysisStatus::PartialLimit
                         : NativeAnalysisStatus::Complete;
    std::queue<std::uint64_t> blocks;
    std::unordered_set<std::uint64_t> queuedBlocks;
    std::unordered_set<std::uint64_t> visitedInstructions;
    blocks.push(address);
    queuedBlocks.insert(address);
    bool limitReached = false;

    while (!blocks.empty() && !limitReached) {
        if (queuedBlocks.size() > kMaximumMethodBlocks) {
            result->status = NativeAnalysisStatus::PartialLimit;
            break;
        }
        auto current = blocks.front();
        blocks.pop();

        while (current >= address && current < analysisEnd) {
            if (!visitedInstructions.insert(current).second) {
                break;
            }
            if (result->instructions.size() >= kMaximumMethodInstructions) {
                result->status = NativeAnalysisStatus::PartialLimit;
                limitReached = true;
                break;
            }

            const auto offset = static_cast<std::size_t>(current - address);
            DecodedInstruction decoded;
            if (!decoder.decode(code.data() + offset, code.size() - offset, current, decoded) ||
                decoded.nextAddress <= current || decoded.nextAddress > analysisEnd) {
                result->status = mergeStatus(
                    result->status,
                    NativeAnalysisStatus::PartialControlFlow);
                break;
            }

            if (decoded.directCallTarget && *decoded.directCallTarget != 0) {
                decoded.instruction.flow = NativeInstructionFlow::DirectCall;
                decoded.instruction.target = uniqueMethodSymbol(*decoded.directCallTarget);
                result->calls.push_back({current, *decoded.directCallTarget});
            } else if (decoded.indirectCall) {
                decoded.instruction.flow = NativeInstructionFlow::IndirectCall;
                if (result->indirectCallCount < std::numeric_limits<std::int32_t>::max()) {
                    ++result->indirectCallCount;
                }
                result->status = mergeStatus(
                    result->status,
                    NativeAnalysisStatus::PartialControlFlow);
            }

            if (decoded.jump) {
                decoded.instruction.flow = decoded.directBranchTarget
                                               ? NativeInstructionFlow::DirectBranch
                                               : NativeInstructionFlow::IndirectBranch;
                if (decoded.directBranchTarget) {
                    decoded.instruction.target = uniqueMethodSymbol(
                        *decoded.directBranchTarget);
                }
            }
            result->instructions.push_back(std::move(decoded.instruction));

            if (decoded.returns) {
                break;
            }
            if (decoded.jump) {
                const auto target = decoded.directBranchTarget;
                if (!target || *target < address || *target >= analysisEnd ||
                    !validInstructionAddress(snapshot_->architecture, *target)) {
                    result->status = mergeStatus(
                        result->status,
                        NativeAnalysisStatus::PartialControlFlow);
                } else if (queuedBlocks.insert(*target).second) {
                    blocks.push(*target);
                }
                if (decoded.unconditionalJump || !target) {
                    break;
                }
            }

            current = decoded.nextAddress;
            if (current == analysisEnd) {
                result->status = mergeStatus(
                    result->status,
                    NativeAnalysisStatus::PartialControlFlow);
            }
        }
    }

    std::sort(
        result->instructions.begin(),
        result->instructions.end(),
        [](const NativeInstruction& left, const NativeInstruction& right) {
            return left.address < right.address;
        });
    const auto instructionIndex = [&result](std::uint64_t instructionAddress) {
        const auto found = std::lower_bound(
            result->instructions.begin(),
            result->instructions.end(),
            instructionAddress,
            [](const NativeInstruction& instruction, std::uint64_t address) {
                return instruction.address < address;
            });
        return found != result->instructions.end() && found->address == instructionAddress
                   ? static_cast<std::int32_t>(
                         std::distance(result->instructions.begin(), found))
                   : -1;
    };
    for (auto& call : result->calls) {
        call.instructionIndex = instructionIndex(call.instructionAddress);
    }
    for (auto& instruction : result->instructions) {
        if (instruction.target.address != 0) {
            instruction.targetInstructionIndex = instructionIndex(
                instruction.target.address);
        }
    }
    std::sort(result->calls.begin(), result->calls.end(), [](const auto& left, const auto& right) {
        return std::tie(left.targetAddress, left.instructionAddress) <
               std::tie(right.targetAddress, right.instructionAddress);
    });
    result->calls.erase(
        std::unique(result->calls.begin(), result->calls.end(), [](const auto& left, const auto& right) {
            return left.targetAddress == right.targetAddress &&
                   left.instructionAddress == right.instructionAddress;
        }),
        result->calls.end());

    std::lock_guard<std::mutex> lock(cacheMutex_);
    return methodAnalysisCache_.emplace(address, std::move(result)).first->second;
}

std::shared_ptr<const DirectCallAnalyzer::CallerAnalysis>
DirectCallAnalyzer::analyzeCallers(std::uint64_t address) const {
    {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        const auto cached = callerAnalysisCache_.find(address);
        if (cached != callerAnalysisCache_.end()) {
            return cached->second;
        }
    }

    auto result = std::make_shared<CallerAnalysis>();
    if (snapshot_ == nullptr || address == 0 || aliasesByAddress_.find(address) == aliasesByAddress_.end()) {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        return callerAnalysisCache_.emplace(address, result).first->second;
    }

    result->status = NativeAnalysisStatus::Complete;
    std::unordered_set<std::uint64_t> candidateBodies;
    std::size_t scannedBytes = 0;
    bool stopScanning = false;

    for (const auto& region : snapshot_->executableRegions) {
        auto position = region.start;
        while (position < region.end) {
            if (scannedBytes >= kMaximumCallerScanBytes ||
                candidateBodies.size() >= kMaximumCallerCandidates) {
                result->status = NativeAnalysisStatus::PartialLimit;
                stopScanning = true;
                break;
            }

            const auto remaining = region.end - position;
            const auto allowed = kMaximumCallerScanBytes - scannedBytes;
            const auto coreBytes64 = std::min<std::uint64_t>(
                remaining,
                std::min<std::uint64_t>(kCallerScanChunkBytes, allowed));
            if (coreBytes64 == 0 || coreBytes64 > std::numeric_limits<std::size_t>::max()) {
                result->status = NativeAnalysisStatus::PartialLimit;
                stopScanning = true;
                break;
            }
            const auto coreBytes = static_cast<std::size_t>(coreBytes64);
            const auto overlap = static_cast<std::size_t>(
                std::min<std::uint64_t>(4, remaining - coreBytes64));
            std::vector<std::uint8_t> bytes(coreBytes + overlap);
            if (!readProcessMemory(snapshot_->pid, position, bytes.data(), bytes.size())) {
                result->status = mergeStatus(
                    result->status,
                    NativeAnalysisStatus::PartialControlFlow);
                position += coreBytes64;
                scannedBytes += coreBytes;
                continue;
            }

            const auto arm64 = snapshot_->architecture == NativeCodeArchitecture::Arm64;
            auto offset = arm64
                              ? static_cast<std::size_t>(
                                    (sizeof(std::uint32_t) -
                                     position % sizeof(std::uint32_t)) %
                                    sizeof(std::uint32_t))
                              : 0;
            const auto instructionStep = arm64 ? sizeof(std::uint32_t) : 1U;
            for (; offset < coreBytes; offset += instructionStep) {
                const auto instructionAddress = position + offset;
                std::optional<std::uint64_t> target;
                if (arm64) {
                    if (offset + sizeof(std::uint32_t) > bytes.size()) {
                        continue;
                    }
                    std::uint32_t instruction = 0;
                    std::memcpy(&instruction, bytes.data() + offset, sizeof(instruction));
                    target = arm64CallTarget(instructionAddress, instruction);
                } else {
                    target = x86CallTarget(
                        instructionAddress,
                        bytes.data() + offset,
                        bytes.size() - offset);
                }
                if (!target || *target != address) {
                    continue;
                }
                const auto upper = std::upper_bound(
                    methodStarts_.begin(),
                    methodStarts_.end(),
                    instructionAddress);
                if (upper == methodStarts_.begin()) {
                    continue;
                }
                const auto callerAddress = *std::prev(upper);
                const auto* callerRegion = findRegion(
                    snapshot_->executableRegions,
                    callerAddress);
                if (callerRegion == nullptr) {
                    continue;
                }
                auto callerEnd = callerRegion->end;
                const auto next = std::upper_bound(
                    methodStarts_.begin(),
                    methodStarts_.end(),
                    callerAddress);
                if (next != methodStarts_.end() && *next < callerEnd) {
                    callerEnd = *next;
                }
                const auto maximumCallerEnd =
                    callerAddress > std::numeric_limits<std::uint64_t>::max() - kMaximumMethodBytes
                        ? std::numeric_limits<std::uint64_t>::max()
                        : callerAddress + kMaximumMethodBytes;
                callerEnd = std::min<std::uint64_t>(
                    callerEnd,
                    maximumCallerEnd);
                if (instructionAddress < callerEnd) {
                    candidateBodies.insert(callerAddress);
                    if (candidateBodies.size() >= kMaximumCallerCandidates) {
                        result->status = NativeAnalysisStatus::PartialLimit;
                        stopScanning = true;
                        break;
                    }
                }
            }

            position += coreBytes64;
            scannedBytes += coreBytes;
            if (stopScanning) {
                break;
            }
        }
        if (stopScanning) {
            break;
        }
    }

    std::vector<std::uint64_t> orderedCandidates(
        candidateBodies.begin(),
        candidateBodies.end());
    std::sort(orderedCandidates.begin(), orderedCandidates.end());
    for (const auto callerAddress : orderedCandidates) {
        const auto analysis = analyzeMethod(callerAddress);
        result->status = mergeStatus(
            result->status,
            analysis->status == NativeAnalysisStatus::Unavailable
                ? NativeAnalysisStatus::PartialControlFlow
                : analysis->status);
        if (analysis->indirectCallCount >
            std::numeric_limits<std::int32_t>::max() - result->indirectCallCount) {
            result->indirectCallCount = std::numeric_limits<std::int32_t>::max();
        } else {
            result->indirectCallCount += analysis->indirectCallCount;
        }
        const auto aliases = aliasesByAddress_.find(callerAddress);
        if (aliases == aliasesByAddress_.end()) {
            continue;
        }
        for (const auto& call : analysis->calls) {
            if (call.targetAddress != address || call.instructionIndex < 0) {
                continue;
            }
            for (const auto& alias : aliases->second) {
                result->callers.push_back({
                    alias,
                    call.instructionAddress,
                    call.instructionIndex,
                });
            }
        }
    }

    std::sort(
        result->callers.begin(),
        result->callers.end(),
        [](const NativeMethodReference& left, const NativeMethodReference& right) {
            return std::tie(
                       left.method.address,
                       left.callSiteAddress,
                       left.method.classIndex,
                       left.method.methodIndex) <
                   std::tie(
                       right.method.address,
                       right.callSiteAddress,
                       right.method.classIndex,
                       right.method.methodIndex);
        });
    result->callers.erase(
        std::unique(
            result->callers.begin(),
            result->callers.end(),
            [](const NativeMethodReference& left, const NativeMethodReference& right) {
                return left.method.address == right.method.address &&
                       left.callSiteAddress == right.callSiteAddress &&
                       left.method.classIndex == right.method.classIndex &&
                       left.method.methodIndex == right.method.methodIndex;
            }),
        result->callers.end());

    std::lock_guard<std::mutex> lock(cacheMutex_);
    return callerAnalysisCache_.emplace(address, std::move(result)).first->second;
}

DirectMethodReferencePage DirectCallAnalyzer::methodCalls(
    std::int32_t classIndex,
    std::int32_t methodIndex,
    std::size_t offset,
    std::size_t limit) const {
    DirectMethodReferencePage page;
    const auto address = methodAddress(classIndex, methodIndex);
    const auto analysis = analyzeMethod(address);
    page.status = analysis->status;
    page.indirectCallCount = analysis->indirectCallCount;

    std::vector<NativeMethodReference> items;
    for (const auto& call : analysis->calls) {
        if (call.instructionIndex < 0) {
            continue;
        }
        const auto aliases = aliasesByAddress_.find(call.targetAddress);
        if (aliases == aliasesByAddress_.end()) {
            items.push_back({
                {-1, -1, call.targetAddress},
                call.instructionAddress,
                call.instructionIndex,
            });
        } else {
            for (const auto& alias : aliases->second) {
                items.push_back({
                    alias,
                    call.instructionAddress,
                    call.instructionIndex,
                });
            }
        }
    }
    std::sort(items.begin(), items.end(), [](const auto& left, const auto& right) {
        return std::tie(
                   left.callSiteAddress,
                   left.method.address,
                   left.method.classIndex,
                   left.method.methodIndex) <
               std::tie(
                   right.callSiteAddress,
                   right.method.address,
                   right.method.classIndex,
                   right.method.methodIndex);
    });
    items.erase(
        std::unique(items.begin(), items.end(), [](const auto& left, const auto& right) {
            return left.callSiteAddress == right.callSiteAddress &&
                   left.method.address == right.method.address &&
                   left.method.classIndex == right.method.classIndex &&
                   left.method.methodIndex == right.method.methodIndex;
        }),
        items.end());
    page.totalCount = items.size();
    page.items = pageItems(items, offset, limit);
    return page;
}

DirectMethodReferencePage DirectCallAnalyzer::methodCallers(
    std::int32_t classIndex,
    std::int32_t methodIndex,
    std::size_t offset,
    std::size_t limit) const {
    DirectMethodReferencePage page;
    const auto analysis = analyzeCallers(methodAddress(classIndex, methodIndex));
    page.totalCount = analysis->callers.size();
    page.status = analysis->status;
    page.indirectCallCount = analysis->indirectCallCount;
    page.items = pageItems(analysis->callers, offset, limit);
    return page;
}

DirectInstructionPage DirectCallAnalyzer::methodInstructions(
    std::int32_t classIndex,
    std::int32_t methodIndex,
    std::size_t offset,
    std::size_t limit) const {
    DirectInstructionPage page;
    const auto analysis = analyzeMethod(methodAddress(classIndex, methodIndex));
    page.totalCount = analysis->instructions.size();
    page.status = analysis->status;
    page.indirectCallCount = analysis->indirectCallCount;
    page.items = pageItems(analysis->instructions, offset, limit);
    return page;
}

}
