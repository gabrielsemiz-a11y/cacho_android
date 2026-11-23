package ar.com.cachito.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import kotlin.math.sqrt

class VisionHelper(private val context: Context) {

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val objectDetector by lazy {
        val opts = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
        ObjectDetection.getClient(opts)
    }

    fun activeVisionEngineLabel(): String = "ML Kit + Comparador Visual Local"

    suspend fun extractTextFromImages(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        for ((idx, uri) in uris.withIndex()) {
            try {
                val img = InputImage.fromFilePath(context, uri)
                val res = textRecognizer.process(img).await()
                if (res.text.isNotBlank()) {
                    if (uris.size > 1) sb.appendLine("--- Página ${idx + 1} ---")
                    sb.appendLine(res.text.trim())
                }
            } catch (_: Exception) {}
        }
        sb.toString().trim()
    }

    suspend fun detectAndMatchObjects(
        bitmap: Bitmap,
        references: Map<String, File>
    ): List<DetectedTarget> = withContext(Dispatchers.Default) {
        val refHists = references.mapNotNull { (label, file) ->
            try {
                val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@mapNotNull null
                val h = colorHistogram(bmp)
                bmp.recycle()
                label to h
            } catch (_: Exception) { null }
        }

        val detected = try {
            val img = InputImage.fromBitmap(bitmap, 0)
            objectDetector.process(img).await()
        } catch (_: Exception) { emptyList() }

        val w = bitmap.width.coerceAtLeast(1).toFloat()
        val h = bitmap.height.coerceAtLeast(1).toFloat()
        val results = mutableListOf<DetectedTarget>()

        for (obj in detected) {
            val box = obj.boundingBox
            val left = box.left.coerceIn(0, bitmap.width - 1)
            val top = box.top.coerceIn(0, bitmap.height - 1)
            val bw = (box.right.coerceIn(left + 1, bitmap.width) - left).coerceAtLeast(1)
            val bh = (box.bottom.coerceIn(top + 1, bitmap.height) - top).coerceAtLeast(1)

            var bestRefLabel: String? = null
            var bestSim = 0f
            if (refHists.isNotEmpty() && bw > 16 && bh > 16) {
                val crop = Bitmap.createBitmap(bitmap, left, top, bw, bh)
                val cropHist = colorHistogram(crop)
                crop.recycle()
                for ((refLabel, refHist) in refHists) {
                    val sim = cosineSim(cropHist, refHist)
                    if (sim > bestSim) {
                        bestSim = sim
                        bestRefLabel = refLabel
                    }
                }
            }

            val isMatch = bestRefLabel != null && bestSim >= 0.84f
            val baseLabel = obj.labels.firstOrNull()?.text ?: "Objeto"
            val baseConf = obj.labels.firstOrNull()?.confidence ?: 0.6f

            results.add(
                DetectedTarget(
                    label = if (isMatch) bestRefLabel!! else baseLabel,
                    confidence = if (isMatch) bestSim else baseConf,
                    leftRatio = (left / w).coerceIn(0f, 1f),
                    topRatio = (top / h).coerceIn(0f, 1f),
                    rightRatio = ((left + bw) / w).coerceIn(0f, 1f),
                    bottomRatio = ((top + bh) / h).coerceIn(0f, 1f),
                    isReferenceMatch = isMatch
                )
            )
        }
        results
    }

    private fun colorHistogram(src: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(src, 32, 32, true)
        val hist = FloatArray(64)
        val pixels = IntArray(32 * 32)
        small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 64
            val g = ((p shr 8) and 0xFF) / 64
            val b = (p and 0xFF) / 64
            hist[(r * 16 + g * 4 + b).coerceIn(0, 63)] += 1f
        }
        if (small !== src) small.recycle()
        return hist
    }

    private fun cosineSim(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom > 0f) dot / denom else 0f
    }
}