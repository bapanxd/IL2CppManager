#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>

namespace il2cppmanager {

struct CountedMetadataSection {
    std::uint8_t directoryIndex = 0;
    std::size_t offset = 0;
    std::size_t size = 0;
    std::size_t count = 0;
};

struct CountedMetadataSections {
    CountedMetadataSection strings;
    CountedMetadataSection events;
    CountedMetadataSection properties;
    CountedMetadataSection methods;
    CountedMetadataSection parameters;
    CountedMetadataSection fields;
    CountedMetadataSection genericParameters;
    CountedMetadataSection genericParameterConstraints;
    CountedMetadataSection genericContainers;
    CountedMetadataSection nestedTypes;
    CountedMetadataSection interfaces;
    CountedMetadataSection vtableMethods;
    CountedMetadataSection interfaceOffsets;
    CountedMetadataSection typeDefinitions;
    CountedMetadataSection images;
    CountedMetadataSection assemblies;
};

struct CountedMetadataIndexWidths {
    std::size_t type = 0;
    std::size_t typeDefinition = 0;
    std::size_t genericContainer = 0;
    std::size_t field = 0;
    std::size_t method = 0;
    std::size_t event = 0;
    std::size_t property = 0;
    std::size_t nestedType = 0;
    std::size_t interface = 0;
    std::size_t parameter = 0;
    std::size_t genericParameter = 0;
};

struct CountedTypeDefinitionLayout {
    std::size_t size = 0;
    std::size_t byValueTypeIndex = 0;
    std::size_t declaringTypeIndex = 0;
    std::size_t parentTypeIndex = 0;
    std::size_t genericContainerIndex = 0;
    std::size_t flags = 0;
    std::size_t fieldStart = 0;
    std::size_t methodStart = 0;
    std::size_t eventStart = 0;
    std::size_t propertyStart = 0;
    std::size_t nestedTypeStart = 0;
    std::size_t interfaceStart = 0;
    std::size_t vtableStart = 0;
    std::size_t interfaceOffsetStart = 0;
    std::size_t methodCount = 0;
    std::size_t propertyCount = 0;
    std::size_t fieldCount = 0;
    std::size_t eventCount = 0;
    std::size_t nestedTypeCount = 0;
    std::size_t vtableCount = 0;
    std::size_t interfaceCount = 0;
    std::size_t interfaceOffsetCount = 0;
    std::size_t bitfield = 0;
    std::size_t token = 0;
};

struct CountedMethodDefinitionLayout {
    std::size_t size = 0;
    std::size_t declaringTypeIndex = 0;
    std::size_t returnTypeIndex = 0;
    std::size_t parameterStart = 0;
    std::size_t genericContainerIndex = 0;
    std::size_t token = 0;
    std::size_t flags = 0;
    std::size_t parameterCount = 0;
};

struct CountedFieldDefinitionLayout {
    std::size_t size = 0;
    std::size_t typeIndex = 0;
    std::size_t token = 0;
};

struct CountedParameterDefinitionLayout {
    std::size_t size = 0;
    std::size_t token = 0;
    std::size_t typeIndex = 0;
};

struct CountedPropertyDefinitionLayout {
    std::size_t size = 0;
    std::size_t getterIndex = 0;
    std::size_t setterIndex = 0;
    std::size_t attributes = 0;
    std::size_t token = 0;
};

struct CountedEventDefinitionLayout {
    std::size_t size = 0;
    std::size_t typeIndex = 0;
    std::size_t addIndex = 0;
    std::size_t removeIndex = 0;
    std::size_t raiseIndex = 0;
    std::size_t token = 0;
};

struct CountedGenericParameterDefinitionLayout {
    std::size_t size = 0;
    std::size_t ownerIndex = 0;
    std::size_t nameIndex = 0;
    std::size_t constraintsStart = 0;
    std::size_t constraintsCount = 0;
    std::size_t number = 0;
    std::size_t flags = 0;
};

struct CountedGenericContainerDefinitionLayout {
    std::size_t size = 0;
    std::size_t parameterCount = 0;
    std::size_t parameterCountSize = 0;
    std::size_t isMethod = 0;
    std::size_t isMethodSize = 0;
    std::size_t parameterStart = 0;
};

struct CountedImageDefinitionLayout {
    std::size_t size = 0;
    std::size_t typeStart = 0;
    std::size_t typeCount = 0;
};

struct CountedAssemblyDefinitionLayout {
    std::size_t size = 0;
    std::size_t nameIndex = 0;
};

struct CountedInterfaceOffsetLayout {
    std::size_t size = 0;
    std::size_t typeIndex = 0;
    std::size_t offset = 0;
};

struct CountedMetadataLayout {
    CountedMetadataIndexWidths widths;
    CountedTypeDefinitionLayout type;
    CountedMethodDefinitionLayout method;
    CountedFieldDefinitionLayout field;
    CountedParameterDefinitionLayout parameter;
    CountedPropertyDefinitionLayout property;
    CountedEventDefinitionLayout event;
    CountedGenericParameterDefinitionLayout genericParameter;
    CountedGenericContainerDefinitionLayout genericContainer;
    CountedImageDefinitionLayout image;
    CountedAssemblyDefinitionLayout assembly;
    CountedInterfaceOffsetLayout interfaceOffset;
    std::size_t nestedTypeRecordSize = 0;
    std::size_t interfaceRecordSize = 0;
    std::size_t vtableMethodRecordSize = 0;
    std::size_t genericParameterConstraintRecordSize = 0;
    bool allowsUnclaimedFields = false;
    bool allowsUnclaimedParameters = false;
};

struct ComplementedCountedMetadataProbe {
    std::int32_t normalizedVersion = 0;
    std::size_t metadataSize = 0;
};

struct ComplementedCountedMetadataInference {
    std::int32_t normalizedVersion = 0;
    std::size_t metadataSize = 0;
    bool supportsRuntimeMetadata = false;
    std::int8_t runtimeTypeByReferenceBit = -1;
    CountedMetadataSections sections;
    CountedMetadataLayout layout;
};

std::optional<ComplementedCountedMetadataProbe> probeComplementedCountedMetadata(
    const std::uint8_t* headerBytes,
    std::size_t headerBytesSize,
    std::size_t availableBytes) noexcept;

std::optional<ComplementedCountedMetadataInference> inferComplementedCountedMetadata(
    const std::uint8_t* bytes,
    std::size_t size) noexcept;

}
