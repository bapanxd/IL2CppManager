package dev.ruri.il2cppmanager.ipc

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
import dev.ruri.il2cppmanager.domain.MethodAnalysisResult
import dev.ruri.il2cppmanager.domain.MethodAnalysisStatus
import dev.ruri.il2cppmanager.domain.MethodDescriptor
import dev.ruri.il2cppmanager.domain.MethodReferenceDescriptor
import dev.ruri.il2cppmanager.domain.NamespaceDescriptor
import dev.ruri.il2cppmanager.domain.PageResult
import dev.ruri.il2cppmanager.domain.PropertyDescriptor
import dev.ruri.il2cppmanager.domain.ProcessDescriptor
import dev.ruri.il2cppmanager.domain.SymbolKind
import dev.ruri.il2cppmanager.domain.SymbolSearchDescriptor
import dev.ruri.il2cppmanager.domain.TypeReferenceDescriptor
import dev.ruri.il2cppmanager.domain.TypeSearchDescriptor
import dev.ruri.il2cppmanager.domain.TypeSizeDescriptor
import dev.ruri.il2cppmanager.domain.ValueKind

object ResponsePayloadCodec {
    fun processPage(page: PageResult<ProcessDescriptor>): Bundle = Bundle().apply {
        val items = page.items
        putPageHeader(page.offset, page.totalCount, page.items.size)
        putIntArray(IpcContract.Key.PIDS, IntArray(items.size) { items[it].pid })
        putStringArray(IpcContract.Key.PROCESS_NAMES, Array(items.size) { items[it].name })
        putLongArray(
            IpcContract.Key.START_TICKS_LIST,
            LongArray(items.size) { items[it].startTicks },
        )
        items.forEach {
            require(it.pid > 0)
            require(it.name.length <= IpcContract.MAX_PROCESS_NAME_LENGTH)
            require(it.startTicks > 0)
        }
    }

    fun decodeProcessPage(payload: Bundle): PageResult<ProcessDescriptor> {
        val pids = payload.requireIntArray(IpcContract.Key.PIDS, IpcContract.MAX_PAGE_SIZE)
        val names = payload.requireStringArray(
            IpcContract.Key.PROCESS_NAMES,
            IpcContract.MAX_PAGE_SIZE,
            IpcContract.MAX_PROCESS_NAME_LENGTH,
        )
        val ticks = payload.requireLongArray(IpcContract.Key.START_TICKS_LIST, IpcContract.MAX_PAGE_SIZE)
        requireSameSize(pids.size, names.size, ticks.size)
        val header = payload.decodePageHeader(pids.size)
        val items = pids.indices.map { index ->
            ProcessDescriptor(pids[index], names[index], ticks[index])
        }
        return PageResult(header.offset, header.totalCount, items)
    }

    fun assemblyPage(page: PageResult<AssemblyDescriptor>): Bundle = namedPage(
        offset = page.offset,
        totalCount = page.totalCount,
        indices = page.items.mapToIntArray(AssemblyDescriptor::index),
        names = page.items.mapToStringArray(AssemblyDescriptor::name),
    )

    fun decodeAssemblyPage(payload: Bundle): PageResult<AssemblyDescriptor> {
        val named = decodeNamedPage(payload)
        return PageResult(
            named.header.offset,
            named.header.totalCount,
            named.indices.indices.map { AssemblyDescriptor(named.indices[it], named.names[it]) },
        )
    }

    fun namespacePage(page: PageResult<NamespaceDescriptor>): Bundle = namedPage(
        offset = page.offset,
        totalCount = page.totalCount,
        indices = page.items.mapToIntArray(NamespaceDescriptor::index),
        names = page.items.mapToStringArray(NamespaceDescriptor::name),
    )

    fun decodeNamespacePage(payload: Bundle): PageResult<NamespaceDescriptor> {
        val named = decodeNamedPage(payload)
        return PageResult(
            named.header.offset,
            named.header.totalCount,
            named.indices.indices.map { NamespaceDescriptor(named.indices[it], named.names[it]) },
        )
    }

    fun classPage(page: PageResult<ClassDescriptor>): Bundle = namedPage(
        offset = page.offset,
        totalCount = page.totalCount,
        indices = page.items.mapToIntArray(ClassDescriptor::index),
        names = page.items.mapToStringArray(ClassDescriptor::name),
    )

    fun decodeClassPage(payload: Bundle): PageResult<ClassDescriptor> {
        val named = decodeNamedPage(payload)
        return PageResult(
            named.header.offset,
            named.header.totalCount,
            named.indices.indices.map { ClassDescriptor(named.indices[it], named.names[it]) },
        )
    }

    fun typeSearchPage(page: PageResult<TypeSearchDescriptor>): Bundle = Bundle().apply {
        val items = page.items
        putPageHeader(page.offset, page.totalCount, items.size)
        items.forEach {
            requireHierarchyIndex(it.index)
            requireName(it.name)
            require(it.qualifiedName.length <= IpcContract.MAX_QUALIFIED_NAME_LENGTH)
        }
        putIntArray(IpcContract.Key.INDICES, items.mapToIntArray(TypeSearchDescriptor::index))
        putStringArray(IpcContract.Key.NAMES, items.mapToStringArray(TypeSearchDescriptor::name))
        putStringArray(
            IpcContract.Key.QUALIFIED_NAMES,
            items.mapToStringArray(TypeSearchDescriptor::qualifiedName),
        )
    }

    fun decodeTypeSearchPage(payload: Bundle): PageResult<TypeSearchDescriptor> {
        val indices = payload.requireIntArray(IpcContract.Key.INDICES, IpcContract.MAX_PAGE_SIZE)
        val names = payload.requireStringArray(
            IpcContract.Key.NAMES,
            IpcContract.MAX_PAGE_SIZE,
            IpcContract.MAX_NAME_LENGTH,
        )
        val qualifiedNames = payload.requireStringArray(
            IpcContract.Key.QUALIFIED_NAMES,
            IpcContract.MAX_PAGE_SIZE,
            IpcContract.MAX_QUALIFIED_NAME_LENGTH,
        )
        requireSameSize(indices.size, names.size, qualifiedNames.size)
        val header = payload.decodePageHeader(indices.size)
        return PageResult(
            header.offset,
            header.totalCount,
            indices.indices.map { index ->
                TypeSearchDescriptor(indices[index], names[index], qualifiedNames[index])
            },
        )
    }

    fun symbolSearchPage(page: PageResult<SymbolSearchDescriptor>): Bundle = Bundle().apply {
        val items = page.items
        putPageHeader(
            page.offset,
            page.totalCount,
            items.size,
            IpcContract.MAX_SYMBOL_COUNT,
        )
        items.forEach { item ->
            requireHierarchyIndex(item.classIndex)
            require(
                (item.memberIndex == -1 && item.kind == SymbolKind.CLASS) ||
                    (item.memberIndex in 0 until IpcContract.MAX_HIERARCHY_COUNT &&
                        item.kind != SymbolKind.CLASS),
            )
            requireName(item.name)
            requireName(item.assemblyName)
            requireQualifiedName(item.ownerName)
        }
        putIntArray(IpcContract.Key.SYMBOL_KINDS, items.mapToIntArray { it.kind.wireValue })
        putIntArray(IpcContract.Key.CLASS_INDICES, items.mapToIntArray { it.classIndex })
        putIntArray(IpcContract.Key.MEMBER_INDICES, items.mapToIntArray { it.memberIndex })
        putStringArray(IpcContract.Key.NAMES, items.mapToStringArray { it.name })
        putStringArray(IpcContract.Key.ASSEMBLY_NAMES, items.mapToStringArray { it.assemblyName })
        putStringArray(IpcContract.Key.OWNER_NAMES, items.mapToStringArray { it.ownerName })
    }

    fun decodeSymbolSearchPage(payload: Bundle): PageResult<SymbolSearchDescriptor> {
        val kinds = payload.requireIntArray(IpcContract.Key.SYMBOL_KINDS, IpcContract.SEARCH_PAGE_SIZE)
        val classIndices = payload.requireIntArray(
            IpcContract.Key.CLASS_INDICES,
            IpcContract.SEARCH_PAGE_SIZE,
        )
        val memberIndices = payload.requireIntArray(
            IpcContract.Key.MEMBER_INDICES,
            IpcContract.SEARCH_PAGE_SIZE,
        )
        val names = payload.requireStringArray(
            IpcContract.Key.NAMES,
            IpcContract.SEARCH_PAGE_SIZE,
            IpcContract.MAX_NAME_LENGTH,
        )
        val assemblyNames = payload.requireStringArray(
            IpcContract.Key.ASSEMBLY_NAMES,
            IpcContract.SEARCH_PAGE_SIZE,
            IpcContract.MAX_NAME_LENGTH,
        )
        val ownerNames = payload.requireStringArray(
            IpcContract.Key.OWNER_NAMES,
            IpcContract.SEARCH_PAGE_SIZE,
            IpcContract.MAX_QUALIFIED_NAME_LENGTH,
        )
        requireSameSize(
            kinds.size,
            classIndices.size,
            memberIndices.size,
            names.size,
            assemblyNames.size,
            ownerNames.size,
        )
        val header = payload.decodePageHeader(kinds.size, IpcContract.MAX_SYMBOL_COUNT)
        val items = kinds.indices.map { index ->
            val kind = SymbolKind.fromWireValue(kinds[index]) ?: malformedResponse("symbol kind")
            val memberIndex = memberIndices[index]
            if (classIndices[index] !in 0 until IpcContract.MAX_HIERARCHY_COUNT ||
                kind == SymbolKind.CLASS && memberIndex != -1 ||
                kind != SymbolKind.CLASS && memberIndex !in 0 until IpcContract.MAX_HIERARCHY_COUNT) {
                malformedResponse("symbol index")
            }
            SymbolSearchDescriptor(
                kind,
                classIndices[index],
                memberIndex,
                names[index],
                assemblyNames[index],
                ownerNames[index],
            )
        }
        return PageResult(header.offset, header.totalCount, items)
    }

    fun classInfo(info: ClassInfoDescriptor): Bundle = Bundle().apply {
        requireHierarchyIndex(info.index)
        requireUnsignedInt(info.flags)
        requireUnsignedInt(info.token)
        requireUnsignedInt(info.bitfield)
        putInt(IpcContract.Key.CLASS_INDEX, info.index)
        putString(IpcContract.Key.CLASS_NAME, info.name)
        putString(IpcContract.Key.CLASS_NAMESPACE_NAME, info.namespaceName)
        putInt(IpcContract.Key.CLASS_ASSEMBLY_INDEX, info.assemblyIndex)
        putString(IpcContract.Key.CLASS_ASSEMBLY_NAME, info.assemblyName)
        putLong(IpcContract.Key.CLASS_FLAGS, info.flags)
        putLong(IpcContract.Key.CLASS_TOKEN, info.token)
        putLong(IpcContract.Key.CLASS_BITFIELD, info.bitfield)
        putTypeReference(
            info.parentType,
            IpcContract.Key.PARENT_TYPE_PRESENT,
            IpcContract.Key.PARENT_TYPE_INDEX,
            IpcContract.Key.PARENT_TYPE_NAME,
            IpcContract.Key.PARENT_TYPE_NAME_RESOLVED,
            IpcContract.Key.PARENT_DEFINITION_INDEX,
            IpcContract.Key.PARENT_DEFINITION_INDEX_PRESENT,
        )
        putTypeReference(
            info.declaringType,
            IpcContract.Key.DECLARING_TYPE_PRESENT,
            IpcContract.Key.DECLARING_TYPE_INDEX,
            IpcContract.Key.DECLARING_TYPE_NAME,
            IpcContract.Key.DECLARING_TYPE_NAME_RESOLVED,
            IpcContract.Key.DECLARING_DEFINITION_INDEX,
            IpcContract.Key.DECLARING_DEFINITION_INDEX_PRESENT,
        )
        putBoolean(IpcContract.Key.TYPE_SIZES_RESOLVED, info.sizes != null)
        putLongArray(
            IpcContract.Key.TYPE_SIZES,
            info.sizes?.toArray() ?: LongArray(TYPE_SIZE_VALUE_COUNT),
        )
    }

    fun decodeClassInfo(payload: Bundle): ClassInfoDescriptor {
        val sizes = payload.requireLongArray(IpcContract.Key.TYPE_SIZES, TYPE_SIZE_VALUE_COUNT)
        if (sizes.size != TYPE_SIZE_VALUE_COUNT) malformedResponse("type sizes")
        return ClassInfoDescriptor(
            index = payload.requireInt(
                IpcContract.Key.CLASS_INDEX,
                minimum = 0,
                maximum = IpcContract.MAX_HIERARCHY_COUNT - 1,
            ),
            name = payload.requireString(IpcContract.Key.CLASS_NAME, IpcContract.MAX_NAME_LENGTH),
            namespaceName = payload.requireString(
                IpcContract.Key.CLASS_NAMESPACE_NAME,
                IpcContract.MAX_NAME_LENGTH,
            ),
            assemblyIndex = payload.requireInt(
                IpcContract.Key.CLASS_ASSEMBLY_INDEX,
                minimum = 0,
                maximum = IpcContract.MAX_HIERARCHY_COUNT - 1,
            ),
            assemblyName = payload.requireString(
                IpcContract.Key.CLASS_ASSEMBLY_NAME,
                IpcContract.MAX_NAME_LENGTH,
            ),
            flags = payload.requireUnsignedInt(IpcContract.Key.CLASS_FLAGS),
            token = payload.requireUnsignedInt(IpcContract.Key.CLASS_TOKEN),
            bitfield = payload.requireUnsignedInt(IpcContract.Key.CLASS_BITFIELD),
            parentType = payload.decodeTypeReference(
                IpcContract.Key.PARENT_TYPE_PRESENT,
                IpcContract.Key.PARENT_TYPE_INDEX,
                IpcContract.Key.PARENT_TYPE_NAME,
                IpcContract.Key.PARENT_TYPE_NAME_RESOLVED,
                IpcContract.Key.PARENT_DEFINITION_INDEX,
                IpcContract.Key.PARENT_DEFINITION_INDEX_PRESENT,
            ),
            declaringType = payload.decodeTypeReference(
                IpcContract.Key.DECLARING_TYPE_PRESENT,
                IpcContract.Key.DECLARING_TYPE_INDEX,
                IpcContract.Key.DECLARING_TYPE_NAME,
                IpcContract.Key.DECLARING_TYPE_NAME_RESOLVED,
                IpcContract.Key.DECLARING_DEFINITION_INDEX,
                IpcContract.Key.DECLARING_DEFINITION_INDEX_PRESENT,
            ),
            sizes = sizes.takeIf { payload.requireBoolean(IpcContract.Key.TYPE_SIZES_RESOLVED) }
                ?.toTypeSizes(),
        )
    }

    fun nestedTypePage(page: PageResult<ClassDescriptor>): Bundle = classPage(page)

    fun decodeNestedTypePage(payload: Bundle): PageResult<ClassDescriptor> = decodeClassPage(payload)

    fun interfacePage(page: PageResult<TypeReferenceDescriptor>): Bundle = Bundle().apply {
        val items = page.items
        putPageHeader(page.offset, page.totalCount, items.size)
        putIntArray(IpcContract.Key.INDICES, items.mapToIntArray(TypeReferenceDescriptor::index))
        putIntArray(IpcContract.Key.TYPE_INDICES, items.mapToIntArray(TypeReferenceDescriptor::typeIndex))
        putIntArray(
            IpcContract.Key.DEFINITION_INDICES,
            items.mapToIntArray { it.definitionIndex ?: 0 },
        )
        putBooleanArray(
            IpcContract.Key.DEFINITION_INDEX_RESOLVED,
            items.mapToBooleanArray { it.definitionIndex != null },
        )
        putStringArray(IpcContract.Key.TYPE_NAMES, items.mapToStringArray { it.name.orEmpty() })
        putBooleanArray(IpcContract.Key.TYPE_RESOLVED, items.mapToBooleanArray { it.name != null })
        items.forEach {
            requireHierarchyIndex(it.index)
            requireHierarchyIndex(it.typeIndex)
            it.definitionIndex?.let(::requireHierarchyIndex)
            it.name?.let(::requireQualifiedName)
        }
    }

    fun decodeInterfacePage(payload: Bundle): PageResult<TypeReferenceDescriptor> {
        val indices = payload.requireIntArray(IpcContract.Key.INDICES, IpcContract.MAX_PAGE_SIZE)
        val typeIndices = payload.requireIntArray(IpcContract.Key.TYPE_INDICES, IpcContract.MAX_PAGE_SIZE)
        val definitionIndices = payload.requireIntArray(
            IpcContract.Key.DEFINITION_INDICES,
            IpcContract.MAX_PAGE_SIZE,
        )
        val definitionResolved = payload.requireBooleanArray(
            IpcContract.Key.DEFINITION_INDEX_RESOLVED,
            IpcContract.MAX_PAGE_SIZE,
        )
        val names = payload.requireStringArray(
            IpcContract.Key.TYPE_NAMES,
            IpcContract.MAX_PAGE_SIZE,
            IpcContract.MAX_QUALIFIED_NAME_LENGTH,
        )
        val resolved = payload.requireBooleanArray(IpcContract.Key.TYPE_RESOLVED, IpcContract.MAX_PAGE_SIZE)
        requireSameSize(
            indices.size,
            typeIndices.size,
            definitionIndices.size,
            definitionResolved.size,
            names.size,
            resolved.size,
        )
        val header = payload.decodePageHeader(indices.size)
        val items = indices.indices.map { index ->
            TypeReferenceDescriptor(
                index = indices[index],
                typeIndex = typeIndices[index],
                definitionIndex = definitionIndices[index].takeIf { definitionResolved[index] },
                name = names[index].takeIf { resolved[index] },
            )
        }
        return PageResult(header.offset, header.totalCount, items)
    }

    fun fieldPage(page: PageResult<FieldDescriptor>): Bundle = Bundle().apply {
        val items = page.items
        putPageHeader(page.offset, page.totalCount, page.items.size)
        putIntArray(IpcContract.Key.INDICES, IntArray(items.size) { items[it].index })
        putStringArray(IpcContract.Key.NAMES, Array(items.size) { items[it].name })
        putIntArray(IpcContract.Key.TYPE_INDICES, IntArray(items.size) { items[it].typeIndex })
        putStringArray(
            IpcContract.Key.TYPE_NAMES,
            Array(items.size) { items[it].typeName.orEmpty() },
        )
        putBooleanArray(
            IpcContract.Key.TYPE_RESOLVED,
            BooleanArray(items.size) { items[it].typeName != null },
        )
        putLongArray(IpcContract.Key.OFFSETS, LongArray(items.size) { items[it].offset ?: 0L })
        putBooleanArray(IpcContract.Key.OFFSET_RESOLVED, BooleanArray(items.size) { items[it].offset != null })
        putIntArray(IpcContract.Key.FLAGS, IntArray(items.size) { items[it].flags ?: 0 })
        putBooleanArray(IpcContract.Key.FLAGS_RESOLVED, BooleanArray(items.size) { items[it].flags != null })
        items.forEach {
            requireHierarchyIndex(it.index)
            requireHierarchyIndex(it.typeIndex)
            requireName(it.name)
            it.typeName?.let(::requireQualifiedName)
            it.offset?.let { offset -> require(offset >= FieldDescriptor.THREAD_STATIC_OFFSET) }
        }
    }

    fun decodeFieldPage(payload: Bundle): PageResult<FieldDescriptor> {
        val indices = payload.requireIntArray(IpcContract.Key.INDICES, IpcContract.MAX_PAGE_SIZE)
        val names = payload.requireStringArray(
            IpcContract.Key.NAMES,
            IpcContract.MAX_PAGE_SIZE,
            IpcContract.MAX_NAME_LENGTH,
        )
        val typeIndices = payload.requireIntArray(IpcContract.Key.TYPE_INDICES, IpcContract.MAX_PAGE_SIZE)
        val types = payload.requireStringArray(
            IpcContract.Key.TYPE_NAMES,
            IpcContract.MAX_PAGE_SIZE,
            IpcContract.MAX_QUALIFIED_NAME_LENGTH,
        )
        val typeResolved = payload.requireBooleanArray(
            IpcContract.Key.TYPE_RESOLVED,
            IpcContract.MAX_PAGE_SIZE,
        )
        val offsets = payload.requireLongArray(IpcContract.Key.OFFSETS, IpcContract.MAX_PAGE_SIZE)
        val offsetResolved = payload.requireBooleanArray(IpcContract.Key.OFFSET_RESOLVED, IpcContract.MAX_PAGE_SIZE)
        val flags = payload.requireIntArray(IpcContract.Key.FLAGS, IpcContract.MAX_PAGE_SIZE)
        val flagsResolved = payload.requireBooleanArray(IpcContract.Key.FLAGS_RESOLVED, IpcContract.MAX_PAGE_SIZE)
        requireSameSize(
            indices.size,
            names.size,
            typeIndices.size,
            types.size,
            typeResolved.size,
            offsets.size,
            offsetResolved.size,
            flags.size,
            flagsResolved.size,
        )
        val header = payload.decodePageHeader(indices.size)
        val items = indices.indices.map { index ->
            FieldDescriptor(
                index = indices[index],
                name = names[index],
                typeIndex = typeIndices[index],
                typeName = types[index].takeIf { typeResolved[index] },
                offset = offsets[index].takeIf { offsetResolved[index] },
                flags = flags[index].takeIf { flagsResolved[index] },
            )
        }
        return PageResult(header.offset, header.totalCount, items)
    }

    fun propertyPage(page: PageResult<PropertyDescriptor>): Bundle = Bundle().apply {
        val items = page.items
        putPageHeader(page.offset, page.totalCount, items.size)
        putIntArray(IpcContract.Key.INDICES, items.mapToIntArray(PropertyDescriptor::index))
        putStringArray(IpcContract.Key.NAMES, items.mapToStringArray(PropertyDescriptor::name))
        putIntArray(IpcContract.Key.TYPE_INDICES, items.mapToIntArray { it.typeIndex ?: 0 })
        putBooleanArray(IpcContract.Key.TYPE_INDEX_PRESENT, items.mapToBooleanArray { it.typeIndex != null })
        putStringArray(IpcContract.Key.TYPE_NAMES, items.mapToStringArray { it.typeName.orEmpty() })
        putBooleanArray(IpcContract.Key.TYPE_RESOLVED, items.mapToBooleanArray { it.typeName != null })
        putIntArray(IpcContract.Key.GETTER_FLAGS, items.mapToIntArray { it.getterFlags ?: 0 })
        putBooleanArray(IpcContract.Key.GETTER_PRESENT, items.mapToBooleanArray { it.getterFlags != null })
        putIntArray(IpcContract.Key.SETTER_FLAGS, items.mapToIntArray { it.setterFlags ?: 0 })
        putBooleanArray(IpcContract.Key.SETTER_PRESENT, items.mapToBooleanArray { it.setterFlags != null })
        putLongArray(IpcContract.Key.ATTRIBUTES, items.mapToLongArray(PropertyDescriptor::attributes))
        putLongArray(IpcContract.Key.TOKENS, items.mapToLongArray(PropertyDescriptor::token))
        items.forEach {
            requireHierarchyIndex(it.index)
            it.typeIndex?.let(::requireHierarchyIndex)
            requireName(it.name)
            it.typeName?.let(::requireQualifiedName)
            requireUnsignedInt(it.attributes)
            requireUnsignedInt(it.token)
        }
    }

    fun decodePropertyPage(payload: Bundle): PageResult<PropertyDescriptor> {
        val arrays = payload.decodeMemberArrays()
        val typeIndices = payload.requireIntArray(IpcContract.Key.TYPE_INDICES, IpcContract.MAX_PAGE_SIZE)
        val typeIndexPresent = payload.requireBooleanArray(IpcContract.Key.TYPE_INDEX_PRESENT, IpcContract.MAX_PAGE_SIZE)
        val getterFlags = payload.requireIntArray(IpcContract.Key.GETTER_FLAGS, IpcContract.MAX_PAGE_SIZE)
        val getterPresent = payload.requireBooleanArray(IpcContract.Key.GETTER_PRESENT, IpcContract.MAX_PAGE_SIZE)
        val setterFlags = payload.requireIntArray(IpcContract.Key.SETTER_FLAGS, IpcContract.MAX_PAGE_SIZE)
        val setterPresent = payload.requireBooleanArray(IpcContract.Key.SETTER_PRESENT, IpcContract.MAX_PAGE_SIZE)
        val attributes = payload.requireLongArray(IpcContract.Key.ATTRIBUTES, IpcContract.MAX_PAGE_SIZE)
        val tokens = payload.requireLongArray(IpcContract.Key.TOKENS, IpcContract.MAX_PAGE_SIZE)
        requireSameSize(
            arrays.indices.size,
            typeIndices.size,
            typeIndexPresent.size,
            getterFlags.size,
            getterPresent.size,
            setterFlags.size,
            setterPresent.size,
            attributes.size,
            tokens.size,
        )
        val items = arrays.indices.indices.map { index ->
            PropertyDescriptor(
                index = arrays.indices[index],
                name = arrays.names[index],
                typeIndex = typeIndices[index].takeIf { typeIndexPresent[index] },
                typeName = arrays.typeNames[index].takeIf { arrays.typeResolved[index] },
                getterFlags = getterFlags[index].takeIf { getterPresent[index] },
                setterFlags = setterFlags[index].takeIf { setterPresent[index] },
                attributes = attributes[index],
                token = tokens[index],
            )
        }
        return PageResult(arrays.header.offset, arrays.header.totalCount, items)
    }

    fun eventPage(page: PageResult<EventDescriptor>): Bundle = Bundle().apply {
        val items = page.items
        putPageHeader(page.offset, page.totalCount, items.size)
        putIntArray(IpcContract.Key.INDICES, items.mapToIntArray(EventDescriptor::index))
        putStringArray(IpcContract.Key.NAMES, items.mapToStringArray(EventDescriptor::name))
        putIntArray(IpcContract.Key.TYPE_INDICES, items.mapToIntArray(EventDescriptor::typeIndex))
        putStringArray(IpcContract.Key.TYPE_NAMES, items.mapToStringArray { it.typeName.orEmpty() })
        putBooleanArray(IpcContract.Key.TYPE_RESOLVED, items.mapToBooleanArray { it.typeName != null })
        putIntArray(IpcContract.Key.ADD_FLAGS, items.mapToIntArray { it.addFlags ?: 0 })
        putBooleanArray(IpcContract.Key.ADD_PRESENT, items.mapToBooleanArray { it.addFlags != null })
        putIntArray(IpcContract.Key.REMOVE_FLAGS, items.mapToIntArray { it.removeFlags ?: 0 })
        putBooleanArray(IpcContract.Key.REMOVE_PRESENT, items.mapToBooleanArray { it.removeFlags != null })
        putIntArray(IpcContract.Key.RAISE_FLAGS, items.mapToIntArray { it.raiseFlags ?: 0 })
        putBooleanArray(IpcContract.Key.RAISE_PRESENT, items.mapToBooleanArray { it.raiseFlags != null })
        putLongArray(IpcContract.Key.TOKENS, items.mapToLongArray(EventDescriptor::token))
        items.forEach {
            requireHierarchyIndex(it.index)
            requireHierarchyIndex(it.typeIndex)
            requireName(it.name)
            it.typeName?.let(::requireQualifiedName)
            requireUnsignedInt(it.token)
        }
    }

    fun decodeEventPage(payload: Bundle): PageResult<EventDescriptor> {
        val arrays = payload.decodeMemberArrays()
        val typeIndices = payload.requireIntArray(IpcContract.Key.TYPE_INDICES, IpcContract.MAX_PAGE_SIZE)
        val addFlags = payload.requireIntArray(IpcContract.Key.ADD_FLAGS, IpcContract.MAX_PAGE_SIZE)
        val addPresent = payload.requireBooleanArray(IpcContract.Key.ADD_PRESENT, IpcContract.MAX_PAGE_SIZE)
        val removeFlags = payload.requireIntArray(IpcContract.Key.REMOVE_FLAGS, IpcContract.MAX_PAGE_SIZE)
        val removePresent = payload.requireBooleanArray(IpcContract.Key.REMOVE_PRESENT, IpcContract.MAX_PAGE_SIZE)
        val raiseFlags = payload.requireIntArray(IpcContract.Key.RAISE_FLAGS, IpcContract.MAX_PAGE_SIZE)
        val raisePresent = payload.requireBooleanArray(IpcContract.Key.RAISE_PRESENT, IpcContract.MAX_PAGE_SIZE)
        val tokens = payload.requireLongArray(IpcContract.Key.TOKENS, IpcContract.MAX_PAGE_SIZE)
        requireSameSize(
            arrays.indices.size,
            typeIndices.size,
            addFlags.size,
            addPresent.size,
            removeFlags.size,
            removePresent.size,
            raiseFlags.size,
            raisePresent.size,
            tokens.size,
        )
        val items = arrays.indices.indices.map { index ->
            EventDescriptor(
                index = arrays.indices[index],
                name = arrays.names[index],
                typeIndex = typeIndices[index],
                typeName = arrays.typeNames[index].takeIf { arrays.typeResolved[index] },
                addFlags = addFlags[index].takeIf { addPresent[index] },
                removeFlags = removeFlags[index].takeIf { removePresent[index] },
                raiseFlags = raiseFlags[index].takeIf { raisePresent[index] },
                token = tokens[index],
            )
        }
        return PageResult(arrays.header.offset, arrays.header.totalCount, items)
    }

    fun methodPage(page: PageResult<MethodDescriptor>): Bundle = Bundle().apply {
        val items = page.items
        putPageHeader(page.offset, page.totalCount, page.items.size)
        putIntArray(IpcContract.Key.INDICES, IntArray(items.size) { items[it].index })
        putStringArray(IpcContract.Key.NAMES, Array(items.size) { items[it].name })
        putStringArray(
            IpcContract.Key.SIGNATURES,
            Array(items.size) { items[it].signature.orEmpty() },
        )
        putBooleanArray(
            IpcContract.Key.SIGNATURE_RESOLVED,
            BooleanArray(items.size) { items[it].signature != null },
        )
        putLongArray(IpcContract.Key.ADDRESSES, LongArray(items.size) { items[it].address ?: 0L })
        putBooleanArray(IpcContract.Key.ADDRESS_RESOLVED, BooleanArray(items.size) { items[it].address != null })
        putLongArray(IpcContract.Key.RVAS, LongArray(items.size) { items[it].rva ?: 0L })
        putBooleanArray(IpcContract.Key.RVA_RESOLVED, BooleanArray(items.size) { items[it].rva != null })
        items.forEach {
            requireHierarchyIndex(it.index)
            requireName(it.name)
            it.signature?.let(::requireQualifiedName)
            it.address?.let { address -> require(address > 0) }
            it.rva?.let { rva -> require(rva >= 0) }
        }
    }

    fun decodeMethodPage(payload: Bundle): PageResult<MethodDescriptor> {
        val indices = payload.requireIntArray(IpcContract.Key.INDICES, IpcContract.MAX_PAGE_SIZE)
        val names = payload.requireStringArray(
            IpcContract.Key.NAMES,
            IpcContract.MAX_PAGE_SIZE,
            IpcContract.MAX_NAME_LENGTH,
        )
        val signatures = payload.requireStringArray(
            IpcContract.Key.SIGNATURES,
            IpcContract.MAX_PAGE_SIZE,
            IpcContract.MAX_QUALIFIED_NAME_LENGTH,
        )
        val signatureResolved = payload.requireBooleanArray(
            IpcContract.Key.SIGNATURE_RESOLVED,
            IpcContract.MAX_PAGE_SIZE,
        )
        val addresses = payload.requireLongArray(IpcContract.Key.ADDRESSES, IpcContract.MAX_PAGE_SIZE)
        val addressResolved = payload.requireBooleanArray(IpcContract.Key.ADDRESS_RESOLVED, IpcContract.MAX_PAGE_SIZE)
        val rvas = payload.requireLongArray(IpcContract.Key.RVAS, IpcContract.MAX_PAGE_SIZE)
        val rvaResolved = payload.requireBooleanArray(IpcContract.Key.RVA_RESOLVED, IpcContract.MAX_PAGE_SIZE)
        requireSameSize(
            indices.size,
            names.size,
            signatures.size,
            signatureResolved.size,
            addresses.size,
            addressResolved.size,
            rvas.size,
            rvaResolved.size,
        )
        val header = payload.decodePageHeader(indices.size)
        val items = indices.indices.map { index ->
            MethodDescriptor(
                index = indices[index],
                name = names[index],
                signature = signatures[index].takeIf { signatureResolved[index] },
                address = addresses[index].takeIf { addressResolved[index] },
                rva = rvas[index].takeIf { rvaResolved[index] },
            )
        }
        return PageResult(header.offset, header.totalCount, items)
    }

    fun methodReferenceAnalysis(
        result: MethodAnalysisResult<MethodReferenceDescriptor>,
    ): Bundle = Bundle().apply {
        val items = result.page.items
        putAnalysisHeader(result, items.size)
        items.forEach { reference ->
            reference.classIndex?.let(::requireHierarchyIndex)
            reference.methodIndex?.let(::requireHierarchyIndex)
            reference.name?.let(::requireName)
            reference.ownerName?.let(::requireQualifiedName)
            reference.signature?.let(::requireQualifiedName)
            require(reference.address > 0)
            reference.rva?.let { require(it >= 0) }
            require(reference.callSiteAddress > 0)
            reference.callSiteRva?.let { require(it >= 0) }
            require(reference.callSiteInstructionIndex >= 0)
        }
        putIntArray(
            IpcContract.Key.CLASS_INDICES,
            items.mapToIntArray { it.classIndex ?: 0 },
        )
        putIntArray(
            IpcContract.Key.MEMBER_INDICES,
            items.mapToIntArray { it.methodIndex ?: 0 },
        )
        putStringArray(IpcContract.Key.NAMES, items.mapToStringArray { it.name.orEmpty() })
        putStringArray(
            IpcContract.Key.OWNER_NAMES,
            items.mapToStringArray { it.ownerName.orEmpty() },
        )
        putStringArray(
            IpcContract.Key.SIGNATURES,
            items.mapToStringArray { it.signature.orEmpty() },
        )
        putBooleanArray(
            IpcContract.Key.REFERENCE_RESOLVED,
            items.mapToBooleanArray { it.classIndex != null },
        )
        putBooleanArray(
            IpcContract.Key.SIGNATURE_RESOLVED,
            items.mapToBooleanArray { it.signature != null },
        )
        putLongArray(
            IpcContract.Key.ADDRESSES,
            items.mapToLongArray(MethodReferenceDescriptor::address),
        )
        putLongArray(IpcContract.Key.RVAS, items.mapToLongArray { it.rva ?: 0L })
        putBooleanArray(
            IpcContract.Key.RVA_RESOLVED,
            items.mapToBooleanArray { it.rva != null },
        )
        putLongArray(
            IpcContract.Key.CALL_SITE_ADDRESSES,
            items.mapToLongArray(MethodReferenceDescriptor::callSiteAddress),
        )
        putLongArray(
            IpcContract.Key.CALL_SITE_RVAS,
            items.mapToLongArray { it.callSiteRva ?: 0L },
        )
        putBooleanArray(
            IpcContract.Key.CALL_SITE_RVA_RESOLVED,
            items.mapToBooleanArray { it.callSiteRva != null },
        )
        putIntArray(
            IpcContract.Key.CALL_SITE_INSTRUCTION_INDICES,
            items.mapToIntArray(MethodReferenceDescriptor::callSiteInstructionIndex),
        )
    }

    fun decodeMethodReferenceAnalysis(
        payload: Bundle,
    ): MethodAnalysisResult<MethodReferenceDescriptor> {
        val classIndices = payload.requireIntArray(
            IpcContract.Key.CLASS_INDICES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val methodIndices = payload.requireIntArray(
            IpcContract.Key.MEMBER_INDICES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val names = payload.requireStringArray(
            IpcContract.Key.NAMES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
            IpcContract.MAX_NAME_LENGTH,
        )
        val ownerNames = payload.requireStringArray(
            IpcContract.Key.OWNER_NAMES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
            IpcContract.MAX_QUALIFIED_NAME_LENGTH,
        )
        val signatures = payload.requireStringArray(
            IpcContract.Key.SIGNATURES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
            IpcContract.MAX_QUALIFIED_NAME_LENGTH,
        )
        val referenceResolved = payload.requireBooleanArray(
            IpcContract.Key.REFERENCE_RESOLVED,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val signatureResolved = payload.requireBooleanArray(
            IpcContract.Key.SIGNATURE_RESOLVED,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val addresses = payload.requireLongArray(
            IpcContract.Key.ADDRESSES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val rvas = payload.requireLongArray(
            IpcContract.Key.RVAS,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val rvaResolved = payload.requireBooleanArray(
            IpcContract.Key.RVA_RESOLVED,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val callSiteAddresses = payload.requireLongArray(
            IpcContract.Key.CALL_SITE_ADDRESSES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val callSiteRvas = payload.requireLongArray(
            IpcContract.Key.CALL_SITE_RVAS,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val callSiteRvaResolved = payload.requireBooleanArray(
            IpcContract.Key.CALL_SITE_RVA_RESOLVED,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val callSiteInstructionIndices = payload.requireIntArray(
            IpcContract.Key.CALL_SITE_INSTRUCTION_INDICES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        requireSameSize(
            classIndices.size,
            methodIndices.size,
            names.size,
            ownerNames.size,
            signatures.size,
            referenceResolved.size,
            signatureResolved.size,
            addresses.size,
            rvas.size,
            rvaResolved.size,
            callSiteAddresses.size,
            callSiteRvas.size,
            callSiteRvaResolved.size,
            callSiteInstructionIndices.size,
        )
        val header = payload.decodeAnalysisHeader(classIndices.size)
        val items = classIndices.indices.map { index ->
            val resolved = referenceResolved[index]
            val invalidResolvedReference =
                resolved && (
                    classIndices[index] !in 0 until IpcContract.MAX_HIERARCHY_COUNT ||
                        methodIndices[index] !in 0 until IpcContract.MAX_HIERARCHY_COUNT
                    )
            val invalidUnresolvedReference =
                !resolved && (
                    names[index].isNotEmpty() ||
                        ownerNames[index].isNotEmpty() ||
                        signatureResolved[index]
                    )
            if (addresses[index] <= 0 || callSiteAddresses[index] <= 0 ||
                callSiteInstructionIndices[index] < 0 ||
                invalidResolvedReference ||
                invalidUnresolvedReference ||
                rvaResolved[index] && rvas[index] < 0 ||
                callSiteRvaResolved[index] && callSiteRvas[index] < 0) {
                malformedResponse("method reference")
            }
            MethodReferenceDescriptor(
                classIndex = classIndices[index].takeIf { resolved },
                methodIndex = methodIndices[index].takeIf { resolved },
                name = names[index].takeIf { resolved },
                ownerName = ownerNames[index].takeIf { resolved },
                signature = signatures[index].takeIf { resolved && signatureResolved[index] },
                address = addresses[index],
                rva = rvas[index].takeIf { rvaResolved[index] },
                callSiteAddress = callSiteAddresses[index],
                callSiteRva = callSiteRvas[index].takeIf { callSiteRvaResolved[index] },
                callSiteInstructionIndex = callSiteInstructionIndices[index],
            )
        }
        return MethodAnalysisResult(
            page = PageResult(header.page.offset, header.page.totalCount, items),
            status = header.status,
            unresolvedIndirectCallCount = header.unresolvedIndirectCallCount,
        )
    }

    fun instructionAnalysis(
        result: MethodAnalysisResult<InstructionDescriptor>,
    ): Bundle = Bundle().apply {
        val items = result.page.items
        putAnalysisHeader(result, items.size)
        items.forEach { instruction ->
            require(instruction.address > 0)
            instruction.rva?.let { require(it >= 0) }
            require(instruction.bytes.isNotBlank())
            require(instruction.bytes.length <= IpcContract.MAX_INSTRUCTION_BYTES_LENGTH)
            require(instruction.mnemonic.isNotBlank())
            require(instruction.mnemonic.length <= IpcContract.MAX_INSTRUCTION_MNEMONIC_LENGTH)
            require(instruction.operands.length <= IpcContract.MAX_INSTRUCTION_OPERANDS_LENGTH)
        }
        putLongArray(
            IpcContract.Key.ADDRESSES,
            items.mapToLongArray(InstructionDescriptor::address),
        )
        putLongArray(IpcContract.Key.RVAS, items.mapToLongArray { it.rva ?: 0L })
        putBooleanArray(
            IpcContract.Key.RVA_RESOLVED,
            items.mapToBooleanArray { it.rva != null },
        )
        putStringArray(
            IpcContract.Key.INSTRUCTION_BYTES,
            items.mapToStringArray(InstructionDescriptor::bytes),
        )
        putStringArray(
            IpcContract.Key.MNEMONICS,
            items.mapToStringArray(InstructionDescriptor::mnemonic),
        )
        putStringArray(
            IpcContract.Key.OPERANDS,
            items.mapToStringArray(InstructionDescriptor::operands),
        )
        putIntArray(
            IpcContract.Key.FLOW_KINDS,
            items.mapToIntArray { it.flowKind.wireValue },
        )
        putIntArray(
            IpcContract.Key.TARGET_INSTRUCTION_INDICES,
            items.mapToIntArray { it.targetInstructionIndex ?: -1 },
        )
        putIntArray(
            IpcContract.Key.TARGET_CLASS_INDICES,
            items.mapToIntArray { it.target?.classIndex ?: 0 },
        )
        putIntArray(
            IpcContract.Key.TARGET_METHOD_INDICES,
            items.mapToIntArray { it.target?.methodIndex ?: 0 },
        )
        putStringArray(
            IpcContract.Key.TARGET_NAMES,
            items.mapToStringArray { it.target?.name.orEmpty() },
        )
        putStringArray(
            IpcContract.Key.TARGET_OWNER_NAMES,
            items.mapToStringArray { it.target?.ownerName.orEmpty() },
        )
        putStringArray(
            IpcContract.Key.TARGET_SIGNATURES,
            items.mapToStringArray { it.target?.signature.orEmpty() },
        )
        putBooleanArray(
            IpcContract.Key.TARGET_PRESENT,
            items.mapToBooleanArray { it.target != null },
        )
        putBooleanArray(
            IpcContract.Key.TARGET_METHOD_RESOLVED,
            items.mapToBooleanArray { it.target?.classIndex != null },
        )
        putBooleanArray(
            IpcContract.Key.TARGET_SIGNATURE_RESOLVED,
            items.mapToBooleanArray { it.target?.signature != null },
        )
        putLongArray(
            IpcContract.Key.TARGET_ADDRESSES,
            items.mapToLongArray { it.target?.address ?: 0L },
        )
        putLongArray(
            IpcContract.Key.TARGET_RVAS,
            items.mapToLongArray { it.target?.rva ?: 0L },
        )
        putBooleanArray(
            IpcContract.Key.TARGET_RVA_RESOLVED,
            items.mapToBooleanArray { it.target?.rva != null },
        )
    }

    fun decodeInstructionAnalysis(
        payload: Bundle,
    ): MethodAnalysisResult<InstructionDescriptor> {
        val addresses = payload.requireLongArray(
            IpcContract.Key.ADDRESSES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val rvas = payload.requireLongArray(
            IpcContract.Key.RVAS,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val rvaResolved = payload.requireBooleanArray(
            IpcContract.Key.RVA_RESOLVED,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val bytes = payload.requireStringArray(
            IpcContract.Key.INSTRUCTION_BYTES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
            IpcContract.MAX_INSTRUCTION_BYTES_LENGTH,
        )
        val mnemonics = payload.requireStringArray(
            IpcContract.Key.MNEMONICS,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
            IpcContract.MAX_INSTRUCTION_MNEMONIC_LENGTH,
        )
        val operands = payload.requireStringArray(
            IpcContract.Key.OPERANDS,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
            IpcContract.MAX_INSTRUCTION_OPERANDS_LENGTH,
        )
        val flowKinds = payload.requireIntArray(
            IpcContract.Key.FLOW_KINDS,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val targetInstructionIndices = payload.requireIntArray(
            IpcContract.Key.TARGET_INSTRUCTION_INDICES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val targetClassIndices = payload.requireIntArray(
            IpcContract.Key.TARGET_CLASS_INDICES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val targetMethodIndices = payload.requireIntArray(
            IpcContract.Key.TARGET_METHOD_INDICES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val targetNames = payload.requireStringArray(
            IpcContract.Key.TARGET_NAMES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
            IpcContract.MAX_NAME_LENGTH,
        )
        val targetOwnerNames = payload.requireStringArray(
            IpcContract.Key.TARGET_OWNER_NAMES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
            IpcContract.MAX_QUALIFIED_NAME_LENGTH,
        )
        val targetSignatures = payload.requireStringArray(
            IpcContract.Key.TARGET_SIGNATURES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
            IpcContract.MAX_QUALIFIED_NAME_LENGTH,
        )
        val targetPresent = payload.requireBooleanArray(
            IpcContract.Key.TARGET_PRESENT,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val targetMethodResolved = payload.requireBooleanArray(
            IpcContract.Key.TARGET_METHOD_RESOLVED,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val targetSignatureResolved = payload.requireBooleanArray(
            IpcContract.Key.TARGET_SIGNATURE_RESOLVED,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val targetAddresses = payload.requireLongArray(
            IpcContract.Key.TARGET_ADDRESSES,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val targetRvas = payload.requireLongArray(
            IpcContract.Key.TARGET_RVAS,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        val targetRvaResolved = payload.requireBooleanArray(
            IpcContract.Key.TARGET_RVA_RESOLVED,
            IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        )
        requireSameSize(
            addresses.size,
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
        )
        val header = payload.decodeAnalysisHeader(addresses.size)
        val items = addresses.indices.map { index ->
            val flowKind = InstructionFlowKind.fromWireValue(flowKinds[index])
                ?: malformedResponse("instruction flow")
            val directFlow = flowKind == InstructionFlowKind.DIRECT_CALL ||
                flowKind == InstructionFlowKind.DIRECT_BRANCH
            if (targetPresent[index] != directFlow) {
                malformedResponse("instruction target")
            }
            val target = if (targetPresent[index]) {
                val resolved = targetMethodResolved[index]
                val invalidResolvedTarget = resolved && (
                    targetClassIndices[index] !in 0 until IpcContract.MAX_HIERARCHY_COUNT ||
                        targetMethodIndices[index] !in 0 until IpcContract.MAX_HIERARCHY_COUNT
                    )
                val invalidUnresolvedTarget = !resolved && (
                    targetNames[index].isNotEmpty() ||
                        targetOwnerNames[index].isNotEmpty() ||
                        targetSignatureResolved[index]
                    )
                if (targetAddresses[index] <= 0 || invalidResolvedTarget ||
                    invalidUnresolvedTarget || targetSignatureResolved[index] && !resolved ||
                    targetRvaResolved[index] && targetRvas[index] < 0
                ) {
                    malformedResponse("instruction target")
                }
                MethodReferenceDescriptor(
                    classIndex = targetClassIndices[index].takeIf { resolved },
                    methodIndex = targetMethodIndices[index].takeIf { resolved },
                    name = targetNames[index].takeIf { resolved },
                    ownerName = targetOwnerNames[index].takeIf { resolved },
                    signature = targetSignatures[index]
                        .takeIf { resolved && targetSignatureResolved[index] },
                    address = targetAddresses[index],
                    rva = targetRvas[index].takeIf { targetRvaResolved[index] },
                    callSiteAddress = addresses[index],
                    callSiteRva = rvas[index].takeIf { rvaResolved[index] },
                    callSiteInstructionIndex = header.page.offset + index,
                )
            } else {
                val invalidAbsentTarget = targetMethodResolved[index] ||
                    targetSignatureResolved[index] || targetAddresses[index] != 0L ||
                    targetRvas[index] != 0L || targetRvaResolved[index] ||
                    targetNames[index].isNotEmpty() || targetOwnerNames[index].isNotEmpty() ||
                    targetSignatures[index].isNotEmpty()
                if (invalidAbsentTarget) malformedResponse("instruction target")
                null
            }
            val targetInstructionIndex = targetInstructionIndices[index].let { value ->
                when {
                    value == -1 -> null
                    value in 0 until header.page.totalCount -> value
                    else -> malformedResponse("instruction target index")
                }
            }
            if (addresses[index] <= 0 ||
                rvaResolved[index] && rvas[index] < 0 ||
                bytes[index].isBlank() ||
                mnemonics[index].isBlank()) {
                malformedResponse("instruction")
            }
            InstructionDescriptor(
                address = addresses[index],
                rva = rvas[index].takeIf { rvaResolved[index] },
                bytes = bytes[index],
                mnemonic = mnemonics[index],
                operands = operands[index],
                flowKind = flowKind,
                targetInstructionIndex = targetInstructionIndex,
                target = target,
            )
        }
        return MethodAnalysisResult(
            page = PageResult(header.page.offset, header.page.totalCount, items),
            status = header.status,
            unresolvedIndirectCallCount = header.unresolvedIndirectCallCount,
        )
    }

    fun fieldReads(results: List<FieldReadResult>): Bundle = Bundle().apply {
        require(results.size <= IpcContract.MAX_FIELD_READ_COUNT)
        putIntArray(IpcContract.Key.FIELD_INDICES, results.mapToIntArray(FieldReadResult::fieldIndex))
        putLongArray(IpcContract.Key.ADDRESSES, results.mapToLongArray(FieldReadResult::address))
        putIntArray(
            IpcContract.Key.READ_STATUSES,
            results.mapToIntArray { it.status.wireValue },
        )
        putIntArray(
            IpcContract.Key.VALUE_KINDS,
            results.mapToIntArray { it.kind.wireValue },
        )
        putStringArray(
            IpcContract.Key.DISPLAY_VALUES,
            results.mapToStringArray { it.displayValue.take(IpcContract.MAX_DISPLAY_VALUE_LENGTH) },
        )
    }

    fun decodeFieldReads(payload: Bundle): List<FieldReadResult> {
        val indices = payload.requireIntArray(
            IpcContract.Key.FIELD_INDICES,
            IpcContract.MAX_FIELD_READ_COUNT,
        )
        val addresses = payload.requireLongArray(
            IpcContract.Key.ADDRESSES,
            IpcContract.MAX_FIELD_READ_COUNT,
        )
        val statuses = payload.requireIntArray(
            IpcContract.Key.READ_STATUSES,
            IpcContract.MAX_FIELD_READ_COUNT,
        )
        val kinds = payload.requireIntArray(
            IpcContract.Key.VALUE_KINDS,
            IpcContract.MAX_FIELD_READ_COUNT,
        )
        val displays = payload.requireStringArray(
            IpcContract.Key.DISPLAY_VALUES,
            IpcContract.MAX_FIELD_READ_COUNT,
            IpcContract.MAX_DISPLAY_VALUE_LENGTH,
        )
        requireSameSize(indices.size, addresses.size, statuses.size, kinds.size, displays.size)
        return indices.indices.map { index ->
            FieldReadResult(
                fieldIndex = indices[index],
                address = addresses[index],
                status = FieldReadStatus.entries.firstOrNull { it.wireValue == statuses[index] }
                    ?: malformedResponse("read status"),
                kind = ValueKind.fromWireValue(kinds[index]) ?: malformedResponse("value kind"),
                displayValue = displays[index],
            )
        }
    }

    fun writeResult(bytesWritten: Int): Bundle = Bundle().apply {
        require(bytesWritten >= 0)
        putInt(IpcContract.Key.BYTES_WRITTEN, bytesWritten)
    }

    fun decodeWriteResult(payload: Bundle): Int = payload.requireInt(
        IpcContract.Key.BYTES_WRITTEN,
        minimum = 0,
        maximum = Double.SIZE_BYTES,
    )

    private fun namedPage(
        offset: Int,
        totalCount: Int,
        indices: IntArray,
        names: Array<String>,
    ): Bundle = Bundle().apply {
        requireSameSize(indices.size, names.size)
        putPageHeader(offset, totalCount, indices.size)
        indices.forEach(::requireHierarchyIndex)
        names.forEach(::requireName)
        putIntArray(IpcContract.Key.INDICES, indices)
        putStringArray(IpcContract.Key.NAMES, names)
    }

    private fun decodeNamedPage(payload: Bundle): NamedPage {
        val indices = payload.requireIntArray(IpcContract.Key.INDICES, IpcContract.MAX_PAGE_SIZE)
        val names = payload.requireStringArray(
            IpcContract.Key.NAMES,
            IpcContract.MAX_PAGE_SIZE,
            IpcContract.MAX_NAME_LENGTH,
        )
        requireSameSize(indices.size, names.size)
        return NamedPage(payload.decodePageHeader(indices.size), indices, names)
    }

    private fun Bundle.decodeMemberArrays(): MemberArrays {
        val indices = requireIntArray(IpcContract.Key.INDICES, IpcContract.MAX_PAGE_SIZE)
        val names = requireStringArray(
            IpcContract.Key.NAMES,
            IpcContract.MAX_PAGE_SIZE,
            IpcContract.MAX_NAME_LENGTH,
        )
        val typeNames = requireStringArray(
            IpcContract.Key.TYPE_NAMES,
            IpcContract.MAX_PAGE_SIZE,
            IpcContract.MAX_QUALIFIED_NAME_LENGTH,
        )
        val typeResolved = requireBooleanArray(IpcContract.Key.TYPE_RESOLVED, IpcContract.MAX_PAGE_SIZE)
        requireSameSize(indices.size, names.size, typeNames.size, typeResolved.size)
        return MemberArrays(decodePageHeader(indices.size), indices, names, typeNames, typeResolved)
    }

    private fun Bundle.putTypeReference(
        reference: TypeReferenceDescriptor?,
        presentKey: String,
        indexKey: String,
        nameKey: String,
        nameResolvedKey: String,
        definitionIndexKey: String,
        definitionIndexPresentKey: String,
    ) {
        reference?.let {
            requireHierarchyIndex(it.typeIndex)
            it.definitionIndex?.let(::requireHierarchyIndex)
            it.name?.let(::requireQualifiedName)
        }
        putBoolean(presentKey, reference != null)
        putInt(indexKey, reference?.typeIndex ?: 0)
        putString(nameKey, reference?.name.orEmpty())
        putBoolean(nameResolvedKey, reference?.name != null)
        putInt(definitionIndexKey, reference?.definitionIndex ?: 0)
        putBoolean(definitionIndexPresentKey, reference?.definitionIndex != null)
    }

    private fun Bundle.decodeTypeReference(
        presentKey: String,
        indexKey: String,
        nameKey: String,
        nameResolvedKey: String,
        definitionIndexKey: String,
        definitionIndexPresentKey: String,
    ): TypeReferenceDescriptor? {
        val present = requireBoolean(presentKey)
        val typeIndex = requireInt(
            indexKey,
            minimum = 0,
            maximum = IpcContract.MAX_HIERARCHY_COUNT - 1,
        )
        val name = requireString(nameKey, IpcContract.MAX_QUALIFIED_NAME_LENGTH)
            .takeIf { requireBoolean(nameResolvedKey) }
        val definitionIndex = requireInt(
            definitionIndexKey,
            minimum = 0,
            maximum = IpcContract.MAX_HIERARCHY_COUNT - 1,
        ).takeIf { requireBoolean(definitionIndexPresentKey) }
        return if (present) {
            TypeReferenceDescriptor(0, typeIndex, definitionIndex, name)
        } else {
            null
        }
    }

    private fun Bundle.requireUnsignedInt(key: String): Long = requireLong(
        key,
        minimum = 0,
        maximum = MAX_UNSIGNED_INT,
    )

    private fun <T> Bundle.putAnalysisHeader(
        result: MethodAnalysisResult<T>,
        itemCount: Int,
    ) {
        require(itemCount <= IpcContract.MAX_ANALYSIS_PAGE_SIZE)
        require(result.unresolvedIndirectCallCount >= 0)
        putPageHeader(result.page.offset, result.page.totalCount, itemCount)
        putInt(IpcContract.Key.ANALYSIS_STATUS, result.status.wireValue)
        putInt(IpcContract.Key.INDIRECT_CALL_COUNT, result.unresolvedIndirectCallCount)
    }

    private fun Bundle.decodeAnalysisHeader(itemCount: Int): AnalysisHeader {
        if (itemCount > IpcContract.MAX_ANALYSIS_PAGE_SIZE) {
            malformedResponse("method analysis page")
        }
        val status = MethodAnalysisStatus.fromWireValue(
            requireInt(IpcContract.Key.ANALYSIS_STATUS),
        ) ?: malformedResponse("analysis status")
        return AnalysisHeader(
            page = decodePageHeader(itemCount),
            status = status,
            unresolvedIndirectCallCount = requireInt(
                IpcContract.Key.INDIRECT_CALL_COUNT,
                minimum = 0,
            ),
        )
    }

    private fun TypeSizeDescriptor.toArray(): LongArray {
        require(instanceSize in 0..MAX_UNSIGNED_INT)
        require(nativeSize in -1..Int.MAX_VALUE.toLong())
        require(staticFieldsSize in 0..MAX_UNSIGNED_INT)
        require(threadStaticFieldsSize in 0..MAX_UNSIGNED_INT)
        return longArrayOf(instanceSize, nativeSize, staticFieldsSize, threadStaticFieldsSize)
    }

    private fun LongArray.toTypeSizes(): TypeSizeDescriptor {
        if (this[0] !in 0..MAX_UNSIGNED_INT ||
            this[1] !in -1..Int.MAX_VALUE.toLong() ||
            this[2] !in 0..MAX_UNSIGNED_INT ||
            this[3] !in 0..MAX_UNSIGNED_INT) {
            malformedResponse("type size values")
        }
        return TypeSizeDescriptor(this[0], this[1], this[2], this[3])
    }

    private fun requireUnsignedInt(value: Long) {
        require(value in 0..MAX_UNSIGNED_INT)
    }

    private fun Bundle.putPageHeader(
        offset: Int,
        totalCount: Int,
        itemCount: Int,
        maximumTotalCount: Int = IpcContract.MAX_HIERARCHY_COUNT,
    ) {
        validatePageHeader(offset, totalCount, itemCount, maximumTotalCount)
        putInt(IpcContract.Key.OFFSET, offset)
        putInt(IpcContract.Key.TOTAL_COUNT, totalCount)
    }

    private fun Bundle.decodePageHeader(
        itemCount: Int,
        maximumTotalCount: Int = IpcContract.MAX_HIERARCHY_COUNT,
    ): PageHeader {
        val offset = requireInt(
            IpcContract.Key.OFFSET,
            minimum = 0,
            maximum = maximumTotalCount,
        )
        val totalCount = requireInt(
            IpcContract.Key.TOTAL_COUNT,
            minimum = 0,
            maximum = maximumTotalCount,
        )
        validatePageHeader(offset, totalCount, itemCount, maximumTotalCount)
        return PageHeader(offset, totalCount)
    }

    private fun validatePageHeader(
        offset: Int,
        totalCount: Int,
        itemCount: Int,
        maximumTotalCount: Int,
    ) {
        require(offset in 0..maximumTotalCount)
        require(totalCount in 0..maximumTotalCount)
        require(itemCount in 0..IpcContract.MAX_PAGE_SIZE)
        require(offset <= totalCount)
        require(itemCount <= totalCount - offset)
    }

    private fun requireHierarchyIndex(index: Int) {
        require(index in 0 until IpcContract.MAX_HIERARCHY_COUNT)
    }

    private fun requireName(name: String) {
        require(name.length <= IpcContract.MAX_NAME_LENGTH)
    }

    private fun requireQualifiedName(name: String) {
        require(name.length <= IpcContract.MAX_QUALIFIED_NAME_LENGTH)
    }

    private fun requireSameSize(expected: Int, vararg sizes: Int) {
        if (sizes.any { it != expected }) {
            malformedResponse("parallel arrays")
        }
    }

    private fun malformedResponse(subject: String): Nothing = throw ProtocolException(
        IpcContract.Error.MALFORMED_REQUEST,
        "Malformed response $subject",
    )

    private data class PageHeader(val offset: Int, val totalCount: Int)

    private data class AnalysisHeader(
        val page: PageHeader,
        val status: MethodAnalysisStatus,
        val unresolvedIndirectCallCount: Int,
    )

    private data class NamedPage(
        val header: PageHeader,
        val indices: IntArray,
        val names: Array<String>,
    )

    private data class MemberArrays(
        val header: PageHeader,
        val indices: IntArray,
        val names: Array<String>,
        val typeNames: Array<String>,
        val typeResolved: BooleanArray,
    )

    private inline fun <T> List<T>.mapToIntArray(transform: (T) -> Int): IntArray =
        IntArray(size) { transform(this[it]) }

    private inline fun <T> List<T>.mapToLongArray(transform: (T) -> Long): LongArray =
        LongArray(size) { transform(this[it]) }

    private inline fun <T> List<T>.mapToBooleanArray(transform: (T) -> Boolean): BooleanArray =
        BooleanArray(size) { transform(this[it]) }

    private inline fun <T> List<T>.mapToStringArray(transform: (T) -> String): Array<String> =
        Array(size) { transform(this[it]) }

    private const val TYPE_SIZE_VALUE_COUNT = 4
    private const val MAX_UNSIGNED_INT = 0xFFFF_FFFFL
}
