package dev.ruri.il2cppmanager.nativebridge

object NativeEngine {
    const val UNAVAILABLE_FIELD_OFFSET = Long.MIN_VALUE

    private const val LIBRARY_NAME = "il2cppmanager"

    private val loadFailure = runCatching {
        System.loadLibrary(LIBRARY_NAME)
    }.exceptionOrNull()

    fun ensureLoaded() {
        loadFailure?.let { throw NativeEngineUnavailableException(it) }
    }

    external fun scanProcessIds(): IntArray
    external fun processName(pid: Int): String?
    external fun processStartTicks(pid: Int): Long
    external fun moduleBase(pid: Int, moduleName: String): Long
    external fun openMetadata(bytes: ByteArray, allowErasedMagic: Boolean): Long
    external fun probeMetadataSize(
        headerBytes: ByteArray,
        availableBytes: Long,
        allowErasedMagic: Boolean,
    ): Long
    external fun closeMetadata(handle: Long)
    external fun attachRuntime(handle: Long, pid: Int): Boolean
    external fun assemblyCount(handle: Long): Int
    external fun assemblyName(handle: Long, index: Int): String?
    external fun assemblyTypeIndices(handle: Long, assemblyIndex: Int): IntArray?
    external fun assemblyTypeNames(handle: Long, assemblyIndex: Int): Array<String>?
    external fun searchSymbols(
        handle: Long,
        query: String,
        exactMatch: Boolean,
        matchCase: Boolean,
        offset: Int,
        limit: Int,
        knownTotalCount: Int,
    ): NativeSymbolSearchPage?
    external fun namespaceCount(handle: Long, assemblyIndex: Int): Int
    external fun namespaceName(handle: Long, assemblyIndex: Int, index: Int): String?
    external fun classCount(handle: Long, assemblyIndex: Int, namespaceIndex: Int): Int
    external fun className(handle: Long, assemblyIndex: Int, namespaceIndex: Int, index: Int): String?
    external fun classIndex(handle: Long, assemblyIndex: Int, namespaceIndex: Int, index: Int): Int
    external fun classFlags(handle: Long, classIndex: Int): Long
    external fun classToken(handle: Long, classIndex: Int): Long
    external fun classBitfield(handle: Long, classIndex: Int): Long
    external fun classDefinitionName(handle: Long, classIndex: Int): String?
    external fun classNamespaceName(handle: Long, classIndex: Int): String?
    external fun classAssemblyName(handle: Long, classIndex: Int): String?
    external fun classAssemblyIndex(handle: Long, classIndex: Int): Int
    external fun classParentTypeIndex(handle: Long, classIndex: Int): Int
    external fun classParentTypeName(handle: Long, classIndex: Int): String?
    external fun classParentDefinitionIndex(handle: Long, classIndex: Int): Int
    external fun classDeclaringTypeIndex(handle: Long, classIndex: Int): Int
    external fun classDeclaringTypeName(handle: Long, classIndex: Int): String?
    external fun classDeclaringDefinitionIndex(handle: Long, classIndex: Int): Int
    external fun classTypeSizes(handle: Long, classIndex: Int): LongArray?
    external fun nestedTypeCount(handle: Long, classIndex: Int): Int
    external fun nestedTypeIndex(handle: Long, classIndex: Int, nestedIndex: Int): Int
    external fun nestedTypeName(handle: Long, classIndex: Int, nestedIndex: Int): String?
    external fun interfaceCount(handle: Long, classIndex: Int): Int
    external fun interfaceTypeIndex(handle: Long, classIndex: Int, interfaceIndex: Int): Int
    external fun interfaceTypeName(handle: Long, classIndex: Int, interfaceIndex: Int): String?
    external fun interfaceDefinitionIndex(handle: Long, classIndex: Int, interfaceIndex: Int): Int
    external fun fieldCount(handle: Long, classIndex: Int): Int
    external fun fieldName(handle: Long, classIndex: Int, fieldIndex: Int): String?
    external fun fieldTypeIndex(handle: Long, classIndex: Int, fieldIndex: Int): Int
    external fun fieldTypeName(handle: Long, classIndex: Int, fieldIndex: Int): String?
    external fun fieldOffset(handle: Long, classIndex: Int, fieldIndex: Int): Long
    external fun fieldFlags(handle: Long, classIndex: Int, fieldIndex: Int): Int
    external fun propertyCount(handle: Long, classIndex: Int): Int
    external fun propertyName(handle: Long, classIndex: Int, propertyIndex: Int): String?
    external fun propertyTypeIndex(handle: Long, classIndex: Int, propertyIndex: Int): Int
    external fun propertyTypeName(handle: Long, classIndex: Int, propertyIndex: Int): String?
    external fun propertyGetterFlags(handle: Long, classIndex: Int, propertyIndex: Int): Int
    external fun propertySetterFlags(handle: Long, classIndex: Int, propertyIndex: Int): Int
    external fun propertyAttributes(handle: Long, classIndex: Int, propertyIndex: Int): Long
    external fun propertyToken(handle: Long, classIndex: Int, propertyIndex: Int): Long
    external fun eventCount(handle: Long, classIndex: Int): Int
    external fun eventName(handle: Long, classIndex: Int, eventIndex: Int): String?
    external fun eventTypeIndex(handle: Long, classIndex: Int, eventIndex: Int): Int
    external fun eventTypeName(handle: Long, classIndex: Int, eventIndex: Int): String?
    external fun eventAddFlags(handle: Long, classIndex: Int, eventIndex: Int): Int
    external fun eventRemoveFlags(handle: Long, classIndex: Int, eventIndex: Int): Int
    external fun eventRaiseFlags(handle: Long, classIndex: Int, eventIndex: Int): Int
    external fun eventToken(handle: Long, classIndex: Int, eventIndex: Int): Long
    external fun methodCount(handle: Long, classIndex: Int): Int
    external fun methodName(handle: Long, classIndex: Int, methodIndex: Int): String?
    external fun methodSignature(handle: Long, classIndex: Int, methodIndex: Int): String?
    external fun methodAddress(handle: Long, classIndex: Int, methodIndex: Int): Long
    external fun methodCalls(
        handle: Long,
        classIndex: Int,
        methodIndex: Int,
        offset: Int,
        limit: Int,
    ): NativeMethodReferencePage?
    external fun methodCallers(
        handle: Long,
        classIndex: Int,
        methodIndex: Int,
        offset: Int,
        limit: Int,
    ): NativeMethodReferencePage?
    external fun methodInstructions(
        handle: Long,
        classIndex: Int,
        methodIndex: Int,
        offset: Int,
        limit: Int,
    ): NativeInstructionPage?
    external fun isExecutableModuleAddress(handle: Long, address: Long): Boolean
    external fun readMemory(pid: Int, address: Long, size: Int): ByteArray?
    external fun writeMemory(pid: Int, address: Long, bytes: ByteArray): Int
}

class NativeSymbolSearchPage(
    val totalCount: Int,
    val kinds: IntArray,
    val classIndices: IntArray,
    val memberIndices: IntArray,
    val names: Array<String>,
    val assemblyNames: Array<String>,
    val ownerNames: Array<String>,
)

class NativeMethodReferencePage(
    val totalCount: Int,
    val status: Int,
    val indirectCount: Int,
    val classIndices: IntArray,
    val methodIndices: IntArray,
    val names: Array<String>,
    val ownerNames: Array<String>,
    val signatures: Array<String>,
    val referenceResolved: BooleanArray,
    val signatureResolved: BooleanArray,
    val addresses: LongArray,
    val rvas: LongArray,
    val rvaResolved: BooleanArray,
    val callSiteAddresses: LongArray,
    val callSiteRvas: LongArray,
    val callSiteRvaResolved: BooleanArray,
    val callSiteInstructionIndices: IntArray,
)

class NativeInstructionPage(
    val totalCount: Int,
    val status: Int,
    val indirectCount: Int,
    val addresses: LongArray,
    val rvas: LongArray,
    val rvaResolved: BooleanArray,
    val bytes: Array<String>,
    val mnemonics: Array<String>,
    val operands: Array<String>,
    val flowKinds: IntArray,
    val targetInstructionIndices: IntArray,
    val targetClassIndices: IntArray,
    val targetMethodIndices: IntArray,
    val targetNames: Array<String>,
    val targetOwnerNames: Array<String>,
    val targetSignatures: Array<String>,
    val targetPresent: BooleanArray,
    val targetMethodResolved: BooleanArray,
    val targetSignatureResolved: BooleanArray,
    val targetAddresses: LongArray,
    val targetRvas: LongArray,
    val targetRvaResolved: BooleanArray,
)

class NativeEngineUnavailableException(cause: Throwable) :
    IllegalStateException("Native engine could not be loaded", cause)
