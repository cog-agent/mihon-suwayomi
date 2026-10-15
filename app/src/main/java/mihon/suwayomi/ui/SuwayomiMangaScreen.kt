package mihon.suwayomi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.presentation.util.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import mihon.suwayomi.SuwayomiCategory
import mihon.suwayomi.SuwayomiError
import mihon.suwayomi.SuwayomiMangaDetails
import mihon.suwayomi.SuwayomiRepository
import mihon.suwayomi.SuwayomiResult
import mihon.suwayomi.downloadable
import mihon.suwayomi.nextToRead
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

class SuwayomiMangaScreenModel(
    private val repository: SuwayomiRepository,
    private val mangaId: Int,
) : ViewModel() {

    val details: StateFlow<Loadable<SuwayomiMangaDetails>>
        field = MutableStateFlow<Loadable<SuwayomiMangaDetails>>(Loadable.Loading)

    val categories: StateFlow<List<SuwayomiCategory>>
        field = MutableStateFlow<List<SuwayomiCategory>>(emptyList())

    val selected: StateFlow<Set<Int>>
        field = MutableStateFlow<Set<Int>>(emptySet())

    val error: StateFlow<SuwayomiError?>
        field = MutableStateFlow<SuwayomiError?>(null)

    init {
        load()
        viewModelScope.launch {
            repository.getCategories().let { if (it is SuwayomiResult.Ok) categories.value = it.value }
        }
    }

    fun consumeError() {
        error.value = null
    }

    fun load() {
        viewModelScope.launch {
            if (details.value !is Loadable.Loaded) details.value = Loadable.Loading
            details.value = when (val result = repository.getMangaDetails(mangaId)) {
                is SuwayomiResult.Ok -> Loadable.Loaded(result.value)
                is SuwayomiResult.Err -> Loadable.Failed(result.error)
            }
        }
    }

    private fun <T> act(call: suspend () -> SuwayomiResult<T>, onOk: () -> Unit = ::load) {
        viewModelScope.launch {
            when (val result = call()) {
                is SuwayomiResult.Ok -> onOk()
                is SuwayomiResult.Err -> error.value = result.error
            }
        }
    }

    fun toggleSelection(chapterId: Int) {
        selected.update { if (chapterId in it) it - chapterId else it + chapterId }
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    fun toggleLibrary() {
        val manga = (details.value as? Loadable.Loaded)?.value?.manga ?: return
        act({ repository.setInLibrary(mangaId, !manga.inLibrary) })
    }

    fun toggleCategory(category: SuwayomiCategory) {
        val manga = (details.value as? Loadable.Loaded)?.value?.manga ?: return
        val member = category.id in manga.categoryIds
        act({
            repository.setMangaCategories(
                mangaId,
                add = if (member) emptyList() else listOf(category.id),
                remove = if (member) listOf(category.id) else emptyList(),
            )
        })
    }

    /** Re-fetches the chapter list from the source through the server. */
    fun refreshChapters() = act({ repository.refreshChapters(mangaId) })

    fun setSelectedRead(read: Boolean) {
        val ids = selected.value.toList()
        if (ids.isEmpty()) return
        act({ repository.setChaptersRead(ids, read) }) {
            clearSelection()
            load()
        }
    }

    fun downloadSelected() {
        val chapters = (details.value as? Loadable.Loaded)?.value?.chapters ?: return
        val ids = chapters.downloadable(selected.value)
        if (ids.isEmpty()) return
        act({ repository.enqueueDownloads(ids) }) {
            clearSelection()
            // Downloads only run while the downloader is started.
            viewModelScope.launch { repository.startDownloader() }
        }
    }
}

data class SuwayomiMangaScreen(val mangaId: Int) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val repository = LocalContext.current.appGraph.suwayomiRepository
        val model = viewModel(key = "suwayomi-manga-$mangaId") { SuwayomiMangaScreenModel(repository, mangaId) }
        val details by model.details.collectAsState()
        val categories by model.categories.collectAsState()
        val selected by model.selected.collectAsState()
        val error by model.error.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        val errorMessage = error?.let { errorText(it) }

        LaunchedEffect(errorMessage) {
            if (errorMessage != null) {
                snackbar.showSnackbar(errorMessage)
                model.consumeError()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text((details as? Loadable.Loaded)?.value?.manga?.title.orEmpty(), maxLines = 1) },
                    navigationIcon = { TextButton(onClick = navigator::pop) { Text("←") } },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                LoadableContent(details, onRetry = model::load) { manga ->
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        item {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SuwayomiImage(repository, manga.manga.thumbnailUrl, Modifier.size(100.dp, 144.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(manga.manga.title, style = MaterialTheme.typography.titleMedium)
                                        listOfNotNull(manga.author, manga.artist).distinct().forEach { Text(it) }
                                        Text(manga.status, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                manga.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                if (manga.genre.isNotEmpty()) {
                                    Text(manga.genre.joinToString(), style = MaterialTheme.typography.bodySmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = model::toggleLibrary) {
                                        Text(
                                            stringResource(
                                                if (manga.manga.inLibrary) {
                                                    MR.strings.suwayomi_remove_from_library
                                                } else {
                                                    MR.strings.suwayomi_add_to_library
                                                },
                                            ),
                                        )
                                    }
                                    OutlinedButton(onClick = model::refreshChapters) {
                                        Text(stringResource(MR.strings.suwayomi_refresh_chapters))
                                    }
                                }
                                manga.chapters.nextToRead()?.let { next ->
                                    Button(
                                        onClick = { navigator.push(SuwayomiReaderScreen(next.id, manga.manga.id)) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("${stringResource(MR.strings.suwayomi_continue_reading)}: ${next.name}") }
                                }
                                if (manga.manga.inLibrary && categories.isNotEmpty()) {
                                    Text(stringResource(MR.strings.suwayomi_categories), style = MaterialTheme.typography.labelLarge)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        categories.forEach { category ->
                                            FilterChip(
                                                selected = category.id in manga.manga.categoryIds,
                                                onClick = { model.toggleCategory(category) },
                                                label = { Text(category.name) },
                                            )
                                        }
                                    }
                                }
                                if (selected.isNotEmpty()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { model.setSelectedRead(true) }) {
                                            Text(stringResource(MR.strings.suwayomi_mark_read))
                                        }
                                        OutlinedButton(onClick = { model.setSelectedRead(false) }) {
                                            Text(stringResource(MR.strings.suwayomi_mark_unread))
                                        }
                                        OutlinedButton(onClick = model::downloadSelected) {
                                            Text(stringResource(MR.strings.suwayomi_download_selected))
                                        }
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                        items(manga.chapters.sortedBy { it.sourceOrder }, key = { it.id }) { chapter ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { navigator.push(SuwayomiReaderScreen(chapter.id, manga.manga.id)) }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = chapter.id in selected,
                                    onCheckedChange = { model.toggleSelection(chapter.id) },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        chapter.name,
                                        maxLines = 1,
                                        color = if (chapter.isRead) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        textDecoration = if (chapter.isBookmarked) TextDecoration.Underline else null,
                                    )
                                    chapter.scanlator?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                }
                                if (chapter.isDownloaded) Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
