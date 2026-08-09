package dev.ruri.il2cppmanager.ipc

import android.os.Bundle
import dev.ruri.il2cppmanager.domain.MemberKind
import dev.ruri.il2cppmanager.domain.MethodAnalysisSection
import dev.ruri.il2cppmanager.domain.PrimitiveValue
import dev.ruri.il2cppmanager.domain.SearchMatchMode
import dev.ruri.il2cppmanager.domain.ValueKind

data class PageRequest(
    val offset: Int,
    val limit: Int,
)

data class OpenTargetRequest(
    val pid: Int,
    val startTicks: Long,
)

data class NamespacePageRequest(
    val assemblyIndex: Int,
    val page: PageRequest,
)

data class ClassPageRequest(
    val assemblyIndex: Int,
    val namespaceIndex: Int,
    val page: PageRequest,
)

data class MemberPageRequest(
    val classIndex: Int,
    val memberKind: MemberKind,
    val page: PageRequest,
)

data class ClassInfoRequest(val classIndex: Int)

data class MethodAnalysisRequest(
    val classIndex: Int,
    val methodIndex: Int,
    val section: MethodAnalysisSection,
    val page: PageRequest,
)

data class TypeSearchRequest(
    val assemblyIndex: Int,
    val query: String,
    val matchMode: SearchMatchMode,
    val matchCase: Boolean,
    val page: PageRequest,
)

data class SymbolSearchRequest(
    val query: String,
    val matchMode: SearchMatchMode,
    val matchCase: Boolean,
    val page: PageRequest,
)

data class ReadFieldsRequest(
    val classIndex: Int,
    val objectAddress: Long,
    val fieldIndices: IntArray,
)

data class WritePrimitiveRequest(
    val classIndex: Int,
    val objectAddress: Long,
    val fieldIndex: Int,
    val value: PrimitiveValue,
)

object RequestPayloadCodec {
    fun scanProcesses(
        offset: Int = 0,
        limit: Int = IpcContract.DEFAULT_PAGE_SIZE,
    ): Bundle = page(offset, limit)

    fun decodeScanProcesses(payload: Bundle): PageRequest = decodePage(payload)

    fun openTarget(pid: Int, startTicks: Long): Bundle = Bundle().apply {
        require(pid > 0)
        require(startTicks > 0)
        putInt(IpcContract.Key.PID, pid)
        putLong(IpcContract.Key.START_TICKS, startTicks)
    }

    fun decodeOpenTarget(payload: Bundle): OpenTargetRequest = OpenTargetRequest(
        pid = payload.requireInt(IpcContract.Key.PID, minimum = 1),
        startTicks = payload.requireLong(IpcContract.Key.START_TICKS, minimum = 1),
    )

    fun listAssemblies(
        offset: Int = 0,
        limit: Int = IpcContract.DEFAULT_PAGE_SIZE,
    ): Bundle = page(offset, limit)

    fun decodeListAssemblies(payload: Bundle): PageRequest = decodePage(payload)

    fun listNamespaces(
        assemblyIndex: Int,
        offset: Int = 0,
        limit: Int = IpcContract.DEFAULT_PAGE_SIZE,
    ): Bundle = page(offset, limit).apply {
        requireHierarchyIndex(assemblyIndex)
        putInt(IpcContract.Key.ASSEMBLY_INDEX, assemblyIndex)
    }

    fun decodeListNamespaces(payload: Bundle): NamespacePageRequest = NamespacePageRequest(
        assemblyIndex = payload.requireHierarchyIndex(IpcContract.Key.ASSEMBLY_INDEX),
        page = decodePage(payload),
    )

    fun listClasses(
        assemblyIndex: Int,
        namespaceIndex: Int,
        offset: Int = 0,
        limit: Int = IpcContract.DEFAULT_PAGE_SIZE,
    ): Bundle = page(offset, limit).apply {
        requireHierarchyIndex(assemblyIndex)
        requireHierarchyIndex(namespaceIndex)
        putInt(IpcContract.Key.ASSEMBLY_INDEX, assemblyIndex)
        putInt(IpcContract.Key.NAMESPACE_INDEX, namespaceIndex)
    }

    fun decodeListClasses(payload: Bundle): ClassPageRequest = ClassPageRequest(
        assemblyIndex = payload.requireHierarchyIndex(IpcContract.Key.ASSEMBLY_INDEX),
        namespaceIndex = payload.requireHierarchyIndex(IpcContract.Key.NAMESPACE_INDEX),
        page = decodePage(payload),
    )

    fun searchTypes(
        assemblyIndex: Int,
        query: String,
        matchMode: SearchMatchMode,
        matchCase: Boolean,
        offset: Int = 0,
        limit: Int = IpcContract.SEARCH_PAGE_SIZE,
    ): Bundle = page(offset, limit).apply {
        requireHierarchyIndex(assemblyIndex)
        require(query.isNotBlank())
        require(query.length <= IpcContract.MAX_SEARCH_QUERY_LENGTH)
        require(limit <= IpcContract.SEARCH_PAGE_SIZE)
        putInt(IpcContract.Key.ASSEMBLY_INDEX, assemblyIndex)
        putString(IpcContract.Key.QUERY, query)
        putInt(IpcContract.Key.MATCH_MODE, matchMode.wireValue)
        putBoolean(IpcContract.Key.MATCH_CASE, matchCase)
    }

    fun decodeSearchTypes(payload: Bundle): TypeSearchRequest {
        val query = payload.requireString(
            IpcContract.Key.QUERY,
            IpcContract.MAX_SEARCH_QUERY_LENGTH,
        )
        if (query.isBlank()) {
            throw ProtocolException(IpcContract.Error.INVALID_ARGUMENT, "Search query is blank")
        }
        val matchMode = SearchMatchMode.fromWireValue(
            payload.requireInt(IpcContract.Key.MATCH_MODE),
        ) ?: throw ProtocolException(IpcContract.Error.INVALID_ARGUMENT, "Invalid search match mode")
        val page = decodePage(payload)
        if (page.limit > IpcContract.SEARCH_PAGE_SIZE) {
            throw ProtocolException(IpcContract.Error.INVALID_ARGUMENT, "Type search page is too large")
        }
        return TypeSearchRequest(
            assemblyIndex = payload.requireHierarchyIndex(IpcContract.Key.ASSEMBLY_INDEX),
            query = query,
            matchMode = matchMode,
            matchCase = payload.requireBoolean(IpcContract.Key.MATCH_CASE),
            page = page,
        )
    }

    fun searchSymbols(
        query: String,
        matchMode: SearchMatchMode,
        matchCase: Boolean,
        offset: Int = 0,
        limit: Int = IpcContract.SEARCH_PAGE_SIZE,
    ): Bundle = page(offset, limit, IpcContract.MAX_SYMBOL_COUNT).apply {
        require(query.isNotBlank())
        require(query.length <= IpcContract.MAX_SEARCH_QUERY_LENGTH)
        require(limit <= IpcContract.SEARCH_PAGE_SIZE)
        putString(IpcContract.Key.QUERY, query)
        putInt(IpcContract.Key.MATCH_MODE, matchMode.wireValue)
        putBoolean(IpcContract.Key.MATCH_CASE, matchCase)
    }

    fun decodeSearchSymbols(payload: Bundle): SymbolSearchRequest {
        val query = payload.requireString(
            IpcContract.Key.QUERY,
            IpcContract.MAX_SEARCH_QUERY_LENGTH,
        )
        if (query.isBlank()) {
            throw ProtocolException(IpcContract.Error.INVALID_ARGUMENT, "Search query is blank")
        }
        val matchMode = SearchMatchMode.fromWireValue(
            payload.requireInt(IpcContract.Key.MATCH_MODE),
        ) ?: throw ProtocolException(IpcContract.Error.INVALID_ARGUMENT, "Invalid search match mode")
        val page = decodePage(payload, IpcContract.MAX_SYMBOL_COUNT)
        if (page.limit > IpcContract.SEARCH_PAGE_SIZE) {
            throw ProtocolException(IpcContract.Error.INVALID_ARGUMENT, "Symbol search page is too large")
        }
        return SymbolSearchRequest(
            query = query,
            matchMode = matchMode,
            matchCase = payload.requireBoolean(IpcContract.Key.MATCH_CASE),
            page = page,
        )
    }

    fun classMembers(
        classIndex: Int,
        memberKind: MemberKind,
        offset: Int = 0,
        limit: Int = IpcContract.DEFAULT_PAGE_SIZE,
    ): Bundle = page(offset, limit).apply {
        requireHierarchyIndex(classIndex)
        putInt(IpcContract.Key.CLASS_INDEX, classIndex)
        putInt(IpcContract.Key.MEMBER_KIND, memberKind.wireValue)
    }

    fun decodeClassMembers(payload: Bundle): MemberPageRequest {
        val memberKindValue = payload.requireInt(IpcContract.Key.MEMBER_KIND)
        val memberKind = MemberKind.fromWireValue(memberKindValue)
            ?: throw ProtocolException(IpcContract.Error.INVALID_ARGUMENT, "Invalid member kind")
        return MemberPageRequest(
            classIndex = payload.requireHierarchyIndex(IpcContract.Key.CLASS_INDEX),
            memberKind = memberKind,
            page = decodePage(payload),
        )
    }

    fun classInfo(classIndex: Int): Bundle = Bundle().apply {
        requireHierarchyIndex(classIndex)
        putInt(IpcContract.Key.CLASS_INDEX, classIndex)
    }

    fun decodeClassInfo(payload: Bundle): ClassInfoRequest = ClassInfoRequest(
        classIndex = payload.requireHierarchyIndex(IpcContract.Key.CLASS_INDEX),
    )

    fun methodAnalysis(
        classIndex: Int,
        methodIndex: Int,
        section: MethodAnalysisSection,
        offset: Int = 0,
        limit: Int = IpcContract.MAX_ANALYSIS_PAGE_SIZE,
    ): Bundle = page(offset, limit).apply {
        requireHierarchyIndex(classIndex)
        requireHierarchyIndex(methodIndex)
        require(limit <= IpcContract.MAX_ANALYSIS_PAGE_SIZE)
        putInt(IpcContract.Key.CLASS_INDEX, classIndex)
        putInt(IpcContract.Key.METHOD_INDEX, methodIndex)
        putInt(IpcContract.Key.ANALYSIS_SECTION, section.wireValue)
    }

    fun decodeMethodAnalysis(payload: Bundle): MethodAnalysisRequest {
        val section = MethodAnalysisSection.fromWireValue(
            payload.requireInt(IpcContract.Key.ANALYSIS_SECTION),
        ) ?: throw ProtocolException(IpcContract.Error.INVALID_ARGUMENT, "Invalid analysis section")
        val page = decodePage(payload)
        if (page.limit > IpcContract.MAX_ANALYSIS_PAGE_SIZE) {
            throw ProtocolException(IpcContract.Error.INVALID_ARGUMENT, "Method analysis page is too large")
        }
        return MethodAnalysisRequest(
            classIndex = payload.requireHierarchyIndex(IpcContract.Key.CLASS_INDEX),
            methodIndex = payload.requireHierarchyIndex(IpcContract.Key.METHOD_INDEX),
            section = section,
            page = page,
        )
    }

    fun readVisibleFields(
        classIndex: Int,
        objectAddress: Long,
        fieldIndices: IntArray,
    ): Bundle = Bundle().apply {
        requireHierarchyIndex(classIndex)
        validateObjectAddress(objectAddress)
        validateFieldIndices(fieldIndices)
        putInt(IpcContract.Key.CLASS_INDEX, classIndex)
        putLong(IpcContract.Key.OBJECT_ADDRESS, objectAddress)
        putIntArray(IpcContract.Key.FIELD_INDICES, fieldIndices.copyOf())
    }

    fun decodeReadVisibleFields(payload: Bundle): ReadFieldsRequest {
        val fieldIndices = payload.requireIntArray(
            IpcContract.Key.FIELD_INDICES,
            IpcContract.MAX_PAGE_SIZE,
        )
        validateFieldIndices(fieldIndices)
        return ReadFieldsRequest(
            classIndex = payload.requireHierarchyIndex(IpcContract.Key.CLASS_INDEX),
            objectAddress = payload.requireLong(IpcContract.Key.OBJECT_ADDRESS, minimum = 1),
            fieldIndices = fieldIndices,
        )
    }

    fun writePrimitive(
        classIndex: Int,
        objectAddress: Long,
        fieldIndex: Int,
        value: PrimitiveValue,
    ): Bundle = Bundle().apply {
        requireHierarchyIndex(classIndex)
        requireHierarchyIndex(fieldIndex)
        validateObjectAddress(objectAddress)
        validatePrimitive(value)
        putInt(IpcContract.Key.CLASS_INDEX, classIndex)
        putLong(IpcContract.Key.OBJECT_ADDRESS, objectAddress)
        putInt(IpcContract.Key.FIELD_INDEX, fieldIndex)
        putInt(IpcContract.Key.VALUE_KIND, value.kind.wireValue)
        putPrimitive(value)
    }

    fun decodeWritePrimitive(payload: Bundle): WritePrimitiveRequest {
        val kindValue = payload.requireInt(IpcContract.Key.VALUE_KIND)
        val kind = ValueKind.fromWireValue(kindValue)
            ?.takeIf(ValueKind::writable)
            ?: throw ProtocolException(IpcContract.Error.UNSUPPORTED_TYPE, "Unsupported write type")
        val value = payload.requirePrimitive(kind)
        validatePrimitive(value)
        return WritePrimitiveRequest(
            classIndex = payload.requireHierarchyIndex(IpcContract.Key.CLASS_INDEX),
            objectAddress = payload.requireLong(IpcContract.Key.OBJECT_ADDRESS, minimum = 1),
            fieldIndex = payload.requireHierarchyIndex(IpcContract.Key.FIELD_INDEX),
            value = value,
        )
    }

    fun closeTarget(): Bundle = Bundle()

    private fun page(
        offset: Int,
        limit: Int,
        maximumOffset: Int = IpcContract.MAX_HIERARCHY_COUNT,
    ): Bundle = Bundle().apply {
        validatePage(offset, limit, maximumOffset)
        putInt(IpcContract.Key.OFFSET, offset)
        putInt(IpcContract.Key.LIMIT, limit)
    }

    private fun decodePage(
        payload: Bundle,
        maximumOffset: Int = IpcContract.MAX_HIERARCHY_COUNT,
    ): PageRequest {
        val offset = payload.requireInt(
            IpcContract.Key.OFFSET,
            minimum = 0,
            maximum = maximumOffset,
        )
        val limit = payload.requireInt(
            IpcContract.Key.LIMIT,
            minimum = 1,
            maximum = IpcContract.MAX_PAGE_SIZE,
        )
        return PageRequest(offset, limit)
    }

    private fun Bundle.requireHierarchyIndex(key: String): Int = requireInt(
        key,
        minimum = 0,
        maximum = IpcContract.MAX_HIERARCHY_COUNT - 1,
    )

    private fun Bundle.putPrimitive(value: PrimitiveValue) {
        when (value) {
            is PrimitiveValue.BooleanValue -> putBoolean(IpcContract.Key.BOOLEAN_VALUE, value.value)
            is PrimitiveValue.Int32Value -> putInt(IpcContract.Key.INT_VALUE, value.value)
            is PrimitiveValue.Int64Value -> putLong(IpcContract.Key.LONG_VALUE, value.value)
            is PrimitiveValue.Float32Value -> putFloat(IpcContract.Key.FLOAT_VALUE, value.value)
            is PrimitiveValue.Float64Value -> putDouble(IpcContract.Key.DOUBLE_VALUE, value.value)
        }
    }

    private fun Bundle.requirePrimitive(kind: ValueKind): PrimitiveValue = when (kind) {
        ValueKind.BOOLEAN -> PrimitiveValue.BooleanValue(requireBoolean(IpcContract.Key.BOOLEAN_VALUE))
        ValueKind.INT32 -> PrimitiveValue.Int32Value(requireInt(IpcContract.Key.INT_VALUE))
        ValueKind.INT64 -> PrimitiveValue.Int64Value(requireLong(IpcContract.Key.LONG_VALUE))
        ValueKind.FLOAT32 -> PrimitiveValue.Float32Value(requireFloat(IpcContract.Key.FLOAT_VALUE))
        ValueKind.FLOAT64 -> PrimitiveValue.Float64Value(requireDouble(IpcContract.Key.DOUBLE_VALUE))
        ValueKind.STRING,
        ValueKind.UNRESOLVED,
        -> throw ProtocolException(IpcContract.Error.UNSUPPORTED_TYPE, "Unsupported write type")
    }

    private fun validatePage(offset: Int, limit: Int, maximumOffset: Int) {
        require(offset in 0..maximumOffset)
        require(limit in 1..IpcContract.MAX_PAGE_SIZE)
    }

    private fun requireHierarchyIndex(index: Int) {
        require(index in 0 until IpcContract.MAX_HIERARCHY_COUNT)
    }

    private fun validateObjectAddress(address: Long) {
        require(address > 0)
    }

    private fun validateFieldIndices(indices: IntArray) {
        require(indices.isNotEmpty())
        require(indices.size <= IpcContract.MAX_FIELD_READ_COUNT)
        require(indices.all { it in 0 until IpcContract.MAX_HIERARCHY_COUNT })
        require(indices.toSet().size == indices.size)
    }

    private fun validatePrimitive(value: PrimitiveValue) {
        when (value) {
            is PrimitiveValue.Float32Value -> require(value.value.isFinite())
            is PrimitiveValue.Float64Value -> require(value.value.isFinite())
            is PrimitiveValue.BooleanValue,
            is PrimitiveValue.Int32Value,
            is PrimitiveValue.Int64Value,
            -> Unit
        }
    }
}
