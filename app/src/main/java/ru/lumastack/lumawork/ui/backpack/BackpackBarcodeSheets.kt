package ru.lumastack.lumawork.ui.backpack

import ru.lumastack.lumawork.ui.theme.AppModalSheet
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.*
import kotlinx.coroutines.*
import ru.lumastack.lumawork.domain.backpack.SerialBarcode
import ru.lumastack.lumawork.ui.theme.AppButton
import ru.lumastack.lumawork.ui.theme.AppOutlinedButton
import ru.lumastack.lumawork.ui.theme.AppSurfaces

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SerialBarcodeSheet(value: String, onDismiss: () -> Unit) {
    val matrix = remember(value) { SerialBarcode.encode(value) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    AppModalSheet(onDismiss = onDismiss, maxWidth = 800.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Серийный номер", style = MaterialTheme.typography.titleLarge)
            if (matrix != null) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val barcodeWidth = maxOf(maxWidth, matrix.width.dp)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Canvas(Modifier.width(barcodeWidth).height(180.dp).background(Color.White)) {
                    val module = (size.width / matrix.width).toInt().coerceAtLeast(1)
                    val width = matrix.width * module.toFloat()
                    val start = (size.width - width) / 2
                    for (x in 0 until matrix.width) if (matrix[x, 0]) drawRect(Color.Black, Offset(start + x * module, 20.dp.toPx()), Size(module.toFloat(), size.height - 40.dp.toPx()))
                    }
                    }
                }
            } else Text("Для этого серийного номера штрихкод Code 128 недоступен. Значение можно скопировать.")
            SelectionContainer { Text(value, style = MaterialTheme.typography.titleMedium) }
            AppOutlinedButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("Серийный номер", value))) } }) { Text("Скопировать S/N") }
            TextButton(onClick = onDismiss) { Text("Готово") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackpackScannerSheet(onDismiss: () -> Unit, onScan: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var cameraAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var reading by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanner by remember { mutableStateOf<DecoratedBarcodeView?>(null) }
    val latestScan by rememberUpdatedState(onScan)
    val accept: (String) -> Unit = { raw -> SerialBarcode.serial(raw)?.let { serial ->
        if (!accepted) { accepted = true; scanner?.pause(); latestScan(serial) }
    } }
    val latestAccept by rememberUpdatedState(accept)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraAllowed = it }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !reading) {
            reading = true; error = null
            scanner?.pause()
            scope.launch {
                try {
                    val value = withContext(Dispatchers.Default) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                        val options = BitmapFactory.Options().apply {
                            var scale = 1
                            while (maxOf(bounds.outWidth, bounds.outHeight) / scale > 2048) scale *= 2
                            inSampleSize = scale
                        }
                        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                            ?: return@withContext null
                        try {
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                            SerialBarcode.decode(BinaryBitmap(HybridBinarizer(source)))
                                ?: SerialBarcode.decode(BinaryBitmap(HybridBinarizer(source.invert())))
                                ?: SerialBarcode.decode(BinaryBitmap(HybridBinarizer(source.rotateCounterClockwise())))
                        } finally { bitmap.recycle() }
                    }
                    if (value != null) latestAccept(value) else error = "Штрихкод не найден. Выберите более чёткую фотографию."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Не удалось считать штрихкод." }
                finally { reading = false }
            }
        }
    }
    LaunchedEffect(Unit) { if (!cameraAllowed) permission.launch(Manifest.permission.CAMERA) }
    DisposableEffect(lifecycle, scanner, cameraAllowed, reading, accepted) {
        val view = scanner
        fun sync() {
            cameraAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (cameraAllowed && !reading && !accepted && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view?.resume() else view?.pause()
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle.addObserver(observer); sync()
        onDispose { lifecycle.removeObserver(observer); view?.pause() }
    }
    AppModalSheet(onDismiss = onDismiss, maxWidth = 800.dp) {
        Column(Modifier.fillMaxWidth().heightIn(min = 420.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Сканировать S/N", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
            if (cameraAllowed && context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                AndroidView(factory = { DecoratedBarcodeView(it).apply {
                    setStatusText("Наведите камеру на штрихкод серийного номера")
                    barcodeView.decoderFactory = DefaultDecoderFactory(SerialBarcode.formats)
                    decodeContinuous(object : BarcodeCallback {
                        override fun barcodeResult(result: BarcodeResult) { latestAccept(result.text) }
                    })
                    scanner = this
                } }, modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 260.dp),
                    onRelease = { it.pause(); scanner = null })
            } else {
                Text("Камера недоступна. Разрешите доступ в настройках или выберите фото штрихкода.")
                AppOutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) { Text("Открыть настройки") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            AppButton(onClick = { photo.launch("image/*") }, enabled = !reading, modifier = Modifier.fillMaxWidth()) {
                Text(if (reading) "Считываю штрихкод…" else "Выбрать фото штрихкода")
            }
        }
    }
}
