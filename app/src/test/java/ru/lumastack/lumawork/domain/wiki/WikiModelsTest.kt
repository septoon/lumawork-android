package ru.lumastack.lumawork.domain.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlinx.serialization.json.Json

class WikiModelsTest {
    @Test
    fun `catalog shows publication time rather than export start time`() {
        assertEquals("Каталог обновлен 04.11.2026 в 20:24", wikiCatalogUpdateMessage("2026-11-04T17:24:31.096Z", ZoneId.of("Europe/Simferopol")))
    }

    @Test
    fun `legacy cached search time is not displayed as the catalog update date`() {
        val snapshot = Json.decodeFromString<WikiSearchSnapshot>("""{"query":"VPN","results":[],"updatedAtEpochMillis":1792712930000}""")
        assertEquals("Дата обновления каталога неизвестна.", wikiCatalogUpdateMessage(snapshot.catalogUpdatedAt))
    }

    @Test
    fun `catalog timestamp is interpreted as UTC and shown in the device zone`() {
        assertEquals("Каталог обновлен 23.10.2026 в 02:48", wikiCatalogUpdateMessage("2026-10-22T23:48:50Z", ZoneId.of("Europe/Simferopol")))
        assertEquals("Каталог обновлен 22.10.2026 в 23:48", wikiCatalogUpdateMessage("2026-10-22T23:48:50Z", ZoneId.of("UTC")))
    }

    @Test
    fun `publication timestamps with an offset describe the same instant`() {
        assertEquals("Каталог обновлен 04.11.2026 в 20:24", wikiCatalogUpdateMessage("2026-11-04T20:24:31+03:00", ZoneId.of("Europe/Simferopol")))
    }

    @Test
    fun `missing malformed and impossible catalog dates stay unknown`() {
        listOf(null, "", "legacy", "20261022T234850Z", "2026-02-30T12:00:00Z", "2026-10-22T25:00:00Z").forEach {
            assertEquals("Дата обновления каталога неизвестна.", wikiCatalogUpdateMessage(it, ZoneId.of("UTC")))
        }
    }

    @Test
    fun `parser extracts metadata headings bullets and important text`() {
        val document = WikiDocumentParser.parse(
            WikiArticle(
                id = "article",
                title = "Fallback",
                section = "Fallback section",
                sourceUrl = "https://example.com/fallback",
                content = """
                    # Настройка PinPad
                    Раздел: Эквайринг
                    Источник: https://example.com/source
                    Дата конвертации: 2026-08-12T10:00:00Z
                    ## Подключение
                    ВАЖНО! Отключите питание.
                    - Подключите кабель
                    2. Включите терминал
                """.trimIndent(),
            ),
        )

        assertEquals("Настройка PinPad", document.title)
        assertEquals("Эквайринг", document.section)
        assertEquals("2026-08-12", document.convertedAt)
        assertTrue(document.blocks.any { it is WikiBlock.Heading && it.title == "Подключение" })
        assertTrue(document.blocks.any { it is WikiBlock.Paragraph && it.important && it.text.startsWith("ВАЖНО:") })
        assertTrue(document.blocks.any { it is WikiBlock.Bullets && it.items.size == 2 })
    }

    @Test
    fun `cleaner removes markdown decorations and keeps readable text`() {
        assertEquals("Откройте инструкцию и код", "Откройте [инструкцию](https://example.com) и **код**".cleanWikiText())
    }

    @Test
    fun `article images use placeholders for the markup supported on iOS`() {
        listOf(
            "[![Подключение](images/thumb.png)](https://example.com/full.png)",
            "[![](images/thumb.png)](https://example.com/full.png)",
            "![Подключение](images/full.png)",
            "![](images/full.png)",
            "[](https://example.com/full.png)",
        ).forEach { markup ->
            val blocks = parse(markup).blocks
            assertEquals(markup, 1, blocks.size)
            assertTrue(markup, blocks.single() is WikiBlock.Image)
        }
    }

    @Test
    fun `image placeholder keeps the surrounding instruction without exposing markup`() {
        val blocks = parse("Сначала ![Схема](images/full.png) затем **подключите кабель**.").blocks

        assertTrue(blocks.first() is WikiBlock.Image)
        assertEquals(
            WikiBlock.Paragraph("Сначала затем подключите кабель.", false),
            blocks.last(),
        )
    }

    @Test
    fun `split image wrapper links are hidden without losing following text`() {
        val blocks = parse(
            """
                ![](images/thumb.png)
                > ](https://example.com/full.png) ⇒
                > ](https://example.com/other.png) ⇒ Продолжите настройку.
            """.trimIndent(),
        ).blocks

        assertTrue(blocks.first() is WikiBlock.Image)
        assertEquals(WikiBlock.Paragraph("Продолжите настройку.", false), blocks.last())
        assertEquals(2, blocks.size)
    }

    @Test
    fun `cleaner removes image labels and wrappers but keeps ordinary link captions`() {
        assertEquals(
            "Откройте инструкцию.",
            "![Схема](images/full.png) Откройте [инструкцию](https://example.com/help).".cleanWikiText(),
        )
        assertEquals("", "[![Схема](images/thumb.png)](https://example.com/full.png)".cleanWikiText())
        assertEquals("", "> ](https://example.com/full.png) ⇒".cleanWikiText())
    }

    private fun parse(content: String): WikiDocument = WikiDocumentParser.parse(
        WikiArticle("article", "Статья", "Раздел", "https://example.com/source", content = content),
    )
}
