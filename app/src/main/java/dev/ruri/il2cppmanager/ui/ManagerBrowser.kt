package dev.ruri.il2cppmanager.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruri.il2cppmanager.domain.SearchMatchMode
import dev.ruri.il2cppmanager.domain.SymbolKind

@Composable
internal fun BrowserPageContent(
    page: BrowserPage,
    query: String,
    searchOptions: BrowserSearchOptions,
    onEntrySelected: (String) -> Unit,
    onLoadMoreSearch: () -> Unit,
    onTabSelected: (ClassTab) -> Unit,
    onMethodSelected: (Int, Int) -> Unit,
    onCopyMethodValue: (Int, MethodCopyTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (page) {
        is BrowserPage.Directory -> DirectoryPage(
            page = page,
            query = query,
            searchOptions = searchOptions,
            onEntrySelected = onEntrySelected,
            onLoadMoreSearch = onLoadMoreSearch,
            modifier = modifier,
        )
        is BrowserPage.SymbolSearch -> SymbolSearchPage(
            page = page,
            onResultSelected = onEntrySelected,
            onLoadMore = onLoadMoreSearch,
            modifier = modifier,
        )
        is BrowserPage.ClassDetails -> ClassDetailsPage(
            page = page,
            query = query,
            searchOptions = searchOptions,
            onTabSelected = onTabSelected,
            onMethodSelected = onMethodSelected,
            onCopyMethodValue = onCopyMethodValue,
            modifier = modifier,
        )
    }
}

@Composable
private fun DirectoryPage(
    page: BrowserPage.Directory,
    query: String,
    searchOptions: BrowserSearchOptions,
    onEntrySelected: (String) -> Unit,
    onLoadMoreSearch: () -> Unit,
    modifier: Modifier,
) {
    page.search?.let { search ->
        TypeSearchDirectoryPage(
            page = page,
            search = search,
            onEntrySelected = onEntrySelected,
            onLoadMore = onLoadMoreSearch,
            modifier = modifier,
        )
        return
    }
    val sourceGroups = remember(page.entries) {
        page.entries
            .map(BrowserEntryViewData::kind)
            .distinct()
            .map { kind -> DirectoryGroup(kind, page.entries.filter { it.kind == kind }) }
    }
    val visibleGroups = remember(sourceGroups, query, searchOptions) {
        sourceGroups.mapNotNull { group ->
            val entries = filterByQuery(group.entries, query) { entry, term ->
                entry.label.matchesSearch(term, searchOptions) ||
                    entry.secondaryLabel?.matchesSearch(term, searchOptions) == true
            }
            group.copy(entries = entries).takeIf { entries.isNotEmpty() }
        }
    }
    if (visibleGroups.isEmpty()) {
        EmptyBrowserMessage(
            message = emptyResultMessage(
                query = query,
                sourceIsEmpty = page.entries.isEmpty(),
                defaultMessage = page.level.defaultEmptyMessage(),
            ),
            modifier = modifier,
        )
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        visibleGroups.forEach { group ->
            val totalCount = sourceGroups.first { it.kind == group.kind }.entries.size
            item(key = "directory:${group.kind.name}:header") {
                SectionHeader(
                    directoryGroupHeading(
                        level = page.level,
                        kind = group.kind,
                        visibleCount = group.entries.size,
                        totalCount = totalCount,
                        filtered = query.isNotBlank(),
                    ),
                )
            }
            items(
                items = group.entries,
                key = BrowserEntryViewData::id,
                contentType = BrowserEntryViewData::kind,
            ) { entry ->
                DirectoryRow(entry = entry, onClick = { onEntrySelected(entry.id) })
                RowDivider()
            }
        }
    }
}

@Composable
private fun TypeSearchDirectoryPage(
    page: BrowserPage.Directory,
    search: PagedSearchViewData,
    onEntrySelected: (String) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier,
) {
    if (search.isInitialLoading) {
        BrowserLoadingMessage(ManagerText.SearchingAllClasses, modifier)
        return
    }
    if (page.entries.isEmpty()) {
        val failure = search.failureMessage
        if (failure == null) {
            EmptyBrowserMessage(ManagerText.NoMatchingClasses, modifier)
        } else {
            RetryBrowserMessage(failure, onLoadMore, modifier)
        }
        return
    }

    val listState = rememberPagedSearchListState(search, page.entries.size, onLoadMore)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
    ) {
        item(
            key = SEARCH_HEADER_KEY,
            contentType = SEARCH_HEADER_CONTENT_TYPE,
        ) {
            SectionHeader(
                typeSearchHeading(
                    totalCount = search.totalCount,
                ),
            )
        }
        items(
            items = page.entries,
            key = BrowserEntryViewData::id,
            contentType = BrowserEntryViewData::kind,
        ) { entry ->
            DirectoryRow(entry = entry, onClick = { onEntrySelected(entry.id) })
            RowDivider()
        }
        searchFooter(search, onLoadMore)
    }
}

@Composable
private fun SymbolSearchPage(
    page: BrowserPage.SymbolSearch,
    onResultSelected: (String) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier,
) {
    val search = page.search
    if (search.isInitialLoading) {
        BrowserLoadingMessage(ManagerText.SearchingEverywhere, modifier)
        return
    }
    if (page.results.isEmpty()) {
        val failure = search.failureMessage
        if (failure == null) {
            EmptyBrowserMessage(ManagerText.NoMatchingSymbols, modifier)
        } else {
            RetryBrowserMessage(failure, onLoadMore, modifier)
        }
        return
    }

    val listState = rememberPagedSearchListState(search, page.results.size, onLoadMore)
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
    ) {
        item(
            key = SEARCH_HEADER_KEY,
            contentType = SEARCH_HEADER_CONTENT_TYPE,
        ) {
            SectionHeader(symbolSearchHeading(search.totalCount))
        }
        items(
            items = page.results,
            key = SymbolSearchViewData::id,
            contentType = { it.kind },
        ) { result ->
            SymbolSearchRow(result, onClick = { onResultSelected(result.id) })
            RowDivider()
        }
        searchFooter(search, onLoadMore)
    }
}

@Composable
private fun rememberPagedSearchListState(
    search: PagedSearchViewData,
    loadedCount: Int,
    onLoadMore: () -> Unit,
): LazyListState {
    val listState = key(search.spec) { rememberLazyListState() }
    val canLoadMore = loadedCount < search.totalCount &&
        !search.isLoadingMore &&
        search.failureMessage == null
    LaunchedEffect(listState, canLoadMore) {
        if (!canLoadMore) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisibleIndex = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisibleIndex to layout.totalItemsCount
        }.collect { (lastVisibleIndex, itemCount) ->
            val triggerIndex = (itemCount - SEARCH_PREFETCH_DISTANCE).coerceAtLeast(0)
            if (itemCount > 0 && lastVisibleIndex >= triggerIndex) onLoadMore()
        }
    }
    return listState
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchFooter(
    search: PagedSearchViewData,
    onLoadMore: () -> Unit,
) {
    if (search.isLoadingMore) {
        item(key = SEARCH_FOOTER_KEY, contentType = SEARCH_FOOTER_CONTENT_TYPE) {
            SearchLoadingFooter()
        }
    } else {
        search.failureMessage?.let { failure ->
            item(key = SEARCH_FOOTER_KEY, contentType = SEARCH_FOOTER_CONTENT_TYPE) {
                SearchFailureFooter(failure, onLoadMore)
            }
        }
    }
}

@Composable
private fun SymbolSearchRow(result: SymbolSearchViewData, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ManagerDimens.DirectoryRowHeight)
            .clickable(
                role = Role.Button,
                onClickLabel = ManagerText.openSearchResultDescription(result),
                onClick = onClick,
            )
            .padding(horizontal = ManagerDimens.ContentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = result.kind.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(DirectoryPageDimens.ResultKindWidth),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = DirectoryPageDimens.ResultVerticalPadding),
        ) {
            DefinitionName(result.name)
            Text(
                text = result.ownerName,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = result.assemblyName,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Icon(
            imageVector = ManagerIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ManagerDimens.SmallIconSize),
        )
    }
}

private data class DirectoryGroup(
    val kind: BrowserEntryKind,
    val entries: List<BrowserEntryViewData>,
)

private fun directoryGroupHeading(
    level: DirectoryLevel,
    kind: BrowserEntryKind,
    visibleCount: Int,
    totalCount: Int,
    filtered: Boolean,
): String {
    val label = when (kind) {
        BrowserEntryKind.ASSEMBLY -> DirectoryPageText.Assemblies
        BrowserEntryKind.NAMESPACE -> DirectoryPageText.Namespaces
        BrowserEntryKind.CLASS -> if (level == DirectoryLevel.NAMESPACES) {
            DirectoryPageText.GlobalClasses
        } else {
            DirectoryPageText.Classes
        }
    }
    val count = if (filtered) resultCountLabel(visibleCount) else totalCount.toString()
    return "$label${DirectoryPageText.Separator}$count"
}

private fun typeSearchHeading(totalCount: Int): String =
    "${DirectoryPageText.Classes}${DirectoryPageText.Separator}${resultCountLabel(totalCount)}"

private fun symbolSearchHeading(totalCount: Int): String =
    "${DirectoryPageText.Everywhere}${DirectoryPageText.Separator}${resultCountLabel(totalCount)}"

private fun resultCountLabel(count: Int): String =
    "$count ${if (count == 1) DirectoryPageText.Result else DirectoryPageText.Results}"

@Composable
private fun DirectoryRow(entry: BrowserEntryViewData, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ManagerDimens.DirectoryRowHeight)
            .clickable(role = Role.Button, onClickLabel = ManagerText.OpenFolder, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = ManagerText.folderDescription(entry)
            }
            .padding(horizontal = ManagerDimens.ContentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ManagerIcons.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(ManagerDimens.IconSize),
        )
        Spacer(modifier = Modifier.size(DirectoryPageDimens.IconGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.secondaryLabel?.let { secondaryLabel ->
                Text(
                    text = secondaryLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = ManagerIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ManagerDimens.SmallIconSize),
        )
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ManagerDimens.ContentPadding,
                end = ManagerDimens.ContentPadding,
                top = DirectoryPageDimens.SectionTopPadding,
                bottom = DirectoryPageDimens.SectionBottomPadding,
            ),
    )
}

@Composable
private fun ClassDetailsPage(
    page: BrowserPage.ClassDetails,
    query: String,
    searchOptions: BrowserSearchOptions,
    onTabSelected: (ClassTab) -> Unit,
    onMethodSelected: (Int, Int) -> Unit,
    onCopyMethodValue: (Int, MethodCopyTarget) -> Unit,
    modifier: Modifier,
) {
    val visibleFields = remember(page.fields, page.selectedTab, query, searchOptions) {
        if (page.selectedTab != ClassTab.FIELDS) page.fields else filterByQuery(page.fields, query) { field, term ->
            field.name.matchesSearch(term, searchOptions) ||
                field.typeLabel.matchesSearch(term, searchOptions) ||
                field.offsetLabel?.matchesSearch(term, searchOptions) == true
        }
    }
    val visibleMethods = remember(page.methods, page.selectedTab, query, searchOptions) {
        if (page.selectedTab != ClassTab.METHODS) page.methods else filterByQuery(page.methods, query) { method, term ->
            method.name.matchesSearch(term, searchOptions) ||
                method.signature?.matchesSearch(term, searchOptions) == true ||
                method.rvaLabel?.matchesSearch(term, searchOptions) == true ||
                method.addressLabel?.matchesSearch(term, searchOptions) == true
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        ClassTabs(
            page = page,
            visibleFieldCount = visibleFields.size,
            visibleMethodCount = visibleMethods.size,
            filtered = query.isNotBlank(),
            onTabSelected = onTabSelected,
        )
        AnimatedContent(
            targetState = page.selectedTab,
            transitionSpec = {
                fadeIn(tween(ManagerMotion.Standard)) togetherWith fadeOut(tween(ManagerMotion.Fast))
            },
            label = "class tab",
            modifier = Modifier.weight(1f),
        ) { tab ->
            when (tab) {
                ClassTab.FIELDS -> DefinitionList(
                    items = visibleFields,
                    sourceIsEmpty = page.fields.isEmpty(),
                    query = query,
                    emptyMessage = ClassPageText.NoDeclaredFields,
                    noMatchMessage = ClassPageText.NoMatchingFields,
                    focusedItemId = page.focusedMemberId,
                    key = FieldViewData::id,
                    row = ::FieldRow,
                )
                ClassTab.METHODS -> DefinitionList(
                    items = visibleMethods,
                    sourceIsEmpty = page.methods.isEmpty(),
                    query = query,
                    emptyMessage = ClassPageText.NoDeclaredMethods,
                    noMatchMessage = ClassPageText.NoMatchingMethods,
                    focusedItemId = page.focusedMemberId,
                    key = MethodViewData::id,
                ) { method ->
                    MethodRow(
                        method = method,
                        onOpen = { onMethodSelected(page.classIndex, method.id) },
                        onCopy = { target -> onCopyMethodValue(method.id, target) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassTabs(
    page: BrowserPage.ClassDetails,
    visibleFieldCount: Int,
    visibleMethodCount: Int,
    filtered: Boolean,
    onTabSelected: (ClassTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ManagerDimens.TabHeight)
            .selectableGroup(),
    ) {
        ClassTab.entries.forEach { tab ->
            val selected = tab == page.selectedTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onTabSelected(tab) },
                    )
                    .semantics(mergeDescendants = true) { this.selected = selected }
                    .padding(horizontal = ClassPageDimens.TabHorizontalPadding),
            ) {
                Text(
                    text = tab.label(page, visibleFieldCount, visibleMethodCount, filtered && selected),
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.Center),
                )
                HorizontalDivider(
                    thickness = ClassPageDimens.SelectedTabIndicatorThickness,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }
        }
    }
    RowDivider()
}

@Composable
private fun FieldRow(field: FieldViewData) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ClassPageDimens.MemberRowHeight)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(
                    field.name,
                    field.typeLabel,
                    field.offsetLabel,
                ).joinToString(", ")
            }
            .padding(horizontal = ManagerDimens.ContentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = ClassPageDimens.MemberVerticalPadding),
        ) {
            DefinitionName(field.name)
            DefinitionMetadata(field.typeLabel)
        }
        field.offsetLabel?.let { offset ->
            Text(
                text = offset,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(start = ClassPageDimens.MetadataGap),
            )
        }
    }
}

@Composable
private fun MethodRow(
    method: MethodViewData,
    onOpen: () -> Unit,
    onCopy: (MethodCopyTarget) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ClassPageDimens.MemberRowHeight)
            .clickable(
                role = Role.Button,
                onClickLabel = ClassPageText.inspectMethod(method.name),
                onClick = onOpen,
            )
            .padding(horizontal = ManagerDimens.ContentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = ClassPageDimens.MethodVerticalPadding),
        ) {
            DefinitionName(method.signature ?: method.name)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ClassPageDimens.AddressGap),
                verticalArrangement = Arrangement.spacedBy(ClassPageDimens.AddressRowGap),
                modifier = Modifier.fillMaxWidth(),
            ) {
                method.rvaLabel?.let { value ->
                    AddressCopyAction(
                        prefix = ClassPageText.Rva,
                        value = value,
                        onClick = { onCopy(MethodCopyTarget.RVA) },
                    )
                }
                method.addressLabel?.let { value ->
                    AddressCopyAction(
                        prefix = ClassPageText.Va,
                        value = value,
                        onClick = { onCopy(MethodCopyTarget.VA) },
                    )
                }
            }
        }
        Icon(
            imageVector = ManagerIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = ClassPageDimens.MetadataGap)
                .size(ManagerDimens.SmallIconSize),
        )
    }
}

@Composable
internal fun AddressCopyAction(prefix: String, value: String, onClick: () -> Unit) {
    val description = "$prefix $value"
    Row(
        modifier = Modifier
            .heightIn(min = ManagerDimens.TouchTarget)
            .clickable(
                role = Role.Button,
                onClickLabel = ManagerText.copyAddressAction(prefix),
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$prefix $value",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 2,
        )
        Spacer(modifier = Modifier.size(ClassPageDimens.CopyIconGap))
        Icon(
            imageVector = ManagerIcons.Copy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(ClassPageDimens.CopyIconSize),
        )
    }
}

@Composable
private fun DefinitionName(value: String) {
    Text(
        text = value,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun DefinitionMetadata(value: String) {
    Text(
        text = value,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun <T> DefinitionList(
    items: List<T>,
    sourceIsEmpty: Boolean,
    query: String,
    emptyMessage: String,
    noMatchMessage: String,
    focusedItemId: Any? = null,
    key: (T) -> Any,
    row: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyBrowserMessage(
            emptyResultMessage(query, sourceIsEmpty, emptyMessage, noMatchMessage),
        )
    } else {
        val listState = rememberLazyListState()
        LaunchedEffect(focusedItemId) {
            val focusedIndex = focusedItemId?.let { id -> items.indexOfFirst { key(it) == id } } ?: -1
            if (focusedIndex >= 0) listState.scrollToItem(focusedIndex)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = key) { item ->
                row(item)
                RowDivider()
            }
        }
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(
        thickness = ManagerDimens.DividerThickness,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun EmptyBrowserMessage(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(ManagerDimens.ContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BrowserLoadingMessage(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(ManagerDimens.ContentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = DirectoryPageDimens.ProgressThickness,
            modifier = Modifier.size(DirectoryPageDimens.ProgressSize),
        )
        Spacer(modifier = Modifier.height(DirectoryPageDimens.ProgressGap))
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RetryBrowserMessage(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(role = Role.Button, onClickLabel = ManagerText.Retry, onClick = onRetry)
            .padding(ManagerDimens.ContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$message\n${ManagerText.TapToRetry}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SearchLoadingFooter() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DirectoryPageDimens.FooterHeight),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = DirectoryPageDimens.FooterProgressThickness,
            modifier = Modifier.size(DirectoryPageDimens.FooterProgressSize),
        )
    }
}

@Composable
private fun SearchFailureFooter(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DirectoryPageDimens.FooterHeight)
            .clickable(role = Role.Button, onClickLabel = ManagerText.Retry, onClick = onRetry)
            .padding(horizontal = ManagerDimens.ContentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$message ${ManagerText.TapToRetry}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun <T> filterByQuery(
    items: List<T>,
    query: String,
    matches: (T, String) -> Boolean,
): List<T> = if (query.isBlank()) items else items.filter { matches(it, query) }

private fun String.matchesSearch(term: String, options: BrowserSearchOptions): Boolean =
    when (options.matchMode) {
        SearchMatchMode.CONTAINS -> contains(term, ignoreCase = !options.matchCase)
        SearchMatchMode.EXACT -> equals(term, ignoreCase = !options.matchCase)
    }

private fun emptyResultMessage(
    query: String,
    sourceIsEmpty: Boolean,
    defaultMessage: String,
    noMatchMessage: String = ManagerText.NoMatchingItems,
): String = if (query.isNotBlank() && !sourceIsEmpty) noMatchMessage else defaultMessage

private fun DirectoryLevel.defaultEmptyMessage(): String = when (this) {
    DirectoryLevel.ASSEMBLIES -> ManagerText.NoAssemblies
    DirectoryLevel.NAMESPACES -> ManagerText.NoNamespacesOrClasses
    DirectoryLevel.CLASSES -> ManagerText.NoClasses
}

private fun ClassTab.label(
    page: BrowserPage.ClassDetails,
    visibleFieldCount: Int,
    visibleMethodCount: Int,
    filtered: Boolean,
): String = when (this) {
    ClassTab.FIELDS -> ManagerText.Fields + countSuffix(visibleFieldCount, page.fields.size, filtered)
    ClassTab.METHODS -> ManagerText.Methods + countSuffix(visibleMethodCount, page.methods.size, filtered)
}

private fun countSuffix(visible: Int, total: Int, filtered: Boolean): String =
    " ${if (filtered) visible else total}"

private object DirectoryPageDimens {
    val IconGap = 16.dp
    val SectionTopPadding = 18.dp
    val SectionBottomPadding = 8.dp
    val ProgressSize = 24.dp
    val ProgressThickness = 2.dp
    val ProgressGap = 12.dp
    val FooterHeight = 52.dp
    val FooterProgressSize = 18.dp
    val FooterProgressThickness = 2.dp
    val ResultVerticalPadding = 10.dp
    val ResultKindWidth = 52.dp
}

private object DirectoryPageText {
    const val Assemblies = "Assemblies"
    const val Namespaces = "Namespaces"
    const val GlobalClasses = "Global classes"
    const val Classes = "Classes"
    const val Everywhere = "Everywhere"
    const val Result = "result"
    const val Results = "results"
    const val Separator = " · "
}

private object ClassPageDimens {
    val AddressGap = 12.dp
    val AddressRowGap = 0.dp
    val CopyIconGap = 6.dp
    val CopyIconSize = 16.dp
    val MetadataGap = 12.dp
    val MemberVerticalPadding = 10.dp
    val MethodVerticalPadding = 4.dp
    val SelectedTabIndicatorThickness = 2.dp
    val TabHorizontalPadding = 14.dp
    val MemberRowHeight = 68.dp
}

private object ClassPageText {
    const val Rva = "RVA"
    const val Va = "VA"
    const val NoDeclaredFields = "This class declares no fields."
    const val NoDeclaredMethods = "This class declares no methods."
    const val NoMatchingFields = "No fields match this search."
    const val NoMatchingMethods = "No methods match this search."

    fun inspectMethod(name: String): String = "Inspect $name"
}

private val SymbolKind.label: String
    get() = when (this) {
        SymbolKind.CLASS -> "CLASS"
        SymbolKind.FIELD -> "FIELD"
        SymbolKind.METHOD -> "METHOD"
    }

private const val SEARCH_PREFETCH_DISTANCE = 6
private const val SEARCH_HEADER_KEY = "search:header"
private const val SEARCH_FOOTER_KEY = "search:footer"
private const val SEARCH_HEADER_CONTENT_TYPE = "search-header"
private const val SEARCH_FOOTER_CONTENT_TYPE = "search-footer"
