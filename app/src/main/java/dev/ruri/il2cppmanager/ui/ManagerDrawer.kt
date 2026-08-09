package dev.ruri.il2cppmanager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection

@Composable
internal fun ManagerDrawerOverlay(
    visible: Boolean,
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit,
    onOpenInfo: (ManagerInfoDestination) -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenTelegram: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    val closeFocusRequester = remember { FocusRequester() }
    val slideFromEnd: (Int) -> Int = { width ->
        if (layoutDirection == LayoutDirection.Ltr) width else -width
    }
    LaunchedEffect(visible) {
        if (visible) closeFocusRequester.requestFocus()
    }

    BoxWithConstraints(modifier = modifier) {
        val drawerWidth = minOf(
            maxWidth * DrawerWidthFraction,
            ManagerDimens.DrawerMaxWidth,
        )
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(ManagerMotion.Standard)),
            exit = fadeOut(tween(ManagerMotion.Fast)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = DrawerScrimAlpha))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = ManagerText.CloseMenu,
                        onClick = onDismiss,
                    )
                    .semantics { contentDescription = ManagerText.CloseMenu },
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(
                animationSpec = tween(ManagerMotion.Slow),
                initialOffsetX = slideFromEnd,
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(ManagerMotion.Standard),
                targetOffsetX = slideFromEnd,
            ),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            DrawerPanel(
                darkTheme = darkTheme,
                onDarkThemeChanged = onDarkThemeChanged,
                onOpenInfo = onOpenInfo,
                onOpenGitHub = onOpenGitHub,
                onOpenTelegram = onOpenTelegram,
                onDismiss = onDismiss,
                closeFocusRequester = closeFocusRequester,
                modifier = Modifier.width(drawerWidth),
            )
        }
    }
}

@Composable
private fun DrawerPanel(
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit,
    onOpenInfo: (ManagerInfoDestination) -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenTelegram: () -> Unit,
    onDismiss: () -> Unit,
    closeFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .focusGroup()
            .semantics {
                paneTitle = ManagerText.AppMenu
                isTraversalGroup = true
            },
    ) {
        DrawerHeader(
            darkTheme = darkTheme,
            onDarkThemeChanged = onDarkThemeChanged,
            onDismiss = onDismiss,
            closeFocusRequester = closeFocusRequester,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            DrawerItem(
                icon = ManagerIcons.Info,
                trailingIcon = ManagerIcons.ChevronRight,
                title = ManagerText.About,
                subtitle = ManagerText.AboutMenuDescription,
                onClick = { onOpenInfo(ManagerInfoDestination.About) },
            )
            DrawerItem(
                icon = ManagerIcons.Code,
                trailingIcon = ManagerIcons.ExternalLink,
                title = ManagerText.OpenSource,
                subtitle = ManagerText.OpenSourceDescription,
                onClick = onOpenGitHub,
            )
            DrawerItem(
                icon = ManagerIcons.Send,
                trailingIcon = ManagerIcons.ExternalLink,
                title = ManagerText.TelegramChannel,
                subtitle = ManagerText.TelegramDescription,
                onClick = onOpenTelegram,
            )
            HorizontalDivider(
                thickness = ManagerDimens.DividerThickness,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = ManagerDimens.ContentPadding),
            )
            DrawerItem(
                icon = ManagerIcons.Credits,
                trailingIcon = ManagerIcons.ChevronRight,
                title = ManagerText.Credits,
                subtitle = ManagerText.CreditsDescription,
                onClick = { onOpenInfo(ManagerInfoDestination.Credits) },
            )
            DrawerItem(
                icon = ManagerIcons.License,
                trailingIcon = ManagerIcons.ChevronRight,
                title = ManagerText.Licenses,
                subtitle = ManagerText.LicensesDescription,
                onClick = { onOpenInfo(ManagerInfoDestination.Licenses) },
            )
        }
    }
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    trailingIcon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ManagerDimens.DrawerRowHeight)
            .clickable(
                role = Role.Button,
                onClickLabel = title,
                onClick = onClick,
            )
            .padding(
                horizontal = ManagerDimens.ContentPadding,
                vertical = ManagerDimens.InfoLineGap,
            )
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(end = ManagerDimens.DrawerIconGap)
                .size(ManagerDimens.IconSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = trailingIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ManagerDimens.SmallIconSize),
        )
    }
}

@Composable
private fun DrawerHeader(
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    closeFocusRequester: FocusRequester,
) {
    Row(
        modifier = Modifier
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
        Text(
            text = ManagerText.AppName,
            color = ManagerPalette.OnDarkSurface,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(modifier = Modifier.selectableGroup()) {
            ThemeOption(
                icon = ManagerIcons.Sun,
                label = ManagerText.LightTheme,
                selected = !darkTheme,
                onClick = { onDarkThemeChanged(false) },
            )
            ThemeOption(
                icon = ManagerIcons.Moon,
                label = ManagerText.DarkTheme,
                selected = darkTheme,
                onClick = { onDarkThemeChanged(true) },
            )
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .focusRequester(closeFocusRequester)
                .size(ManagerDimens.TouchTarget),
        ) {
            Icon(
                imageVector = ManagerIcons.Close,
                contentDescription = ManagerText.CloseMenu,
                tint = ManagerPalette.OnDarkSurface,
                modifier = Modifier.size(ManagerDimens.UtilityIconSize),
            )
        }
    }
}

@Composable
private fun ThemeOption(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(ManagerDimens.TouchTarget)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        MonochromeToggleVisual(icon = icon, selected = selected)
    }
}

private const val DrawerWidthFraction = 0.76f
private const val DrawerScrimAlpha = 0.46f
