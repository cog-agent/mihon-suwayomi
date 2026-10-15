package mihon.suwayomi.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.suwayomi.SuwayomiBrowseType
import mihon.suwayomi.SuwayomiCategory
import mihon.suwayomi.SuwayomiDownloadStatus
import mihon.suwayomi.SuwayomiError
import mihon.suwayomi.SuwayomiExtension
import mihon.suwayomi.SuwayomiManga
import mihon.suwayomi.SuwayomiRepository
import mihon.suwayomi.SuwayomiResult
import mihon.suwayomi.SuwayomiSource
import mihon.suwayomi.SuwayomiUpdateStatus

data class BrowseState(
    val source: SuwayomiSource? = null,
    val type: SuwayomiBrowseType = SuwayomiBrowseType.POPULAR,
    val query: String = "",
    val mangas: List<SuwayomiManga> = emptyList(),
    val page: Int = 0,
    val hasNextPage: Boolean = false,
    val loading: Boolean = false,
)

class SuwayomiHomeScreenModel(private val repository: SuwayomiRepository) : ViewModel() {

    val library: StateFlow<Loadable<List<SuwayomiManga>>>
        field = MutableStateFlow<Loadable<List<SuwayomiManga>>>(Loadable.Loading)

    val categories: StateFlow<List<SuwayomiCategory>>
        field = MutableStateFlow<List<SuwayomiCategory>>(emptyList())

    /** Null shows the whole library. */
    val selectedCategory: StateFlow<Int?>
        field = MutableStateFlow<Int?>(null)

    val sources: StateFlow<Loadable<List<SuwayomiSource>>>
        field = MutableStateFlow<Loadable<List<SuwayomiSource>>>(Loadable.Loading)

    val browse: StateFlow<BrowseState>
        field = MutableStateFlow(BrowseState())

    val downloads: StateFlow<Loadable<SuwayomiDownloadStatus>>
        field = MutableStateFlow<Loadable<SuwayomiDownloadStatus>>(Loadable.Loading)

    val extensions: StateFlow<Loadable<List<SuwayomiExtension>>>
        field = MutableStateFlow<Loadable<List<SuwayomiExtension>>>(Loadable.Loading)

    val updateStatus: StateFlow<Loadable<SuwayomiUpdateStatus>>
        field = MutableStateFlow<Loadable<SuwayomiUpdateStatus>>(Loadable.Loading)

    /** The last failed action, shown once by the screen and then cleared with [consumeError]. */
    val error: StateFlow<SuwayomiError?>
        field = MutableStateFlow<SuwayomiError?>(null)

    val repo: SuwayomiRepository get() = repository

    fun consumeError() {
        error.value = null
    }

    private fun <T> MutableStateFlow<Loadable<T>>.load(call: suspend () -> SuwayomiResult<T>) {
        viewModelScope.launch {
            if (value !is Loadable.Loaded) value = Loadable.Loading
            value = when (val result = call()) {
                is SuwayomiResult.Ok -> Loadable.Loaded(result.value)
                is SuwayomiResult.Err -> Loadable.Failed(result.error)
            }
        }
    }

    /** Runs an action whose failure should be reported as a message, then calls [onOk]. */
    private fun <T> act(call: suspend () -> SuwayomiResult<T>, onOk: (T) -> Unit = {}) {
        viewModelScope.launch {
            when (val result = call()) {
                is SuwayomiResult.Ok -> onOk(result.value)
                is SuwayomiResult.Err -> error.value = result.error
            }
        }
    }

    // region Library

    fun loadLibrary() {
        library.load { repository.getLibrary() }
        viewModelScope.launch {
            repository.getCategories().let { if (it is SuwayomiResult.Ok) categories.value = it.value }
        }
    }

    fun selectCategory(id: Int?) {
        selectedCategory.value = id
    }

    fun createCategory(name: String) {
        if (name.isBlank()) return
        act({ repository.createCategory(name.trim()) }) { loadLibrary() }
    }

    // endregion

    // region Browse

    fun loadSources() = sources.load { repository.getSources() }

    fun openSource(source: SuwayomiSource?) {
        browseJob?.cancel()
        browse.value = BrowseState(source = source)
        if (source != null) fetchBrowse(reset = true)
    }

    fun setBrowseType(type: SuwayomiBrowseType) {
        browse.update { it.copy(type = type) }
        fetchBrowse(reset = true)
    }

    fun search(query: String) {
        browse.update {
            it.copy(query = query, type = if (query.isBlank()) SuwayomiBrowseType.POPULAR else SuwayomiBrowseType.SEARCH)
        }
        fetchBrowse(reset = true)
    }

    fun loadMore() {
        val current = browse.value
        if (current.loading || !current.hasNextPage) return
        fetchBrowse(reset = false)
    }

    private var browseJob: Job? = null

    private fun fetchBrowse(reset: Boolean) {
        val current = browse.value
        val source = current.source ?: return
        val page = if (reset) 1 else current.page + 1
        browse.update { it.copy(loading = true, mangas = if (reset) emptyList() else it.mangas) }
        // A newer request supersedes the one in flight, so a slow older answer can never overwrite it.
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            val result = repository.browse(source.id, current.type, page, current.query)
            when (result) {
                is SuwayomiResult.Ok -> browse.update {
                    it.copy(
                        mangas = if (reset) result.value.mangas else it.mangas + result.value.mangas,
                        page = page,
                        hasNextPage = result.value.hasNextPage,
                        loading = false,
                    )
                }
                is SuwayomiResult.Err -> {
                    browse.update { it.copy(loading = false) }
                    error.value = result.error
                }
            }
        }
    }

    fun toggleInLibrary(manga: SuwayomiManga) {
        act({ repository.setInLibrary(manga.id, !manga.inLibrary) }) { updated ->
            browse.update { state ->
                state.copy(mangas = state.mangas.map { if (it.id == updated.id) updated else it })
            }
            loadLibrary()
        }
    }

    // endregion

    // region Downloads

    fun loadDownloads() = downloads.load { repository.getDownloadStatus() }

    private fun downloaderAction(call: suspend () -> SuwayomiResult<SuwayomiDownloadStatus>) =
        act(call) { downloads.value = Loadable.Loaded(it) }

    fun startDownloader() = downloaderAction { repository.startDownloader() }

    fun stopDownloader() = downloaderAction { repository.stopDownloader() }

    fun clearDownloads() = downloaderAction { repository.clearDownloadQueue() }

    fun dequeue(chapterId: Int) = downloaderAction { repository.dequeueDownloads(listOf(chapterId)) }

    // endregion

    // region Extensions

    fun loadExtensions() = extensions.load { repository.getExtensions() }

    fun refreshExtensions() = extensions.load { repository.fetchExtensions() }

    fun installExtension(extension: SuwayomiExtension) =
        act({ repository.installExtension(extension.pkgName) }) { reloadExtensions() }

    fun updateExtension(extension: SuwayomiExtension) =
        act({ repository.updateExtension(extension.pkgName) }) { reloadExtensions() }

    fun uninstallExtension(extension: SuwayomiExtension) =
        act({ repository.uninstallExtension(extension.pkgName) }) { reloadExtensions() }

    private fun reloadExtensions() {
        viewModelScope.launch {
            repository.getExtensions().let { if (it is SuwayomiResult.Ok) extensions.value = Loadable.Loaded(it.value) }
        }
    }

    // endregion

    // region Library updates

    fun loadUpdateStatus() = updateStatus.load { repository.getLibraryUpdateStatus() }

    fun updateLibrary() {
        val category = selectedCategory.value
        act({ repository.updateLibrary(listOfNotNull(category)) }) { updateStatus.value = Loadable.Loaded(it) }
    }

    // endregion
}
