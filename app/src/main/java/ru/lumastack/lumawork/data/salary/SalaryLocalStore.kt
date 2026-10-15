package ru.lumastack.lumawork.data.salary

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.lumastack.lumawork.data.session.EncryptedValueStore

class SalaryPasscodeStore(context: Context) {
    private val encrypted = EncryptedValueStore(
        context = context,
        preferencesName = "LumaWork.secure.salary",
        valueKey = "passcode",
        keyAlias = "LumaWork.SalaryPasscode",
    )

    fun read(): String? = encrypted.read()?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    fun save(value: String) {
        require(value.length == 4 && value.all(Char::isDigit))
        encrypted.save(value)
    }
}

class SalarySlipStore(context: Context, owner: String) {
    private val directory = File(
        context.filesDir,
        "salary-slips/${MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }}",
    )

    suspend fun months(): Set<String> = withContext(Dispatchers.IO) {
        directory.listFiles().orEmpty().mapNotNull { it.name.substringBeforeLast('.').takeIf(MONTH::matches) }.toSet()
    }

    suspend fun attach(month: String, sourceName: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(MONTH.matches(month))
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val extension = sourceName.substringAfterLast('.', "").lowercase().takeIf { it in EXTENSIONS }
            ?: throw IllegalArgumentException("Поддерживаются только PDF и HTML.")
        directory.mkdirs()
        EXTENSIONS.forEach { File(directory, "$month.$it").delete() }
        val destination = File(directory, "$month.$extension")
        val temporary = File(directory, "${destination.name}.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(destination)) { "Не удалось сохранить расчётный листок." }
    }

    suspend fun delete(month: String) = withContext(Dispatchers.IO) {
        EXTENSIONS.forEach { File(directory, "$month.$it").delete() }
    }

    fun file(month: String): File? = EXTENSIONS.asSequence().map { File(directory, "$month.$it") }.firstOrNull(File::isFile)

    companion object {
        private val MONTH = Regex("^\\d{4}-(0[1-9]|1[0-2])$")
        private val EXTENSIONS = setOf("pdf", "html", "htm")
        private const val MAX_BYTES = 10 * 1024 * 1024
    }
}
