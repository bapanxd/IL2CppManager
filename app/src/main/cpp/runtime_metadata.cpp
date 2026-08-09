#include "runtime_metadata.h"

#include <elf.h>

#include <algorithm>
#include <array>
#include <cstring>
#include <limits>
#include <sstream>
#include <string_view>
#include <tuple>

namespace il2cppmanager {
namespace {

constexpr std::size_t kScanChunkBytes = 4U * 1024U * 1024U;
constexpr std::size_t kScanOverlapBytes = 256;
constexpr std::size_t kMaximumScannedBytes = 128U * 1024U * 1024U;
constexpr std::size_t kMaximumReadOnlyScanRegionBytes = 64U * 1024U * 1024U;
constexpr std::size_t kMaximumRuntimeStringBytes = 4096;
constexpr std::size_t kMaximumMethodSignatureBytes = 16U * 1024U;
constexpr std::size_t kMaximumTypeDepth = 12;
constexpr std::uint64_t kMaximumRegistrationCount = 8U * 1024U * 1024U;
constexpr std::uint64_t kMaximumMethodPointerCount = 16U * 1024U * 1024U;
constexpr std::size_t kMaximumCachedMethodPointerBytes = 16U * 1024U * 1024U;
constexpr std::size_t kMaximumProgramHeaderCount = 256;
constexpr std::uint64_t kMetadataTypesCountOffset = 0x30;
constexpr std::uint64_t kMetadataTypesOffset = 0x38;
constexpr std::uint64_t kMetadataFieldOffsetsCountOffset = 0x50;
constexpr std::uint64_t kMetadataFieldOffsetsOffset = 0x58;
constexpr std::uint64_t kMetadataTypeSizesCountOffset = 0x60;
constexpr std::uint64_t kMetadataTypeSizesOffset = 0x68;
constexpr std::uint64_t kMetadataRegistrationSize = 0x80;
constexpr std::uint64_t kTypeDataOffset = 0;
constexpr std::uint64_t kTypeBitsOffset = 8;
constexpr std::uint64_t kCodeGenModuleNameOffset = 0;
constexpr std::uint64_t kCodeGenModuleMethodCountOffset = 8;
constexpr std::uint64_t kCodeGenModuleMethodsOffset = 0x10;
constexpr std::uint32_t kMethodTokenTable = 0x06000000U;
constexpr std::uint32_t kTokenTableMask = 0xFF000000U;
constexpr std::uint32_t kTokenRidMask = 0x00FFFFFFU;

struct ModuleElfInfo {
    std::optional<NativeCodeArchitecture> architecture;
    std::vector<std::pair<std::uint64_t, std::uint64_t>> executableFileRanges;
};

enum class TypeKind : std::uint8_t {
    Void = 0x01,
    Boolean = 0x02,
    Char = 0x03,
    I1 = 0x04,
    U1 = 0x05,
    I2 = 0x06,
    U2 = 0x07,
    I4 = 0x08,
    U4 = 0x09,
    I8 = 0x0A,
    U8 = 0x0B,
    R4 = 0x0C,
    R8 = 0x0D,
    String = 0x0E,
    Pointer = 0x0F,
    ByReference = 0x10,
    ValueType = 0x11,
    Class = 0x12,
    TypeVariable = 0x13,
    Array = 0x14,
    GenericInstance = 0x15,
    TypedReference = 0x16,
    NativeInt = 0x18,
    NativeUInt = 0x19,
    FunctionPointer = 0x1B,
    Object = 0x1C,
    SzArray = 0x1D,
    MethodVariable = 0x1E,
    Enum = 0x55,
};

bool matchesModule(const ProcessMapRegion& region, const std::string& moduleName) noexcept {
    return modulePathMatches(region.path, moduleName);
}

bool containsAddress(
    const std::vector<ProcessMapRegion>& regions,
    std::uint64_t address) noexcept {
    return std::any_of(regions.begin(), regions.end(), [address](const ProcessMapRegion& region) {
        return address >= region.start && address < region.end;
    });
}

bool checkedSize(std::uint64_t count, std::size_t width, std::size_t& result) noexcept {
    if (count > static_cast<std::uint64_t>(std::numeric_limits<std::size_t>::max() / width)) {
        return false;
    }
    result = static_cast<std::size_t>(count) * width;
    return true;
}

bool appendSignaturePart(std::string& signature, std::string_view part) {
    if (part.size() > kMaximumMethodSignatureBytes ||
        signature.size() > kMaximumMethodSignatureBytes - part.size()) {
        return false;
    }
    signature.append(part);
    return true;
}

ModuleElfInfo readModuleElfInfo(
    std::int32_t pid,
    const std::string& moduleName,
    const std::vector<ProcessMapRegion>& maps) {
    ModuleElfInfo result;
    for (const auto& region : maps) {
        if (!region.readable || !matchesModule(region, moduleName) ||
            region.end - region.start < sizeof(Elf64_Ehdr)) {
            continue;
        }
        Elf64_Ehdr header{};
        if (!readProcessMemory(
                pid,
                region.start,
                reinterpret_cast<std::uint8_t*>(&header),
                sizeof(header)) ||
            std::memcmp(header.e_ident, ELFMAG, SELFMAG) != 0 ||
            header.e_ident[EI_CLASS] != ELFCLASS64 ||
            header.e_ident[EI_DATA] != ELFDATA2LSB) {
            continue;
        }
        switch (header.e_machine) {
            case EM_AARCH64:
                result.architecture = NativeCodeArchitecture::Arm64;
                break;
            case EM_X86_64:
                result.architecture = NativeCodeArchitecture::X86_64;
                break;
            default:
                return result;
        }
        if (!result.architecture ||
            header.e_phentsize != sizeof(Elf64_Phdr) || header.e_phnum == 0 ||
            header.e_phnum > kMaximumProgramHeaderCount) {
            return result;
        }
        std::size_t tableBytes = 0;
        if (!checkedSize(header.e_phnum, sizeof(Elf64_Phdr), tableBytes) ||
            header.e_phoff > std::numeric_limits<std::uint64_t>::max() - region.start) {
            return result;
        }
        const auto tableAddress = region.start + header.e_phoff;
        if (tableAddress < region.start || tableAddress > region.end ||
            tableBytes > region.end - tableAddress) {
            return result;
        }
        std::vector<Elf64_Phdr> headers(header.e_phnum);
        if (!readProcessMemory(
                pid,
                tableAddress,
                reinterpret_cast<std::uint8_t*>(headers.data()),
                tableBytes)) {
            return result;
        }
        for (const auto& program : headers) {
            if (program.p_type != PT_LOAD || (program.p_flags & PF_X) == 0 ||
                program.p_filesz == 0 ||
                program.p_offset > std::numeric_limits<std::uint64_t>::max() - region.fileOffset) {
                continue;
            }
            const auto fileOffset = region.fileOffset + program.p_offset;
            if (program.p_filesz > std::numeric_limits<std::uint64_t>::max() - fileOffset) {
                continue;
            }
            result.executableFileRanges.emplace_back(
                fileOffset,
                fileOffset + program.p_filesz);
        }
        std::sort(result.executableFileRanges.begin(), result.executableFileRanges.end());
        return result;
    }
    return result;
}

template <typename Value>
Value load(const std::uint8_t* bytes, std::size_t offset) noexcept {
    Value value{};
    std::memcpy(&value, bytes + offset, sizeof(value));
    return value;
}

std::optional<std::string> primitiveName(TypeKind kind) {
    switch (kind) {
        case TypeKind::Void: return "void";
        case TypeKind::Boolean: return "bool";
        case TypeKind::Char: return "char";
        case TypeKind::I1: return "sbyte";
        case TypeKind::U1: return "byte";
        case TypeKind::I2: return "short";
        case TypeKind::U2: return "ushort";
        case TypeKind::I4: return "int";
        case TypeKind::U4: return "uint";
        case TypeKind::I8: return "long";
        case TypeKind::U8: return "ulong";
        case TypeKind::R4: return "float";
        case TypeKind::R8: return "double";
        case TypeKind::String: return "string";
        case TypeKind::TypedReference: return "TypedReference";
        case TypeKind::NativeInt: return "nint";
        case TypeKind::NativeUInt: return "nuint";
        case TypeKind::Object: return "object";
        default: return std::nullopt;
    }
}

}

bool RuntimeMetadataResolver::TypeDefinitionKey::operator==(
    const TypeDefinitionKey& other) const noexcept {
    return nameIndex == other.nameIndex &&
           namespaceIndex == other.namespaceIndex &&
           fieldStart == other.fieldStart &&
           methodStart == other.methodStart;
}

std::size_t RuntimeMetadataResolver::TypeDefinitionKeyHash::operator()(
    const TypeDefinitionKey& value) const noexcept {
    std::size_t result = std::hash<std::int32_t>{}(value.nameIndex);
    result ^= std::hash<std::int32_t>{}(value.namespaceIndex) + 0x9E3779B9U + (result << 6U) + (result >> 2U);
    result ^= std::hash<std::int32_t>{}(value.fieldStart) + 0x9E3779B9U + (result << 6U) + (result >> 2U);
    result ^= std::hash<std::int32_t>{}(value.methodStart) + 0x9E3779B9U + (result << 6U) + (result >> 2U);
    return result;
}

bool RuntimeMetadataResolver::GenericParameterKey::operator==(
    const GenericParameterKey& other) const noexcept {
    return ownerIndex == other.ownerIndex &&
           nameIndex == other.nameIndex &&
           constraintsStart == other.constraintsStart &&
           constraintsCount == other.constraintsCount &&
           number == other.number &&
           flags == other.flags;
}

std::size_t RuntimeMetadataResolver::GenericParameterKeyHash::operator()(
    const GenericParameterKey& value) const noexcept {
    std::size_t result = std::hash<std::int32_t>{}(value.ownerIndex);
    const auto combine = [&result](std::size_t hash) {
        result ^= hash + 0x9E3779B9U + (result << 6U) + (result >> 2U);
    };
    combine(std::hash<std::int32_t>{}(value.nameIndex));
    combine(std::hash<std::int16_t>{}(value.constraintsStart));
    combine(std::hash<std::int16_t>{}(value.constraintsCount));
    combine(std::hash<std::uint16_t>{}(value.number));
    combine(std::hash<std::uint16_t>{}(value.flags));
    return result;
}

RuntimeMetadataResolver::RuntimeMetadataResolver(
    std::int32_t pid,
    std::string moduleName,
    std::shared_ptr<const MetadataModel> model,
    std::vector<ProcessMapRegion> maps,
    std::vector<ProcessMapRegion> scanRegions)
    : pid_(pid),
      moduleName_(std::move(moduleName)),
      model_(std::move(model)),
      maps_(std::move(maps)),
      scanRegions_(std::move(scanRegions)) {
    moduleRegions_.reserve(maps_.size());
    for (const auto& region : maps_) {
        if (matchesModule(region, moduleName_)) {
            moduleRegions_.push_back(region);
            moduleHasExecutableMapping_ |= region.executable;
        }
    }
    auto elfInfo = readModuleElfInfo(pid_, moduleName_, moduleRegions_);
    moduleBase_ = findModuleBase(maps_, moduleName_).value_or(0);
    moduleArchitecture_ = elfInfo.architecture;
    if (!moduleHasExecutableMapping_) {
        executableFileRanges_ = std::move(elfInfo.executableFileRanges);
    }
    typeDefinitionIndices_.reserve(model_->types().size());
    for (std::size_t index = 0; index < model_->types().size(); ++index) {
        const auto& type = model_->types()[index];
        typeDefinitionIndices_.emplace(
            TypeDefinitionKey{type.nameIndex, type.namespaceIndex, type.fieldStart, type.methodStart},
            static_cast<std::int32_t>(index));
    }
    genericParameterIndices_.reserve(model_->genericParameters().size());
    for (std::size_t index = 0; index < model_->genericParameters().size(); ++index) {
        const auto& parameter = model_->genericParameters()[index];
        genericParameterIndices_.emplace(
            GenericParameterKey{
                parameter.ownerIndex,
                parameter.nameIndex,
                static_cast<std::int16_t>(parameter.constraintsStart),
                static_cast<std::int16_t>(parameter.constraintTypeIndices.size()),
                parameter.number,
                parameter.flags,
            },
            static_cast<std::int32_t>(index));
    }
}

std::shared_ptr<RuntimeMetadataResolver> RuntimeMetadataResolver::attach(
    std::int32_t pid,
    const std::string& moduleName,
    std::shared_ptr<const MetadataModel> model) {
    if (pid <= 0 || moduleName.empty() || model == nullptr || model->types().empty() ||
        !model->supportsRuntimeMetadata()) {
        return nullptr;
    }
    auto maps = readProcessMaps(pid);
    if (maps.empty()) {
        return nullptr;
    }

    std::vector<ProcessMapRegion> writable;
    std::vector<ProcessMapRegion> readOnly;
    for (const auto& region : maps) {
        if (!region.readable || !matchesModule(region, moduleName)) {
            continue;
        }
        const auto size = region.end - region.start;
        if (region.writable) {
            writable.push_back(region);
        } else if (size <= kMaximumReadOnlyScanRegionBytes &&
                   (region.fileOffset != 0 || size <= kScanChunkBytes)) {
            readOnly.push_back(region);
        }
    }
    writable.insert(writable.end(), readOnly.begin(), readOnly.end());
    if (writable.empty()) {
        return nullptr;
    }

    auto resolver = std::shared_ptr<RuntimeMetadataResolver>(new RuntimeMetadataResolver(
        pid,
        moduleName,
        std::move(model),
        std::move(maps),
        std::move(writable)));
    return resolver->discover() ? resolver : nullptr;
}

bool RuntimeMetadataResolver::discover() {
    std::optional<MetadataRegistration> metadata;
    std::unordered_map<std::string, CodeGenModule> modules;
    const auto typeCount = static_cast<std::uint64_t>(model_->types().size());
    const auto imageCount = model_->imageNames().size();
    const auto searchModules = imageCount > 0 && imageCount <= kMaximumRegistrationCount;
    std::vector<std::uint8_t> buffer(kScanChunkBytes + kScanOverlapBytes);
    std::size_t totalScanned = 0;

    for (const auto& region : scanRegions_) {
        const auto regionSize64 = region.end - region.start;
        if (regionSize64 == 0 || regionSize64 > std::numeric_limits<std::size_t>::max()) {
            continue;
        }
        const auto regionSize = static_cast<std::size_t>(regionSize64);
        std::size_t consumed = 0;
        while (consumed < regionSize && totalScanned < kMaximumScannedBytes) {
            const auto remaining = regionSize - consumed;
            const auto size = std::min(buffer.size(), remaining);
            if (!readProcessMemory(pid_, region.start + consumed, buffer.data(), size)) {
                break;
            }
            const auto absoluteStart = region.start + consumed;
            const auto alignment = static_cast<std::size_t>((8U - absoluteStart % 8U) % 8U);
            for (std::size_t offset = alignment;
                 offset + sizeof(std::uint64_t) * 2 <= size;
                 offset += sizeof(std::uint64_t)) {
                const auto* bytes = buffer.data() + offset;
                const auto available = size - offset;
                if (!metadata && available >= kMetadataRegistrationSize) {
                    const auto typesCount = load<std::uint64_t>(bytes, kMetadataTypesCountOffset);
                    const auto fieldOffsetsCount =
                        load<std::uint64_t>(bytes, kMetadataFieldOffsetsCountOffset);
                    const auto typeSizesCount =
                        load<std::uint64_t>(bytes, kMetadataTypeSizesCountOffset);
                    if (fieldOffsetsCount == typeCount && typeSizesCount == typeCount &&
                        typesCount > typeCount && typesCount <= kMaximumRegistrationCount) {
                        MetadataRegistration candidate{
                            absoluteStart + offset,
                            static_cast<std::uint32_t>(typesCount),
                            load<std::uint64_t>(bytes, kMetadataTypesOffset),
                            load<std::uint64_t>(bytes, kMetadataFieldOffsetsOffset),
                            load<std::uint64_t>(bytes, kMetadataTypeSizesOffset),
                        };
                        if (validateMetadataRegistration(candidate)) {
                            metadata = candidate;
                        }
                    }
                }
                if (searchModules && modules.empty() &&
                    load<std::uint64_t>(bytes, 0) == imageCount) {
                    const auto modulesAddress = load<std::uint64_t>(bytes, sizeof(std::uint64_t));
                    if (auto candidate = validateCodeGenModules(modulesAddress)) {
                        modules = std::move(*candidate);
                    }
                }
                if (metadata && !modules.empty()) {
                    metadataRegistration_ = *metadata;
                    codeGenModules_ = std::move(modules);
                    return true;
                }
            }
            totalScanned += size;
            if (remaining <= kScanChunkBytes) {
                break;
            }
            consumed += kScanChunkBytes;
        }
    }
    if (metadata) {
        metadataRegistration_ = *metadata;
    }
    codeGenModules_ = std::move(modules);
    return metadata.has_value() || !codeGenModules_.empty();
}

bool RuntimeMetadataResolver::validateMetadataRegistration(
    const MetadataRegistration& registration) const {
    std::size_t typesBytes = 0;
    std::size_t offsetsBytes = 0;
    if (!checkedSize(registration.typesCount, sizeof(std::uint64_t), typesBytes) ||
        !checkedSize(model_->types().size(), sizeof(std::uint64_t), offsetsBytes) ||
        !isReadable(registration.types, typesBytes) ||
        !isReadable(registration.fieldOffsets, offsetsBytes)) {
        return false;
    }

    if (!isReadable(registration.typeSizes, offsetsBytes)) {
        return false;
    }
    const auto references = referencedTypeIndices();
    if (references.empty() ||
        static_cast<std::uint64_t>(*std::max_element(references.begin(), references.end())) >=
            registration.typesCount) {
        return false;
    }
    const auto sampleCount = std::min<std::size_t>(references.size(), 32);
    std::size_t validSamples = 0;
    for (std::size_t index = 0; index < sampleCount; ++index) {
        const auto pointer = readValue<std::uint64_t>(
            registration.types + static_cast<std::uint64_t>(references[index]) * sizeof(std::uint64_t));
        if (pointer && *pointer != 0 && isReadable(*pointer, 16)) {
            ++validSamples;
        }
    }
    return validSamples >= std::min<std::size_t>(sampleCount, 4);
}

std::optional<std::unordered_map<std::string, RuntimeMetadataResolver::CodeGenModule>>
RuntimeMetadataResolver::validateCodeGenModules(std::uint64_t modulesAddress) const {
    std::size_t arrayBytes = 0;
    if (!checkedSize(model_->imageNames().size(), sizeof(std::uint64_t), arrayBytes) ||
        !isReadable(modulesAddress, arrayBytes)) {
        return std::nullopt;
    }
    std::unordered_set<std::string> expected(
        model_->imageNames().begin(),
        model_->imageNames().end());
    std::unordered_map<std::string, CodeGenModule> modules;
    modules.reserve(expected.size());

    for (std::size_t index = 0; index < model_->imageNames().size(); ++index) {
        const auto moduleAddress = readValue<std::uint64_t>(
            modulesAddress + index * sizeof(std::uint64_t));
        if (!moduleAddress || !isReadable(*moduleAddress, 0x18)) {
            return std::nullopt;
        }
        const auto nameAddress = readValue<std::uint64_t>(*moduleAddress + kCodeGenModuleNameOffset);
        const auto methodCount = readValue<std::uint64_t>(*moduleAddress + kCodeGenModuleMethodCountOffset);
        const auto methodPointers = readValue<std::uint64_t>(*moduleAddress + kCodeGenModuleMethodsOffset);
        if (!nameAddress || !methodCount || !methodPointers ||
            *methodCount > kMaximumMethodPointerCount) {
            return std::nullopt;
        }
        auto name = readString(*nameAddress);
        if (!name || expected.erase(*name) != 1) {
            return std::nullopt;
        }
        std::size_t methodBytes = 0;
        if (*methodCount > 0 &&
            (!checkedSize(*methodCount, sizeof(std::uint64_t), methodBytes) ||
             *methodPointers == 0 || !isReadable(*methodPointers, methodBytes))) {
            return std::nullopt;
        }
        modules.emplace(std::move(*name), CodeGenModule{*methodCount, *methodPointers});
    }
    return expected.empty() ? std::optional(std::move(modules)) : std::nullopt;
}

bool RuntimeMetadataResolver::isReadable(std::uint64_t address, std::size_t size) const noexcept {
    if (address == 0 || size == 0 ||
        static_cast<std::uint64_t>(size) > std::numeric_limits<std::uint64_t>::max() - address) {
        return false;
    }
    const auto end = address + size;
    return std::any_of(maps_.begin(), maps_.end(), [address, end](const ProcessMapRegion& region) {
        return region.readable && address >= region.start && end <= region.end;
    });
}

bool RuntimeMetadataResolver::isMapped(std::uint64_t address, std::size_t size) const noexcept {
    if (address == 0 || size == 0 ||
        static_cast<std::uint64_t>(size) > std::numeric_limits<std::uint64_t>::max() - address) {
        return false;
    }
    const auto end = address + size;
    return std::any_of(maps_.begin(), maps_.end(), [address, end](const ProcessMapRegion& region) {
        return address >= region.start && end <= region.end;
    });
}

bool RuntimeMetadataResolver::readBytes(
    std::uint64_t address,
    void* destination,
    std::size_t size) const noexcept {
    return destination != nullptr && isReadable(address, size) &&
           readProcessMemory(pid_, address, static_cast<std::uint8_t*>(destination), size);
}

std::optional<std::string> RuntimeMetadataResolver::readString(std::uint64_t address) const {
    const auto region = std::find_if(maps_.begin(), maps_.end(), [address](const ProcessMapRegion& candidate) {
        return candidate.readable && address >= candidate.start && address < candidate.end;
    });
    if (region == maps_.end()) {
        return std::nullopt;
    }
    const auto available = static_cast<std::size_t>(std::min<std::uint64_t>(
        region->end - address,
        kMaximumRuntimeStringBytes + 1));
    std::vector<std::uint8_t> bytes(available);
    if (!readBytes(address, bytes.data(), bytes.size())) {
        return std::nullopt;
    }
    const auto terminator = std::find(bytes.begin(), bytes.end(), std::uint8_t{0});
    if (terminator == bytes.end()) {
        return std::nullopt;
    }
    std::string value(reinterpret_cast<const char*>(bytes.data()), terminator - bytes.begin());
    return isValidUtf8(value) ? std::optional(std::move(value)) : std::nullopt;
}

std::optional<std::string> RuntimeMetadataResolver::fieldTypeName(const FieldMetadata& field) const {
    return typeName(field.typeIndex);
}

std::optional<std::string> RuntimeMetadataResolver::propertyTypeName(
    const TypeMetadata& type,
    const PropertyMetadata& property) const {
    if (property.getterIndex >= 0 &&
        static_cast<std::size_t>(property.getterIndex) < type.methods.size()) {
        return typeName(type.methods[static_cast<std::size_t>(property.getterIndex)].returnTypeIndex);
    }
    if (property.setterIndex < 0 ||
        static_cast<std::size_t>(property.setterIndex) >= type.methods.size()) {
        return std::nullopt;
    }
    const auto& parameters = type.methods[static_cast<std::size_t>(property.setterIndex)].parameters;
    return parameters.empty() ? std::nullopt : typeName(parameters.back().typeIndex);
}

std::optional<std::string> RuntimeMetadataResolver::eventTypeName(
    const EventMetadata& event) const {
    return typeName(event.typeIndex);
}

std::optional<std::string> RuntimeMetadataResolver::referencedTypeName(
    std::int32_t typeIndex) const {
    return typeName(typeIndex);
}

std::optional<std::int32_t> RuntimeMetadataResolver::referencedTypeDefinitionIndex(
    std::int32_t typeIndex) const {
    if (const auto direct = model_->typeDefinitionIndexForTypeIndex(typeIndex)) {
        return direct;
    }
    if (metadataRegistration_.types == 0 || typeIndex < 0 ||
        static_cast<std::uint32_t>(typeIndex) >= metadataRegistration_.typesCount) {
        return std::nullopt;
    }
    const auto typeAddress = readValue<std::uint64_t>(
        metadataRegistration_.types + static_cast<std::uint64_t>(typeIndex) * sizeof(std::uint64_t));
    if (!typeAddress || *typeAddress == 0) {
        return std::nullopt;
    }
    std::unordered_set<std::uint64_t> visited;
    return typeDefinitionIndexAt(*typeAddress, 0, visited);
}

std::optional<RuntimeMetadataResolver::TypeSizes> RuntimeMetadataResolver::typeSizes(
    std::int32_t typeDefinitionIndex) const {
    if (metadataRegistration_.typeSizes == 0 || typeDefinitionIndex < 0 ||
        static_cast<std::size_t>(typeDefinitionIndex) >= model_->types().size()) {
        return std::nullopt;
    }
    const auto sizesAddress = readValue<std::uint64_t>(
        metadataRegistration_.typeSizes +
        static_cast<std::uint64_t>(typeDefinitionIndex) * sizeof(std::uint64_t));
    if (!sizesAddress || *sizesAddress == 0) {
        return std::nullopt;
    }
    return readValue<TypeSizes>(*sizesAddress);
}

std::optional<std::int64_t> RuntimeMetadataResolver::fieldOffset(
    std::int32_t typeIndex,
    std::size_t fieldIndex) const {
    const auto offsets = fieldOffsets(typeIndex);
    if (!offsets || fieldIndex >= offsets->size()) {
        return std::nullopt;
    }
    return static_cast<std::int64_t>((*offsets)[fieldIndex]);
}

std::shared_ptr<const std::vector<std::int32_t>> RuntimeMetadataResolver::fieldOffsets(
    std::int32_t typeIndex) const {
    if (metadataRegistration_.fieldOffsets == 0 || typeIndex < 0 ||
        static_cast<std::size_t>(typeIndex) >= model_->types().size()) {
        return nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        const auto cached = fieldOffsetCache_.find(typeIndex);
        if (cached != fieldOffsetCache_.end()) {
            return cached->second;
        }
    }
    const auto& fields = model_->types()[static_cast<std::size_t>(typeIndex)].fields;
    std::size_t byteCount = 0;
    if (fields.empty() || !checkedSize(fields.size(), sizeof(std::int32_t), byteCount)) {
        return nullptr;
    }
    const auto address = readValue<std::uint64_t>(
        metadataRegistration_.fieldOffsets +
        static_cast<std::uint64_t>(typeIndex) * sizeof(std::uint64_t));
    if (!address || *address == 0) {
        return nullptr;
    }
    auto result = std::make_shared<std::vector<std::int32_t>>(fields.size());
    if (!readBytes(*address, result->data(), byteCount)) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(cacheMutex_);
    return fieldOffsetCache_.emplace(typeIndex, std::move(result)).first->second;
}

std::optional<std::int32_t> RuntimeMetadataResolver::fieldFlags(const FieldMetadata& field) const {
    if (metadataRegistration_.types == 0 || field.typeIndex < 0 ||
        static_cast<std::uint32_t>(field.typeIndex) >= metadataRegistration_.typesCount) {
        return std::nullopt;
    }
    const auto typeAddress = readValue<std::uint64_t>(
        metadataRegistration_.types + static_cast<std::uint64_t>(field.typeIndex) * sizeof(std::uint64_t));
    if (!typeAddress || *typeAddress == 0) {
        return std::nullopt;
    }
    const auto bits = readValue<std::uint32_t>(*typeAddress + kTypeBitsOffset);
    return bits ? std::optional<std::int32_t>(*bits & 0xFFFFU) : std::nullopt;
}

std::optional<std::string> RuntimeMetadataResolver::methodSignature(
    const MethodMetadata& method) const {
    const auto returnTypeName = typeName(method.returnTypeIndex);
    if (!returnTypeName) {
        return std::nullopt;
    }
    std::string signature;
    if (!appendSignaturePart(signature, *returnTypeName) ||
        !appendSignaturePart(signature, " ") ||
        !appendSignaturePart(signature, method.name)) {
        return std::nullopt;
    }
    if (method.genericContainerIndex >= 0) {
        const auto containerIndex = static_cast<std::size_t>(method.genericContainerIndex);
        if (containerIndex >= model_->genericContainers().size()) {
            return std::nullopt;
        }
        const auto& container = model_->genericContainers()[containerIndex];
        if (!container.isMethod || container.ownerIndex != method.definitionIndex ||
            container.parameterCount <= 0 || container.parameterStart < 0) {
            return std::nullopt;
        }
        if (!appendSignaturePart(signature, "<")) {
            return std::nullopt;
        }
        for (std::size_t index = 0;
             index < static_cast<std::size_t>(container.parameterCount);
             ++index) {
            const auto parameterIndex = static_cast<std::size_t>(container.parameterStart) + index;
            if (parameterIndex >= model_->genericParameters().size()) {
                return std::nullopt;
            }
            if (index != 0) {
                if (!appendSignaturePart(signature, ", ")) {
                    return std::nullopt;
                }
            }
            if (!appendSignaturePart(signature, model_->genericParameters()[parameterIndex].name)) {
                return std::nullopt;
            }
        }
        if (!appendSignaturePart(signature, ">")) {
            return std::nullopt;
        }
    }
    if (!appendSignaturePart(signature, "(")) {
        return std::nullopt;
    }
    for (std::size_t index = 0; index < method.parameters.size(); ++index) {
        if (index != 0) {
            if (!appendSignaturePart(signature, ", ")) {
                return std::nullopt;
            }
        }
        const auto& parameter = method.parameters[index];
        const auto parameterTypeName = typeName(parameter.typeIndex);
        if (!parameterTypeName) {
            return std::nullopt;
        }
        if (!appendSignaturePart(signature, *parameterTypeName)) {
            return std::nullopt;
        }
        if (!parameter.name.empty()) {
            if (!appendSignaturePart(signature, " ") ||
                !appendSignaturePart(signature, parameter.name)) {
                return std::nullopt;
            }
        }
    }
    if (!appendSignaturePart(signature, ")")) {
        return std::nullopt;
    }
    return std::optional(std::move(signature));
}

std::optional<std::uint64_t> RuntimeMetadataResolver::methodAddress(
    const TypeMetadata& type,
    const MethodMetadata& method) const {
    if ((method.token & kTokenTableMask) != kMethodTokenTable) {
        return std::nullopt;
    }
    const auto module = codeGenModules_.find(type.imageName);
    if (module == codeGenModules_.end()) {
        return std::nullopt;
    }
    const auto rid = method.token & kTokenRidMask;
    if (rid == 0 || rid > module->second.methodPointerCount) {
        return std::nullopt;
    }
    std::optional<std::uint64_t> pointer;
    if (const auto pointers = methodPointers(type.imageName)) {
        pointer = (*pointers)[static_cast<std::size_t>(rid - 1)];
    } else {
        pointer = readValue<std::uint64_t>(
            module->second.methodPointers +
            static_cast<std::uint64_t>(rid - 1) * sizeof(std::uint64_t));
    }
    return pointer && *pointer != 0 && isMapped(*pointer, 1) ? pointer : std::nullopt;
}

bool RuntimeMetadataResolver::methodMatchesAddress(
    const TypeMetadata& type,
    const MethodMetadata& method,
    std::uint64_t query) const {
    const auto address = methodAddress(type, method);
    if (!address) {
        return false;
    }
    if (*address == query) {
        return true;
    }
    const auto rva = moduleRva(*address);
    return rva && *rva == query;
}

std::shared_ptr<const std::vector<std::uint64_t>> RuntimeMetadataResolver::methodPointers(
    const std::string& imageName) const {
    {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        const auto cached = methodPointerCache_.find(imageName);
        if (cached != methodPointerCache_.end()) {
            return cached->second;
        }
    }
    const auto module = codeGenModules_.find(imageName);
    if (module == codeGenModules_.end()) {
        return nullptr;
    }
    std::size_t byteCount = 0;
    if (!checkedSize(module->second.methodPointerCount, sizeof(std::uint64_t), byteCount) ||
        byteCount == 0 || byteCount > kMaximumCachedMethodPointerBytes) {
        return nullptr;
    }
    auto result = std::make_shared<std::vector<std::uint64_t>>(
        static_cast<std::size_t>(module->second.methodPointerCount));
    if (!readBytes(module->second.methodPointers, result->data(), byteCount)) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(cacheMutex_);
    return methodPointerCache_.emplace(imageName, std::move(result)).first->second;
}

std::shared_ptr<const NativeCodeSnapshot> RuntimeMetadataResolver::nativeCodeSnapshot() const {
    if (!moduleArchitecture_) {
        return nullptr;
    }

    auto snapshot = std::make_shared<NativeCodeSnapshot>();
    snapshot->pid = pid_;
    snapshot->moduleBase = moduleBase_;
    snapshot->architecture = *moduleArchitecture_;

    for (const auto& region : moduleRegions_) {
        if (region.executable) {
            snapshot->executableRegions.push_back(region);
            continue;
        }
        if (moduleHasExecutableMapping_ || !region.readable || executableFileRanges_.empty() ||
            region.end - region.start >
                std::numeric_limits<std::uint64_t>::max() - region.fileOffset) {
            continue;
        }
        const auto fileEnd = region.fileOffset + region.end - region.start;
        for (const auto& range : executableFileRanges_) {
            const auto intersectionStart = std::max(region.fileOffset, range.first);
            const auto intersectionEnd = std::min(fileEnd, range.second);
            if (intersectionStart >= intersectionEnd) {
                continue;
            }
            snapshot->executableRegions.push_back({
                region.start + intersectionStart - region.fileOffset,
                region.start + intersectionEnd - region.fileOffset,
                intersectionStart,
                true,
                false,
                true,
                region.path,
            });
        }
    }

    std::sort(
        snapshot->executableRegions.begin(),
        snapshot->executableRegions.end(),
        [](const ProcessMapRegion& left, const ProcessMapRegion& right) {
            return left.start < right.start;
        });
    std::vector<ProcessMapRegion> mergedRegions;
    mergedRegions.reserve(snapshot->executableRegions.size());
    for (const auto& region : snapshot->executableRegions) {
        if (!mergedRegions.empty() && region.start <= mergedRegions.back().end) {
            mergedRegions.back().end = std::max(mergedRegions.back().end, region.end);
        } else {
            mergedRegions.push_back(region);
        }
    }
    snapshot->executableRegions = std::move(mergedRegions);
    if (snapshot->executableRegions.empty()) {
        return nullptr;
    }

    std::unordered_map<
        std::string,
        std::shared_ptr<const std::vector<std::uint64_t>>> pointersByImage;
    pointersByImage.reserve(codeGenModules_.size());
    for (std::size_t classIndex = 0; classIndex < model_->types().size(); ++classIndex) {
        if (classIndex > static_cast<std::size_t>(std::numeric_limits<std::int32_t>::max())) {
            break;
        }
        const auto& type = model_->types()[classIndex];
        const auto module = codeGenModules_.find(type.imageName);
        if (module == codeGenModules_.end()) {
            continue;
        }
        auto pointers = pointersByImage.find(type.imageName);
        if (pointers == pointersByImage.end()) {
            pointers = pointersByImage.emplace(
                type.imageName,
                methodPointers(type.imageName)).first;
        }
        for (std::size_t methodIndex = 0; methodIndex < type.methods.size(); ++methodIndex) {
            if (methodIndex > static_cast<std::size_t>(std::numeric_limits<std::int32_t>::max())) {
                break;
            }
            const auto& method = type.methods[methodIndex];
            if ((method.token & kTokenTableMask) != kMethodTokenTable) {
                continue;
            }
            const auto rid = method.token & kTokenRidMask;
            if (rid == 0 || rid > module->second.methodPointerCount) {
                continue;
            }
            const auto address = pointers->second != nullptr
                                     ? std::optional(
                                           (*pointers->second)[static_cast<std::size_t>(rid - 1)])
                                     : methodAddress(type, method);
            if (!address || *address == 0 || !isExecutableModuleAddress(*address) ||
                !containsAddress(snapshot->executableRegions, *address)) {
                continue;
            }
            snapshot->methods.push_back({
                static_cast<std::int32_t>(classIndex),
                static_cast<std::int32_t>(methodIndex),
                *address,
            });
        }
    }
    std::sort(
        snapshot->methods.begin(),
        snapshot->methods.end(),
        [](const NativeMethodSymbol& left, const NativeMethodSymbol& right) {
            return std::tie(left.address, left.classIndex, left.methodIndex) <
                   std::tie(right.address, right.classIndex, right.methodIndex);
        });
    return snapshot->methods.empty() ? nullptr : snapshot;
}

std::shared_ptr<DirectCallAnalyzer> RuntimeMetadataResolver::directCallAnalyzer() const {
    std::call_once(directCallAnalyzerOnce_, [this] {
        auto snapshot = nativeCodeSnapshot();
        if (snapshot != nullptr) {
            directCallAnalyzer_ = std::make_shared<DirectCallAnalyzer>(std::move(snapshot));
        }
    });
    return directCallAnalyzer_;
}

std::optional<std::uint64_t> RuntimeMetadataResolver::moduleRva(
    std::uint64_t address) const noexcept {
    return moduleBase_ != 0 && address >= moduleBase_ && isExecutableModuleAddress(address)
               ? std::optional(address - moduleBase_)
               : std::nullopt;
}

RuntimeMetadataResolver::MethodReference RuntimeMetadataResolver::enrichMethodReference(
    const NativeMethodSymbol& symbol,
    std::uint64_t callSiteAddress,
    std::int32_t callSiteInstructionIndex) const {
    MethodReference reference;
    reference.address = symbol.address;
    reference.rva = moduleRva(symbol.address);
    reference.callSiteAddress = callSiteAddress;
    reference.callSiteRva = moduleRva(callSiteAddress);
    reference.callSiteInstructionIndex = callSiteInstructionIndex;
    if (symbol.classIndex >= 0 &&
        static_cast<std::size_t>(symbol.classIndex) < model_->types().size()) {
        const auto& type = model_->types()[static_cast<std::size_t>(symbol.classIndex)];
        if (symbol.methodIndex >= 0 &&
            static_cast<std::size_t>(symbol.methodIndex) < type.methods.size()) {
            const auto& method = type.methods[static_cast<std::size_t>(symbol.methodIndex)];
            reference.classIndex = symbol.classIndex;
            reference.methodIndex = symbol.methodIndex;
            reference.name = method.name;
            reference.ownerName = typeDefinitionName(symbol.classIndex);
            reference.signature = methodSignature(method);
        }
    }
    return reference;
}

RuntimeMetadataResolver::MethodReferencePage RuntimeMetadataResolver::enrichMethodReferences(
    DirectMethodReferencePage page) const {
    MethodReferencePage result;
    result.totalCount = page.totalCount;
    result.status = page.status;
    result.indirectCallCount = page.indirectCallCount;
    result.items.reserve(page.items.size());

    for (const auto& item : page.items) {
        result.items.push_back(enrichMethodReference(
            item.method,
            item.callSiteAddress,
            item.callSiteInstructionIndex));
    }
    return result;
}

RuntimeMetadataResolver::MethodReferencePage RuntimeMetadataResolver::methodCalls(
    std::int32_t classIndex,
    std::int32_t methodIndex,
    std::size_t offset,
    std::size_t limit) const {
    const auto analyzer = directCallAnalyzer();
    return analyzer == nullptr
               ? MethodReferencePage{}
               : enrichMethodReferences(
                     analyzer->methodCalls(classIndex, methodIndex, offset, limit));
}

RuntimeMetadataResolver::MethodReferencePage RuntimeMetadataResolver::methodCallers(
    std::int32_t classIndex,
    std::int32_t methodIndex,
    std::size_t offset,
    std::size_t limit) const {
    const auto analyzer = directCallAnalyzer();
    return analyzer == nullptr
               ? MethodReferencePage{}
               : enrichMethodReferences(
                     analyzer->methodCallers(classIndex, methodIndex, offset, limit));
}

RuntimeMetadataResolver::InstructionPage RuntimeMetadataResolver::methodInstructions(
    std::int32_t classIndex,
    std::int32_t methodIndex,
    std::size_t offset,
    std::size_t limit) const {
    const auto analyzer = directCallAnalyzer();
    if (analyzer == nullptr) {
        return {};
    }
    const auto page = analyzer->methodInstructions(classIndex, methodIndex, offset, limit);
    InstructionPage result;
    result.totalCount = page.totalCount;
    result.status = page.status;
    result.indirectCallCount = page.indirectCallCount;
    result.items.reserve(page.items.size());
    for (std::size_t index = 0; index < page.items.size(); ++index) {
        const auto& item = page.items[index];
        Instruction instruction;
        instruction.address = item.address;
        instruction.rva = moduleRva(item.address);
        instruction.bytes = item.bytes;
        instruction.mnemonic = item.mnemonic;
        instruction.operands = item.operands;
        instruction.flow = item.flow;
        instruction.targetInstructionIndex = item.targetInstructionIndex;
        if (item.target.address != 0) {
            instruction.target = enrichMethodReference(
                item.target,
                item.address,
                static_cast<std::int32_t>(offset + index));
        }
        result.items.push_back(std::move(instruction));
    }
    return result;
}

bool RuntimeMetadataResolver::isExecutableModuleAddress(std::uint64_t address) const noexcept {
    for (const auto& region : moduleRegions_) {
        if (address < region.start || address >= region.end) {
            continue;
        }
        if (region.executable) {
            return true;
        }
        if (moduleHasExecutableMapping_ || !region.readable || executableFileRanges_.empty() ||
            address - region.start > std::numeric_limits<std::uint64_t>::max() - region.fileOffset) {
            return false;
        }
        const auto fileOffset = region.fileOffset + address - region.start;
        return std::any_of(
            executableFileRanges_.begin(),
            executableFileRanges_.end(),
            [fileOffset](const auto& range) {
                return fileOffset >= range.first && fileOffset < range.second;
            });
    }
    return false;
}

std::optional<std::string> RuntimeMetadataResolver::typeName(std::int32_t typeIndex) const {
    if (metadataRegistration_.types == 0 || typeIndex < 0 ||
        static_cast<std::uint32_t>(typeIndex) >= metadataRegistration_.typesCount) {
        return std::nullopt;
    }
    {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        const auto cached = typeNameCache_.find(typeIndex);
        if (cached != typeNameCache_.end()) {
            return cached->second;
        }
    }
    const auto typeAddress = readValue<std::uint64_t>(
        metadataRegistration_.types + static_cast<std::uint64_t>(typeIndex) * sizeof(std::uint64_t));
    if (!typeAddress || *typeAddress == 0) {
        return std::nullopt;
    }
    std::unordered_set<std::uint64_t> visited;
    auto resolved = typeNameAt(*typeAddress, 0, visited);
    if (resolved) {
        std::lock_guard<std::mutex> lock(cacheMutex_);
        typeNameCache_.emplace(typeIndex, *resolved);
    }
    return resolved;
}

std::optional<std::string> RuntimeMetadataResolver::typeNameAt(
    std::uint64_t typeAddress,
    std::size_t depth,
    std::unordered_set<std::uint64_t>& visited) const {
    if (depth >= kMaximumTypeDepth || !visited.insert(typeAddress).second) {
        return std::nullopt;
    }
    const auto data = readValue<std::uint64_t>(typeAddress + kTypeDataOffset);
    const auto bits = readValue<std::uint32_t>(typeAddress + kTypeBitsOffset);
    if (!data || !bits) {
        return std::nullopt;
    }
    const auto kind = static_cast<TypeKind>((*bits >> 16U) & 0xFFU);
    auto result = primitiveName(kind);
    switch (kind) {
        case TypeKind::Class:
        case TypeKind::ValueType:
        case TypeKind::Enum: {
            const auto index = typeDefinitionIndex(*data);
            result = index ? std::optional(typeDefinitionName(*index)) : std::nullopt;
            break;
        }
        case TypeKind::Pointer:
        case TypeKind::ByReference:
        case TypeKind::SzArray: {
            result = typeNameAt(*data, depth + 1, visited);
            if (result) {
                result->append(kind == TypeKind::Pointer ? "*" : kind == TypeKind::ByReference ? "&" : "[]");
            }
            break;
        }
        case TypeKind::Array: {
            const auto elementType = readValue<std::uint64_t>(*data);
            const auto rank = readValue<std::uint8_t>(*data + 8);
            result = elementType ? typeNameAt(*elementType, depth + 1, visited) : std::nullopt;
            if (result && rank && *rank > 0 && *rank <= 32) {
                result->push_back('[');
                result->append(*rank - 1, ',');
                result->push_back(']');
            } else if (result) {
                result.reset();
            }
            break;
        }
        case TypeKind::GenericInstance:
            result = genericTypeName(*data, depth + 1, visited);
            break;
        case TypeKind::TypeVariable:
        case TypeKind::MethodVariable:
            result = genericParameterName(*data, kind == TypeKind::MethodVariable);
            break;
        case TypeKind::FunctionPointer:
            result = std::nullopt;
            break;
        default:
            break;
    }
    if (result && kind != TypeKind::ByReference) {
        const auto bit = model_->runtimeTypeByReferenceBit();
        if (bit && ((*bits >> *bit) & 1U) != 0U) {
            result->push_back('&');
        }
    }
    visited.erase(typeAddress);
    return result;
}

std::optional<std::string> RuntimeMetadataResolver::genericTypeName(
    std::uint64_t genericClassAddress,
    std::size_t depth,
    std::unordered_set<std::uint64_t>& visited) const {
    const auto typeHandle = readValue<std::uint64_t>(genericClassAddress);
    const auto classInstance = readValue<std::uint64_t>(genericClassAddress + 8);
    if (!typeHandle || !classInstance) {
        return std::nullopt;
    }
    auto baseName = typeNameAt(*typeHandle, depth + 1, visited);
    if (!baseName) {
        return std::nullopt;
    }
    auto name = std::move(*baseName);
    if (*classInstance == 0) {
        return std::nullopt;
    }
    const auto argumentCount = readValue<std::uint64_t>(*classInstance);
    const auto arguments = readValue<std::uint64_t>(*classInstance + 8);
    if (!argumentCount || !arguments || *argumentCount == 0 || *argumentCount > 64) {
        return std::nullopt;
    }
    const auto arityMarker = name.rfind('`');
    if (arityMarker == std::string::npos || arityMarker + 1 >= name.size()) {
        return std::nullopt;
    }
    std::uint64_t arity = 0;
    for (auto index = arityMarker + 1; index < name.size(); ++index) {
        const auto character = name[index];
        if (character < '0' || character > '9') {
            return std::nullopt;
        }
        arity = arity * 10U + static_cast<std::uint64_t>(character - '0');
        if (arity > 64) {
            return std::nullopt;
        }
    }
    if (arity != *argumentCount) {
        return std::nullopt;
    }
    name.resize(arityMarker);
    name.push_back('<');
    for (std::size_t index = 0; index < *argumentCount; ++index) {
        if (index != 0) {
            name.append(", ");
        }
        const auto argument = readValue<std::uint64_t>(*arguments + index * sizeof(std::uint64_t));
        if (!argument) {
            return std::nullopt;
        }
        auto argumentName = typeNameAt(*argument, depth + 1, visited);
        if (!argumentName) {
            return std::nullopt;
        }
        name.append(*argumentName);
    }
    name.push_back('>');
    return name;
}

std::optional<std::int32_t> RuntimeMetadataResolver::typeDefinitionIndex(
    std::uint64_t handle) const {
    if (handle < model_->types().size()) {
        return static_cast<std::int32_t>(handle);
    }
    std::array<std::uint8_t, 64> bytes{};
    const auto fieldStartOffset = model_->typeDefinitionFieldStartOffset();
    if (fieldStartOffset > bytes.size() - sizeof(std::int32_t) * 2 ||
        !readBytes(handle, bytes.data(), fieldStartOffset + sizeof(std::int32_t) * 2)) {
        return std::nullopt;
    }
    const TypeDefinitionKey key{
        load<std::int32_t>(bytes.data(), 0),
        load<std::int32_t>(bytes.data(), 4),
        load<std::int32_t>(bytes.data(), fieldStartOffset),
        load<std::int32_t>(bytes.data(), fieldStartOffset + sizeof(std::int32_t)),
    };
    const auto found = typeDefinitionIndices_.find(key);
    return found == typeDefinitionIndices_.end() ? std::nullopt : std::optional(found->second);
}

std::optional<std::int32_t> RuntimeMetadataResolver::genericParameterIndex(
    std::uint64_t handle) const {
    if (handle < model_->genericParameters().size()) {
        return static_cast<std::int32_t>(handle);
    }
    std::array<std::uint8_t, 16> bytes{};
    if (!readBytes(handle, bytes.data(), bytes.size())) {
        return std::nullopt;
    }
    const GenericParameterKey key{
        load<std::int32_t>(bytes.data(), 0),
        load<std::int32_t>(bytes.data(), 4),
        load<std::int16_t>(bytes.data(), 8),
        load<std::int16_t>(bytes.data(), 10),
        load<std::uint16_t>(bytes.data(), 12),
        load<std::uint16_t>(bytes.data(), 14),
    };
    const auto found = genericParameterIndices_.find(key);
    return found == genericParameterIndices_.end() ? std::nullopt : std::optional(found->second);
}

std::optional<std::string> RuntimeMetadataResolver::genericParameterName(
    std::uint64_t handle,
    bool methodParameter) const {
    const auto index = genericParameterIndex(handle);
    if (!index) {
        return std::nullopt;
    }
    const auto& parameter = model_->genericParameters()[static_cast<std::size_t>(*index)];
    if (parameter.ownerIndex < 0 ||
        static_cast<std::size_t>(parameter.ownerIndex) >= model_->genericContainers().size() ||
        model_->genericContainers()[static_cast<std::size_t>(parameter.ownerIndex)].isMethod !=
            methodParameter) {
        return std::nullopt;
    }
    return parameter.name;
}

std::optional<std::int32_t> RuntimeMetadataResolver::typeDefinitionIndexAt(
    std::uint64_t typeAddress,
    std::size_t depth,
    std::unordered_set<std::uint64_t>& visited) const {
    if (depth >= kMaximumTypeDepth || !visited.insert(typeAddress).second) {
        return std::nullopt;
    }
    const auto data = readValue<std::uint64_t>(typeAddress + kTypeDataOffset);
    const auto bits = readValue<std::uint32_t>(typeAddress + kTypeBitsOffset);
    if (!data || !bits) {
        visited.erase(typeAddress);
        return std::nullopt;
    }
    const auto kind = static_cast<TypeKind>((*bits >> 16U) & 0xFFU);
    std::optional<std::int32_t> result;
    switch (kind) {
        case TypeKind::Class:
        case TypeKind::ValueType:
        case TypeKind::Enum:
            result = typeDefinitionIndex(*data);
            break;
        case TypeKind::GenericInstance: {
            const auto definitionType = readValue<std::uint64_t>(*data);
            if (definitionType && *definitionType != 0) {
                result = typeDefinitionIndexAt(*definitionType, depth + 1, visited);
            }
            break;
        }
        case TypeKind::Pointer:
        case TypeKind::ByReference:
        case TypeKind::SzArray:
            result = typeDefinitionIndexAt(*data, depth + 1, visited);
            break;
        case TypeKind::Array: {
            const auto elementType = readValue<std::uint64_t>(*data);
            if (elementType && *elementType != 0) {
                result = typeDefinitionIndexAt(*elementType, depth + 1, visited);
            }
            break;
        }
        default:
            break;
    }
    visited.erase(typeAddress);
    return result;
}

std::string RuntimeMetadataResolver::typeDefinitionName(std::int32_t index) const {
    if (index < 0 || static_cast<std::size_t>(index) >= model_->types().size()) {
        return "?";
    }
    std::vector<std::string_view> names;
    std::unordered_set<std::int32_t> visited;
    std::string namespaze;
    auto current = index;
    while (current >= 0 && static_cast<std::size_t>(current) < model_->types().size() &&
           visited.insert(current).second) {
        const auto& type = model_->types()[static_cast<std::size_t>(current)];
        names.push_back(type.name);
        if (!type.namespaze.empty()) {
            namespaze = type.namespaze;
        }
        current = type.declaringTypeDefinitionIndex;
    }
    std::string name;
    if (!namespaze.empty()) {
        name.append(namespaze);
        name.push_back('.');
    }
    for (auto iterator = names.rbegin(); iterator != names.rend(); ++iterator) {
        if (iterator != names.rbegin()) {
            name.push_back('+');
        }
        name.append(*iterator);
    }
    return name;
}

std::vector<std::int32_t> RuntimeMetadataResolver::referencedTypeIndices() const {
    std::vector<std::int32_t> result;
    result.reserve(128);
    const auto append = [&result](std::int32_t typeIndex) {
        if (typeIndex >= 0 && result.size() < 128) {
            result.push_back(typeIndex);
        }
    };
    for (const auto& type : model_->types()) {
        append(type.byValueTypeIndex);
        append(type.declaringTypeIndex);
        append(type.parentTypeIndex);
        for (const auto interfaceTypeIndex : type.interfaceTypeIndices) {
            append(interfaceTypeIndex);
        }
        for (const auto& field : type.fields) {
            append(field.typeIndex);
        }
        for (const auto& method : type.methods) {
            append(method.returnTypeIndex);
            for (const auto& parameter : method.parameters) {
                append(parameter.typeIndex);
            }
        }
        for (const auto& event : type.events) {
            append(event.typeIndex);
        }
        if (result.size() >= 128) {
            break;
        }
    }
    return result;
}

}
