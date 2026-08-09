#pragma once

#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory>
#include <optional>
#include <string>
#include <unordered_map>
#include <vector>

namespace il2cppmanager {

struct MetadataModelAccess;

enum class MetadataParseError {
    None,
    InvalidMagic,
    UnsupportedVersion,
    AmbiguousLayout,
    Malformed,
    ResourceLimit,
};

enum class MetadataParseStage {
    None,
    Header,
    Sections,
    TypeDefinition,
    FieldDefinition,
    MethodDefinition,
    GenericParameterDefinition,
    GenericParameterConstraint,
    GenericContainerDefinition,
    PropertyDefinition,
    EventDefinition,
    NestedTypeDefinition,
    InterfaceDefinition,
    ImageDefinition,
    AssemblyDefinition,
};

struct FieldMetadata {
    std::string name;
    std::int32_t typeIndex = -1;
    std::uint32_t token = 0;
};

struct ParameterMetadata {
    std::string name;
    std::int32_t typeIndex = -1;
    std::uint32_t token = 0;
};

struct GenericParameterMetadata {
    std::string name;
    std::int32_t nameIndex = -1;
    std::int32_t ownerIndex = -1;
    std::int32_t constraintsStart = -1;
    std::uint16_t number = 0;
    std::uint16_t flags = 0;
    std::vector<std::int32_t> constraintTypeIndices;
};

struct GenericContainerMetadata {
    std::int32_t ownerIndex = -1;
    std::int32_t parameterStart = -1;
    std::int32_t parameterCount = 0;
    bool isMethod = false;
};

struct MethodMetadata {
    std::string name;
    std::int32_t definitionIndex = -1;
    std::int32_t returnTypeIndex = -1;
    std::int32_t genericContainerIndex = -1;
    std::uint32_t token = 0;
    std::uint16_t flags = 0;
    std::vector<ParameterMetadata> parameters;
};

struct PropertyMetadata {
    std::string name;
    std::int32_t getterIndex = -1;
    std::int32_t setterIndex = -1;
    std::uint32_t attributes = 0;
    std::uint32_t token = 0;
};

struct EventMetadata {
    std::string name;
    std::int32_t typeIndex = -1;
    std::int32_t addIndex = -1;
    std::int32_t removeIndex = -1;
    std::int32_t raiseIndex = -1;
    std::uint32_t token = 0;
};

struct TypeMetadata {
    std::string name;
    std::string namespaze;
    std::string imageName;
    std::string assemblyName;
    std::int32_t assemblyIndex = -1;
    std::int32_t nameIndex = -1;
    std::int32_t namespaceIndex = -1;
    std::int32_t byValueTypeIndex = -1;
    std::int32_t declaringTypeIndex = -1;
    std::int32_t parentTypeIndex = -1;
    std::int32_t genericContainerIndex = -1;
    std::int32_t declaringTypeDefinitionIndex = -1;
    std::int32_t fieldStart = -1;
    std::int32_t methodStart = -1;
    std::uint32_t flags = 0;
    std::uint32_t bitfield = 0;
    std::uint32_t token = 0;
    std::vector<FieldMetadata> fields;
    std::vector<MethodMetadata> methods;
    std::vector<PropertyMetadata> properties;
    std::vector<EventMetadata> events;
    std::vector<std::int32_t> nestedTypeIndices;
    std::vector<std::int32_t> interfaceTypeIndices;
};

struct NamespaceMetadata {
    std::string name;
    std::vector<std::int32_t> typeIndices;
};

struct AssemblyMetadata {
    std::string name;
    std::vector<std::int32_t> typeIndices;
    std::vector<NamespaceMetadata> namespaces;
};

class MetadataModel final {
public:
    static constexpr std::uint32_t kMagic = 0xFAB11BAF;
    static constexpr std::size_t kMaximumMetadataBytes = 128U * 1024U * 1024U;

    std::size_t typeDefinitionFieldStartOffset() const noexcept;
    bool supportsRuntimeMetadata() const noexcept;
    std::optional<std::uint8_t> runtimeTypeByReferenceBit() const noexcept;
    const std::vector<AssemblyMetadata>& assemblies() const noexcept;
    const std::vector<std::string>& imageNames() const noexcept;
    const std::vector<TypeMetadata>& types() const noexcept;
    const std::vector<GenericParameterMetadata>& genericParameters() const noexcept;
    const std::vector<GenericContainerMetadata>& genericContainers() const noexcept;
    std::optional<std::int32_t> typeDefinitionIndexForTypeIndex(
        std::int32_t typeIndex) const noexcept;

private:
    friend struct MetadataModelAccess;

    MetadataModel() = default;

    std::size_t typeDefinitionFieldStartOffset_ = 0;
    bool supportsRuntimeMetadata_ = false;
    std::optional<std::uint8_t> runtimeTypeByReferenceBit_;
    std::vector<AssemblyMetadata> assemblies_;
    std::vector<std::string> imageNames_;
    std::vector<TypeMetadata> types_;
    std::vector<GenericParameterMetadata> genericParameters_;
    std::vector<GenericContainerMetadata> genericContainers_;
    std::unordered_map<std::int32_t, std::int32_t> typeDefinitionIndicesByTypeIndex_;
};

struct MetadataParseResult {
    std::shared_ptr<const MetadataModel> model;
    MetadataParseError error = MetadataParseError::None;
    MetadataParseStage stage = MetadataParseStage::None;
    std::size_t index = std::numeric_limits<std::size_t>::max();

    explicit operator bool() const noexcept {
        return model != nullptr;
    }
};

MetadataParseResult parseMetadata(
    std::vector<std::uint8_t> bytes,
    bool allowErasedMagic = false) noexcept;
std::optional<std::size_t> probeMetadataSize(
    const std::vector<std::uint8_t>& headerBytes,
    std::size_t availableBytes,
    bool allowErasedMagic = false) noexcept;
bool isValidUtf8(const std::string& value) noexcept;

}
