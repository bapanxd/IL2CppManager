package dev.ruri.il2cppmanager.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ruri.il2cppmanager.domain.InstructionFlowKind
import dev.ruri.il2cppmanager.domain.MethodAnalysisStatus

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun MethodInstructionsPage(
    page: MethodCanvasPage.Instructions,
    onLoadMore: () -> Unit,
    onLoadPrevious: () -> Unit,
    onInstructionSelected: (Long) -> Unit,
    onInstructionTargetSelected: (Long, Int, Int) -> Unit,
    onInstructionScrollConsumed: (Long) -> Unit,
    onCopyInstruction: (Long) -> Unit,
    modifier: Modifier = Modifier,
    additionalBottomPadding: Dp = 0.dp,
) {
    val analysis = page.analysis
    when {
        analysis.isInitialLoading && analysis.items.isEmpty() -> AnalysisMessage(
            message = MethodAnalysisText.ReadingInstructions,
            loading = true,
            modifier = modifier,
        )
        analysis.failureMessage != null && analysis.items.isEmpty() -> AnalysisRetry(
            message = analysis.failureMessage,
            onRetry = onLoadMore,
            modifier = modifier,
        )
        analysis.status == MethodAnalysisStatus.UNAVAILABLE -> AnalysisMessage(
            message = MethodAnalysisText.InstructionsUnavailable,
            modifier = modifier,
        )
        analysis.items.isEmpty() -> AnalysisMessage(
            message = analysis.withNote(MethodAnalysisText.NoInstructions),
            modifier = modifier,
        )
        else -> {
            val layoutDirection = LocalLayoutDirection.current
            val systemContentPadding = WindowInsets.navigationBars.asPaddingValues()
            val startPadding = if (layoutDirection == LayoutDirection.Ltr) {
                systemContentPadding.calculateLeftPadding(layoutDirection)
            } else {
                systemContentPadding.calculateRightPadding(layoutDirection)
            }
            val endPadding = if (layoutDirection == LayoutDirection.Ltr) {
                systemContentPadding.calculateRightPadding(layoutDirection)
            } else {
                systemContentPadding.calculateLeftPadding(layoutDirection)
            }
            val listContentPadding = PaddingValues(
                start = startPadding,
                top = systemContentPadding.calculateTopPadding(),
                end = endPadding,
                bottom = systemContentPadding.calculateBottomPadding() + additionalBottomPadding,
            )
            val listState = rememberInstructionsListState(analysis, onLoadMore)
            val addressScrollRequest = page.scrollRequest as? InstructionScrollRequest.Address
            val focusIndex = analysis.items.indexOfFirst {
                it.address == addressScrollRequest?.address
            }
            val noteVisible = analysisNote(analysis) != null
            val instructionStartIndex = 2 +
                (if (noteVisible) 1 else 0) +
                (if (analysis.hasPrevious) 1 else 0)
            LaunchedEffect(page.scrollRequest?.id, focusIndex, instructionStartIndex) {
                when (val request = page.scrollRequest) {
                    is InstructionScrollRequest.Address -> if (focusIndex >= 0) {
                        listState.scrollToItem(
                            (instructionStartIndex + focusIndex -
                                MethodAnalysisDimens.FocusLeadRows).coerceAtLeast(0),
                        )
                        onInstructionScrollConsumed(request.id)
                    }
                    is InstructionScrollRequest.Viewport -> {
                        listState.scrollToItem(
                            request.firstVisibleItemIndex,
                            request.firstVisibleItemScrollOffset,
                        )
                        onInstructionScrollConsumed(request.id)
                    }
                    null -> Unit
                }
            }
            LazyColumn(
                state = listState,
                modifier = modifier.fillMaxSize(),
                contentPadding = listContentPadding,
            ) {
                item(key = MethodAnalysisKeys.Heading) {
                    AnalysisHeading(
                        MethodAnalysisText.instructionsHeading(analysis.totalCount),
                    )
                }
                analysisNote(analysis)?.let { note ->
                    item(key = MethodAnalysisKeys.Note) {
                        AnalysisNote(note)
                    }
                }
                if (analysis.hasPrevious) {
                    item(
                        key = MethodAnalysisKeys.Previous,
                        contentType = MethodAnalysisContent.Footer,
                    ) {
                        PreviousInstructionsAction(
                            loading = analysis.isLoadingPrevious,
                            onClick = onLoadPrevious,
                        )
                    }
                }
                stickyHeader(
                    key = MethodAnalysisKeys.InstructionHeader,
                    contentType = MethodAnalysisContent.InstructionHeader,
                ) {
                    Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
                        InstructionHeader(
                            addressLabel = page.addressMode.label,
                            selectedInstructionAddress = page.selectedInstructionAddress,
                            onCopyInstruction = onCopyInstruction,
                        )
                        RowDivider()
                    }
                }
                itemsIndexed(
                    items = analysis.items,
                    key = { _, item -> item.address },
                    contentType = { _, _ -> MethodAnalysisContent.Instruction },
                ) { _, instruction ->
                    InstructionRow(
                        instruction = instruction,
                        addressMode = page.addressMode,
                        selected = instruction.address == page.selectedInstructionAddress,
                        targetLoading = instruction.address == page.pendingTargetAddress,
                        onSelected = { onInstructionSelected(instruction.address) },
                        onTargetSelected = {
                            onInstructionTargetSelected(
                                instruction.address,
                                listState.firstVisibleItemIndex,
                                listState.firstVisibleItemScrollOffset,
                            )
                        },
                        onCopy = { onCopyInstruction(instruction.address) },
                    )
                }
                analysisFooter(analysis, onLoadMore)
            }
        }
    }
}

@Composable
private fun rememberInstructionsListState(
    analysis: MethodAnalysisSectionViewData<*>,
    onLoadMore: () -> Unit,
): LazyListState {
    val listState = rememberLazyListState()
    val canLoadMore = analysis.hasMore &&
        !analysis.isInitialLoading &&
        !analysis.isLoadingMore &&
        !analysis.isLoadingPrevious &&
        analysis.failureMessage == null
    LaunchedEffect(listState, canLoadMore, analysis.items.size) {
        if (!canLoadMore) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisibleIndex = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex to layout.totalItemsCount
        }.collect { (lastVisibleIndex, itemCount) ->
            val triggerIndex = (itemCount - MethodAnalysisDimens.PrefetchDistance).coerceAtLeast(0)
            if (itemCount > 0 && lastVisibleIndex >= triggerIndex) onLoadMore()
        }
    }
    return listState
}

@Composable
private fun AnalysisHeading(label: String) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() }
            .padding(
                start = ManagerDimens.ContentPadding,
                end = ManagerDimens.ContentPadding,
                top = MethodAnalysisDimens.HeadingTopPadding,
                bottom = MethodAnalysisDimens.HeadingBottomPadding,
            ),
    )
}

@Composable
private fun AnalysisNote(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ManagerDimens.ContentPadding,
                vertical = MethodAnalysisDimens.NoteVerticalPadding,
            ),
    )
}

@Composable
private fun AnalysisMessage(
    message: String,
    modifier: Modifier,
    loading: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(ManagerDimens.ContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(MethodAnalysisDimens.ProgressSize),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    strokeWidth = MethodAnalysisDimens.ProgressThickness,
                )
                Spacer(modifier = Modifier.size(MethodAnalysisDimens.ProgressGap))
            }
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AnalysisRetry(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(ManagerDimens.ContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = MethodAnalysisText.Retry,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .heightIn(min = ManagerDimens.TouchTarget)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = MethodAnalysisText.RetryAnalysis,
                        onClick = onRetry,
                    )
                    .padding(
                        horizontal = MethodAnalysisDimens.RetryHorizontalPadding,
                        vertical = MethodAnalysisDimens.RetryVerticalPadding,
                    ),
            )
        }
    }
}

@Composable
private fun InstructionHeader(
    addressLabel: String,
    selectedInstructionAddress: Long?,
    onCopyInstruction: (Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MethodAnalysisDimens.InstructionToolbarHeight)
            .padding(start = ManagerDimens.ContentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$addressLabel \u00B7 ${MethodAnalysisText.MachineCode}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier.size(ManagerDimens.TouchTarget),
            contentAlignment = Alignment.Center,
        ) {
            selectedInstructionAddress?.let { address ->
                Icon(
                    imageVector = ManagerIcons.Copy,
                    contentDescription = MethodAnalysisText.CopySelectedInstruction,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(ManagerDimens.TouchTarget)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = MethodAnalysisText.CopySelectedInstruction,
                            onClick = { onCopyInstruction(address) },
                        )
                        .padding(MethodAnalysisDimens.HeaderActionPadding),
                )
            }
        }
    }
}

@Composable
private fun InstructionRow(
    instruction: InstructionViewData,
    addressMode: InstructionAddressMode,
    selected: Boolean,
    targetLoading: Boolean,
    onSelected: () -> Unit,
    onTargetSelected: () -> Unit,
    onCopy: () -> Unit,
) {
    val address = addressMode.value(instruction)
    val semanticLabel = MethodAnalysisText.instructionDescription(
        addressMode.label,
        address,
        instruction,
    )
    val localBranch = instruction.flowKind == InstructionFlowKind.DIRECT_BRANCH &&
        instruction.targetInstructionIndex != null
    val target = instruction.target
    val targetBeforeSource = target?.address?.let { it < instruction.address } == true
    val targetSummary = instruction.targetSummary(addressMode, localBranch, targetBeforeSource)
    val targetActionAvailable = target != null && (localBranch || target.canOpen)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.background
                },
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ManagerDimens.TouchTarget)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = MethodAnalysisText.SelectInstruction,
                    onLongClickLabel = MethodAnalysisText.CopyInstruction,
                    onClick = onSelected,
                    onLongClick = onCopy,
                )
                .semantics {
                    contentDescription = semanticLabel
                    this.selected = selected
                }
                .padding(
                    horizontal = ManagerDimens.ContentPadding,
                    vertical = MethodAnalysisDimens.InstructionVerticalPadding,
                ),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(
                    MethodAnalysisDimens.InstructionMetadataGap,
                ),
            ) {
                InstructionMetadata("${addressMode.label} $address")
                InstructionMetadata(instruction.bytesLabel)
            }
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                        append(instruction.mnemonic)
                    }
                    if (instruction.operands.isNotBlank()) {
                        append("  ")
                        append(instruction.operands)
                    }
                },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.padding(
                    top = MethodAnalysisDimens.InstructionCodeTopPadding,
                ),
            )
            if (!targetActionAvailable) {
                targetSummary?.let { summary ->
                    InstructionTargetLabel(
                        value = summary,
                        modifier = Modifier.padding(
                            top = MethodAnalysisDimens.InstructionTargetTopPadding,
                        ),
                    )
                }
            }
        }
        if (targetActionAvailable && targetSummary != null) {
            InstructionTargetAction(
                label = targetSummary,
                localBranch = localBranch,
                targetBeforeSource = targetBeforeSource,
                loading = targetLoading,
                onClick = onTargetSelected,
            )
        }
        RowDivider()
    }
}

@Composable
private fun InstructionTargetLabel(
    value: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = value,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = FontFamily.Monospace,
        ),
        modifier = modifier,
    )
}

@Composable
private fun InstructionMetadata(value: String) {
    Text(
        text = value,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
private fun InstructionTargetAction(
    label: String,
    localBranch: Boolean,
    targetBeforeSource: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val description = if (localBranch) {
        MethodAnalysisText.JumpToInstruction
    } else {
        MethodAnalysisText.OpenTargetMethod
    }
    val interaction = if (loading) {
        Modifier
    } else {
        Modifier.clickable(
            role = Role.Button,
            onClickLabel = "$description: $label",
            onClick = onClick,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ManagerDimens.TouchTarget)
            .then(interaction)
            .padding(start = ManagerDimens.ContentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InstructionTargetLabel(
            value = label,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = MethodAnalysisDimens.InstructionTargetVerticalPadding),
        )
        Box(
            modifier = Modifier.size(ManagerDimens.TouchTarget),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(ManagerDimens.SmallIconSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    strokeWidth = MethodAnalysisDimens.ProgressThickness,
                )
            } else if (localBranch) {
                Text(
                    text = if (targetBeforeSource) "\u2191" else "\u2193",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Icon(
                    imageVector = ManagerIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(ManagerDimens.SmallIconSize),
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.analysisFooter(
    analysis: MethodAnalysisSectionViewData<*>,
    onRetry: () -> Unit,
) {
    when {
        analysis.isLoadingMore -> item(
            key = MethodAnalysisKeys.Footer,
            contentType = MethodAnalysisContent.Footer,
        ) {
            AnalysisFooter(MethodAnalysisText.LoadingMore, loading = true)
        }
        analysis.failureMessage != null -> item(
            key = MethodAnalysisKeys.Footer,
            contentType = MethodAnalysisContent.Footer,
        ) {
            AnalysisFooter(
                message = analysis.failureMessage,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun PreviousInstructionsAction(
    loading: Boolean,
    onClick: () -> Unit,
) {
    val clickModifier = if (loading) {
        Modifier
    } else {
        Modifier.clickable(
            role = Role.Button,
            onClickLabel = MethodAnalysisText.LoadEarlierInstructions,
            onClick = onClick,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .heightIn(min = MethodAnalysisDimens.FooterHeight)
            .padding(horizontal = ManagerDimens.ContentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(MethodAnalysisDimens.FooterProgressSize),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                strokeWidth = MethodAnalysisDimens.ProgressThickness,
            )
            Spacer(modifier = Modifier.size(MethodAnalysisDimens.ProgressGap))
        }
        Text(
            text = if (loading) {
                MethodAnalysisText.LoadingEarlier
            } else {
                MethodAnalysisText.LoadEarlier
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun AnalysisFooter(
    message: String,
    loading: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    val modifier = if (onRetry != null) {
        Modifier.clickable(
            role = Role.Button,
            onClickLabel = MethodAnalysisText.RetryAnalysis,
            onClick = onRetry,
        )
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .heightIn(min = MethodAnalysisDimens.FooterHeight)
            .padding(horizontal = ManagerDimens.ContentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(MethodAnalysisDimens.FooterProgressSize),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                strokeWidth = MethodAnalysisDimens.ProgressThickness,
            )
            Spacer(modifier = Modifier.size(MethodAnalysisDimens.ProgressGap))
        }
        Text(
            text = if (onRetry == null) message else "$message ${MethodAnalysisText.TapToRetry}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun analysisNote(analysis: MethodAnalysisSectionViewData<*>): String? =
    when (analysis.status) {
        MethodAnalysisStatus.PARTIAL_CONTROL_FLOW -> {
            val count = analysis.unresolvedIndirectCallCount
            if (count > 0) {
                MethodAnalysisText.unresolvedCalls(count)
            } else {
                MethodAnalysisText.IndirectCallsMissing
            }
        }
        MethodAnalysisStatus.PARTIAL_LIMIT -> MethodAnalysisText.AnalysisLimited
        MethodAnalysisStatus.COMPLETE,
        MethodAnalysisStatus.UNAVAILABLE,
        null,
        -> null
    }

private fun MethodAnalysisSectionViewData<*>.withNote(message: String): String =
    analysisNote(this)?.let { "$message $it" } ?: message

private val InstructionAddressMode.label: String
    get() = when (this) {
        InstructionAddressMode.RVA -> MethodAnalysisText.Rva
        InstructionAddressMode.VA -> MethodAnalysisText.Va
    }

private fun InstructionAddressMode.value(instruction: InstructionViewData): String = when (this) {
    InstructionAddressMode.RVA -> instruction.rvaLabel ?: MethodAnalysisText.MissingAddress
    InstructionAddressMode.VA -> instruction.addressLabel
}

private fun InstructionViewData.targetSummary(
    addressMode: InstructionAddressMode,
    localBranch: Boolean,
    targetBeforeSource: Boolean,
): String? = target?.let { reference ->
    val prefix = when {
        localBranch -> "${MethodAnalysisText.Jump} ${if (targetBeforeSource) "\u2191" else "\u2193"}"
        flowKind == InstructionFlowKind.DIRECT_CALL -> MethodAnalysisText.Call
        flowKind == InstructionFlowKind.DIRECT_BRANCH -> MethodAnalysisText.Branch
        else -> MethodAnalysisText.Target
    }
    val address = when (addressMode) {
        InstructionAddressMode.RVA -> reference.rvaLabel ?: reference.addressLabel
        InstructionAddressMode.VA -> reference.addressLabel
    }
    val symbol = sequenceOf(reference.signature, reference.name)
        .filterNotNull()
        .firstOrNull(String::isNotBlank)
    val value = if (localBranch) address else symbol ?: address
    "$prefix  $value"
}

private object MethodAnalysisKeys {
    const val Heading = "analysis:heading"
    const val Note = "analysis:note"
    const val InstructionHeader = "analysis:instruction-header"
    const val Previous = "analysis:previous"
    const val Footer = "analysis:footer"
}

private object MethodAnalysisContent {
    const val InstructionHeader = "analysis-instruction-header"
    const val Instruction = "analysis-instruction"
    const val Footer = "analysis-footer"
}

private object MethodAnalysisDimens {
    const val PrefetchDistance = 6
    const val FocusLeadRows = 3
    val HeadingTopPadding = 18.dp
    val HeadingBottomPadding = 8.dp
    val NoteVerticalPadding = 8.dp
    val ProgressSize = 22.dp
    val ProgressThickness = 2.dp
    val ProgressGap = 10.dp
    val RetryHorizontalPadding = 16.dp
    val RetryVerticalPadding = 8.dp
    val InstructionToolbarHeight = 48.dp
    val HeaderActionPadding = 14.dp
    val InstructionVerticalPadding = 8.dp
    val InstructionMetadataGap = 12.dp
    val InstructionCodeTopPadding = 4.dp
    val InstructionTargetTopPadding = 4.dp
    val InstructionTargetVerticalPadding = 8.dp
    val FooterHeight = 52.dp
    val FooterProgressSize = 18.dp
}

private object MethodAnalysisText {
    const val Rva = "RVA"
    const val Va = "VA"
    const val MachineCode = "MACHINE CODE"
    const val ReadingInstructions = "Reading instructions..."
    const val LoadingMore = "Loading more..."
    const val LoadingEarlier = "Loading earlier instructions..."
    const val LoadEarlier = "Load earlier instructions"
    const val LoadEarlierInstructions = "Load earlier instructions"
    const val Retry = "Retry"
    const val RetryAnalysis = "Retry analysis"
    const val TapToRetry = "Tap to retry."
    const val NoInstructions = "No instructions found."
    const val InstructionsUnavailable = "Raw instructions are unavailable."
    const val IndirectCallsMissing = "Indirect control flow may be missing."
    const val AnalysisLimited = "Safety limit reached; results may be incomplete."
    const val MissingAddress = "\u2014"
    const val SelectInstruction = "Select instruction"
    const val CopyInstruction = "Copy instruction"
    const val CopySelectedInstruction = "Copy selected instruction"
    const val JumpToInstruction = "Jump to target instruction"
    const val OpenTargetMethod = "Open target method"
    const val Call = "CALL"
    const val Branch = "BRANCH"
    const val Jump = "JUMP"
    const val Target = "TARGET"

    fun instructionsHeading(totalCount: Int?): String =
        totalCount?.let { "$it ${if (it == 1) "instruction" else "instructions"}" }
            ?: "Instructions"

    fun unresolvedCalls(count: Int): String =
        "$count indirect ${if (count == 1) "call was" else "calls were"} not resolved."

    fun instructionDescription(
        addressType: String,
        address: String,
        instruction: InstructionViewData,
    ): String = buildString {
        append(addressType)
        append(' ')
        append(address)
        append(", bytes ")
        append(instruction.bytesLabel)
        append(", ")
        append(instruction.mnemonic)
        if (instruction.operands.isNotBlank()) {
            append(' ')
            append(instruction.operands)
        }
    }
}
