package dev.ruri.il2cppmanager.ui

import android.graphics.Paint

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.ruri.il2cppmanager.domain.MethodAnalysisStatus
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun CallGraphPage(
    page: MethodCanvasPage.Graph,
    onNodeSelected: (String) -> Unit,
    onNodeToggled: (String, CallGraphDirection) -> Unit,
    onNodeClosed: (String) -> Unit,
    onNodeMoved: (String, CallGraphNodePositionViewData) -> Unit,
    onLayoutPositionsDiscovered: (Map<String, CallGraphNodePositionViewData>) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onNodeInstructionsSelected: (String) -> Unit,
    onNodeCanvasSelected: (String) -> Unit,
    onCopyNodeValue: (String, MethodCopyTarget) -> Unit,
    showWorkspaceSwitch: Boolean,
    onWorkspaceSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val structuralGraph = remember(
        page.graph.rootNodeId,
        page.graph.nodes,
        page.graph.edges,
        page.graph.nodeLimitReached,
    ) {
        page.graph
            .copy(selectedNodeId = page.graph.rootNodeId)
            .visibleGraph()
    }
    val expandedGraph = structuralGraph.copy(
        selectedNodeId = page.graph.selectedNodeId
            .takeIf { selectedNodeId -> structuralGraph.nodes.any { it.id == selectedNodeId } }
            ?: structuralGraph.rootNodeId,
    )
    val graph = remember(expandedGraph, page.editor.hiddenNodeIds) {
        expandedGraph.withHiddenNodes(page.editor.hiddenNodeIds)
    }
    val knownMethodNodeIds = remember(page.graph.nodes) {
        page.graph.nodes.mapTo(mutableSetOf(), CallGraphNodeViewData::id)
    }
    val expandedMethodNodeIds = remember(expandedGraph.nodes) {
        expandedGraph.nodes.mapTo(mutableSetOf(), CallGraphNodeViewData::id)
    }
    val reservedOffscreenMethodNodeIds = remember(
        knownMethodNodeIds,
        expandedMethodNodeIds,
        page.editor.restorableNodeIds,
    ) {
        (knownMethodNodeIds - expandedMethodNodeIds) +
            (page.editor.restorableNodeIds intersect knownMethodNodeIds)
    }
    val colors = callGraphColors()
    Column(modifier = modifier.fillMaxSize()) {
        CallGraphViewport(
            page = page.copy(graph = graph),
            layoutGraph = expandedGraph,
            knownMethodNodeIds = knownMethodNodeIds,
            reservedOffscreenMethodNodeIds = reservedOffscreenMethodNodeIds,
            onNodeSelected = onNodeSelected,
            onNodeClosed = onNodeClosed,
            onNodeMoved = onNodeMoved,
            onLayoutPositionsDiscovered = onLayoutPositionsDiscovered,
            onUndo = onUndo,
            onRedo = onRedo,
            showWorkspaceSwitch = showWorkspaceSwitch,
            onWorkspaceSwitch = onWorkspaceSwitch,
            colors = colors,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        graph.selectedNode?.let { node ->
            SelectedCallGraphNodePanel(
                node = node,
                graphBusy = graph.isLoading,
                onNodeToggled = onNodeToggled,
                onNodeInstructionsSelected = onNodeInstructionsSelected,
                onNodeCanvasSelected = onNodeCanvasSelected,
                onCopyNodeValue = onCopyNodeValue,
            )
        }
    }
}

private fun CallGraphViewData.withHiddenNodes(hiddenNodeIds: Set<String>): CallGraphViewData {
    val removableNodeIds = hiddenNodeIds - rootNodeId
    if (removableNodeIds.isEmpty()) return this
    val retainedNodeIds = nodes
        .asSequence()
        .map(CallGraphNodeViewData::id)
        .filterNot(removableNodeIds::contains)
        .toSet()
    return copy(
        selectedNodeId = selectedNodeId.takeIf { it in retainedNodeIds } ?: rootNodeId,
        nodes = nodes.filter { it.id in retainedNodeIds },
        edges = edges.filter {
            it.fromNodeId in retainedNodeIds && it.toNodeId in retainedNodeIds
        },
    )
}

@Composable
private fun CallGraphViewport(
    page: MethodCanvasPage.Graph,
    layoutGraph: CallGraphViewData,
    knownMethodNodeIds: Set<String>,
    reservedOffscreenMethodNodeIds: Set<String>,
    onNodeSelected: (String) -> Unit,
    onNodeClosed: (String) -> Unit,
    onNodeMoved: (String, CallGraphNodePositionViewData) -> Unit,
    onLayoutPositionsDiscovered: (Map<String, CallGraphNodePositionViewData>) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    showWorkspaceSwitch: Boolean,
    onWorkspaceSwitch: () -> Unit,
    colors: CallGraphColors,
    modifier: Modifier,
) {
    val graph = page.graph
    val density = LocalDensity.current
    val nodeHeight = CallGraphDimens.NodeHeight * max(1f, density.fontScale)
    val nodeWidthPixels = with(density) { CallGraphDimens.NodeWidth.toPx() }
    val nodeHeightPixels = with(density) { nodeHeight.toPx() }
    val horizontalGapPixels = with(density) { CallGraphDimens.HorizontalGap.toPx() }
    val verticalGapPixels = with(density) { CallGraphDimens.VerticalGap.toPx() }
    val graphPaddingPixels = with(density) { CallGraphDimens.GraphPadding.toPx() }
    val arrowSizePixels = with(density) { CallGraphDimens.ArrowSize.toPx() }
    val curvePixels = with(density) { CallGraphDimens.MinimumCurve.toPx() }
    val loopPixels = with(density) { CallGraphDimens.LoopWidth.toPx() }
    val edgeWidthPixels = with(density) { CallGraphDimens.EdgeWidth.toPx() }
    val selectedEdgeWidthPixels = with(density) { CallGraphDimens.SelectedEdgeWidth.toPx() }
    val gridSpacingPixels = with(density) { CallGraphDimens.GridSpacing.toPx() }
    val minimumGridSpacingPixels = with(density) { CallGraphDimens.MinimumGridSpacing.toPx() }
    val gridDotRadiusPixels = with(density) { CallGraphDimens.GridDotRadius.toPx() }
    val accessibilityMovePixels = with(density) {
        CallGraphDimens.AccessibilityMoveStep.toPx()
    }
    val densityScale = density.density
    var activeDrag by remember(page.destinationId) {
        mutableStateOf<CallGraphDragSession?>(null)
    }
    val draggedNodeId by remember(page.destinationId) {
        derivedStateOf { activeDrag?.nodeId }
    }
    val dragInProgress by remember(page.destinationId) {
        derivedStateOf { activeDrag?.isDragging == true }
    }
    val hasActiveDrag by remember(page.destinationId) {
        derivedStateOf { activeDrag != null }
    }
    val pendingDrag by remember(page.destinationId) {
        derivedStateOf { activeDrag?.takeUnless(CallGraphDragSession::isDragging) }
    }
    val calculatedLayout = remember(
        layoutGraph.rootNodeId,
        layoutGraph.nodes,
        layoutGraph.edges,
        nodeWidthPixels,
        nodeHeightPixels,
        horizontalGapPixels,
        verticalGapPixels,
        graphPaddingPixels,
    ) {
        calculateCallGraphLayout(
            graph = layoutGraph,
            nodeWidth = nodeWidthPixels,
            nodeHeight = nodeHeightPixels,
            horizontalGap = horizontalGapPixels,
            verticalGap = verticalGapPixels,
            padding = graphPaddingPixels,
        )
    }
    val automaticPositions = remember(page.automaticNodePositions, densityScale) {
        page.automaticNodePositions.mapValues { (_, position) ->
            position.toGraphOffset(densityScale)
        }
    }
    val committedPositions = remember(page.editor.nodePositions, densityScale) {
        page.editor.nodePositions.mapValues { (_, position) ->
            position.toGraphOffset(densityScale)
        }
    }
    val restorablePositions = remember(
        page.editor.restorableNodePositions,
        densityScale,
    ) {
        page.editor.restorableNodePositions.mapValues { (_, positions) ->
            positions.mapTo(mutableSetOf()) { position ->
                position.toGraphOffset(densityScale)
            }
        }
    }
    val reservedSlots = remember(
        knownMethodNodeIds,
        reservedOffscreenMethodNodeIds,
        automaticPositions,
        committedPositions,
        restorablePositions,
        nodeWidthPixels,
        nodeHeightPixels,
    ) {
        val methodNodeIds = reservedOffscreenMethodNodeIds +
            (restorablePositions.keys intersect knownMethodNodeIds)
        buildList {
            methodNodeIds.forEach { nodeId ->
                nodeReservationPositions(
                    nodeId = nodeId,
                    automaticPositions = automaticPositions,
                    committedPositions = committedPositions,
                    restorablePositions = restorablePositions,
                ).forEach { position ->
                    add(nodeRect(position, nodeWidthPixels, nodeHeightPixels))
                }
            }
        }
    }
    val baseLayout = remember(
        calculatedLayout,
        layoutGraph.rootNodeId,
        layoutGraph.nodes,
        layoutGraph.edges,
        automaticPositions,
        committedPositions,
        reservedSlots,
        nodeWidthPixels,
        nodeHeightPixels,
        horizontalGapPixels,
        verticalGapPixels,
        graphPaddingPixels,
    ) {
        calculatedLayout.stabilized(
            graph = layoutGraph,
            automaticPositions = automaticPositions,
            positionOverrides = committedPositions,
            reservedSlots = reservedSlots,
            nodeWidth = nodeWidthPixels,
            nodeHeight = nodeHeightPixels,
            horizontalGap = horizontalGapPixels,
            verticalGap = verticalGapPixels,
            padding = graphPaddingPixels,
        )
    }
    val layout = remember(
        baseLayout,
        graph.rootNodeId,
        graph.nodes,
        graph.edges,
        committedPositions,
        nodeWidthPixels,
        nodeHeightPixels,
        graphPaddingPixels,
    ) {
        baseLayout.displayLayout(
            graph = graph,
            positionOverrides = committedPositions,
            nodeWidth = nodeWidthPixels,
            nodeHeight = nodeHeightPixels,
            padding = graphPaddingPixels,
        )
    }
    val discoveredPositions = remember(
        layout.positions,
        page.automaticNodePositions,
        page.editor.nodePositions,
        densityScale,
    ) {
        layout.positions
            .asSequence()
            .filter { (nodeId, position) ->
                nodeId !in page.automaticNodePositions &&
                    nodeId !in page.editor.nodePositions &&
                    position.x.isFinite() &&
                    position.y.isFinite()
            }
            .associate { (nodeId, position) ->
                nodeId to position.toViewPosition(densityScale)
            }
    }
    LaunchedEffect(discoveredPositions) {
        if (discoveredPositions.isNotEmpty()) {
            onLayoutPositionsDiscovered(discoveredPositions)
        }
    }
    val nodeTones = remember(graph.rootNodeId, graph.nodes, graph.edges) {
        graph.nodeTones()
    }
    val edgePath = remember { Path() }
    val gridRenderer = remember { CallGraphGridRenderer() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by rememberSaveable(page.destinationId) {
        mutableFloatStateOf(CallGraphDefaults.InitialScale)
    }
    var translationX by rememberSaveable(page.destinationId) { mutableFloatStateOf(0f) }
    var translationY by rememberSaveable(page.destinationId) { mutableFloatStateOf(0f) }
    var hasFitted by rememberSaveable(page.destinationId) { mutableStateOf(false) }

    fun fitGraph() {
        if (viewportSize.width <= 0 || viewportSize.height <= 0 || layout.nodes.isEmpty()) return
        val transform = fitTransform(viewportSize, layout.bounds, graphPaddingPixels)
        scale = transform.scale
        translationX = transform.translation.x
        translationY = transform.translation.y
        hasFitted = true
    }

    LaunchedEffect(
        viewportSize,
        layout.nodes.isEmpty(),
        page.destinationId,
    ) {
        if (viewportSize.width <= 0 || viewportSize.height <= 0 || layout.nodes.isEmpty()) {
            return@LaunchedEffect
        }
        if (!hasFitted) fitGraph()
    }

    LaunchedEffect(pendingDrag, committedPositions) {
        val drag = pendingDrag ?: return@LaunchedEffect
        val committedPosition = committedPositions[drag.nodeId] ?: return@LaunchedEffect
        if (committedPosition.isNear(drag.currentPosition)) activeDrag = null
    }

    fun centerNode(nodeId: String): Boolean {
        val position = layout.positions[nodeId] ?: return false
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return false
        val nodeCenter = Offset(
            x = position.x + nodeWidthPixels / CallGraphDefaults.CenterDivisor,
            y = position.y + nodeHeightPixels / CallGraphDefaults.CenterDivisor,
        )
        translationX = viewportSize.width / CallGraphDefaults.CenterDivisor - nodeCenter.x * scale
        translationY = viewportSize.height / CallGraphDefaults.CenterDivisor - nodeCenter.y * scale
        return true
    }

    fun selectRelativeNode(delta: Int): Boolean {
        if (layout.nodes.isEmpty()) return false
        val currentIndex = layout.nodes.indexOfFirst { it.id == graph.selectedNodeId }
            .takeIf { it >= 0 }
            ?: CallGraphDefaults.RootSortRank
        val targetIndex = (currentIndex + delta).coerceIn(layout.nodes.indices)
        if (targetIndex == currentIndex) return false
        val target = layout.nodes[targetIndex]
        onNodeSelected(target.id)
        centerNode(target.id)
        return true
    }

    fun zoomBy(factor: Float): Boolean {
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return false
        val nextScale = (scale * factor).coerceIn(
            CallGraphDefaults.MinimumScale,
            CallGraphDefaults.MaximumScale,
        )
        if (nextScale == scale) return false
        val focalPoint = Offset(
            x = viewportSize.width / CallGraphDefaults.CenterDivisor,
            y = viewportSize.height / CallGraphDefaults.CenterDivisor,
        )
        val scaleChange = nextScale / scale
        translationX = focalPoint.x - (focalPoint.x - translationX) * scaleChange
        translationY = focalPoint.y - (focalPoint.y - translationY) * scaleChange
        scale = nextScale
        return true
    }

    fun startNodeDrag(nodeId: String) {
        if (activeDrag != null) return
        val position = layout.positions[nodeId] ?: return
        activeDrag = CallGraphDragSession(
            nodeId = nodeId,
            startPosition = position,
            currentPosition = position,
        )
    }

    fun dragNodeBy(nodeId: String, delta: Offset) {
        val drag = activeDrag?.takeIf { it.nodeId == nodeId && it.isDragging } ?: return
        activeDrag = drag.copy(currentPosition = drag.currentPosition + delta)
    }

    fun finishNodeDrag(nodeId: String) {
        val drag = activeDrag?.takeIf { it.nodeId == nodeId && it.isDragging } ?: return
        if (drag.startPosition.isNear(drag.currentPosition)) {
            activeDrag = null
            return
        }
        activeDrag = drag.copy(isDragging = false)
        onNodeMoved(nodeId, drag.currentPosition.toViewPosition(densityScale))
    }

    fun cancelNodeDrag(nodeId: String) {
        if (activeDrag?.nodeId == nodeId) activeDrag = null
    }

    fun moveNodeBy(nodeId: String, delta: Offset): Boolean {
        if (activeDrag != null) return false
        val position = layout.positions[nodeId] ?: return false
        onNodeMoved(nodeId, (position + delta).toViewPosition(densityScale))
        return true
    }

    fun closeNode(nodeId: String) {
        cancelNodeDrag(nodeId)
        onNodeClosed(nodeId)
    }

    val transformableState = rememberTransformableState {
            centroid,
            zoomChange,
            panChange,
            _,
        ->
        val nextScale = (scale * zoomChange).coerceIn(
            CallGraphDefaults.MinimumScale,
            CallGraphDefaults.MaximumScale,
        )
        val focalPoint = centroid.takeIf { it.x.isFinite() && it.y.isFinite() } ?: Offset(
            x = viewportSize.width / CallGraphDefaults.CenterDivisor,
            y = viewportSize.height / CallGraphDefaults.CenterDivisor,
        )
        val scaleChange = nextScale / scale
        translationX = focalPoint.x -
            (focalPoint.x - translationX) * scaleChange +
            panChange.x
        translationY = focalPoint.y -
            (focalPoint.y - translationY) * scaleChange +
            panChange.y
        scale = nextScale
    }
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .semantics {
                contentDescription = CallGraphText.graphDescription(layout.nodes.size)
                graph.selectedNode?.let {
                    stateDescription = CallGraphText.selectedNodeDescription(it)
                }
                customActions = listOf(
                    CustomAccessibilityAction(CallGraphText.PreviousMethod) {
                        selectRelativeNode(CallGraphDefaults.PreviousNode)
                    },
                    CustomAccessibilityAction(CallGraphText.NextMethod) {
                        selectRelativeNode(CallGraphDefaults.NextNode)
                    },
                    CustomAccessibilityAction(CallGraphText.ZoomIn) {
                        zoomBy(CallGraphDefaults.AccessibilityZoomIn)
                    },
                    CustomAccessibilityAction(CallGraphText.ZoomOut) {
                        zoomBy(CallGraphDefaults.AccessibilityZoomOut)
                    },
                    CustomAccessibilityAction(CallGraphText.FitDescription) {
                        fitGraph()
                        true
                    },
                )
            }
            .transformable(
                state = transformableState,
                enabled = !dragInProgress,
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val currentScale = scale
            val dragSession = activeDrag
            val normalEdgeStroke = Stroke(
                width = edgeWidthPixels / currentScale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            val selectedEdgeStroke = Stroke(
                width = selectedEdgeWidthPixels / currentScale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            gridRenderer.draw(
                drawScope = this,
                color = colors.grid,
                spacing = (gridSpacingPixels * currentScale)
                    .coerceAtLeast(minimumGridSpacingPixels),
                radius = gridDotRadiusPixels,
                translation = Offset(translationX, translationY),
            )
            withTransform({
                translate(left = translationX, top = translationY)
                scale(
                    scaleX = currentScale,
                    scaleY = currentScale,
                    pivot = Offset.Zero,
                )
            }) {
                layout.edges.forEach { edge ->
                    val selected = edge.fromNodeId == graph.selectedNodeId ||
                        edge.toNodeId == graph.selectedNodeId
                    drawCallGraphEdge(
                        edge = edge,
                        positions = layout.positions,
                        dragSession = dragSession,
                        path = edgePath,
                        nodeWidth = nodeWidthPixels,
                        nodeHeight = nodeHeightPixels,
                        color = colors.edge,
                        stroke = if (selected) {
                            selectedEdgeStroke
                        } else {
                            normalEdgeStroke
                        },
                        arrowSize = arrowSizePixels / currentScale,
                        minimumCurve = curvePixels,
                        loopWidth = loopPixels,
                    )
                }
            }
        }

        layout.nodes.forEach { node ->
            val position = layout.positions[node.id] ?: return@forEach
            key(node.id) {
                CallGraphNode(
                        node = node,
                        selected = node.id == graph.selectedNodeId,
                        tone = nodeTones.getValue(node.id),
                        onSelected = { onNodeSelected(node.id) },
                        onClosed = if (node.isRoot) null else {
                            { closeNode(node.id) }
                        },
                        onDragStarted = { startNodeDrag(node.id) },
                        onDragged = { delta -> dragNodeBy(node.id, delta) },
                        onDragStopped = { finishNodeDrag(node.id) },
                        onDragCanceled = { cancelNodeDrag(node.id) },
                        onMoveBy = { delta -> moveNodeBy(node.id, delta) },
                        accessibilityMoveStep = accessibilityMovePixels,
                        modifier = Modifier
                            .callGraphTransform(
                                nodeId = node.id,
                                position = position,
                                draggedNodeId = draggedNodeId,
                                currentDragPosition = {
                                    activeDrag
                                        ?.takeIf { it.nodeId == node.id }
                                        ?.currentPosition
                                },
                                currentScale = { scale },
                                currentTranslationX = { translationX },
                                currentTranslationY = { translationY },
                            )
                            .zIndex(
                                when (node.id) {
                                    draggedNodeId -> CallGraphDefaults.DraggedZIndex.toFloat()
                                    graph.selectedNodeId ->
                                        CallGraphDefaults.SelectedZIndex.toFloat()
                                    else -> CallGraphDefaults.DefaultZIndex.toFloat()
                                },
                            )
                            .size(
                                width = CallGraphDimens.NodeWidth,
                                height = nodeHeight,
                            ),
                )
            }
        }

        if (layout.nodes.isEmpty()) {
            Text(
                text = CallGraphText.Unavailable,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        CallGraphToolbar(
            canUndo = page.editor.canUndo && !hasActiveDrag,
            canRedo = page.editor.canRedo && !hasActiveDrag,
            canFit = layout.nodes.isNotEmpty(),
            onUndo = onUndo,
            onRedo = onRedo,
            onFit = ::fitGraph,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(CallGraphDimens.ControlMargin)
                .zIndex(CallGraphDefaults.ControlsZIndex),
        )
        if (showWorkspaceSwitch) {
            WorkspaceSwitchButton(
                browserVisible = false,
                onClick = onWorkspaceSwitch,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(ManagerDimens.WorkspaceSwitchMargin)
                    .zIndex(CallGraphDefaults.ControlsZIndex),
            )
        }
    }
}

@Composable
private fun CallGraphToolbar(
    canUndo: Boolean,
    canRedo: Boolean,
    canFit: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(CallGraphDimens.ControlCornerRadius))
            .background(
                MaterialTheme.colorScheme.surface.copy(
                    alpha = ManagerDefaults.FloatingControlBackgroundAlpha,
                ),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CallGraphToolbarIconAction(
            icon = ManagerIcons.Undo,
            description = CallGraphText.UndoDescription,
            enabled = canUndo,
            onClick = onUndo,
        )
        CallGraphToolbarIconAction(
            icon = ManagerIcons.Redo,
            description = CallGraphText.RedoDescription,
            enabled = canRedo,
            onClick = onRedo,
        )
        CallGraphToolbarAction(
            label = CallGraphText.Fit,
            description = CallGraphText.FitDescription,
            enabled = canFit,
            onClick = onFit,
        )
    }
}

@Composable
private fun CallGraphToolbarIconAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(
                alpha = CallGraphDefaults.DisabledContentAlpha,
            ),
        ),
        modifier = Modifier
            .size(ManagerDimens.TouchTarget)
            .semantics { contentDescription = description },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(CallGraphDimens.ControlIconSize),
        )
    }
}

@Composable
private fun CallGraphToolbarAction(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(
                alpha = CallGraphDefaults.DisabledContentAlpha,
            ),
        ),
        contentPadding = PaddingValues(horizontal = CallGraphDimens.ControlHorizontalPadding),
        modifier = Modifier
            .heightIn(min = ManagerDimens.TouchTarget)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = CallGraphDefaults.SingleLine,
        )
    }
}

@Composable
private fun CallGraphNode(
    node: CallGraphNodeViewData,
    selected: Boolean,
    tone: CallGraphNodeTone,
    onSelected: () -> Unit,
    onClosed: (() -> Unit)?,
    onDragStarted: () -> Unit,
    onDragged: (Offset) -> Unit,
    onDragStopped: () -> Unit,
    onDragCanceled: () -> Unit,
    onMoveBy: (Offset) -> Boolean,
    accessibilityMoveStep: Float,
    modifier: Modifier,
) {
    val subtleContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val currentOnDragStarted by rememberUpdatedState(onDragStarted)
    val currentOnDragged by rememberUpdatedState(onDragged)
    val currentOnDragStopped by rememberUpdatedState(onDragStopped)
    val currentOnDragCanceled by rememberUpdatedState(onDragCanceled)
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(node.id) {
                    detectDragGestures(
                        onDragStart = { currentOnDragStarted() },
                        onDragEnd = { currentOnDragStopped() },
                        onDragCancel = { currentOnDragCanceled() },
                    ) { change, dragAmount ->
                        change.consume()
                        currentOnDragged(dragAmount)
                    }
                }
                .clickable(
                    role = Role.Button,
                    onClickLabel = CallGraphText.selectDescription(node),
                    onClick = onSelected,
                )
                .semantics(mergeDescendants = true) {
                    this.selected = selected
                    stateDescription = if (selected) {
                        CallGraphText.Selected
                    } else {
                        CallGraphText.NotSelected
                    }
                    contentDescription = CallGraphText.nodeDescription(node)
                    customActions = buildList {
                        add(
                            CustomAccessibilityAction(CallGraphText.MoveLeft) {
                                onMoveBy(Offset(-accessibilityMoveStep, 0f))
                            },
                        )
                        add(
                            CustomAccessibilityAction(CallGraphText.MoveRight) {
                                onMoveBy(Offset(accessibilityMoveStep, 0f))
                            },
                        )
                        add(
                            CustomAccessibilityAction(CallGraphText.MoveUp) {
                                onMoveBy(Offset(0f, -accessibilityMoveStep))
                            },
                        )
                        add(
                            CustomAccessibilityAction(CallGraphText.MoveDown) {
                                onMoveBy(Offset(0f, accessibilityMoveStep))
                            },
                        )
                        onClosed?.let { close ->
                            add(
                                CustomAccessibilityAction(
                                    CallGraphText.removeDescription(node),
                                ) {
                                    close()
                                    true
                                },
                            )
                        }
                    }
                },
            shape = RoundedCornerShape(CallGraphDimens.NodeCornerRadius),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(
                width = CallGraphDimens.NodeBorderWidth,
                color = if (selected) {
                    MaterialTheme.colorScheme.outline.copy(
                        alpha = CallGraphDefaults.SelectedNodeBorderAlpha,
                    )
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(CallGraphDimens.NodePadding),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            end = if (onClosed == null) {
                                0.dp
                            } else {
                                CallGraphDimens.NodeCloseClearance
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(CallGraphDimens.NodeMarkerSize)
                            .background(subtleContentColor, CircleShape),
                    )
                    Spacer(modifier = Modifier.width(CallGraphDimens.NodeMarkerGap))
                    Text(
                        text = tone.label,
                        color = subtleContentColor,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = CallGraphDefaults.SingleLine,
                    )
                    Text(
                        text = node.ownerName.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = CallGraphDefaults.SingleLine,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = CallGraphDimens.NodeOwnerGap),
                    )
                    if (node.calls.isLoading || node.callers.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(CallGraphDimens.NodeProgressSize),
                            color = subtleContentColor,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                            strokeWidth = CallGraphDimens.ProgressStrokeWidth,
                        )
                    }
                }
                Text(
                    text = node.signature ?: node.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = CallGraphDefaults.NodeSignatureLines,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(CallGraphDimens.NodeAddressGap),
                    verticalArrangement = Arrangement.spacedBy(CallGraphDimens.NodeContentGap),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    node.rvaLabel?.let { value ->
                        GraphAddressLabel(
                            prefix = CallGraphText.Rva,
                            value = value,
                        )
                    }
                    node.address.takeIf { it > 0 }?.let {
                        GraphAddressLabel(
                            prefix = CallGraphText.Va,
                            value = node.addressLabel,
                        )
                    }
                }
            }
        }
        onClosed?.let { close ->
            IconButton(
                onClick = close,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(ManagerDimens.TouchTarget)
                    .semantics {
                        contentDescription = CallGraphText.removeDescription(node)
                    },
            ) {
                Icon(
                    imageVector = ManagerIcons.Close,
                    contentDescription = null,
                    tint = subtleContentColor,
                    modifier = Modifier.size(CallGraphDimens.NodeCloseIconSize),
                )
            }
        }
    }
}

@Composable
private fun GraphAddressLabel(
    prefix: String,
    value: String,
) {
    Text(
        text = "$prefix $value",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        maxLines = CallGraphDefaults.SingleLine,
    )
}

@Composable
private fun SelectedCallGraphNodePanel(
    node: CallGraphNodeViewData,
    graphBusy: Boolean,
    onNodeToggled: (String, CallGraphDirection) -> Unit,
    onNodeInstructionsSelected: (String) -> Unit,
    onNodeCanvasSelected: (String) -> Unit,
    onCopyNodeValue: (String, MethodCopyTarget) -> Unit,
) {
    val issue = remember(node) { node.analysisIssue() }
    val canOpenCanvas = node.canOpen && node.ownerName != null && !node.isRoot
    HorizontalDivider(
        thickness = ManagerDimens.DividerThickness,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding(),
    ) {
        key(node.id) {
            val openCanvasModifier = if (canOpenCanvas) {
                Modifier
                    .clickable(
                        role = Role.Button,
                        onClickLabel = CallGraphText.openCanvasDescription(node),
                        onClick = { onNodeCanvasSelected(node.id) },
                    )
            } else {
                Modifier
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = CallGraphDimens.PanelHorizontalPadding,
                        top = CallGraphDimens.PanelTopPadding,
                        end = CallGraphDimens.PanelHorizontalPadding,
                        bottom = CallGraphDimens.PanelBottomPadding,
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ManagerDimens.TouchTarget)
                        .then(openCanvasModifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(
                            CallGraphDimens.PanelIdentityGap,
                        ),
                    ) {
                        node.ownerName
                            ?.takeIf(String::isNotBlank)
                            ?.let { ownerName ->
                                Text(
                                    text = ownerName,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = CallGraphDefaults.SingleLine,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        Text(
                            text = node.signature ?: node.name,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = CallGraphDefaults.PanelSignatureLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (canOpenCanvas) {
                        Spacer(modifier = Modifier.width(CallGraphDimens.PanelOpenIconGap))
                        Icon(
                            imageVector = ManagerIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(CallGraphDimens.PanelOpenIconSize),
                        )
                    }
                }
                if (node.rvaLabel != null || node.address > 0) {
                    FlowRow(
                        modifier = Modifier.padding(top = CallGraphDimens.PanelAddressTopPadding),
                        horizontalArrangement = Arrangement.spacedBy(CallGraphDimens.AddressGap),
                        verticalArrangement = Arrangement.spacedBy(CallGraphDimens.AddressGap),
                    ) {
                        node.rvaLabel?.let { value ->
                            AddressCopyAction(
                                prefix = CallGraphText.Rva,
                                value = value,
                                onClick = { onCopyNodeValue(node.id, MethodCopyTarget.RVA) },
                            )
                        }
                        node.address.takeIf { it > 0 }?.let {
                            AddressCopyAction(
                                prefix = CallGraphText.Va,
                                value = node.addressLabel,
                                onClick = { onCopyNodeValue(node.id, MethodCopyTarget.VA) },
                            )
                        }
                    }
                }
                issue?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = CallGraphDefaults.PanelIssueLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = CallGraphDimens.PanelIssueTopPadding),
                    )
                }
            }
        }
        HorizontalDivider(
            thickness = ManagerDimens.DividerThickness,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = CallGraphDimens.PanelActionHorizontalPadding,
                    vertical = CallGraphDimens.PanelActionVerticalPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(CallGraphDimens.PanelActionGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CallGraphDimens.PanelActionGap),
            ) {
                CallGraphAction(
                    label = CallGraphText.Raw,
                    enabled = node.canOpen,
                    onClick = { onNodeInstructionsSelected(node.id) },
                    modifier = Modifier.weight(1f),
                )
                CallGraphAction(
                    label = node.calls.actionLabel(CallGraphText.Calls),
                    loading = node.calls.isLoading,
                    enabled = node.canRequest(node.calls, graphBusy),
                    onClick = { onNodeToggled(node.id, CallGraphDirection.CALLS) },
                    modifier = Modifier.weight(1f),
                    active = node.calls.isExpanded,
                    reportsExpansionState = true,
                )
                CallGraphAction(
                    label = node.callers.actionLabel(CallGraphText.CalledBy),
                    loading = node.callers.isLoading,
                    enabled = node.canRequest(node.callers, graphBusy),
                    onClick = { onNodeToggled(node.id, CallGraphDirection.CALLERS) },
                    modifier = Modifier.weight(1f),
                    active = node.callers.isExpanded,
                    reportsExpansionState = true,
                )
            }
        }
    }
}

@Composable
private fun CallGraphAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    loading: Boolean = false,
    active: Boolean = false,
    reportsExpansionState: Boolean = false,
    statusDescription: String? = null,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = contentColor,
            disabledContentColor = contentColor.copy(
                alpha = CallGraphDefaults.DisabledContentAlpha,
            ),
        ),
        contentPadding = PaddingValues(
            horizontal = CallGraphDimens.PanelActionHorizontalPadding,
        ),
        modifier = modifier
            .heightIn(min = ManagerDimens.TouchTarget)
            .clip(RoundedCornerShape(CallGraphDimens.ActionCornerRadius))
            .background(
                if (active) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    Color.Transparent
                },
            )
            .semantics {
                if (statusDescription != null) {
                    stateDescription = statusDescription
                } else if (reportsExpansionState) {
                    stateDescription = if (active) {
                        CallGraphText.Expanded
                    } else {
                        CallGraphText.Collapsed
                    }
                }
            },
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(CallGraphDimens.ActionProgressSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                strokeWidth = CallGraphDimens.ProgressStrokeWidth,
            )
            Spacer(modifier = Modifier.width(CallGraphDimens.ActionProgressGap))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = CallGraphDefaults.SingleLine,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun calculateCallGraphLayout(
    graph: CallGraphViewData,
    nodeWidth: Float,
    nodeHeight: Float,
    horizontalGap: Float,
    verticalGap: Float,
    padding: Float,
): CallGraphLayout {
    val nodes = graph.nodes
        .distinctBy(CallGraphNodeViewData::id)
        .sortedWith(CallGraphNodeComparator)
    if (nodes.isEmpty()) {
        return CallGraphLayout(
            nodes = emptyList(),
            edges = emptyList(),
            positions = emptyMap(),
            bounds = Rect.Zero,
            rootCenter = Offset.Zero,
        )
    }
    val nodesById = nodes.associateBy(CallGraphNodeViewData::id)
    val edges = graph.edges
        .asSequence()
        .filter { it.fromNodeId in nodesById && it.toNodeId in nodesById }
        .map { edge ->
            VisualCallGraphEdge(
                fromNodeId = edge.fromNodeId,
                toNodeId = edge.toNodeId,
            )
        }
        .distinct()
        .sortedWith(compareBy(VisualCallGraphEdge::fromNodeId, VisualCallGraphEdge::toNodeId))
        .toList()
    val rankByNodeId = nodes.mapIndexed { index, node -> node.id to index }.toMap()
    val idComparator = compareBy<String> { rankByNodeId[it] ?: Int.MAX_VALUE }
    val outgoing = edges
        .groupBy(VisualCallGraphEdge::fromNodeId, VisualCallGraphEdge::toNodeId)
        .mapValues { (_, ids) -> ids.distinct().sortedWith(idComparator) }
    val incoming = edges
        .groupBy(VisualCallGraphEdge::toNodeId, VisualCallGraphEdge::fromNodeId)
        .mapValues { (_, ids) -> ids.distinct().sortedWith(idComparator) }
    val anchor = nodesById[graph.rootNodeId] ?: nodes.firstOrNull(CallGraphNodeViewData::isRoot) ?: nodes.first()
    val depths = mutableMapOf(anchor.id to CallGraphDefaults.RootDepth)
    val queue = ArrayDeque<String>()
    queue.add(anchor.id)
    while (queue.isNotEmpty()) {
        val nodeId = queue.removeFirst()
        val depth = depths.getValue(nodeId)
        outgoing[nodeId].orEmpty().forEach { childId ->
            if (childId !in depths) {
                depths[childId] = depth + CallGraphDefaults.LayerStep
                queue.add(childId)
            }
        }
        incoming[nodeId].orEmpty().forEach { callerId ->
            if (callerId !in depths) {
                depths[callerId] = depth - CallGraphDefaults.LayerStep
                queue.add(callerId)
            }
        }
    }
    var detachedDepth = (depths.values.maxOrNull() ?: CallGraphDefaults.RootDepth) +
        CallGraphDefaults.LayerStep
    nodes.filter { it.id !in depths }
        .chunked(CallGraphDefaults.DetachedLayerCapacity)
        .forEach { layer ->
            layer.forEach { depths[it.id] = detachedDepth }
            detachedDepth += CallGraphDefaults.LayerStep
        }

    val layers = nodes
        .groupBy { depths.getValue(it.id) }
        .toSortedMap()
        .mapValues { (_, layerNodes) -> layerNodes.sortedWith(CallGraphNodeComparator) }
    val visualRows = layers.values.flatMap { layer ->
        layer.chunked(CallGraphDefaults.MaximumNodesPerRow)
    }
    val widestRow = visualRows.maxOf { row ->
        row.size * nodeWidth + max(0, row.size - 1) * horizontalGap
    }
    val graphWidth = widestRow + padding * CallGraphDefaults.PaddingSides
    val graphHeight = visualRows.size * nodeHeight +
        max(0, visualRows.size - 1) * verticalGap +
        padding * CallGraphDefaults.PaddingSides
    val positions = mutableMapOf<String, Offset>()
    visualRows.forEachIndexed { rowIndex, rowNodes ->
        val rowWidth = rowNodes.size * nodeWidth +
            max(0, rowNodes.size - 1) * horizontalGap
        val startX = (graphWidth - rowWidth) / CallGraphDefaults.CenterDivisor
        val y = padding + rowIndex * (nodeHeight + verticalGap)
        rowNodes.forEachIndexed { nodeIndex, node ->
            positions[node.id] = Offset(
                x = startX + nodeIndex * (nodeWidth + horizontalGap),
                y = y,
            )
        }
    }
    val rootPosition = positions.getValue(anchor.id)
    return CallGraphLayout(
        nodes = nodes,
        edges = edges,
        positions = positions,
        bounds = Rect(0f, 0f, graphWidth, graphHeight),
        rootCenter = Offset(
            x = rootPosition.x + nodeWidth / CallGraphDefaults.CenterDivisor,
            y = rootPosition.y + nodeHeight / CallGraphDefaults.CenterDivisor,
        ),
    )
}

private fun CallGraphLayout.stabilized(
    graph: CallGraphViewData,
    automaticPositions: Map<String, Offset>,
    positionOverrides: Map<String, Offset>,
    reservedSlots: List<Rect>,
    nodeWidth: Float,
    nodeHeight: Float,
    horizontalGap: Float,
    verticalGap: Float,
    padding: Float,
): CallGraphLayout {
    if (nodes.isEmpty()) return this
    val calculatedPositions = positions
    val hasKnownPosition = nodes.any { node ->
        positionOverrides[node.id].isFinitePosition() ||
            automaticPositions[node.id].isFinitePosition()
    }
    if (!hasKnownPosition && reservedSlots.isEmpty()) {
        return copy(
            bounds = methodGraphBounds(calculatedPositions.values, nodeWidth, nodeHeight, padding),
        )
    }

    val resolvedPositions = linkedMapOf<String, Offset>()
    val occupied = reservedSlots.toMutableList()
    nodes.forEach { node ->
        val position = (
            positionOverrides[node.id]
                ?: automaticPositions[node.id]
            )?.takeIf(Offset::isFinitePosition)
            ?: return@forEach
        resolvedPositions[node.id] = position
        occupied += nodeRect(position, nodeWidth, nodeHeight)
    }
    val rootNodeId = graph.rootNodeId.takeIf { nodeId ->
        calculatedPositions.containsKey(nodeId)
    } ?: nodes.first().id
    val collisionGap = min(horizontalGap, verticalGap) *
        CallGraphDefaults.CollisionGapFraction
    nodes.filter { node -> node.id !in resolvedPositions }.forEach { node ->
        val calculatedPosition = calculatedPositions.getValue(node.id)
        val anchor = graph.placementAnchorFor(node.id, resolvedPositions.keys)
        val anchorPosition = anchor?.nodeId?.let(resolvedPositions::get)
        val calculatedAnchorPosition = anchor?.nodeId?.let(calculatedPositions::get)
        val rootPosition = resolvedPositions[rootNodeId]
            ?: positionOverrides[rootNodeId]
            ?: automaticPositions[rootNodeId]
            ?: calculatedPositions.getValue(rootNodeId)
        val preferredPosition = if (anchorPosition != null && calculatedAnchorPosition != null) {
            anchorPosition + (calculatedPosition - calculatedAnchorPosition)
        } else {
            rootPosition + (
                calculatedPosition - calculatedPositions.getValue(rootNodeId)
                )
        }
        val verticalDirection = when (anchor?.direction) {
            CallGraphDirection.CALLERS -> -1f
            CallGraphDirection.CALLS -> 1f
            null -> if (calculatedPosition.y < calculatedPositions.getValue(rootNodeId).y) {
                -1f
            } else {
                1f
            }
        }
        val position = collisionFreePosition(
            preferred = preferredPosition,
            candidates = methodPlacementCandidates(
                preferred = preferredPosition,
                horizontalStep = nodeWidth + horizontalGap,
                verticalStep = nodeHeight + verticalGap,
                verticalDirection = verticalDirection,
            ),
            occupied = occupied,
            width = nodeWidth,
            height = nodeHeight,
            gap = collisionGap,
            fallbackDirection = verticalDirection,
        )
        resolvedPositions[node.id] = position
        occupied += nodeRect(position, nodeWidth, nodeHeight)
    }
    val rootPosition = resolvedPositions.getValue(rootNodeId)
    return copy(
        positions = resolvedPositions,
        bounds = methodGraphBounds(resolvedPositions.values, nodeWidth, nodeHeight, padding),
        rootCenter = Offset(
            x = rootPosition.x + nodeWidth / CallGraphDefaults.CenterDivisor,
            y = rootPosition.y + nodeHeight / CallGraphDefaults.CenterDivisor,
        ),
    )
}

private fun CallGraphLayout.displayLayout(
    graph: CallGraphViewData,
    positionOverrides: Map<String, Offset>,
    nodeWidth: Float,
    nodeHeight: Float,
    padding: Float,
): CallGraphLayout {
    val nodesById = graph.nodes.associateBy(CallGraphNodeViewData::id)
    val displayNodes = nodes
        .asSequence()
        .mapNotNull { node -> nodesById[node.id] }
        .toList()
    if (displayNodes.isEmpty()) {
        return CallGraphLayout(
            nodes = emptyList(),
            edges = emptyList(),
            positions = emptyMap(),
            bounds = Rect.Zero,
            rootCenter = Offset.Zero,
        )
    }
    val displayNodeIds = displayNodes.mapTo(mutableSetOf(), CallGraphNodeViewData::id)
    val displayPositions = displayNodes.associate { node ->
        node.id to (positionOverrides[node.id] ?: positions.getValue(node.id))
    }
    val displayEdges = edges.filter { edge ->
        edge.fromNodeId in displayNodeIds && edge.toNodeId in displayNodeIds
    }
    val rootNodeId = graph.rootNodeId.takeIf { it in displayNodeIds }
        ?: displayNodes.first().id
    val rootPosition = displayPositions.getValue(rootNodeId)
    return CallGraphLayout(
        nodes = displayNodes,
        edges = displayEdges,
        positions = displayPositions,
        bounds = methodGraphBounds(displayPositions.values, nodeWidth, nodeHeight, padding),
        rootCenter = Offset(
            x = rootPosition.x + nodeWidth / CallGraphDefaults.CenterDivisor,
            y = rootPosition.y + nodeHeight / CallGraphDefaults.CenterDivisor,
        ),
    )
}

private fun methodPlacementCandidates(
    preferred: Offset,
    horizontalStep: Float,
    verticalStep: Float,
    verticalDirection: Float,
): Sequence<Offset> = sequence {
    yield(preferred)
    for (ring in 1..CallGraphDefaults.NodePlacementRings) {
        val horizontalOffset = horizontalStep * ring
        yield(Offset(preferred.x + horizontalOffset, preferred.y))
        yield(Offset(preferred.x - horizontalOffset, preferred.y))
        val directedY = preferred.y + verticalStep * ring * verticalDirection
        val oppositeY = preferred.y - verticalStep * ring * verticalDirection
        yield(Offset(preferred.x, directedY))
        yield(Offset(preferred.x, oppositeY))
        for (column in 1..ring) {
            val xOffset = horizontalStep * column
            yield(Offset(preferred.x + xOffset, directedY))
            yield(Offset(preferred.x - xOffset, directedY))
            yield(Offset(preferred.x + xOffset, oppositeY))
            yield(Offset(preferred.x - xOffset, oppositeY))
        }
    }
}

private fun collisionFreePosition(
    preferred: Offset,
    candidates: Sequence<Offset>,
    occupied: List<Rect>,
    width: Float,
    height: Float,
    gap: Float,
    fallbackDirection: Float,
): Offset = candidates.firstOrNull { candidate ->
    candidate.isFinitePosition() &&
        occupied.none { existing ->
            existing.overlapsWithGap(nodeRect(candidate, width, height), gap)
        }
} ?: if (fallbackDirection < 0f) {
    Offset(
        x = preferred.x,
        y = (occupied.minOfOrNull(Rect::top) ?: preferred.y) - height - gap,
    )
} else {
    Offset(
        x = preferred.x,
        y = (occupied.maxOfOrNull(Rect::bottom) ?: preferred.y) + gap,
    )
}

private fun CallGraphViewData.placementAnchorFor(
    nodeId: String,
    positionedNodeIds: Set<String>,
): CallGraphPlacementAnchor? {
    edges.forEach { edge ->
        edge.origins.forEach { origin ->
            val adjacentNodeId = when (origin.direction) {
                CallGraphDirection.CALLS -> edge.toNodeId
                CallGraphDirection.CALLERS -> edge.fromNodeId
            }
            if (adjacentNodeId == nodeId && origin.nodeId in positionedNodeIds) {
                return CallGraphPlacementAnchor(origin.nodeId, origin.direction)
            }
        }
    }
    return null
}

private fun Offset?.isFinitePosition(): Boolean =
    this != null && x.isFinite() && y.isFinite()

private fun nodeReservationPositions(
    nodeId: String,
    automaticPositions: Map<String, Offset>,
    committedPositions: Map<String, Offset>,
    restorablePositions: Map<String, Set<Offset>>,
): Set<Offset> = buildSet {
    automaticPositions[nodeId]?.takeIf(Offset::isFinitePosition)?.let(::add)
    committedPositions[nodeId]?.takeIf(Offset::isFinitePosition)?.let(::add)
    restorablePositions[nodeId]
        .orEmpty()
        .filterTo(this, Offset::isFinitePosition)
}
private fun methodGraphBounds(
    positions: Collection<Offset>,
    nodeWidth: Float,
    nodeHeight: Float,
    padding: Float,
): Rect {
    if (positions.isEmpty()) return Rect.Zero
    return Rect(
        left = positions.minOf(Offset::x) - padding,
        top = positions.minOf(Offset::y) - padding,
        right = positions.maxOf { position -> position.x + nodeWidth } + padding,
        bottom = positions.maxOf { position -> position.y + nodeHeight } + padding,
    )
}

private fun nodeRect(
    position: Offset,
    width: Float,
    height: Float,
): Rect = Rect(
    left = position.x,
    top = position.y,
    right = position.x + width,
    bottom = position.y + height,
)

private fun Rect.overlapsWithGap(
    other: Rect,
    gap: Float,
): Boolean =
    left < other.right + gap &&
        right + gap > other.left &&
        top < other.bottom + gap &&
        bottom + gap > other.top

private fun fitTransform(
    viewport: IntSize,
    graphBounds: Rect,
    margin: Float,
): CallGraphTransform {
    val availableWidth = max(
        CallGraphDefaults.MinimumAvailablePixels,
        viewport.width - margin * CallGraphDefaults.PaddingSides,
    )
    val availableHeight = max(
        CallGraphDefaults.MinimumAvailablePixels,
        viewport.height - margin * CallGraphDefaults.PaddingSides,
    )
    val scale = min(
        availableWidth / graphBounds.width.coerceAtLeast(CallGraphDefaults.MinimumAvailablePixels),
        availableHeight / graphBounds.height.coerceAtLeast(CallGraphDefaults.MinimumAvailablePixels),
    ).coerceIn(CallGraphDefaults.MinimumScale, CallGraphDefaults.MaximumFitScale)
    return CallGraphTransform(
        scale = scale,
        translation = Offset(
            x = viewport.width / CallGraphDefaults.CenterDivisor - graphBounds.center.x * scale,
            y = viewport.height / CallGraphDefaults.CenterDivisor - graphBounds.center.y * scale,
        ),
    )
}

private fun DrawScope.drawCallGraphEdge(
    edge: VisualCallGraphEdge,
    positions: Map<String, Offset>,
    dragSession: CallGraphDragSession?,
    path: Path,
    nodeWidth: Float,
    nodeHeight: Float,
    color: Color,
    stroke: Stroke,
    arrowSize: Float,
    minimumCurve: Float,
    loopWidth: Float,
) {
    val from = positions.renderPosition(edge.fromNodeId, dragSession) ?: return
    val to = positions.renderPosition(edge.toNodeId, dragSession) ?: return
    if (edge.fromNodeId == edge.toNodeId) {
        val start = Offset(from.x + nodeWidth, from.y + nodeHeight * CallGraphDefaults.LoopStartFraction)
        val end = Offset(from.x + nodeWidth, from.y + nodeHeight * CallGraphDefaults.LoopEndFraction)
        val firstControl = Offset(start.x + loopWidth, start.y + loopWidth)
        val secondControl = Offset(end.x + loopWidth, end.y - loopWidth)
        path.reset()
        path.moveTo(start.x, start.y)
        path.cubicTo(
            firstControl.x,
            firstControl.y,
            secondControl.x,
            secondControl.y,
            end.x,
            end.y,
        )
        drawPath(
            path = path,
            color = color,
            style = stroke,
        )
        drawArrowHead(end, secondControl, color, stroke.width, arrowSize)
        return
    }

    val fromCenter = Offset(
        x = from.x + nodeWidth / CallGraphDefaults.CenterDivisor,
        y = from.y + nodeHeight / CallGraphDefaults.CenterDivisor,
    )
    val toCenter = Offset(
        x = to.x + nodeWidth / CallGraphDefaults.CenterDivisor,
        y = to.y + nodeHeight / CallGraphDefaults.CenterDivisor,
    )
    val sameLayer = abs(toCenter.y - fromCenter.y) < nodeHeight
    val start: Offset
    val end: Offset
    val firstControl: Offset
    val secondControl: Offset
    if (sameLayer) {
        val rightward = toCenter.x >= fromCenter.x
        val direction = if (rightward) CallGraphDefaults.ForwardDirection else -CallGraphDefaults.ForwardDirection
        start = Offset(
            x = fromCenter.x + direction * nodeWidth / CallGraphDefaults.CenterDivisor,
            y = fromCenter.y,
        )
        end = Offset(
            x = toCenter.x - direction * nodeWidth / CallGraphDefaults.CenterDivisor,
            y = toCenter.y,
        )
        firstControl = Offset(
            x = start.x + direction * minimumCurve,
            y = start.y - minimumCurve,
        )
        secondControl = Offset(
            x = end.x - direction * minimumCurve,
            y = end.y - minimumCurve,
        )
    } else {
        val downward = toCenter.y > fromCenter.y
        val direction = if (downward) {
            CallGraphDefaults.ForwardDirection
        } else {
            -CallGraphDefaults.ForwardDirection
        }
        start = Offset(
            x = fromCenter.x,
            y = fromCenter.y + direction * nodeHeight / CallGraphDefaults.CenterDivisor,
        )
        end = Offset(
            x = toCenter.x,
            y = toCenter.y - direction * nodeHeight / CallGraphDefaults.CenterDivisor,
        )
        val controlDistance = max(abs(end.y - start.y) / CallGraphDefaults.CenterDivisor, minimumCurve)
        firstControl = Offset(start.x, start.y + direction * controlDistance)
        secondControl = Offset(end.x, end.y - direction * controlDistance)
    }
    path.reset()
    path.moveTo(start.x, start.y)
    path.cubicTo(
        firstControl.x,
        firstControl.y,
        secondControl.x,
        secondControl.y,
        end.x,
        end.y,
    )
    drawPath(
        path = path,
        color = color,
        style = stroke,
    )
    drawArrowHead(end, secondControl, color, stroke.width, arrowSize)
}

private fun DrawScope.drawArrowHead(
    tip: Offset,
    previousControl: Offset,
    color: Color,
    strokeWidth: Float,
    size: Float,
) {
    val angle = atan2(tip.y - previousControl.y, tip.x - previousControl.x)
    val first = Offset(
        x = tip.x - size * cos(angle - CallGraphDefaults.ArrowAngleRadians),
        y = tip.y - size * sin(angle - CallGraphDefaults.ArrowAngleRadians),
    )
    val second = Offset(
        x = tip.x - size * cos(angle + CallGraphDefaults.ArrowAngleRadians),
        y = tip.y - size * sin(angle + CallGraphDefaults.ArrowAngleRadians),
    )
    drawLine(
        color = color,
        start = first,
        end = tip,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = color,
        start = second,
        end = tip,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
}

private const val GRID_COORDINATES_PER_POINT = 2
private const val GRID_EXTRA_POINT_COUNT = 2
private const val GRID_BUFFER_GROWTH_FACTOR = 2

private class CallGraphGridRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private var coordinates = FloatArray(0)
    private var pointFloatCount = 0
    private var cachedWidth = Float.NaN
    private var cachedHeight = Float.NaN
    private var cachedSpacing = Float.NaN

    fun draw(
        drawScope: DrawScope,
        color: Color,
        spacing: Float,
        radius: Float,
        translation: Offset,
    ) {
        val width = drawScope.size.width
        val height = drawScope.size.height
        if (!spacing.isFinite() || spacing <= 0f ||
            !radius.isFinite() || radius <= 0f ||
            !width.isFinite() || width <= 0f ||
            !height.isFinite() || height <= 0f ||
            !ensureCoordinates(width, height, spacing)
        ) {
            return
        }
        paint.color = color.toArgb()
        paint.strokeWidth = radius + radius
        drawScope.drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            val checkpoint = nativeCanvas.save()
            try {
                nativeCanvas.translate(
                    translation.x.positiveModulo(spacing),
                    translation.y.positiveModulo(spacing),
                )
                nativeCanvas.drawPoints(coordinates, 0, pointFloatCount, paint)
            } finally {
                nativeCanvas.restoreToCount(checkpoint)
            }
        }
    }

    private fun ensureCoordinates(
        width: Float,
        height: Float,
        spacing: Float,
    ): Boolean {
        if (width == cachedWidth && height == cachedHeight && spacing == cachedSpacing) {
            return pointFloatCount > 0
        }
        val columnCount = (width / spacing).toInt() + GRID_EXTRA_POINT_COUNT
        val rowCount = (height / spacing).toInt() + GRID_EXTRA_POINT_COUNT
        val requiredFloatCount = columnCount.toLong() *
            rowCount.toLong() *
            GRID_COORDINATES_PER_POINT
        if (requiredFloatCount <= 0L || requiredFloatCount > Int.MAX_VALUE) return false
        val requiredSize = requiredFloatCount.toInt()
        if (coordinates.size < requiredSize) {
            coordinates = FloatArray(
                max(requiredSize, coordinates.size * GRID_BUFFER_GROWTH_FACTOR),
            )
        }
        var coordinateIndex = 0
        repeat(columnCount) { column ->
            val x = column * spacing
            repeat(rowCount) { row ->
                coordinates[coordinateIndex++] = x
                coordinates[coordinateIndex++] = row * spacing
            }
        }
        pointFloatCount = coordinateIndex
        cachedWidth = width
        cachedHeight = height
        cachedSpacing = spacing
        return true
    }
}

private fun Float.positiveModulo(divisor: Float): Float = ((this % divisor) + divisor) % divisor

private fun CallGraphNodeViewData.analysisIssue(): String? {
    val callsIssue = calls.issue()
    val callersIssue = callers.issue()
    return when {
        callsIssue == null -> callersIssue?.let { issue ->
            "${CallGraphText.CalledByStatus} · $issue"
        }
        callersIssue == null -> "${CallGraphText.CallsStatus} · $callsIssue"
        callsIssue == callersIssue -> callsIssue
        else -> buildString {
            append(CallGraphText.CallsStatus)
            append(" · ")
            append(callsIssue)
            appendLine()
            append(CallGraphText.CalledByStatus)
            append(" · ")
            append(callersIssue)
        }
    }
}

private fun CallGraphExpansionViewData.issue(): String? = when {
    failureMessage != null -> failureMessage
    status == MethodAnalysisStatus.PARTIAL_LIMIT -> CallGraphText.AnalysisLimit
    status == MethodAnalysisStatus.PARTIAL_CONTROL_FLOW || unresolvedIndirectCallCount > 0 ->
        CallGraphText.IndirectOmitted
    status == MethodAnalysisStatus.UNAVAILABLE -> CallGraphText.AnalysisUnavailable
    else -> null
}

private fun CallGraphNodeViewData.canRequest(
    expansion: CallGraphExpansionViewData,
    graphBusy: Boolean,
): Boolean = canOpen &&
    (expansion.isExpanded || !graphBusy) &&
    (!expansion.isLoading || expansion.isExpanded)

private fun CallGraphExpansionViewData.accessibilityCountLabel(): String = when {
    isLoading -> CallGraphText.Loading
    failureMessage != null -> CallGraphText.Failed
    totalCount == null -> CallGraphText.Unknown
    hasMore -> "$loadedCount of $totalCount loaded"
    else -> totalCount.toString()
}

private fun CallGraphExpansionViewData.actionLabel(label: String): String {
    val total = totalCount ?: return label
    val count = if (hasMore) "$loadedCount/$total" else total.toString()
    return "$label · $count"
}

private fun CallGraphNodeViewData.expansion(
    direction: CallGraphDirection,
): CallGraphExpansionViewData = when (direction) {
    CallGraphDirection.CALLS -> calls
    CallGraphDirection.CALLERS -> callers
}

private fun CallGraphViewData.nodeTones(): Map<String, CallGraphNodeTone> {
    val nodesById = nodes.associateBy(CallGraphNodeViewData::id)
    val directionsByNode = mutableMapOf<String, MutableSet<CallGraphDirection>>()
    for (edge in edges) {
        for (origin in edge.origins) {
            val owner = nodesById[origin.nodeId] ?: continue
            if (!owner.expansion(origin.direction).isExpanded) continue
            val introducedNodeId = when (origin.direction) {
                CallGraphDirection.CALLS -> edge.toNodeId
                CallGraphDirection.CALLERS -> edge.fromNodeId
            }
            directionsByNode
                .getOrPut(introducedNodeId, ::mutableSetOf)
                .add(origin.direction)
        }
    }
    return nodes.associate { node ->
        val directions = directionsByNode[node.id].orEmpty()
        val tone = when {
            node.id == rootNodeId -> CallGraphNodeTone.ROOT
            directions.size != 1 -> CallGraphNodeTone.MIXED
            CallGraphDirection.CALLS in directions -> CallGraphNodeTone.CALL
            else -> CallGraphNodeTone.CALLER
        }
        node.id to tone
    }
}

private enum class CallGraphNodeTone(val label: String) {
    ROOT(CallGraphText.Root),
    CALL(CallGraphText.Call),
    CALLER(CallGraphText.Caller),
    MIXED(CallGraphText.Mixed),
}

private data class CallGraphLayout(
    val nodes: List<CallGraphNodeViewData>,
    val edges: List<VisualCallGraphEdge>,
    val positions: Map<String, Offset>,
    val bounds: Rect,
    val rootCenter: Offset,
)

private data class CallGraphPlacementAnchor(
    val nodeId: String,
    val direction: CallGraphDirection,
)

private data class CallGraphDragSession(
    val nodeId: String,
    val startPosition: Offset,
    val currentPosition: Offset,
    val isDragging: Boolean = true,
)

private fun Modifier.callGraphTransform(
    nodeId: String,
    position: Offset,
    draggedNodeId: String?,
    currentDragPosition: () -> Offset?,
    currentScale: () -> Float,
    currentTranslationX: () -> Float,
    currentTranslationY: () -> Float,
): Modifier =
    offset {
        val renderPosition = if (nodeId == draggedNodeId) {
            currentDragPosition() ?: position
        } else {
            position
        }
        val scale = currentScale()
        IntOffset(
            x = (currentTranslationX() + renderPosition.x * scale).roundToInt(),
            y = (currentTranslationY() + renderPosition.y * scale).roundToInt(),
        )
    }.graphicsLayer {
        val scale = currentScale()
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(0f, 0f)
    }

private fun Map<String, Offset>.renderPosition(
    nodeId: String,
    dragSession: CallGraphDragSession?,
): Offset? = if (dragSession?.nodeId == nodeId) {
    dragSession.currentPosition
} else {
    this[nodeId]
}

private fun CallGraphNodePositionViewData.toGraphOffset(densityScale: Float): Offset = Offset(
    x = x * densityScale,
    y = y * densityScale,
)

private fun Offset.toViewPosition(densityScale: Float): CallGraphNodePositionViewData =
    CallGraphNodePositionViewData(
        x = x / densityScale,
        y = y / densityScale,
    )

private fun Offset.isNear(other: Offset): Boolean =
    abs(x - other.x) <= CallGraphDefaults.PositionEpsilon &&
        abs(y - other.y) <= CallGraphDefaults.PositionEpsilon

private data class VisualCallGraphEdge(
    val fromNodeId: String,
    val toNodeId: String,
)

private data class CallGraphColors(
    val edge: Color,
    val grid: Color,
)

@Composable
private fun callGraphColors(): CallGraphColors {
    return CallGraphColors(
        edge = MaterialTheme.colorScheme.onSurfaceVariant.copy(
            alpha = CallGraphDefaults.EdgeAlpha,
        ),
        grid = MaterialTheme.colorScheme.outlineVariant.copy(
            alpha = CallGraphDefaults.GridAlpha,
        ),
    )
}

private data class CallGraphTransform(
    val scale: Float,
    val translation: Offset,
)

private val CallGraphNodeComparator = compareBy<CallGraphNodeViewData>(
    { if (it.isRoot) CallGraphDefaults.RootSortRank else CallGraphDefaults.NodeSortRank },
    { it.ownerName.orEmpty() },
    CallGraphNodeViewData::name,
    CallGraphNodeViewData::address,
    CallGraphNodeViewData::id,
)

private object CallGraphDimens {
    val NodeWidth = 264.dp
    val NodeHeight = 112.dp
    val EdgeWidth = 1.dp
    val SelectedEdgeWidth = 2.dp
    val GridSpacing = 28.dp
    val MinimumGridSpacing = 14.dp
    val GridDotRadius = 1.dp
    val NodePadding = 10.dp
    val NodeBorderWidth = 1.dp
    val NodeCornerRadius = 14.dp
    val NodeContentGap = 4.dp
    val NodeMarkerSize = 7.dp
    val NodeMarkerGap = 6.dp
    val NodeOwnerGap = 8.dp
    val NodeAddressGap = 6.dp
    val NodeProgressSize = 12.dp
    val NodeCloseClearance = 36.dp
    val NodeCloseIconSize = 16.dp
    val ActionProgressSize = 14.dp
    val ProgressStrokeWidth = 1.5.dp
    val ActionProgressGap = 6.dp
    val HorizontalGap = 28.dp
    val VerticalGap = 72.dp
    val GraphPadding = 36.dp
    val ArrowSize = 7.dp
    val MinimumCurve = 24.dp
    val LoopWidth = 42.dp
    val ControlMargin = 8.dp
    val ControlHorizontalPadding = 10.dp
    val ControlCornerRadius = 10.dp
    val ControlIconSize = 18.dp
    val AccessibilityMoveStep = 32.dp
    val PanelHorizontalPadding = 16.dp
    val PanelTopPadding = 8.dp
    val PanelBottomPadding = 4.dp
    val PanelIdentityGap = 2.dp
    val PanelAddressTopPadding = 2.dp
    val PanelIssueTopPadding = 4.dp
    val PanelOpenIconGap = 8.dp
    val PanelOpenIconSize = 20.dp
    val AddressGap = 8.dp
    val PanelActionHorizontalPadding = 8.dp
    val PanelActionVerticalPadding = 4.dp
    val PanelActionGap = 4.dp
    val ActionCornerRadius = 8.dp
}

private object CallGraphDefaults {
    const val RootDepth = 0
    const val LayerStep = 1
    const val DetachedLayerCapacity = 8
    const val MaximumNodesPerRow = 6
    const val PaddingSides = 2
    const val RootSortRank = 0
    const val NodeSortRank = 1
    const val DefaultZIndex = 0
    const val SelectedZIndex = 1
    const val DraggedZIndex = 2
    const val ControlsZIndex = 3f
    const val SingleLine = 1
    const val NodeSignatureLines = 2
    const val PanelSignatureLines = 2
    const val PanelIssueLines = 2
    const val NodePlacementRings = 12
    const val CenterDivisor = 2f
    const val InitialScale = 1f
    const val MinimumScale = 0.2f
    const val MaximumScale = 2.5f
    const val MaximumFitScale = 1f
    const val MinimumAvailablePixels = 1f
    const val PositionEpsilon = 0.5f

    const val GridAlpha = 0.72f
    const val EdgeAlpha = 0.52f
    const val SelectedNodeBorderAlpha = 0.60f
    const val DisabledContentAlpha = 0.38f
    const val CollisionGapFraction = 0.5f
    const val ForwardDirection = 1f
    const val LoopStartFraction = 0.64f
    const val LoopEndFraction = 0.36f
    const val ArrowAngleRadians = 0.58f
    const val PreviousNode = -1
    const val NextNode = 1
    const val AccessibilityZoomIn = 1.25f
    const val AccessibilityZoomOut = 0.8f
}

private object CallGraphText {
    const val Unavailable = "Call graph unavailable."
    const val AnalysisLimit = "Analysis limits omitted some direct edges."
    const val IndirectOmitted = "Indirect control flow is unresolved and omitted."
    const val AnalysisUnavailable = "Analysis is unavailable for some nodes."
    const val Fit = "FIT"
    const val FitDescription = "Fit and reset call graph view"
    const val UndoDescription = "Undo last canvas edit"
    const val RedoDescription = "Redo last canvas edit"
    const val Root = "ROOT"
    const val Call = "CALL"
    const val Caller = "CALLER"
    const val Mixed = "MIXED"
    const val Selected = "Selected"
    const val NotSelected = "Not selected"
    const val Raw = "RAW"
    const val Calls = "CALLS"
    const val CalledBy = "CALLED BY"
    const val CallsStatus = "Calls"
    const val CalledByStatus = "Called by"
    const val Expanded = "Expanded"
    const val Collapsed = "Collapsed"
    const val Loading = "loading"
    const val Failed = "failed"
    const val Unknown = "unknown"
    const val Rva = "RVA"
    const val Va = "VA"
    const val PreviousMethod = "Previous method"
    const val NextMethod = "Next method"
    const val ZoomIn = "Zoom in"
    const val ZoomOut = "Zoom out"
    const val MoveLeft = "Move method left"
    const val MoveRight = "Move method right"
    const val MoveUp = "Move method up"
    const val MoveDown = "Move method down"
    fun graphDescription(methodCount: Int): String =
        "Call graph with $methodCount methods. Swipe for graph actions."

    fun selectedNodeDescription(node: CallGraphNodeViewData): String =
        "Selected ${node.ownerName?.let { "$it." }.orEmpty()}${node.name}"

    fun selectDescription(node: CallGraphNodeViewData): String =
        "Select ${node.ownerName?.let { "$it." }.orEmpty()}${node.name}"

    fun openCanvasDescription(node: CallGraphNodeViewData): String =
        "Open ${node.ownerName?.let { "$it." }.orEmpty()}${node.name} in a canvas"

    fun removeDescription(node: CallGraphNodeViewData): String =
        "Remove ${node.ownerName?.let { "$it." }.orEmpty()}${node.name} from canvas"

    fun nodeDescription(node: CallGraphNodeViewData): String = buildString {
        if (node.isRoot) append("Root method, ")
        node.ownerName?.takeIf(String::isNotBlank)?.let {
            append(it)
            append(", ")
        }
        append(node.signature ?: node.name)
        append(", address ")
        append(node.addressLabel)
        append(", ")
        append("incoming direct references ")
        append(node.callers.accessibilityCountLabel())
        append(", outgoing direct references ")
        append(node.calls.accessibilityCountLabel())
    }
}
