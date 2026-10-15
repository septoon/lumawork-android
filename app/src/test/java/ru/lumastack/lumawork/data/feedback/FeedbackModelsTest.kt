package ru.lumastack.lumawork.data.feedback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackModelsTest {
    @Test fun `draft requires title message and at least one area`() {
        assertFalse(FeedbackDraft(title = "Сбой", message = "Кнопка не выполняет ожидаемое действие.").valid)
        assertTrue(FeedbackDraft(title = "Сбой кнопки", message = "Кнопка не выполняет ожидаемое действие.", areaCodes = setOf("home")).valid)
    }

    @Test fun `other area requires a description`() {
        assertFalse(FeedbackDraft(title = "Сбой кнопки", message = "Кнопка не выполняет ожидаемое действие.", areaCodes = setOf("other")).valid)
        assertTrue(FeedbackDraft(title = "Сбой кнопки", message = "Кнопка не выполняет ожидаемое действие.", areaCodes = setOf("other"), otherArea = "Настройки").valid)
    }
}
