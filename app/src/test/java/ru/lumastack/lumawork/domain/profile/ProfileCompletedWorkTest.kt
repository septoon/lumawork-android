package ru.lumastack.lumawork.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequest
import ru.lumastack.lumawork.domain.simpleone.SimpleOneRequestSource

class ProfileCompletedWorkTest {
    @Test
    fun `empty statistics keep all categories in iOS order and hide demounting`() {
        val statistics = ProfileCompletedWorkStatistics(emptyList())
        assertEquals(listOf("Переносные", "Стационарные", "Интеграции", "Кассы"), statistics.categories.map { it.category.title })
        statistics.categories.forEach {
            assertEquals(0, it.total)
            assertEquals(listOf(ProfileWorkOperation.Install, ProfileWorkOperation.Replacement, ProfileWorkOperation.ServiceStd), it.operations)
        }
    }

    @Test
    fun `only closure codes with successful visit prefix qualify`() {
        val records = listOf(
            request("yes", closureCode = " \tРЕШЕНО\n С  ВЫЕЗДОМ — выполнено "),
            request("refusal", closureCode = "Отказ с выездом"),
            request("noVisit", closureCode = "Решено без выезда"),
            request("embedded", closureCode = "Не решено с выездом"),
            request("stateOnly", closureCode = "").copy(state = "Выполнена"),
        )
        assertEquals(1, ProfileCompletedWorkStatistics(records).summary(ProfileWorkCategory.Portable).total)
    }

    @Test
    fun `operation codes are trimmed but case sensitive and unknown operations are ignored`() {
        val records = listOf(
            request("install", " install "),
            request("replace", "replacement"),
            request("service", "serviceStd"),
            request("badCase", "servicestd"),
            request("displayTitle", "Установка"),
            request("unknown", "returnEquip"),
        )
        val summary = ProfileCompletedWorkStatistics(records).summary(ProfileWorkCategory.Portable)
        assertEquals(3, summary.total)
        assertEquals(1, summary.count(ProfileWorkOperation.Install))
        assertEquals(1, summary.count(ProfileWorkOperation.Replacement))
        assertEquals(1, summary.count(ProfileWorkOperation.ServiceStd))
    }

    @Test
    fun `terminal codes are case insensitive and universal joins portable`() {
        val records = listOf(
            request("portable", terminalType = " PORTABLE_POS "),
            request("universal", terminalType = "universal_pos"),
            request("stationary", terminalType = "stat_pos"),
            request("integration", terminalType = "INTEL_PIN"),
            request("fiscal", terminalType = "fiscal_pos"),
            request("unknown", terminalType = "other"),
        )
        assertEquals(listOf(2, 1, 1, 1), ProfileCompletedWorkStatistics(records).categories.map { it.total })
    }

    @Test
    fun `installation labels take precedence with generic terminal fallback`() {
        val records = listOf(
            request("installed").copy(additionalInformation = "Тип терминала: fiscal_pos\nТип устанавливаемого ТО: stat_pos"),
            request("fallback").copy(additionalInformation = "Тип устанавливаемого ТО: unknown\nТип терминала: intel_pin"),
            request("wrongLabel").copy(additionalInformation = "Тип демонтируемого ТО: fiscal_pos"),
        )
        assertEquals(listOf(0, 1, 1, 0), ProfileCompletedWorkStatistics(records).categories.map { it.total })
    }

    @Test
    fun `demounting uses dismantled then installed then generic label and adds its line only when counted`() {
        val records = listOf(
            request("demount", "dismounting").copy(additionalInformation = "Тип устанавливаемого ТО: stat_pos\nТип демонтируемого ТО: fiscal_pos\nТип терминала: portable_pos"),
            request("installedFallback", "dismounting").copy(additionalInformation = "Тип демонтируемого ТО: unknown\nТип устанавливаемого ТО: intel_pin"),
            request("genericFallback", "dismounting").copy(additionalInformation = "Тип терминала: stat_pos"),
        )
        val statistics = ProfileCompletedWorkStatistics(records)
        assertEquals(listOf(0, 1, 1, 1), statistics.categories.map { it.total })
        assertFalse(statistics.summary(ProfileWorkCategory.Portable).operations.contains(ProfileWorkOperation.Dismounting))
        assertTrue(statistics.summary(ProfileWorkCategory.Fiscal).operations.contains(ProfileWorkOperation.Dismounting))
    }

    @Test
    fun `trimmed request numbers deduplicate only after full eligibility and preserve case`() {
        val records = listOf(
            request("REQ", terminalType = "unknown"),
            request("REQ", "unknown"),
            request("REQ", closureCode = "Отказ"),
            request(" REQ ", terminalType = "stat_pos"),
            request("REQ", terminalType = "fiscal_pos"),
            request("req", terminalType = "fiscal_pos"),
            request(" \n "),
        )
        assertEquals(listOf(0, 1, 0, 1), ProfileCompletedWorkStatistics(records).categories.map { it.total })
    }

    @Test
    fun `labels ignore surrounding whitespace colons and case without collapsing interior spaces`() {
        val records = listOf(
            request("label").copy(additionalInformation = "  тИп УСТАНАВЛИВАЕМОГО то :  FISCAL_POS "),
            request("direct").copy(additionalInformation = "", tableFields = mapOf(" : Тип терминала : " to "stat_pos")),
            request("differentLabel").copy(additionalInformation = "Тип  терминала: portable_pos"),
        )
        assertEquals(listOf(0, 1, 0, 1), ProfileCompletedWorkStatistics(records).categories.map { it.total })
    }

    @Test
    fun `information sources follow iOS parsed text then original table columns precedence`() {
        val records = listOf(
            request("description").copy(additionalInformation = "", description = "Тип терминала: stat_pos"),
            request("tableInformation").copy(additionalInformation = "", tableFields = mapOf("Информация" to "Тип терминала: intel_pin")),
            request("tableAdditional").copy(additionalInformation = "", tableFields = mapOf("Доп. информация" to "Тип терминала: fiscal_pos")),
            request("precedence").copy(description = "Тип устанавливаемого ТО: fiscal_pos", tableFields = mapOf("Тип устанавливаемого ТО" to "stat_pos")),
        )
        assertEquals(listOf(1, 1, 1, 1), ProfileCompletedWorkStatistics(records).categories.map { it.total })
    }

    @Test
    fun `first duplicate label wins even when unknown and next label supplies fallback`() {
        val record = request("duplicateLabel").copy(
            additionalInformation = "Тип устанавливаемого ТО: unknown\nТип устанавливаемого ТО: portable_pos\nТип терминала: fiscal_pos",
        )
        assertEquals(listOf(0, 0, 0, 1), ProfileCompletedWorkStatistics(listOf(record)).categories.map { it.total })
    }

    @Test
    fun `missing explicit closure code falls back to retained information`() {
        val record = request("closureFallback", closureCode = "").copy(
            additionalInformation = "Тип терминала: portable_pos\nКод закрытия: Решено с выездом",
        )
        assertEquals(1, ProfileCompletedWorkStatistics(listOf(record)).summary(ProfileWorkCategory.Portable).total)
    }

    private fun request(
        number: String,
        operation: String = "install",
        terminalType: String = "portable_pos",
        closureCode: String = "Решено с выездом",
    ) = SimpleOneRequest(
        sysId = number,
        number = number,
        source = SimpleOneRequestSource.Closed,
        requestType = operation,
        closureCode = closureCode,
        additionalInformation = "Тип устанавливаемого ТО: $terminalType",
    )

    private fun ProfileCompletedWorkStatistics.summary(category: ProfileWorkCategory) = categories.first { it.category == category }
}
