package mihon.suwayomi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.kanade.presentation.util.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.app.di.appGraph
import mihon.suwayomi.SuwayomiBrowseType
import mihon.suwayomi.SuwayomiManga
import mihon.suwayomi.SuwayomiRepository
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Entry point of the native Suwayomi client: library, browse, downloads, extensions and library updates. */
object SuwayomiHomeScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val repository = LocalContext.current.appGraph.suwayomiRepository
        val model = viewModel(key = "suwayomi-home") { SuwayomiHomeScreenModel(repository) }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val snackbar = remember { SnackbarHostState() }
        val error by model.error.collectAsState()
        val errorMessage = error?.let { errorText(it) }

        LaunchedEffect(errorMessage) {
            if (errorMessage != null) {
                snackbar.showSnackbar(errorMessage)
                model.consumeError()
            }
        }
        LaunchedEffect(tab) {
            when (tab) {
                1 -> model.loadSources()
                2 -> model.loadDownloads()
                3 -> model.loadExtensions()
                4 -> model.loadUpdateStatus()
            }
        }

        val titles = listOf(
            MR.strings.suwayomi_tab_library,
            MR.strings.suwayomi_tab_browse,
            MR.strings.suwayomi_tab_downloads,
            MR.strings.suwayomi_tab_extensions,
            MR.strings.suwayomi_tab_updates,
        )

        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(stringResource(MR.strings.suwayomi_title)) },
                        navigationIcon = { TextButton(onClick = navigator::pop) { Text("←") } },
                    )
                    ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                        titles.forEachIndexed { index, title ->
                            Tab(
                                selected = tab == index,
                                onClick = { tab = index },
                                text = { Text(stringResource(title)) },
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    0 -> LibraryTab(model, repository)
                    1 -> BrowseTab(model, repository)
                    2 -> DownloadsTab(model)
                    3 -> ExtensionsTab(model)
                    else -> UpdatesTab(model)
                }
            }
        }
    }
}

@Composable
internal fun MangaRow(
    repository: SuwayomiRepository,
    manga: SuwayomiManga,
    trailing: @Composable (() -> Unit)? = null,
) {
    val navigator = LocalNavigator.currentOrThrow
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { navigator.push(SuwayomiMangaScreen(manga.id)) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SuwayomiImage(repository, manga.thumbnailUrl, Modifier.size(width = 48.dp, height = 68.dp))
        Column(Modifier.weight(1f)) {
            Text(manga.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            if (manga.unreadCount > 0) {
                Text(
                    stringResource(MR.strings.suwayomi_unread_count, manga.unreadCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun LibraryTab(model: SuwayomiHomeScreenModel, repository: SuwayomiRepository) {
    val library by model.library.collectAsState()
    val categories by model.categories.collectAsState()
    val selected by model.selectedCategory.collectAsState()
    var newCategory by remember { mutableStateOf("") }

    LoadableContent(library, onRetry = model::loadLibrary) { all ->
        val shown = if (selected == null) all else all.filter { selected in it.categoryIds }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selected == null,
                        onClick = { model.selectCategory(null) },
                        label = { Text(stringResource(MR.strings.suwayomi_all_categories)) },
                    )
                    categories.forEach { category ->
                        FilterChip(
                            selected = selected == category.id,
                            onClick = { model.selectCategory(category.id) },
                            label = { Text(category.name) },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { newCategory = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(MR.strings.suwayomi_new_category)) },
                        singleLine = true,
                    )
                    OutlinedButton(
                        enabled = newCategory.isNotBlank(),
                        onClick = {
                            model.createCategory(newCategory)
                            newCategory = ""
                        },
                    ) { Text("+") }
                }
            }
            if (shown.isEmpty()) {
                item { Text(stringResource(MR.strings.suwayomi_empty), Modifier.padding(16.dp)) }
            }
            items(shown, key = { it.id }) { MangaRow(repository, it) }
        }
    }
}

@Composable
private fun BrowseTab(model: SuwayomiHomeScreenModel, repository: SuwayomiRepository) {
    val sources by model.sources.collectAsState()
    val browse by model.browse.collectAsState()
    val source = browse.source

    if (source == null) {
        LoadableContent(sources, onRetry = model::loadSources) { list ->
            LazyColumn {
                items(list, key = { it.id }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { model.openSource(item) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SuwayomiImage(repository, item.iconUrl, Modifier.size(36.dp))
                        Text("${item.name} (${item.lang})", Modifier.weight(1f))
                    }
                }
            }
        }
        return
    }

    var query by rememberSaveable(source.id) { mutableStateOf(browse.query) }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { model.openSource(null) }) { Text("←") }
                    Text(source.name, style = MaterialTheme.typography.titleMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(MR.strings.suwayomi_search)) },
                        singleLine = true,
                    )
                    Button(onClick = { model.search(query) }) { Text(stringResource(MR.strings.suwayomi_search)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = browse.type == SuwayomiBrowseType.POPULAR,
                        onClick = { query = ""; model.setBrowseType(SuwayomiBrowseType.POPULAR) },
                        label = { Text(stringResource(MR.strings.suwayomi_popular)) },
                    )
                    if (source.supportsLatest) {
                        FilterChip(
                            selected = browse.type == SuwayomiBrowseType.LATEST,
                            onClick = { query = ""; model.setBrowseType(SuwayomiBrowseType.LATEST) },
                            label = { Text(stringResource(MR.strings.suwayomi_latest)) },
                        )
                    }
                }
            }
        }
        items(browse.mangas, key = { it.id }) { manga ->
            MangaRow(repository, manga) {
                OutlinedButton(onClick = { model.toggleInLibrary(manga) }) {
                    Text(
                        stringResource(
                            if (manga.inLibrary) MR.strings.suwayomi_in_library else MR.strings.suwayomi_add_to_library,
                        ),
                    )
                }
            }
        }
        item {
            if (browse.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            } else if (browse.hasNextPage) {
                TextButton(onClick = model::loadMore, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(MR.strings.suwayomi_load_more))
                }
            }
        }
    }
}

@Composable
private fun DownloadsTab(model: SuwayomiHomeScreenModel) {
    val downloads by model.downloads.collectAsState()
    LoadableContent(downloads, onRetry = model::loadDownloads) { status ->
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            if (status.running) MR.strings.suwayomi_downloader_running else MR.strings.suwayomi_downloader_stopped,
                        ),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !status.running, onClick = model::startDownloader) {
                            Text(stringResource(MR.strings.suwayomi_downloader_start))
                        }
                        OutlinedButton(enabled = status.running, onClick = model::stopDownloader) {
                            Text(stringResource(MR.strings.suwayomi_downloader_stop))
                        }
                        OutlinedButton(enabled = status.queue.isNotEmpty(), onClick = model::clearDownloads) {
                            Text(stringResource(MR.strings.suwayomi_downloader_clear))
                        }
                    }
                }
                HorizontalDivider()
            }
            items(status.queue, key = { it.chapterId }) { download ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(download.mangaTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(
                            "${download.chapterName} · ${download.state}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                        LinearProgressIndicator(progress = { download.progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { model.dequeue(download.chapterId) }) {
                        Text(stringResource(MR.strings.suwayomi_remove_from_queue))
                    }
                }
            }
            if (status.queue.isEmpty()) {
                item { Text(stringResource(MR.strings.suwayomi_empty), Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun ExtensionsTab(model: SuwayomiHomeScreenModel) {
    val extensions by model.extensions.collectAsState()
    LoadableContent(extensions, onRetry = model::loadExtensions) { list ->
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                OutlinedButton(onClick = model::refreshExtensions, modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(MR.strings.suwayomi_extension_refresh))
                }
            }
            items(list, key = { it.pkgName }) { extension ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(extension.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text("${extension.lang} · ${extension.versionName}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!extension.isInstalled) {
                        Button(onClick = { model.installExtension(extension) }) {
                            Text(stringResource(MR.strings.suwayomi_extension_install))
                        }
                    } else {
                        if (extension.hasUpdate) {
                            Button(onClick = { model.updateExtension(extension) }) {
                                Text(stringResource(MR.strings.suwayomi_extension_update))
                            }
                        }
                        OutlinedButton(onClick = { model.uninstallExtension(extension) }) {
                            Text(stringResource(MR.strings.suwayomi_extension_uninstall))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdatesTab(model: SuwayomiHomeScreenModel) {
    val status by model.updateStatus.collectAsState()
    LoadableContent(status, onRetry = model::loadUpdateStatus) { update ->
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (update.isRunning) {
                    stringResource(MR.strings.suwayomi_update_running, update.finishedJobs, update.totalJobs)
                } else {
                    stringResource(MR.strings.suwayomi_update_idle)
                },
            )
            if (update.isRunning && update.totalJobs > 0) {
                LinearProgressIndicator(
                    progress = { update.finishedJobs.toFloat() / update.totalJobs },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
            if (update.skippedMangas > 0) {
                Text(stringResource(MR.strings.suwayomi_update_skipped, update.skippedMangas))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !update.isRunning, onClick = model::updateLibrary) {
                    Text(stringResource(MR.strings.suwayomi_update_library))
                }
                OutlinedButton(onClick = model::loadUpdateStatus) {
                    Text(stringResource(MR.strings.suwayomi_retry))
                }
            }
        }
    }
}
