package mihon.suwayomi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.presentation.util.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.suwayomi.SuwayomiChapter
import mihon.suwayomi.SuwayomiChapterPages
import mihon.suwayomi.SuwayomiImageScale
import tachiyomi.presentation.core.util.collectAsState
import mihon.suwayomi.SuwayomiRepository
import mihon.suwayomi.SuwayomiResult
import mihon.suwayomi.chapterAfter
import mihon.suwayomi.getOrNull
import mihon.suwayomi.resumePage
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data class ReaderContent(val pages: SuwayomiChapterPages, val startPage: Int, val next: SuwayomiChapter?)

class SuwayomiReaderScreenModel(
    private val repository: SuwayomiRepository,
    private val chapterId: Int,
    private val mangaId: Int,
) : ViewModel() {

    val content: StateFlow<Loadable<ReaderContent>>
        field = MutableStateFlow<Loadable<ReaderContent>>(Loadable.Loading)

    val repo: SuwayomiRepository get() = repository

    fun load() {
        viewModelScope.launch {
            content.value = Loadable.Loading
            val pages = repository.getChapterPages(chapterId)
            content.value = when (pages) {
                is SuwayomiResult.Err -> Loadable.Failed(pages.error)
                is SuwayomiResult.Ok -> {
                    // The chapter list gives resume state and the following chapter; the pages alone are enough to read.
                    val details = repository.getMangaDetails(mangaId).getOrNull()
                    val chapter = details?.chapters?.firstOrNull { it.id == chapterId }
                    Loadable.Loaded(
                        ReaderContent(
                            pages = pages.value,
                            startPage = chapter?.let { resumePage(it, pages.value.pageUrls.size) }
                                ?: pages.value.lastPageRead.coerceIn(0, (pages.value.pageUrls.size - 1).coerceAtLeast(0)),
                            next = details?.chapters?.chapterAfter(chapterId),
                        ),
                    )
                }
            }
        }
    }

    suspend fun saveProgress(page: Int, pageCount: Int) {
        // Progress is best effort; a failed save must not interrupt reading.
        withContext(NonCancellable) { repository.saveProgress(chapterId, page, pageCount) }
    }
}

data class SuwayomiReaderScreen(val chapterId: Int, val mangaId: Int) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val graph = LocalContext.current.appGraph
        val repository = graph.suwayomiRepository
        val prefs = graph.suwayomiReaderPreferences
        val model = viewModel(key = "suwayomi-reader-$chapterId") { SuwayomiReaderScreenModel(repository, chapterId, mangaId) }
        val content by model.content.collectAsState()
        val mode by prefs.readingMode.collectAsState()
        val scale by prefs.imageScale.collectAsState()
        val keepScreenOn by prefs.keepScreenOn.collectAsState()
        val showPageNumber by prefs.showPageNumber.collectAsState()

        val view = LocalView.current
        DisposableEffect(view, keepScreenOn) {
            val previous = view.keepScreenOn
            view.keepScreenOn = keepScreenOn
            onDispose { view.keepScreenOn = previous }
        }

        // The model outlives the screen, so reload to pick up the progress saved the last time.
        LaunchedEffect(Unit) { model.load() }

        Box(Modifier.fillMaxSize()) {
            LoadableContent(content, onRetry = model::load) { reader ->
                val urls = reader.pages.pageUrls
                if (urls.isEmpty()) {
                    Text(stringResource(MR.strings.suwayomi_empty), Modifier.padding(16.dp))
                    return@LoadableContent
                }
                val pager = rememberPagerState(initialPage = reader.startPage) { urls.size }
                val list = rememberLazyListState(initialFirstVisibleItemIndex = reader.startPage)
                val current = if (mode.continuous) list.firstVisibleItemIndex else pager.currentPage

                // Switching modes keeps the reader on the page it was showing.
                LaunchedEffect(mode) {
                    if (mode.continuous) list.scrollToItem(pager.currentPage) else pager.scrollToPage(list.firstVisibleItemIndex)
                }
                LaunchedEffect(mode, pager, list) {
                    snapshotFlow { if (mode.continuous) list.firstVisibleItemIndex else pager.settledPage }
                        .distinctUntilChanged()
                        .collect { model.saveProgress(it, urls.size) }
                }

                Box(Modifier.fillMaxSize()) {
                    val contentScale = if (scale == SuwayomiImageScale.FitWidth) ContentScale.FillWidth else ContentScale.Fit
                    val page: @Composable (Int) -> Unit = { index ->
                        if (scale == SuwayomiImageScale.FitWidth && !mode.continuous) {
                            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                SuwayomiImage(repository, urls[index], Modifier.fillMaxWidth(), contentScale)
                            }
                        } else {
                            SuwayomiImage(repository, urls[index], Modifier.fillMaxSize(), contentScale)
                        }
                    }
                    when {
                        mode.continuous -> LazyColumn(Modifier.fillMaxSize(), state = list) {
                            items(urls.size) { index ->
                                SuwayomiImage(repository, urls[index], Modifier.fillMaxWidth(), ContentScale.FillWidth)
                            }
                        }
                        mode.horizontal -> HorizontalPager(
                            pager,
                            Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1,
                            reverseLayout = mode.reversed,
                        ) { page(it) }
                        else -> VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page(it) }
                    }
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = navigator::pop) { Text("←") }
                        if (showPageNumber) {
                            Text(
                                stringResource(MR.strings.suwayomi_page_progress, current + 1, urls.size),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        val next = reader.next
                        if (next != null && current == urls.size - 1) {
                            TextButton(onClick = { navigator.replace(SuwayomiReaderScreen(next.id, mangaId)) }) {
                                Text("→")
                            }
                        } else {
                            TextButton(onClick = {}, enabled = false) { Text("") }
                        }
                    }
                }
            }
        }
    }
}
