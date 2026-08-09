package dev.ruri.il2cppmanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.hypot
import kotlinx.coroutines.delay

@Composable
fun ManagerScreen(
    state: ManagerUiState,
    onAction: (ManagerAction) -> Unit,
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ManagerTheme(darkTheme = darkTheme) {
        ManagerScreenContent(
            state = state,
            onAction = onAction,
            darkTheme = darkTheme,
            onDarkThemeChanged = onDarkThemeChanged,
            modifier = modifier,
        )
    }
}

@Composable
private fun ManagerScreenContent(
    state: ManagerUiState,
    onAction: (ManagerAction) -> Unit,
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit,
    modifier: Modifier,
) {
    var isMenuVisible by rememberSaveable { mutableStateOf(false) }
    var infoDestination by rememberSaveable {
        mutableStateOf<ManagerInfoDestination?>(null)
    }
    val isBrowserWorkspaceSelected = state.workspaceTabs.selectedCanvasId == null
    val browserPage = (state.content as? ManagerContent.Browser)
        ?.page
        ?.takeIf { isBrowserWorkspaceSelected }
    val searchablePage = browserPage
    val sessionKey = state.selectedProcess?.id.orEmpty()
    var retainedSearchablePage by remember(sessionKey) {
        mutableStateOf<BrowserPage?>(searchablePage)
    }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isBrowserSearchVisible = searchablePage != null && !state.processPicker.isVisible
    val searchDockPage = searchablePage ?: retainedSearchablePage
    val browserSearchDockHeight = searchDockPage?.searchDockHeight() ?: 0.dp
    val showWorkspaceSwitch = state.workspaceTabs.canvases.isNotEmpty() &&
        !state.processPicker.isVisible &&
        state.feedback == null
    val workspaceStateHolder = key(sessionKey) { rememberSaveableStateHolder() }
    val savedDestinations = key(sessionKey) { remember { SavedDestinationRegistry() } }
    val currentOnAction by rememberUpdatedState(onAction)
    val openExternalLink = rememberManagerExternalLinkOpener()

    SideEffect {
        searchablePage?.let { retainedSearchablePage = it }
    }
    LaunchedEffect(state.feedback?.id) {
        if (state.feedback == null) return@LaunchedEffect
        delay(ManagerMotion.FeedbackVisibilityMillis)
        currentOnAction(ManagerAction.DismissFeedback)
    }
    LaunchedEffect(state.workspaceTabs.selectedCanvasId) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    BackHandler(
        enabled = isMenuVisible || state.processPicker.isVisible || state.canNavigateBack,
    ) {
        when {
            isMenuVisible -> isMenuVisible = false
            state.processPicker.isVisible -> onAction(ManagerAction.DismissProcessPicker)
            state.workspaceTabs.selectedCanvasId != null -> {
                onAction(
                    ManagerAction.MethodCanvasBack(
                        canvasId = state.workspaceTabs.selectedCanvasId,
                    ),
                )
            }
            else -> onAction(ManagerAction.NavigateBack)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    isTraversalGroup = true
                    if (isMenuVisible || infoDestination != null) hideFromAccessibility()
                },
        ) {
            ManagerHeader(
                selectedProcess = state.selectedProcess,
                isProcessPickerVisible = state.processPicker.isVisible,
                onToggleProcessPicker = {
                    isMenuVisible = false
                    onAction(ManagerAction.ToggleProcessPicker)
                },
                onOpenMenu = {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    if (state.processPicker.isVisible) {
                        onAction(ManagerAction.DismissProcessPicker)
                    }
                    isMenuVisible = true
                },
                modifier = Modifier.semantics { traversalIndex = -1f },
            )
            WorkspaceContextBar(
                isBrowserWorkspaceSelected = isBrowserWorkspaceSelected,
                state = state,
                onAction = onAction,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        isTraversalGroup = true
                        traversalIndex = 1f
                    },
            ) {
                if (!state.processPicker.isVisible) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(ManagerDefaults.ContentLayer)
                            .semantics { traversalIndex = 1f },
                    ) {
                        ManagerBody(
                            content = state.content,
                            sessionKey = sessionKey,
                            stateHolder = workspaceStateHolder,
                            savedDestinations = savedDestinations,
                            retainedBrowserDestinationIds = state.breadcrumbs
                                .mapTo(mutableSetOf(), BreadcrumbViewData::id),
                            retainedCanvasIds = state.workspaceTabs.canvases
                                .mapTo(mutableSetOf(), CanvasTabViewData::id),
                            browserQuery = state.browserQuery,
                            browserSearchOptions = state.browserSearchOptions,
                            browserSearchDockHeight = browserSearchDockHeight,
                            navigationDirection = state.navigationDirection,
                            showWorkspaceSwitch = showWorkspaceSwitch,
                            onWorkspaceSwitch = {
                                onAction(ManagerAction.ToggleWorkspace)
                            },
                            onAction = onAction,
                        )
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = isBrowserSearchVisible,
                    enter = slideInVertically(tween(ManagerMotion.Standard)) { height -> height } +
                        fadeIn(tween(ManagerMotion.Standard)),
                    exit = slideOutVertically(tween(ManagerMotion.Fast)) { height -> height } +
                        fadeOut(tween(ManagerMotion.Fast)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(ManagerDefaults.SearchDockLayer)
                        .semantics {
                            traversalIndex = 0f
                            if (!isBrowserSearchVisible) hideFromAccessibility()
                        },
                ) {
                    searchDockPage?.let { page ->
                        BrowserSearchDock(
                            value = state.browserQuery,
                            options = state.browserSearchOptions,
                            placeholder = page.searchPlaceholder(state.browserSearchOptions.scope),
                            contextKey = "$sessionKey:${page.searchContextKey()}",
                            showScope = page.supportsSearchScope(),
                            onValueChanged = { query ->
                                onAction(ManagerAction.BrowserQueryChanged(query))
                            },
                            onScopeChanged = { scope ->
                                onAction(ManagerAction.BrowserSearchScopeChanged(scope))
                            },
                            onMatchModeChanged = { mode ->
                                onAction(ManagerAction.BrowserMatchModeChanged(mode))
                            },
                            onMatchCaseChanged = { matchCase ->
                                onAction(ManagerAction.BrowserMatchCaseChanged(matchCase))
                            },
                            modifier = Modifier
                                .navigationBarsPadding()
                                .imePadding(),
                        )
                    }
                }
                ProcessPickerOverlay(
                    state = state.processPicker,
                    onQueryChanged = { query ->
                        onAction(ManagerAction.ProcessQueryChanged(query))
                    },
                    onProcessSelected = { processId ->
                        onAction(ManagerAction.ProcessSelected(processId))
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(ManagerDefaults.ProcessPickerLayer),
                )
                FeedbackBanner(
                    feedback = state.feedback,
                    onDismiss = { onAction(ManagerAction.DismissFeedback) },
                    includeNavigationBarPadding = !isBrowserSearchVisible,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(ManagerDefaults.FeedbackLayer)
                        .then(
                            if (isBrowserSearchVisible) {
                                Modifier
                                    .windowInsetsPadding(
                                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                                    )
                                    .windowInsetsPadding(
                                        WindowInsets.ime.only(WindowInsetsSides.Bottom),
                                    )
                                    .padding(bottom = browserSearchDockHeight)
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
        ManagerDrawerOverlay(
            visible = isMenuVisible,
            darkTheme = darkTheme,
            onDarkThemeChanged = onDarkThemeChanged,
            onOpenInfo = { destination ->
                isMenuVisible = false
                infoDestination = destination
            },
            onOpenGitHub = {
                isMenuVisible = false
                openExternalLink(ManagerProjectLinks.GitHub)
            },
            onOpenTelegram = {
                isMenuVisible = false
                openExternalLink(ManagerProjectLinks.Telegram)
            },
            onDismiss = { isMenuVisible = false },
            modifier = Modifier.fillMaxSize(),
        )
        infoDestination?.let { destination ->
            ManagerInfoOverlay(
                destination = destination,
                onDestinationChanged = { infoDestination = it },
                onOpenExternalLink = openExternalLink,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(ManagerDefaults.InfoLayer),
            )
        }
    }

}

@Composable
private fun WorkspaceContextBar(
    isBrowserWorkspaceSelected: Boolean,
    state: ManagerUiState,
    onAction: (ManagerAction) -> Unit,
) {
    if (
        state.processPicker.isVisible ||
        (state.workspaceTabs.canvases.isEmpty() && state.breadcrumbs.size <= 1)
    ) {
        return
    }
    AnimatedContent(
        targetState = isBrowserWorkspaceSelected,
        contentKey = { browserSelected -> browserSelected },
        transitionSpec = { workspaceContextTransition() },
        label = "workspace context",
    ) { browserSelected ->
        when {
            browserSelected && state.breadcrumbs.size > 1 -> {
                BrowserBreadcrumbBar(
                    breadcrumbs = state.breadcrumbs,
                    onBreadcrumbSelected = { breadcrumbId ->
                        onAction(ManagerAction.BreadcrumbSelected(breadcrumbId))
                    },
                )
            }
            !browserSelected && state.workspaceTabs.canvases.isNotEmpty() -> {
                WorkspaceTabBar(
                    tabs = state.workspaceTabs,
                    onCanvasSelected = { canvasId ->
                        onAction(ManagerAction.CanvasTabSelected(canvasId))
                    },
                    onCanvasClosed = { canvasId ->
                        onAction(ManagerAction.CanvasTabClosed(canvasId))
                    },
                    onCloseAllCanvases = {
                        onAction(ManagerAction.CloseAllCanvasTabs)
                    },
                )
            }
        }
    }
}

@Composable
private fun ManagerBody(
    content: ManagerContent,
    sessionKey: String,
    stateHolder: SaveableStateHolder,
    savedDestinations: SavedDestinationRegistry,
    retainedBrowserDestinationIds: Set<String>,
    retainedCanvasIds: Set<String>,
    browserQuery: String,
    browserSearchOptions: BrowserSearchOptions,
    browserSearchDockHeight: Dp,
    navigationDirection: NavigationDirection,
    showWorkspaceSwitch: Boolean,
    onWorkspaceSwitch: () -> Unit,
    onAction: (ManagerAction) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val workspaceTransitionOffset = with(LocalDensity.current) {
        ManagerDimens.WorkspaceTransitionOffset.toPx()
    }
    val browserDockInsetsModifier = if (browserSearchDockHeight > 0.dp) {
        Modifier
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom))
    } else {
        Modifier
    }
    val currentBrowserContent = content as? ManagerContent.Browser
    val currentCanvasContent = content as? ManagerContent.Canvas
    var retainedBrowserContent by remember(sessionKey) {
        mutableStateOf(currentBrowserContent)
    }
    var retainedCanvasContent by remember(sessionKey) {
        mutableStateOf(currentCanvasContent)
    }
    val browserContent = currentBrowserContent ?: retainedBrowserContent
    val canvasContent = (currentCanvasContent ?: retainedCanvasContent)?.takeIf { candidate ->
        currentCanvasContent != null || candidate.canvasId in retainedCanvasIds
    }

    SideEffect {
        currentBrowserContent?.let { retainedBrowserContent = it }
        when {
            currentCanvasContent != null -> retainedCanvasContent = currentCanvasContent
            retainedCanvasContent
                ?.canvasId
                ?.let { canvasId -> canvasId !in retainedCanvasIds } == true ->
                retainedCanvasContent = null
        }
    }
    LaunchedEffect(
        stateHolder,
        retainedBrowserDestinationIds,
        retainedCanvasIds,
    ) {
        savedDestinations.prune(
            stateHolder = stateHolder,
            retainedBrowserDestinationIds = retainedBrowserDestinationIds,
            retainedCanvasIds = retainedCanvasIds,
        )
    }

    when (content) {
        is ManagerContent.Browser,
        is ManagerContent.Canvas,
        -> {
            val selectedWorkspace = if (content is ManagerContent.Browser) {
                WorkspaceLayer.BROWSER
            } else {
                WorkspaceLayer.CANVAS
            }
            val transition = updateTransition(
                targetState = selectedWorkspace,
                label = "workspace switch",
            )
            val browserAlpha = transition.animateFloat(
                transitionSpec = { tween(ManagerMotion.Fast) },
                label = "browser alpha",
            ) { workspace ->
                workspace.alphaFor(WorkspaceLayer.BROWSER)
            }
            val canvasAlpha = transition.animateFloat(
                transitionSpec = { tween(ManagerMotion.Fast) },
                label = "canvas alpha",
            ) { workspace ->
                workspace.alphaFor(WorkspaceLayer.CANVAS)
            }
            val horizontalDirection = if (layoutDirection == LayoutDirection.Ltr) {
                WorkspaceLayerDefaults.ForwardDirection
            } else {
                -WorkspaceLayerDefaults.ForwardDirection
            }
            val browserTranslationX = transition.animateFloat(
                transitionSpec = { tween(ManagerMotion.Fast) },
                label = "browser translation",
            ) { workspace ->
                if (workspace == WorkspaceLayer.BROWSER) {
                    WorkspaceLayerDefaults.RestingTranslation
                } else {
                    -workspaceTransitionOffset * horizontalDirection
                }
            }
            val canvasTranslationX = transition.animateFloat(
                transitionSpec = { tween(ManagerMotion.Fast) },
                label = "canvas translation",
            ) { workspace ->
                if (workspace == WorkspaceLayer.CANVAS) {
                    WorkspaceLayerDefaults.RestingTranslation
                } else {
                    workspaceTransitionOffset * horizontalDirection
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                browserContent?.let { targetContent ->
                    key(sessionKey, WorkspaceLayer.BROWSER) {
                        RetainedWorkspaceLayer(
                            isActive = selectedWorkspace == WorkspaceLayer.BROWSER,
                            alpha = browserAlpha,
                            translationX = browserTranslationX,
                        ) {
                            ManagerDestinationLayer(
                                content = targetContent,
                                sessionKey = sessionKey,
                                stateHolder = stateHolder,
                                savedDestinations = savedDestinations,
                                retainedBrowserDestinationIds =
                                    retainedBrowserDestinationIds,
                                retainedCanvasIds = retainedCanvasIds,
                                browserQuery = browserQuery,
                                browserSearchOptions = browserSearchOptions,
                                browserSearchDockHeight = browserSearchDockHeight,
                                modifier = browserDockInsetsModifier,
                                navigationDirection = navigationDirection,
                                layoutDirection = layoutDirection,
                                showWorkspaceSwitch = showWorkspaceSwitch,
                                onWorkspaceSwitch = onWorkspaceSwitch,
                                onAction = onAction,
                                label = "browser navigation",
                            )
                        }
                    }
                }
                canvasContent?.let { targetContent ->
                    key(sessionKey, WorkspaceLayer.CANVAS) {
                        RetainedWorkspaceLayer(
                            isActive = selectedWorkspace == WorkspaceLayer.CANVAS,
                            alpha = canvasAlpha,
                            translationX = canvasTranslationX,
                        ) {
                            ManagerDestinationLayer(
                                content = targetContent,
                                sessionKey = sessionKey,
                                stateHolder = stateHolder,
                                savedDestinations = savedDestinations,
                                retainedBrowserDestinationIds =
                                    retainedBrowserDestinationIds,
                                retainedCanvasIds = retainedCanvasIds,
                                browserQuery = browserQuery,
                                browserSearchOptions = browserSearchOptions,
                                browserSearchDockHeight = browserSearchDockHeight,
                                modifier = browserDockInsetsModifier,
                                navigationDirection = navigationDirection,
                                layoutDirection = layoutDirection,
                                showWorkspaceSwitch = showWorkspaceSwitch,
                                onWorkspaceSwitch = onWorkspaceSwitch,
                                onAction = onAction,
                                label = "canvas navigation",
                            )
                        }
                    }
                }
            }
        }
        ManagerContent.NoProcess,
        ManagerContent.Parsing,
        is ManagerContent.Failure,
        -> ManagerDestinationLayer(
            content = content,
            sessionKey = sessionKey,
            stateHolder = stateHolder,
            savedDestinations = savedDestinations,
            retainedBrowserDestinationIds = retainedBrowserDestinationIds,
            retainedCanvasIds = retainedCanvasIds,
            browserQuery = browserQuery,
            browserSearchOptions = browserSearchOptions,
            browserSearchDockHeight = browserSearchDockHeight,
            modifier = browserDockInsetsModifier,
            navigationDirection = navigationDirection,
            layoutDirection = layoutDirection,
            showWorkspaceSwitch = showWorkspaceSwitch,
            onWorkspaceSwitch = onWorkspaceSwitch,
            onAction = onAction,
            label = "manager state",
        )
    }
}

@Composable
private fun RetainedWorkspaceLayer(
    isActive: Boolean,
    alpha: State<Float>,
    translationX: State<Float>,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(
                if (isActive) {
                    WorkspaceLayerDefaults.ActiveZIndex
                } else {
                    WorkspaceLayerDefaults.InactiveZIndex
                },
            )
            .graphicsLayer {
                this.alpha = alpha.value
                this.translationX = translationX.value
            }
            .then(
                if (isActive) {
                    Modifier
                } else {
                    Modifier.focusProperties { canFocus = false }
                },
            )
            .semantics {
                if (!isActive) hideFromAccessibility()
            }
            .pointerInput(isActive) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!isActive) {
                            event.changes.forEach { change -> change.consume() }
                        }
                    }
                }
            },
    ) {
        content()
    }
}

@Composable
private fun ManagerDestinationLayer(
    content: ManagerContent,
    sessionKey: String,
    stateHolder: SaveableStateHolder,
    savedDestinations: SavedDestinationRegistry,
    retainedBrowserDestinationIds: Set<String>,
    retainedCanvasIds: Set<String>,
    browserQuery: String,
    browserSearchOptions: BrowserSearchOptions,
    browserSearchDockHeight: Dp,
    modifier: Modifier,
    navigationDirection: NavigationDirection,
    layoutDirection: LayoutDirection,
    showWorkspaceSwitch: Boolean,
    onWorkspaceSwitch: () -> Unit,
    onAction: (ManagerAction) -> Unit,
    label: String,
) {
    val latestContent by rememberUpdatedState(content)

    AnimatedContent(
        targetState = content,
        contentKey = ManagerContent::destinationKey,
        transitionSpec = {
            managerContentTransition(
                initialContent = initialState,
                targetContent = targetState,
                direction = navigationDirection,
                layoutDirection = layoutDirection,
            )
        },
        label = label,
        modifier = Modifier.fillMaxSize(),
    ) { targetContent ->
        val destinationContent = latestContent.takeIf { latest ->
            latest.destinationKey() == targetContent.destinationKey()
        } ?: targetContent
        val stateKey = sessionKey + ":" + targetContent.destinationKey()
        SideEffect {
            savedDestinations.register(
                content = destinationContent,
                stateKey = stateKey,
                retainedBrowserDestinationIds = retainedBrowserDestinationIds,
                retainedCanvasIds = retainedCanvasIds,
            )
        }
        stateHolder.SaveableStateProvider(stateKey) {
            ManagerDestinationContent(
                content = destinationContent,
                browserQuery = browserQuery,
                browserSearchOptions = browserSearchOptions,
                browserSearchDockHeight = browserSearchDockHeight,
                modifier = modifier,
                showWorkspaceSwitch = showWorkspaceSwitch,
                onWorkspaceSwitch = onWorkspaceSwitch,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun ManagerDestinationContent(
    content: ManagerContent,
    browserQuery: String,
    browserSearchOptions: BrowserSearchOptions,
    browserSearchDockHeight: Dp,
    modifier: Modifier,
    showWorkspaceSwitch: Boolean,
    onWorkspaceSwitch: () -> Unit,
    onAction: (ManagerAction) -> Unit,
) {
    when (content) {
        ManagerContent.NoProcess -> NoProcessState()
        ManagerContent.Parsing -> ParsingState()
        is ManagerContent.Failure -> FailureState(
            failure = content,
            onRetry = { onAction(ManagerAction.Retry) },
        )
        is ManagerContent.Browser -> Box(modifier = Modifier.fillMaxSize()) {
            BrowserPageContent(
                page = content.page,
                query = browserQuery,
                searchOptions = browserSearchOptions,
                onEntrySelected = { entryId ->
                    onAction(ManagerAction.BrowserEntrySelected(entryId))
                },
                onLoadMoreSearch = {
                    onAction(ManagerAction.LoadMoreSearch)
                },
                onTabSelected = { tab ->
                    onAction(ManagerAction.ClassTabSelected(tab))
                },
                onMethodSelected = { classIndex, methodIndex ->
                    onAction(ManagerAction.MethodSelected(classIndex, methodIndex))
                },
                onCopyMethodValue = { methodId, target ->
                    onAction(ManagerAction.CopyMethodValue(methodId, target))
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(modifier)
                    .padding(bottom = browserSearchDockHeight),
            )
            if (showWorkspaceSwitch) {
                WorkspaceSwitchButton(
                    browserVisible = true,
                    onClick = onWorkspaceSwitch,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .then(modifier)
                        .padding(
                            end = ManagerDimens.WorkspaceSwitchMargin,
                            bottom = browserSearchDockHeight +
                                ManagerDimens.WorkspaceSwitchMargin,
                        ),
                )
            }
        }
        is ManagerContent.Canvas -> MethodCanvasContent(
            canvasId = content.canvasId,
            page = content.page,
            showWorkspaceSwitch = showWorkspaceSwitch,
            onWorkspaceSwitch = onWorkspaceSwitch,
            onAction = onAction,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private enum class WorkspaceLayer {
    BROWSER,
    CANVAS,
    ;

    fun alphaFor(layer: WorkspaceLayer): Float =
        if (this == layer) {
            WorkspaceLayerDefaults.ActiveAlpha
        } else {
            WorkspaceLayerDefaults.InactiveAlpha
        }
}

private object WorkspaceLayerDefaults {
    const val ActiveAlpha = 1f
    const val InactiveAlpha = 0f
    const val ActiveZIndex = 1f
    const val InactiveZIndex = 0f
    const val RestingTranslation = 0f
    const val ForwardDirection = 1f
}

private class SavedDestinationRegistry {
    private val browserStateKeys = mutableMapOf<String, String>()
    private val canvasStateKeys = mutableMapOf<String, MutableSet<String>>()

    fun register(
        content: ManagerContent,
        stateKey: String,
        retainedBrowserDestinationIds: Set<String>,
        retainedCanvasIds: Set<String>,
    ) {
        when (content) {
            is ManagerContent.Browser -> {
                val destinationId = content.page.destinationId
                if (destinationId in retainedBrowserDestinationIds) {
                    browserStateKeys[destinationId] = stateKey
                }
            }
            is ManagerContent.Canvas -> if (content.canvasId in retainedCanvasIds) {
                canvasStateKeys
                    .getOrPut(content.canvasId, ::mutableSetOf)
                    .add(stateKey)
            }
            ManagerContent.NoProcess,
            ManagerContent.Parsing,
            is ManagerContent.Failure,
            -> Unit
        }
    }

    fun prune(
        stateHolder: SaveableStateHolder,
        retainedBrowserDestinationIds: Set<String>,
        retainedCanvasIds: Set<String>,
    ) {
        (browserStateKeys.keys - retainedBrowserDestinationIds).forEach { destinationId ->
            browserStateKeys.remove(destinationId)?.let(stateHolder::removeState)
        }
        (canvasStateKeys.keys - retainedCanvasIds).forEach { canvasId ->
            canvasStateKeys.remove(canvasId)?.forEach(stateHolder::removeState)
        }
    }
}

@Composable
private fun MethodCanvasContent(
    canvasId: String,
    page: MethodCanvasPage,
    showWorkspaceSwitch: Boolean,
    onWorkspaceSwitch: () -> Unit,
    onAction: (ManagerAction) -> Unit,
    modifier: Modifier,
) {
    when (page) {
        is MethodCanvasPage.Graph -> CallGraphPage(
            page = page,
            onNodeSelected = { nodeId ->
                onAction(
                    ManagerAction.CallGraphNodeSelected(
                        canvasId = canvasId,
                        nodeId = nodeId,
                    ),
                )
            },
            onNodeToggled = { nodeId, direction ->
                onAction(
                    ManagerAction.CallGraphNodeToggled(
                        canvasId = canvasId,
                        nodeId = nodeId,
                        direction = direction,
                    ),
                )
            },
            onNodeClosed = { nodeId ->
                onAction(
                    ManagerAction.CallGraphNodeClosed(
                        canvasId = canvasId,
                        nodeId = nodeId,
                    ),
                )
            },
            onNodeMoved = { nodeId, position ->
                onAction(
                    ManagerAction.CallGraphNodeMoved(
                        canvasId = canvasId,
                        nodeId = nodeId,
                        position = position,
                    ),
                )
            },
            onLayoutPositionsDiscovered = { positions ->
                onAction(
                    ManagerAction.CallGraphLayoutPositionsDiscovered(
                        canvasId = canvasId,
                        positions = positions,
                    ),
                )
            },
            onUndo = {
                onAction(ManagerAction.CallGraphUndo(canvasId))
            },
            onRedo = {
                onAction(ManagerAction.CallGraphRedo(canvasId))
            },
            onNodeInstructionsSelected = { nodeId ->
                onAction(
                    ManagerAction.CallGraphNodeInstructionsSelected(
                        canvasId = canvasId,
                        nodeId = nodeId,
                    ),
                )
            },
            onNodeCanvasSelected = { nodeId ->
                onAction(
                    ManagerAction.CallGraphNodeCanvasSelected(
                        canvasId = canvasId,
                        nodeId = nodeId,
                    ),
                )
            },
            onCopyNodeValue = { nodeId, target ->
                onAction(
                    ManagerAction.CopyCallGraphNodeValue(
                        canvasId = canvasId,
                        nodeId = nodeId,
                        target = target,
                    ),
                )
            },
            showWorkspaceSwitch = showWorkspaceSwitch,
            onWorkspaceSwitch = onWorkspaceSwitch,
            modifier = modifier,
        )
        is MethodCanvasPage.Instructions -> MethodCanvasInstructions(
            canvasId = canvasId,
            page = page,
            showWorkspaceSwitch = showWorkspaceSwitch,
            onWorkspaceSwitch = onWorkspaceSwitch,
            onAction = onAction,
            modifier = modifier,
        )
    }
}

@Composable
private fun MethodCanvasInstructions(
    canvasId: String,
    page: MethodCanvasPage.Instructions,
    showWorkspaceSwitch: Boolean,
    onWorkspaceSwitch: () -> Unit,
    onAction: (ManagerAction) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MethodCanvasDimens.BackRowHeight)
                .clickable(
                    role = Role.Button,
                    onClickLabel = MethodCanvasText.BackToCanvas,
                    onClick = {
                        onAction(ManagerAction.MethodCanvasBack(canvasId))
                    },
                )
                .padding(horizontal = ManagerDimens.ContentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ManagerIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(MethodCanvasDimens.BackIconSize)
                    .graphicsLayer(rotationZ = MethodCanvasDimens.BackIconRotation),
            )
            Text(
                text = MethodCanvasText.BackToCanvas,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = MethodCanvasDimens.BackLabelGap),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(modifier = Modifier.weight(1f)) {
            MethodInstructionsPage(
                page = page,
                onLoadMore = {
                    onAction(ManagerAction.LoadMoreInstructions(canvasId))
                },
                onLoadPrevious = {
                    onAction(ManagerAction.LoadPreviousInstructions(canvasId))
                },
                onInstructionSelected = { address ->
                    onAction(
                        ManagerAction.InstructionSelected(
                            canvasId = canvasId,
                            address = address,
                        ),
                    )
                },
                onInstructionTargetSelected = {
                        address,
                        firstVisibleItemIndex,
                        firstVisibleItemScrollOffset,
                    ->
                    onAction(
                        ManagerAction.InstructionTargetSelected(
                            canvasId = canvasId,
                            address = address,
                            firstVisibleItemIndex = firstVisibleItemIndex,
                            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
                        ),
                    )
                },
                onInstructionScrollConsumed = { requestId ->
                    onAction(
                        ManagerAction.InstructionScrollConsumed(
                            canvasId = canvasId,
                            requestId = requestId,
                        ),
                    )
                },
                onCopyInstruction = { address ->
                    onAction(
                        ManagerAction.CopyInstruction(
                            canvasId = canvasId,
                            address = address,
                        ),
                    )
                },
                additionalBottomPadding = if (showWorkspaceSwitch) {
                    ManagerDimens.WorkspaceSwitchClearance
                } else {
                    0.dp
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (showWorkspaceSwitch) {
                WorkspaceSwitchButton(
                    browserVisible = false,
                    onClick = onWorkspaceSwitch,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(ManagerDimens.WorkspaceSwitchMargin),
                )
            }
        }
    }
}

private fun BrowserPage.searchPlaceholder(scope: BrowserSearchScope): String = when (this) {
    is BrowserPage.Directory -> when (level) {
        DirectoryLevel.ASSEMBLIES -> if (scope == BrowserSearchScope.EVERYWHERE) {
            ManagerText.SearchEverywhere
        } else {
            ManagerText.SearchAssemblies
        }
        DirectoryLevel.NAMESPACES -> ManagerText.SearchAllClasses
        DirectoryLevel.CLASSES -> ManagerText.SearchClasses
    }
    is BrowserPage.SymbolSearch -> ManagerText.SearchEverywhere
    is BrowserPage.ClassDetails -> when (selectedTab) {
        ClassTab.FIELDS -> ManagerText.SearchFields
        ClassTab.METHODS -> ManagerText.SearchMethods
    }
}

private fun BrowserPage.supportsSearchScope(): Boolean = when (this) {
    is BrowserPage.Directory -> level == DirectoryLevel.ASSEMBLIES
    is BrowserPage.SymbolSearch -> true
    is BrowserPage.ClassDetails -> false
}

private fun BrowserPage.searchDockHeight(): Dp =
    ManagerDimens.DividerThickness +
        ManagerDimens.SearchBarHeight +
        if (supportsSearchScope()) {
            ManagerDimens.SearchScopeHeight + ManagerDimens.DividerThickness
        } else {
            0.dp
        }

private fun BrowserPage.searchContextKey(): String = when (this) {
    is BrowserPage.Directory -> destinationId
    is BrowserPage.SymbolSearch -> destinationId
    is BrowserPage.ClassDetails -> "${destinationId}:${selectedTab.name}"
}

@Composable
private fun NoProcessState() {
    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        NoProcessArrow(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = ManagerDimens.ContentPadding)
                .widthIn(max = ManagerDimens.EmptyStateMaxWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = ManagerText.NoProcessSelected,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(modifier = Modifier.height(ManagerDimens.EmptyStateTitleGap))
            Text(
                text = ManagerText.NoProcessDescription,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NoProcessArrow(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
        alpha = NoProcessArrowSpec.ColorAlpha,
    )
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    Canvas(modifier = modifier) {
        fun horizontal(fraction: Float): Float = if (leftToRight) {
            size.width * fraction
        } else {
            size.width * (1f - fraction)
        }
        fun point(position: Offset) = Offset(
            horizontal(position.x),
            size.height * position.y,
        )
        fun Path.curveTo(
            controlOne: Offset,
            controlTwo: Offset,
            destination: Offset,
        ) {
            cubicTo(
                controlOne.x,
                controlOne.y,
                controlTwo.x,
                controlTwo.y,
                destination.x,
                destination.y,
            )
        }

        val start = point(NoProcessArrowSpec.Start)
        val end = Offset(
            horizontal(NoProcessArrowSpec.EndXFraction),
            NoProcessArrowSpec.EndY.toPx(),
        )
        val finalControl = point(NoProcessArrowSpec.FinalControl)
        val curve = Path().apply {
            moveTo(start.x, start.y)
            val bendEntry = point(NoProcessArrowSpec.BendEntry)
            curveTo(
                point(NoProcessArrowSpec.TailControlOne),
                point(NoProcessArrowSpec.TailControlTwo),
                bendEntry,
            )
            val bendExit = point(NoProcessArrowSpec.BendExit)
            curveTo(
                point(NoProcessArrowSpec.BendControlOne),
                point(NoProcessArrowSpec.BendControlTwo),
                bendExit,
            )
            val sweepEnd = point(NoProcessArrowSpec.SweepEnd)
            curveTo(
                point(NoProcessArrowSpec.SweepControlOne),
                point(NoProcessArrowSpec.SweepControlTwo),
                sweepEnd,
            )
            curveTo(
                point(NoProcessArrowSpec.FinalSweepControl),
                finalControl,
                end,
            )
        }
        val stroke = Stroke(
            width = NoProcessArrowSpec.StrokeWidth.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        drawPath(path = curve, color = color, style = stroke)

        val directionX = end.x - finalControl.x
        val directionY = end.y - finalControl.y
        val directionLength = hypot(directionX, directionY)
        val unitX = directionX / directionLength
        val unitY = directionY / directionLength
        val headLength = NoProcessArrowSpec.ArrowHeadLength.toPx()
        val headHalfWidth = NoProcessArrowSpec.ArrowHeadHalfWidth.toPx()
        val base = Offset(
            end.x - unitX * headLength,
            end.y - unitY * headLength,
        )
        val normalX = -unitY
        val normalY = unitX
        val arrowHead = Path().apply {
            moveTo(
                base.x + normalX * headHalfWidth,
                base.y + normalY * headHalfWidth,
            )
            lineTo(end.x, end.y)
            lineTo(
                base.x - normalX * headHalfWidth,
                base.y - normalY * headHalfWidth,
            )
        }
        drawPath(path = arrowHead, color = color, style = stroke)
    }
}

private object NoProcessArrowSpec {
    val Start = Offset(0.50f, 0.45f)
    val TailControlOne = Offset(0.47f, 0.43f)
    val TailControlTwo = Offset(0.43f, 0.37f)
    val BendEntry = Offset(0.43f, 0.33f)
    val BendControlOne = Offset(0.43f, 0.30f)
    val BendControlTwo = Offset(0.45f, 0.28f)
    val BendExit = Offset(0.49f, 0.27f)
    val SweepControlOne = Offset(0.56f, 0.25f)
    val SweepControlTwo = Offset(0.64f, 0.22f)
    val SweepEnd = Offset(0.72f, 0.17f)
    val FinalSweepControl = Offset(0.80f, 0.12f)
    val FinalControl = Offset(0.85f, 0.05f)
    const val EndXFraction = 0.88f
    const val ColorAlpha = 0.42f
    val EndY = 8.dp
    val StrokeWidth = 2.dp
    val ArrowHeadLength = 18.dp
    val ArrowHeadHalfWidth = 8.dp
}

@Composable
private fun ParsingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = ManagerText.ParsingMetadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun FailureState(
    failure: ManagerContent.Failure,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(ManagerDimens.ContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = failure.message,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            if (failure.detail != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = failure.detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (failure.canRetry) {
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onRetry,
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp,
                        focusedElevation = 0.dp,
                        hoveredElevation = 0.dp,
                        disabledElevation = 0.dp,
                    ),
                    modifier = Modifier.heightIn(min = ManagerDimens.TouchTarget),
                ) {
                    Text(ManagerText.Retry)
                }
            }
        }
    }
}

private fun destinationTransition(
    direction: NavigationDirection,
    layoutDirection: LayoutDirection,
): ContentTransform {
    val sign = if (layoutDirection == LayoutDirection.Ltr) 1 else -1
    return when (direction) {
        NavigationDirection.FORWARD -> {
            (slideInHorizontally(tween(ManagerMotion.Slow)) { width -> width * sign } +
                fadeIn(tween(ManagerMotion.Standard))) togetherWith
                (slideOutHorizontally(tween(ManagerMotion.Slow)) { width -> -width * sign / 3 } +
                    fadeOut(tween(ManagerMotion.Fast)))
        }
        NavigationDirection.BACKWARD -> {
            (slideInHorizontally(tween(ManagerMotion.Slow)) { width -> -width * sign / 3 } +
                fadeIn(tween(ManagerMotion.Standard))) togetherWith
                (slideOutHorizontally(tween(ManagerMotion.Slow)) { width -> width * sign } +
                    fadeOut(tween(ManagerMotion.Fast)))
        }
        NavigationDirection.NONE ->
            fadeIn(tween(ManagerMotion.Standard)) togetherWith fadeOut(tween(ManagerMotion.Fast))
    }
}

private fun managerContentTransition(
    initialContent: ManagerContent,
    targetContent: ManagerContent,
    direction: NavigationDirection,
    layoutDirection: LayoutDirection,
): ContentTransform = when {
    initialContent is ManagerContent.Browser && targetContent is ManagerContent.Browser ->
        destinationTransition(direction, layoutDirection)
    initialContent is ManagerContent.Canvas &&
        targetContent is ManagerContent.Canvas &&
        initialContent.canvasId == targetContent.canvasId ->
        destinationTransition(direction, layoutDirection)
    else -> ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = ExitTransition.None,
        sizeTransform = null,
    )
}

private fun workspaceContextTransition(): ContentTransform = ContentTransform(
    targetContentEnter = fadeIn(tween(ManagerMotion.Fast)),
    initialContentExit = fadeOut(tween(ManagerMotion.Fast)),
    sizeTransform = null,
)

private fun ManagerContent.destinationKey(): String = when (this) {
    ManagerContent.NoProcess -> "no-process"
    ManagerContent.Parsing -> "parsing"
    is ManagerContent.Failure -> "failure"
    is ManagerContent.Browser -> "browser:${page.destinationId}"
    is ManagerContent.Canvas -> "canvas:$canvasId:${page.destinationId}"
}

private object MethodCanvasDimens {
    val BackRowHeight = 48.dp
    val BackIconSize = 20.dp
    val BackLabelGap = 8.dp
    const val BackIconRotation = 180f
}

private object MethodCanvasText {
    const val BackToCanvas = "Back to canvas"
}
