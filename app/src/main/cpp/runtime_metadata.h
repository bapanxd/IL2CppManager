#pragma once

#include "direct_call_analyzer.h"
#include "metadata_parser.h"
#include "process_memory.h"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace il2cppmanager {

class RuntimeMetadataResolver final {
public:
    struct TypeSizes {
        std::uint32_t instanceSize = 0;
        std::int32_t nativeSize = -1;
        std::uint32_t staticFieldsSize = 0;
        std::uint32_t threadStaticFieldsSize = 0;
    };

    struct MethodReference {
        std::int32_t classIndex = -1;
        std::int32_t methodIndex = -1;
        std::string name;
        std::string ownerName;
        std::optional<std::string> signature;
        std::uint64_t address = 0;
        std::optional<std::uint64_t> rva;
        std::uint64_t callSiteAddress = 0;
        std::optional<std::uint64_t> callSiteRva;
        std::int32_t callSiteInstructionIndex = -1;
    };

    struct MethodReferencePage {
        std::size_t totalCount = 0;
        NativeAnalysisStatus status = NativeAnalysisStatus::Unavailable;
        std::int32_t indirectCallCount = 0;
        std::vector<MethodReference> items;
    };

    struct Instruction {
        std::uint64_t address = 0;
        std::optional<std::uint64_t> rva;
        std::string bytes;
        std::string mnemonic;
        std::string operands;
        NativeInstructionFlow flow = NativeInstructionFlow::None;
        std::int32_t targetInstructionIndex = -1;
        std::optional<MethodReference> target;
    };

    struct InstructionPage {
        std::size_t totalCount = 0;
        NativeAnalysisStatus status = NativeAnalysisStatus::Unavailable;
        std::int32_t indirectCallCount = 0;
        std::vector<Instruction> items;
    };

    static std::shared_ptr<RuntimeMetadataResolver> attach(
        std::int32_t pid,
        const std::string& moduleName,
        std::shared_ptr<const MetadataModel> model);

    std::optional<std::string> fieldTypeName(const FieldMetadata& field) const;
    std::optional<std::string> propertyTypeName(
        const TypeMetadata& type,
        const PropertyMetadata& property) const;
    std::optional<std::string> eventTypeName(const EventMetadata& event) const;
    std::optional<std::string> referencedTypeName(std::int32_t typeIndex) const;
    std::optional<std::int32_t> referencedTypeDefinitionIndex(std::int32_t typeIndex) const;
    std::optional<TypeSizes> typeSizes(std::int32_t typeDefinitionIndex) const;
    std::optional<std::int64_t> fieldOffset(std::int32_t typeIndex, std::size_t fieldIndex) const;
    std::optional<std::int32_t> fieldFlags(const FieldMetadata& field) const;
    std::optional<std::string> methodSignature(const MethodMetadata& method) const;
    std::optional<std::uint64_t> methodAddress(
        const TypeMetadata& type,
        const MethodMetadata& method) const;
    bool methodMatchesAddress(
        const TypeMetadata& type,
        const MethodMetadata& method,
        std::uint64_t query) const;
    MethodReferencePage methodCalls(
        std::int32_t classIndex,
        std::int32_t methodIndex,
        std::size_t offset,
        std::size_t limit) const;
    MethodReferencePage methodCallers(
        std::int32_t classIndex,
        std::int32_t methodIndex,
        std::size_t offset,
        std::size_t limit) const;
    InstructionPage methodInstructions(
        std::int32_t classIndex,
        std::int32_t methodIndex,
        std::size_t offset,
        std::size_t limit) const;
    bool isExecutableModuleAddress(std::uint64_t address) const noexcept;

private:
    struct TypeDefinitionKey {
        std::int32_t nameIndex;
        std::int32_t namespaceIndex;
        std::int32_t fieldStart;
        std::int32_t methodStart;

        bool operator==(const TypeDefinitionKey& other) const noexcept;
    };

    struct TypeDefinitionKeyHash {
        std::size_t operator()(const TypeDefinitionKey& value) const noexcept;
    };

    struct GenericParameterKey {
        std::int32_t ownerIndex;
        std::int32_t nameIndex;
        std::int16_t constraintsStart;
        std::int16_t constraintsCount;
        std::uint16_t number;
        std::uint16_t flags;

        bool operator==(const GenericParameterKey& other) const noexcept;
    };

    struct GenericParameterKeyHash {
        std::size_t operator()(const GenericParameterKey& value) const noexcept;
    };

    struct MetadataRegistration {
        std::uint64_t address = 0;
        std::uint32_t typesCount = 0;
        std::uint64_t types = 0;
        std::uint64_t fieldOffsets = 0;
        std::uint64_t typeSizes = 0;
    };

    struct CodeGenModule {
        std::uint64_t methodPointerCount = 0;
        std::uint64_t methodPointers = 0;
    };

    RuntimeMetadataResolver(
        std::int32_t pid,
        std::string moduleName,
        std::shared_ptr<const MetadataModel> model,
        std::vector<ProcessMapRegion> maps,
        std::vector<ProcessMapRegion> scanRegions);

    bool discover();
    bool validateMetadataRegistration(const MetadataRegistration& registration) const;
    std::optional<std::unordered_map<std::string, CodeGenModule>> validateCodeGenModules(
        std::uint64_t modulesAddress) const;

    bool isReadable(std::uint64_t address, std::size_t size) const noexcept;
    bool isMapped(std::uint64_t address, std::size_t size) const noexcept;
    bool readBytes(std::uint64_t address, void* destination, std::size_t size) const noexcept;
    std::optional<std::string> readString(std::uint64_t address) const;
    std::shared_ptr<const std::vector<std::int32_t>> fieldOffsets(
        std::int32_t typeIndex) const;
    std::shared_ptr<const std::vector<std::uint64_t>> methodPointers(
        const std::string& imageName) const;
    std::shared_ptr<DirectCallAnalyzer> directCallAnalyzer() const;
    std::shared_ptr<const NativeCodeSnapshot> nativeCodeSnapshot() const;
    MethodReference enrichMethodReference(
        const NativeMethodSymbol& symbol,
        std::uint64_t callSiteAddress,
        std::int32_t callSiteInstructionIndex) const;
    MethodReferencePage enrichMethodReferences(DirectMethodReferencePage page) const;
    std::optional<std::uint64_t> moduleRva(std::uint64_t address) const noexcept;

    template <typename Value>
    std::optional<Value> readValue(std::uint64_t address) const noexcept {
        Value value{};
        if (!readBytes(address, &value, sizeof(value))) {
            return std::nullopt;
        }
        return value;
    }

    std::optional<std::string> typeName(std::int32_t typeIndex) const;
    std::optional<std::string> typeNameAt(
        std::uint64_t typeAddress,
        std::size_t depth,
        std::unordered_set<std::uint64_t>& visited) const;
    std::optional<std::string> genericTypeName(
        std::uint64_t genericClassAddress,
        std::size_t depth,
        std::unordered_set<std::uint64_t>& visited) const;
    std::optional<std::int32_t> typeDefinitionIndex(std::uint64_t handle) const;
    std::optional<std::int32_t> genericParameterIndex(std::uint64_t handle) const;
    std::optional<std::string> genericParameterName(
        std::uint64_t handle,
        bool methodParameter) const;
    std::optional<std::int32_t> typeDefinitionIndexAt(
        std::uint64_t typeAddress,
        std::size_t depth,
        std::unordered_set<std::uint64_t>& visited) const;
    std::string typeDefinitionName(std::int32_t index) const;
    std::vector<std::int32_t> referencedTypeIndices() const;

    std::int32_t pid_;
    std::string moduleName_;
    std::shared_ptr<const MetadataModel> model_;
    std::vector<ProcessMapRegion> maps_;
    std::vector<ProcessMapRegion> scanRegions_;
    std::vector<ProcessMapRegion> moduleRegions_;
    std::uint64_t moduleBase_ = 0;
    bool moduleHasExecutableMapping_ = false;
    std::optional<NativeCodeArchitecture> moduleArchitecture_;
    std::vector<std::pair<std::uint64_t, std::uint64_t>> executableFileRanges_;
    MetadataRegistration metadataRegistration_;
    std::unordered_map<std::string, CodeGenModule> codeGenModules_;
    std::unordered_map<TypeDefinitionKey, std::int32_t, TypeDefinitionKeyHash> typeDefinitionIndices_;
    std::unordered_map<GenericParameterKey, std::int32_t, GenericParameterKeyHash>
        genericParameterIndices_;
    mutable std::mutex cacheMutex_;
    mutable std::unordered_map<std::int32_t, std::string> typeNameCache_;
    mutable std::unordered_map<
        std::int32_t,
        std::shared_ptr<const std::vector<std::int32_t>>> fieldOffsetCache_;
    mutable std::unordered_map<
        std::string,
        std::shared_ptr<const std::vector<std::uint64_t>>> methodPointerCache_;
    mutable std::once_flag directCallAnalyzerOnce_;
    mutable std::shared_ptr<DirectCallAnalyzer> directCallAnalyzer_;
};

}
