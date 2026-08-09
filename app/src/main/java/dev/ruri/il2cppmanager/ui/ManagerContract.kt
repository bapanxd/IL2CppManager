package dev.ruri.il2cppmanager.ui

import androidx.compose.runtime.Immutable
import dev.ruri.il2cppmanager.domain.MethodAnalysisStatus
import dev.ruri.il2cppmanager.domain.InstructionFlowKind
import dev.ruri.il2cppmanager.domain.SearchMatchMode
import dev.ruri.il2cppmanager.domain.SymbolKind

@Immutable
data class ManagerUiState(
    val selectedProcess: ProcessViewData? = null,
    val processPicker: ProcessPickerViewState = ProcessPickerViewState(),
    val content: ManagerContent = ManagerContent.NoProcess,
    val workspaceTabs: WorkspaceTabsViewData = WorkspaceTabsViewData(),
    val browserQuery: String = "",
    val browserSearchOptions: BrowserSearchOptions = BrowserSearchOptions(),
    val breadcrumbs: List<BreadcrumbViewData> = emptyList(),
    val navigationDirection: NavigationDirection = NavigationDirection.NONE,
    val feedback: FeedbackViewData? = null,
) {
    val canNavigateBack: Boolean
        get() = workspaceTabs.selectedCanvasId != null ||
            breadcrumbs.size > 1 ||
            browserQuery.isNotBlank()
}

@Immutable
data class WorkspaceTabsViewData(
    val selectedCanvasId: String? = null,
    val canvases: List<CanvasTabViewData> = emptyList(),
)

@Immutable
data class CanvasTabViewData(
    val id: String,
    val methodName: String,
    val ownerName: String,
    val isBusy: Boolean = false,
)

@Immutable
data class ProcessPickerViewState(
    val isVisible: Boolean = false,
    val query: String = "",
    val processes: List<ProcessViewData> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
)

@Immutable
data class ProcessViewData(
    val id: String,
    val pid: Int,
    val appName: String,
    val packageName: String,
    val processName: String,
)

@Immutable
data class BreadcrumbViewData(
    val id: String,
    val label: String,
)

@Immutable
sealed interface ManagerContent {
    @Immutable
    data object NoProcess : ManagerContent

    @Immutable
    data object Parsing : ManagerContent

    @Immutable
    data class Failure(
        val message: String,
        val detail: String? = null,
        val canRetry: Boolean = false,
    ) : ManagerContent

    @Immutable
    data class Browser(val page: BrowserPage) : ManagerContent

    @Immutable
    data class Canvas(
        val canvasId: String,
        val page: MethodCanvasPage,
    ) : ManagerContent
}

@Immutable
sealed interface BrowserPage {
    val destinationId: String

    @Immutable
    data class Directory(
        override val destinationId: String,
        val level: DirectoryLevel,
        val entries: List<BrowserEntryViewData>,
        val search: PagedSearchViewData? = null,
    ) : BrowserPage

    @Immutable
    data class SymbolSearch(
        override val destinationId: String,
        val results: List<SymbolSearchViewData>,
        val search: PagedSearchViewData,
    ) : BrowserPage

    @Immutable
    data class ClassDetails(
        override val destinationId: String,
        val classIndex: Int,
        val selectedTab: ClassTab = ClassTab.FIELDS,
        val fields: List<FieldViewData> = emptyList(),
        val methods: List<MethodViewData> = emptyList(),
        val focusedMemberId: Int? = null,
    ) : BrowserPage
}

@Immutable
sealed interface MethodCanvasPage {
    val destinationId: String

    @Immutable
    data class Instructions(
        override val destinationId: String,
        val addressMode: InstructionAddressMode,
        val analysis: MethodAnalysisSectionViewData<InstructionViewData>,
        val scrollRequest: InstructionScrollRequest? = null,
        val selectedInstructionAddress: Long? = null,
        val pendingTargetAddress: Long? = null,
    ) : MethodCanvasPage

    @Immutable
    data class Graph(
        override val destinationId: String,
        val graph: CallGraphViewData,
        val editor: CallGraphEditorViewData = CallGraphEditorViewData(),
        val automaticNodePositions: Map<String, CallGraphNodePositionViewData> = emptyMap(),
    ) : MethodCanvasPage
}

@Immutable
data class CallGraphEditorViewData(
    val hiddenNodeIds: Set<String> = emptySet(),
    val nodePositions: Map<String, CallGraphNodePositionViewData> = emptyMap(),
    val restorableNodeIds: Set<String> = emptySet(),
    val restorableNodePositions: Map<String, Set<CallGraphNodePositionViewData>> = emptyMap(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

@Immutable
data class CallGraphNodePositionViewData(
    val x: Float,
    val y: Float,
)

sealed interface InstructionScrollRequest {
    val id: Long

    @Immutable
    data class Address(
        override val id: Long,
        val address: Long,
    ) : InstructionScrollRequest

    @Immutable
    data class Viewport(
        override val id: Long,
        val firstVisibleItemIndex: Int,
        val firstVisibleItemScrollOffset: Int,
    ) : InstructionScrollRequest {
        init {
            require(firstVisibleItemIndex >= 0)
            require(firstVisibleItemScrollOffset >= 0)
        }
    }
}

enum class DirectoryLevel {
    ASSEMBLIES,
    NAMESPACES,
    CLASSES,
}

@Immutable
data class BrowserSearchOptions(
    val scope: BrowserSearchScope = BrowserSearchScope.CURRENT_LEVEL,
    val matchMode: SearchMatchMode = SearchMatchMode.CONTAINS,
    val matchCase: Boolean = false,
)

enum class BrowserSearchScope {
    CURRENT_LEVEL,
    EVERYWHERE,
}

@Immutable
data class SearchSpecViewData(
    val query: String,
    val scope: BrowserSearchScope,
    val matchMode: SearchMatchMode,
    val matchCase: Boolean,
)

@Immutable
data class PagedSearchViewData(
    val spec: SearchSpecViewData,
    val totalCount: Int,
    val isInitialLoading: Boolean,
    val isLoadingMore: Boolean,
    val failureMessage: String? = null,
)

@Immutable
data class SymbolSearchViewData(
    val id: String,
    val name: String,
    val kind: SymbolKind,
    val assemblyName: String,
    val ownerName: String,
)

@Immutable
data class BrowserEntryViewData(
    val id: String,
    val label: String,
    val kind: BrowserEntryKind,
    val secondaryLabel: String? = null,
)

enum class BrowserEntryKind {
    ASSEMBLY,
    NAMESPACE,
    CLASS,
}

enum class ClassTab {
    FIELDS,
    METHODS,
}

enum class MethodCopyTarget {
    RVA,
    VA,
}

enum class CallGraphDirection {
    CALLS,
    CALLERS,
}

enum class InstructionAddressMode {
    RVA,
    VA,
}

@Immutable
data class FieldViewData(
    val id: Int,
    val name: String,
    val typeLabel: String,
    val offsetLabel: String?,
)

@Immutable
data class MethodViewData(
    val id: Int,
    val name: String,
    val signature: String?,
    val rvaLabel: String?,
    val addressLabel: String?,
    val address: Long?,
)

@Immutable
data class MethodAnalysisSectionViewData<out T>(
    val status: MethodAnalysisStatus? = null,
    val items: List<T> = emptyList(),
    val totalCount: Int? = null,
    val unresolvedIndirectCallCount: Int = 0,
    val isInitialLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isLoadingPrevious: Boolean = false,
    val failureMessage: String? = null,
    val itemOffset: Int = 0,
) {
    val hasPrevious: Boolean
        get() = itemOffset > 0

    val hasMore: Boolean
        get() = totalCount?.let { itemOffset + items.size < it } == true
}

@Immutable
data class MethodReferenceViewData(
    val classIndex: Int?,
    val methodIndex: Int?,
    val name: String?,
    val ownerName: String?,
    val signature: String?,
    val rvaLabel: String?,
    val addressLabel: String,
    val address: Long,
) {
    val canOpen: Boolean
        get() = classIndex != null && methodIndex != null
}

@Immutable
data class CallGraphViewData(
    val rootNodeId: String,
    val selectedNodeId: String,
    val nodes: List<CallGraphNodeViewData>,
    val edges: List<CallGraphEdgeViewData>,
    val nodeLimitReached: Boolean = false,
) {
    val selectedNode: CallGraphNodeViewData?
        get() = nodes.firstOrNull { it.id == selectedNodeId }

    val rootNode: CallGraphNodeViewData?
        get() = nodes.firstOrNull { it.id == rootNodeId }

    val isLoading: Boolean
        get() = nodes.any { it.calls.isLoading || it.callers.isLoading }

    fun visibleNodeIds(): Set<String> {
        val nodesById = nodes.associateBy(CallGraphNodeViewData::id)
        if (rootNodeId !in nodesById) return emptySet()
        val visible = linkedSetOf(rootNodeId)
        val pending = mutableListOf(rootNodeId)
        var index = 0
        while (index < pending.size) {
            val nodeId = pending[index++]
            val node = nodesById.getValue(nodeId)
            for (edge in edges) {
                for (origin in edge.origins) {
                    if (origin.nodeId != nodeId || !node.expansion(origin.direction).isExpanded) {
                        continue
                    }
                    val adjacentNodeId = when (origin.direction) {
                        CallGraphDirection.CALLS -> edge.toNodeId
                        CallGraphDirection.CALLERS -> edge.fromNodeId
                    }
                    if (adjacentNodeId in nodesById && visible.add(adjacentNodeId)) {
                        pending += adjacentNodeId
                    }
                }
            }
        }
        return visible
    }

    fun visibleGraph(): CallGraphViewData {
        val visibleNodeIds = visibleNodeIds()
        val nodesById = nodes.associateBy(CallGraphNodeViewData::id)
        return copy(
            selectedNodeId = selectedNodeId.takeIf { it in visibleNodeIds } ?: rootNodeId,
            nodes = nodes.filter { it.id in visibleNodeIds },
            edges = edges.filter { edge ->
                edge.fromNodeId in visibleNodeIds &&
                    edge.toNodeId in visibleNodeIds &&
                    edge.origins.any { origin ->
                        origin.nodeId in visibleNodeIds &&
                            nodesById[origin.nodeId]
                                ?.expansion(origin.direction)
                                ?.isExpanded == true
                    }
            },
        )
    }
}

private fun CallGraphNodeViewData.expansion(
    direction: CallGraphDirection,
): CallGraphExpansionViewData = when (direction) {
    CallGraphDirection.CALLS -> calls
    CallGraphDirection.CALLERS -> callers
}

@Immutable
data class CallGraphNodeViewData(
    val id: String,
    val classIndex: Int?,
    val methodIndex: Int?,
    val ownerName: String?,
    val name: String,
    val signature: String?,
    val rvaLabel: String?,
    val addressLabel: String,
    val address: Long,
    val isRoot: Boolean,
    val calls: CallGraphExpansionViewData = CallGraphExpansionViewData(),
    val callers: CallGraphExpansionViewData = CallGraphExpansionViewData(),
) {
    val canOpen: Boolean
        get() = classIndex != null && methodIndex != null
}

@Immutable
data class CallGraphEdgeViewData(
    val fromNodeId: String,
    val toNodeId: String,
    val origins: List<CallGraphExpansionKey>,
)

@Immutable
data class CallGraphExpansionKey(
    val nodeId: String,
    val direction: CallGraphDirection,
)

@Immutable
data class CallGraphExpansionViewData(
    val status: MethodAnalysisStatus? = null,
    val loadedCount: Int = 0,
    val totalCount: Int? = null,
    val unresolvedIndirectCallCount: Int = 0,
    val isExpanded: Boolean = false,
    val isLoading: Boolean = false,
    val failureMessage: String? = null,
) {
    val hasMore: Boolean
        get() = totalCount?.let { loadedCount < it } == true
}

@Immutable
data class InstructionViewData(
    val address: Long,
    val addressLabel: String,
    val rvaLabel: String?,
    val bytesLabel: String,
    val mnemonic: String,
    val operands: String,
    val flowKind: InstructionFlowKind,
    val targetInstructionIndex: Int?,
    val target: MethodReferenceViewData?,
)

@Immutable
data class FeedbackViewData(
    val id: String,
    val message: String,
)

enum class NavigationDirection {
    NONE,
    FORWARD,
    BACKWARD,
}

sealed interface ManagerAction {
    data object ToggleProcessPicker : ManagerAction
    data object DismissProcessPicker : ManagerAction
    data class ProcessQueryChanged(val query: String) : ManagerAction
    data class BrowserQueryChanged(val query: String) : ManagerAction
    data class BrowserSearchScopeChanged(val scope: BrowserSearchScope) : ManagerAction
    data class BrowserMatchModeChanged(val mode: SearchMatchMode) : ManagerAction
    data class BrowserMatchCaseChanged(val matchCase: Boolean) : ManagerAction
    data class ProcessSelected(val processId: String) : ManagerAction
    data class BreadcrumbSelected(val breadcrumbId: String) : ManagerAction
    data object NavigateBack : ManagerAction
    data class BrowserEntrySelected(val entryId: String) : ManagerAction
    data object LoadMoreSearch : ManagerAction
    data class ClassTabSelected(val tab: ClassTab) : ManagerAction
    data class MethodSelected(
        val classIndex: Int,
        val methodIndex: Int,
    ) : ManagerAction
    data object ToggleWorkspace : ManagerAction
    data class CanvasTabSelected(val canvasId: String) : ManagerAction
    data class CanvasTabClosed(val canvasId: String) : ManagerAction
    data object CloseAllCanvasTabs : ManagerAction
    data class MethodCanvasBack(val canvasId: String) : ManagerAction
    data class CallGraphNodeSelected(
        val canvasId: String,
        val nodeId: String,
    ) : ManagerAction
    data class CallGraphNodeToggled(
        val canvasId: String,
        val nodeId: String,
        val direction: CallGraphDirection,
    ) : ManagerAction
    data class CallGraphNodeClosed(
        val canvasId: String,
        val nodeId: String,
    ) : ManagerAction
    data class CallGraphNodeMoved(
        val canvasId: String,
        val nodeId: String,
        val position: CallGraphNodePositionViewData,
    ) : ManagerAction
    data class CallGraphLayoutPositionsDiscovered(
        val canvasId: String,
        val positions: Map<String, CallGraphNodePositionViewData>,
    ) : ManagerAction
    data class CallGraphUndo(val canvasId: String) : ManagerAction
    data class CallGraphRedo(val canvasId: String) : ManagerAction
    data class CallGraphNodeInstructionsSelected(
        val canvasId: String,
        val nodeId: String,
    ) : ManagerAction
    data class CallGraphNodeCanvasSelected(
        val canvasId: String,
        val nodeId: String,
    ) : ManagerAction
    data class LoadMoreInstructions(val canvasId: String) : ManagerAction
    data class LoadPreviousInstructions(val canvasId: String) : ManagerAction
    data class InstructionSelected(
        val canvasId: String,
        val address: Long,
    ) : ManagerAction
    data class InstructionTargetSelected(
        val canvasId: String,
        val address: Long,
        val firstVisibleItemIndex: Int,
        val firstVisibleItemScrollOffset: Int,
    ) : ManagerAction
    data class InstructionScrollConsumed(
        val canvasId: String,
        val requestId: Long,
    ) : ManagerAction
    data class CopyInstruction(
        val canvasId: String,
        val address: Long,
    ) : ManagerAction
    data class CopyMethodValue(
        val methodIndex: Int,
        val target: MethodCopyTarget,
    ) : ManagerAction
    data class CopyCallGraphNodeValue(
        val canvasId: String,
        val nodeId: String,
        val target: MethodCopyTarget,
    ) : ManagerAction
    data object DismissFeedback : ManagerAction
    data object Retry : ManagerAction
}
