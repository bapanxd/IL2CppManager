#pragma once

#include "process_memory.h"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace il2cppmanager {

enum class NativeAnalysisStatus : std::int32_t {
    Complete = 0,
    PartialControlFlow = 1,
    PartialLimit = 2,
    Unavailable = 3,
};

enum class NativeCodeArchitecture : std::uint8_t {
    Arm64 = 1,
    X86_64 = 2,
};

enum class NativeInstructionFlow : std::int32_t {
    None = 0,
    DirectCall = 1,
    DirectBranch = 2,
    IndirectCall = 3,
    IndirectBranch = 4,
};

struct NativeMethodSymbol {
    std::int32_t classIndex = -1;
    std::int32_t methodIndex = -1;
    std::uint64_t address = 0;
};

struct NativeMethodReference {
    NativeMethodSymbol method;
    std::uint64_t callSiteAddress = 0;
    std::int32_t callSiteInstructionIndex = -1;
};

struct NativeCodeSnapshot {
    std::int32_t pid = 0;
    std::uint64_t moduleBase = 0;
    NativeCodeArchitecture architecture = NativeCodeArchitecture::Arm64;
    std::vector<ProcessMapRegion> executableRegions;
    std::vector<NativeMethodSymbol> methods;
};

struct DirectMethodReferencePage {
    std::size_t totalCount = 0;
    NativeAnalysisStatus status = NativeAnalysisStatus::Unavailable;
    std::int32_t indirectCallCount = 0;
    std::vector<NativeMethodReference> items;
};

struct NativeInstruction {
    std::uint64_t address = 0;
    std::string bytes;
    std::string mnemonic;
    std::string operands;
    NativeInstructionFlow flow = NativeInstructionFlow::None;
    NativeMethodSymbol target;
    std::int32_t targetInstructionIndex = -1;
};

struct DirectInstructionPage {
    std::size_t totalCount = 0;
    NativeAnalysisStatus status = NativeAnalysisStatus::Unavailable;
    std::int32_t indirectCallCount = 0;
    std::vector<NativeInstruction> items;
};

class DirectCallAnalyzer final {
public:
    explicit DirectCallAnalyzer(std::shared_ptr<const NativeCodeSnapshot> snapshot);

    DirectMethodReferencePage methodCalls(
        std::int32_t classIndex,
        std::int32_t methodIndex,
        std::size_t offset,
        std::size_t limit) const;
    DirectMethodReferencePage methodCallers(
        std::int32_t classIndex,
        std::int32_t methodIndex,
        std::size_t offset,
        std::size_t limit) const;
    DirectInstructionPage methodInstructions(
        std::int32_t classIndex,
        std::int32_t methodIndex,
        std::size_t offset,
        std::size_t limit) const;

private:
    struct MethodBodyAnalysis;
    struct CallerAnalysis;

    std::uint64_t methodAddress(
        std::int32_t classIndex,
        std::int32_t methodIndex) const noexcept;
    NativeMethodSymbol uniqueMethodSymbol(std::uint64_t address) const noexcept;
    std::shared_ptr<const MethodBodyAnalysis> analyzeMethod(std::uint64_t address) const;
    std::shared_ptr<const CallerAnalysis> analyzeCallers(std::uint64_t address) const;

    std::shared_ptr<const NativeCodeSnapshot> snapshot_;
    std::vector<std::uint64_t> methodStarts_;
    std::unordered_map<std::uint64_t, std::uint64_t> addressesByIdentity_;
    std::unordered_map<std::uint64_t, std::vector<NativeMethodSymbol>> aliasesByAddress_;
    mutable std::mutex cacheMutex_;
    mutable std::unordered_map<
        std::uint64_t,
        std::shared_ptr<const MethodBodyAnalysis>> methodAnalysisCache_;
    mutable std::unordered_map<
        std::uint64_t,
        std::shared_ptr<const CallerAnalysis>> callerAnalysisCache_;
};

}
