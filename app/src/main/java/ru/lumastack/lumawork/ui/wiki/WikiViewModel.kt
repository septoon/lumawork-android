package ru.lumastack.lumawork.ui.wiki

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.data.wiki.WikiFailure
import ru.lumastack.lumawork.data.wiki.WikiRepository
import ru.lumastack.lumawork.domain.wiki.WikiArticle
import ru.lumastack.lumawork.domain.wiki.WikiDocument
import ru.lumastack.lumawork.domain.wiki.WikiDocumentParser
import ru.lumastack.lumawork.domain.wiki.WikiSearchResult

data class WikiUiState(
    val query: String = "инструкция",
    val results: List<WikiSearchResult> = emptyList(),
    val selectedResult: WikiSearchResult? = null,
    val article: WikiArticle? = null,
    val document: WikiDocument? = null,
    val isSearching: Boolean = false,
    val isArticleLoading: Boolean = false,
    val isPdfAvailable: Boolean = false,
    val didCheckPdf: Boolean = false,
    val errorMessage: String? = null,
    val articleErrorMessage: String? = null,
    val notice: String? = null,
    val offline: Boolean = false,
    val snapshotId: String? = null,
)

class WikiViewModel(
    private val repository: WikiRepository,
    private val ownerUserId: String,
    private val startOffline: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(WikiUiState(offline = startOffline))
    private var offlineResults = emptyList<WikiSearchResult>()
    private var offlineQuery = ""
    val state = mutableState.asStateFlow()
    private var searchJob: Job? = null
    private var articleJob: Job? = null
    private var catalogJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val cached = repository.readSearch(ownerUserId)
                if (cached != null) {
                    offlineResults = cached.results; offlineQuery = cached.query
                    mutableState.update { it.copy(query = cached.query, results = cached.results, snapshotId = cached.snapshotId) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("Не удалось прочитать сохранённый поиск Вики.") }
            if (!startOffline) { scheduleSearch(immediate = true); refreshCatalogVersion() }
        }
    }

    fun updateQuery(value: String) {
        mutableState.update { it.copy(query = value, errorMessage = null) }
        scheduleSearch(immediate = false)
    }

    fun refresh() { scheduleSearch(immediate = true); refreshCatalogVersion() }
    fun refreshCatalogVersion() {
        if (startOffline) return
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            try {
                repository.refreshCatalogVersion(ownerUserId)?.let { version -> mutableState.update { it.copy(snapshotId = version) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the last known catalog version, like iOS. */ }
        }
    }
    fun notice(value: String?) { mutableState.update { it.copy(notice = value) } }

    fun open(result: WikiSearchResult) {
        articleJob?.cancel()
        mutableState.update {
            it.copy(selectedResult = result, article = null, document = null, isArticleLoading = true,
                articleErrorMessage = null, isPdfAvailable = false, didCheckPdf = false)
        }
        articleJob = viewModelScope.launch {
            try { repository.readArticle(result.id)?.let(::showArticle) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("Не удалось прочитать сохранённую статью.") }
            try {
                if (startOffline) {
                    mutableState.update { it.copy(isArticleLoading = false, articleErrorMessage = if (it.article == null) "Статья ещё не сохранена. Подключитесь к сети." else null) }
                    return@launch
                }
                showArticle(repository.refreshArticle(result.id))
                mutableState.update { it.copy(isArticleLoading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(isArticleLoading = false, notice = error.userMessage())
                }
            }
            val available = try { repository.isPdfAvailable(result.id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            mutableState.update { it.copy(isPdfAvailable = available, didCheckPdf = true) }
        }
    }

    fun closeArticle() {
        articleJob?.cancel()
        mutableState.update { it.copy(selectedResult = null, article = null, document = null, articleErrorMessage = null, isArticleLoading = false) }
    }

    fun pdfUrl(): String? = mutableState.value.selectedResult?.id?.let(repository::pdfUrl)

    private fun scheduleSearch(immediate: Boolean) {
        searchJob?.cancel()
        if (startOffline) {
            val query = mutableState.value.query.trim()
            val results = if (query == offlineQuery.trim()) offlineResults else offlineResults.filter {
                "${it.title} ${it.section} ${it.excerpt}".contains(query, ignoreCase = true)
            }
            mutableState.update { it.copy(isSearching = false, results = results) }
            return
        }
        searchJob = viewModelScope.launch {
            val query = mutableState.value.query.trim()
            if (query.isEmpty()) {
                mutableState.update { it.copy(results = emptyList(), isSearching = false, errorMessage = null) }
                return@launch
            }
            if (!immediate) delay(350)
            mutableState.update { it.copy(isSearching = true, errorMessage = null) }
            try {
                val snapshot = repository.search(ownerUserId, query)
                if (mutableState.value.query.trim() == query) {
                    mutableState.update { it.copy(results = snapshot.results, isSearching = false) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(isSearching = false, notice = error.userMessage()) }
            }
        }
    }

    private fun showArticle(article: WikiArticle) {
        mutableState.update { it.copy(article = article, document = WikiDocumentParser.parse(article)) }
    }

    private fun Throwable.userMessage(): String = (this as? WikiFailure)?.message ?: "Вики временно недоступна."

    companion object {
        fun factory(repository: WikiRepository, ownerUserId: String, startOffline: Boolean): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = WikiViewModel(repository, ownerUserId, startOffline) as T
            }
    }
}
