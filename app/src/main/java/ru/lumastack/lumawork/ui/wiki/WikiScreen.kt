package ru.lumastack.lumawork.ui.wiki

import ru.lumastack.lumawork.ui.theme.AppRefreshContainer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import ru.lumastack.lumawork.ui.theme.AppButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton as OutlinedButton
import ru.lumastack.lumawork.ui.theme.AppTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.net.toUri
import ru.lumastack.lumawork.data.wiki.WikiRepository
import ru.lumastack.lumawork.domain.wiki.WikiBlock
import ru.lumastack.lumawork.domain.wiki.WikiSearchResult
import ru.lumastack.lumawork.domain.wiki.cleanWikiText
import ru.lumastack.lumawork.ui.adaptive.AppWidthClass

@Composable
fun WikiRoute(
    ownerUserId: String,
    startOffline: Boolean,
    repository: WikiRepository,
    widthClass: AppWidthClass,
    modifier: Modifier = Modifier,
) {
    val wikiViewModel: WikiViewModel = viewModel(
        key = "wiki-$ownerUserId-$startOffline",
        factory = WikiViewModel.factory(repository, ownerUserId, startOffline),
    )
    val state by wikiViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); wikiViewModel.notice(null) } }
    androidx.compose.material3.Scaffold(snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) }, modifier = modifier) { padding ->
    WikiScreen(
        state = state,
        widthClass = widthClass,
        onQueryChange = wikiViewModel::updateQuery,
        onRefresh = wikiViewModel::refresh,
        onOpen = wikiViewModel::open,
        onCloseArticle = wikiViewModel::closeArticle,
        pdfUrl = wikiViewModel::pdfUrl,
        modifier = Modifier.padding(padding),
    )
    }
}

@Composable
private fun WikiScreen(
    state: WikiUiState,
    widthClass: AppWidthClass,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (WikiSearchResult) -> Unit,
    onCloseArticle: () -> Unit,
    pdfUrl: () -> String?,
    modifier: Modifier = Modifier,
) {
    val background = ru.lumastack.lumawork.ui.theme.appScreenBackground()
    val searchState = rememberLazyListState()
    val articleState = rememberLazyListState()
    var displayedArticleId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.selectedResult?.id) {
        if (displayedArticleId != state.selectedResult?.id) {
            articleState.scrollToItem(0)
            displayedArticleId = state.selectedResult?.id
        }
    }
    BackHandler(enabled = state.selectedResult != null, onBack = onCloseArticle)
    Box(modifier.fillMaxSize().background(background), contentAlignment = Alignment.TopCenter) {
        if (widthClass != AppWidthClass.Expanded) {
            if (state.selectedResult == null) {
                WikiSearchPanel(state, onQueryChange, onRefresh, onOpen,
                    Modifier.widthIn(max = 680.dp).fillMaxSize(), listState = searchState)
            } else {
                WikiArticlePanel(state, onCloseArticle, pdfUrl,
                    Modifier.widthIn(max = 760.dp).fillMaxSize(), listState = articleState)
            }
        } else {
            Row(
                Modifier.widthIn(max = 1180.dp).fillMaxSize().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                WikiSearchPanel(
                    state, onQueryChange, onRefresh, onOpen,
                    Modifier.weight(0.42f).fillMaxHeight().widthIn(max = 470.dp),
                    listState = searchState,
                )
                Surface(
                    Modifier.weight(0.58f).fillMaxHeight(),
                    shape = RoundedCornerShape(26.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                    tonalElevation = 2.dp,
                ) {
                    if (state.selectedResult == null) {
                        WikiEmpty("Выберите статью", "Содержание откроется здесь, а результаты поиска останутся слева.")
                    } else {
                        WikiArticlePanel(state, onCloseArticle = null, pdfUrl = pdfUrl,
                            listState = articleState)
                    }
                }
            }
        }
    }
}

@Composable
private fun WikiSearchPanel(
    state: WikiUiState,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (WikiSearchResult) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    AppRefreshContainer(modifier, isRefreshing = state.isSearching, onRefresh = onRefresh, enabled = state.query.isNotBlank() && !state.offline) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                AppTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    label = "Поиск по инструкциям", search = true,
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        if (state.isSearching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "quick") {
                WikiQuickSearch(state.query, onQueryChange)
            }
            item(key = "summary") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Результаты", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.offline) "Без сети · сохранённые результаты" else if (state.results.isEmpty()) "База знаний VTB Wiki" else "${state.results.size} по запросу «${state.query.trim()}»",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            state.errorMessage?.let { item(key = "error") { WikiMessage(it, true) } }
            if (state.results.isEmpty() && !state.isSearching && state.errorMessage == null) {
                item(key = "empty") { WikiMessage("Ничего не найдено. Измените запрос или выберите быстрый фильтр.") }
            }
            items(state.results, key = WikiSearchResult::id) { result ->
                WikiResultCard(result, state.selectedResult?.id == result.id) { onOpen(result) }
            }
        }
    }
}

@Composable
private fun WikiQuickSearch(query: String, onQueryChange: (String) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("База знаний", fontWeight = FontWeight.Bold)
            Text(
                "Статьи и инструкции.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUICK_QUERIES.forEach { value ->
                    if (query.trim() == value) Button(onClick = { onQueryChange(value) }) { Text(value) }
                    else OutlinedButton(onClick = { onQueryChange(value) }) { Text(value) }
                }
            }
        }
    }
}

@Composable
private fun WikiResultCard(result: WikiSearchResult, selected: Boolean, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(result.title, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(result.section, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, maxLines = 2)
            Text(
                result.excerpt.cleanWikiText(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WikiArticlePanel(
    state: WikiUiState,
    onCloseArticle: (() -> Unit)?,
    pdfUrl: () -> String?,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val result = state.selectedResult ?: return
    LazyColumn(
        modifier,
        state = listState,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "article-header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onCloseArticle != null) TextButton(onClick = onCloseArticle) { Text("Назад") }
                Column(Modifier.weight(1f)) {
                    Text(state.document?.title ?: result.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(state.document?.section ?: result.section, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.isArticleLoading && state.article == null) {
            item(key = "article-loading") {
                Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                }
            }
        }
        state.articleErrorMessage?.let { item(key = "article-error") { WikiMessage(it, true) } }
        state.article?.takeIf { it.truncated == true }?.let {
            item(key = "truncated") { WikiMessage("Статья показана не полностью. Уточните запрос или откройте источник.") }
        }
        state.article?.let { article ->
            item(key = "actions") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { openUrl(context, state.document?.sourceUrl ?: article.sourceUrl) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Источник") }
                    OutlinedButton(
                        onClick = { copyText(context, article.content) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Копировать") }
                    if (state.isPdfAvailable) {
                        OutlinedButton(
                            onClick = { pdfUrl()?.let { openUrl(context, it) } },
                            modifier = Modifier.weight(1f),
                        ) { Text("PDF") }
                    }
                }
            }
        }
        state.document?.convertedAt?.let { date ->
            item(key = "converted") { Text("Дата конвертации: $date", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
        }
        state.document?.blocks?.let { blocks ->
            itemsIndexed(blocks, key = { index, block -> "$index-${block.hashCode()}" }) { _, block ->
                WikiBlockView(block)
            }
        }
        if (state.didCheckPdf && !state.isPdfAvailable && state.article != null) {
            item(key = "no-pdf") { Text("PDF для этой статьи пока недоступен.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
        item(key = "bottom") { Spacer(Modifier.size(4.dp)) }
    }
}

@Composable
private fun WikiBlockView(block: WikiBlock) {
    when (block) {
        is WikiBlock.Heading -> Text(
            block.title,
            style = if (block.level == 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = if (block.level == 2) 8.dp else 2.dp),
        )
        is WikiBlock.Paragraph -> Text(
            block.text,
            color = if (block.important) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (block.important) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                    RoundedCornerShape(16.dp),
                )
                .padding(if (block.important) 13.dp else 0.dp),
        )
        is WikiBlock.Bullets -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.items.forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 7.dp).size(5.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        Text(item, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        is WikiBlock.Image -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Изображение: ${block.title}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(13.dp))
        }
    }
}

@Composable
private fun WikiMessage(message: String, error: Boolean = false) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(
            message,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(15.dp),
        )
    }
}

@Composable
private fun WikiEmpty(title: String, message: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun openUrl(context: Context, value: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, value.toUri())) }
}

private fun copyText(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Wiki", value))
}

private val QUICK_QUERIES = listOf("SimpleOne", "PinPad", "ККТ", "VPN", "GoFix")
