package dev.ruri.il2cppmanager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruri.il2cppmanager.domain.SearchMatchMode

@Composable
internal fun ManagerHeader(
    selectedProcess: ProcessViewData?,
    isProcessPickerVisible: Boolean,
    onToggleProcessPicker: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (isProcessPickerVisible) 180f else 0f,
        animationSpec = tween(ManagerMotion.Standard),
        label = "process picker arrow",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ManagerPalette.DarkSurface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(ManagerDimens.HeaderHeight)
            .padding(
                start = ManagerDimens.ContentPadding,
                end = ManagerDimens.HeaderEndPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (isProcessPickerVisible) {
                        ManagerText.CloseProcessPicker
                    } else {
                        ManagerText.OpenProcessPicker
                    },
                    onClick = onToggleProcessPicker,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectedProcess?.appName ?: ManagerText.SelectProcess,
                    color = ManagerPalette.OnDarkSurface,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selectedProcess != null) {
                    Text(
                        text = ManagerText.processSubtitle(selectedProcess),
                        color = ManagerPalette.OnDarkSurfaceSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = ManagerIcons.ChevronDown,
                contentDescription = null,
                tint = ManagerPalette.OnDarkSurface,
                modifier = Modifier
                    .size(ManagerDimens.IconSize)
                    .graphicsLayer(rotationZ = arrowRotation),
            )
        }
        IconButton(
            onClick = onOpenMenu,
            modifier = Modifier.size(ManagerDimens.TouchTarget),
        ) {
            Icon(
                imageVector = ManagerIcons.Menu,
                contentDescription = ManagerText.OpenMenu,
                tint = ManagerPalette.OnDarkSurface,
                modifier = Modifier.size(ManagerDimens.UtilityIconSize),
            )
        }
    }
}

@Composable
internal fun WorkspaceTabBar(
    tabs: WorkspaceTabsViewData,
    onCanvasSelected: (String) -> Unit,
    onCanvasClosed: (String) -> Unit,
    onCloseAllCanvases: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val canvasIds = remember(tabs.canvases) {
        tabs.canvases.map(CanvasTabViewData::id)
    }

    LaunchedEffect(tabs.selectedCanvasId, canvasIds) {
        val selectedIndex = canvasIds.indexOf(tabs.selectedCanvasId)
        if (selectedIndex >= 0) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ManagerDimens.WorkspaceTabHeight)
            .background(ManagerPalette.DarkSurface)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            items(
                items = tabs.canvases,
                key = CanvasTabViewData::id,
            ) { tab ->
                WorkspaceCanvasTab(
                    tab = tab,
                    selected = tab.id == tabs.selectedCanvasId,
                    onSelected = { onCanvasSelected(tab.id) },
                    onClosed = { onCanvasClosed(tab.id) },
                )
            }
        }
        WorkspaceOverflowMenu(
            tabs = tabs,
            onCanvasSelected = onCanvasSelected,
            onCanvasClosed = onCanvasClosed,
            onCloseAllCanvases = onCloseAllCanvases,
        )
    }
}

@Composable
private fun WorkspaceCanvasTab(
    tab: CanvasTabViewData,
    selected: Boolean,
    onSelected: () -> Unit,
    onClosed: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(
                min = ManagerDimens.WorkspaceCanvasTabMinWidth,
                max = ManagerDimens.WorkspaceCanvasTabMaxWidth,
            )
            .background(
                if (selected) ManagerPalette.WorkspaceTabSelected else Color.Transparent,
            ),
    ) {
        Row(modifier = Modifier.fillMaxHeight()) {
            WorkspaceTabSelection(
                label = tab.methodName,
                secondaryLabel = tab.ownerName.substringAfterLast('.'),
                contentDescription = ManagerText.canvasTabDescription(tab),
                selected = selected,
                isBusy = tab.isBusy,
                onClick = onSelected,
                modifier = Modifier.widthIn(
                    min = ManagerDimens.WorkspaceCanvasLabelMinWidth,
                    max = ManagerDimens.WorkspaceCanvasLabelMaxWidth,
                ),
            )
            IconButton(
                onClick = onClosed,
                modifier = Modifier.size(ManagerDimens.TouchTarget),
            ) {
                Icon(
                    imageVector = ManagerIcons.Close,
                    contentDescription = ManagerText.closeCanvasDescription(tab),
                    tint = ManagerPalette.OnDarkSurfaceSecondary,
                    modifier = Modifier.size(ManagerDimens.UtilityIconSize),
                )
            }
        }
        if (selected) {
            WorkspaceSelectionIndicator()
        }
    }
}

@Composable
private fun WorkspaceTabSelection(
    label: String,
    contentDescription: String,
    selected: Boolean,
    isBusy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                this.selected = selected
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ManagerDimens.WorkspaceTabHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) {
                        ManagerPalette.OnDarkSurface
                    } else {
                        ManagerPalette.OnDarkSurfaceSecondary
                    },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                secondaryLabel?.let { owner ->
                    Text(
                        text = owner,
                        color = ManagerPalette.OnDarkSurfaceTertiary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isBusy) {
                Spacer(modifier = Modifier.width(ManagerDimens.WorkspaceBusyIndicatorGap))
                CircularProgressIndicator(
                    modifier = Modifier.size(ManagerDimens.WorkspaceBusyIndicatorSize),
                    color = ManagerPalette.OnDarkSurfaceSecondary,
                    trackColor = Color.Transparent,
                    strokeWidth = ManagerDimens.WorkspaceBusyIndicatorStroke,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.WorkspaceSelectionIndicator() {
    Spacer(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(ManagerDimens.WorkspaceTabIndicatorThickness)
            .background(ManagerPalette.OnDarkSurfaceSecondary),
    )
}

@Composable
internal fun WorkspaceSwitchButton(
    browserVisible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = ManagerDimens.TouchTarget)
            .semantics {
                contentDescription = ManagerText.switchWorkspaceDescription(browserVisible)
            },
        shape = RoundedCornerShape(ManagerDimens.WorkspaceSwitchCornerRadius),
        color = MaterialTheme.colorScheme.surface.copy(
            alpha = ManagerDefaults.FloatingControlBackgroundAlpha,
        ),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = ManagerDimens.DividerThickness,
            color = MaterialTheme.colorScheme.outlineVariant.copy(
                alpha = ManagerDefaults.WorkspaceSwitchBorderAlpha,
            ),
        ),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = ManagerDimens.WorkspaceSwitchHorizontalPadding,
            ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (browserVisible) {
                    ManagerIcons.CanvasWorkspace
                } else {
                    ManagerIcons.BrowserWorkspace
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ManagerDimens.WorkspaceSwitchIconSize),
            )
            Spacer(modifier = Modifier.width(ManagerDimens.WorkspaceSwitchContentGap))
            Text(
                text = ManagerText.SwitchWorkspace,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun WorkspaceOverflowMenu(
    tabs: WorkspaceTabsViewData,
    onCanvasSelected: (String) -> Unit,
    onCanvasClosed: (String) -> Unit,
    onCloseAllCanvases: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(tabs.canvases.isEmpty()) {
        if (tabs.canvases.isEmpty()) {
            expanded = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(ManagerDimens.TouchTarget),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = { expanded = true },
            enabled = tabs.canvases.isNotEmpty(),
            modifier = Modifier
                .size(ManagerDimens.TouchTarget)
                .semantics {
                    contentDescription = ManagerText.OpenCanvasMenu
                },
        ) {
            Text(
                text = ManagerText.WorkspaceOverflowSymbol,
                color = if (tabs.canvases.isEmpty()) {
                    ManagerPalette.DarkSurfaceDivider
                } else {
                    ManagerPalette.OnDarkSurface
                },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(ManagerDimens.WorkspaceMenuCornerRadius),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp,
            shadowElevation = ManagerDimens.WorkspaceMenuElevation,
            border = BorderStroke(
                width = ManagerDimens.DividerThickness,
                color = MaterialTheme.colorScheme.outlineVariant.copy(
                    alpha = ManagerDefaults.WorkspaceMenuBorderAlpha,
                ),
            ),
            modifier = Modifier.width(ManagerDimens.WorkspaceOverflowMenuWidth),
        ) {
            Spacer(modifier = Modifier.height(ManagerDimens.WorkspaceMenuOuterPadding))
            tabs.canvases.forEach { tab ->
                WorkspaceOverflowCanvasRow(
                    tab = tab,
                    selected = tab.id == tabs.selectedCanvasId,
                    onSelected = {
                        expanded = false
                        onCanvasSelected(tab.id)
                    },
                    onClosed = {
                        if (tabs.canvases.size == 1) {
                            expanded = false
                        }
                        onCanvasClosed(tab.id)
                    },
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(
                    horizontal = ManagerDimens.WorkspaceMenuDividerHorizontalPadding,
                    vertical = ManagerDimens.WorkspaceMenuDividerVerticalPadding,
                ),
                thickness = ManagerDimens.DividerThickness,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = ManagerText.CloseAllTabs,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                onClick = {
                    expanded = false
                    onCloseAllCanvases()
                },
                modifier = Modifier
                    .padding(horizontal = ManagerDimens.WorkspaceMenuOuterPadding)
                    .clip(RoundedCornerShape(ManagerDimens.WorkspaceMenuItemCornerRadius))
                    .heightIn(min = ManagerDimens.TouchTarget),
            )
            Spacer(modifier = Modifier.height(ManagerDimens.WorkspaceMenuOuterPadding))
        }
    }
}

@Composable
private fun WorkspaceOverflowCanvasRow(
    tab: CanvasTabViewData,
    selected: Boolean,
    onSelected: () -> Unit,
    onClosed: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ManagerDimens.WorkspaceMenuOuterPadding,
                vertical = ManagerDimens.WorkspaceMenuItemVerticalPadding,
            )
            .heightIn(min = ManagerDimens.WorkspaceMenuItemMinHeight)
            .clip(RoundedCornerShape(ManagerDimens.WorkspaceMenuItemCornerRadius))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.outlineVariant.copy(
                        alpha = ManagerDefaults.WorkspaceMenuSelectionAlpha,
                    )
                } else {
                    Color.Transparent
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(
                    role = Role.Tab,
                    onClick = onSelected,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = ManagerText.canvasTabDescription(tab)
                    this.selected = selected
                }
                .padding(horizontal = ManagerDimens.WorkspaceMenuHorizontalPadding),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = tab.methodName,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tab.ownerName.substringAfterLast('.'),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onClosed,
            modifier = Modifier.size(ManagerDimens.TouchTarget),
        ) {
            Icon(
                imageVector = ManagerIcons.Close,
                contentDescription = ManagerText.closeCanvasDescription(tab),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ManagerDimens.UtilityIconSize),
            )
        }
    }
}

@Composable
internal fun BrowserBreadcrumbBar(
    breadcrumbs: List<BreadcrumbViewData>,
    onBreadcrumbSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollState, breadcrumbs) {
        snapshotFlow(scrollState::maxValue).collect(scrollState::scrollTo)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ManagerDimens.BreadcrumbHeight)
            .background(ManagerPalette.DarkSurface)
            .horizontalScroll(scrollState)
            .padding(horizontal = ManagerDimens.BreadcrumbHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        breadcrumbs.forEachIndexed { index, breadcrumb ->
            if (index == breadcrumbs.lastIndex) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .heightIn(min = ManagerDimens.TouchTarget)
                        .padding(horizontal = ManagerDimens.WorkspaceTabHorizontalPadding)
                        .semantics {
                            contentDescription =
                                ManagerText.currentBreadcrumbDescription(breadcrumb)
                        },
                ) {
                    Text(
                        text = breadcrumb.label,
                        color = ManagerPalette.OnDarkSurface,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            } else {
                TextButton(
                    onClick = { onBreadcrumbSelected(breadcrumb.id) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = ManagerPalette.OnDarkSurfaceSecondary,
                    ),
                    modifier = Modifier.heightIn(min = ManagerDimens.TouchTarget),
                ) {
                    Text(
                        text = breadcrumb.label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }
            if (index < breadcrumbs.lastIndex) {
                Text(
                    text = "\u203A",
                    color = ManagerPalette.OnDarkSurfaceSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
internal fun ProcessPickerOverlay(
    state: ProcessPickerViewState,
    onQueryChanged: (String) -> Unit,
    onProcessSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.isVisible,
        enter = slideInVertically(
            animationSpec = tween(ManagerMotion.Slow),
            initialOffsetY = { -it / 5 },
        ) + fadeIn(tween(ManagerMotion.Standard)),
        exit = slideOutVertically(
            animationSpec = tween(ManagerMotion.Standard),
            targetOffsetY = { -it / 5 },
        ) + fadeOut(tween(ManagerMotion.Fast)),
        modifier = modifier,
    ) {
        val filteredProcesses = remember(state.query, state.processes) {
            val normalizedQuery = state.query.trim()
            if (normalizedQuery.isEmpty()) {
                state.processes
            } else {
                state.processes.filter { process ->
                    process.appName.contains(normalizedQuery, ignoreCase = true) ||
                        process.packageName.contains(normalizedQuery, ignoreCase = true) ||
                        process.processName.contains(normalizedQuery, ignoreCase = true) ||
                        process.pid.toString().contains(normalizedQuery)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ManagerPalette.DarkSurface),
        ) {
            ProcessSearchField(
                value = state.query,
                onValueChanged = onQueryChanged,
            )
            when {
                state.isLoading -> LoadingProcesses()
                state.message != null -> PickerMessage(state.message)
                filteredProcesses.isEmpty() -> PickerMessage(
                    if (state.query.isBlank()) {
                        ManagerText.NoRunningProcesses
                    } else {
                        ManagerText.NoMatchingProcesses
                    },
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        items = filteredProcesses,
                        key = ProcessViewData::id,
                    ) { process ->
                        ProcessRow(
                            process = process,
                            onClick = { onProcessSelected(process.id) },
                        )
                        HorizontalDivider(
                            thickness = ManagerDimens.DividerThickness,
                            color = ManagerPalette.DarkSurfaceDivider,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessSearchField(
    value: String,
    onValueChanged: (String) -> Unit,
) {
    DarkSearchField(
        value = value,
        placeholder = ManagerText.SearchProcesses,
        onValueChanged = onValueChanged,
        autoFocus = true,
    )
    HorizontalDivider(
        thickness = ManagerDimens.DividerThickness,
        color = ManagerPalette.DarkSurfaceDivider,
    )
}

@Composable
internal fun BrowserSearchDock(
    value: String,
    options: BrowserSearchOptions,
    placeholder: String,
    contextKey: String,
    showScope: Boolean,
    onValueChanged: (String) -> Unit,
    onScopeChanged: (BrowserSearchScope) -> Unit,
    onMatchModeChanged: (SearchMatchMode) -> Unit,
    onMatchCaseChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(contextKey) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ManagerPalette.DarkSurface),
    ) {
        HorizontalDivider(
            thickness = ManagerDimens.DividerThickness,
            color = ManagerPalette.DarkSurfaceDivider,
        )
        if (showScope) {
            SearchScopeBar(
                scope = options.scope,
                onScopeChanged = onScopeChanged,
            )
            HorizontalDivider(
                thickness = ManagerDimens.DividerThickness,
                color = ManagerPalette.DarkSurfaceDivider,
            )
        }
        DarkSearchField(
            value = value,
            placeholder = placeholder,
            onValueChanged = onValueChanged,
            autoFocus = false,
            trailingContent = {
                SearchUtilityDivider()
                SearchModeToggle(
                    icon = ManagerIcons.ExactMatch,
                    label = ManagerText.ExactMatch,
                    checked = options.matchMode == SearchMatchMode.EXACT,
                    onCheckedChange = { exactMatch ->
                        onMatchModeChanged(
                            if (exactMatch) SearchMatchMode.EXACT else SearchMatchMode.CONTAINS,
                        )
                    },
                )
                SearchModeToggle(
                    icon = ManagerIcons.MatchCase,
                    label = ManagerText.MatchCase,
                    checked = options.matchCase,
                    onCheckedChange = onMatchCaseChanged,
                )
            },
        )
    }
}

@Composable
private fun SearchScopeBar(
    scope: BrowserSearchScope,
    onScopeChanged: (BrowserSearchScope) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ManagerDimens.SearchScopeHeight)
            .selectableGroup(),
    ) {
        SearchScopeOption(
            label = ManagerText.CurrentLevelTab,
            accessibilityLabel = ManagerText.CurrentLevel,
            selected = scope == BrowserSearchScope.CURRENT_LEVEL,
            onClick = { onScopeChanged(BrowserSearchScope.CURRENT_LEVEL) },
            modifier = Modifier.weight(1f),
        )
        SearchScopeOption(
            label = ManagerText.EverywhereTab,
            accessibilityLabel = ManagerText.Everywhere,
            selected = scope == BrowserSearchScope.EVERYWHERE,
            onClick = { onScopeChanged(BrowserSearchScope.EVERYWHERE) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SearchScopeOption(
    label: String,
    accessibilityLabel: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(ManagerDimens.SearchScopeHeight)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
                this.selected = selected
            }
            .padding(horizontal = ManagerDimens.SearchScopeOptionPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) {
                ManagerPalette.OnDarkSurface
            } else {
                ManagerPalette.OnDarkSurfaceSecondary
            },
            style = MaterialTheme.typography.labelMedium,
        )
        HorizontalDivider(
            thickness = ManagerDimens.SearchOptionIndicatorThickness,
            color = if (selected) ManagerPalette.OnDarkSurface else Color.Transparent,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun SearchModeToggle(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(ManagerDimens.TouchTarget)
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
            },
    ) {
        MonochromeToggleVisual(icon = icon, selected = checked)
    }
}

@Composable
internal fun MonochromeToggleVisual(
    icon: ImageVector,
    selected: Boolean,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) {
            ManagerPalette.OnDarkSurface
        } else {
            ManagerPalette.OnDarkSurfaceSecondary
        },
        animationSpec = tween(ManagerMotion.Fast),
        label = "toggle icon color",
    )
    Box(modifier = Modifier.fillMaxSize()) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(ManagerDimens.UtilityIconSize)
                .align(Alignment.Center),
        )
        HorizontalDivider(
            thickness = ManagerDimens.SearchOptionIndicatorThickness,
            color = if (selected) ManagerPalette.OnDarkSurface else Color.Transparent,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = ManagerDimens.SearchModeIndicatorBottomPadding)
                .width(ManagerDimens.SearchModeIndicatorWidth),
        )
    }
}

@Composable
private fun SearchUtilityDivider() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.width(ManagerDimens.SearchUtilityDividerGap))
        Box(
            modifier = Modifier
                .width(ManagerDimens.DividerThickness)
                .height(ManagerDimens.SearchUtilityDividerHeight)
                .background(ManagerPalette.DarkSurfaceDivider),
        )
        Spacer(modifier = Modifier.width(ManagerDimens.SearchUtilityDividerGap))
    }
}

@Composable
private fun DarkSearchField(
    value: String,
    placeholder: String,
    onValueChanged: (String) -> Unit,
    autoFocus: Boolean,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(autoFocus) {
        if (autoFocus) focusRequester.requestFocus()
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChanged,
        modifier = modifier
            .fillMaxWidth()
            .height(ManagerDimens.SearchBarHeight)
            .focusRequester(focusRequester)
            .semantics { contentDescription = placeholder },
        singleLine = true,
        textStyle = TextStyle(
            color = ManagerPalette.OnDarkSurface,
            fontFamily = FontFamily.SansSerif,
            fontSize = MaterialTheme.typography.bodyLarge.fontSize,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
        cursorBrush = SolidColor(ManagerPalette.OnDarkSurface),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = ManagerDimens.ContentPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = ManagerIcons.Search,
                    contentDescription = null,
                    tint = ManagerPalette.OnDarkSurfaceSecondary,
                    modifier = Modifier.size(ManagerDimens.IconSize),
                )
                Spacer(modifier = Modifier.size(ManagerDimens.SearchIconGap))
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = ManagerPalette.OnDarkSurfaceSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    IconButton(
                        onClick = { onValueChanged("") },
                        modifier = Modifier.size(ManagerDimens.TouchTarget),
                    ) {
                        Icon(
                            imageVector = ManagerIcons.Close,
                            contentDescription = ManagerText.ClearSearch,
                            tint = ManagerPalette.OnDarkSurface,
                            modifier = Modifier.size(ManagerDimens.SmallIconSize),
                        )
                    }
                }
                trailingContent?.invoke()
            }
        },
    )
}

@Composable
private fun ProcessRow(
    process: ProcessViewData,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ManagerDimens.DirectoryRowHeight)
            .clickable(
                role = Role.Button,
                onClickLabel = ManagerText.SelectProcess,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = ManagerText.processDescription(process)
            }
            .padding(horizontal = ManagerDimens.ContentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = process.appName,
                color = ManagerPalette.OnDarkSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = ManagerText.processSubtitle(process),
                color = ManagerPalette.OnDarkSurfaceSecondary,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = ManagerIcons.ChevronRight,
            contentDescription = null,
            tint = ManagerPalette.OnDarkSurfaceSecondary,
            modifier = Modifier.size(ManagerDimens.SmallIconSize),
        )
    }
}

@Composable
private fun LoadingProcesses() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = ManagerPalette.OnDarkSurface,
            trackColor = ManagerPalette.DarkSurfaceDivider,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
private fun PickerMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(ManagerDimens.ContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = ManagerPalette.OnDarkSurfaceSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun FeedbackBanner(
    feedback: FeedbackViewData?,
    onDismiss: () -> Unit,
    includeNavigationBarPadding: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = feedback != null,
        enter = slideInVertically(
            animationSpec = tween(ManagerMotion.Standard),
            initialOffsetY = { it },
        ) + fadeIn(tween(ManagerMotion.Standard)),
        exit = slideOutVertically(
            animationSpec = tween(ManagerMotion.Standard),
            targetOffsetY = { it },
        ) + fadeOut(tween(ManagerMotion.Fast)),
        modifier = modifier,
    ) {
        if (feedback != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ManagerPalette.DarkSurface)
                    .then(
                        if (includeNavigationBarPadding) {
                            Modifier.navigationBarsPadding()
                        } else {
                            Modifier
                        },
                    )
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(start = ManagerDimens.ContentPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = feedback.message,
                    color = ManagerPalette.OnDarkSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(ManagerDimens.TouchTarget),
                ) {
                    Icon(
                        imageVector = ManagerIcons.Close,
                        contentDescription = ManagerText.DismissMessage,
                        tint = ManagerPalette.OnDarkSurface,
                        modifier = Modifier.size(ManagerDimens.SmallIconSize),
                    )
                }
            }
        }
    }
}
