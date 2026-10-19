package ru.lumastack.lumawork.ui.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Uses an on-device recognizer only; no silent network fallback for work dictation. */
internal class AssistantDictation(private val context: Context, private val onText: (String) -> Unit,
    private val onListening: (Boolean) -> Unit, private val onError: (String) -> Unit,
) : AutoCloseable {
    private var recognizer: SpeechRecognizer? = null
    fun start() {
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onError("Локальное распознавание речи недоступно. Используйте текстовый ввод."); return
        }
        close()
        try {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { onListening(true) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { onListening(false) }
                    override fun onError(error: Int) { onListening(false); onError("Не удалось распознать речь. Проверьте наличие русской офлайн-модели.") }
                    override fun onResults(results: Bundle?) {
                        onListening(false)
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf(String::isNotBlank)?.let(onText)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU").putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
            }
            onListening(true)
        } catch (_: Exception) { close(); onError("Не удалось начать локальную диктовку.") }
    }
    fun finish() { recognizer?.stopListening(); onListening(false) }
    override fun close() { recognizer?.cancel(); recognizer?.destroy(); recognizer = null; onListening(false) }
}
