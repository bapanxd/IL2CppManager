package dev.ruri.il2cppmanager.root

import android.os.Bundle
import dev.ruri.il2cppmanager.domain.AssemblyDescriptor
import dev.ruri.il2cppmanager.domain.ClassDescriptor
import dev.ruri.il2cppmanager.domain.ClassInfoDescriptor
import dev.ruri.il2cppmanager.domain.EventDescriptor
import dev.ruri.il2cppmanager.domain.FieldDescriptor
import dev.ruri.il2cppmanager.domain.FieldReadResult
import dev.ruri.il2cppmanager.domain.FieldReadStatus
import dev.ruri.il2cppmanager.domain.InstructionDescriptor
import dev.ruri.il2cppmanager.domain.InstructionFlowKind
import dev.ruri.il2cppmanager.domain.MemberKind
import dev.ruri.il2cppmanager.domain.MethodAnalysisResult
import dev.ruri.il2cppmanager.domain.MethodAnalysisSection
import dev.ruri.il2cppmanager.domain.MethodAnalysisStatus
import dev.ruri.il2cppmanager.domain.MethodDescriptor
import dev.ruri.il2cppmanager.domain.MethodReferenceDescriptor
import dev.ruri.il2cppmanager.domain.NamespaceDescriptor
import dev.ruri.il2cppmanager.domain.PageResult
import dev.ruri.il2cppmanager.domain.PrimitiveCodec
import dev.ruri.il2cppmanager.domain.ProcessDescriptor
import dev.ruri.il2cppmanager.domain.PropertyDescriptor
import dev.ruri.il2cppmanager.domain.SearchMatchMode
import dev.ruri.il2cppmanager.domain.SymbolKind
import dev.ruri.il2cppmanager.domain.SymbolSearchDescriptor
import dev.ruri.il2cppmanager.domain.TypeReferenceDescriptor
import dev.ruri.il2cppmanager.domain.TypeSearchDescriptor
import dev.ruri.il2cppmanager.domain.TypeSizeDescriptor
import dev.ruri.il2cppmanager.domain.ValueKind
import dev.ruri.il2cppmanager.ipc.IpcContract
import dev.ruri.il2cppmanager.ipc.MemberPageRequest
import dev.ruri.il2cppmanager.ipc.PageRequest
import dev.ruri.il2cppmanager.ipc.RequestPayloadCodec
import dev.ruri.il2cppmanager.ipc.ResponsePayloadCodec
import dev.ruri.il2cppmanager.nativebridge.NativeEngine
import dev.ruri.il2cppmanager.nativebridge.NativeInstructionPage
import dev.ruri.il2cppmanager.nativebridge.NativeMethodReferencePage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

internal class RootCommandDispatcher(
    private val metadataLocator: MetadataLocator = MetadataLocator(),
) : AutoCloseable {
    private var session: TargetSession? = null
    private var processSnapshot: List<ProcessDescriptor>? = null

    fun handle(command: Int, payload: Bundle): Bundle = when (command) {
        IpcContract.Command.SCAN_PROCESSES -> scanProcesses(payload)
        IpcContract.Command.OPEN_TARGET -> openTarget(payload)
        IpcContract.Command.LIST_ASSEMBLIES -> listAssemblies(payload)
        IpcContract.Command.LIST_NAMESPACES -> listNamespaces(payload)
        IpcContract.Command.LIST_CLASSES -> listClasses(payload)
        IpcContract.Command.CLASS_INFO -> classInfo(payload)
        IpcContract.Command.SEARCH_TYPES -> searchTypes(payload)
        IpcContract.Command.SEARCH_SYMBOLS -> searchSymbols(payload)
        IpcContract.Command.CLASS_MEMBERS -> listClassMembers(payload)
        IpcContract.Command.METHOD_ANALYSIS -> methodAnalysis(payload)
        IpcContract.Command.READ_VISIBLE_FIELDS -> readVisibleFields(payload)
        IpcContract.Command.WRITE_PRIMITIVE -> writePrimitive(payload)
        IpcContract.Command.CLOSE_TARGET -> closeTarget()
        else -> throw ServiceFault(IpcContract.Error.UNKNOWN_COMMAND, "Unknown command")
    }

    override fun close() {
        processSnapshot = null
        closeSession()
    }

    private fun scanProcesses(payload: Bundle): Bundle {
        val page = RequestPayloadCodec.decodeScanProcesses(payload)
        val processes = if (page.offset == 0 || processSnapshot == null) {
            NativeEngine.scanProcessIds()
                .asSequence()
                .filter { it > 0 }
                .distinct()
                .sorted()
                .take(IpcContract.MAX_PROCESS_SCAN_COUNT)
                .mapNotNull(::describeProcess)
                .sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER, ProcessDescriptor::name)
                        .thenBy(ProcessDescriptor::pid),
                )
                .toList()
                .also { processSnapshot = it }
        } else {
            requireNotNull(processSnapshot)
        }
        val result = processes.page(page)
        if (result.offset + result.items.size >= result.totalCount) {
            processSnapshot = null
        }
        return ResponsePayloadCodec.processPage(result)
    }

    private fun describeProcess(pid: Int): ProcessDescriptor? = runCatching {
        val startTicks = NativeEngine.processStartTicks(pid)
        if (startTicks <= 0) {
            return null
        }
        val name = NativeEngine.processName(pid)
            ?.substringBefore(NULL_CHARACTER)
            ?.take(IpcContract.MAX_PROCESS_NAME_LENGTH)
            ?.takeIf(String::isNotBlank)
            ?: return null
        ProcessDescriptor(
            pid = pid,
            name = name,
            startTicks = startTicks,
        )
    }.getOrNull()

    private fun openTarget(payload: Bundle): Bundle {
        val request = RequestPayloadCodec.decodeOpenTarget(payload)
        val actualStartTicks = NativeEngine.processStartTicks(request.pid)
        if (actualStartTicks <= 0) {
            throw ServiceFault(IpcContract.Error.PROCESS_NOT_FOUND, "Target process no longer exists")
        }
        if (actualStartTicks != request.startTicks) {
            throw ServiceFault(IpcContract.Error.PROCESS_CHANGED, "Target process identity changed")
        }
        val moduleBase = NativeEngine.moduleBase(request.pid, IL2CPP_MODULE_NAME)
        if (moduleBase <= 0) {
            throw ServiceFault(IpcContract.Error.NOT_IL2CPP, "Not a Unity IL2CPP app")
        }
        val processMaps = ProcessMaps.load(request.pid)
        var metadataHandle = 0L
        try {
            metadataHandle = metadataLocator.open(request.pid, processMaps)
            NativeEngine.attachRuntime(metadataHandle, request.pid)
            val newSession = TargetSession(
                pid = request.pid,
                startTicks = request.startTicks,
                moduleBase = moduleBase,
                metadataHandle = metadataHandle,
                maps = processMaps,
            )
            closeSession()
            session = newSession
            metadataHandle = 0L
            return Bundle()
        } finally {
            if (metadataHandle > 0) {
                runCatching { NativeEngine.closeMetadata(metadataHandle) }
            }
        }
    }

    private fun listAssemblies(payload: Bundle): Bundle {
        val target = requireSession()
        val page = RequestPayloadCodec.decodeListAssemblies(payload)
        val totalCount = requireMetadataCount(
            NativeEngine.assemblyCount(target.metadataHandle),
            "assembly count",
        )
        val result = page.build(totalCount) { index ->
            AssemblyDescriptor(
                index = index,
                name = requireMetadataName(
                    NativeEngine.assemblyName(target.metadataHandle, index),
                    "assembly",
                ),
            )
        }
        return ResponsePayloadCodec.assemblyPage(result)
    }

    private fun listNamespaces(payload: Bundle): Bundle {
        val target = requireSession()
        val request = RequestPayloadCodec.decodeListNamespaces(payload)
        requireAssemblyIndex(target, request.assemblyIndex)
        val totalCount = requireMetadataCount(
            NativeEngine.namespaceCount(target.metadataHandle, request.assemblyIndex),
            "namespace count",
        )
        val result = request.page.build(totalCount) { index ->
            NamespaceDescriptor(
                index = index,
                name = requireMetadataName(
                    NativeEngine.namespaceName(target.metadataHandle, request.assemblyIndex, index),
                    "namespace",
                ),
            )
        }
        return ResponsePayloadCodec.namespacePage(result)
    }

    private fun listClasses(payload: Bundle): Bundle {
        val target = requireSession()
        val request = RequestPayloadCodec.decodeListClasses(payload)
        requireAssemblyIndex(target, request.assemblyIndex)
        requireNamespaceIndex(target, request.assemblyIndex, request.namespaceIndex)
        val totalCount = requireMetadataCount(
            NativeEngine.classCount(
                target.metadataHandle,
                request.assemblyIndex,
                request.namespaceIndex,
            ),
            "class count",
        )
        val result = request.page.build(totalCount) { index ->
            val classIndex = NativeEngine.classIndex(
                target.metadataHandle,
                request.assemblyIndex,
                request.namespaceIndex,
                index,
            )
            if (classIndex < 0 || classIndex >= IpcContract.MAX_HIERARCHY_COUNT) {
                throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Invalid class index")
            }
            ClassDescriptor(
                index = classIndex,
                name = requireMetadataName(
                    NativeEngine.className(
                        target.metadataHandle,
                        request.assemblyIndex,
                        request.namespaceIndex,
                        index,
                    ),
                    "class",
                ),
            )
        }
        return ResponsePayloadCodec.classPage(result)
    }

    private fun searchTypes(payload: Bundle): Bundle {
        val target = requireSession()
        val request = RequestPayloadCodec.decodeSearchTypes(payload)
        requireAssemblyIndex(target, request.assemblyIndex)
        val key = TypeSearchKey(
            request.assemblyIndex,
            request.query,
            request.matchMode,
            request.matchCase,
        )
        val index = typeSearchIndex(target, request.assemblyIndex)
        val positions = target.cachedTypeSearch
            ?.takeIf { it.key == key }
            ?.positions
            ?: matchingTypePositions(index, key)
                .also { target.cachedTypeSearch = CachedTypeSearch(key, it) }
        val fromIndex = min(request.page.offset, positions.size)
        val toIndex = min(fromIndex + request.page.limit, positions.size)
        val result = PageResult(
            offset = request.page.offset,
            totalCount = positions.size,
            items = (fromIndex until toIndex).map { resultIndex ->
                val position = positions[resultIndex]
                val typeIndex = index.indices[position]
                val name = index.names[position]
                TypeSearchDescriptor(
                    index = typeIndex,
                    name = name,
                    qualifiedName = qualifiedTypeName(target, index, typeIndex),
                )
            },
        )
        return ResponsePayloadCodec.typeSearchPage(result)
    }

    private fun searchSymbols(payload: Bundle): Bundle {
        val target = requireSession()
        val request = RequestPayloadCodec.decodeSearchSymbols(payload)
        val key = SymbolSearchKey(request.query, request.matchMode, request.matchCase)
        val knownTotalCount = target.symbolSearchSummary
            ?.takeIf { request.page.offset > 0 && it.key == key }
            ?.totalCount
        val nativePage = NativeEngine.searchSymbols(
            target.metadataHandle,
            request.query,
            request.matchMode == SearchMatchMode.EXACT,
            request.matchCase,
            request.page.offset,
            request.page.limit,
            knownTotalCount ?: -1,
        ) ?: throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Symbol search is unavailable")
        val size = nativePage.kinds.size
        if (size > request.page.limit ||
            nativePage.classIndices.size != size ||
            nativePage.memberIndices.size != size ||
            nativePage.names.size != size ||
            nativePage.assemblyNames.size != size ||
            nativePage.ownerNames.size != size ||
            nativePage.totalCount !in 0..IpcContract.MAX_SYMBOL_COUNT ||
            size > nativePage.totalCount ||
            size > 0 && request.page.offset > nativePage.totalCount - size) {
            throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Invalid symbol search page")
        }
        if (knownTotalCount != null && nativePage.totalCount != knownTotalCount) {
            throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Symbol search total changed")
        }
        val items = List(size) { index ->
            val kind = SymbolKind.fromWireValue(nativePage.kinds[index])
                ?: throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Invalid symbol kind")
            val classIndex = requireMetadataIndex(nativePage.classIndices[index], "class index")
            val memberIndex = nativePage.memberIndices[index]
            if (kind == SymbolKind.CLASS && memberIndex != -1 ||
                kind != SymbolKind.CLASS && memberIndex !in 0 until IpcContract.MAX_HIERARCHY_COUNT) {
                throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Invalid symbol member index")
            }
            SymbolSearchDescriptor(
                kind = kind,
                classIndex = classIndex,
                memberIndex = memberIndex,
                name = requireMetadataName(nativePage.names[index], "symbol"),
                assemblyName = requireMetadataName(nativePage.assemblyNames[index], "assembly"),
                ownerName = nativePage.ownerNames[index].also { ownerName ->
                    if (ownerName.length > IpcContract.MAX_QUALIFIED_NAME_LENGTH) {
                        throw ServiceFault(
                            IpcContract.Error.METADATA_UNSUPPORTED,
                            "Type path is too long",
                        )
                    }
                },
            )
        }
        if (request.page.offset == 0) {
            target.symbolSearchSummary = SymbolSearchSummary(key, nativePage.totalCount)
        }
        return ResponsePayloadCodec.symbolSearchPage(
            PageResult(request.page.offset, nativePage.totalCount, items),
        )
    }

    private fun typeSearchIndex(target: TargetSession, assemblyIndex: Int): TypeSearchIndex {
        target.typeSearchIndex?.takeIf { it.assemblyIndex == assemblyIndex }?.let { return it.value }
        val indices = NativeEngine.assemblyTypeIndices(target.metadataHandle, assemblyIndex)
            ?: throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Type index is unavailable")
        val names = NativeEngine.assemblyTypeNames(target.metadataHandle, assemblyIndex)
            ?: throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Type names are unavailable")
        if (indices.size != names.size || indices.size > IpcContract.MAX_HIERARCHY_COUNT) {
            throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Invalid type search index")
        }
        var previousIndex = -1
        indices.indices.forEach { position ->
            val index = requireMetadataIndex(indices[position], "type definition index")
            if (index <= previousIndex) {
                throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Unordered type definition index")
            }
            previousIndex = index
            requireMetadataName(names[position], "type")
        }
        return TypeSearchIndex(indices, names).also {
            target.typeSearchIndex = CachedTypeSearchIndex(assemblyIndex, it)
            target.cachedTypeSearch = null
        }
    }

    private fun matchingTypePositions(index: TypeSearchIndex, key: TypeSearchKey): IntArray {
        fun matches(name: String): Boolean = when (key.matchMode) {
            SearchMatchMode.CONTAINS -> name.contains(key.query, ignoreCase = !key.matchCase)
            SearchMatchMode.EXACT -> name.equals(key.query, ignoreCase = !key.matchCase)
        }
        var count = 0
        index.names.forEach { if (matches(it)) count++ }
        val positions = IntArray(count)
        var outputIndex = 0
        index.names.indices.forEach { position ->
            if (matches(index.names[position])) {
                positions[outputIndex++] = position
            }
        }
        return positions
    }

    private fun qualifiedTypeName(
        target: TargetSession,
        index: TypeSearchIndex,
        typeIndex: Int,
    ): String {
        val names = ArrayList<String>(MAX_TYPE_NESTING_DEPTH)
        val visited = HashSet<Int>(MAX_TYPE_NESTING_DEPTH)
        var currentIndex = typeIndex
        repeat(MAX_TYPE_NESTING_DEPTH) {
            if (!visited.add(currentIndex)) {
                throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Cyclic declaring type chain")
            }
            val position = index.indices.binarySearch(currentIndex)
            if (position < 0) {
                throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Declaring type is outside the assembly")
            }
            names += index.names[position]
            val declaringIndex = NativeEngine.classDeclaringDefinitionIndex(
                target.metadataHandle,
                currentIndex,
            )
            if (declaringIndex < 0) {
                val namespaceName = requireMetadataName(
                    NativeEngine.classNamespaceName(target.metadataHandle, currentIndex),
                    "type namespace",
                )
                val localName = names.asReversed().joinToString(NESTED_TYPE_SEPARATOR)
                val qualifiedName = if (namespaceName.isBlank()) {
                    localName
                } else {
                    "$namespaceName$NAMESPACE_SEPARATOR$localName"
                }
                if (qualifiedName.length > IpcContract.MAX_QUALIFIED_NAME_LENGTH) {
                    throw ServiceFault(IpcContract.Error.METADATA_UNSUPPORTED, "Type path is too long")
                }
                return qualifiedName
            }
            currentIndex = requireMetadataIndex(declaringIndex, "declaring type definition index")
        }
        throw ServiceFault(IpcContract.Error.METADATA_UNSUPPORTED, "Type nesting exceeds the safety limit")
    }

    private fun listClassMembers(payload: Bundle): Bundle {
        val target = requireSession()
        val request = RequestPayloadCodec.decodeClassMembers(payload)
        requireClass(target, request.classIndex)
        return when (request.memberKind) {
            MemberKind.FIELD -> listFields(target, request)
            MemberKind.METHOD -> listMethods(target, request)
            MemberKind.PROPERTY -> listProperties(target, request)
            MemberKind.EVENT -> listEvents(target, request)
            MemberKind.NESTED_TYPE -> listNestedTypes(target, request)
            MemberKind.INTERFACE -> listInterfaces(target, request)
        }
    }

    private fun classInfo(payload: Bundle): Bundle {
        val target = requireSession()
        val request = RequestPayloadCodec.decodeClassInfo(payload)
        val flags = requireClass(target, request.classIndex)
        val token = requireUnsignedMetadata(
            NativeEngine.classToken(target.metadataHandle, request.classIndex),
            "class token",
        )
        val bitfield = requireUnsignedMetadata(
            NativeEngine.classBitfield(target.metadataHandle, request.classIndex),
            "class bitfield",
        )
        val parentType = typeReference(
            index = NativeEngine.classParentTypeIndex(target.metadataHandle, request.classIndex),
            name = NativeEngine.classParentTypeName(target.metadataHandle, request.classIndex),
            definitionIndex = NativeEngine.classParentDefinitionIndex(
                target.metadataHandle,
                request.classIndex,
            ),
        )
        val declaringType = typeReference(
            index = NativeEngine.classDeclaringTypeIndex(target.metadataHandle, request.classIndex),
            name = NativeEngine.classDeclaringTypeName(target.metadataHandle, request.classIndex),
            definitionIndex = NativeEngine.classDeclaringDefinitionIndex(
                target.metadataHandle,
                request.classIndex,
            ),
        )
        val sizes = typeSizes(NativeEngine.classTypeSizes(target.metadataHandle, request.classIndex))
        return ResponsePayloadCodec.classInfo(
            ClassInfoDescriptor(
                index = request.classIndex,
                name = requireMetadataName(
                    NativeEngine.classDefinitionName(target.metadataHandle, request.classIndex),
                    "class",
                ),
                namespaceName = requireMetadataName(
                    NativeEngine.classNamespaceName(target.metadataHandle, request.classIndex),
                    "class namespace",
                ),
                assemblyIndex = requireMetadataIndex(
                    NativeEngine.classAssemblyIndex(target.metadataHandle, request.classIndex),
                    "class assembly index",
                ),
                assemblyName = requireMetadataName(
                    NativeEngine.classAssemblyName(target.metadataHandle, request.classIndex),
                    "class assembly",
                ),
                flags = flags,
                token = token,
                bitfield = bitfield,
                parentType = parentType,
                declaringType = declaringType,
                sizes = sizes,
            ),
        )
    }

    private fun listFields(target: TargetSession, request: MemberPageRequest): Bundle {
        val totalCount = requireFieldCount(target, request.classIndex)
        val result = request.page.build(totalCount) { index ->
            FieldDescriptor(
                index = index,
                name = requireMetadataName(
                    NativeEngine.fieldName(target.metadataHandle, request.classIndex, index),
                    "field",
                ),
                typeIndex = requireMetadataIndex(
                    NativeEngine.fieldTypeIndex(target.metadataHandle, request.classIndex, index),
                    "field type index",
                ),
                typeName = optionalMetadataTypeName(
                    NativeEngine.fieldTypeName(target.metadataHandle, request.classIndex, index),
                ),
                offset = NativeEngine.fieldOffset(target.metadataHandle, request.classIndex, index)
                    .takeUnless { it == NativeEngine.UNAVAILABLE_FIELD_OFFSET },
                flags = optionalMetadataFlags(
                    NativeEngine.fieldFlags(target.metadataHandle, request.classIndex, index),
                ),
            )
        }
        return ResponsePayloadCodec.fieldPage(result)
    }

    private fun listMethods(target: TargetSession, request: MemberPageRequest): Bundle {
        val totalCount = requireMethodCount(target, request.classIndex)
        val result = request.page.build(totalCount) { index ->
            val address = NativeEngine.methodAddress(
                target.metadataHandle,
                request.classIndex,
                index,
            ).takeIf { it > 0 }
            MethodDescriptor(
                index = index,
                name = requireMetadataName(
                    NativeEngine.methodName(target.metadataHandle, request.classIndex, index),
                    "method",
                ),
                signature = optionalMethodSignature(
                    NativeEngine.methodSignature(target.metadataHandle, request.classIndex, index),
                ),
                address = address,
                rva = address
                    ?.takeIf {
                        it >= target.moduleBase &&
                            NativeEngine.isExecutableModuleAddress(target.metadataHandle, it)
                    }
                    ?.let { it - target.moduleBase },
            )
        }
        return ResponsePayloadCodec.methodPage(result)
    }

    private fun methodAnalysis(payload: Bundle): Bundle {
        val target = requireSession()
        val request = RequestPayloadCodec.decodeMethodAnalysis(payload)
        requireClass(target, request.classIndex)
        requireMethodIndex(
            request.methodIndex,
            requireMethodCount(target, request.classIndex),
        )
        return when (request.section) {
            MethodAnalysisSection.CALLS -> {
                val nativePage = NativeEngine.methodCalls(
                    target.metadataHandle,
                    request.classIndex,
                    request.methodIndex,
                    request.page.offset,
                    request.page.limit,
                ) ?: methodAnalysisUnavailable()
                ResponsePayloadCodec.methodReferenceAnalysis(
                    nativePage.toReferenceAnalysis(target, request.page),
                )
            }
            MethodAnalysisSection.CALLERS -> {
                val nativePage = NativeEngine.methodCallers(
                    target.metadataHandle,
                    request.classIndex,
                    request.methodIndex,
                    request.page.offset,
                    request.page.limit,
                ) ?: methodAnalysisUnavailable()
                ResponsePayloadCodec.methodReferenceAnalysis(
                    nativePage.toReferenceAnalysis(target, request.page),
                )
            }
            MethodAnalysisSection.INSTRUCTIONS -> {
                val nativePage = NativeEngine.methodInstructions(
                    target.metadataHandle,
                    request.classIndex,
                    request.methodIndex,
                    request.page.offset,
                    request.page.limit,
                ) ?: methodAnalysisUnavailable()
                ResponsePayloadCodec.instructionAnalysis(
                    nativePage.toInstructionAnalysis(target, request.page),
                )
            }
        }
    }

    private fun NativeMethodReferencePage.toReferenceAnalysis(
        target: TargetSession,
        request: PageRequest,
    ): MethodAnalysisResult<MethodReferenceDescriptor> {
        requireAnalysisPage(
            request = request,
            totalCount = totalCount,
            itemCount = addresses.size,
            sizes = intArrayOf(
                classIndices.size,
                methodIndices.size,
                names.size,
                ownerNames.size,
                signatures.size,
                referenceResolved.size,
                signatureResolved.size,
                rvas.size,
                rvaResolved.size,
                callSiteAddresses.size,
                callSiteRvas.size,
                callSiteRvaResolved.size,
                callSiteInstructionIndices.size,
            ),
        )
        val analysisStatus = requireAnalysisStatus(status)
        requireIndirectCallCount(indirectCount)
        val items = addresses.indices.map { index ->
            val address = requireCodeAddress(addresses[index])
            val callSiteAddress = requireCodeAddress(callSiteAddresses[index])
            val resolved = referenceResolved[index]
            if (signatureResolved[index] && !resolved) {
                invalidNativeAnalysis()
            }
            val classIndex = classIndices[index].takeIf { resolved }?.let {
                requireMetadataIndex(it, "referenced class index")
            }
            val methodIndex = methodIndices[index].takeIf { resolved }?.let {
                val owner = requireNotNull(classIndex)
                requireMethodIndex(it, requireMethodCount(target, owner))
                it
            }
            MethodReferenceDescriptor(
                classIndex = classIndex,
                methodIndex = methodIndex,
                name = names[index].takeIf { resolved }?.let {
                    requireAnalysisText(it, IpcContract.MAX_NAME_LENGTH, allowBlank = true)
                },
                ownerName = ownerNames[index].takeIf { resolved }?.let {
                    requireAnalysisText(
                        it,
                        IpcContract.MAX_QUALIFIED_NAME_LENGTH,
                        allowBlank = true,
                    )
                },
                signature = signatures[index]
                    .takeIf { resolved && signatureResolved[index] }
                    ?.let {
                        requireAnalysisText(
                            it,
                            IpcContract.MAX_QUALIFIED_NAME_LENGTH,
                            allowBlank = true,
                        )
                    },
                address = address,
                rva = requireAnalysisRva(
                    target = target,
                    address = address,
                    value = rvas[index],
                    resolved = rvaResolved[index],
                ),
                callSiteAddress = callSiteAddress,
                callSiteRva = requireAnalysisRva(
                    target = target,
                    address = callSiteAddress,
                    value = callSiteRvas[index],
                    resolved = callSiteRvaResolved[index],
                ),
                callSiteInstructionIndex = requireAnalysisIndex(
                    callSiteInstructionIndices[index],
                ),
            )
        }
        return MethodAnalysisResult(
            page = PageResult(request.offset, totalCount, items),
            status = analysisStatus,
            unresolvedIndirectCallCount = indirectCount,
        )
    }

    private fun NativeInstructionPage.toInstructionAnalysis(
        target: TargetSession,
        request: PageRequest,
    ): MethodAnalysisResult<InstructionDescriptor> {
        requireAnalysisPage(
            request = request,
            totalCount = totalCount,
            itemCount = addresses.size,
            sizes = intArrayOf(
                rvas.size,
                rvaResolved.size,
                bytes.size,
                mnemonics.size,
                operands.size,
                flowKinds.size,
                targetInstructionIndices.size,
                targetClassIndices.size,
                targetMethodIndices.size,
                targetNames.size,
                targetOwnerNames.size,
                targetSignatures.size,
                targetPresent.size,
                targetMethodResolved.size,
                targetSignatureResolved.size,
                targetAddresses.size,
                targetRvas.size,
                targetRvaResolved.size,
            ),
        )
        val analysisStatus = requireAnalysisStatus(status)
        requireIndirectCallCount(indirectCount)
        val items = addresses.indices.map { index ->
            val address = requireCodeAddress(addresses[index])
            val rva = requireAnalysisRva(
                target = target,
                address = address,
                value = rvas[index],
                resolved = rvaResolved[index],
            )
            val flowKind = requireInstructionFlow(flowKinds[index])
            val directFlow = flowKind == InstructionFlowKind.DIRECT_CALL ||
                flowKind == InstructionFlowKind.DIRECT_BRANCH
            if (targetPresent[index] != directFlow) {
                invalidNativeAnalysis()
            }
            val targetReference = if (targetPresent[index]) {
                val targetAddress = requireCodeAddress(targetAddresses[index])
                val resolved = targetMethodResolved[index]
                if (targetSignatureResolved[index] && !resolved) {
                    invalidNativeAnalysis()
                }
                val classIndex = targetClassIndices[index].takeIf { resolved }?.let {
                    requireMetadataIndex(it, "target class index")
                }
                val methodIndex = targetMethodIndices[index].takeIf { resolved }?.let {
                    val owner = requireNotNull(classIndex)
                    requireMethodIndex(it, requireMethodCount(target, owner))
                    it
                }
                MethodReferenceDescriptor(
                    classIndex = classIndex,
                    methodIndex = methodIndex,
                    name = targetNames[index].takeIf { resolved }?.let {
                        requireAnalysisText(it, IpcContract.MAX_NAME_LENGTH, allowBlank = true)
                    },
                    ownerName = targetOwnerNames[index].takeIf { resolved }?.let {
                        requireAnalysisText(
                            it,
                            IpcContract.MAX_QUALIFIED_NAME_LENGTH,
                            allowBlank = true,
                        )
                    },
                    signature = targetSignatures[index]
                        .takeIf { resolved && targetSignatureResolved[index] }
                        ?.let {
                            requireAnalysisText(
                                it,
                                IpcContract.MAX_QUALIFIED_NAME_LENGTH,
                                allowBlank = true,
                            )
                        },
                    address = targetAddress,
                    rva = requireAnalysisRva(
                        target = target,
                        address = targetAddress,
                        value = targetRvas[index],
                        resolved = targetRvaResolved[index],
                    ),
                    callSiteAddress = address,
                    callSiteRva = rva,
                    callSiteInstructionIndex = request.offset + index,
                )
            } else {
                if (targetMethodResolved[index] || targetSignatureResolved[index] ||
                    targetAddresses[index] != 0L || targetRvas[index] != 0L ||
                    targetRvaResolved[index] || targetNames[index].isNotEmpty() ||
                    targetOwnerNames[index].isNotEmpty() || targetSignatures[index].isNotEmpty()
                ) {
                    invalidNativeAnalysis()
                }
                null
            }
            val targetInstructionIndex = targetInstructionIndices[index].let { value ->
                when {
                    value == -1 -> null
                    value in 0 until totalCount -> value
                    else -> invalidNativeAnalysis()
                }
            }
            InstructionDescriptor(
                address = address,
                rva = rva,
                bytes = requireAnalysisText(
                    bytes[index],
                    IpcContract.MAX_INSTRUCTION_BYTES_LENGTH,
                ),
                mnemonic = requireAnalysisText(
                    mnemonics[index],
                    IpcContract.MAX_INSTRUCTION_MNEMONIC_LENGTH,
                ),
                operands = requireAnalysisText(
                    operands[index],
                    IpcContract.MAX_INSTRUCTION_OPERANDS_LENGTH,
                    allowBlank = true,
                ),
                flowKind = flowKind,
                targetInstructionIndex = targetInstructionIndex,
                target = targetReference,
            )
        }
        return MethodAnalysisResult(
            page = PageResult(request.offset, totalCount, items),
            status = analysisStatus,
            unresolvedIndirectCallCount = indirectCount,
        )
    }

    private fun listProperties(target: TargetSession, request: MemberPageRequest): Bundle {
        val totalCount = requireMetadataCount(
            NativeEngine.propertyCount(target.metadataHandle, request.classIndex),
            "property count",
        )
        val result = request.page.build(totalCount) { index ->
            val rawTypeIndex = NativeEngine.propertyTypeIndex(target.metadataHandle, request.classIndex, index)
            PropertyDescriptor(
                index = index,
                name = requireMetadataName(
                    NativeEngine.propertyName(target.metadataHandle, request.classIndex, index),
                    "property",
                ),
                typeIndex = rawTypeIndex.takeIf { it >= 0 }?.let {
                    requireMetadataIndex(it, "property type index")
                },
                typeName = optionalMetadataTypeName(
                    NativeEngine.propertyTypeName(target.metadataHandle, request.classIndex, index),
                ),
                getterFlags = optionalMetadataFlags(
                    NativeEngine.propertyGetterFlags(
                        target.metadataHandle,
                        request.classIndex,
                        index,
                    ),
                ),
                setterFlags = optionalMetadataFlags(
                    NativeEngine.propertySetterFlags(
                        target.metadataHandle,
                        request.classIndex,
                        index,
                    ),
                ),
                attributes = requireUnsignedMetadata(
                    NativeEngine.propertyAttributes(target.metadataHandle, request.classIndex, index),
                    "property attributes",
                ),
                token = requireUnsignedMetadata(
                    NativeEngine.propertyToken(target.metadataHandle, request.classIndex, index),
                    "property token",
                ),
            )
        }
        return ResponsePayloadCodec.propertyPage(result)
    }

    private fun listEvents(target: TargetSession, request: MemberPageRequest): Bundle {
        val totalCount = requireMetadataCount(
            NativeEngine.eventCount(target.metadataHandle, request.classIndex),
            "event count",
        )
        val result = request.page.build(totalCount) { index ->
            EventDescriptor(
                index = index,
                name = requireMetadataName(
                    NativeEngine.eventName(target.metadataHandle, request.classIndex, index),
                    "event",
                ),
                typeIndex = requireMetadataIndex(
                    NativeEngine.eventTypeIndex(target.metadataHandle, request.classIndex, index),
                    "event type index",
                ),
                typeName = optionalMetadataTypeName(
                    NativeEngine.eventTypeName(target.metadataHandle, request.classIndex, index),
                ),
                addFlags = optionalMetadataFlags(
                    NativeEngine.eventAddFlags(
                        target.metadataHandle,
                        request.classIndex,
                        index,
                    ),
                ),
                removeFlags = optionalMetadataFlags(
                    NativeEngine.eventRemoveFlags(
                        target.metadataHandle,
                        request.classIndex,
                        index,
                    ),
                ),
                raiseFlags = optionalMetadataFlags(
                    NativeEngine.eventRaiseFlags(
                        target.metadataHandle,
                        request.classIndex,
                        index,
                    ),
                ),
                token = requireUnsignedMetadata(
                    NativeEngine.eventToken(target.metadataHandle, request.classIndex, index),
                    "event token",
                ),
            )
        }
        return ResponsePayloadCodec.eventPage(result)
    }

    private fun listNestedTypes(target: TargetSession, request: MemberPageRequest): Bundle {
        val totalCount = requireMetadataCount(
            NativeEngine.nestedTypeCount(target.metadataHandle, request.classIndex),
            "nested type count",
        )
        val result = request.page.build(totalCount) { index ->
            ClassDescriptor(
                index = requireMetadataIndex(
                    NativeEngine.nestedTypeIndex(target.metadataHandle, request.classIndex, index),
                    "nested type index",
                ),
                name = requireMetadataName(
                    NativeEngine.nestedTypeName(target.metadataHandle, request.classIndex, index),
                    "nested type",
                ),
            )
        }
        return ResponsePayloadCodec.nestedTypePage(result)
    }

    private fun listInterfaces(target: TargetSession, request: MemberPageRequest): Bundle {
        val totalCount = requireMetadataCount(
            NativeEngine.interfaceCount(target.metadataHandle, request.classIndex),
            "interface count",
        )
        val result = request.page.build(totalCount) { index ->
            TypeReferenceDescriptor(
                index = index,
                typeIndex = requireMetadataIndex(
                    NativeEngine.interfaceTypeIndex(target.metadataHandle, request.classIndex, index),
                    "interface type index",
                ),
                definitionIndex = NativeEngine.interfaceDefinitionIndex(
                    target.metadataHandle,
                    request.classIndex,
                    index,
                ).takeIf { it >= 0 }?.let {
                    requireMetadataIndex(it, "interface definition index")
                },
                name = optionalMetadataTypeName(
                    NativeEngine.interfaceTypeName(target.metadataHandle, request.classIndex, index),
                ),
            )
        }
        return ResponsePayloadCodec.interfacePage(result)
    }

    private fun readVisibleFields(payload: Bundle): Bundle {
        val target = requireSession()
        val request = RequestPayloadCodec.decodeReadVisibleFields(payload)
        val fieldCount = requireFieldCount(target, request.classIndex)
        request.fieldIndices.forEach { requireFieldIndex(it, fieldCount) }
        val results = request.fieldIndices.map { fieldIndex ->
            readField(target, request.classIndex, request.objectAddress, fieldIndex)
        }
        return ResponsePayloadCodec.fieldReads(results)
    }

    private fun readField(
        target: TargetSession,
        classIndex: Int,
        objectAddress: Long,
        fieldIndex: Int,
    ): FieldReadResult {
        val typeName = NativeEngine.fieldTypeName(target.metadataHandle, classIndex, fieldIndex)
        val offset = NativeEngine.fieldOffset(target.metadataHandle, classIndex, fieldIndex)
        if (typeName == null || offset < 0) {
            return FieldReadResult(
                fieldIndex,
                UNRESOLVED_ADDRESS,
                FieldReadStatus.UNRESOLVED,
                ValueKind.UNRESOLVED,
                "",
            )
        }
        val kind = ValueKind.fromTypeName(typeName)
        if (kind == ValueKind.UNRESOLVED) {
            return FieldReadResult(
                fieldIndex,
                UNRESOLVED_ADDRESS,
                FieldReadStatus.UNSUPPORTED_TYPE,
                kind,
                "",
            )
        }
        val address = checkedAddress(objectAddress, offset)
            ?: return FieldReadResult(
                fieldIndex,
                UNRESOLVED_ADDRESS,
                FieldReadStatus.INVALID_ADDRESS,
                kind,
                "",
            )
        return if (kind == ValueKind.STRING) {
            readStringField(target, fieldIndex, address)
        } else {
            readPrimitiveField(target, fieldIndex, address, kind)
        }
    }

    private fun readPrimitiveField(
        target: TargetSession,
        fieldIndex: Int,
        address: Long,
        kind: ValueKind,
    ): FieldReadResult {
        if (!target.canAccess(address, kind.byteSize, writable = false)) {
            return FieldReadResult(fieldIndex, address, FieldReadStatus.INVALID_ADDRESS, kind, "")
        }
        val bytes = NativeEngine.readMemory(target.pid, address, kind.byteSize)
        val value = bytes
            ?.takeIf { it.size == kind.byteSize }
            ?.let { PrimitiveCodec.decode(kind, it) }
            ?: return FieldReadResult(fieldIndex, address, FieldReadStatus.READ_FAILED, kind, "")
        return FieldReadResult(
            fieldIndex,
            address,
            FieldReadStatus.SUCCESS,
            kind,
            PrimitiveCodec.display(value),
        )
    }

    private fun readStringField(
        target: TargetSession,
        fieldIndex: Int,
        address: Long,
    ): FieldReadResult {
        val kind = ValueKind.STRING
        val pointerBytes = readExact(target, address, Long.SIZE_BYTES)
            ?: return FieldReadResult(fieldIndex, address, FieldReadStatus.READ_FAILED, kind, "")
        val stringAddress = littleEndian(pointerBytes).long
        if (stringAddress == 0L) {
            return FieldReadResult(fieldIndex, address, FieldReadStatus.SUCCESS, kind, NULL_VALUE)
        }
        val lengthAddress = checkedAddress(stringAddress, STRING_LENGTH_OFFSET.toLong())
            ?: return FieldReadResult(fieldIndex, address, FieldReadStatus.INVALID_ADDRESS, kind, "")
        val lengthBytes = readExact(target, lengthAddress, Int.SIZE_BYTES)
            ?: return FieldReadResult(fieldIndex, address, FieldReadStatus.READ_FAILED, kind, "")
        val characterCount = littleEndian(lengthBytes).int
        if (characterCount !in 0..MAX_STRING_CHARACTERS) {
            return FieldReadResult(fieldIndex, address, FieldReadStatus.READ_FAILED, kind, "")
        }
        val dataAddress = checkedAddress(stringAddress, STRING_DATA_OFFSET.toLong())
            ?: return FieldReadResult(fieldIndex, address, FieldReadStatus.INVALID_ADDRESS, kind, "")
        val byteCount = characterCount * Char.SIZE_BYTES
        val characters = if (byteCount == 0) {
            ByteArray(0)
        } else {
            readExact(target, dataAddress, byteCount)
                ?: return FieldReadResult(fieldIndex, address, FieldReadStatus.READ_FAILED, kind, "")
        }
        val value = String(characters, Charsets.UTF_16LE).take(IpcContract.MAX_DISPLAY_VALUE_LENGTH)
        return FieldReadResult(fieldIndex, address, FieldReadStatus.SUCCESS, kind, value)
    }

    private fun writePrimitive(payload: Bundle): Bundle {
        val target = requireSession()
        val request = RequestPayloadCodec.decodeWritePrimitive(payload)
        val fieldCount = requireFieldCount(target, request.classIndex)
        requireFieldIndex(request.fieldIndex, fieldCount)
        val typeName = NativeEngine.fieldTypeName(
            target.metadataHandle,
            request.classIndex,
            request.fieldIndex,
        ) ?: throw ServiceFault(IpcContract.Error.UNRESOLVED_METADATA, "Field type is unresolved")
        val fieldKind = ValueKind.fromTypeName(typeName)
        if (!fieldKind.writable || fieldKind != request.value.kind) {
            throw ServiceFault(IpcContract.Error.UNSUPPORTED_TYPE, "Field type does not match write value")
        }
        val offset = NativeEngine.fieldOffset(
            target.metadataHandle,
            request.classIndex,
            request.fieldIndex,
        )
        val address = checkedAddress(request.objectAddress, offset)
            ?: throw ServiceFault(IpcContract.Error.UNRESOLVED_METADATA, "Field offset is unresolved")
        val bytes = PrimitiveCodec.encode(request.value)
        if (!target.canAccess(address, bytes.size, writable = true)) {
            throw ServiceFault(IpcContract.Error.MEMORY_ACCESS_DENIED, "Field address is not writable")
        }
        val bytesWritten = NativeEngine.writeMemory(target.pid, address, bytes)
        if (bytesWritten != bytes.size) {
            throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Memory write was incomplete")
        }
        return ResponsePayloadCodec.writeResult(bytesWritten)
    }

    private fun closeTarget(): Bundle {
        closeSession()
        return Bundle()
    }

    private fun requireSession(): TargetSession {
        val current = session
            ?: throw ServiceFault(IpcContract.Error.NO_TARGET, "No target process is open")
        val actualStartTicks = NativeEngine.processStartTicks(current.pid)
        if (actualStartTicks != current.startTicks) {
            closeSession()
            val errorCode = if (actualStartTicks <= 0) {
                IpcContract.Error.PROCESS_NOT_FOUND
            } else {
                IpcContract.Error.PROCESS_CHANGED
            }
            throw ServiceFault(errorCode, "Target process identity changed")
        }
        return current
    }

    private fun requireAssemblyIndex(target: TargetSession, assemblyIndex: Int) {
        val assemblyCount = requireMetadataCount(
            NativeEngine.assemblyCount(target.metadataHandle),
            "assembly count",
        )
        if (assemblyIndex !in 0 until assemblyCount) {
            throw ServiceFault(IpcContract.Error.OUT_OF_RANGE, "Assembly index is out of range")
        }
    }

    private fun requireNamespaceIndex(
        target: TargetSession,
        assemblyIndex: Int,
        namespaceIndex: Int,
    ) {
        val namespaceCount = requireMetadataCount(
            NativeEngine.namespaceCount(target.metadataHandle, assemblyIndex),
            "namespace count",
        )
        if (namespaceIndex !in 0 until namespaceCount) {
            throw ServiceFault(IpcContract.Error.OUT_OF_RANGE, "Namespace index is out of range")
        }
    }

    private fun requireFieldCount(target: TargetSession, classIndex: Int): Int = requireMetadataCount(
        NativeEngine.fieldCount(target.metadataHandle, classIndex),
        "field count",
    )

    private fun requireMethodCount(target: TargetSession, classIndex: Int): Int = requireMetadataCount(
        NativeEngine.methodCount(target.metadataHandle, classIndex),
        "method count",
    )

    private fun requireClass(target: TargetSession, classIndex: Int): Long {
        requireMetadataIndex(classIndex, "class index")
        return requireUnsignedMetadata(
            NativeEngine.classFlags(target.metadataHandle, classIndex),
            "class flags",
        )
    }

    private fun requireMetadataIndex(value: Int, label: String): Int {
        if (value !in 0 until IpcContract.MAX_HIERARCHY_COUNT) {
            throw ServiceFault(IpcContract.Error.OUT_OF_RANGE, "Invalid $label")
        }
        return value
    }

    private fun requireUnsignedMetadata(value: Long, label: String): Long {
        if (value !in 0..MAX_UNSIGNED_INT) {
            throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Invalid $label")
        }
        return value
    }

    private fun requireMetadataFlags(value: Int, label: String): Int {
        if (value !in 0..MAX_UNSIGNED_SHORT) {
            throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Invalid $label")
        }
        return value
    }

    private fun optionalMetadataFlags(value: Int): Int? =
        value.takeIf { it >= 0 }?.let { requireMetadataFlags(it, "member flags") }

    private fun typeReference(
        index: Int,
        name: String?,
        definitionIndex: Int,
    ): TypeReferenceDescriptor? {
        if (index < 0) return null
        return TypeReferenceDescriptor(
            index = 0,
            typeIndex = requireMetadataIndex(index, "type index"),
            definitionIndex = definitionIndex.takeIf { it >= 0 }?.let {
                requireMetadataIndex(it, "type definition index")
            },
            name = optionalMetadataTypeName(name),
        )
    }

    private fun typeSizes(values: LongArray?): TypeSizeDescriptor? {
        if (values == null) return null
        if (values.size != TYPE_SIZE_VALUE_COUNT ||
            values[0] !in 0..MAX_UNSIGNED_INT ||
            values[1] !in -1..Int.MAX_VALUE.toLong() ||
            values[2] !in 0..MAX_UNSIGNED_INT ||
            values[3] !in 0..MAX_UNSIGNED_INT) {
            throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Invalid class type sizes")
        }
        return TypeSizeDescriptor(values[0], values[1], values[2], values[3])
    }

    private fun requireFieldIndex(fieldIndex: Int, fieldCount: Int) {
        if (fieldIndex !in 0 until fieldCount) {
            throw ServiceFault(IpcContract.Error.OUT_OF_RANGE, "Field index is out of range")
        }
    }

    private fun requireMethodIndex(methodIndex: Int, methodCount: Int) {
        if (methodIndex !in 0 until methodCount) {
            throw ServiceFault(IpcContract.Error.OUT_OF_RANGE, "Method index is out of range")
        }
    }

    private fun requireAnalysisPage(
        request: PageRequest,
        totalCount: Int,
        itemCount: Int,
        sizes: IntArray,
    ) {
        val validTotalCount = totalCount in 0..IpcContract.MAX_HIERARCHY_COUNT
        val validOffset = validTotalCount && request.offset <= totalCount
        val expectedItemCount = if (validOffset) {
            minOf(request.limit, totalCount - request.offset)
        } else {
            -1
        }
        if (!validOffset ||
            itemCount != expectedItemCount ||
            sizes.any { it != itemCount }) {
            invalidNativeAnalysis()
        }
    }

    private fun requireAnalysisStatus(value: Int): MethodAnalysisStatus =
        MethodAnalysisStatus.fromWireValue(value) ?: invalidNativeAnalysis()

    private fun requireInstructionFlow(value: Int): InstructionFlowKind =
        InstructionFlowKind.fromWireValue(value) ?: invalidNativeAnalysis()

    private fun requireAnalysisIndex(value: Int): Int =
        value.takeIf { it in 0 until IpcContract.MAX_HIERARCHY_COUNT }
            ?: invalidNativeAnalysis()

    private fun requireIndirectCallCount(value: Int) {
        if (value !in 0..IpcContract.MAX_HIERARCHY_COUNT) {
            invalidNativeAnalysis()
        }
    }

    private fun requireCodeAddress(value: Long): Long =
        value.takeIf { it > 0 } ?: invalidNativeAnalysis()

    private fun requireAnalysisRva(
        target: TargetSession,
        address: Long,
        value: Long,
        resolved: Boolean,
    ): Long? {
        if (!resolved) {
            return null
        }
        if (address < target.moduleBase || value != address - target.moduleBase) {
            invalidNativeAnalysis()
        }
        return value
    }

    private fun requireAnalysisText(
        value: String,
        maximumLength: Int,
        allowBlank: Boolean = false,
    ): String {
        if (value.length > maximumLength || !allowBlank && value.isBlank()) {
            invalidNativeAnalysis()
        }
        return value
    }

    private fun methodAnalysisUnavailable(): Nothing =
        throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Method analysis is unavailable")

    private fun invalidNativeAnalysis(): Nothing =
        throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "Native method analysis is invalid")

    private fun requireMetadataCount(value: Int, label: String): Int {
        if (value < 0) {
            throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "$label is unavailable")
        }
        if (value > IpcContract.MAX_HIERARCHY_COUNT) {
            throw ServiceFault(IpcContract.Error.METADATA_UNSUPPORTED, "$label exceeds the safety limit")
        }
        return value
    }

    private fun requireMetadataName(value: String?, label: String): String {
        val name = value
            ?: throw ServiceFault(IpcContract.Error.NATIVE_FAILURE, "$label name is unavailable")
        if (name.length > IpcContract.MAX_NAME_LENGTH) {
            throw ServiceFault(IpcContract.Error.METADATA_UNSUPPORTED, "$label name is too long")
        }
        return name
    }

    private fun optionalMetadataTypeName(value: String?): String? =
        value?.takeIf { it.length <= IpcContract.MAX_QUALIFIED_NAME_LENGTH }

    private fun optionalMethodSignature(value: String?): String? =
        value?.takeIf { it.length <= IpcContract.MAX_QUALIFIED_NAME_LENGTH }

    private fun readExact(
        target: TargetSession,
        address: Long,
        size: Int,
    ): ByteArray? {
        if (!target.canAccess(address, size, writable = false)) {
            return null
        }
        return NativeEngine.readMemory(target.pid, address, size)?.takeIf { it.size == size }
    }

    private fun checkedAddress(base: Long, offset: Long): Long? {
        if (base <= 0 || offset < 0) {
            return null
        }
        return try {
            Math.addExact(base, offset).takeIf { it > 0 }
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun littleEndian(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun closeSession() {
        val current = session ?: return
        session = null
        runCatching { NativeEngine.closeMetadata(current.metadataHandle) }
    }

    private fun <T> List<T>.page(request: PageRequest): PageResult<T> {
        request.requireOffsetWithin(size)
        val toIndex = min(request.offset + request.limit, size)
        return PageResult(request.offset, size, subList(request.offset, toIndex))
    }

    private fun <T> PageRequest.build(totalCount: Int, item: (Int) -> T): PageResult<T> {
        requireOffsetWithin(totalCount)
        val toIndex = min(offset + limit, totalCount)
        return PageResult(offset, totalCount, (offset until toIndex).map(item))
    }

    private fun PageRequest.requireOffsetWithin(totalCount: Int) {
        if (offset > totalCount) {
            throw ServiceFault(IpcContract.Error.OUT_OF_RANGE, "Page offset is out of range")
        }
    }

    private data class TargetSession(
        val pid: Int,
        val startTicks: Long,
        val moduleBase: Long,
        val metadataHandle: Long,
        var maps: ProcessMaps,
    ) {
        var typeSearchIndex: CachedTypeSearchIndex? = null
        var cachedTypeSearch: CachedTypeSearch? = null
        var symbolSearchSummary: SymbolSearchSummary? = null

        fun canAccess(address: Long, size: Int, writable: Boolean): Boolean {
            if (if (writable) maps.canWrite(address, size) else maps.canRead(address, size)) {
                return true
            }
            maps = ProcessMaps.load(pid)
            return if (writable) maps.canWrite(address, size) else maps.canRead(address, size)
        }
    }

    private data class TypeSearchIndex(
        val indices: IntArray,
        val names: Array<String>,
    )

    private data class CachedTypeSearchIndex(
        val assemblyIndex: Int,
        val value: TypeSearchIndex,
    )

    private data class TypeSearchKey(
        val assemblyIndex: Int,
        val query: String,
        val matchMode: SearchMatchMode,
        val matchCase: Boolean,
    )

    private data class CachedTypeSearch(
        val key: TypeSearchKey,
        val positions: IntArray,
    )

    private data class SymbolSearchKey(
        val query: String,
        val matchMode: SearchMatchMode,
        val matchCase: Boolean,
    )

    private data class SymbolSearchSummary(
        val key: SymbolSearchKey,
        val totalCount: Int,
    )

    private companion object {
        const val IL2CPP_MODULE_NAME = "libil2cpp.so"
        const val NULL_CHARACTER = '\u0000'
        const val NULL_VALUE = "null"
        const val UNRESOLVED_ADDRESS = -1L
        const val STRING_LENGTH_OFFSET = Long.SIZE_BYTES * 2
        const val STRING_DATA_OFFSET = STRING_LENGTH_OFFSET + Int.SIZE_BYTES
        const val MAX_STRING_CHARACTERS = 2_048
        const val TYPE_SIZE_VALUE_COUNT = 4
        const val MAX_UNSIGNED_INT = 0xFFFF_FFFFL
        const val MAX_UNSIGNED_SHORT = 0xFFFF
        const val MAX_TYPE_NESTING_DEPTH = 64
        const val NESTED_TYPE_SEPARATOR = "/"
        const val NAMESPACE_SEPARATOR = "."
    }
}
