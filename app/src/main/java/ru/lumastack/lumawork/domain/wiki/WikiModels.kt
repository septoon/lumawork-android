package ru.lumastack.lumawork.domain.wiki

import kotlinx.serialization.Serializable

@Serializable
data class WikiSearchResult(
    val id: String,
    val title: String,
    val section: String,
    val sourceUrl: String,
    val score: Int,
    val excerpt: String,
    val hasPdf: Boolean? = null,
    val pdfUrl: String? = null,
)

@Serializable
data class WikiArticle(
    val id: String,
    val title: String,
    val section: String,
    val sourceUrl: String,
    val relativePath: String? = null,
    val hasPdf: Boolean? = null,
    val pdfUrl: String? = null,
    val pdfRelativePath: String? = null,
    val content: String,
    val truncated: Boolean? = null,
    val contentLength: Int? = null,
)

@Serializable
data class WikiSearchSnapshot(
    val query: String,
    val results: List<WikiSearchResult>,
    val updatedAtEpochMillis: Long? = null,
)

data class WikiDocument(
    val title: String,
    val section: String,
    val sourceUrl: String,
    val convertedAt: String?,
    val blocks: List<WikiBlock>,
)

sealed interface WikiBlock {
    data class Heading(val level: Int, val title: String) : WikiBlock
    data class Paragraph(val text: String, val important: Boolean) : WikiBlock
    data class Bullets(val items: List<String>) : WikiBlock
    data class Image(val title: String) : WikiBlock
}

object WikiDocumentParser {
    fun parse(article: WikiArticle): WikiDocument {
        val lines = article.content.replace("\r", "").lines().toMutableList()
        val title = firstValue("#", lines) ?: article.title
        val section = metadata("Раздел:", lines) ?: article.section
        val source = metadata("Источник:", lines) ?: article.sourceUrl
        val convertedAt = metadata("Дата конвертации:", lines)?.replace(Regex("T.*$"), "")
        lines.removeAll { raw ->
            val line = raw.trim()
            line.isEmpty() || line == "---" || line == "# $title" ||
                line.startsWith("Источник:") || line.startsWith("Раздел:") || line.startsWith("Дата конвертации:")
        }
        return WikiDocument(title, section, source, convertedAt, parseBlocks(lines))
    }

    private fun parseBlocks(lines: List<String>): List<WikiBlock> {
        val blocks = mutableListOf<WikiBlock>()
        val paragraph = mutableListOf<String>()
        val bullets = mutableListOf<String>()
        fun flushParagraph() {
            val text = paragraph.joinToString(" ").cleanWikiText()
            paragraph.clear()
            if (text.isNotEmpty()) blocks += WikiBlock.Paragraph(text, text.contains("ВАЖНО", ignoreCase = true))
        }
        fun flushBullets() {
            val items = bullets.map(String::cleanWikiText).filter(String::isNotEmpty)
            bullets.clear()
            if (items.isNotEmpty()) blocks += WikiBlock.Bullets(items)
        }
        lines.forEach { raw ->
            var line = raw.trim()
            if (line.isEmpty()) {
                flushParagraph(); flushBullets(); return@forEach
            }
            imageTitle(line)?.let {
                flushParagraph(); flushBullets(); blocks += WikiBlock.Image(it)
                val remainingText = line.removeWikiImageMarkup().cleanWikiText()
                if (remainingText.isNotEmpty()) paragraph += remainingText
                return@forEach
            }
            val withoutImageWrapper = line.removeWikiImageWrapperPrefix()
            if (withoutImageWrapper != line) {
                flushParagraph(); flushBullets()
                line = withoutImageWrapper
                if (line.isEmpty()) return@forEach
            }
            if (line.startsWith("## ") || line.startsWith("### ")) {
                flushParagraph(); flushBullets()
                val level = if (line.startsWith("### ")) 3 else 2
                blocks += WikiBlock.Heading(level, line.replace(Regex("^#{2,3}\\s+"), "").cleanWikiText())
                return@forEach
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                flushParagraph(); bullets += line.drop(2); return@forEach
            }
            if (line.matches(Regex("^\\d+\\.\\s+.*"))) {
                flushParagraph(); bullets += line.replace(Regex("^\\d+\\.\\s+"), ""); return@forEach
            }
            paragraph += line
        }
        flushParagraph(); flushBullets()
        return blocks.ifEmpty { listOf(WikiBlock.Paragraph("Статья не содержит распознанного текста.", false)) }
    }

    private fun metadata(prefix: String, lines: List<String>): String? = lines.firstOrNull { it.trim().startsWith(prefix) }
        ?.trim()?.removePrefix(prefix)?.trim()?.takeIf(String::isNotEmpty)
    private fun firstValue(prefix: String, lines: List<String>): String? = lines.firstOrNull { it.trim().startsWith("$prefix ") }
        ?.trim()?.removePrefix(prefix)?.trim()?.takeIf(String::isNotEmpty)
    private fun imageTitle(value: String): String? {
        if (wikiEmptyImageMarkup.containsMatchIn(value)) return "Изображение"
        val title = wikiImageMarkup.firstNotNullOfOrNull { it.find(value)?.groupValues?.get(1) } ?: return null
        return title.cleanWikiText().ifEmpty { "Изображение" }
    }
}

private val wikiImageMarkup = listOf(
    Regex("""\[!\[([^]]*)\]\([^)]+\)\]\([^)]+\)"""),
    Regex("""!\[([^]]*)\]\([^)]+\)"""),
)
private val wikiEmptyImageMarkup = Regex("""\[\]\([^)]+\)""")

private fun String.removeWikiImageMarkup(): String = wikiImageMarkup.fold(this) { value, pattern ->
    value.replace(pattern, "")
}.replace(wikiEmptyImageMarkup, "").removeWikiImageWrapperPrefix()

private fun String.removeWikiImageWrapperPrefix(): String {
    val source = trim()
    val withoutLink = source.replace(Regex("""^(?:>\s*)*\]\(https?://[^)]+\)"""), "").trim()
    val meaningfulText = withoutLink.replace(Regex("""[>\[\]\\*_;⇒→.\-]"""), "").trim()
    if (meaningfulText.isEmpty()) return ""
    if (withoutLink == source) return source
    return withoutLink.replace(Regex("""^[>\[\]\\;⇒→.]+\s*"""), "").trim()
}

fun String.cleanWikiText(): String = removeWikiImageMarkup()
    .replace(Regex("\\[([^]]+)\\]\\([^)]+\\)"), "$1")
    .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
    .replace(Regex("__([^_]+)__"), "$1")
    .replace(Regex("_([^_]+)_"), "$1")
    .replace(Regex("`([^`]+)`"), "$1")
    .replace("\\", "")
    .replace(Regex("\\s+"), " ")
    .trim()
    .replace("ВАЖНО!", "ВАЖНО:")
