package com.typefree.ime.network.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.typefree.ime.audio.WavAudioRecorder
import com.typefree.ime.data.SttConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fully-offline on-device ASR engine: FunASR **SenseVoice-Small** (int8 ONNX ≈ 230MB)
 * + silero VAD shipped inside the APK assets (`sherpa/`), driven by the k2-fsa
 * **sherpa-onnx** JNI runtime. No network, no API key, no billing.
 *
 * SenseVoice is a *non-streaming* model, so true token streaming is impossible;
 * instead a silero VAD splits the live 16kHz PCM feed into speech segments and
 * every closed segment is recognized immediately, emulating quasi-streaming
 * finalized sentences (~0.45s of trailing silence closes a sentence, 20s hard
 * cap per segment keeps decode latency bounded on long dictation).
 *
 * Notes:
 * - Hotwords are silently ignored: sherpa-onnx exposes no hotword interface for
 *   SenseVoice (unlike Aliyun Bailian / Volcengine streaming).
 * - Language is auto-detected by the model itself (zh/en/ja/ko/yue); the
 *   [SttConfig.language] override is intentionally not applied.
 * - The recognizer is a process-wide singleton loaded lazily on first use and
 *   stays resident (~300MB RAM) for the IME process lifetime.
 */
class LocalSenseVoiceAsrClient(private val appContext: Context) : StreamingAsrClient {

    companion object {
        private const val TAG = "LocalSenseVoiceAsr"
        private const val ASSET_DIR = "sherpa"
        private const val MODEL_ASSET = "$ASSET_DIR/model.int8.onnx"
        private const val TOKENS_ASSET = "$ASSET_DIR/tokens.txt"
        private const val VAD_ASSET = "$ASSET_DIR/silero_vad.onnx"
        private const val VAD_WINDOW_SAMPLES = 512
        private const val SAMPLE_RATE = 16000
        private const val MIN_SEGMENT_SAMPLES = 1600 // 0.1s @16kHz
        private const val NUM_THREADS = 2

        @Volatile
        private var sharedRecognizer: OfflineRecognizer? = null
        private val recognizerMutex = Any()

        /** Serializes all decode calls: one resident recognizer, possibly concurrent sessions. */
        private val decodeMutex = Mutex()

        /** True when this APK actually embeds the sherpa model assets. */
        fun isModelEmbedded(context: Context): Boolean {
            return try {
                val names = context.assets.list(ASSET_DIR)?.toSet() ?: emptySet()
                names.contains("model.int8.onnx") &&
                    names.contains("tokens.txt") &&
                    names.contains("silero_vad.onnx")
            } catch (e: Exception) {
                false
            }
        }

        private fun pcm16LeToFloat(pcm: ByteArray): FloatArray {
            val sampleCount = pcm.size / 2
            val out = FloatArray(sampleCount)
            val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) {
                out[i] = buffer.short / 32768.0f
            }
            return out
        }
    }

    /** Loads (or returns) the process-wide offline recognizer. Blocking; call off the main thread. */
    private fun acquireRecognizer(): OfflineRecognizer {
        sharedRecognizer?.let { return it }
        synchronized(recognizerMutex) {
            sharedRecognizer?.let { return it }
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = MODEL_ASSET,
                        language = "",
                        useInverseTextNormalization = true
                    ),
                    tokens = TOKENS_ASSET,
                    modelType = "sense-voice",
                    numThreads = NUM_THREADS,
                    provider = "cpu"
                )
            )
            val recognizer = OfflineRecognizer(assetManager = appContext.assets, config = config)
            sharedRecognizer = recognizer
            return recognizer
        }
    }

    override fun openSession(
        config: SttConfig,
        hotwords: List<String>
    ): StreamingAsrSession {
        if (!isModelEmbedded(appContext)) {
            return DashScopeStreamingAsrClient.FailedImmediateSession(
                "当前 APK 未内嵌本地 SenseVoice 模型（assets/sherpa），请安装带本地引擎的完整版"
            )
        }
        return LocalSenseVoiceSession()
    }

    /** Pre-loads the 230MB model into memory while the voice panel is opening. */
    override fun prewarmSession(
        config: SttConfig,
        hotwords: List<String>
    ) {
        if (!isModelEmbedded(appContext)) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                acquireRecognizer()
                Log.i(TAG, "本地 SenseVoice 引擎预热完成")
            } catch (e: Throwable) {
                Log.e(TAG, "本地 SenseVoice 引擎预热失败", e)
            }
        }
    }

    /**
     * Whole-file offline recognition (History audio retry / error fallback) bypassing
     * VAD entirely — the WAV already contains the complete utterance.
     */
    override suspend fun transcribeWavFile(
        wavFile: File,
        config: SttConfig,
        hotwords: List<String>,
        timeoutMs: Long
    ): String = withContext(Dispatchers.Default) {
        val rawPcm = WavAudioRecorder.extractPcmFromWav(wavFile)
        if (rawPcm.isEmpty()) {
            throw IOException("本地音频文件为空，无法识别")
        }
        val normalizedPcm = WavAudioRecorder.normalizePcm16Le(rawPcm)
        val recognizer = acquireRecognizer()
        val text = decodeMutex.withLock {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(pcm16LeToFloat(normalizedPcm), SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }
        if (text.isBlank()) {
            throw IOException("本地语音识别无结果，请重试")
        }
        text
    }

    private inner class LocalSenseVoiceSession : StreamingAsrSession {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private var vad: Vad? = try {
            Vad(
                assetManager = appContext.assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = VAD_ASSET,
                        threshold = 0.5f,
                        minSilenceDuration = 0.45f,
                        minSpeechDuration = 0.25f,
                        windowSize = VAD_WINDOW_SAMPLES,
                        maxSpeechDuration = 20f
                    ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1
            )
        )
        } catch (e: Throwable) {
            Log.e(TAG, "本地 VAD 初始化失败（so 库缺失或 assets 损坏）", e)
            null
        }

        private val _partialText = MutableStateFlow("")
        override val partialText: StateFlow<String> = _partialText.asStateFlow()

        private val stateMutex = Any()
        private val fullPcmBuffer = ByteArrayOutputStream() // 短语音 VAD 未成段时的整段兜底
        private val fullText = StringBuilder()
        private val segmentChannel = Channel<FloatArray>(Channel.UNLIMITED)
        private var receivedAnyAudio = false
        private var finished = false
        private var cancelled = false

        private val decodeJob: Job = scope.launch {
            for (samples in segmentChannel) {
                deliverSegment(samples)
            }
        }

        override fun sendPcmFrame(pcm16Le: ByteArray) {
            if (cancelled || finished) return
            synchronized(stateMutex) {
                fullPcmBuffer.write(pcm16Le, 0, pcm16Le.size)
                receivedAnyAudio = true
            }
            val vad = this.vad ?: return
            val samples = pcm16LeToFloat(pcm16Le)
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + VAD_WINDOW_SAMPLES, samples.size)
                vad.acceptWaveform(samples.copyOfRange(offset, end))
                offset = end
            }
            drainVadSegments()
        }

        override fun resetPrefix() {
            synchronized(stateMutex) {
                fullPcmBuffer.reset()
                fullText.setLength(0)
                receivedAnyAudio = false
            }
            _partialText.value = ""
            vad?.reset()
        }

        override suspend fun finishAndAwaitFinal(timeoutMs: Long): Result<String> {
            if (cancelled) {
                return Result.failure(IOException("本地语音识别已取消"))
            }
            finished = true
            return try {
                val vad = this.vad
                if (vad != null) {
                    vad.flush() // 强制产出尾段（含尾部静音前的残余语音）
                    drainVadSegments()
                }
                segmentChannel.close()
                withTimeoutOrNull(timeoutMs) { decodeJob.join() }

                var text = synchronized(stateMutex) { fullText.toString().trim() }
                if (text.isBlank() && receivedAnyAudio) {
                    // 短语音（<0.25s）或整句仍在 VAD 缓冲内：整段兜底识别
                    val pcm = synchronized(stateMutex) { fullPcmBuffer.toByteArray() }
                    if (pcm.size >= MIN_SEGMENT_SAMPLES * 2) {
                        val recognizer = acquireRecognizer()
                        text = decodeMutex.withLock {
                            val stream = recognizer.createStream()
                            try {
                                stream.acceptWaveform(pcm16LeToFloat(pcm), SAMPLE_RATE)
                                recognizer.decode(stream)
                                recognizer.getResult(stream).text.trim()
                            } finally {
                                stream.release()
                            }
                        }
                    }
                }
                if (text.isBlank()) {
                    Result.failure(IllegalArgumentException("未识别到清晰语音内容，请重试"))
                } else {
                    Result.success(text)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "本地语音识别失败", e)
                Result.failure(
                    if (e is IOException) e
                    else IOException("本地语音识别失败: ${e.message ?: e.javaClass.simpleName}", e)
                )
            } finally {
                release()
            }
        }

        override fun cancel() {
            cancelled = true
            finished = true
            segmentChannel.close()
            decodeJob.cancel()
            release()
        }

        private fun drainVadSegments() {
            val vad = this.vad ?: return
            while (!vad.empty()) {
                val segment = vad.front()
                vad.pop()
                if (segment.samples.size >= MIN_SEGMENT_SAMPLES) {
                    segmentChannel.trySend(segment.samples)
                }
            }
        }

        private suspend fun deliverSegment(samples: FloatArray) {
            try {
                val recognizer = acquireRecognizer()
                val text = decodeMutex.withLock {
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        recognizer.decode(stream)
                        recognizer.getResult(stream).text.trim()
                    } finally {
                        stream.release()
                    }
                }
                if (text.isNotBlank()) {
                    val combined = synchronized(stateMutex) {
                        fullText.append(text)
                        fullText.toString()
                    }
                    _partialText.value = combined
                }
            } catch (e: Throwable) {
                Log.e(TAG, "本地语音分段解码失败", e)
            }
        }

        private fun release() {
            try {
                vad?.release()
            } catch (e: Throwable) {
                Log.w(TAG, "VAD release 异常", e)
            }
            vad = null
        }
    }
}
