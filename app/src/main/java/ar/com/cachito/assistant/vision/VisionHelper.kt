package ar.com.cachito.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import ar.com.cachito.assistant.model.DetectedTarget
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.min
import kotlin.math.sqrt

class VisionHelper(private val context: Context) {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    private var yoloInterpreter: Interpreter? = null
    private var loadedYoloName: String = ""

    init {
        tryLoadYoloModel()
    }

    private fun tryLoadYoloModel() {
        val candidates = listOf("models/yolo26n.tflite", "models/yoloe26.tflite", "yolo26n.tflite")
        for (assetPath in candidates) {
            try {
                val afd = context.assets.openFd(assetPath)
                val inputStream = FileInputStream(afd.fileDescriptor)
                val channel = inputStream.channel
                val mapped = channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                yoloInterpreter = Interpreter(mapped, Interpreter.Options().apply { setNumThreads(4) })
                loadedYoloName = assetPath.substringAfterLast('/')
                break
            } catch (_: Exception) {
                // Si el usuario aún no copió el .tflite en assets/models/, usa ML Kit + matcher visual
            }
        }
    }

    fun activeVisionEngineLabel(): String =
        if (yoloInterpreter != null) "YOLO26 ($loadedYoloName)" else "YOLO26-Compat + ML Kit Vision"

    suspend fun extractTextFromImages(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        uris.forEachIndexed { idx, uri ->
            try {
                val img = InputImage.fromFilePath(context, uri)
                val result = textRecognizer.process(img).await()
                val pageTxt = result.text.trim()
                if (pageTxt.isNotBlank()) {
                    sb.appendLine("--- Página ${idx + 1} ---")
                    sb.appendLine(pageTxt)
                    sb.appendLine()
                }
            } catch (_: Exception) {}
        }
        sb.toString().trim()
    }

    /**
     * Analiza un frame o foto para:
     * - Contar objetos detectados (YOLO26 TFLite si está presente en assets, o ML Kit Object Detection)
     * - Comparar cada recorte contra las fotos de muestra guardadas (ej. llaves, control, billetera)
     */
    suspend fun detectAndMatchObjects(
        bitmap: Bitmap,
        referencePhotos: Map<String, File>
    ): List<DetectedTarget> = withContext(Dispatchers.Default) {
        val refHistograms = referencePhotos.mapNotNull { (name, file) ->
            if (!file.exists()) return@mapNotNull null
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@mapNotNull null
            name to computeColorSignature(bmp)
        }.toMap()

        val w = bitmap.width.coerceAtLeast(1).toFloat()
        val h = bitmap.height.coerceAtLeast(1).toFloat()

        // 1) Si existe modelo YOLO26 / YOLOE-26 .tflite en assets/models/
        yoloInterpreter?.let { interp ->
            val yoloHits = runYolo26Inference(interp, bitmap, refHistograms)
            if (yoloHits.isNotEmpty()) return@withContext yoloHits
        }

        // 2) Pipeline nativo ML Kit Object Detection + Matching de firma visual con fotos de muestra
        return@withContext try {
            val input = InputImage.fromBitmap(bitmap, 0)
            val objects = objectDetector.process(input).await()
            objects.map { obj ->
                val box = obj.boundingBox
                val l = (box.left / w).coerceIn(0f, 1f)
                val t = (box.top / h).coerceIn(0f, 1f)
                val r = (box.right / w).coerceIn(0f, 1f)
                val b = (box.bottom / h).coerceIn(0f, 1f)

                var baseLabel = obj.labels.firstOrNull()?.text ?: "Objeto"
                var conf = obj.labels.firstOrNull()?.confidence ?: 0.72f
                var matchedRef = false

                if (refHistograms.isNotEmpty() && box.width() > 16 && box.height() > 16) {
                    val crop = Bitmap.createBitmap(
                        bitmap,
                        box.left.coerceIn(0, bitmap.width - 1),
                        box.top.coerceIn(0, bitmap.height - 1),
                        box.width().coerceAtMost(bitmap.width - box.left.coerceAtLeast(0)),
                        box.height().coerceAtMost(bitmap.height - box.top.coerceAtLeast(0))
                    )
                    val cropSig = computeColorSignature(crop)
                    val bestMatch = refHistograms.maxByOrNull { cosineSim(cropSig, it.value) }
                    if (bestMatch != null) {
                        val sim = cosineSim(cropSig, bestMatch.value)
                        if (sim >= 0.84f) {
                            baseLabel = "★ ${bestMatch.key}"
                            conf = sim
                            matchedRef = true
                        }
                    }
                }

                DetectedTarget(
                    label = baseLabel,
                    confidence = conf,
                    leftRatio = l,
                    topRatio = t,
                    rightRatio = r,
                    bottomRatio = b,
                    isReferenceMatch = matchedRef
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun runYolo26Inference(
        interpreter: Interpreter,
        bitmap: Bitmap,
        refHistograms: Map<String, FloatArray>
    ): List<DetectedTarget> {
        return try {
            val inputSize = 640
            val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            val pixels = IntArray(inputSize * inputSize)
            resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
            for (px in pixels) {
                inputBuffer.putFloat(Color.red(px) / 255f)
                inputBuffer.putFloat(Color.green(px) / 255f)
                inputBuffer.putFloat(Color.blue(px) / 255f)
            }
            // Formato estándar YOLO26 end-to-end [1, 300, 6] -> (x1, y1, x2, y2, score, class_id)
            val output = Array(1) { Array(300) { FloatArray(6) } }
            interpreter.run(inputBuffer, output)

            val results = mutableListOf<DetectedTarget>()
            for (det in output[0]) {
                val score = det[4]
                if (score < 0.42f) continue
                val clsId = det[5].toInt()
                val l = (det[0] / inputSize).coerceIn(0f, 1f)
                val t = (det[1] / inputSize).coerceIn(0f, 1f)
                val r = (det[2] / inputSize).coerceIn(0f, 1f)
                val b = (det[3] / inputSize).coerceIn(0f, 1f)
                if (r <= l || b <= t) continue

                var label = cocoLabelEs(clsId)
                var isRef = false
                if (refHistograms.isNotEmpty()) {
                    val x1 = (l * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                    val y1 = (t * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                    val cw = ((r - l) * bitmap.width).toInt().coerceIn(1, bitmap.width - x1)
                    val ch = ((b - t) * bitmap.height).toInt().coerceIn(1, bitmap.height - y1)
                    val cropSig = computeColorSignature(Bitmap.createBitmap(bitmap, x1, y1, cw, ch))
                    val best = refHistograms.maxByOrNull { cosineSim(cropSig, it.value) }
                    if (best != null && cosineSim(cropSig, best.value) >= 0.85f) {
                        label = "★ ${best.key}"
                        isRef = true
                    }
                }
                results.add(DetectedTarget(label, score, l, t, r, b, isRef))
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun computeColorSignature(bmp: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(bmp, 32, 32, true)
        val hist = FloatArray(48)
        val hsv = FloatArray(3)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                Color.colorToHSV(small.getPixel(x, y), hsv)
                val hBin = ((hsv[0] / 360f) * 16).toInt().coerceIn(0, 15)
                val sBin = (hsv[1] * 16).toInt().coerceIn(0, 15)
                val vBin = (hsv[2] * 16).toInt().coerceIn(0, 15)
                hist[hBin] += 1f
                hist[16 + sBin] += 1f
                hist[32 + vBin] += 1f
            }
        }
        val norm = sqrt(hist.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1f)
        for (i in hist.indices) hist[i] /= norm
        return hist
    }

    private fun cosineSim(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in 0 until min(a.size, b.size)) dot += a[i] * b[i]
        return dot
    }

    private fun cocoLabelEs(id: Int): String = when (id) {
        0 -> "Persona"
        24 -> "Mochila"
        25 -> "Paraguas"
        26 -> "Bolso"
        28 -> "Valija"
        39 -> "Botella"
        41 -> "Taza"
        56 -> "Silla"
        62 -> "TV / Monitor"
        63 -> "Notebook"
        64 -> "Mouse"
        65 -> "Control remoto"
        66 -> "Teclado"
        67 -> "Celular"
        73 -> "Libro"
        74 -> "Reloj"
        76 -> "Tijera"
        else -> "Objeto #$id"
    }
}
