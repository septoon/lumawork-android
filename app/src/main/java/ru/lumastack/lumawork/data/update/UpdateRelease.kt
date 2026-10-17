package ru.lumastack.lumawork.data.update

import java.net.URI
import java.time.Instant
import kotlinx.serialization.json.*

data class UpdateRelease(
    val versionName: String, val versionCode: Long, val downloadUrl: String,
    val sha256: String, val size: Long, val minSdk: Int, val changelog: List<String>,
    val signingCertificateSHA256: String,
) {
    fun isNewerThan(installedCode: Long) = versionCode > installedCode
    companion object {
        const val METADATA_URL = "https://app.lumastack.ru/android/latest.json"
        fun parse(text: String): UpdateRelease = try {
            require(text.length <= 131072)
            val data = Json.parseToJsonElement(text).jsonObject
            fun string(key: String): String {
                val p = data.getValue(key).jsonPrimitive
                require(p.isString)
                return p.content
            }
            fun number(key: String): Long {
                val p = data.getValue(key).jsonPrimitive
                require(!p.isString)
                return requireNotNull(p.longOrNull)
            }
            require(number("schemaVersion") == 1L)
            require(string("applicationId") == "ru.lumastack.lumawork")
            val name = string("versionName").also { require(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) }
            val code = number("versionCode").also { require(it in 1..2100000000) }
            val url = string("downloadUrl")
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host == "app.lumastack.ru" && uri.port == -1 && uri.userInfo == null && uri.query == null && uri.fragment == null)
            require(uri.rawPath == "/android/releases/LumaWork-Android-$name-$code.apk")
            val hash = string("sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            val cert = string("signingCertificateSHA256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            val size = number("size").also { require(it in 1..536870912) }
            val sdk = number("minSdk").also { require(it in 26..1000) }.toInt()
            val date = Instant.parse(string("publishedAt"))
            require(!date.isAfter(Instant.now().plusSeconds(300)))
            val notes = data.getValue("changelog").jsonArray.map { element ->
                val p = element.jsonPrimitive
                require(p.isString && p.content.isNotBlank() && p.content.length <= 4000)
                p.content
            }.also { require(it.size in 1..100) }
            UpdateRelease(name, code, url, hash, size, sdk, notes, cert)
        } catch (e: Exception) {
            throw IllegalArgumentException("Некорректные данные Android-релиза.", e)
        }
    }
}
