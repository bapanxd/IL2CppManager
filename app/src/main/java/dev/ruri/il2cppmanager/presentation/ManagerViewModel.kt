package dev.ruri.il2cppmanager.presentation

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ruri.il2cppmanager.domain.AssemblyDescriptor
import dev.ruri.il2cppmanager.domain.ClassDescriptor
import dev.ruri.il2cppmanager.domain.FieldDescriptor
import dev.ruri.il2cppmanager.domain.InstructionDescriptor
import dev.ruri.il2cppmanager.domain.InstructionFlowKind
import dev.ruri.il2cppmanager.domain.MethodAnalysisResult
import dev.ruri.il2cppmanager.domain.MethodDescriptor
import dev.ruri.il2cppmanager.domain.MethodReferenceDescriptor
import dev.ruri.il2cppmanager.domain.NamespaceDescriptor
import dev.ruri.il2cppmanager.domain.PageResult
import dev.ruri.il2cppmanager.domain.ProcessDescriptor
import dev.ruri.il2cppmanager.domain.SearchMatchMode
import dev.ruri.il2cppmanager.domain.SymbolKind
import dev.ruri.il2cppmanager.domain.SymbolSearchDescriptor
import dev.ruri.il2cppmanager.domain.TypeSearchDescriptor
import dev.ruri.il2cppmanager.ipc.IpcContract
import dev.ruri.il2cppmanager.ipc.RemoteServiceException
import dev.ruri.il2cppmanager.ipc.RootServiceClient
import dev.ruri.il2cppmanager.ui.BreadcrumbViewData
import dev.ruri.il2cppmanager.ui.BrowserEntryKind
import dev.ruri.il2cppmanager.ui.BrowserEntryViewData
import dev.ruri.il2cppmanager.ui.BrowserPage
import dev.ruri.il2cppmanager.ui.BrowserSearchOptions
import dev.ruri.il2cppmanager.ui.BrowserSearchScope
import dev.ruri.il2cppmanager.ui.CanvasTabViewData
import dev.ruri.il2cppmanager.ui.CallGraphDirection
import dev.ruri.il2cppmanager.ui.CallGraphEdgeViewData
import dev.ruri.il2cppmanager.ui.CallGraphEditorViewData
import dev.ruri.il2cppmanager.ui.CallGraphExpansionKey
import dev.ruri.il2cppmanager.ui.CallGraphExpansionViewData
import dev.ruri.il2cppmanager.ui.CallGraphNodePositionViewData
import dev.ruri.il2cppmanager.ui.CallGraphNodeViewData
import dev.ruri.il2cppmanager.ui.CallGraphViewData
import dev.ruri.il2cppmanager.ui.ClassTab
import dev.ruri.il2cppmanager.ui.DirectoryLevel
import dev.ruri.il2cppmanager.ui.FeedbackViewData
import dev.ruri.il2cppmanager.ui.FieldViewData
import dev.ruri.il2cppmanager.ui.InstructionViewData
import dev.ruri.il2cppmanager.ui.InstructionAddressMode
import dev.ruri.il2cppmanager.ui.InstructionScrollRequest
import dev.ruri.il2cppmanager.ui.ManagerAction
import dev.ruri.il2cppmanager.ui.ManagerContent
import dev.ruri.il2cppmanager.ui.ManagerUiState
import dev.ruri.il2cppmanager.ui.MethodAnalysisSectionViewData
import dev.ruri.il2cppmanager.ui.MethodCanvasPage
import dev.ruri.il2cppmanager.ui.MethodCopyTarget
import dev.ruri.il2cppmanager.ui.MethodReferenceViewData
import dev.ruri.il2cppmanager.ui.MethodViewData
import dev.ruri.il2cppmanager.ui.NavigationDirection
import dev.ruri.il2cppmanager.ui.PagedSearchViewData
import dev.ruri.il2cppmanager.ui.ProcessViewData
import dev.ruri.il2cppmanager.ui.SearchSpecViewData
import dev.ruri.il2cppmanager.ui.SymbolSearchViewData
import dev.ruri.il2cppmanager.ui.WorkspaceTabsViewData
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val client = RootServiceClient.getInstance(application)
    private val packageManager = application.packageManager
    private val clipboardManager = requireNotNull(
        application.getSystemService(ClipboardManager::class.java),
    )
    private val mutableState = MutableStateFlow(ManagerUiState())
    val state: StateFlow<ManagerUiState> = mutableState.asStateFlow()

    private var hostStarted = false
    private var sessionConnected = false
    private var selectedDescriptor: ProcessDescriptor? = null
    private var processDescriptors = emptyMap<String, ProcessDescriptor>()
    private var browserStack = emptyList<Destination>()
    private var methodCanvases = emptyList<MethodCanvasState>()
    private var activeCanvasId: String? = null
    private var lastActiveCanvasId: String? = null
    private var operationGeneration = 0L
    private var sessionGeneration = 0L
    private var scanGeneration = 0L
    private var routeSequence = 0L
    private var instructionScrollSequence = 0L
    private var feedbackSequence = 0L
    private var requestJob: Job? = null
    private var scanJob: Job? = null
    private val installedAppIndex by lazy(::installedApplications)
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private val canvasJobs = mutableMapOf<String, Job>()
    private val canvasGenerations = mutableMapOf<String, Long>()
    fun onHostStarted() {
        if (hostStarted) return
        hostStarted = true
        if (mutableState.value.processPicker.isVisible &&
            scanJob?.isActive != true &&
            processDescriptors.isEmpty()
        ) {
            scanProcesses()
        }
    }

    fun onHostStopped() {
        hostStarted = false
    }

    fun onAction(action: ManagerAction) {
        when (action) {
            ManagerAction.ToggleProcessPicker -> toggleProcessPicker()
            ManagerAction.DismissProcessPicker -> setPickerVisible(false)
            is ManagerAction.ProcessQueryChanged -> updatePickerQuery(action.query)
            is ManagerAction.BrowserQueryChanged -> updateBrowserQuery(action.query)
            is ManagerAction.BrowserSearchScopeChanged -> updateBrowserSearchScope(action.scope)
            is ManagerAction.BrowserMatchModeChanged -> updateBrowserMatchMode(action.mode)
            is ManagerAction.BrowserMatchCaseChanged -> updateBrowserMatchCase(action.matchCase)
            is ManagerAction.ProcessSelected -> selectProcess(action.processId)
            is ManagerAction.BreadcrumbSelected -> selectBreadcrumb(action.breadcrumbId)
            ManagerAction.NavigateBack -> navigateBack()
            is ManagerAction.BrowserEntrySelected -> selectEntry(action.entryId)
            ManagerAction.LoadMoreSearch -> loadMoreSearch()
            is ManagerAction.ClassTabSelected -> selectTab(action.tab)
            is ManagerAction.MethodSelected -> selectMethod(action.classIndex, action.methodIndex)
            ManagerAction.ToggleWorkspace -> toggleWorkspace()
            is ManagerAction.CanvasTabSelected -> selectCanvasTab(action.canvasId)
            is ManagerAction.CanvasTabClosed -> closeCanvasTab(action.canvasId)
            ManagerAction.CloseAllCanvasTabs -> closeAllCanvasTabs()
            is ManagerAction.MethodCanvasBack -> navigateCanvasBack(action.canvasId)
            is ManagerAction.CallGraphNodeSelected ->
                selectCallGraphNode(action.canvasId, action.nodeId)
            is ManagerAction.CallGraphNodeToggled -> toggleCallGraphNode(
                action.canvasId,
                action.nodeId,
                action.direction,
            )
            is ManagerAction.CallGraphNodeClosed ->
                closeCallGraphNode(action.canvasId, action.nodeId)
            is ManagerAction.CallGraphNodeMoved ->
                moveCallGraphNode(action.canvasId, action.nodeId, action.position)
            is ManagerAction.CallGraphLayoutPositionsDiscovered ->
                discoverCallGraphLayoutPositions(action.canvasId, action.positions)
            is ManagerAction.CallGraphUndo -> undoCallGraphEdit(action.canvasId)
            is ManagerAction.CallGraphRedo -> redoCallGraphEdit(action.canvasId)
            is ManagerAction.CallGraphNodeInstructionsSelected ->
                openCallGraphNodeInstructions(action.canvasId, action.nodeId)
            is ManagerAction.CallGraphNodeCanvasSelected ->
                openCallGraphNodeCanvas(action.canvasId, action.nodeId)
            is ManagerAction.LoadMoreInstructions -> loadMoreInstructions(action.canvasId)
            is ManagerAction.LoadPreviousInstructions -> loadPreviousInstructions(action.canvasId)
            is ManagerAction.InstructionSelected ->
                selectInstruction(action.canvasId, action.address)
            is ManagerAction.InstructionTargetSelected -> selectInstructionTarget(action)
            is ManagerAction.InstructionScrollConsumed ->
                consumeInstructionScroll(action.canvasId, action.requestId)
            is ManagerAction.CopyInstruction -> copyInstruction(action.canvasId, action.address)
            is ManagerAction.CopyMethodValue -> copyMethodValue(action.methodIndex, action.target)
            is ManagerAction.CopyCallGraphNodeValue ->
                copyCallGraphNodeValue(action.canvasId, action.nodeId, action.target)
            ManagerAction.DismissFeedback -> mutableState.update { it.copy(feedback = null) }
            ManagerAction.Retry -> retryTarget()
        }
    }

    override fun onCleared() {
        canvasJobs.values.forEach(Job::cancel)
        client.disconnect()
    }

    private fun toggleProcessPicker() {
        if (mutableState.value.content == ManagerContent.Parsing) {
            feedback(TARGET_LOADING)
            return
        }
        val visible = !mutableState.value.processPicker.isVisible
        setPickerVisible(visible)
        if (visible && hostStarted) {
            scanProcesses()
        }
    }

    private fun setPickerVisible(visible: Boolean) {
        mutableState.update { current ->
            current.copy(
                processPicker = current.processPicker.copy(
                    isVisible = visible,
                    isLoading = visible && hostStarted,
                    message = if (visible && !hostStarted) SERVICE_INACTIVE else null,
                ),
            )
        }
    }

    private fun updatePickerQuery(query: String) {
        mutableState.update { current ->
            current.copy(processPicker = current.processPicker.copy(query = query))
        }
    }

    private fun updateBrowserQuery(query: String) {
        if (activeCanvasId != null) return
        val destination = browserStack.lastOrNull() ?: return
        cancelPendingNavigation()
        val acceptedQuery = query.take(IpcContract.MAX_SEARCH_QUERY_LENGTH)
        val updated = destination.withSearchQuery(acceptedQuery)
        replaceTop(updated)
        when (updated) {
            is Destination.Assemblies -> if (acceptedQuery.isBlank()) {
                cancelRemoteSearch()
                render(NavigationDirection.NONE)
            } else if (mutableState.value.browserSearchOptions.scope == BrowserSearchScope.EVERYWHERE) {
                beginSymbolSearch(updated, NavigationDirection.NONE, debounce = true)
            } else {
                cancelRemoteSearch()
                render(NavigationDirection.NONE)
            }
            is Destination.Assembly -> if (acceptedQuery.isBlank()) {
                cancelRemoteSearch()
                render(NavigationDirection.NONE)
            } else {
                beginTypeSearch(updated, NavigationDirection.NONE, debounce = true)
            }
            else -> {
                cancelRemoteSearch()
                mutableState.update { it.copy(browserQuery = acceptedQuery) }
            }
        }
    }

    private fun updateBrowserSearchScope(scope: BrowserSearchScope) {
        if (activeCanvasId != null) return
        if (mutableState.value.browserSearchOptions.scope == scope) return
        if (browserStack.lastOrNull() !is Destination.Assemblies) return
        cancelPendingNavigation()
        cancelRemoteSearch()
        mutableState.update { current ->
            current.copy(
                browserSearchOptions = current.browserSearchOptions.copy(scope = scope),
            )
        }
        val root = (browserStack.lastOrNull() as Destination.Assemblies).copy(search = null)
        replaceTop(root)
        if (scope == BrowserSearchScope.EVERYWHERE && root.query.isNotBlank()) {
            beginSymbolSearch(root, NavigationDirection.NONE, debounce = false)
        } else {
            render(NavigationDirection.NONE)
        }
    }

    private fun updateBrowserMatchMode(mode: SearchMatchMode) {
        if (activeCanvasId != null) return
        if (mutableState.value.browserSearchOptions.matchMode == mode) return
        cancelPendingNavigation()
        mutableState.update { current ->
            current.copy(
                browserSearchOptions = current.browserSearchOptions.copy(matchMode = mode),
            )
        }
        restartVisibleRemoteSearch()
    }

    private fun updateBrowserMatchCase(matchCase: Boolean) {
        if (activeCanvasId != null) return
        if (mutableState.value.browserSearchOptions.matchCase == matchCase) return
        cancelPendingNavigation()
        mutableState.update { current ->
            current.copy(
                browserSearchOptions = current.browserSearchOptions.copy(matchCase = matchCase),
            )
        }
        restartVisibleRemoteSearch()
    }

    private fun restartVisibleRemoteSearch() {
        when (val page = browserStack.lastOrNull()) {
            is Destination.Assemblies -> if (
                page.query.isNotBlank() &&
                mutableState.value.browserSearchOptions.scope == BrowserSearchScope.EVERYWHERE
            ) {
                beginSymbolSearch(page.copy(search = null), NavigationDirection.NONE, debounce = true)
            }
            is Destination.Assembly -> if (page.query.isNotBlank()) {
                beginTypeSearch(page.copy(search = null), NavigationDirection.NONE, debounce = true)
            }
            else -> Unit
        }
    }

    private fun beginTypeSearch(
        page: Destination.Assembly,
        direction: NavigationDirection,
        debounce: Boolean,
    ) {
        val spec = searchSpec(page.query)
        val generation = nextSearchGeneration()
        replaceTop(
            page.copy(
                search = PagedSearchState(
                    spec = spec,
                    items = emptyList(),
                    totalCount = 0,
                    isInitialLoading = true,
                    isLoadingMore = false,
                ),
            ),
        )
        render(direction)
        requestTypeSearch(
            generation = generation,
            routeId = page.routeId,
            assemblyIndex = page.assembly.index,
            spec = spec,
            offset = 0,
            expectedTotalCount = null,
            debounce = debounce,
        )
    }

    private fun beginSymbolSearch(
        page: Destination.Assemblies,
        direction: NavigationDirection,
        debounce: Boolean,
    ) {
        val spec = searchSpec(page.query)
        val generation = nextSearchGeneration()
        replaceTop(
            page.copy(
                search = PagedSearchState(
                    spec = spec,
                    items = emptyList(),
                    totalCount = 0,
                    isInitialLoading = true,
                    isLoadingMore = false,
                ),
            ),
        )
        render(direction)
        requestSymbolSearch(
            generation = generation,
            routeId = page.routeId,
            spec = spec,
            offset = 0,
            expectedTotalCount = null,
            debounce = debounce,
        )
    }

    private fun loadMoreSearch() {
        if (activeCanvasId != null) return
        when (browserStack.lastOrNull()) {
            is Destination.Assemblies -> loadMoreSymbolSearch()
            is Destination.Assembly -> loadMoreTypeSearch()
            else -> Unit
        }
    }

    private fun loadMoreTypeSearch() {
        val page = browserStack.lastOrNull() as? Destination.Assembly ?: return
        val search = page.search ?: return
        if (searchJob?.isActive == true ||
            search.isInitialLoading ||
            search.isLoadingMore) {
            return
        }
        if (search.failureMessage != null && search.items.isEmpty()) {
            beginTypeSearch(page.copy(search = null), NavigationDirection.NONE, debounce = false)
            return
        }
        if (search.items.size >= search.totalCount) return
        val generation = nextSearchGeneration()
        replaceTop(
            page.copy(
                search = search.copy(
                    isLoadingMore = true,
                    failureMessage = null,
                ),
            ),
        )
        render(NavigationDirection.NONE)
        requestTypeSearch(
            generation = generation,
            routeId = page.routeId,
            assemblyIndex = page.assembly.index,
            spec = search.spec,
            offset = search.items.size,
            expectedTotalCount = search.totalCount,
            debounce = false,
        )
    }

    private fun loadMoreSymbolSearch() {
        if (mutableState.value.browserSearchOptions.scope != BrowserSearchScope.EVERYWHERE) return
        val page = browserStack.lastOrNull() as? Destination.Assemblies ?: return
        val search = page.search ?: return
        if (searchJob?.isActive == true || search.isInitialLoading || search.isLoadingMore) return
        if (search.failureMessage != null && search.items.isEmpty()) {
            beginSymbolSearch(page.copy(search = null), NavigationDirection.NONE, debounce = false)
            return
        }
        if (search.items.size >= search.totalCount) return
        val generation = nextSearchGeneration()
        replaceTop(
            page.copy(
                search = search.copy(
                    isLoadingMore = true,
                    failureMessage = null,
                ),
            ),
        )
        render(NavigationDirection.NONE)
        requestSymbolSearch(
            generation = generation,
            routeId = page.routeId,
            spec = search.spec,
            offset = search.items.size,
            expectedTotalCount = search.totalCount,
            debounce = false,
        )
    }

    private fun requestTypeSearch(
        generation: Long,
        routeId: String,
        assemblyIndex: Int,
        spec: SearchSpec,
        offset: Int,
        expectedTotalCount: Int?,
        debounce: Boolean,
    ) {
        searchJob = viewModelScope.launch {
            try {
                if (debounce) delay(SEARCH_DEBOUNCE_MILLIS)
                val result = client.searchTypes(
                    assemblyIndex = assemblyIndex,
                    query = spec.query,
                    matchMode = spec.matchMode,
                    matchCase = spec.matchCase,
                    offset = offset,
                    limit = IpcContract.SEARCH_PAGE_SIZE,
                )
                val page = activeTypeSearch(generation, routeId, spec) ?: return@launch
                val search = requireNotNull(page.search)
                check(search.items.size == offset) { INVALID_PAGE }
                check(result.offset == offset) { INVALID_PAGE }
                expectedTotalCount?.let { check(result.totalCount == it) { INVALID_PAGE } }
                check(result.items.size <= IpcContract.SEARCH_PAGE_SIZE) { INVALID_PAGE }
                val nextOffset = offset + result.items.size
                check(nextOffset <= result.totalCount) { INVALID_PAGE }
                check(nextOffset >= result.totalCount || result.items.isNotEmpty()) { STALLED_PAGE }
                val existingIndices = search.items.asSequence().map(TypeSearchDescriptor::index).toHashSet()
                val newIndices = result.items.map(TypeSearchDescriptor::index)
                check(newIndices.size == newIndices.toSet().size) { INVALID_PAGE }
                check(newIndices.none(existingIndices::contains)) { INVALID_PAGE }
                replaceTop(
                    page.copy(
                        search = search.copy(
                            items = search.items + result.items,
                            totalCount = result.totalCount,
                            isInitialLoading = false,
                            isLoadingMore = false,
                            failureMessage = null,
                        ),
                    ),
                )
                render(NavigationDirection.NONE)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                typeSearchFailure(generation, routeId, spec, error)
            } finally {
                if (generation == searchGeneration) searchJob = null
            }
        }
    }

    private fun requestSymbolSearch(
        generation: Long,
        routeId: String,
        spec: SearchSpec,
        offset: Int,
        expectedTotalCount: Int?,
        debounce: Boolean,
    ) {
        searchJob = viewModelScope.launch {
            try {
                if (debounce) delay(SEARCH_DEBOUNCE_MILLIS)
                val result = client.searchSymbols(
                    query = spec.query,
                    matchMode = spec.matchMode,
                    matchCase = spec.matchCase,
                    offset = offset,
                    limit = IpcContract.SEARCH_PAGE_SIZE,
                )
                val page = activeSymbolSearch(generation, routeId, spec) ?: return@launch
                val search = requireNotNull(page.search)
                check(search.items.size == offset) { INVALID_PAGE }
                check(result.offset == offset) { INVALID_PAGE }
                expectedTotalCount?.let { check(result.totalCount == it) { INVALID_PAGE } }
                check(result.items.size <= IpcContract.SEARCH_PAGE_SIZE) { INVALID_PAGE }
                val nextOffset = offset + result.items.size
                check(nextOffset <= result.totalCount) { INVALID_PAGE }
                check(nextOffset >= result.totalCount || result.items.isNotEmpty()) { STALLED_PAGE }
                val existingKeys = search.items.asSequence().map { it.searchKey() }.toHashSet()
                val newKeys = result.items.map { it.searchKey() }
                check(newKeys.size == newKeys.toSet().size) { INVALID_PAGE }
                check(newKeys.none(existingKeys::contains)) { INVALID_PAGE }
                replaceTop(
                    page.copy(
                        search = search.copy(
                            items = search.items + result.items,
                            totalCount = result.totalCount,
                            isInitialLoading = false,
                            isLoadingMore = false,
                            failureMessage = null,
                        ),
                    ),
                )
                render(NavigationDirection.NONE)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                symbolSearchFailure(generation, routeId, spec, error)
            } finally {
                if (generation == searchGeneration) searchJob = null
            }
        }
    }

    private fun typeSearchFailure(
        generation: Long,
        routeId: String,
        spec: SearchSpec,
        error: Throwable,
    ) {
        Log.e(LOG_TAG, "Type search failed", error)
        val page = activeTypeSearch(generation, routeId, spec) ?: return
        val search = requireNotNull(page.search)
        val message = error.userMessage()
        replaceTop(
            page.copy(
                search = search.copy(
                    isInitialLoading = false,
                    isLoadingMore = false,
                    failureMessage = message,
                ),
            ),
        )
        render(NavigationDirection.NONE)
        feedback(message)
        if (error.invalidatesSession()) sessionConnected = false
    }

    private fun symbolSearchFailure(
        generation: Long,
        routeId: String,
        spec: SearchSpec,
        error: Throwable,
    ) {
        Log.e(LOG_TAG, "Symbol search failed", error)
        val page = activeSymbolSearch(generation, routeId, spec) ?: return
        val search = requireNotNull(page.search)
        val message = error.userMessage()
        replaceTop(
            page.copy(
                search = search.copy(
                    isInitialLoading = false,
                    isLoadingMore = false,
                    failureMessage = message,
                ),
            ),
        )
        render(NavigationDirection.NONE)
        feedback(message)
        if (error.invalidatesSession()) sessionConnected = false
    }

    private fun activeTypeSearch(
        generation: Long,
        routeId: String,
        spec: SearchSpec,
    ): Destination.Assembly? {
        if (generation != searchGeneration) return null
        val page = browserStack.lastOrNull() as? Destination.Assembly ?: return null
        if (page.routeId != routeId || page.search?.spec != spec || searchSpec(page.query) != spec) {
            return null
        }
        return page
    }

    private fun activeSymbolSearch(
        generation: Long,
        routeId: String,
        spec: SearchSpec,
    ): Destination.Assemblies? {
        if (generation != searchGeneration) return null
        val page = browserStack.lastOrNull() as? Destination.Assemblies ?: return null
        if (page.routeId != routeId || page.search?.spec != spec || searchSpec(page.query) != spec) {
            return null
        }
        return page
    }

    private fun searchSpec(query: String): SearchSpec {
        val options = mutableState.value.browserSearchOptions
        return SearchSpec(query, options.scope, options.matchMode, options.matchCase)
    }

    private fun renderAfterNavigation(direction: NavigationDirection) {
        when (val page = browserStack.lastOrNull()) {
            is Destination.Assemblies -> if (
                page.query.isNotBlank() &&
                mutableState.value.browserSearchOptions.scope == BrowserSearchScope.EVERYWHERE &&
                page.search?.spec != searchSpec(page.query)
            ) {
                beginSymbolSearch(page.copy(search = null), direction, debounce = false)
                return
            }
            is Destination.Assembly -> if (
                page.query.isNotBlank() && page.search?.spec != searchSpec(page.query)
            ) {
                beginTypeSearch(page.copy(search = null), direction, debounce = false)
                return
            }
            else -> Unit
        }
        render(direction)
    }

    private fun nextSearchGeneration(): Long {
        searchJob?.cancel()
        searchJob = null
        return ++searchGeneration
    }

    private fun cancelRemoteSearch() {
        searchJob?.cancel()
        searchJob = null
        searchGeneration++
        when (val page = browserStack.lastOrNull()) {
            is Destination.Assemblies -> page.search?.takeIf {
                it.isInitialLoading || it.isLoadingMore
            }?.let { search ->
                replaceTop(
                    page.copy(
                        search = search.copy(
                            isInitialLoading = false,
                            isLoadingMore = false,
                        ),
                    ),
                )
            }
            is Destination.Assembly -> page.search?.takeIf {
                it.isInitialLoading || it.isLoadingMore
            }?.let { search ->
                replaceTop(
                    page.copy(
                        search = search.copy(
                            isInitialLoading = false,
                            isLoadingMore = false,
                        ),
                    ),
                )
            }
            else -> Unit
        }
    }

    private fun scanProcesses() {
        scanJob?.cancel()
        val generation = ++scanGeneration
        mutableState.update { current ->
            current.copy(
                processPicker = current.processPicker.copy(isLoading = true, message = null),
            )
        }
        scanJob = viewModelScope.launch {
            try {
                val descriptors = loadPages(client::scanProcesses).distinctBy(::processId)
                val views = withContext(Dispatchers.IO) { processViews(descriptors) }
                if (generation != scanGeneration) return@launch
                processDescriptors = descriptors.associateBy(::processId)
                mutableState.update { current ->
                    current.copy(
                        processPicker = current.processPicker.copy(
                            processes = views,
                            isLoading = false,
                            message = null,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "Process scan failed", error)
                if (generation == scanGeneration) {
                    mutableState.update { current ->
                        current.copy(
                            processPicker = current.processPicker.copy(
                                processes = emptyList(),
                                isLoading = false,
                                message = error.userMessage(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun processViews(descriptors: List<ProcessDescriptor>): List<ProcessViewData> {
        return descriptors.map { descriptor ->
            val baseName = descriptor.name.substringBefore(PROCESS_SEPARATOR)
            val app = installedAppIndex.byProcess[descriptor.name]
                ?: installedAppIndex.byPackage[baseName]
            ProcessViewData(
                id = processId(descriptor),
                pid = descriptor.pid,
                appName = app?.label ?: descriptor.name,
                packageName = app?.packageName ?: baseName,
                processName = descriptor.name,
            )
        }.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, ProcessViewData::appName)
                .thenBy(String.CASE_INSENSITIVE_ORDER, ProcessViewData::processName)
                .thenBy(ProcessViewData::pid),
        )
    }

    private fun installedApplications(): InstalledAppIndex {
        val infos = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }
        } catch (error: RuntimeException) {
            Log.e(LOG_TAG, "Installed application lookup failed", error)
            emptyList()
        }
        val applications = infos.map(::installedApp)
        return InstalledAppIndex(
            byPackage = applications.associateBy(InstalledApp::packageName),
            byProcess = applications.associateBy(InstalledApp::processName),
        )
    }

    private fun installedApp(info: ApplicationInfo): InstalledApp {
        val label = try {
            packageManager.getApplicationLabel(info).toString().ifBlank { info.packageName }
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Application label lookup failed for ${info.packageName}", error)
            info.packageName
        }
        return InstalledApp(
            packageName = info.packageName,
            processName = info.processName ?: info.packageName,
            label = label,
        )
    }

    private fun selectProcess(id: String) {
        if (mutableState.value.content == ManagerContent.Parsing) {
            feedback(TARGET_LOADING)
            return
        }
        val descriptor = processDescriptors[id]
        val view = mutableState.value.processPicker.processes.firstOrNull { it.id == id }
        if (descriptor == null || view == null) {
            feedback(PROCESS_GONE)
            scanProcesses()
            return
        }
        openTarget(descriptor, view)
    }

    private fun openTarget(descriptor: ProcessDescriptor, view: ProcessViewData) {
        resetMethodCanvases()
        val generation = beginOperation()
        selectedDescriptor = descriptor
        sessionConnected = false
        browserStack = emptyList()
        mutableState.update { current ->
            current.copy(
                selectedProcess = view,
                processPicker = current.processPicker.copy(isVisible = false),
                content = ManagerContent.Parsing,
                workspaceTabs = WorkspaceTabsViewData(),
                browserQuery = "",
                browserSearchOptions = current.browserSearchOptions.copy(
                    scope = BrowserSearchScope.CURRENT_LEVEL,
                ),
                breadcrumbs = listOf(BreadcrumbViewData(ROOT_ROUTE, view.appName)),
                navigationDirection = NavigationDirection.NONE,
                feedback = null,
            )
        }
        requestJob = viewModelScope.launch {
            try {
                client.openTarget(descriptor.pid, descriptor.startTicks)
                val assemblies = loadPages(client::listAssemblies)
                if (!current(generation)) return@launch
                sessionConnected = true
                browserStack = listOf(Destination.Assemblies(assemblies))
                render(NavigationDirection.NONE)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                targetFailure(generation, error, "Target open failed")
            }
        }
    }

    private fun targetFailure(generation: Long, error: Throwable, logMessage: String) {
        Log.e(LOG_TAG, logMessage, error)
        if (!current(generation)) return
        sessionConnected = false
        mutableState.update { current ->
            current.copy(
                content = ManagerContent.Failure(
                    message = error.userMessage(),
                    detail = error.remoteDetail(),
                    canRetry = error.retryable(),
                ),
                navigationDirection = NavigationDirection.NONE,
            )
        }
    }

    private fun retryTarget() {
        val descriptor = selectedDescriptor ?: return
        val view = mutableState.value.selectedProcess ?: return
        if (!hostStarted) {
            feedback(SERVICE_INACTIVE)
            return
        }
        openTarget(descriptor, view)
    }

    private fun selectEntry(id: String) {
        if (activeCanvasId != null) return
        when (val page = browserStack.lastOrNull()) {
            is Destination.Assemblies -> if (page.search == null) {
                page.items.firstOrNull {
                    entryId(page.routeId, ASSEMBLY_ENTRY, it.index) == id
                }?.let(::openAssembly)
            } else {
                page.search.items.firstOrNull {
                    symbolEntryId(page.routeId, it) == id
                }?.let(::openSymbolResult)
            }
            is Destination.Assembly -> {
                page.search?.items?.firstOrNull {
                    entryId(page.routeId, CLASS_ENTRY, it.index) == id
                }?.let { type ->
                    openClass(
                        classIndex = type.index,
                        className = type.name,
                        breadcrumbLabel = type.qualifiedName,
                    )
                } ?: page.namespaces.firstOrNull {
                    entryId(page.routeId, NAMESPACE_ENTRY, it.index) == id
                }?.let { openNamespace(page.assembly, it) } ?: page.globalClasses.firstOrNull {
                    entryId(page.routeId, CLASS_ENTRY, it.index) == id
                }?.let { clazz ->
                    openClass(clazz.index, clazz.name)
                }
            }
            is Destination.Namespace -> page.classes.firstOrNull {
                entryId(page.routeId, CLASS_ENTRY, it.index) == id
            }?.let { openClass(it.index, it.name) }
            is Destination.ClassDetails -> Unit
            null -> Unit
        }
    }

    private fun openSymbolResult(result: SymbolSearchDescriptor) {
        val tab = when (result.kind) {
            SymbolKind.CLASS,
            SymbolKind.FIELD,
            -> ClassTab.FIELDS
            SymbolKind.METHOD -> ClassTab.METHODS
        }
        val focusedMemberId = result.memberIndex.takeIf { result.kind != SymbolKind.CLASS }
        openClass(
            classIndex = result.classIndex,
            className = result.ownerName,
            breadcrumbLabel = result.ownerName,
            initialTab = tab,
            focusedMemberId = focusedMemberId,
        )
    }

    private fun openAssembly(assembly: AssemblyDescriptor) {
        if (!connected()) return
        val generation = beginOperation()
        requestJob = viewModelScope.launch {
            try {
                val namespaces = loadPages { offset, limit ->
                    client.listNamespaces(assembly.index, offset, limit)
                }
                val global = namespaces.firstOrNull { it.name.isBlank() }
                val globalClasses = global?.let {
                    loadPages { offset, limit ->
                        client.listClasses(assembly.index, it.index, offset, limit)
                    }
                }.orEmpty()
                if (!current(generation)) return@launch
                browserStack += Destination.Assembly(
                    routeId = nextRoute(ASSEMBLY_ROUTE),
                    assembly = assembly,
                    namespaces = namespaces.filterNot { it.name.isBlank() },
                    globalClasses = globalClasses,
                )
                render(NavigationDirection.FORWARD)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                navigationFailure(generation, error, "Assembly load failed")
            }
        }
    }

    private fun openNamespace(assembly: AssemblyDescriptor, namespace: NamespaceDescriptor) {
        if (!connected()) return
        val generation = beginOperation()
        requestJob = viewModelScope.launch {
            try {
                val classes = loadPages { offset, limit ->
                    client.listClasses(assembly.index, namespace.index, offset, limit)
                }
                if (!current(generation)) return@launch
                browserStack += Destination.Namespace(
                    routeId = nextRoute(NAMESPACE_ROUTE),
                    namespace = namespace,
                    classes = classes,
                )
                render(NavigationDirection.FORWARD)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                navigationFailure(generation, error, "Namespace load failed")
            }
        }
    }

    private fun openClass(
        classIndex: Int,
        className: String,
        breadcrumbLabel: String = className,
        initialTab: ClassTab = ClassTab.FIELDS,
        focusedMemberId: Int? = null,
    ) {
        if (!connected()) return
        val generation = beginOperation()
        requestJob = viewModelScope.launch {
            try {
                val members = loadClassMembers(classIndex)
                if (!current(generation)) return@launch
                browserStack += Destination.ClassDetails(
                    routeId = nextRoute(CLASS_ROUTE),
                    classIndex = classIndex,
                    label = breadcrumbLabel,
                    members = members,
                    tab = initialTab,
                    focusedMemberId = focusedMemberId,
                )
                render(NavigationDirection.FORWARD)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                navigationFailure(generation, error, "Class load failed")
            }
        }
    }

    private suspend fun loadClassMembers(classIndex: Int): ClassMembers =
        withContext(Dispatchers.Default) {
            coroutineScope {
                val fields = async {
                    loadPages { offset, limit -> client.listFields(classIndex, offset, limit) }
                }
                val methods = async {
                    loadPages { offset, limit -> client.listMethods(classIndex, offset, limit) }
                }
                ClassMembers(
                    fields = fields.await().map { it.viewData() },
                    methods = methods.await().map { it.viewData() },
                )
            }
        }

    private fun selectMethod(classIndex: Int, methodIndex: Int) {
        if (activeCanvasId != null) return
        val current = browserStack.lastOrNull() as? Destination.ClassDetails ?: return
        val target = current.members.methods
            .firstOrNull { current.classIndex == classIndex && it.id == methodIndex }
            ?.let { MethodTarget(current.label, it) }
            ?: return
        openMethod(classIndex, target)
    }

    private fun openMethod(
        classIndex: Int,
        target: MethodTarget,
    ) {
        if (!connected()) return
        val key = MethodKey(classIndex, target.method.id)
        val existing = methodCanvases.firstOrNull { it.key == key }
        if (existing != null) {
            activeCanvasId = existing.id
            lastActiveCanvasId = existing.id
            render(NavigationDirection.NONE)
            return
        }
        val method = MethodInstructionsState(
            classIndex = classIndex,
            methodIndex = target.method.id,
            ownerName = target.ownerName,
            name = target.method.name,
            signature = target.method.signature,
            rvaLabel = target.method.rvaLabel,
            addressLabel = target.method.addressLabel,
            address = target.method.address,
        )
        val canvasId = nextRoute(METHOD_ROUTE)
        methodCanvases += MethodCanvasState(
            id = canvasId,
            key = key,
            method = method,
            graph = method.initialCallGraph(),
        )
        canvasGenerations[canvasId] = 0L
        activeCanvasId = canvasId
        lastActiveCanvasId = canvasId
        render(NavigationDirection.FORWARD)
    }

    private fun toggleWorkspace() {
        val currentCanvasId = activeCanvasId
        activeCanvasId = if (currentCanvasId == null) {
            lastActiveCanvasId
                ?.takeIf { canvasId -> methodCanvas(canvasId) != null }
                ?: methodCanvases.lastOrNull()?.id
        } else {
            lastActiveCanvasId = currentCanvasId
            null
        }
        render(NavigationDirection.NONE)
    }

    private fun selectCanvasTab(canvasId: String) {
        if (activeCanvasId == canvasId || methodCanvas(canvasId) == null) return
        activeCanvasId = canvasId
        lastActiveCanvasId = canvasId
        render(NavigationDirection.NONE)
    }

    private fun closeCanvasTab(canvasId: String) {
        val index = methodCanvases.indexOfFirst { it.id == canvasId }
        if (index < 0) return
        val wasActive = activeCanvasId == canvasId
        cancelCanvasJob(canvasId)
        canvasGenerations.remove(canvasId)
        methodCanvases = methodCanvases.filterNot { it.id == canvasId }
        val fallbackCanvasId = methodCanvases.getOrNull(index)?.id
            ?: methodCanvases.getOrNull(index - 1)?.id
        if (wasActive) {
            activeCanvasId = fallbackCanvasId
        }
        if (lastActiveCanvasId == canvasId) {
            lastActiveCanvasId = activeCanvasId ?: fallbackCanvasId
        }
        render(NavigationDirection.NONE)
    }

    private fun closeAllCanvasTabs() {
        if (methodCanvases.isEmpty()) return
        resetMethodCanvases()
        render(NavigationDirection.NONE)
    }

    private fun selectInstruction(canvasId: String, address: Long) {
        var page = instructionRoute(canvasId) ?: return
        if (page.pendingTargetAddress != null) {
            cancelCanvasOperation(canvasId)
            page = instructionRoute(canvasId) ?: return
        }
        if (page.method.instructions.items.none { it.address == address }) return
        replaceInstructionRoute(canvasId, page.routeId) {
            it.copy(selectedInstructionAddress = address)
        }
        render(NavigationDirection.NONE)
    }

    private fun selectInstructionTarget(action: ManagerAction.InstructionTargetSelected) {
        val page = instructionRoute(action.canvasId) ?: return
        val instruction = page.method.instructions.items.firstOrNull {
            it.address == action.address
        } ?: return
        val target = instruction.target ?: return
        if (instruction.flowKind == InstructionFlowKind.DIRECT_BRANCH &&
            instruction.targetInstructionIndex != null
        ) {
            focusInstruction(
                canvasId = action.canvasId,
                page = page,
                sourceAddress = instruction.address,
                targetAddress = target.address,
                targetInstructionIndex = instruction.targetInstructionIndex,
                firstVisibleItemIndex = action.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = action.firstVisibleItemScrollOffset,
            )
            return
        }
        val classIndex = target.classIndex ?: return
        val methodTarget = target.toMethodTarget() ?: return
        replaceInstructionRoute(action.canvasId, page.routeId) {
            it.copy(selectedInstructionAddress = instruction.address)
        }
        openMethod(classIndex, methodTarget)
    }

    private fun focusInstruction(
        canvasId: String,
        page: MethodInstructionsRoute,
        sourceAddress: Long,
        targetAddress: Long,
        targetInstructionIndex: Int,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ) {
        if (sourceAddress == targetAddress) {
            selectInstruction(canvasId, targetAddress)
            return
        }
        if (!connected()) return
        val analysis = page.method.instructions
        val totalCount = analysis.totalCount
        if (totalCount != null && targetInstructionIndex !in 0 until totalCount) {
            feedback(INSTRUCTION_TARGET_UNAVAILABLE)
            return
        }
        val sourceLocation = InstructionLocation(
            analysis = analysis,
            selectedInstructionAddress = page.selectedInstructionAddress,
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        )
        if (analysis.items.any { it.address == targetAddress }) {
            replaceInstructionRoute(canvasId, page.routeId) {
                it.copy(
                    scrollRequest = nextInstructionAddressScroll(targetAddress),
                    selectedInstructionAddress = targetAddress,
                    instructionHistory = it.instructionHistory + sourceLocation,
                )
            }
            render(NavigationDirection.NONE)
            return
        }
        val token = beginCanvasOperation(canvasId) ?: return
        val current = activeInstructionRoute(token, page.routeId) ?: return
        replaceInstructionRoute(canvasId, current.routeId) {
            it.copy(pendingTargetAddress = sourceAddress)
        }
        render(NavigationDirection.NONE)
        val targetOffset = targetInstructionIndex.pageOffset()
        canvasJobs[canvasId] = viewModelScope.launch {
            try {
                val targetAnalysis = loadMethodAnalysisPage(
                    existing = MethodAnalysisSectionViewData(),
                    offset = targetOffset,
                    limit = IpcContract.MAX_ANALYSIS_PAGE_SIZE,
                    prepend = false,
                    loader = { offset, limit ->
                        client.methodInstructions(
                            current.method.classIndex,
                            current.method.methodIndex,
                            offset,
                            limit,
                        )
                    },
                    transform = { it.viewData() },
                )
                check(targetInstructionIndex in targetAnalysis.loadedIndices &&
                    targetAnalysis.items.any { it.address == targetAddress }
                ) {
                    INVALID_INSTRUCTION_TARGET
                }
                val destination = activeInstructionRoute(token, current.routeId)
                    ?: return@launch
                replaceInstructionRoute(canvasId, destination.routeId) {
                    it.copy(
                        method = it.method.copy(instructions = targetAnalysis),
                        scrollRequest = nextInstructionAddressScroll(targetAddress),
                        selectedInstructionAddress = targetAddress,
                        pendingTargetAddress = null,
                        instructionHistory = it.instructionHistory + sourceLocation,
                    )
                }
                render(NavigationDirection.NONE)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                instructionJumpFailure(token, current.routeId, error)
            } finally {
                finishCanvasOperation(token)
            }
        }
    }

    private fun consumeInstructionScroll(
        canvasId: String,
        requestId: Long,
    ) {
        val page = instructionRoute(canvasId) ?: return
        if (page.scrollRequest?.id != requestId) return
        replaceInstructionRoute(canvasId, page.routeId) { it.copy(scrollRequest = null) }
        render(NavigationDirection.NONE)
    }

    private fun instructionJumpFailure(
        token: CanvasOperationToken,
        routeId: String,
        error: Throwable,
    ) {
        Log.e(LOG_TAG, "Instruction jump failed", error)
        activeInstructionRoute(token, routeId) ?: return
        replaceInstructionRoute(token.canvasId, routeId) {
            it.copy(pendingTargetAddress = null)
        }
        render(NavigationDirection.NONE)
        feedback(error.userMessage())
        if (error.invalidatesSession()) sessionConnected = false
    }

    private fun selectCallGraphNode(canvasId: String, nodeId: String) {
        val canvas = graphCanvas(canvasId) ?: return
        if (canvas.graph.selectedNodeId == nodeId ||
            nodeId in canvas.editor.hiddenNodeIds ||
            canvas.graph.nodes.none { it.id == nodeId }
        ) {
            return
        }
        replaceCanvas(canvas.copy(graph = canvas.graph.copy(selectedNodeId = nodeId)))
        render(NavigationDirection.NONE)
    }

    private fun openCallGraphNodeCanvas(canvasId: String, nodeId: String) {
        val node = graphCanvas(canvasId)
            ?.graph
            ?.nodes
            ?.firstOrNull { it.id == nodeId && !it.isRoot }
            ?: return
        val classIndex = node.classIndex ?: return
        val target = node.toMethodTarget() ?: return
        openMethod(classIndex, target)
    }

    private fun closeCallGraphNode(canvasId: String, nodeId: String) {
        var canvas = graphCanvas(canvasId) ?: return
        var methodNode = canvas.graph.nodes.firstOrNull { it.id == nodeId } ?: return
        if (methodNode.isRoot || nodeId in canvas.editor.hiddenNodeIds) return
        if (methodNode.calls.isLoading || methodNode.callers.isLoading) {
            cancelCanvasOperation(canvasId)
            canvas = graphCanvas(canvasId) ?: return
            methodNode = canvas.graph.nodes.firstOrNull { it.id == nodeId } ?: return
            if (methodNode.isRoot || nodeId in canvas.editor.hiddenNodeIds) return
        }
        val nextHiddenNodeIds = canvas.editor.hiddenNodeIds + nodeId
        val selectionBefore = canvas.graph.selectedNodeId
        val selectionAfter = selectionBefore.takeIf {
            it in canvas.graph.visibleNodeIds() && it !in nextHiddenNodeIds
        } ?: canvas.graph.rootNodeId
        val edit = CallGraphEdit.NodeClosed(
            nodeId = nodeId,
            selectionBefore = selectionBefore,
            selectionAfter = selectionAfter,
        )
        val editor = canvas.editor.commit(edit)
        replaceCanvas(
            canvas.copy(
                graph = canvas.graph.copy(selectedNodeId = selectionAfter),
                editor = editor,
            ),
        )
        render(NavigationDirection.NONE)
    }

    private fun moveCallGraphNode(
        canvasId: String,
        nodeId: String,
        position: CallGraphNodePositionViewData,
    ) {
        if (!position.x.isFinite() || !position.y.isFinite()) return
        val canvas = graphCanvas(canvasId) ?: return
        if (nodeId in canvas.editor.hiddenNodeIds ||
            canvas.graph.nodes.none { it.id == nodeId }
        ) {
            return
        }
        val previousPosition = canvas.editor.nodePositions[nodeId]
        if (previousPosition == position) return
        val edit = CallGraphEdit.NodeMoved(
            nodeId = nodeId,
            positionBefore = previousPosition,
            positionAfter = position,
        )
        replaceCanvas(
            canvas.copy(
                graph = canvas.graph.copy(selectedNodeId = nodeId),
                editor = canvas.editor.commit(edit),
            ),
        )
        render(NavigationDirection.NONE)
    }

    private fun discoverCallGraphLayoutPositions(
        canvasId: String,
        positions: Map<String, CallGraphNodePositionViewData>,
    ) {
        if (positions.isEmpty()) return
        val canvas = graphCanvas(canvasId) ?: return
        val validNodeIds = canvas.graph.nodes.mapTo(mutableSetOf(), CallGraphNodeViewData::id)
        val additions = positions.filter { (nodeId, position) ->
            nodeId in validNodeIds &&
                nodeId !in canvas.layout.automaticNodePositions &&
                position.x.isFinite() &&
                position.y.isFinite()
        }
        if (additions.isEmpty()) return
        replaceCanvas(
            canvas.copy(
                layout = canvas.layout.copy(
                    automaticNodePositions =
                        canvas.layout.automaticNodePositions + additions,
                ),
            ),
        )
        render(NavigationDirection.NONE)
    }

    private fun undoCallGraphEdit(canvasId: String) {
        val canvas = graphCanvas(canvasId) ?: return
        val result = canvas.editor.undo() ?: return
        val preferredSelection = when (val edit = result.edit) {
            is CallGraphEdit.NodeClosed -> edit.selectionBefore
            is CallGraphEdit.NodeRestored -> canvas.graph.selectedNodeId
            is CallGraphEdit.NodeMoved -> canvas.graph.selectedNodeId
        }
        replaceCanvas(
            canvas.copy(
                graph = canvas.graph.selectVisibleNode(
                    preferredNodeId = preferredSelection,
                    hiddenNodeIds = result.editor.hiddenNodeIds,
                ),
                editor = result.editor,
            ),
        )
        render(NavigationDirection.NONE)
    }

    private fun redoCallGraphEdit(canvasId: String) {
        val canvas = graphCanvas(canvasId) ?: return
        val result = canvas.editor.redo() ?: return
        val preferredSelection = when (val edit = result.edit) {
            is CallGraphEdit.NodeClosed -> edit.selectionAfter
            is CallGraphEdit.NodeRestored -> canvas.graph.selectedNodeId
            is CallGraphEdit.NodeMoved -> canvas.graph.selectedNodeId
        }
        replaceCanvas(
            canvas.copy(
                graph = canvas.graph.selectVisibleNode(
                    preferredNodeId = preferredSelection,
                    hiddenNodeIds = result.editor.hiddenNodeIds,
                ),
                editor = result.editor,
            ),
        )
        render(NavigationDirection.NONE)
    }

    private fun CallGraphViewData.selectVisibleNode(
        preferredNodeId: String,
        hiddenNodeIds: Set<String>,
    ): CallGraphViewData {
        val visibleNodeIds = visibleNodeIds() - hiddenNodeIds
        return copy(
            selectedNodeId = preferredNodeId.takeIf { it in visibleNodeIds } ?: rootNodeId,
        )
    }

    private fun toggleCallGraphNode(
        canvasId: String,
        nodeId: String,
        direction: CallGraphDirection,
    ) {
        if (!connected()) return
        var canvas = graphCanvas(canvasId) ?: return
        var node = canvas.graph.nodes.firstOrNull { it.id == nodeId } ?: return
        var expansion = node.expansion(direction)
        if (expansion.isExpanded) {
            if (expansion.isLoading) {
                cancelCanvasOperation(canvasId)
                canvas = graphCanvas(canvasId) ?: return
                node = canvas.graph.nodes.firstOrNull { it.id == nodeId } ?: return
                expansion = node.expansion(direction)
            }
            var graph = canvas.graph.updateNode(nodeId) { current ->
                current.withExpansion(
                    direction,
                    current.expansion(direction).copy(
                        isExpanded = false,
                        isLoading = false,
                    ),
                )
            }
            if (graph.selectedNodeId !in graph.visibleNodeIds()) {
                graph = graph.copy(selectedNodeId = nodeId)
            }
            replaceCanvas(canvas.copy(graph = graph))
            render(NavigationDirection.NONE)
            return
        }
        if (canvasJobs[canvasId]?.isActive == true || canvas.graph.isLoading) {
            feedback(CALL_GRAPH_LOADING_MESSAGE)
            return
        }
        if (!node.canOpen) {
            feedback(CALL_GRAPH_TARGET_UNRESOLVED)
            return
        }
        val graph = canvas.graph.updateNode(nodeId) { current ->
            current.withExpansion(
                direction,
                current.expansion(direction).copy(isExpanded = true),
            )
        }
        replaceCanvas(canvas.copy(graph = graph))
        render(NavigationDirection.NONE)
        if (expansion.totalCount != null && expansion.failureMessage == null) return
        val token = beginCanvasOperation(canvasId) ?: return
        requestCallGraphExpansion(token, nodeId, direction)
    }

    private fun requestCallGraphExpansion(
        token: CanvasOperationToken,
        nodeId: String,
        direction: CallGraphDirection,
    ) {
        val canvas = activeGraphCanvas(token) ?: return
        val node = canvas.graph.nodes.firstOrNull { it.id == nodeId } ?: return
        val expansion = node.expansion(direction)
        val classIndex = node.classIndex ?: return
        val methodIndex = node.methodIndex ?: return
        val loadingGraph = canvas.graph.updateNode(nodeId) { current ->
            current.withExpansion(
                direction,
                expansion.copy(isLoading = true, failureMessage = null),
            )
        }
        replaceCanvas(canvas.copy(graph = loadingGraph))
        render(NavigationDirection.NONE)
        canvasJobs[token.canvasId] = viewModelScope.launch {
            try {
                val result = when (direction) {
                    CallGraphDirection.CALLS -> client.methodCalls(
                        classIndex,
                        methodIndex,
                        expansion.loadedCount,
                        IpcContract.MAX_ANALYSIS_PAGE_SIZE,
                    )
                    CallGraphDirection.CALLERS -> client.methodCallers(
                        classIndex,
                        methodIndex,
                        expansion.loadedCount,
                        IpcContract.MAX_ANALYSIS_PAGE_SIZE,
                    )
                }
                val analyzedCanvas = activeGraphCanvas(token) ?: return@launch
                val analyzedNode = analyzedCanvas.graph.nodes.firstOrNull { it.id == nodeId }
                    ?: return@launch
                val analyzedExpansion = analyzedNode.expansion(direction)
                check(result.page.offset == analyzedExpansion.loadedCount) {
                    INVALID_ANALYSIS_PAGE
                }
                check(result.page.offset + result.page.items.size <= result.page.totalCount) {
                    INVALID_ANALYSIS_PAGE
                }
                analyzedExpansion.totalCount?.let {
                    check(it == result.page.totalCount) { INVALID_ANALYSIS_PAGE }
                }
                analyzedExpansion.status?.let {
                    check(it == result.status) { INVALID_ANALYSIS_PAGE }
                    check(
                        analyzedExpansion.unresolvedIndirectCallCount ==
                            result.unresolvedIndirectCallCount,
                    ) { INVALID_ANALYSIS_PAGE }
                }
                check(
                    result.page.items.isNotEmpty() ||
                        result.page.offset >= result.page.totalCount,
                ) { STALLED_ANALYSIS_PAGE }
                val references = withContext(Dispatchers.Default) {
                    result.page.items.map { it.viewData() }
                }
                val latestCanvas = activeGraphCanvas(token) ?: return@launch
                val latestNode = latestCanvas.graph.nodes.firstOrNull { it.id == nodeId }
                    ?: return@launch
                val latestExpansion = latestNode.expansion(direction)
                if (!latestExpansion.isLoading ||
                    latestExpansion.loadedCount != analyzedExpansion.loadedCount
                ) {
                    return@launch
                }
                val updatedExpansion = latestExpansion.copy(
                    status = result.status,
                    loadedCount = result.page.offset + result.page.items.size,
                    totalCount = result.page.totalCount,
                    unresolvedIndirectCallCount = result.unresolvedIndirectCallCount,
                    isLoading = false,
                    failureMessage = null,
                )
                val graph = latestCanvas.graph
                    .mergeReferences(nodeId, direction, references)
                    .updateNode(nodeId) { current ->
                        current.withExpansion(direction, updatedExpansion)
                    }
                replaceCanvas(latestCanvas.copy(graph = graph))
                render(NavigationDirection.NONE)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                callGraphFailure(token, nodeId, direction, error)
            } finally {
                finishCanvasOperation(token)
            }
        }
    }

    private fun callGraphFailure(
        token: CanvasOperationToken,
        nodeId: String,
        direction: CallGraphDirection,
        error: Throwable,
    ) {
        Log.e(LOG_TAG, "Call graph expansion failed", error)
        val canvas = activeGraphCanvas(token) ?: return
        val graph = canvas.graph.updateNode(nodeId) { node ->
            val failed = node.expansion(direction).copy(
                isExpanded = false,
                isLoading = false,
                failureMessage = error.userMessage(),
            )
            node.withExpansion(direction, failed)
        }
        replaceCanvas(canvas.copy(graph = graph))
        render(NavigationDirection.NONE)
        if (error.invalidatesSession()) sessionConnected = false
    }

    private fun openCallGraphNodeInstructions(canvasId: String, nodeId: String) {
        if (!connected()) return
        val canvas = graphCanvas(canvasId) ?: return
        val node = canvas.graph.nodes.firstOrNull { it.id == nodeId } ?: return
        val preparedMethod = node.methodInstructionsState() ?: run {
            feedback(CALL_GRAPH_TARGET_UNRESOLVED)
            return
        }
        cancelCanvasOperation(canvasId)
        val currentCanvas = graphCanvas(canvasId) ?: return
        val currentAnalysis = preparedMethod.instructions
        val shouldLoad = currentAnalysis.totalCount == null &&
            currentAnalysis.failureMessage == null &&
            !currentAnalysis.isInitialLoading
        val method = if (shouldLoad) {
            preparedMethod.copy(instructions = currentAnalysis.loading(initial = true))
        } else {
            preparedMethod
        }
        val route = MethodInstructionsRoute(
            routeId = nextRoute(canvasId + ":" + INSTRUCTIONS_ROUTE + ":" + node.id),
            nodeId = node.id,
            method = method,
        )
        replaceCanvas(
            currentCanvas.copy(route = MethodCanvasRoute.Instructions(route)),
        )
        render(NavigationDirection.FORWARD)
        if (shouldLoad) {
            val token = beginCanvasOperation(canvasId) ?: return
            requestMethodAnalysisPage(
                token = token,
                routeId = route.routeId,
                offset = currentAnalysis.itemOffset,
            )
        }
    }

    private fun loadMoreInstructions(canvasId: String) {
        if (!connected() || canvasJobs[canvasId]?.isActive == true) return
        val page = instructionRoute(canvasId) ?: return
        val analysis = page.method.instructions
        if (analysis.isInitialLoading || analysis.isLoadingMore || analysis.isLoadingPrevious) return
        if (analysis.totalCount != null && !analysis.hasMore) return
        val token = beginCanvasOperation(canvasId) ?: return
        replaceInstructionRoute(canvasId, page.routeId) {
            it.copy(
                method = it.method.copy(
                    instructions = it.method.instructions.loading(
                        initial = analysis.totalCount == null,
                    ),
                ),
            )
        }
        render(NavigationDirection.NONE)
        requestMethodAnalysisPage(
            token = token,
            routeId = page.routeId,
            offset = analysis.itemOffset + analysis.items.size,
        )
    }

    private fun loadPreviousInstructions(canvasId: String) {
        if (!connected() || canvasJobs[canvasId]?.isActive == true) return
        val page = instructionRoute(canvasId) ?: return
        val analysis = page.method.instructions
        if (analysis.isInitialLoading || analysis.isLoadingMore ||
            analysis.isLoadingPrevious || !analysis.hasPrevious
        ) {
            return
        }
        val limit = minOf(IpcContract.MAX_ANALYSIS_PAGE_SIZE, analysis.itemOffset)
        val offset = analysis.itemOffset - limit
        val token = beginCanvasOperation(canvasId) ?: return
        replaceInstructionRoute(canvasId, page.routeId) {
            it.copy(
                method = it.method.copy(
                    instructions = it.method.instructions.loading(
                        initial = false,
                        previous = true,
                    ),
                ),
            )
        }
        render(NavigationDirection.NONE)
        requestMethodAnalysisPage(
            token = token,
            routeId = page.routeId,
            offset = offset,
            limit = limit,
            prepend = true,
        )
    }

    private fun requestMethodAnalysisPage(
        token: CanvasOperationToken,
        routeId: String,
        offset: Int,
        limit: Int = IpcContract.MAX_ANALYSIS_PAGE_SIZE,
        prepend: Boolean = false,
    ) {
        canvasJobs[token.canvasId] = viewModelScope.launch {
            try {
                val destination = activeInstructionRoute(token, routeId) ?: return@launch
                val method = destination.method
                val analysis = loadMethodAnalysisPage(
                    existing = method.instructions,
                    offset = offset,
                    limit = limit,
                    prepend = prepend,
                    loader = { pageOffset, pageLimit ->
                        client.methodInstructions(
                            method.classIndex,
                            method.methodIndex,
                            pageOffset,
                            pageLimit,
                        )
                    },
                    transform = { it.viewData() },
                )
                activeInstructionRoute(token, routeId) ?: return@launch
                replaceInstructionRoute(token.canvasId, routeId) {
                    it.copy(method = it.method.copy(instructions = analysis))
                }
                render(NavigationDirection.NONE)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                methodAnalysisFailure(token, routeId, error)
            } finally {
                finishCanvasOperation(token)
            }
        }
    }

    private suspend fun <Source, Target> loadMethodAnalysisPage(
        existing: MethodAnalysisSectionViewData<Target>,
        offset: Int,
        limit: Int,
        prepend: Boolean,
        loader: suspend (Int, Int) -> MethodAnalysisResult<Source>,
        transform: (Source) -> Target,
    ): MethodAnalysisSectionViewData<Target> {
        val initial = existing.totalCount == null
        when {
            initial -> check(existing.items.isEmpty()) { INVALID_ANALYSIS_PAGE }
            prepend -> check(offset + limit == existing.itemOffset) { INVALID_ANALYSIS_PAGE }
            else -> check(offset == existing.itemOffset + existing.items.size) {
                INVALID_ANALYSIS_PAGE
            }
        }
        val result = loader(offset, limit)
        val page = result.page
        check(page.offset == offset) { INVALID_ANALYSIS_PAGE }
        check(page.offset + page.items.size <= page.totalCount) { INVALID_ANALYSIS_PAGE }
        if (prepend) {
            check(page.offset + page.items.size == existing.itemOffset) {
                INVALID_ANALYSIS_PAGE
            }
        }
        check(page.items.isNotEmpty() || offset >= page.totalCount) { STALLED_ANALYSIS_PAGE }
        existing.totalCount?.let { check(it == page.totalCount) { INVALID_ANALYSIS_PAGE } }
        existing.status?.let { check(it == result.status) { INVALID_ANALYSIS_PAGE } }
        if (existing.status != null) {
            check(existing.unresolvedIndirectCallCount == result.unresolvedIndirectCallCount) {
                INVALID_ANALYSIS_PAGE
            }
        }
        val items = withContext(Dispatchers.Default) { page.items.map(transform) }
        val mergedItems = when {
            initial -> items
            prepend -> items + existing.items
            else -> existing.items + items
        }
        return existing.copy(
            status = result.status,
            items = mergedItems,
            totalCount = page.totalCount,
            unresolvedIndirectCallCount = result.unresolvedIndirectCallCount,
            isInitialLoading = false,
            isLoadingMore = false,
            isLoadingPrevious = false,
            failureMessage = null,
            itemOffset = if (initial || prepend) page.offset else existing.itemOffset,
        )
    }

    private fun methodAnalysisFailure(
        token: CanvasOperationToken,
        routeId: String,
        error: Throwable,
    ) {
        Log.e(LOG_TAG, "Method instruction analysis failed", error)
        val destination = activeInstructionRoute(token, routeId) ?: return
        val previousLoad = destination.method.instructions.isLoadingPrevious
        replaceInstructionRoute(token.canvasId, routeId) {
            val instructions = if (previousLoad) {
                it.method.instructions.stopped()
            } else {
                it.method.instructions.failed(error.userMessage())
            }
            it.copy(method = it.method.copy(instructions = instructions))
        }
        render(NavigationDirection.NONE)
        if (previousLoad) feedback(error.userMessage())
        if (error.invalidatesSession()) sessionConnected = false
    }

    private fun navigationFailure(generation: Long, error: Throwable, logMessage: String) {
        Log.e(LOG_TAG, logMessage, error)
        if (!current(generation)) return
        render(NavigationDirection.NONE)
        feedback(error.userMessage())
        if (error.invalidatesSession()) sessionConnected = false
    }

    private fun selectBreadcrumb(id: String) {
        if (activeCanvasId != null) return
        val index = browserStack.indexOfFirst { it.routeId == id }
        if (index < 0 || index == browserStack.lastIndex) return
        invalidate()
        browserStack = browserStack.take(index + 1)
        renderAfterNavigation(NavigationDirection.BACKWARD)
    }

    private fun navigateBack() {
        activeCanvasId?.let {
            navigateCanvasBack(it)
            return
        }
        if (browserStack.size <= 1) {
            val root = browserStack.lastOrNull() as? Destination.Assemblies ?: return
            if (root.query.isBlank()) return
            invalidate()
            replaceTop(root.copy(query = "", search = null))
            render(NavigationDirection.NONE)
            return
        }
        invalidate()
        browserStack = browserStack.dropLast(1)
        renderAfterNavigation(NavigationDirection.BACKWARD)
    }

    private fun navigateCanvasBack(canvasId: String) {
        val canvas = methodCanvas(canvasId) ?: return
        when (val route = canvas.route) {
            MethodCanvasRoute.Graph -> {
                if (activeCanvasId != canvasId) return
                lastActiveCanvasId = canvasId
                activeCanvasId = null
                render(NavigationDirection.BACKWARD)
            }
            is MethodCanvasRoute.Instructions -> {
                cancelCanvasOperation(canvasId)
                val currentCanvas = methodCanvas(canvasId) ?: return
                val currentRoute = instructionRoute(canvasId) ?: return
                val location = currentRoute.instructionHistory.lastOrNull()
                if (location == null) {
                    replaceCanvas(currentCanvas.copy(route = MethodCanvasRoute.Graph))
                } else {
                    replaceInstructionRoute(canvasId, currentRoute.routeId) {
                        it.copy(
                            method = it.method.copy(instructions = location.analysis),
                            scrollRequest = nextInstructionViewportScroll(location),
                            selectedInstructionAddress = location.selectedInstructionAddress,
                            pendingTargetAddress = null,
                            instructionHistory = it.instructionHistory.dropLast(1),
                        )
                    }
                }
                render(NavigationDirection.BACKWARD)
            }
        }
    }

    private fun selectTab(tab: ClassTab) {
        if (activeCanvasId != null) return
        val page = currentClass() ?: return
        if (page.tab == tab) return
        replaceTop(page.copy(tab = tab, focusedMemberId = null))
        render(NavigationDirection.NONE)
    }

    private fun copyMethodValue(index: Int, target: MethodCopyTarget) {
        if (activeCanvasId != null) return
        val page = browserStack.lastOrNull() as? Destination.ClassDetails ?: return
        val method = page.members.methods.firstOrNull { it.id == index } ?: return
        copyMethodAddress(target, method.rvaLabel, method.addressLabel)
    }

    private fun copyCallGraphNodeValue(
        canvasId: String,
        nodeId: String,
        target: MethodCopyTarget,
    ) {
        val canvas = graphCanvas(canvasId) ?: return
        val node = canvas.graph.nodes.firstOrNull { it.id == nodeId } ?: return
        copyMethodAddress(target, node.rvaLabel, node.addressLabel)
    }

    private fun copyMethodAddress(
        target: MethodCopyTarget,
        rvaLabel: String?,
        addressLabel: String?,
    ) {
        val value = when (target) {
            MethodCopyTarget.RVA -> rvaLabel
            MethodCopyTarget.VA -> addressLabel
        } ?: return
        val clipLabel = when (target) {
            MethodCopyTarget.RVA -> RVA_CLIP_LABEL
            MethodCopyTarget.VA -> VA_CLIP_LABEL
        }
        val confirmation = when (target) {
            MethodCopyTarget.RVA -> RVA_COPIED
            MethodCopyTarget.VA -> VA_COPIED
        }
        copyToClipboard(clipLabel, value, confirmation)
    }

    private fun copyInstruction(canvasId: String, address: Long) {
        val page = instructionRoute(canvasId) ?: return
        val instruction = page.method.instructions.items.firstOrNull {
            it.address == address
        } ?: return
        val useRva = page.method.rvaLabel != null && instruction.rvaLabel != null
        val addressType = if (useRva) RVA_LABEL else VA_LABEL
        val addressValue = if (useRva) requireNotNull(instruction.rvaLabel) else instruction.addressLabel
        val operation = buildString {
            append(instruction.mnemonic)
            if (instruction.operands.isNotBlank()) {
                append(' ')
                append(instruction.operands)
            }
        }
        val value = addressType + " " + addressValue + "  " +
            instruction.bytesLabel + "  " + operation
        copyToClipboard(INSTRUCTION_CLIP_LABEL, value, INSTRUCTION_COPIED)
    }

    private fun copyToClipboard(label: String, value: String, confirmation: String) {
        try {
            clipboardManager.setPrimaryClip(ClipData.newPlainText(label, value))
            feedback(confirmation)
        } catch (error: RuntimeException) {
            Log.e(LOG_TAG, "Clipboard write failed", error)
            feedback(CLIPBOARD_UNAVAILABLE)
        }
    }

    private fun render(direction: NavigationDirection) {
        val destination = browserStack.lastOrNull() ?: return
        val process = mutableState.value.selectedProcess ?: return
        val activeCanvas = activeCanvasId?.let(::methodCanvas)
        if (activeCanvasId != null && activeCanvas == null) {
            activeCanvasId = null
        }
        mutableState.update { current ->
            current.copy(
                content = activeCanvas?.let { canvas ->
                    ManagerContent.Canvas(
                        canvasId = canvas.id,
                        page = canvas.toPage(),
                    )
                } ?: ManagerContent.Browser(destination.toBrowserPage()),
                workspaceTabs = WorkspaceTabsViewData(
                    selectedCanvasId = activeCanvas?.id,
                    canvases = methodCanvases.map { canvas ->
                        CanvasTabViewData(
                            id = canvas.id,
                            methodName = canvas.method.name,
                            ownerName = canvas.method.ownerName,
                            isBusy = canvas.isBusy,
                        )
                    },
                ),
                browserQuery = destination.searchQuery,
                breadcrumbs = browserStack.mapIndexed { index, item ->
                    BreadcrumbViewData(
                        id = item.routeId,
                        label = if (index == 0) process.appName else item.label,
                    )
                },
                navigationDirection = direction,
            )
        }
    }

    private fun Destination.toBrowserPage(): BrowserPage = when (this) {
        is Destination.Assemblies -> search?.let { state ->
            BrowserPage.SymbolSearch(
                destinationId = routeId,
                results = state.items.map { result ->
                    SymbolSearchViewData(
                        id = symbolEntryId(routeId, result),
                        name = result.name,
                        kind = result.kind,
                        assemblyName = result.assemblyName,
                        ownerName = result.ownerName,
                    )
                },
                search = state.viewData(),
            )
        } ?: BrowserPage.Directory(
            destinationId = routeId,
            level = DirectoryLevel.ASSEMBLIES,
            entries = items.map {
                BrowserEntryViewData(
                    id = entryId(routeId, ASSEMBLY_ENTRY, it.index),
                    label = it.name,
                    kind = BrowserEntryKind.ASSEMBLY,
                )
            },
        )
        is Destination.Assembly -> BrowserPage.Directory(
            destinationId = routeId,
            level = DirectoryLevel.NAMESPACES,
            entries = search?.items?.map { type ->
                BrowserEntryViewData(
                    id = entryId(routeId, CLASS_ENTRY, type.index),
                    label = type.name,
                    kind = BrowserEntryKind.CLASS,
                    secondaryLabel = type.qualifiedName.takeIf { it != type.name } ?: GLOBAL_NAMESPACE,
                )
            } ?: namespaces.map {
                    BrowserEntryViewData(
                        id = entryId(routeId, NAMESPACE_ENTRY, it.index),
                        label = it.name,
                        kind = BrowserEntryKind.NAMESPACE,
                    )
                } + globalClasses.map {
                    BrowserEntryViewData(
                        id = entryId(routeId, CLASS_ENTRY, it.index),
                        label = it.name,
                        kind = BrowserEntryKind.CLASS,
                        secondaryLabel = GLOBAL_NAMESPACE,
                    )
                },
            search = search?.viewData(),
        )
        is Destination.Namespace -> BrowserPage.Directory(
            destinationId = routeId,
            level = DirectoryLevel.CLASSES,
            entries = classes.map {
                BrowserEntryViewData(
                    id = entryId(routeId, CLASS_ENTRY, it.index),
                    label = it.name,
                    kind = BrowserEntryKind.CLASS,
                )
            },
        )
        is Destination.ClassDetails -> BrowserPage.ClassDetails(
            destinationId = routeId,
            classIndex = classIndex,
            selectedTab = tab,
            fields = members.fields,
            methods = members.methods,
            focusedMemberId = focusedMemberId,
        )
    }

    private val Destination.searchQuery: String
        get() = when (this) {
            is Destination.Assemblies -> query
            is Destination.Assembly -> query
            is Destination.Namespace -> query
            is Destination.ClassDetails -> when (tab) {
                ClassTab.FIELDS -> fieldQuery
                ClassTab.METHODS -> methodQuery
            }
        }

    private fun Destination.withSearchQuery(query: String): Destination = when (this) {
        is Destination.Assemblies -> copy(query = query, search = null)
        is Destination.Assembly -> copy(query = query, search = null)
        is Destination.Namespace -> copy(query = query)
        is Destination.ClassDetails -> when (tab) {
            ClassTab.FIELDS -> copy(fieldQuery = query)
            ClassTab.METHODS -> copy(methodQuery = query)
        }
    }

    private fun FieldDescriptor.viewData(): FieldViewData {
        val type = typeName ?: typeIndexLabel(typeIndex)
        return FieldViewData(
            id = index,
            name = name,
            typeLabel = type,
            offsetLabel = fieldOffsetLabel(this),
        )
    }

    private fun MethodDescriptor.viewData(): MethodViewData = MethodViewData(
        id = index,
        name = name,
        signature = signature,
        rvaLabel = rva?.let(::formatAddress),
        addressLabel = address?.let(::formatAddress),
        address = address,
    )

    private fun MethodReferenceDescriptor.viewData(): MethodReferenceViewData =
        MethodReferenceViewData(
            classIndex = classIndex,
            methodIndex = methodIndex,
            name = name,
            ownerName = ownerName,
            signature = signature,
            rvaLabel = rva?.let(::formatAddress),
            addressLabel = formatAddress(address),
            address = address,
        )

    private fun InstructionDescriptor.viewData(): InstructionViewData = InstructionViewData(
        address = address,
        addressLabel = formatAddress(address),
        rvaLabel = rva?.let(::formatAddress),
        bytesLabel = bytes,
        mnemonic = mnemonic,
        operands = operands,
        flowKind = flowKind,
        targetInstructionIndex = targetInstructionIndex,
        target = target?.viewData(),
    )

    private fun MethodCanvasState.toPage(): MethodCanvasPage = when (val current = route) {
        MethodCanvasRoute.Graph -> MethodCanvasPage.Graph(
            destinationId = id + ":graph",
            graph = graph,
            editor = editor.viewData(),
            automaticNodePositions = layout.automaticNodePositions,
        )
        is MethodCanvasRoute.Instructions -> MethodCanvasPage.Instructions(
            destinationId = current.page.routeId,
            addressMode = if (current.page.method.rvaLabel != null) {
                InstructionAddressMode.RVA
            } else {
                InstructionAddressMode.VA
            },
            analysis = current.page.method.instructions,
            scrollRequest = current.page.scrollRequest,
            selectedInstructionAddress = current.page.selectedInstructionAddress,
            pendingTargetAddress = current.page.pendingTargetAddress,
        )
    }

    private val MethodCanvasState.isBusy: Boolean
        get() = graph.isLoading || when (val current = route) {
                MethodCanvasRoute.Graph -> false
                is MethodCanvasRoute.Instructions -> with(current.page) {
                    val analysis = method.instructions
                    analysis.isInitialLoading ||
                        analysis.isLoadingMore ||
                        analysis.isLoadingPrevious ||
                        pendingTargetAddress != null
                }
            }

    private fun <T> MethodAnalysisSectionViewData<T>.loading(
        initial: Boolean,
        previous: Boolean = false,
    ): MethodAnalysisSectionViewData<T> = copy(
        isInitialLoading = initial,
        isLoadingMore = !initial && !previous,
        isLoadingPrevious = previous,
        failureMessage = null,
    )

    private fun <T> MethodAnalysisSectionViewData<T>.failed(
        message: String,
    ): MethodAnalysisSectionViewData<T> = copy(
        isInitialLoading = false,
        isLoadingMore = false,
        isLoadingPrevious = false,
        failureMessage = message,
    )

    private fun <T> MethodAnalysisSectionViewData<T>.stopped(): MethodAnalysisSectionViewData<T> =
        copy(
            isInitialLoading = false,
            isLoadingMore = false,
            isLoadingPrevious = false,
        )

    private fun CallGraphNodeViewData.methodInstructionsState(): MethodInstructionsState? {
        val resolvedClassIndex = classIndex ?: return null
        val resolvedMethodIndex = methodIndex ?: return null
        val resolvedOwnerName = ownerName ?: return null
        return MethodInstructionsState(
            classIndex = resolvedClassIndex,
            methodIndex = resolvedMethodIndex,
            ownerName = resolvedOwnerName,
            name = name,
            signature = signature,
            rvaLabel = rvaLabel,
            addressLabel = addressLabel,
            address = address.takeIf { it > 0 },
        )
    }

    private fun MethodReferenceViewData.toMethodTarget(): MethodTarget? {
        val resolvedMethodIndex = methodIndex ?: return null
        val resolvedName = name ?: return null
        val resolvedOwnerName = ownerName ?: return null
        return MethodTarget(
            ownerName = resolvedOwnerName,
            method = MethodViewData(
                id = resolvedMethodIndex,
                name = resolvedName,
                signature = signature,
                rvaLabel = rvaLabel,
                addressLabel = addressLabel,
                address = address,
            ),
        )
    }

    private fun MethodInstructionsState.initialCallGraph(): CallGraphViewData {
        val root = CallGraphNodeViewData(
            id = callGraphNodeId(address, classIndex, methodIndex),
            classIndex = classIndex,
            methodIndex = methodIndex,
            ownerName = ownerName,
            name = name,
            signature = signature,
            rvaLabel = rvaLabel,
            addressLabel = addressLabel ?: CALL_GRAPH_ADDRESS_UNAVAILABLE,
            address = address ?: 0L,
            isRoot = true,
        )
        return CallGraphViewData(
            rootNodeId = root.id,
            selectedNodeId = root.id,
            nodes = listOf(root),
            edges = emptyList(),
        )
    }

    private fun CallGraphViewData.mergeReferences(
        sourceNodeId: String,
        direction: CallGraphDirection,
        references: List<MethodReferenceViewData>,
    ): CallGraphViewData {
        if (references.isEmpty()) return this
        val nodeMap = nodes.associateByTo(linkedMapOf(), CallGraphNodeViewData::id)
        val edgeMap = edges.associateByTo(linkedMapOf()) { it.fromNodeId to it.toNodeId }
        val mergedEdges = edges.toMutableList()
        var limitReached = nodeLimitReached
        val origin = CallGraphExpansionKey(sourceNodeId, direction)
        references.forEach { reference ->
            val candidate = reference.callGraphNode()
            val existing = nodeMap[candidate.id]
            if (existing == null && nodeMap.size >= MAX_CALL_GRAPH_NODES) {
                limitReached = true
                return@forEach
            }
            if (existing == null) {
                nodeMap[candidate.id] = candidate
            } else {
                nodeMap[candidate.id] = existing.enrichWith(candidate)
            }
            val fromNodeId = if (direction == CallGraphDirection.CALLS) {
                sourceNodeId
            } else {
                candidate.id
            }
            val toNodeId = if (direction == CallGraphDirection.CALLS) {
                candidate.id
            } else {
                sourceNodeId
            }
            val edgeKey = fromNodeId to toNodeId
            val existingEdge = edgeMap[edgeKey]
            if (existingEdge == null) {
                val edge = CallGraphEdgeViewData(
                    fromNodeId = fromNodeId,
                    toNodeId = toNodeId,
                    origins = listOf(origin),
                )
                edgeMap[edgeKey] = edge
                mergedEdges += edge
            } else if (origin !in existingEdge.origins) {
                val updatedEdge = existingEdge.copy(origins = existingEdge.origins + origin)
                edgeMap[edgeKey] = updatedEdge
                val edgeIndex = mergedEdges.indexOfFirst {
                    it.fromNodeId == fromNodeId && it.toNodeId == toNodeId
                }
                mergedEdges[edgeIndex] = updatedEdge
            }
        }
        return copy(
            nodes = nodeMap.values.toList(),
            edges = mergedEdges,
            nodeLimitReached = limitReached,
        )
    }

    private fun MethodReferenceViewData.callGraphNode() = CallGraphNodeViewData(
        id = callGraphNodeId(address, classIndex, methodIndex),
        classIndex = classIndex,
        methodIndex = methodIndex,
        ownerName = ownerName,
        name = name ?: addressLabel,
        signature = signature,
        rvaLabel = rvaLabel,
        addressLabel = addressLabel,
        address = address,
        isRoot = false,
    )

    private fun callGraphNodeId(
        address: Long?,
        classIndex: Int?,
        methodIndex: Int?,
    ): String = when {
        classIndex != null && methodIndex != null ->
            "$CALL_GRAPH_METHOD_KEY$classIndex:$methodIndex"
        address != null && address > 0 ->
            "$CALL_GRAPH_ADDRESS_KEY${address.toString(HEX_RADIX)}"
        else -> "$CALL_GRAPH_METHOD_KEY$classIndex:$methodIndex"
    }

    private fun CallGraphNodeViewData.enrichWith(
        candidate: CallGraphNodeViewData,
    ): CallGraphNodeViewData {
        val useCandidateAddress = address <= 0 && candidate.address > 0
        return copy(
            classIndex = classIndex ?: candidate.classIndex,
            methodIndex = methodIndex ?: candidate.methodIndex,
            ownerName = ownerName ?: candidate.ownerName,
            name = name.takeUnless { it == addressLabel } ?: candidate.name,
            signature = signature ?: candidate.signature,
            rvaLabel = rvaLabel ?: candidate.rvaLabel,
            addressLabel = if (useCandidateAddress) candidate.addressLabel else addressLabel,
            address = if (useCandidateAddress) candidate.address else address,
        )
    }

    private fun CallGraphViewData.updateNode(
        nodeId: String,
        transform: (CallGraphNodeViewData) -> CallGraphNodeViewData,
    ): CallGraphViewData = copy(
        nodes = nodes.map { node -> if (node.id == nodeId) transform(node) else node },
    )

    private fun CallGraphNodeViewData.expansion(
        direction: CallGraphDirection,
    ): CallGraphExpansionViewData = when (direction) {
        CallGraphDirection.CALLS -> calls
        CallGraphDirection.CALLERS -> callers
    }

    private fun CallGraphNodeViewData.withExpansion(
        direction: CallGraphDirection,
        expansion: CallGraphExpansionViewData,
    ): CallGraphNodeViewData = when (direction) {
        CallGraphDirection.CALLS -> copy(calls = expansion)
        CallGraphDirection.CALLERS -> copy(callers = expansion)
    }

    private fun CallGraphNodeViewData.toMethodTarget(): MethodTarget? {
        if (classIndex == null) return null
        val resolvedMethodIndex = methodIndex ?: return null
        val resolvedOwnerName = ownerName ?: return null
        return MethodTarget(
            ownerName = resolvedOwnerName,
            method = MethodViewData(
                id = resolvedMethodIndex,
                name = name,
                signature = signature,
                rvaLabel = rvaLabel,
                addressLabel = addressLabel,
                address = address.takeIf { it > 0 },
            ),
        )
    }

    private fun PagedSearchState<*>.viewData() = PagedSearchViewData(
        spec = SearchSpecViewData(
            query = spec.query,
            scope = spec.scope,
            matchMode = spec.matchMode,
            matchCase = spec.matchCase,
        ),
        totalCount = totalCount,
        isInitialLoading = isInitialLoading,
        isLoadingMore = isLoadingMore,
        failureMessage = failureMessage,
    )

    private fun SymbolSearchDescriptor.searchKey() = SymbolSearchKey(kind, classIndex, memberIndex)

    private fun fieldOffsetLabel(field: FieldDescriptor): String? {
        val offset = field.offset ?: return null
        return when {
            offset == FieldDescriptor.THREAD_STATIC_OFFSET -> THREAD_STATIC_LABEL
            field.flags?.and(FieldDescriptor.STATIC_FLAG) != 0 -> "$STATIC_LABEL +${formatHex(offset)}"
            else -> "+${formatHex(offset)}"
        }
    }

    private fun typeIndexLabel(typeIndex: Int) = "$TYPE_INDEX_LABEL $typeIndex"

    private fun formatHex(value: Long, minimumDigits: Int = 0): String =
        HEX_PREFIX + value.toString(HEX_RADIX).uppercase(Locale.ROOT).padStart(minimumDigits, '0')

    private fun formatAddress(address: Long) = formatHex(address)

    private fun Int.pageOffset(): Int =
        this / IpcContract.MAX_ANALYSIS_PAGE_SIZE * IpcContract.MAX_ANALYSIS_PAGE_SIZE

    private val MethodAnalysisSectionViewData<*>.loadedIndices: IntRange
        get() = itemOffset until itemOffset + items.size

    private fun connected(): Boolean {
        if (hostStarted && sessionConnected) return true
        feedback(SERVICE_INACTIVE)
        return false
    }

    private fun beginOperation(): Long {
        cancelRemoteSearch()
        requestJob?.cancel()
        requestJob = null
        return ++operationGeneration
    }

    private fun cancelPendingNavigation() {
        requestJob?.cancel()
        requestJob = null
        operationGeneration++
    }

    private fun invalidate() {
        cancelRemoteSearch()
        requestJob?.cancel()
        requestJob = null
        operationGeneration++
    }

    private fun resetMethodCanvases() {
        canvasJobs.values.forEach(Job::cancel)
        canvasJobs.clear()
        canvasGenerations.clear()
        methodCanvases = emptyList()
        activeCanvasId = null
        lastActiveCanvasId = null
        sessionGeneration++
    }

    private fun beginCanvasOperation(canvasId: String): CanvasOperationToken? {
        if (methodCanvas(canvasId) == null) return null
        canvasJobs.remove(canvasId)?.cancel()
        val generation = canvasGenerations.getOrDefault(canvasId, 0L) + 1L
        canvasGenerations[canvasId] = generation
        return CanvasOperationToken(
            canvasId = canvasId,
            generation = generation,
            sessionGeneration = sessionGeneration,
        )
    }

    private fun cancelCanvasJob(canvasId: String) {
        canvasJobs.remove(canvasId)?.cancel()
        canvasGenerations[canvasId]?.let { generation ->
            canvasGenerations[canvasId] = generation + 1L
        }
    }

    private fun cancelCanvasOperation(canvasId: String) {
        cancelCanvasJob(canvasId)
        val canvas = methodCanvas(canvasId) ?: return
        val route = when (val current = canvas.route) {
            MethodCanvasRoute.Graph -> current
            is MethodCanvasRoute.Instructions -> MethodCanvasRoute.Instructions(
                current.page.copy(
                    method = current.page.method.copy(
                        instructions = current.page.method.instructions.stopped(),
                    ),
                    pendingTargetAddress = null,
                ),
            )
        }
        replaceCanvas(
            canvas.copy(
                graph = canvas.graph.stopLoading(),
                route = route,
            ),
        )
    }

    private fun finishCanvasOperation(token: CanvasOperationToken) {
        if (isCurrent(token)) canvasJobs.remove(token.canvasId)
    }

    private fun isCurrent(token: CanvasOperationToken): Boolean =
        token.sessionGeneration == sessionGeneration &&
            canvasGenerations[token.canvasId] == token.generation

    private fun methodCanvas(canvasId: String): MethodCanvasState? =
        methodCanvases.firstOrNull { it.id == canvasId }

    private fun graphCanvas(canvasId: String): MethodCanvasState? =
        methodCanvas(canvasId)?.takeIf { it.route == MethodCanvasRoute.Graph }

    private fun instructionRoute(canvasId: String): MethodInstructionsRoute? =
        (methodCanvas(canvasId)?.route as? MethodCanvasRoute.Instructions)?.page

    private fun activeCanvas(token: CanvasOperationToken): MethodCanvasState? {
        if (!isCurrent(token)) return null
        return methodCanvas(token.canvasId)
    }

    private fun activeGraphCanvas(token: CanvasOperationToken): MethodCanvasState? =
        activeCanvas(token)?.takeIf { it.route == MethodCanvasRoute.Graph }

    private fun activeInstructionRoute(
        token: CanvasOperationToken,
        routeId: String,
    ): MethodInstructionsRoute? {
        val canvas = activeCanvas(token) ?: return null
        val route = (canvas.route as? MethodCanvasRoute.Instructions)?.page ?: return null
        return route.takeIf { it.routeId == routeId }
    }

    private fun replaceCanvas(canvas: MethodCanvasState): Boolean {
        val index = methodCanvases.indexOfFirst { it.id == canvas.id }
        if (index < 0) return false
        methodCanvases = methodCanvases.toMutableList().also { it[index] = canvas }
        return true
    }

    private fun replaceInstructionRoute(
        canvasId: String,
        routeId: String,
        transform: (MethodInstructionsRoute) -> MethodInstructionsRoute,
    ): Boolean {
        val canvas = methodCanvas(canvasId) ?: return false
        val current = (canvas.route as? MethodCanvasRoute.Instructions)?.page ?: return false
        if (current.routeId != routeId) return false
        return replaceCanvas(
            canvas.copy(
                route = MethodCanvasRoute.Instructions(transform(current)),
            ),
        )
    }

    private fun CallGraphViewData.stopLoading(): CallGraphViewData = copy(
        nodes = nodes.map { node ->
            node.copy(
                calls = node.calls.stopLoading(),
                callers = node.callers.stopLoading(),
            )
        },
    )

    private fun CallGraphExpansionViewData.stopLoading(): CallGraphExpansionViewData {
        if (!isLoading) return this
        return copy(
            isExpanded = isExpanded && totalCount != null,
            isLoading = false,
        )
    }

    private fun current(generation: Long) = generation == operationGeneration

    private fun currentClass() = browserStack.lastOrNull() as? Destination.ClassDetails

    private fun replaceTop(page: Destination) {
        if (browserStack.isNotEmpty()) browserStack = browserStack.dropLast(1) + page
    }

    private fun nextRoute(prefix: String) = "$prefix:${++routeSequence}"

    private fun nextInstructionAddressScroll(address: Long) =
        InstructionScrollRequest.Address(
            id = ++instructionScrollSequence,
            address = address,
        )

    private fun nextInstructionViewportScroll(location: InstructionLocation) =
        InstructionScrollRequest.Viewport(
            id = ++instructionScrollSequence,
            firstVisibleItemIndex = location.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = location.firstVisibleItemScrollOffset,
        )

    private fun feedback(message: String) {
        mutableState.update {
            it.copy(feedback = FeedbackViewData((++feedbackSequence).toString(), message))
        }
    }

    private suspend fun <T> loadPages(
        loader: suspend (Int, Int) -> PageResult<T>,
    ): List<T> = withContext(Dispatchers.Default) {
        val result = mutableListOf<T>()
        var offset = 0
        while (true) {
            val page = loader(offset, IpcContract.MAX_PAGE_SIZE)
            check(page.offset == offset) { INVALID_PAGE }
            result += page.items
            val next = page.offset + page.items.size
            if (next >= page.totalCount) break
            check(page.items.isNotEmpty()) { STALLED_PAGE }
            check(next > offset) { STALLED_PAGE }
            offset = next
        }
        result
    }

    private fun processId(process: ProcessDescriptor) = "${process.pid}:${process.startTicks}"

    private fun entryId(route: String, kind: String, index: Int) = "$route:$kind:$index"

    private fun symbolEntryId(route: String, symbol: SymbolSearchDescriptor) =
        "$route:$SYMBOL_ENTRY:${symbol.kind.wireValue}:${symbol.classIndex}:${symbol.memberIndex}"

    private fun Throwable.remoteDetail() =
        (this as? RemoteServiceException)?.message?.takeIf { it != userMessage() }

    private fun Throwable.userMessage() = when ((this as? RemoteServiceException)?.errorCode) {
        IpcContract.Error.NOT_IL2CPP -> NOT_IL2CPP
        IpcContract.Error.METADATA_NOT_FOUND -> METADATA_MISSING
        IpcContract.Error.METADATA_UNSUPPORTED -> METADATA_UNSUPPORTED
        IpcContract.Error.PROCESS_NOT_FOUND,
        IpcContract.Error.PROCESS_CHANGED,
        -> PROCESS_GONE
        IpcContract.Error.MEMORY_ACCESS_DENIED -> MEMORY_DENIED
        IpcContract.Error.NATIVE_UNAVAILABLE -> NATIVE_UNAVAILABLE
        IpcContract.Error.NO_TARGET,
        IpcContract.Error.SERVICE_DISCONNECTED,
        -> SERVICE_DISCONNECTED
        IpcContract.Error.TIMEOUT -> REQUEST_TIMEOUT
        else -> message?.takeIf(String::isNotBlank) ?: UNEXPECTED_ERROR
    }

    private fun Throwable.retryable() = when ((this as? RemoteServiceException)?.errorCode) {
        IpcContract.Error.NOT_IL2CPP,
        IpcContract.Error.PROCESS_NOT_FOUND,
        IpcContract.Error.PROCESS_CHANGED,
        -> false
        else -> true
    }

    private fun Throwable.invalidatesSession() = when (
        (this as? RemoteServiceException)?.errorCode
    ) {
        IpcContract.Error.NO_TARGET,
        IpcContract.Error.PROCESS_NOT_FOUND,
        IpcContract.Error.PROCESS_CHANGED,
        IpcContract.Error.SERVICE_DISCONNECTED,
        -> true
        else -> false
    }

    private data class InstalledApp(
        val packageName: String,
        val processName: String,
        val label: String,
    )

    private data class InstalledAppIndex(
        val byPackage: Map<String, InstalledApp>,
        val byProcess: Map<String, InstalledApp>,
    )

    private sealed interface Destination {
        val routeId: String
        val label: String

        data class Assemblies(
            val items: List<AssemblyDescriptor>,
            val query: String = "",
            val search: PagedSearchState<SymbolSearchDescriptor>? = null,
        ) : Destination {
            override val routeId = ROOT_ROUTE
            override val label = ""
        }

        data class Assembly(
            override val routeId: String,
            val assembly: AssemblyDescriptor,
            val namespaces: List<NamespaceDescriptor>,
            val globalClasses: List<ClassDescriptor>,
            val query: String = "",
            val search: PagedSearchState<TypeSearchDescriptor>? = null,
        ) : Destination {
            override val label = assembly.name
        }

        data class Namespace(
            override val routeId: String,
            val namespace: NamespaceDescriptor,
            val classes: List<ClassDescriptor>,
            val query: String = "",
        ) : Destination {
            override val label = namespace.name
        }

        data class ClassDetails(
            override val routeId: String,
            val classIndex: Int,
            override val label: String,
            val members: ClassMembers,
            val tab: ClassTab = ClassTab.FIELDS,
            val fieldQuery: String = "",
            val methodQuery: String = "",
            val focusedMemberId: Int? = null,
        ) : Destination

    }

    private data class ClassMembers(
        val fields: List<FieldViewData>,
        val methods: List<MethodViewData>,
    )

    private data class MethodTarget(
        val ownerName: String,
        val method: MethodViewData,
    )

    private data class MethodKey(
        val classIndex: Int,
        val methodIndex: Int,
    )

    private data class CallGraphEditorState(
        val hiddenNodeIds: Set<String> = emptySet(),
        val nodePositions: Map<String, CallGraphNodePositionViewData> = emptyMap(),
        val undoHistory: List<CallGraphEdit> = emptyList(),
        val redoHistory: List<CallGraphEdit> = emptyList(),
    ) {
        fun commit(edit: CallGraphEdit): CallGraphEditorState = edit
            .applyTo(this)
            .copy(
                undoHistory = (undoHistory + edit).takeLast(MAX_CALL_GRAPH_EDIT_HISTORY),
                redoHistory = emptyList(),
            )

        fun undo(): CallGraphEditResult? {
            val edit = undoHistory.lastOrNull() ?: return null
            val editor = edit.revertIn(this).copy(
                undoHistory = undoHistory.dropLast(1),
                redoHistory = (redoHistory + edit).takeLast(MAX_CALL_GRAPH_EDIT_HISTORY),
            )
            return CallGraphEditResult(editor, edit)
        }

        fun redo(): CallGraphEditResult? {
            val edit = redoHistory.lastOrNull() ?: return null
            val editor = edit.applyTo(this).copy(
                undoHistory = (undoHistory + edit).takeLast(MAX_CALL_GRAPH_EDIT_HISTORY),
                redoHistory = redoHistory.dropLast(1),
            )
            return CallGraphEditResult(editor, edit)
        }

        fun withPosition(
            nodeId: String,
            position: CallGraphNodePositionViewData?,
        ): CallGraphEditorState = copy(
            nodePositions = if (position == null) {
                nodePositions - nodeId
            } else {
                nodePositions + (nodeId to position)
            },
        )

        fun viewData(): CallGraphEditorViewData {
            val restorableNodeIds = mutableSetOf<String>()
            val restorableNodePositions =
                mutableMapOf<String, MutableSet<CallGraphNodePositionViewData>>()
            for (edit in undoHistory + redoHistory) {
                when (edit) {
                    is CallGraphEdit.NodeClosed -> restorableNodeIds += edit.nodeId
                    is CallGraphEdit.NodeRestored -> restorableNodeIds += edit.nodeId
                    is CallGraphEdit.NodeMoved -> {
                        val positions = restorableNodePositions.getOrPut(
                            edit.nodeId,
                            ::mutableSetOf,
                        )
                        edit.positionBefore?.let(positions::add)
                        positions += edit.positionAfter
                    }
                }
            }
            return CallGraphEditorViewData(
                hiddenNodeIds = hiddenNodeIds,
                nodePositions = nodePositions,
                restorableNodeIds = restorableNodeIds.toSet(),
                restorableNodePositions = restorableNodePositions.mapValues { (_, positions) ->
                    positions.toSet()
                },
                canUndo = undoHistory.isNotEmpty(),
                canRedo = redoHistory.isNotEmpty(),
            )
        }
    }

    private sealed interface CallGraphEdit {
        fun applyTo(editor: CallGraphEditorState): CallGraphEditorState
        fun revertIn(editor: CallGraphEditorState): CallGraphEditorState

        data class NodeClosed(
            val nodeId: String,
            val selectionBefore: String,
            val selectionAfter: String,
        ) : CallGraphEdit {
            override fun applyTo(editor: CallGraphEditorState): CallGraphEditorState =
                editor.copy(hiddenNodeIds = editor.hiddenNodeIds + nodeId)

            override fun revertIn(editor: CallGraphEditorState): CallGraphEditorState =
                editor.copy(hiddenNodeIds = editor.hiddenNodeIds - nodeId)
        }

        data class NodeRestored(
            val nodeId: String,
        ) : CallGraphEdit {
            override fun applyTo(editor: CallGraphEditorState): CallGraphEditorState =
                editor.copy(hiddenNodeIds = editor.hiddenNodeIds - nodeId)

            override fun revertIn(editor: CallGraphEditorState): CallGraphEditorState =
                editor.copy(hiddenNodeIds = editor.hiddenNodeIds + nodeId)
        }

        data class NodeMoved(
            val nodeId: String,
            val positionBefore: CallGraphNodePositionViewData?,
            val positionAfter: CallGraphNodePositionViewData,
        ) : CallGraphEdit {
            override fun applyTo(editor: CallGraphEditorState): CallGraphEditorState =
                editor.withPosition(nodeId, positionAfter)

            override fun revertIn(editor: CallGraphEditorState): CallGraphEditorState =
                editor.withPosition(nodeId, positionBefore)
        }
    }

    private data class CallGraphEditResult(
        val editor: CallGraphEditorState,
        val edit: CallGraphEdit,
    )

    private data class CallGraphLayoutState(
        val automaticNodePositions: Map<String, CallGraphNodePositionViewData> = emptyMap(),
    )

    private data class MethodCanvasState(
        val id: String,
        val key: MethodKey,
        val method: MethodInstructionsState,
        val graph: CallGraphViewData,
        val editor: CallGraphEditorState = CallGraphEditorState(),
        val layout: CallGraphLayoutState = CallGraphLayoutState(),
        val route: MethodCanvasRoute = MethodCanvasRoute.Graph,
    )

    private sealed interface MethodCanvasRoute {
        data object Graph : MethodCanvasRoute

        data class Instructions(
            val page: MethodInstructionsRoute,
        ) : MethodCanvasRoute
    }

    private data class MethodInstructionsRoute(
        val routeId: String,
        val nodeId: String,
        val method: MethodInstructionsState,
        val scrollRequest: InstructionScrollRequest? = null,
        val selectedInstructionAddress: Long? = null,
        val pendingTargetAddress: Long? = null,
        val instructionHistory: List<InstructionLocation> = emptyList(),
    )

    private data class CanvasOperationToken(
        val canvasId: String,
        val generation: Long,
        val sessionGeneration: Long,
    )

    private data class MethodInstructionsState(
        val classIndex: Int,
        val methodIndex: Int,
        val ownerName: String,
        val name: String,
        val signature: String?,
        val rvaLabel: String?,
        val addressLabel: String?,
        val address: Long?,
        val instructions: MethodAnalysisSectionViewData<InstructionViewData> =
            MethodAnalysisSectionViewData(),
    )

    private data class InstructionLocation(
        val analysis: MethodAnalysisSectionViewData<InstructionViewData>,
        val selectedInstructionAddress: Long?,
        val firstVisibleItemIndex: Int,
        val firstVisibleItemScrollOffset: Int,
    ) {
        init {
            require(firstVisibleItemIndex >= 0)
            require(firstVisibleItemScrollOffset >= 0)
        }
    }

    private data class SearchSpec(
        val query: String,
        val scope: BrowserSearchScope,
        val matchMode: SearchMatchMode,
        val matchCase: Boolean,
    )

    private data class PagedSearchState<T>(
        val spec: SearchSpec,
        val items: List<T>,
        val totalCount: Int,
        val isInitialLoading: Boolean,
        val isLoadingMore: Boolean,
        val failureMessage: String? = null,
    )

    private data class SymbolSearchKey(
        val kind: SymbolKind,
        val classIndex: Int,
        val memberIndex: Int,
    )

    private companion object {
        const val LOG_TAG = "ManagerViewModel"
        const val ROOT_ROUTE = "root"
        const val ASSEMBLY_ROUTE = "assembly"
        const val NAMESPACE_ROUTE = "namespace"
        const val CLASS_ROUTE = "class"
        const val METHOD_ROUTE = "method"
        const val ASSEMBLY_ENTRY = "assembly"
        const val NAMESPACE_ENTRY = "namespace"
        const val CLASS_ENTRY = "class"
        const val SYMBOL_ENTRY = "symbol"
        const val PROCESS_SEPARATOR = ':'
        const val HEX_PREFIX = "0x"
        const val HEX_RADIX = 16
        const val GLOBAL_NAMESPACE = "Global namespace"
        const val TYPE_INDEX_LABEL = "Type index"
        const val THREAD_STATIC_LABEL = "thread-static"
        const val STATIC_LABEL = "static"
        const val RVA_CLIP_LABEL = "IL2CPP method RVA"
        const val VA_CLIP_LABEL = "IL2CPP method VA"
        const val INSTRUCTION_CLIP_LABEL = "IL2CPP instruction"
        const val RVA_LABEL = "RVA"
        const val VA_LABEL = "VA"
        const val RVA_COPIED = "RVA copied."
        const val VA_COPIED = "VA copied."
        const val INSTRUCTION_COPIED = "Instruction copied."
        const val CLIPBOARD_UNAVAILABLE = "Clipboard is unavailable."
        const val SERVICE_INACTIVE = "Root service is not active."
        const val TARGET_LOADING = "Wait for the current target to finish opening."
        const val PROCESS_GONE = "Process is no longer running."
        const val NOT_IL2CPP = "Not a Unity IL2CPP app."
        const val METADATA_MISSING = "IL2CPP metadata was not found."
        const val METADATA_UNSUPPORTED = "IL2CPP metadata is unsupported."
        const val MEMORY_DENIED = "Memory access was denied."
        const val NATIVE_UNAVAILABLE = "Native engine is unavailable."
        const val SERVICE_DISCONNECTED = "Root service disconnected."
        const val REQUEST_TIMEOUT = "Root service request timed out."
        const val UNEXPECTED_ERROR = "Unexpected root service error."
        const val INVALID_PAGE = "Root service returned an invalid page"
        const val STALLED_PAGE = "Root service page did not advance"
        const val INVALID_ANALYSIS_PAGE = "Root service returned an invalid method analysis page"
        const val STALLED_ANALYSIS_PAGE = "Method analysis page did not advance"
        const val INVALID_INSTRUCTION_TARGET = "Instruction target is outside the method analysis"
        const val INSTRUCTION_TARGET_UNAVAILABLE = "Instruction target is unavailable."
        const val CALL_GRAPH_TARGET_UNRESOLVED = "This native target has no metadata method."
        const val CALL_GRAPH_LOADING_MESSAGE = "Wait for the current graph expansion."
        const val CALL_GRAPH_ADDRESS_UNAVAILABLE = "Address unavailable"
        const val CALL_GRAPH_ADDRESS_KEY = "address:"
        const val CALL_GRAPH_METHOD_KEY = "method:"
        const val INSTRUCTIONS_ROUTE = "instructions"
        const val SEARCH_DEBOUNCE_MILLIS = 225L
        const val MAX_CALL_GRAPH_NODES = 64
        const val MAX_CALL_GRAPH_EDIT_HISTORY = 64
    }
}
