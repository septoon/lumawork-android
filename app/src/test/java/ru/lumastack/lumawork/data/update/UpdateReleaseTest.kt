package ru.lumastack.lumawork.data.update

import org.junit.Assert.*
import org.junit.Test

class UpdateReleaseTest {
    private val valid = """{"schemaVersion":1,"applicationId":"ru.lumastack.lumawork","versionName":"0.1.1","versionCode":2,"downloadUrl":"https://app.lumastack.ru/android/releases/LumaWork-Android-0.1.1-2.apk","sha256":"${"a".repeat(64)}","signingCertificateSHA256":"${"b".repeat(64)}","size":1234,"publishedAt":"2026-10-16T12:00:00Z","minSdk":26,"changelog":["Исправления"]}"""
    @Test fun acceptsReleaseAndComparesCodes() {
        val r = UpdateRelease.parse(valid)
        assertTrue(r.isNewerThan(1))
        assertFalse(r.isNewerThan(2))
        assertFalse(r.isNewerThan(3))
    }
    @Test fun rejectsForeignPackageAndHost() {
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.parse(valid.replace("ru.lumastack.lumawork", "evil.package")) }
        assertThrows(IllegalArgumentException::class.java) { UpdateRelease.parse(valid.replace("https://app.lumastack.ru/", "https://evil.example/")) }
    }
    @Test fun rejectsWrongTypesAndCorruptedValues() {
        listOf(valid.replace("\"versionCode\":2", "\"versionCode\":\"2\""), valid.replace("\"size\":1234", "\"size\":-1"), valid.replace("a".repeat(64), "bad"), valid.replace("https://", "http://"), valid.replace("\"changelog\":[\"Исправления\"]", "\"changelog\":[]"), "{}").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { UpdateRelease.parse(value) }
        }
    }
}
