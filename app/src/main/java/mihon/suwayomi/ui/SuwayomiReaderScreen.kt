package mihon.suwayomi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
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
) : ScreenModel {

    val content: StateFlow<Loadable<ReaderContent>>
        field = MutableStateFlow<Loadable<ReaderContent>>(Loadable.Loading)

    val repo: SuwayomiRepository get() = repository

    init {
        load()
    }

    fun load() {
        screenModelScope.launch {
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
        val repository = LocalContext.current.appGraph.suwayomiRepository
        val model = rememberScreenModel { SuwayomiReaderScreenModel(repository, chapterId, mangaId) }
        val content by model.content.collectAsState()

        Box(Modifier.fillMaxSize()) {
            LoadableContent(content, onRetry = model::load) { reader ->
                val urls = reader.pages.pageUrls
                if (urls.isEmpty()) {
                    Text(stringResource(MR.strings.suwayomi_empty), Modifier.padding(16.dp))
                    return@LoadableContent
                }
                val pager = rememberPagerState(initialPage = reader.startPage) { urls.size }

                LaunchedEffect(pager) {
                    snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { model.saveProgress(it, urls.size) }
                }

                Box(Modifier.fillMaxSize()) {
                    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { index ->
                        SuwayomiImage(repository, urls[index], Modifier.fillMaxSize(), ContentScale.Fit)
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
                        Text(
                            stringResource(MR.strings.suwayomi_page_progress, pager.currentPage + 1, urls.size),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        val next = reader.next
                        if (next != null && pager.currentPage == urls.size - 1) {
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
