package dev.ruri.il2cppmanager.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow

internal enum class ManagerInfoDestination(
    val title: String,
) {
    About(ManagerText.About),
    Credits(ManagerText.Credits),
    Licenses(ManagerText.Licenses),
    ApacheLicense(ManagerText.ApacheLicense),
    CapstoneLicense(ManagerText.CapstoneBsdLicense),
}

internal object ManagerProjectLinks {
    const val GitHub = "https://github.com/bapanxd/IL2CppManager"
    const val Telegram = "https://t.me/il2cppmanager"
    const val Developer = "https://t.me/bapanff"
}

@Composable
internal fun rememberManagerVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching { context.managerVersionName() }
            .getOrDefault(ManagerText.UnknownVersion)
            .removeSuffix(DebugVersionSuffix)
            .ifBlank { ManagerText.UnknownVersion }
    }
}

@Composable
internal fun rememberManagerExternalLinkOpener(): (String) -> Unit {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    return remember(context, uriHandler) {
        { url ->
            runCatching { uriHandler.openUri(url) }
                .onFailure {
                    Toast.makeText(
                        context.applicationContext,
                        ManagerText.LinkUnavailable,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }
}

@Composable
internal fun ManagerInfoOverlay(
    destination: ManagerInfoDestination,
    onDestinationChanged: (ManagerInfoDestination?) -> Unit,
    onOpenExternalLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigateBack = {
        onDestinationChanged(
            when (destination) {
                ManagerInfoDestination.ApacheLicense,
                ManagerInfoDestination.CapstoneLicense
                -> ManagerInfoDestination.Licenses

                else -> null
            },
        )
    }

    BackHandler(onBack = navigateBack)

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .blockTouchesBehind()
            .semantics { paneTitle = destination.title },
    ) {
        ManagerInfoHeader(
            title = destination.title,
            onBack = navigateBack,
        )
        Box(modifier = Modifier.weight(1f)) {
            when (destination) {
                ManagerInfoDestination.About -> AboutPage(
                    onOpenExternalLink = onOpenExternalLink,
                )

                ManagerInfoDestination.Credits -> CreditsPage()
                ManagerInfoDestination.Licenses -> LicensesPage(
                    onDestinationChanged = onDestinationChanged,
                )

                ManagerInfoDestination.ApacheLicense -> LicenseTextPage(
                    assetPath = ApacheLicenseAssetPath,
                )

                ManagerInfoDestination.CapstoneLicense -> LicenseTextPage(
                    assetPath = CapstoneLicenseAssetPath,
                )
            }
        }
    }
}

@Composable
private fun ManagerInfoHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ManagerPalette.DarkSurface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(ManagerDimens.HeaderHeight)
            .padding(end = ManagerDimens.HeaderEndPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(ManagerDimens.TouchTarget),
        ) {
            Icon(
                imageVector = ManagerIcons.Back,
                contentDescription = ManagerText.Back,
                tint = ManagerPalette.OnDarkSurface,
                modifier = Modifier.size(ManagerDimens.UtilityIconSize),
            )
        }
        Text(
            text = title,
            color = ManagerPalette.OnDarkSurface,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AboutPage(
    onOpenExternalLink: (String) -> Unit,
) {
    val version = rememberManagerVersionName()

    InfoPageColumn {
        Text(
            text = ManagerText.AppName,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(ManagerDimens.InfoLineGap))
        Text(
            text = ManagerText.versionLabel(version),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(ManagerDimens.InfoItemGap))
        Text(
            text = ManagerText.AboutDescription,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(ManagerDimens.InfoSectionGap))
        SectionHeading(text = ManagerText.ProjectLinks)
        Spacer(modifier = Modifier.height(ManagerDimens.InfoLineGap))
        InfoActionRow(
            icon = ManagerIcons.Code,
            title = ManagerText.GitHub,
            subtitle = ManagerText.GitHubDescription,
            trailingIcon = ManagerIcons.ExternalLink,
            onClick = { onOpenExternalLink(ManagerProjectLinks.GitHub) },
        )
        InfoActionRow(
            icon = ManagerIcons.Send,
            title = ManagerText.TelegramChannel,
            subtitle = ManagerText.TelegramDescription,
            trailingIcon = ManagerIcons.ExternalLink,
            onClick = { onOpenExternalLink(ManagerProjectLinks.Telegram) },
        )
        InfoActionRow(
            icon = ManagerIcons.Info,
            title = ManagerText.Developer,
            subtitle = ManagerText.DeveloperDescription,
            trailingIcon = ManagerIcons.ExternalLink,
            onClick = { onOpenExternalLink(ManagerProjectLinks.Developer) },
        )
    }
}

@Composable
private fun CreditsPage() {
    InfoPageColumn {
        CreditEntry(
            icon = ManagerIcons.Credits,
            title = ManagerText.Capstone,
            description = ManagerText.CapstoneDescription,
            author = ManagerText.CapstoneAuthor,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        CreditEntry(
            icon = ManagerIcons.Code,
            title = ManagerText.Libsu,
            description = ManagerText.LibsuDescription,
            author = ManagerText.LibsuAuthor,
        )
    }
}

@Composable
private fun LicensesPage(
    onDestinationChanged: (ManagerInfoDestination?) -> Unit,
) {
    InfoPageColumn {
        InfoActionRow(
            icon = ManagerIcons.License,
            title = ManagerText.AppName,
            subtitle = ManagerText.ApacheLicense,
            trailingIcon = ManagerIcons.ChevronRight,
            onClick = {
                onDestinationChanged(ManagerInfoDestination.ApacheLicense)
            },
        )
        InfoActionRow(
            icon = ManagerIcons.License,
            title = ManagerText.Capstone,
            subtitle = ManagerText.CapstoneBsdLicense,
            trailingIcon = ManagerIcons.ChevronRight,
            onClick = {
                onDestinationChanged(ManagerInfoDestination.CapstoneLicense)
            },
        )
        InfoActionRow(
            icon = ManagerIcons.License,
            title = ManagerText.Libsu,
            subtitle = ManagerText.ApacheLicense,
            trailingIcon = ManagerIcons.ChevronRight,
            onClick = {
                onDestinationChanged(ManagerInfoDestination.ApacheLicense)
            },
        )
    }
}

@Composable
private fun LicenseTextPage(
    assetPath: String,
) {
    val context = LocalContext.current
    val licenseText = remember(context, assetPath) {
        context.readAssetText(assetPath)
            .getOrElse { ManagerText.LicenseUnavailable }
    }

    SelectionContainer {
        Text(
            text = licenseText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(ManagerDimens.ContentPadding),
        )
    }
}

@Composable
private fun InfoPageColumn(
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(ManagerDimens.ContentPadding),
        content = content,
    )
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun InfoActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailingIcon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ManagerDimens.InfoRowMinHeight)
            .clickable(
                role = Role.Button,
                onClickLabel = title,
                onClick = onClick,
            )
            .padding(vertical = ManagerDimens.InfoLineGap)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfoLeadingIcon(icon = icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
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
private fun CreditEntry(
    icon: ImageVector,
    title: String,
    description: String,
    author: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ManagerDimens.InfoRowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfoLeadingIcon(icon = icon)
        Column(
            verticalArrangement = Arrangement.spacedBy(ManagerDimens.InfoLineGap),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = author,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun InfoLeadingIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(end = ManagerDimens.DrawerIconGap)
            .size(ManagerDimens.IconSize),
    )
}

@Suppress("DEPRECATION")
private fun Context.managerVersionName(): String =
    packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

private fun Context.readAssetText(path: String): Result<String> = runCatching {
    assets.open(path).bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
}

private fun Modifier.blockTouchesBehind(): Modifier = pointerInput(Unit) {
    detectTapGestures(onTap = {})
}

private const val DebugVersionSuffix = "-debug"
private const val ApacheLicenseAssetPath = "licenses/apache-2.0.txt"
private const val CapstoneLicenseAssetPath = "licenses/capstone-LICENSE.txt"
