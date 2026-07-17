package au.edu.uts.vibepocket

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

internal class PhoneDictationController(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val gate = DictationSessionGate()
    private var recognizer: SpeechRecognizer? = null
    private var generation: Long? = null

    fun start(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition is not available on this phone.")
            return false
        }
        val nextGeneration = gate.begin() ?: return false
        return runCatching {
            val engine = createRecognizer()
            generation = nextGeneration
            recognizer = engine
            engine.setRecognitionListener(listenerFor(nextGeneration))
            engine.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                },
            )
            true
        }.getOrElse {
            gate.fail(nextGeneration)
            releaseRecognizer()
            onError("The phone could not start speech recognition.")
            false
        }
    }

    fun stop() {
        val activeGeneration = generation ?: return
        when (val outcome = gate.release(activeGeneration)) {
            DictationGateOutcome.Pending -> runCatching { recognizer?.stopListening() }
                .onFailure { fail(activeGeneration, "The phone could not finish speech recognition.") }
            DictationGateOutcome.Ignored -> Unit
            is DictationGateOutcome.Complete -> finish(outcome.text)
        }
    }

    fun cancel() {
        gate.cancel()
        generation = null
        runCatching { recognizer?.cancel() }
        releaseRecognizer()
    }

    fun destroy() = cancel()

    private fun createRecognizer(): SpeechRecognizer =
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun listenerFor(callbackGeneration: Long) = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = bestResult(results).trim().takeIf(String::isNotEmpty)
            when (val outcome = gate.complete(callbackGeneration, text)) {
                DictationGateOutcome.Pending, DictationGateOutcome.Ignored -> Unit
                is DictationGateOutcome.Complete -> finish(outcome.text)
            }
        }

        override fun onError(error: Int) {
            fail(callbackGeneration, recognitionError(error))
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun fail(callbackGeneration: Long, message: String) {
        if (!gate.fail(callbackGeneration)) return
        generation = null
        releaseRecognizer()
        onError(message)
    }

    private fun finish(text: String?) {
        generation = null
        releaseRecognizer()
        if (!text.isNullOrBlank()) onResult(text)
        else onError("No speech was recognized.")
    }

    private fun releaseRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun bestResult(results: Bundle?): String = results
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        .orEmpty()
}

internal sealed interface DictationGateOutcome {
    data object Pending : DictationGateOutcome
    data object Ignored : DictationGateOutcome
    data class Complete(val text: String?) : DictationGateOutcome
}

internal class DictationSessionGate {
    private data class Session(
        val generation: Long,
        var released: Boolean = false,
        var completed: Boolean = false,
        var text: String? = null,
    )

    private var nextGeneration = 0L
    private var session: Session? = null

    fun begin(): Long? {
        if (session != null) return null
        return (++nextGeneration).also { session = Session(it) }
    }

    fun release(generation: Long): DictationGateOutcome {
        val current = session?.takeIf { it.generation == generation }
            ?: return DictationGateOutcome.Ignored
        current.released = true
        return if (current.completed) consume(current) else DictationGateOutcome.Pending
    }

    fun complete(generation: Long, text: String?): DictationGateOutcome {
        val current = session?.takeIf { it.generation == generation }
            ?: return DictationGateOutcome.Ignored
        if (current.completed) return DictationGateOutcome.Ignored
        current.completed = true
        current.text = text
        return if (current.released) consume(current) else DictationGateOutcome.Pending
    }

    fun fail(generation: Long): Boolean {
        if (session?.generation != generation) return false
        session = null
        return true
    }

    fun cancel() {
        session = null
        nextGeneration += 1
    }

    private fun consume(current: Session): DictationGateOutcome.Complete {
        session = null
        return DictationGateOutcome.Complete(current.text)
    }
}

private fun recognitionError(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_AUDIO -> "The phone microphone could not be read."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required for Voice."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition lost its network connection."
    SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is already busy."
    SpeechRecognizer.ERROR_SERVER -> "The speech recognition service returned an error."
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was detected."
    else -> "Speech recognition stopped unexpectedly."
}
