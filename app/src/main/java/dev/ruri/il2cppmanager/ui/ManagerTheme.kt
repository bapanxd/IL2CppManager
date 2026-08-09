package dev.ruri.il2cppmanager.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object ManagerPalette {
    val DarkSurface = Color(0xFF1A1A1A)
    val OnDarkSurface = Color(0xFFE2E2E4)
    val OnDarkSurfaceSecondary = Color(0xFFB8B8B8)
    val OnDarkSurfaceTertiary = Color(0xFF8E8E93)
    val DarkSurfaceDivider = Color(0xFF353535)
    val WorkspaceTabSelected = Color(0xFF202020)
}

internal object ManagerDimens {
    val HeaderHeight = 64.dp
    val BreadcrumbHeight = 48.dp
    val BreadcrumbHorizontalPadding = 8.dp
    val WorkspaceTabHeight = 48.dp
    val WorkspaceCanvasTabMinWidth = 144.dp
    val WorkspaceCanvasTabMaxWidth = 176.dp
    val WorkspaceCanvasLabelMinWidth = 96.dp
    val WorkspaceCanvasLabelMaxWidth = 128.dp
    val WorkspaceTabIndicatorThickness = 2.dp
    val WorkspaceTabHorizontalPadding = 12.dp
    val WorkspaceBusyIndicatorSize = 12.dp
    val WorkspaceBusyIndicatorStroke = 1.5.dp
    val WorkspaceBusyIndicatorGap = 8.dp
    val WorkspaceOverflowMenuWidth = 280.dp
    val WorkspaceMenuHorizontalPadding = 12.dp
    val WorkspaceMenuItemMinHeight = 56.dp
    val WorkspaceMenuOuterPadding = 6.dp
    val WorkspaceMenuItemVerticalPadding = 2.dp
    val WorkspaceMenuDividerHorizontalPadding = 12.dp
    val WorkspaceMenuDividerVerticalPadding = 4.dp
    val WorkspaceMenuCornerRadius = 12.dp
    val WorkspaceMenuItemCornerRadius = 8.dp
    val WorkspaceMenuElevation = 8.dp
    val WorkspaceSwitchMargin = 16.dp
    val WorkspaceTransitionOffset = 12.dp
    val WorkspaceSwitchCornerRadius = 14.dp
    val WorkspaceSwitchIconSize = 18.dp
    val WorkspaceSwitchHorizontalPadding = 14.dp
    val WorkspaceSwitchContentGap = 8.dp
    val WorkspaceSwitchClearance = 80.dp
    val DirectoryRowHeight = 68.dp
    val TabHeight = 52.dp
    val SearchBarHeight = 52.dp
    val SearchOptionIndicatorThickness = 2.dp
    val SearchScopeHeight = 48.dp
    val SearchScopeOptionPadding = 16.dp
    val SearchModeIndicatorWidth = 12.dp
    val SearchModeIndicatorBottomPadding = 5.dp
    val SearchUtilityDividerHeight = 20.dp
    val SearchUtilityDividerGap = 2.dp
    val SearchIconGap = 12.dp
    val TouchTarget = 48.dp
    val ContentPadding = 20.dp
    val IconSize = 24.dp
    val UtilityIconSize = 20.dp
    val SmallIconSize = 18.dp
    val DividerThickness = 1.dp
    val DrawerMaxWidth = 480.dp
    val DrawerRowHeight = 64.dp
    val DrawerIconGap = 16.dp
    val InfoRowMinHeight = 68.dp
    val InfoSectionGap = 24.dp
    val InfoItemGap = 16.dp
    val InfoLineGap = 4.dp
    val HeaderEndPadding = 8.dp
    val EmptyStateMaxWidth = 320.dp
    val EmptyStateTitleGap = 8.dp
}

internal object ManagerDefaults {
    const val WorkspaceMenuBorderAlpha = 0.72f
    const val WorkspaceMenuSelectionAlpha = 0.72f
    const val FloatingControlBackgroundAlpha = 0.92f
    const val WorkspaceSwitchBorderAlpha = 0.78f
    const val ContentLayer = 0f
    const val SearchDockLayer = 1f
    const val FeedbackLayer = 2f
    const val ProcessPickerLayer = 3f
    const val InfoLayer = 4f
}

internal object ManagerMotion {
    const val Fast = 140
    const val Standard = 220
    const val Slow = 280
    const val FeedbackVisibilityMillis = 2_500L
}

internal object ManagerText {
    const val AppName = "IL2CppManager"
    const val SelectProcess = "Select Process"
    const val NoProcessSelected = "No process selected"
    const val NoProcessDescription =
        "Run a Unity IL2CPP game, then select its process from the header above."
    const val SearchProcesses = "Search processes"
    const val SearchAssemblies = "Search assemblies"
    const val SearchEverywhere = "Search everywhere"
    const val SearchAllClasses = "Search all classes"
    const val SearchClasses = "Search classes"
    const val SearchFields = "Search fields"
    const val SearchMethods = "Search methods"
    const val ClearSearch = "Clear search"
    const val CurrentLevel = "Current level"
    const val Everywhere = "Everywhere"
    const val CurrentLevelTab = "CURRENT LEVEL"
    const val EverywhereTab = "EVERYWHERE"
    const val ExactMatch = "Exact match"
    const val MatchCase = "Match case"
    const val AppMenu = "App menu"
    const val OpenMenu = "Open menu"
    const val CloseMenu = "Close menu"
    const val LightTheme = "Light theme"
    const val DarkTheme = "Dark theme"
    const val About = "About"
    const val AboutMenuDescription = "Project information"
    const val AboutDescription =
        "Browse IL2CPP metadata and explore method relationships on rooted Android devices."
    const val GitHub = "GitHub"
    const val GitHubDescription = "Project source code"
    const val OpenSource = "Open source"
    const val OpenSourceDescription = "View project on GitHub"
    const val TelegramChannel = "Telegram channel"
    const val TelegramDescription = "Updates and support"
    const val Developer = "Developer"
    const val DeveloperDescription = "@bapanff"
    const val ProjectLinks = "Project links"
    const val Credits = "Credits"
    const val CreditsDescription = "Capstone and libsu"
    const val Licenses = "Licenses"
    const val LicensesDescription = "Open-source license texts"
    const val Capstone = "Capstone"
    const val CapstoneDescription = "Disassembly engine"
    const val CapstoneAuthor = "Nguyen Anh Quynh / COSEINC"
    const val Libsu = "libsu"
    const val LibsuDescription = "Root shell and root service framework"
    const val LibsuAuthor = "topjohnwu"
    const val ApacheLicense = "Apache License 2.0"
    const val CapstoneBsdLicense = "BSD 3-Clause License"
    const val LinkUnavailable = "No app is available to open this link."
    const val LicenseUnavailable = "The bundled license text could not be read."
    const val UnknownVersion = "Unknown version"
    const val Back = "Back"
    const val NoRunningProcesses = "No running processes found."
    const val NoMatchingProcesses = "No matching processes."
    const val ParsingMetadata = "Parsing IL2CPP metadata..."
    const val Retry = "Retry"
    const val Fields = "Fields"
    const val Methods = "Methods"
    const val NoAssemblies = "No assemblies found."
    const val NoNamespacesOrClasses = "No namespaces or classes found."
    const val NoClasses = "No classes found."
    const val NoMatchingItems = "No matching items."
    const val NoMatchingClasses = "No classes match this search."
    const val SearchingAllClasses = "Searching all classes..."
    const val SearchingEverywhere = "Searching everywhere..."
    const val NoMatchingSymbols = "No classes, fields, or methods match this search."
    const val TapToRetry = "Tap to retry."
    const val DismissMessage = "Dismiss message"
    const val OpenProcessPicker = "Open process picker"
    const val CloseProcessPicker = "Close process picker"
    const val OpenFolder = "Open folder"
    const val ProcessIdPrefix = "PID"
    const val WorkspaceOverflowSymbol = "\u22EF"
    const val OpenCanvasMenu = "Open canvas tabs menu"
    const val CloseAllTabs = "Close all tabs"
    const val SwitchWorkspace = "Switch"
    const val SwitchToBrowser = "Switch to browser"
    const val SwitchToCanvas = "Switch to canvas"
    const val CurrentLocation = "current location"

    fun processSubtitle(process: ProcessViewData): String =
        "${process.processName} · $ProcessIdPrefix ${process.pid}"

    fun processDescription(process: ProcessViewData): String =
        "${process.appName}, ${process.processName}, $ProcessIdPrefix ${process.pid}"

    fun folderDescription(entry: BrowserEntryViewData): String =
        "${entry.label}, ${entry.kind.name.lowercase()} folder"

    fun copyAddressAction(prefix: String): String = "Copy $prefix"

    fun openSearchResultDescription(result: SymbolSearchViewData): String =
        "Open ${result.kind.name.lowercase()} ${result.name} in ${result.ownerName}"

    fun canvasTabDescription(tab: CanvasTabViewData): String = buildString {
        append(tab.methodName)
        append(", ")
        append(tab.ownerName)
        append(", canvas tab")
        if (tab.isBusy) append(", loading")
    }

    fun closeCanvasDescription(tab: CanvasTabViewData): String =
        "Close ${tab.methodName} in ${tab.ownerName}"

    fun switchWorkspaceDescription(browserVisible: Boolean): String =
        if (browserVisible) SwitchToCanvas else SwitchToBrowser

    fun versionLabel(version: String): String = "Version $version"

    fun currentBreadcrumbDescription(breadcrumb: BreadcrumbViewData): String =
        "${breadcrumb.label}, $CurrentLocation"
}

private val LightManagerColorScheme = lightColorScheme(
    primary = Color(0xFF1A1A1A),
    onPrimary = Color(0xFFF8F8F8),
    primaryContainer = Color(0xFF1A1A1A),
    onPrimaryContainer = Color(0xFFF8F8F8),
    secondary = Color(0xFF6C6C70),
    onSecondary = Color(0xFFF8F8F8),
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = Color(0xFF1A1A1A),
    onTertiary = Color(0xFFF8F8F8),
    background = Color(0xFFF2F2F2),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFF2F2F2),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF6C6C70),
    outline = Color(0xFF6C6C70),
    outlineVariant = Color(0xFFDEDEDE),
    error = Color(0xFF1A1A1A),
    onError = Color(0xFFF8F8F8),
    scrim = Color.Black,
)

private val DarkManagerColorScheme = darkColorScheme(
    primary = ManagerPalette.OnDarkSurface,
    onPrimary = Color(0xFF1A1A1A),
    primaryContainer = Color(0xFF343436),
    onPrimaryContainer = ManagerPalette.OnDarkSurface,
    secondary = Color(0xFFA8A8AC),
    onSecondary = Color(0xFF1A1A1A),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = ManagerPalette.OnDarkSurface,
    tertiary = ManagerPalette.OnDarkSurface,
    onTertiary = Color(0xFF1A1A1A),
    background = Color(0xFF242426),
    onBackground = ManagerPalette.OnDarkSurface,
    surface = Color(0xFF242426),
    onSurface = ManagerPalette.OnDarkSurface,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFA8A8AC),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFF3A3A3C),
    error = ManagerPalette.OnDarkSurface,
    onError = Color(0xFF1A1A1A),
    scrim = Color.Black,
)

private val ManagerTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 19.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
    ),
)

private val ManagerShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

@Composable
fun ManagerTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkManagerColorScheme else LightManagerColorScheme,
        typography = ManagerTypography,
        shapes = ManagerShapes,
        content = content,
    )
}
