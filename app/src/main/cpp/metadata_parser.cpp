#include "metadata_parser.h"

#include <algorithm>
#include <array>
#include <limits>
#include <optional>
#include <string_view>
#include <unordered_map>
#include <utility>

namespace il2cppmanager {

struct MetadataModelAccess {
    static std::shared_ptr<MetadataModel> create(
        std::size_t typeCount,
        std::size_t typeDefinitionFieldStartOffset,
        bool supportsRuntimeMetadata,
        std::int8_t runtimeTypeByReferenceBit) {
        auto model = std::shared_ptr<MetadataModel>(new MetadataModel());
        model->typeDefinitionFieldStartOffset_ = typeDefinitionFieldStartOffset;
        model->supportsRuntimeMetadata_ = supportsRuntimeMetadata;
        if (runtimeTypeByReferenceBit >= 0) {
            model->runtimeTypeByReferenceBit_ =
                static_cast<std::uint8_t>(runtimeTypeByReferenceBit);
        }
        model->types_.resize(typeCount);
        return model;
    }

    static std::vector<TypeMetadata>& types(MetadataModel& model) noexcept {
        return model.types_;
    }

    static std::vector<AssemblyMetadata>& assemblies(MetadataModel& model) noexcept {
        return model.assemblies_;
    }

    static std::vector<std::string>& imageNames(MetadataModel& model) noexcept {
        return model.imageNames_;
    }

    static std::vector<GenericParameterMetadata>& genericParameters(MetadataModel& model) noexcept {
        return model.genericParameters_;
    }

    static std::vector<GenericContainerMetadata>& genericContainers(MetadataModel& model) noexcept {
        return model.genericContainers_;
    }

    static std::unordered_map<std::int32_t, std::int32_t>& typeDefinitionIndicesByTypeIndex(
        MetadataModel& model) noexcept {
        return model.typeDefinitionIndicesByTypeIndex_;
    }
};

namespace {

constexpr std::size_t kHeaderPrefixSize = 8;
constexpr std::size_t kMaximumHeaderSectionCount = 41;
constexpr std::uint32_t kErasedMetadataMagic = 0;
constexpr std::size_t kMaximumDefinitionCount = 4U * 1024U * 1024U;
constexpr std::size_t kMaximumAssemblyCount = 64U * 1024U;
constexpr std::size_t kMaximumMetadataStringBytes = 1024U * 1024U;

struct Section {
    std::size_t offset = 0;
    std::size_t size = 0;
    std::size_t count = 0;
    bool hasCount = false;
};

struct TypeDefinitionLayout {
    std::size_t size;
    std::size_t byValueTypeIndex;
    std::size_t declaringTypeIndex;
    std::size_t parentTypeIndex;
    std::size_t genericContainerIndex;
    std::size_t flags;
    std::size_t fieldStart;
    std::size_t methodStart;
    std::size_t eventStart;
    std::size_t propertyStart;
    std::size_t nestedTypeStart;
    std::size_t interfaceStart;
    std::size_t methodCount;
    std::size_t propertyCount;
    std::size_t fieldCount;
    std::size_t eventCount;
    std::size_t nestedTypeCount;
    std::size_t interfaceCount;
    std::size_t bitfield;
    std::size_t token;
};

struct MethodDefinitionLayout {
    std::size_t size;
    std::size_t declaringTypeIndex;
    std::size_t returnTypeIndex;
    std::size_t parameterStart;
    std::size_t genericContainerIndex;
    std::size_t token;
    std::size_t flags;
    std::size_t parameterCount;
};

struct FieldDefinitionLayout {
    std::size_t size;
    std::size_t typeIndex;
    std::size_t token;
};

struct ParameterDefinitionLayout {
    std::size_t size;
    std::size_t token;
    std::size_t typeIndex;
};

struct PropertyDefinitionLayout {
    std::size_t size;
    std::size_t getterIndex;
    std::size_t setterIndex;
    std::size_t attributes;
    std::size_t token;
};

struct EventDefinitionLayout {
    std::size_t size;
    std::size_t typeIndex;
    std::size_t addIndex;
    std::size_t removeIndex;
    std::size_t raiseIndex;
    std::size_t token;
};

struct GenericParameterDefinitionLayout {
    std::size_t size;
    std::size_t ownerIndex;
    std::size_t nameIndex;
    std::size_t constraintsStart;
    std::size_t constraintsCount;
    std::size_t number;
    std::size_t flags;
};

struct GenericContainerDefinitionLayout {
    std::size_t size;
    std::size_t parameterCount;
    std::size_t parameterCountSize;
    std::size_t isMethod;
    std::size_t isMethodSize;
    std::size_t parameterStart;
};

struct ImageDefinitionLayout {
    std::size_t size;
    std::size_t typeStart;
    std::size_t typeCount;
};

struct AssemblyDefinitionLayout {
    std::size_t size;
    std::size_t nameIndex;
};

struct IndexWidths {
    std::size_t type = 4;
    std::size_t typeDefinition = 4;
    std::size_t genericContainer = 4;
    std::size_t field = 4;
    std::size_t method = 4;
    std::size_t event = 4;
    std::size_t property = 4;
    std::size_t nestedType = 4;
    std::size_t interface = 4;
    std::size_t parameter = 4;
    std::size_t genericParameter = 4;
};

struct Schema {
    std::int32_t version;
    bool supportsRuntimeMetadata;
    std::int8_t runtimeTypeByReferenceBit;
    std::size_t headerSectionCount;
    std::size_t headerSectionSize;
    TypeDefinitionLayout type;
    MethodDefinitionLayout method;
    FieldDefinitionLayout field;
    ParameterDefinitionLayout parameter;
    PropertyDefinitionLayout property;
    EventDefinitionLayout event;
    GenericParameterDefinitionLayout genericParameter;
    GenericContainerDefinitionLayout genericContainer;
    IndexWidths widths;
    ImageDefinitionLayout image;
    AssemblyDefinitionLayout assembly;
    std::size_t typeDefinitionsSectionIndex;
    std::size_t imagesSectionIndex;
    std::size_t assembliesSectionIndex;
};

constexpr TypeDefinitionLayout kVersion23Type{
    104, 12, 20, 24, 40, 44, 48, 52, 56, 60, 64, 68, 80, 82, 84, 86, 88, 92, 96, 100,
};
constexpr TypeDefinitionLayout kVersion24_1Type{
    100, 8, 16, 20, 36, 40, 44, 48, 52, 56, 60, 64, 76, 78, 80, 82, 84, 88, 92, 96,
};
constexpr TypeDefinitionLayout kVersion24_2Type{
    92, 8, 16, 20, 28, 32, 36, 40, 44, 48, 52, 56, 68, 70, 72, 74, 76, 80, 84, 88,
};
constexpr TypeDefinitionLayout kVersion27Type{
    88, 8, 12, 16, 24, 28, 32, 36, 40, 44, 48, 52, 64, 66, 68, 70, 72, 76, 80, 84,
};
constexpr TypeDefinitionLayout kVersion35Type{
    84, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 60, 62, 64, 66, 68, 72, 76, 80,
};
constexpr MethodDefinitionLayout kVersion23Method{56, 4, 8, 12, 20, 44, 48, 54};
constexpr MethodDefinitionLayout kVersion24_1Method{52, 4, 8, 12, 16, 40, 44, 50};
constexpr MethodDefinitionLayout kVersion27Method{32, 4, 8, 12, 16, 20, 24, 30};
constexpr MethodDefinitionLayout kVersion31Method{36, 4, 8, 16, 20, 24, 28, 34};
constexpr PropertyDefinitionLayout kVersion23Property{24, 4, 8, 12, 20};
constexpr PropertyDefinitionLayout kVersion27Property{20, 4, 8, 12, 16};
constexpr EventDefinitionLayout kVersion23Event{28, 4, 8, 12, 16, 24};
constexpr EventDefinitionLayout kVersion27Event{24, 4, 8, 12, 16, 20};
constexpr GenericParameterDefinitionLayout kLegacyGenericParameter{16, 0, 4, 8, 10, 12, 14};
constexpr GenericContainerDefinitionLayout kLegacyGenericContainer{16, 4, 4, 8, 4, 12};
constexpr IndexWidths kLegacyIndexWidths{};
constexpr ImageDefinitionLayout kVersion23Image{24, 8, 12};
constexpr ImageDefinitionLayout kVersion24Image{32, 8, 12};
constexpr ImageDefinitionLayout kVersion27Image{40, 8, 12};
constexpr AssemblyDefinitionLayout kVersion23Assembly{68, 16};
constexpr AssemblyDefinitionLayout kVersion27Assembly{64, 16};

constexpr std::array<Schema, 11> kLegacySchemas{{
    {23, false, -1, 32, 8, kVersion23Type, kVersion23Method, {16, 4, 12}, {16, 4, 12}, kVersion23Property, kVersion23Event, kLegacyGenericParameter, kLegacyGenericContainer, kLegacyIndexWidths, kVersion23Image, kVersion23Assembly, 19, 21, 22},
    {24, false, -1, 33, 8, kVersion23Type, kVersion23Method, {16, 4, 12}, {16, 4, 12}, kVersion23Property, kVersion23Event, kLegacyGenericParameter, kLegacyGenericContainer, kLegacyIndexWidths, kVersion24Image, kVersion23Assembly, 19, 21, 22},
    {24, false, -1, 33, 8, kVersion24_1Type, kVersion24_1Method, {12, 4, 8}, {12, 4, 8}, kVersion27Property, kVersion27Event, kLegacyGenericParameter, kLegacyGenericContainer, kLegacyIndexWidths, kVersion27Image, kVersion23Assembly, 19, 21, 22},
    {24, false, -1, 33, 8, kVersion24_1Type, kVersion24_1Method, {12, 4, 8}, {12, 4, 8}, kVersion27Property, kVersion27Event, kLegacyGenericParameter, kLegacyGenericContainer, kLegacyIndexWidths, kVersion27Image, kVersion27Assembly, 19, 21, 22},
    {24, false, -1, 32, 8, kVersion24_2Type, kVersion27Method, {12, 4, 8}, {12, 4, 8}, kVersion27Property, kVersion27Event, kLegacyGenericParameter, kLegacyGenericContainer, kLegacyIndexWidths, kVersion27Image, kVersion23Assembly, 19, 20, 21},
    {24, false, -1, 32, 8, kVersion24_2Type, kVersion27Method, {12, 4, 8}, {12, 4, 8}, kVersion27Property, kVersion27Event, kLegacyGenericParameter, kLegacyGenericContainer, kLegacyIndexWidths, kVersion27Image, kVersion27Assembly, 19, 20, 21},
    {24, true, 29, 31, 8, kVersion27Type, kVersion31Method, {12, 4, 8}, {12, 4, 8}, kVersion27Property, kVersion27Event, kLegacyGenericParameter, kLegacyGenericContainer, kLegacyIndexWidths, kVersion27Image, kVersion27Assembly, 19, 20, 21},
    {27, true, -1, 31, 8, kVersion27Type, kVersion27Method, {12, 4, 8}, {12, 4, 8}, kVersion27Property, kVersion27Event, kLegacyGenericParameter, kLegacyGenericContainer, kLegacyIndexWidths, kVersion27Image, kVersion27Assembly, 19, 20, 21},
    {29, true, 29, 31, 8, kVersion27Type, kVersion27Method, {12, 4, 8}, {12, 4, 8}, kVersion27Property, kVersion27Event, kLegacyGenericParameter, kLegacyGenericContainer, kLegacyIndexWidths, kVersion27Image, kVersion27Assembly, 19, 20, 21},
    {31, true, 29, 31, 8, kVersion27Type, kVersion31Method, {12, 4, 8}, {12, 4, 8}, kVersion27Property, kVersion27Event, kLegacyGenericParameter, kLegacyGenericContainer, kLegacyIndexWidths, kVersion27Image, kVersion27Assembly, 19, 20, 21},
    {35, true, 29, 31, 8, kVersion35Type, kVersion31Method, {12, 4, 8}, {12, 4, 8}, kVersion27Property, kVersion27Event, kLegacyGenericParameter, kLegacyGenericContainer, kLegacyIndexWidths, kVersion27Image, kVersion27Assembly, 19, 20, 21},
}};

struct ModernSchemaDescriptor {
    std::int32_t version;
    std::size_t headerSectionCount;
    std::size_t typeDefinitionsSectionIndex;
    std::size_t imagesSectionIndex;
    std::size_t assembliesSectionIndex;
};

constexpr std::array<ModernSchemaDescriptor, 7> kModernSchemas{{
    {38, 31, 19, 20, 21},
    {39, 31, 19, 20, 21},
    {104, 32, 19, 21, 22},
    {105, 32, 19, 21, 22},
    {106, 32, 19, 21, 22},
    {107, 32, 19, 21, 22},
    {108, 41, 19, 21, 22},
}};

class ByteView final {
public:
    explicit ByteView(const std::vector<std::uint8_t>& bytes) : bytes_(bytes) {}

    bool contains(std::size_t offset, std::size_t length) const noexcept {
        return offset <= bytes_.size() && length <= bytes_.size() - offset;
    }

    bool readU16(std::size_t offset, std::uint16_t& value) const noexcept {
        if (!contains(offset, sizeof(value))) {
            return false;
        }
        value = static_cast<std::uint16_t>(bytes_[offset]) |
                static_cast<std::uint16_t>(bytes_[offset + 1]) << 8U;
        return true;
    }

    bool readU8(std::size_t offset, std::uint8_t& value) const noexcept {
        if (!contains(offset, sizeof(value))) {
            return false;
        }
        value = bytes_[offset];
        return true;
    }

    bool readI16(std::size_t offset, std::int16_t& value) const noexcept {
        std::uint16_t raw = 0;
        if (!readU16(offset, raw)) {
            return false;
        }
        value = static_cast<std::int16_t>(raw);
        return true;
    }

    bool readU32(std::size_t offset, std::uint32_t& value) const noexcept {
        if (!contains(offset, sizeof(value))) {
            return false;
        }
        value = static_cast<std::uint32_t>(bytes_[offset]) |
                static_cast<std::uint32_t>(bytes_[offset + 1]) << 8U |
                static_cast<std::uint32_t>(bytes_[offset + 2]) << 16U |
                static_cast<std::uint32_t>(bytes_[offset + 3]) << 24U;
        return true;
    }

    bool readI32(std::size_t offset, std::int32_t& value) const noexcept {
        std::uint32_t raw = 0;
        if (!readU32(offset, raw)) {
            return false;
        }
        value = static_cast<std::int32_t>(raw);
        return true;
    }

    bool readUnsigned(
        std::size_t offset,
        std::size_t width,
        std::uint32_t& value) const noexcept {
        switch (width) {
            case 1: {
                std::uint8_t raw = 0;
                if (!readU8(offset, raw)) {
                    return false;
                }
                value = raw;
                return true;
            }
            case 2: {
                std::uint16_t raw = 0;
                if (!readU16(offset, raw)) {
                    return false;
                }
                value = raw;
                return true;
            }
            case 4:
                return readU32(offset, value);
            default:
                return false;
        }
    }

    bool readIndex(
        std::size_t offset,
        std::size_t width,
        std::int32_t& value) const noexcept {
        std::uint32_t raw = 0;
        if (!readUnsigned(offset, width, raw)) {
            return false;
        }
        const auto nullValue = width == 1 ? 0xFFU : width == 2 ? 0xFFFFU : 0xFFFFFFFFU;
        if (raw == nullValue) {
            value = -1;
            return true;
        }
        if (raw > static_cast<std::uint32_t>(std::numeric_limits<std::int32_t>::max())) {
            return false;
        }
        value = static_cast<std::int32_t>(raw);
        return true;
    }

    const std::uint8_t* data(std::size_t offset) const noexcept {
        return bytes_.data() + offset;
    }

    std::size_t size() const noexcept {
        return bytes_.size();
    }

private:
    const std::vector<std::uint8_t>& bytes_;
};

struct Header {
    Section strings;
    Section events;
    Section properties;
    Section methods;
    Section parameters;
    Section fields;
    Section genericParameters;
    Section genericParameterConstraints;
    Section genericContainers;
    Section nestedTypes;
    Section interfaces;
    Section interfaceOffsets;
    Section typeDefinitions;
    Section inlineArrays;
    Section images;
    Section assemblies;
};

struct RawImage {
    std::string name;
    std::int32_t assemblyIndex = -1;
    std::int32_t typeStart = -1;
    std::uint32_t typeCount = 0;
};

bool validSectionEntries(
    const Section& section,
    std::size_t entrySize,
    std::size_t maximumCount,
    std::size_t& count) noexcept {
    if (entrySize == 0) {
        return false;
    }
    if (section.hasCount) {
        if (section.count > maximumCount ||
            section.count > std::numeric_limits<std::size_t>::max() / entrySize ||
            section.count * entrySize != section.size) {
            return false;
        }
        count = section.count;
        return true;
    }
    if (section.size % entrySize != 0) {
        return false;
    }
    count = section.size / entrySize;
    return count <= maximumCount;
}

bool validIndexRange(std::int32_t start, std::size_t count, std::size_t total) noexcept {
    if (count == 0) {
        return start >= -1;
    }
    if (start < 0) {
        return false;
    }
    const auto unsignedStart = static_cast<std::size_t>(start);
    return unsignedStart <= total && count <= total - unsignedStart;
}

bool validRelativeMethodIndex(std::int32_t index, std::size_t methodCount) noexcept {
    return index == -1 || (index >= 0 && static_cast<std::size_t>(index) < methodCount);
}

bool allClaimed(const std::vector<std::uint8_t>& entries) noexcept {
    return std::find(entries.begin(), entries.end(), std::uint8_t{0}) == entries.end();
}

std::size_t headerSize(const Schema& schema) noexcept {
    return kHeaderPrefixSize + schema.headerSectionCount * schema.headerSectionSize;
}

bool parseHeader(
    const ByteView& view,
    const Schema& schema,
    std::size_t availableBytes,
    Header& header,
    std::size_t* metadataSize = nullptr) noexcept {
    if ((schema.headerSectionSize != 8 && schema.headerSectionSize != 12) ||
        schema.headerSectionCount > kMaximumHeaderSectionCount ||
        view.size() < headerSize(schema) ||
        availableBytes < headerSize(schema)) {
        return false;
    }
    std::array<Section, kMaximumHeaderSectionCount> sections{};
    std::size_t maximumEnd = headerSize(schema);
    for (std::size_t index = 0; index < schema.headerSectionCount; ++index) {
        std::int32_t offset = 0;
        std::int32_t size = 0;
        std::int32_t count = 0;
        const auto descriptor = kHeaderPrefixSize + index * schema.headerSectionSize;
        if (!view.readI32(descriptor, offset) ||
            !view.readI32(descriptor + sizeof(std::int32_t), size) ||
            (schema.headerSectionSize == 12 &&
             (!view.readI32(descriptor + sizeof(std::int32_t) * 2, count) || count < 0)) ||
            offset < 0 || size < 0) {
            return false;
        }
        auto& section = sections[index];
        section.offset = static_cast<std::size_t>(offset);
        section.size = static_cast<std::size_t>(size);
        section.count = static_cast<std::size_t>(count);
        section.hasCount = schema.headerSectionSize == 12;
        if (section.offset > availableBytes || section.size > availableBytes - section.offset ||
            (section.size > 0 && section.offset < headerSize(schema))) {
            return false;
        }
        maximumEnd = std::max(maximumEnd, section.offset + section.size);
    }
    if (schema.typeDefinitionsSectionIndex >= schema.headerSectionCount ||
        schema.imagesSectionIndex >= schema.headerSectionCount ||
        schema.assembliesSectionIndex >= schema.headerSectionCount) {
        return false;
    }

    header.strings = sections[2];
    header.events = sections[3];
    header.properties = sections[4];
    header.methods = sections[5];
    header.parameters = sections[10];
    header.fields = sections[11];
    header.genericParameters = sections[12];
    header.genericParameterConstraints = sections[13];
    header.genericContainers = sections[14];
    header.nestedTypes = sections[15];
    header.interfaces = sections[16];
    header.interfaceOffsets = sections[18];
    header.typeDefinitions = sections[schema.typeDefinitionsSectionIndex];
    if (schema.version >= 104) {
        header.inlineArrays = sections[20];
    }
    header.images = sections[schema.imagesSectionIndex];
    header.assemblies = sections[schema.assembliesSectionIndex];
    if (metadataSize != nullptr) {
        *metadataSize = maximumEnd;
    }
    return true;
}

struct SectionCounts {
    std::size_t types = 0;
    std::size_t fields = 0;
    std::size_t methods = 0;
    std::size_t parameters = 0;
    std::size_t properties = 0;
    std::size_t events = 0;
    std::size_t genericParameters = 0;
    std::size_t genericParameterConstraints = 0;
    std::size_t genericContainers = 0;
    std::size_t nestedTypes = 0;
    std::size_t interfaces = 0;
    std::size_t images = 0;
    std::size_t assemblies = 0;
};

bool readSectionCounts(
    const Header& header,
    const Schema& schema,
    SectionCounts& counts) noexcept {
    return validSectionEntries(
               header.typeDefinitions,
               schema.type.size,
               kMaximumDefinitionCount,
               counts.types) &&
           counts.types > 0 &&
           validSectionEntries(
               header.fields,
               schema.field.size,
               kMaximumDefinitionCount,
               counts.fields) &&
           validSectionEntries(
               header.methods,
               schema.method.size,
               kMaximumDefinitionCount,
               counts.methods) &&
           validSectionEntries(
               header.parameters,
               schema.parameter.size,
               kMaximumDefinitionCount,
               counts.parameters) &&
           validSectionEntries(
               header.properties,
               schema.property.size,
               kMaximumDefinitionCount,
               counts.properties) &&
           validSectionEntries(
               header.events,
               schema.event.size,
               kMaximumDefinitionCount,
               counts.events) &&
           validSectionEntries(
               header.genericParameters,
               schema.genericParameter.size,
               kMaximumDefinitionCount,
               counts.genericParameters) &&
           validSectionEntries(
               header.genericParameterConstraints,
               schema.widths.type,
               kMaximumDefinitionCount,
               counts.genericParameterConstraints) &&
           validSectionEntries(
               header.genericContainers,
               schema.genericContainer.size,
               kMaximumDefinitionCount,
               counts.genericContainers) &&
           validSectionEntries(
               header.nestedTypes,
               sizeof(std::int32_t),
               kMaximumDefinitionCount,
               counts.nestedTypes) &&
           validSectionEntries(
               header.interfaces,
               schema.widths.type,
               kMaximumDefinitionCount,
               counts.interfaces) &&
           validSectionEntries(
               header.images,
               schema.image.size,
               kMaximumAssemblyCount,
               counts.images) &&
           counts.images > 0 &&
           validSectionEntries(
               header.assemblies,
               schema.assembly.size,
               kMaximumAssemblyCount,
               counts.assemblies) &&
           counts.assemblies == counts.images;
}

Schema modernHeaderSchema(const ModernSchemaDescriptor& descriptor) noexcept {
    Schema schema{};
    schema.version = descriptor.version;
    schema.supportsRuntimeMetadata = false;
    schema.runtimeTypeByReferenceBit = -1;
    schema.headerSectionCount = descriptor.headerSectionCount;
    schema.headerSectionSize = 12;
    schema.typeDefinitionsSectionIndex = descriptor.typeDefinitionsSectionIndex;
    schema.imagesSectionIndex = descriptor.imagesSectionIndex;
    schema.assembliesSectionIndex = descriptor.assembliesSectionIndex;
    return schema;
}

std::size_t indexWidth(std::size_t count) noexcept {
    if (count <= std::numeric_limits<std::uint8_t>::max()) {
        return sizeof(std::uint8_t);
    }
    if (count <= std::numeric_limits<std::uint16_t>::max()) {
        return sizeof(std::uint16_t);
    }
    return sizeof(std::uint32_t);
}

bool validIndexWidth(std::size_t width) noexcept {
    return width == sizeof(std::uint8_t) ||
           width == sizeof(std::uint16_t) ||
           width == sizeof(std::uint32_t);
}

bool validDeclaredCount(
    const Section& section,
    std::size_t maximumCount,
    bool requireNonEmpty = false) noexcept {
    return section.hasCount && section.count <= maximumCount &&
           (!requireNonEmpty || section.count > 0) &&
           (section.count > 0 || section.size == 0);
}

bool declaredLayoutMatches(
    const Section& section,
    std::size_t entrySize,
    std::size_t maximumCount) noexcept {
    std::size_t count = 0;
    return validSectionEntries(section, entrySize, maximumCount, count);
}

std::optional<Schema> materializeModernSchema(
    const ModernSchemaDescriptor& descriptor,
    const Header& header) noexcept {
    if (!validDeclaredCount(header.typeDefinitions, kMaximumDefinitionCount, true) ||
        !validDeclaredCount(header.fields, kMaximumDefinitionCount) ||
        !validDeclaredCount(header.methods, kMaximumDefinitionCount) ||
        !validDeclaredCount(header.parameters, kMaximumDefinitionCount) ||
        !validDeclaredCount(header.properties, kMaximumDefinitionCount) ||
        !validDeclaredCount(header.events, kMaximumDefinitionCount) ||
        !validDeclaredCount(header.genericParameters, kMaximumDefinitionCount) ||
        !validDeclaredCount(header.genericParameterConstraints, kMaximumDefinitionCount) ||
        !validDeclaredCount(header.genericContainers, kMaximumDefinitionCount) ||
        !validDeclaredCount(header.nestedTypes, kMaximumDefinitionCount) ||
        !validDeclaredCount(header.interfaces, kMaximumDefinitionCount) ||
        !validDeclaredCount(header.interfaceOffsets, kMaximumDefinitionCount) ||
        !validDeclaredCount(header.images, kMaximumAssemblyCount, true) ||
        !validDeclaredCount(header.assemblies, kMaximumAssemblyCount, true) ||
        (descriptor.version >= 104 &&
         !validDeclaredCount(header.inlineArrays, kMaximumDefinitionCount))) {
        return std::nullopt;
    }

    const auto typeDefinitionWidth = indexWidth(header.typeDefinitions.count);
    const auto genericContainerWidth = indexWidth(header.genericContainers.count);
    const auto parameterWidth = descriptor.version >= 39
        ? indexWidth(header.parameters.count)
        : sizeof(std::int32_t);
    const auto interfaceWidth = descriptor.version >= 104
        ? indexWidth(header.interfaceOffsets.count)
        : sizeof(std::int32_t);
    const auto eventWidth = descriptor.version >= 104
        ? indexWidth(header.events.count)
        : sizeof(std::int32_t);
    const auto propertyWidth = descriptor.version >= 104
        ? indexWidth(header.properties.count)
        : sizeof(std::int32_t);
    const auto nestedTypeWidth = descriptor.version >= 104
        ? indexWidth(header.nestedTypes.count)
        : sizeof(std::int32_t);
    const auto methodWidth = descriptor.version >= 105
        ? indexWidth(header.methods.count)
        : sizeof(std::int32_t);
    const auto genericParameterWidth = descriptor.version >= 106
        ? indexWidth(header.genericParameters.count)
        : sizeof(std::int32_t);
    const auto fieldWidth = descriptor.version >= 106
        ? indexWidth(header.fields.count)
        : sizeof(std::int32_t);

    if (header.typeDefinitions.size % header.typeDefinitions.count != 0) {
        return std::nullopt;
    }
    const auto typeRecordSize = header.typeDefinitions.size / header.typeDefinitions.count;
    const auto fixedTypeBytes = 40U + genericContainerWidth + fieldWidth + methodWidth +
        eventWidth + propertyWidth + nestedTypeWidth + interfaceWidth * 2U;
    if (typeRecordSize < fixedTypeBytes ||
        (typeRecordSize - fixedTypeBytes) % 3U != 0) {
        return std::nullopt;
    }
    const auto typeWidth = (typeRecordSize - fixedTypeBytes) / 3U;
    if (!validIndexWidth(typeWidth)) {
        return std::nullopt;
    }

    const auto methodRecordSize = 20U + typeDefinitionWidth + typeWidth +
        parameterWidth + genericContainerWidth;
    const auto fieldRecordSize = 8U + typeWidth;
    const auto parameterRecordSize = 8U + typeWidth;
    const auto propertyRecordSize = 12U + methodWidth * 2U;
    const auto eventRecordSize = 8U + typeWidth + methodWidth * 3U;
    const auto genericParameterRecordSize = 12U + genericContainerWidth;
    const auto genericContainerRecordSize = descriptor.version >= 106
        ? 7U + genericParameterWidth
        : 12U + genericParameterWidth;
    const auto imageRecordSize = 28U + typeDefinitionWidth * 2U + methodWidth +
        (descriptor.version >= 108 ? 16U + typeDefinitionWidth : 0U);
    const auto interfaceOffsetRecordSize = typeWidth + sizeof(std::int32_t);
    const auto inlineArrayRecordSize = typeWidth + sizeof(std::uint32_t);
    if (!declaredLayoutMatches(
            header.typeDefinitions,
            typeRecordSize,
            kMaximumDefinitionCount) ||
        !declaredLayoutMatches(header.fields, fieldRecordSize, kMaximumDefinitionCount) ||
        !declaredLayoutMatches(header.methods, methodRecordSize, kMaximumDefinitionCount) ||
        !declaredLayoutMatches(
            header.parameters,
            parameterRecordSize,
            kMaximumDefinitionCount) ||
        !declaredLayoutMatches(
            header.properties,
            propertyRecordSize,
            kMaximumDefinitionCount) ||
        !declaredLayoutMatches(header.events, eventRecordSize, kMaximumDefinitionCount) ||
        !declaredLayoutMatches(
            header.genericParameters,
            genericParameterRecordSize,
            kMaximumDefinitionCount) ||
        !declaredLayoutMatches(
            header.genericParameterConstraints,
            typeWidth,
            kMaximumDefinitionCount) ||
        !declaredLayoutMatches(
            header.genericContainers,
            genericContainerRecordSize,
            kMaximumDefinitionCount) ||
        !declaredLayoutMatches(
            header.nestedTypes,
            sizeof(std::int32_t),
            kMaximumDefinitionCount) ||
        !declaredLayoutMatches(header.interfaces, typeWidth, kMaximumDefinitionCount) ||
        !declaredLayoutMatches(
            header.interfaceOffsets,
            interfaceOffsetRecordSize,
            kMaximumDefinitionCount) ||
        !declaredLayoutMatches(header.images, imageRecordSize, kMaximumAssemblyCount) ||
        !declaredLayoutMatches(
            header.assemblies,
            68U,
            kMaximumAssemblyCount) ||
        (descriptor.version >= 104 &&
         !declaredLayoutMatches(
             header.inlineArrays,
             inlineArrayRecordSize,
             kMaximumDefinitionCount))) {
        return std::nullopt;
    }

    Schema schema = modernHeaderSchema(descriptor);
    schema.widths = {
        typeWidth,
        typeDefinitionWidth,
        genericContainerWidth,
        fieldWidth,
        methodWidth,
        eventWidth,
        propertyWidth,
        nestedTypeWidth,
        interfaceWidth,
        parameterWidth,
        genericParameterWidth,
    };

    std::size_t offset = sizeof(std::int32_t) * 2U;
    const auto take = [&offset](std::size_t width) {
        const auto result = offset;
        offset += width;
        return result;
    };
    const auto byValueTypeIndex = take(typeWidth);
    const auto declaringTypeIndex = take(typeWidth);
    const auto parentTypeIndex = take(typeWidth);
    const auto genericContainerIndex = take(genericContainerWidth);
    const auto flags = take(sizeof(std::uint32_t));
    const auto fieldStart = take(fieldWidth);
    const auto methodStart = take(methodWidth);
    const auto eventStart = take(eventWidth);
    const auto propertyStart = take(propertyWidth);
    const auto nestedTypeStart = take(nestedTypeWidth);
    const auto interfaceStart = take(interfaceWidth);
    take(sizeof(std::int32_t));
    take(interfaceWidth);
    const auto methodCount = take(sizeof(std::uint16_t));
    const auto propertyCount = take(sizeof(std::uint16_t));
    const auto fieldCount = take(sizeof(std::uint16_t));
    const auto eventCount = take(sizeof(std::uint16_t));
    const auto nestedTypeCount = take(sizeof(std::uint16_t));
    take(sizeof(std::uint16_t));
    const auto interfaceCount = take(sizeof(std::uint16_t));
    take(sizeof(std::uint16_t));
    const auto bitfield = take(sizeof(std::uint32_t));
    const auto token = take(sizeof(std::uint32_t));
    if (offset != typeRecordSize) {
        return std::nullopt;
    }
    schema.type = {
        typeRecordSize,
        byValueTypeIndex,
        declaringTypeIndex,
        parentTypeIndex,
        genericContainerIndex,
        flags,
        fieldStart,
        methodStart,
        eventStart,
        propertyStart,
        nestedTypeStart,
        interfaceStart,
        methodCount,
        propertyCount,
        fieldCount,
        eventCount,
        nestedTypeCount,
        interfaceCount,
        bitfield,
        token,
    };

    offset = sizeof(std::int32_t);
    const auto methodDeclaringTypeIndex = take(typeDefinitionWidth);
    const auto returnTypeIndex = take(typeWidth);
    take(sizeof(std::uint32_t));
    const auto parameterStart = take(parameterWidth);
    const auto methodGenericContainerIndex = take(genericContainerWidth);
    const auto methodToken = take(sizeof(std::uint32_t));
    const auto methodFlags = take(sizeof(std::uint16_t));
    take(sizeof(std::uint16_t));
    take(sizeof(std::uint16_t));
    const auto parameterCount = take(sizeof(std::uint16_t));
    if (offset != methodRecordSize) {
        return std::nullopt;
    }
    schema.method = {
        methodRecordSize,
        methodDeclaringTypeIndex,
        returnTypeIndex,
        parameterStart,
        methodGenericContainerIndex,
        methodToken,
        methodFlags,
        parameterCount,
    };
    schema.field = {fieldRecordSize, sizeof(std::int32_t), 4U + typeWidth};
    schema.parameter = {parameterRecordSize, sizeof(std::int32_t), 8U};
    schema.property = {
        propertyRecordSize,
        sizeof(std::int32_t),
        4U + methodWidth,
        4U + methodWidth * 2U,
        8U + methodWidth * 2U,
    };
    schema.event = {
        eventRecordSize,
        sizeof(std::int32_t),
        4U + typeWidth,
        4U + typeWidth + methodWidth,
        4U + typeWidth + methodWidth * 2U,
        4U + typeWidth + methodWidth * 3U,
    };
    schema.genericParameter = {
        genericParameterRecordSize,
        0,
        genericContainerWidth,
        genericContainerWidth + sizeof(std::int32_t),
        genericContainerWidth + sizeof(std::int32_t) + sizeof(std::int16_t),
        genericContainerWidth + sizeof(std::int32_t) + sizeof(std::int16_t) * 2U,
        genericContainerWidth + sizeof(std::int32_t) + sizeof(std::int16_t) * 2U +
            sizeof(std::uint16_t),
    };
    schema.genericContainer = descriptor.version >= 106
        ? GenericContainerDefinitionLayout{
              genericContainerRecordSize,
              sizeof(std::int32_t),
              sizeof(std::uint16_t),
              sizeof(std::int32_t) + sizeof(std::uint16_t),
              sizeof(std::uint8_t),
              7U,
          }
        : GenericContainerDefinitionLayout{
              genericContainerRecordSize,
              sizeof(std::int32_t),
              sizeof(std::int32_t),
              sizeof(std::int32_t) * 2U,
              sizeof(std::int32_t),
              sizeof(std::int32_t) * 3U,
          };
    schema.image = {
        imageRecordSize,
        sizeof(std::int32_t) * 2U,
        sizeof(std::int32_t) * 2U + typeDefinitionWidth,
    };
    schema.assembly = {68U, 20U};
    return schema;
}

class StringTable final {
public:
    StringTable(const ByteView& view, Section section) : view_(view), section_(section) {}

    std::optional<std::string> get(std::int32_t index) const {
        if (index < 0) {
            return std::nullopt;
        }

        const auto relative = static_cast<std::size_t>(index);
        if (relative >= section_.size) {
            return std::nullopt;
        }

        const auto start = section_.offset + relative;
        const auto remaining = section_.size - relative;
        const auto searchLength = std::min(remaining, kMaximumMetadataStringBytes + 1U);
        const auto* begin = view_.data(start);
        const auto* end = std::find(begin, begin + searchLength, std::uint8_t{0});
        if (end == begin + searchLength) {
            return std::nullopt;
        }

        std::string value(reinterpret_cast<const char*>(begin), reinterpret_cast<const char*>(end));
        if (!isValidUtf8(value)) {
            return std::nullopt;
        }
        return value;
    }

private:
    const ByteView& view_;
    Section section_;
};

MetadataParseResult malformed(MetadataParseStage stage, std::size_t index = std::numeric_limits<std::size_t>::max()) noexcept {
    return {nullptr, MetadataParseError::Malformed, stage, index};
}

bool isAcceptedMagic(std::uint32_t magic, bool allowErasedMagic) noexcept {
    return magic == MetadataModel::kMagic ||
           (allowErasedMagic && magic == kErasedMetadataMagic);
}

MetadataParseResult parseSchema(
    const std::vector<std::uint8_t>& bytes,
    const Schema* schema) {
    const ByteView view(bytes);
    Header header;
    if (!parseHeader(view, *schema, view.size(), header)) {
        return malformed(MetadataParseStage::Header);
    }

    SectionCounts counts;
    if (!readSectionCounts(header, *schema, counts)) {
        return malformed(MetadataParseStage::Sections);
    }
    const auto typeCount = counts.types;
    const auto fieldCount = counts.fields;
    const auto methodCount = counts.methods;
    const auto parameterCount = counts.parameters;
    const auto propertyCount = counts.properties;
    const auto eventCount = counts.events;
    const auto genericParameterCount = counts.genericParameters;
    const auto genericParameterConstraintCount = counts.genericParameterConstraints;
    const auto genericContainerCount = counts.genericContainers;
    const auto nestedTypeCount = counts.nestedTypes;
    const auto interfaceCount = counts.interfaces;
    const auto imageCount = counts.images;
    const auto assemblyCount = counts.assemblies;

    auto model = MetadataModelAccess::create(
        typeCount,
        schema->type.fieldStart,
        schema->supportsRuntimeMetadata,
        schema->runtimeTypeByReferenceBit);
    auto& modelTypes = MetadataModelAccess::types(*model);
    auto& typeDefinitionIndicesByTypeIndex =
        MetadataModelAccess::typeDefinitionIndicesByTypeIndex(*model);
    typeDefinitionIndicesByTypeIndex.reserve(typeCount);
    StringTable strings(view, header.strings);
    auto& modelGenericParameters = MetadataModelAccess::genericParameters(*model);
    auto& modelGenericContainers = MetadataModelAccess::genericContainers(*model);
    modelGenericParameters.resize(genericParameterCount);
    modelGenericContainers.resize(genericContainerCount);
    std::vector<std::uint8_t> claimedGenericParameters(genericParameterCount);
    std::vector<std::uint8_t> claimedGenericParameterConstraints(genericParameterConstraintCount);
    std::vector<std::uint8_t> claimedGenericContainers(genericContainerCount);

    for (std::size_t index = 0; index < genericContainerCount; ++index) {
        const auto offset = header.genericContainers.offset +
            index * schema->genericContainer.size;
        std::uint32_t parameterCount = 0;
        std::uint32_t isMethod = 0;
        auto& container = modelGenericContainers[index];
        if (!view.readI32(offset, container.ownerIndex) ||
            !view.readUnsigned(
                offset + schema->genericContainer.parameterCount,
                schema->genericContainer.parameterCountSize,
                parameterCount) ||
            !view.readUnsigned(
                offset + schema->genericContainer.isMethod,
                schema->genericContainer.isMethodSize,
                isMethod) ||
            !view.readIndex(
                offset + schema->genericContainer.parameterStart,
                schema->widths.genericParameter,
                container.parameterStart) ||
            container.ownerIndex < 0 ||
            parameterCount > static_cast<std::uint32_t>(std::numeric_limits<std::int32_t>::max()) ||
            (isMethod != 0 && isMethod != 1) ||
            !validIndexRange(
                container.parameterStart,
                parameterCount,
                genericParameterCount)) {
            return malformed(MetadataParseStage::GenericContainerDefinition, index);
        }
        container.parameterCount = static_cast<std::int32_t>(parameterCount);
        container.isMethod = isMethod != 0;
        const auto ownerCount = container.isMethod ? methodCount : typeCount;
        if (static_cast<std::size_t>(container.ownerIndex) >= ownerCount) {
            return malformed(MetadataParseStage::GenericContainerDefinition, index);
        }
    }

    for (std::size_t index = 0; index < genericParameterCount; ++index) {
        const auto offset = header.genericParameters.offset +
            index * schema->genericParameter.size;
        std::int16_t constraintsStart = 0;
        std::int16_t constraintsCount = 0;
        auto& parameter = modelGenericParameters[index];
        if (!view.readIndex(
                offset + schema->genericParameter.ownerIndex,
                schema->widths.genericContainer,
                parameter.ownerIndex) ||
            !view.readI32(offset + schema->genericParameter.nameIndex, parameter.nameIndex) ||
            !view.readI16(
                offset + schema->genericParameter.constraintsStart,
                constraintsStart) ||
            !view.readI16(
                offset + schema->genericParameter.constraintsCount,
                constraintsCount) ||
            !view.readU16(offset + schema->genericParameter.number, parameter.number) ||
            !view.readU16(offset + schema->genericParameter.flags, parameter.flags) ||
            parameter.ownerIndex < 0 ||
            static_cast<std::size_t>(parameter.ownerIndex) >= genericContainerCount ||
            constraintsCount < 0 ||
            !validIndexRange(
                constraintsStart,
                static_cast<std::size_t>(constraintsCount),
                genericParameterConstraintCount)) {
            return malformed(MetadataParseStage::GenericParameterDefinition, index);
        }
        auto name = strings.get(parameter.nameIndex);
        if (!name) {
            return malformed(MetadataParseStage::GenericParameterDefinition, index);
        }
        parameter.name = std::move(*name);
        parameter.constraintsStart = constraintsStart;
        parameter.constraintTypeIndices.reserve(static_cast<std::size_t>(constraintsCount));
        for (std::size_t constraintOffset = 0;
             constraintOffset < static_cast<std::size_t>(constraintsCount);
             ++constraintOffset) {
            const auto constraintIndex = static_cast<std::size_t>(constraintsStart) + constraintOffset;
            if (claimedGenericParameterConstraints[constraintIndex] != 0) {
                return malformed(MetadataParseStage::GenericParameterConstraint, constraintIndex);
            }
            claimedGenericParameterConstraints[constraintIndex] = 1;
            std::int32_t typeIndex = -1;
            if (!view.readIndex(
                    header.genericParameterConstraints.offset +
                        constraintIndex * schema->widths.type,
                    schema->widths.type,
                    typeIndex) ||
                typeIndex < 0) {
                return malformed(MetadataParseStage::GenericParameterConstraint, constraintIndex);
            }
            parameter.constraintTypeIndices.push_back(typeIndex);
        }
    }

    for (std::size_t containerIndex = 0; containerIndex < genericContainerCount; ++containerIndex) {
        const auto& container = modelGenericContainers[containerIndex];
        for (std::size_t offset = 0; offset < static_cast<std::size_t>(container.parameterCount); ++offset) {
            const auto parameterIndex = static_cast<std::size_t>(container.parameterStart) + offset;
            if (claimedGenericParameters[parameterIndex] != 0) {
                return malformed(MetadataParseStage::GenericParameterDefinition, parameterIndex);
            }
            claimedGenericParameters[parameterIndex] = 1;
            const auto& parameter = modelGenericParameters[parameterIndex];
            if (parameter.ownerIndex != static_cast<std::int32_t>(containerIndex) ||
                parameter.number != offset) {
                return malformed(MetadataParseStage::GenericParameterDefinition, parameterIndex);
            }
        }
    }

    if (!allClaimed(claimedGenericParameters) ||
        !allClaimed(claimedGenericParameterConstraints)) {
        return malformed(MetadataParseStage::Sections);
    }

    std::vector<std::uint8_t> claimedFields(fieldCount);
    std::vector<std::uint8_t> claimedMethods(methodCount);
    std::vector<std::uint8_t> claimedParameters(parameterCount);
    std::vector<std::uint8_t> claimedProperties(propertyCount);
    std::vector<std::uint8_t> claimedEvents(eventCount);
    std::vector<std::uint8_t> claimedNestedTypes(nestedTypeCount);
    std::vector<std::uint8_t> claimedInterfaces(interfaceCount);

    for (std::size_t typeIndex = 0; typeIndex < typeCount; ++typeIndex) {
        const auto offset = header.typeDefinitions.offset + typeIndex * schema->type.size;
        std::int32_t nameIndex = 0;
        std::int32_t namespaceIndex = 0;
        std::int32_t byValueTypeIndex = 0;
        std::int32_t declaringTypeIndex = 0;
        std::int32_t parentTypeIndex = 0;
        std::int32_t genericContainerIndex = 0;
        std::int32_t firstField = 0;
        std::int32_t firstMethod = 0;
        std::int32_t firstEvent = 0;
        std::int32_t firstProperty = 0;
        std::int32_t firstNestedType = 0;
        std::int32_t firstInterface = 0;
        std::uint16_t currentFieldCount = 0;
        std::uint16_t currentMethodCount = 0;
        std::uint16_t currentPropertyCount = 0;
        std::uint16_t currentEventCount = 0;
        std::uint16_t currentNestedTypeCount = 0;
        std::uint16_t currentInterfaceCount = 0;
        std::uint32_t typeFlags = 0;
        std::uint32_t typeBitfield = 0;
        std::uint32_t typeToken = 0;
        if (!view.readI32(offset, nameIndex) ||
            !view.readI32(offset + 4, namespaceIndex) ||
            !view.readIndex(
                offset + schema->type.byValueTypeIndex,
                schema->widths.type,
                byValueTypeIndex) ||
            !view.readIndex(
                offset + schema->type.declaringTypeIndex,
                schema->widths.type,
                declaringTypeIndex) ||
            !view.readIndex(
                offset + schema->type.parentTypeIndex,
                schema->widths.type,
                parentTypeIndex) ||
            !view.readIndex(
                offset + schema->type.genericContainerIndex,
                schema->widths.genericContainer,
                genericContainerIndex) ||
            !view.readU32(offset + schema->type.flags, typeFlags) ||
            !view.readIndex(
                offset + schema->type.fieldStart,
                schema->widths.field,
                firstField) ||
            !view.readIndex(
                offset + schema->type.methodStart,
                schema->widths.method,
                firstMethod) ||
            !view.readIndex(
                offset + schema->type.eventStart,
                schema->widths.event,
                firstEvent) ||
            !view.readIndex(
                offset + schema->type.propertyStart,
                schema->widths.property,
                firstProperty) ||
            !view.readIndex(
                offset + schema->type.nestedTypeStart,
                schema->widths.nestedType,
                firstNestedType) ||
            !view.readIndex(
                offset + schema->type.interfaceStart,
                schema->widths.interface,
                firstInterface) ||
            !view.readU16(offset + schema->type.fieldCount, currentFieldCount) ||
            !view.readU16(offset + schema->type.methodCount, currentMethodCount) ||
            !view.readU16(offset + schema->type.propertyCount, currentPropertyCount) ||
            !view.readU16(offset + schema->type.eventCount, currentEventCount) ||
            !view.readU16(offset + schema->type.nestedTypeCount, currentNestedTypeCount) ||
            !view.readU16(offset + schema->type.interfaceCount, currentInterfaceCount) ||
            !view.readU32(offset + schema->type.bitfield, typeBitfield) ||
            !view.readU32(offset + schema->type.token, typeToken) ||
            byValueTypeIndex < 0 || declaringTypeIndex < -1 || parentTypeIndex < -1 ||
            genericContainerIndex < -1 ||
            (genericContainerIndex >= 0 &&
             static_cast<std::size_t>(genericContainerIndex) >= genericContainerCount) ||
            !validIndexRange(firstField, currentFieldCount, fieldCount) ||
            !validIndexRange(firstMethod, currentMethodCount, methodCount) ||
            !validIndexRange(firstProperty, currentPropertyCount, propertyCount) ||
            !validIndexRange(firstEvent, currentEventCount, eventCount) ||
            !validIndexRange(firstNestedType, currentNestedTypeCount, nestedTypeCount) ||
            !validIndexRange(firstInterface, currentInterfaceCount, interfaceCount)) {
            return malformed(MetadataParseStage::TypeDefinition, typeIndex);
        }
        if (genericContainerIndex >= 0) {
            const auto& container = modelGenericContainers[static_cast<std::size_t>(genericContainerIndex)];
            if (container.isMethod || container.ownerIndex != static_cast<std::int32_t>(typeIndex) ||
                claimedGenericContainers[static_cast<std::size_t>(genericContainerIndex)] != 0) {
                return malformed(MetadataParseStage::TypeDefinition, typeIndex);
            }
            claimedGenericContainers[static_cast<std::size_t>(genericContainerIndex)] = 1;
        }

        auto name = strings.get(nameIndex);
        auto namespaze = strings.get(namespaceIndex);
        if (!name || !namespaze) {
            return malformed(MetadataParseStage::TypeDefinition, typeIndex);
        }

        auto& type = modelTypes[typeIndex];
        type.name = std::move(*name);
        type.namespaze = std::move(*namespaze);
        type.nameIndex = nameIndex;
        type.namespaceIndex = namespaceIndex;
        type.byValueTypeIndex = byValueTypeIndex;
        type.declaringTypeIndex = declaringTypeIndex;
        type.parentTypeIndex = parentTypeIndex;
        type.genericContainerIndex = genericContainerIndex;
        type.fieldStart = firstField;
        type.methodStart = firstMethod;
        type.flags = typeFlags;
        type.bitfield = typeBitfield;
        type.token = typeToken;
        type.fields.reserve(currentFieldCount);
        type.methods.reserve(currentMethodCount);
        type.properties.reserve(currentPropertyCount);
        type.events.reserve(currentEventCount);
        type.nestedTypeIndices.reserve(currentNestedTypeCount);
        type.interfaceTypeIndices.reserve(currentInterfaceCount);
        if (!typeDefinitionIndicesByTypeIndex.emplace(
                byValueTypeIndex,
                static_cast<std::int32_t>(typeIndex)).second) {
            return malformed(MetadataParseStage::TypeDefinition, typeIndex);
        }

        for (std::size_t fieldOffset = 0; fieldOffset < currentFieldCount; ++fieldOffset) {
            const auto definitionIndex = static_cast<std::size_t>(firstField) + fieldOffset;
            if (claimedFields[definitionIndex] != 0) {
                return malformed(MetadataParseStage::FieldDefinition, definitionIndex);
            }
            claimedFields[definitionIndex] = 1;
            const auto definitionOffset = header.fields.offset +
                definitionIndex * schema->field.size;
            std::int32_t fieldNameIndex = 0;
            std::int32_t fieldTypeIndex = 0;
            std::uint32_t fieldToken = 0;
            if (!view.readI32(definitionOffset, fieldNameIndex) ||
                !view.readIndex(
                    definitionOffset + schema->field.typeIndex,
                    schema->widths.type,
                    fieldTypeIndex) ||
                !view.readU32(definitionOffset + schema->field.token, fieldToken) ||
                fieldTypeIndex < 0) {
                return malformed(MetadataParseStage::FieldDefinition, definitionIndex);
            }
            auto fieldName = strings.get(fieldNameIndex);
            if (!fieldName) {
                return malformed(MetadataParseStage::FieldDefinition, definitionIndex);
            }
            type.fields.push_back({std::move(*fieldName), fieldTypeIndex, fieldToken});
        }

        for (std::size_t methodOffset = 0; methodOffset < currentMethodCount; ++methodOffset) {
            const auto definitionIndex = static_cast<std::size_t>(firstMethod) + methodOffset;
            if (claimedMethods[definitionIndex] != 0) {
                return malformed(MetadataParseStage::MethodDefinition, definitionIndex);
            }
            claimedMethods[definitionIndex] = 1;
            const auto definitionOffset = header.methods.offset +
                definitionIndex * schema->method.size;
            std::int32_t methodNameIndex = 0;
            std::int32_t declaringTypeIndex = 0;
            std::int32_t returnTypeIndex = 0;
            std::int32_t firstParameter = 0;
            std::int32_t methodGenericContainerIndex = 0;
            std::uint32_t methodToken = 0;
            std::uint16_t methodFlags = 0;
            std::uint16_t currentParameterCount = 0;
            if (!view.readI32(definitionOffset, methodNameIndex) ||
                !view.readIndex(
                    definitionOffset + schema->method.declaringTypeIndex,
                    schema->widths.typeDefinition,
                    declaringTypeIndex) ||
                !view.readIndex(
                    definitionOffset + schema->method.returnTypeIndex,
                    schema->widths.type,
                    returnTypeIndex) ||
                !view.readIndex(
                    definitionOffset + schema->method.parameterStart,
                    schema->widths.parameter,
                    firstParameter) ||
                !view.readIndex(
                    definitionOffset + schema->method.genericContainerIndex,
                    schema->widths.genericContainer,
                    methodGenericContainerIndex) ||
                !view.readU32(definitionOffset + schema->method.token, methodToken) ||
                !view.readU16(definitionOffset + schema->method.flags, methodFlags) ||
                !view.readU16(
                    definitionOffset + schema->method.parameterCount,
                    currentParameterCount) ||
                declaringTypeIndex != static_cast<std::int32_t>(typeIndex) ||
                returnTypeIndex < 0 ||
                methodGenericContainerIndex < -1 ||
                (methodGenericContainerIndex >= 0 &&
                 static_cast<std::size_t>(methodGenericContainerIndex) >= genericContainerCount) ||
                !validIndexRange(firstParameter, currentParameterCount, parameterCount)) {
                return malformed(MetadataParseStage::MethodDefinition, definitionIndex);
            }
            if (methodGenericContainerIndex >= 0) {
                const auto& container =
                    modelGenericContainers[static_cast<std::size_t>(methodGenericContainerIndex)];
                if (!container.isMethod ||
                    container.ownerIndex != static_cast<std::int32_t>(definitionIndex) ||
                    claimedGenericContainers[static_cast<std::size_t>(methodGenericContainerIndex)] != 0) {
                    return malformed(MetadataParseStage::MethodDefinition, definitionIndex);
                }
                claimedGenericContainers[static_cast<std::size_t>(methodGenericContainerIndex)] = 1;
            }
            auto methodName = strings.get(methodNameIndex);
            if (!methodName) {
                return malformed(MetadataParseStage::MethodDefinition, definitionIndex);
            }
            MethodMetadata method;
            method.name = std::move(*methodName);
            method.definitionIndex = static_cast<std::int32_t>(definitionIndex);
            method.returnTypeIndex = returnTypeIndex;
            method.genericContainerIndex = methodGenericContainerIndex;
            method.token = methodToken;
            method.flags = methodFlags;
            method.parameters.reserve(currentParameterCount);
            for (std::size_t parameterOffset = 0; parameterOffset < currentParameterCount; ++parameterOffset) {
                const auto parameterIndex = static_cast<std::size_t>(firstParameter) + parameterOffset;
                if (claimedParameters[parameterIndex] != 0) {
                    return malformed(MetadataParseStage::MethodDefinition, definitionIndex);
                }
                claimedParameters[parameterIndex] = 1;
                const auto parameterDefinitionOffset =
                    header.parameters.offset + parameterIndex * schema->parameter.size;
                std::int32_t parameterNameIndex = 0;
                std::uint32_t parameterToken = 0;
                std::int32_t parameterTypeIndex = 0;
                if (!view.readI32(parameterDefinitionOffset, parameterNameIndex) ||
                    !view.readU32(
                        parameterDefinitionOffset + schema->parameter.token,
                        parameterToken) ||
                    !view.readIndex(
                        parameterDefinitionOffset + schema->parameter.typeIndex,
                        schema->widths.type,
                        parameterTypeIndex) ||
                    parameterTypeIndex < 0) {
                    return malformed(MetadataParseStage::MethodDefinition, definitionIndex);
                }
                auto parameterName = strings.get(parameterNameIndex);
                if (!parameterName) {
                    return malformed(MetadataParseStage::MethodDefinition, definitionIndex);
                }
                method.parameters.push_back({
                    std::move(*parameterName),
                    parameterTypeIndex,
                    parameterToken,
                });
            }
            type.methods.push_back(std::move(method));
        }

        for (std::size_t propertyOffset = 0; propertyOffset < currentPropertyCount; ++propertyOffset) {
            const auto definitionIndex = static_cast<std::size_t>(firstProperty) + propertyOffset;
            if (claimedProperties[definitionIndex] != 0) {
                return malformed(MetadataParseStage::PropertyDefinition, definitionIndex);
            }
            claimedProperties[definitionIndex] = 1;
            const auto definitionOffset = header.properties.offset +
                definitionIndex * schema->property.size;
            std::int32_t propertyNameIndex = 0;
            PropertyMetadata property;
            if (!view.readI32(definitionOffset, propertyNameIndex) ||
                !view.readIndex(
                    definitionOffset + schema->property.getterIndex,
                    schema->widths.method,
                    property.getterIndex) ||
                !view.readIndex(
                    definitionOffset + schema->property.setterIndex,
                    schema->widths.method,
                    property.setterIndex) ||
                !view.readU32(
                    definitionOffset + schema->property.attributes,
                    property.attributes) ||
                !view.readU32(definitionOffset + schema->property.token, property.token) ||
                !validRelativeMethodIndex(property.getterIndex, currentMethodCount) ||
                !validRelativeMethodIndex(property.setterIndex, currentMethodCount)) {
                return malformed(MetadataParseStage::PropertyDefinition, definitionIndex);
            }
            auto propertyName = strings.get(propertyNameIndex);
            if (!propertyName) {
                return malformed(MetadataParseStage::PropertyDefinition, definitionIndex);
            }
            property.name = std::move(*propertyName);
            type.properties.push_back(std::move(property));
        }

        for (std::size_t eventOffset = 0; eventOffset < currentEventCount; ++eventOffset) {
            const auto definitionIndex = static_cast<std::size_t>(firstEvent) + eventOffset;
            if (claimedEvents[definitionIndex] != 0) {
                return malformed(MetadataParseStage::EventDefinition, definitionIndex);
            }
            claimedEvents[definitionIndex] = 1;
            const auto definitionOffset = header.events.offset +
                definitionIndex * schema->event.size;
            std::int32_t eventNameIndex = 0;
            EventMetadata event;
            if (!view.readI32(definitionOffset, eventNameIndex) ||
                !view.readIndex(
                    definitionOffset + schema->event.typeIndex,
                    schema->widths.type,
                    event.typeIndex) ||
                !view.readIndex(
                    definitionOffset + schema->event.addIndex,
                    schema->widths.method,
                    event.addIndex) ||
                !view.readIndex(
                    definitionOffset + schema->event.removeIndex,
                    schema->widths.method,
                    event.removeIndex) ||
                !view.readIndex(
                    definitionOffset + schema->event.raiseIndex,
                    schema->widths.method,
                    event.raiseIndex) ||
                !view.readU32(definitionOffset + schema->event.token, event.token) ||
                event.typeIndex < 0 ||
                !validRelativeMethodIndex(event.addIndex, currentMethodCount) ||
                !validRelativeMethodIndex(event.removeIndex, currentMethodCount) ||
                !validRelativeMethodIndex(event.raiseIndex, currentMethodCount)) {
                return malformed(MetadataParseStage::EventDefinition, definitionIndex);
            }
            auto eventName = strings.get(eventNameIndex);
            if (!eventName) {
                return malformed(MetadataParseStage::EventDefinition, definitionIndex);
            }
            event.name = std::move(*eventName);
            type.events.push_back(std::move(event));
        }

        for (std::size_t nestedOffset = 0; nestedOffset < currentNestedTypeCount; ++nestedOffset) {
            const auto entryIndex = static_cast<std::size_t>(firstNestedType) + nestedOffset;
            if (claimedNestedTypes[entryIndex] != 0) {
                return malformed(MetadataParseStage::NestedTypeDefinition, entryIndex);
            }
            claimedNestedTypes[entryIndex] = 1;
            std::int32_t nestedTypeIndex = 0;
            if (!view.readIndex(
                    header.nestedTypes.offset + entryIndex * sizeof(std::int32_t),
                    sizeof(std::int32_t),
                    nestedTypeIndex) ||
                nestedTypeIndex < 0 || static_cast<std::size_t>(nestedTypeIndex) >= typeCount ||
                nestedTypeIndex == static_cast<std::int32_t>(typeIndex)) {
                return malformed(MetadataParseStage::NestedTypeDefinition, entryIndex);
            }
            auto& nestedType = modelTypes[static_cast<std::size_t>(nestedTypeIndex)];
            if (nestedType.declaringTypeDefinitionIndex >= 0) {
                return malformed(MetadataParseStage::NestedTypeDefinition, entryIndex);
            }
            nestedType.declaringTypeDefinitionIndex = static_cast<std::int32_t>(typeIndex);
            type.nestedTypeIndices.push_back(nestedTypeIndex);
        }

        for (std::size_t interfaceOffset = 0; interfaceOffset < currentInterfaceCount; ++interfaceOffset) {
            const auto entryIndex = static_cast<std::size_t>(firstInterface) + interfaceOffset;
            if (claimedInterfaces[entryIndex] != 0) {
                return malformed(MetadataParseStage::InterfaceDefinition, entryIndex);
            }
            claimedInterfaces[entryIndex] = 1;
            std::int32_t interfaceTypeIndex = 0;
            if (!view.readIndex(
                    header.interfaces.offset + entryIndex * schema->widths.type,
                    schema->widths.type,
                    interfaceTypeIndex) ||
                interfaceTypeIndex < 0) {
                return malformed(MetadataParseStage::InterfaceDefinition, entryIndex);
            }
            type.interfaceTypeIndices.push_back(interfaceTypeIndex);
        }
    }

    if (!allClaimed(claimedGenericContainers) ||
        !allClaimed(claimedFields) ||
        !allClaimed(claimedMethods) ||
        !allClaimed(claimedParameters) ||
        !allClaimed(claimedProperties) ||
        !allClaimed(claimedEvents) ||
        !allClaimed(claimedNestedTypes) ||
        !allClaimed(claimedInterfaces)) {
        return malformed(MetadataParseStage::Sections);
    }

    for (std::size_t typeIndex = 0; typeIndex < typeCount; ++typeIndex) {
        const auto& type = modelTypes[typeIndex];
        const auto declaringDefinition = typeDefinitionIndicesByTypeIndex.find(type.declaringTypeIndex);
        if ((type.declaringTypeIndex < 0 && type.declaringTypeDefinitionIndex >= 0) ||
            (type.declaringTypeIndex >= 0 &&
             (declaringDefinition == typeDefinitionIndicesByTypeIndex.end() ||
              declaringDefinition->second != type.declaringTypeDefinitionIndex))) {
            return malformed(MetadataParseStage::NestedTypeDefinition, typeIndex);
        }
    }

    std::vector<RawImage> images;
    images.reserve(imageCount);
    std::vector<std::uint8_t> claimedTypes(typeCount);
    for (std::size_t imageIndex = 0; imageIndex < imageCount; ++imageIndex) {
        const auto offset = header.images.offset + imageIndex * schema->image.size;
        std::int32_t nameIndex = 0;
        RawImage image;
        if (!view.readI32(offset, nameIndex) ||
            !view.readI32(offset + 4, image.assemblyIndex) ||
            !view.readIndex(
                offset + schema->image.typeStart,
                schema->widths.typeDefinition,
                image.typeStart) ||
            !view.readU32(offset + schema->image.typeCount, image.typeCount) ||
            !validIndexRange(image.typeStart, image.typeCount, typeCount)) {
            return malformed(MetadataParseStage::ImageDefinition, imageIndex);
        }
        auto name = strings.get(nameIndex);
        if (!name) {
            return malformed(MetadataParseStage::ImageDefinition, imageIndex);
        }
        image.name = std::move(*name);
        for (std::size_t relativeTypeIndex = 0; relativeTypeIndex < image.typeCount; ++relativeTypeIndex) {
            const auto typeIndex = static_cast<std::size_t>(image.typeStart) + relativeTypeIndex;
            if (claimedTypes[typeIndex] != 0) {
                return malformed(MetadataParseStage::ImageDefinition, imageIndex);
            }
            claimedTypes[typeIndex] = 1;
            modelTypes[typeIndex].imageName = image.name;
        }
        images.push_back(std::move(image));
    }
    if (!allClaimed(claimedTypes)) {
        return malformed(MetadataParseStage::Sections);
    }

    auto& modelImageNames = MetadataModelAccess::imageNames(*model);
    modelImageNames.reserve(images.size());
    for (const auto& image : images) {
        modelImageNames.push_back(image.name);
    }

    auto& modelAssemblies = MetadataModelAccess::assemblies(*model);
    modelAssemblies.reserve(assemblyCount);
    std::vector<std::uint8_t> claimedImages(imageCount);
    for (std::size_t assemblyIndex = 0; assemblyIndex < assemblyCount; ++assemblyIndex) {
        const auto offset = header.assemblies.offset +
            assemblyIndex * schema->assembly.size;
        std::int32_t imageIndex = 0;
        std::int32_t nameIndex = 0;
        if (!view.readI32(offset, imageIndex) ||
            !view.readI32(offset + schema->assembly.nameIndex, nameIndex) ||
            imageIndex < 0 || static_cast<std::size_t>(imageIndex) >= images.size()) {
            return malformed(MetadataParseStage::AssemblyDefinition, assemblyIndex);
        }
        if (claimedImages[static_cast<std::size_t>(imageIndex)] != 0) {
            return malformed(MetadataParseStage::AssemblyDefinition, assemblyIndex);
        }
        claimedImages[static_cast<std::size_t>(imageIndex)] = 1;

        const auto& image = images[static_cast<std::size_t>(imageIndex)];
        if (image.assemblyIndex >= 0 && static_cast<std::size_t>(image.assemblyIndex) != assemblyIndex) {
            return malformed(MetadataParseStage::AssemblyDefinition, assemblyIndex);
        }

        auto name = strings.get(nameIndex);
        if (!name) {
            return malformed(MetadataParseStage::AssemblyDefinition, assemblyIndex);
        }

        AssemblyMetadata assembly;
        assembly.name = std::move(*name);
        assembly.typeIndices.reserve(image.typeCount);
        std::unordered_map<std::string, std::size_t> namespaceIndices;
        namespaceIndices.reserve(image.typeCount);

        for (std::size_t relativeTypeIndex = 0; relativeTypeIndex < image.typeCount; ++relativeTypeIndex) {
            const auto typeIndex = static_cast<std::size_t>(image.typeStart) + relativeTypeIndex;
            auto& type = modelTypes[typeIndex];
            type.assemblyIndex = static_cast<std::int32_t>(assemblyIndex);
            type.assemblyName = assembly.name;
            assembly.typeIndices.push_back(static_cast<std::int32_t>(typeIndex));
            if (type.declaringTypeDefinitionIndex >= 0) {
                continue;
            }
            const auto inserted = namespaceIndices.emplace(type.namespaze, assembly.namespaces.size());
            if (inserted.second) {
                assembly.namespaces.push_back({type.namespaze, {}});
            }
            assembly.namespaces[inserted.first->second].typeIndices.push_back(static_cast<std::int32_t>(typeIndex));
        }

        modelAssemblies.push_back(std::move(assembly));
    }
    if (!allClaimed(claimedImages)) {
        return malformed(MetadataParseStage::Sections);
    }

    return {std::move(model), MetadataParseError::None};
}

MetadataParseResult parseModel(
    std::vector<std::uint8_t> bytes,
    bool allowErasedMagic) {
    if (bytes.size() > MetadataModel::kMaximumMetadataBytes) {
        return {nullptr, MetadataParseError::ResourceLimit};
    }
    if (bytes.size() < kHeaderPrefixSize) {
        return malformed(MetadataParseStage::Header);
    }

    const ByteView view(bytes);
    std::uint32_t magic = 0;
    std::int32_t version = 0;
    if (!view.readU32(0, magic) || !view.readI32(4, version)) {
        return malformed(MetadataParseStage::Header);
    }
    if (!isAcceptedMagic(magic, allowErasedMagic)) {
        return {nullptr, MetadataParseError::InvalidMagic};
    }

    bool versionSupported = false;
    MetadataParseResult accepted;
    MetadataParseResult failure = malformed(MetadataParseStage::Sections);
    const auto acceptCandidate = [&](MetadataParseResult candidate) {
        if (!candidate) {
            if (static_cast<int>(candidate.stage) > static_cast<int>(failure.stage)) {
                failure = candidate;
            }
            return true;
        }
        if (accepted) {
            return false;
        }
        accepted = std::move(candidate);
        return true;
    };
    for (const auto& schema : kLegacySchemas) {
        if (schema.version != version) {
            continue;
        }
        versionSupported = true;
        if (!acceptCandidate(parseSchema(bytes, &schema))) {
            return {nullptr, MetadataParseError::AmbiguousLayout};
        }
    }
    for (const auto& descriptor : kModernSchemas) {
        if (descriptor.version != version) {
            continue;
        }
        versionSupported = true;
        const auto headerSchema = modernHeaderSchema(descriptor);
        Header header;
        if (!parseHeader(view, headerSchema, view.size(), header)) {
            continue;
        }
        const auto schema = materializeModernSchema(descriptor, header);
        if (schema && !acceptCandidate(parseSchema(bytes, &*schema))) {
            return {nullptr, MetadataParseError::AmbiguousLayout};
        }
    }
    if (!versionSupported) {
        return {nullptr, MetadataParseError::UnsupportedVersion};
    }
    return accepted ? accepted : failure;
}

}

std::size_t MetadataModel::typeDefinitionFieldStartOffset() const noexcept {
    return typeDefinitionFieldStartOffset_;
}

bool MetadataModel::supportsRuntimeMetadata() const noexcept {
    return supportsRuntimeMetadata_;
}

std::optional<std::uint8_t> MetadataModel::runtimeTypeByReferenceBit() const noexcept {
    return runtimeTypeByReferenceBit_;
}

const std::vector<AssemblyMetadata>& MetadataModel::assemblies() const noexcept {
    return assemblies_;
}

const std::vector<std::string>& MetadataModel::imageNames() const noexcept {
    return imageNames_;
}

const std::vector<TypeMetadata>& MetadataModel::types() const noexcept {
    return types_;
}

const std::vector<GenericParameterMetadata>& MetadataModel::genericParameters() const noexcept {
    return genericParameters_;
}

const std::vector<GenericContainerMetadata>& MetadataModel::genericContainers() const noexcept {
    return genericContainers_;
}

std::optional<std::int32_t> MetadataModel::typeDefinitionIndexForTypeIndex(
    std::int32_t typeIndex) const noexcept {
    const auto found = typeDefinitionIndicesByTypeIndex_.find(typeIndex);
    return found == typeDefinitionIndicesByTypeIndex_.end()
               ? std::nullopt
               : std::optional(found->second);
}

MetadataParseResult parseMetadata(
    std::vector<std::uint8_t> bytes,
    bool allowErasedMagic) noexcept {
    try {
        return parseModel(std::move(bytes), allowErasedMagic);
    } catch (...) {
        return {nullptr, MetadataParseError::ResourceLimit};
    }
}

std::optional<std::size_t> probeMetadataSize(
    const std::vector<std::uint8_t>& headerBytes,
    std::size_t availableBytes,
    bool allowErasedMagic) noexcept {
    try {
        if (headerBytes.size() < kHeaderPrefixSize || availableBytes < kHeaderPrefixSize) {
            return std::nullopt;
        }
        const ByteView view(headerBytes);
        std::uint32_t magic = 0;
        std::int32_t version = 0;
        if (!view.readU32(0, magic) ||
            !isAcceptedMagic(magic, allowErasedMagic) ||
            !view.readI32(sizeof(std::uint32_t), version)) {
            return std::nullopt;
        }
        bool versionSupported = false;
        std::optional<std::size_t> acceptedSize;
        const auto acceptSize = [&](std::size_t metadataSize) {
            if (acceptedSize && *acceptedSize != metadataSize) {
                return false;
            }
            acceptedSize = metadataSize;
            return true;
        };
        for (const auto& schema : kLegacySchemas) {
            if (schema.version != version) {
                continue;
            }
            versionSupported = true;
            Header header;
            std::size_t metadataSize = 0;
            SectionCounts counts;
            if (!parseHeader(view, schema, availableBytes, header, &metadataSize) ||
                metadataSize > MetadataModel::kMaximumMetadataBytes ||
                header.strings.size == 0 ||
                !readSectionCounts(header, schema, counts)) {
                continue;
            }
            if (!acceptSize(metadataSize)) {
                return std::nullopt;
            }
        }
        for (const auto& descriptor : kModernSchemas) {
            if (descriptor.version != version) {
                continue;
            }
            versionSupported = true;
            const auto headerSchema = modernHeaderSchema(descriptor);
            Header header;
            std::size_t metadataSize = 0;
            if (!parseHeader(view, headerSchema, availableBytes, header, &metadataSize) ||
                metadataSize > MetadataModel::kMaximumMetadataBytes ||
                header.strings.size == 0) {
                continue;
            }
            const auto schema = materializeModernSchema(descriptor, header);
            SectionCounts counts;
            if (!schema || !readSectionCounts(header, *schema, counts)) {
                continue;
            }
            if (!acceptSize(metadataSize)) {
                return std::nullopt;
            }
        }
        return versionSupported ? acceptedSize : std::nullopt;
    } catch (...) {
        return std::nullopt;
    }
}

bool isValidUtf8(const std::string& value) noexcept {
    const auto* bytes = reinterpret_cast<const std::uint8_t*>(value.data());
    std::size_t index = 0;
    while (index < value.size()) {
        const auto first = bytes[index];
        if (first <= 0x7F) {
            ++index;
            continue;
        }
        if (first >= 0xC2 && first <= 0xDF) {
            if (index + 1 >= value.size() || (bytes[index + 1] & 0xC0U) != 0x80U) {
                return false;
            }
            index += 2;
            continue;
        }
        if (first >= 0xE0 && first <= 0xEF) {
            if (index + 2 >= value.size() ||
                (bytes[index + 1] & 0xC0U) != 0x80U ||
                (bytes[index + 2] & 0xC0U) != 0x80U ||
                (first == 0xE0 && bytes[index + 1] < 0xA0) ||
                (first == 0xED && bytes[index + 1] >= 0xA0)) {
                return false;
            }
            index += 3;
            continue;
        }
        if (first >= 0xF0 && first <= 0xF4) {
            if (index + 3 >= value.size() ||
                (bytes[index + 1] & 0xC0U) != 0x80U ||
                (bytes[index + 2] & 0xC0U) != 0x80U ||
                (bytes[index + 3] & 0xC0U) != 0x80U ||
                (first == 0xF0 && bytes[index + 1] < 0x90) ||
                (first == 0xF4 && bytes[index + 1] >= 0x90)) {
                return false;
            }
            index += 4;
            continue;
        }
        return false;
    }
    return true;
}

}
