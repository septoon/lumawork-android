package ru.lumastack.lumawork

import android.os.Bundle
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import ru.lumastack.lumawork.ui.shell.PreviewShell

/** Uses the existing preview repositories; never included in a release build. */
class DebugPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val previewContext = remember { PreviewStorageContext(this) }
            CompositionLocalProvider(LocalContext provides previewContext) { PreviewShell() }
        }
    }
}

/** Keep fixture PINs and documents separate from the ordinary debug application. */
private class PreviewStorageContext(base: Context) : ContextWrapper(base) {
    override fun getSharedPreferences(name: String, mode: Int) =
        super.getSharedPreferences("ui-preview-$name", mode)

    override fun getFilesDir() = File(super.getFilesDir(), "ui-preview").apply { mkdirs() }
    override fun getCacheDir() = File(super.getCacheDir(), "ui-preview").apply { mkdirs() }
    override fun getNoBackupFilesDir() = File(super.getNoBackupFilesDir(), "ui-preview").apply { mkdirs() }
}
