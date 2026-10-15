package ru.lumastack.lumawork.domain.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiModelsTest {
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
}
