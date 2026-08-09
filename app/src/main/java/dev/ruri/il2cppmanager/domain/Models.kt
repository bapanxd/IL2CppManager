package dev.ruri.il2cppmanager.domain

data class PageResult<T>(
    val offset: Int,
    val totalCount: Int,
    val items: List<T>,
) {
    init {
        require(offset >= 0)
        require(totalCount >= 0)
        require(offset <= totalCount)
        require(items.size <= totalCount - offset)
    }
}

data class ProcessDescriptor(
    val pid: Int,
    val name: String,
    val startTicks: Long,
)

data class AssemblyDescriptor(
    val index: Int,
    val name: String,
)

data class NamespaceDescriptor(
    val index: Int,
    val name: String,
)

data class ClassDescriptor(
    val index: Int,
    val name: String,
)

data class TypeSearchDescriptor(
    val index: Int,
    val name: String,
    val qualifiedName: String,
)

enum class SymbolKind(val wireValue: Int) {
    CLASS(0),
    FIELD(1),
    METHOD(2),
    ;

    companion object {
        fun fromWireValue(value: Int): SymbolKind? = entries.firstOrNull { it.wireValue == value }
    }
}

data class SymbolSearchDescriptor(
    val kind: SymbolKind,
    val classIndex: Int,
    val memberIndex: Int,
    val name: String,
    val assemblyName: String,
    val ownerName: String,
)

enum class SearchMatchMode(val wireValue: Int) {
    CONTAINS(0),
    EXACT(1),
    ;

    companion object {
        fun fromWireValue(value: Int): SearchMatchMode? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class MemberKind(val wireValue: Int) {
    FIELD(1),
    METHOD(2),
    PROPERTY(3),
    EVENT(4),
    NESTED_TYPE(5),
    INTERFACE(6),
    ;

    companion object {
        fun fromWireValue(value: Int): MemberKind? = entries.firstOrNull { it.wireValue == value }
    }
}

data class TypeReferenceDescriptor(
    val index: Int,
    val typeIndex: Int,
    val definitionIndex: Int?,
    val name: String?,
)

data class TypeSizeDescriptor(
    val instanceSize: Long,
    val nativeSize: Long,
    val staticFieldsSize: Long,
    val threadStaticFieldsSize: Long,
)

data class ClassInfoDescriptor(
    val index: Int,
    val name: String,
    val namespaceName: String,
    val assemblyIndex: Int,
    val assemblyName: String,
    val flags: Long,
    val token: Long,
    val bitfield: Long,
    val parentType: TypeReferenceDescriptor?,
    val declaringType: TypeReferenceDescriptor?,
    val sizes: TypeSizeDescriptor?,
)

data class FieldDescriptor(
    val index: Int,
    val name: String,
    val typeIndex: Int,
    val typeName: String?,
    val offset: Long?,
    val flags: Int?,
) {
    companion object {
        const val THREAD_STATIC_OFFSET = -1L
        const val STATIC_FLAG = 0x0010
    }
}

data class PropertyDescriptor(
    val index: Int,
    val name: String,
    val typeIndex: Int?,
    val typeName: String?,
    val getterFlags: Int?,
    val setterFlags: Int?,
    val attributes: Long,
    val token: Long,
)

data class EventDescriptor(
    val index: Int,
    val name: String,
    val typeIndex: Int,
    val typeName: String?,
    val addFlags: Int?,
    val removeFlags: Int?,
    val raiseFlags: Int?,
    val token: Long,
)

data class MethodDescriptor(
    val index: Int,
    val name: String,
    val signature: String?,
    val address: Long?,
    val rva: Long?,
)

enum class MethodAnalysisSection(val wireValue: Int) {
    INSTRUCTIONS(0),
    CALLS(1),
    CALLERS(2),
    ;

    companion object {
        fun fromWireValue(value: Int): MethodAnalysisSection? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class MethodAnalysisStatus(val wireValue: Int) {
    COMPLETE(0),
    PARTIAL_CONTROL_FLOW(1),
    PARTIAL_LIMIT(2),
    UNAVAILABLE(3),
    ;

    companion object {
        fun fromWireValue(value: Int): MethodAnalysisStatus? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class InstructionFlowKind(val wireValue: Int) {
    NONE(0),
    DIRECT_CALL(1),
    DIRECT_BRANCH(2),
    INDIRECT_CALL(3),
    INDIRECT_BRANCH(4),
    ;

    companion object {
        fun fromWireValue(value: Int): InstructionFlowKind? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class MethodReferenceDescriptor(
    val classIndex: Int?,
    val methodIndex: Int?,
    val name: String?,
    val ownerName: String?,
    val signature: String?,
    val address: Long,
    val rva: Long?,
    val callSiteAddress: Long,
    val callSiteRva: Long?,
    val callSiteInstructionIndex: Int,
) {
    init {
        require(address > 0)
        require(rva == null || rva >= 0)
        require(callSiteAddress > 0)
        require(callSiteRva == null || callSiteRva >= 0)
        require(callSiteInstructionIndex >= 0)
        require(classIndex == null || classIndex >= 0)
        require(methodIndex == null || methodIndex >= 0)
        val resolved = classIndex != null
        require((methodIndex != null) == resolved)
        require((name != null) == resolved)
        require((ownerName != null) == resolved)
        require(signature == null || resolved)
    }
}

data class InstructionDescriptor(
    val address: Long,
    val rva: Long?,
    val bytes: String,
    val mnemonic: String,
    val operands: String,
    val flowKind: InstructionFlowKind,
    val targetInstructionIndex: Int?,
    val target: MethodReferenceDescriptor?,
) {
    init {
        require(address > 0)
        require(rva == null || rva >= 0)
        require(bytes.isNotBlank())
        require(mnemonic.isNotBlank())
        require(targetInstructionIndex == null || targetInstructionIndex >= 0)
        val directFlow = flowKind == InstructionFlowKind.DIRECT_CALL ||
            flowKind == InstructionFlowKind.DIRECT_BRANCH
        require((target != null) == directFlow)
        require(target == null || target.callSiteAddress == address)
    }
}

data class MethodAnalysisResult<T>(
    val page: PageResult<T>,
    val status: MethodAnalysisStatus,
    val unresolvedIndirectCallCount: Int,
) {
    init {
        require(unresolvedIndirectCallCount >= 0)
    }
}

enum class FieldReadStatus(val wireValue: Int) {
    SUCCESS(0),
    UNRESOLVED(1),
    UNSUPPORTED_TYPE(2),
    INVALID_ADDRESS(3),
    READ_FAILED(4),
    ;
}

data class FieldReadResult(
    val fieldIndex: Int,
    val address: Long,
    val status: FieldReadStatus,
    val kind: ValueKind,
    val displayValue: String,
)
