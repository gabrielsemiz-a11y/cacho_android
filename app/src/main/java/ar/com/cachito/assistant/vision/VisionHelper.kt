package ar.com.cachito.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import ar.com.cachito.assistant.model.DetectedTarget
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

class VisionHelper(private val context: Context) {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var objectDetector: ObjectDetector? = null
    private var detectorInitAttempted = false
    private val refHistCache = mutableMapOf<String, FloatArray>()

    private val cocoSpanish = mapOf(
        "person" to "Persona",
        "bicycle" to "Bicicleta",
        "car" to "Auto",
        "motorcycle" to "Moto",
        "airplane" to "Avión",
        "bus" to "Colectivo",
        "train" to "Tren",
        "truck" to "Camión",
        "boat" to "Barco",
        "traffic light" to "Semáforo",
        "fire hydrant" to "Hidrante",
        "stop sign" to "Señal de Pare",
        "parking meter" to "Parquímetro",
        "bench" to "Banco",
        "bird" to "Pájaro",
        "cat" to "Gato",
        "dog" to "Perro",
        "horse" to "Caballo",
        "sheep" to "Oveja",
        "cow" to "Vaca",
        "elephant" to "Elefante",
        "bear" to "Oso",
        "zebra" to "Cebra",
        "giraffe" to "Jirafa",
        "backpack" to "Mochila",
        "umbrella" to "Paraguas",
        "handbag" to "Cartera",
        "tie" to "Corbata",
        "suitcase" to "Valija",
        "frisbee" to "Frisbee",
        "skis" to "Esquís",
        "snowboard" to "Snowboard",
        "sports ball" to "Pelota",
        "kite" to "Barrilete",
        "baseball bat" to "Bate",
        "baseball glove" to "Guante",
        "skateboard" to "Patineta",
        "surfboard" to "Tabla de surf",
        "tennis racket" to "Raqueta",
        "bottle" to "Botella",
        "wine glass" to "Copa",
        "cup" to "Taza / Vaso",
        "fork" to "Tenedor",
        "knife" to "Cuchillo",
        "spoon" to "Cuchara",
        "bowl" to "Bol",
        "banana" to "Banana",
        "apple" to "Manzana",
        "sandwich" to "Sándwich",
        "orange" to "Naranja",
        "broccoli" to "Brócoli",
        "carrot" to "Zanahoria",
        "hot dog" to "Pancho",
        "pizza" to "Pizza",
        "donut" to "Dona",
        "cake" to "Torta",
        "chair" to "Silla",
        "couch" to "Sillón",
        "potted plant" to "Planta",
        "bed" to "Cama",
        "dining table" to "Mesa",
        "toilet" to "Inodoro",
        "tv" to "Televisor",
        "laptop" to "Notebook",
        "mouse" to "Mouse",
        "remote" to "Control remoto",
        "keyboard" to "Teclado",
        "cell phone" to "Celular",
        "microwave" to "Microondas",
        "oven" to "Horno",
        "toaster" to "Tostadora",
        "sink" to "Pileta / Bacha",
        "refrigerator" to "Heladera",
        "book" to "Libro",
        "clock" to "Reloj",
        "vase" to "Florero",
        "scissors" to "Tijera",
        "teddy bear" to "Peluche",
        "hair drier" to "Secador de pelo",
        "toothbrush" to "Cepillo de dientes"
    )

    private fun ensureDetector(): ObjectDetector? {
        if (detectorInitAttempted) return objectDetector
        detectorInitAttempted = true
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("efficientdet_lite0.tflite")
                .build()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(0.35f)
                .setMaxResults(8)
                .build()
            objectDetector = ObjectDetector.createFromOptions(context, options)
            objectDetector
        } catch (e: Throwable) {
            Log.e("VisionHelper", "No se pudo iniciar EfficientDet-Lite0: ${e.message}")
            null
        }
    }

    fun activeVisionEngineLabel(): String {
        val det = ensureDetector()
        return if (det != null) {
            "MediaPipe EfficientDet-Lite0 (80 objetos ES) + Muestras"
        } else {
            "Modelo de visión no encontrado en assets"
        }
    }

    suspend fun extractTextFromImages(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        uris.forEachIndexed { idx, uri ->
            try {
                val img = InputImage.fromFilePath(context, uri)
                val res = textRecognizer.process(img).await()
                val pageText = res.text.trim()
                if (pageText.isNotBlank()) {
                    if (uris.size > 1) sb.appendLine("--- Página ${idx + 1} ---")
                    sb.appendLine(pageText)
                    sb.appendLine()
                }
            } catch (e: Exception) {
                Log.w("VisionHelper", "Error OCR página ${idx + 1}: ${e.message}")
            }
        }
        sb.toString().trim()
    }

    suspend fun detectAndMatchObjects(
        frame: Bitmap,
        references: Map<String, File>
    ): List<DetectedTarget> = withContext(Dispatchers.Default) {
        val det = ensureDetector() ?: return@withContext emptyList()
        val refHists = references.mapValues { (name, file) ->
            refHistCache[name] ?: computeFileHistogram(file)?.also { refHistCache[name] = it }
        }.filterValues { it != null }.mapValues { it.value!! }

        try {
            val mpImage = BitmapImageBuilder(frame).build()
            val result = det.detect(mpImage)
            val w = frame.width.toFloat().coerceAtLeast(1f)
            val h = frame.height.toFloat().coerceAtLeast(1f)

            val targets = mutableListOf<DetectedTarget>()
            for (detection in result.detections()) {
                val box = detection.boundingBox()
                val left = (box.left / w).coerceIn(0f, 1f)
                val top = (box.top / h).coerceIn(0f, 1f)
                val right = (box.right / w).coerceIn(left, 1f)
                val bottom = (box.bottom / h).coerceIn(top, 1f)

                val cat = detection.categories().firstOrNull()
                val rawName = cat?.categoryName()?.lowercase()?.trim().orEmpty()
                val esName = cocoSpanish[rawName] ?: rawName.replaceFirstChar { it.uppercase() }.ifBlank { "Objeto" }
                val score = cat?.score() ?: 0.5f

                var finalLabel = esName
                var finalScore = score
                var isMatch = false

                if (refHists.isNotEmpty()) {
                    val cropHist = computeCropHistogram(frame, left, top, right, bottom)
                    if (cropHist != null) {
                        var bestRefName: String? = null
                        var bestSim = 0f
                        for ((refName, refHist) in refHists) {
                            val sim = cosineSimilarity(cropHist, refHist)
                            if (sim > bestSim) {
                                bestSim = sim
                                bestRefName = refName
                            }
                        }
                        if (bestRefName != null && bestSim >= 0.90f) {
                            finalLabel = bestRefName
                            finalScore = bestSim
                            isMatch = true
                        }
                    }
                }

                targets.add(
                    DetectedTarget(
                        label = finalLabel,
                        confidence = finalScore,
                        leftRatio = left,
                        topRatio = top,
                        rightRatio = right,
                        bottomRatio = bottom,
                        isReferenceMatch = isMatch
                    )
                )
            }
            targets
        } catch (e: Exception) {
            Log.w("VisionHelper", "Error detectando objetos: ${e.message}")
            emptyList()
        }
    }

    private fun computeFileHistogram(file: File): FloatArray? {
        if (!file.exists()) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return try {
            computeCropHistogram(bmp, 0.15f, 0.15f, 0.85f, 0.85f)
        } finally {
            bmp.recycle()
        }
    }

    private fun computeCropHistogram(bmp: Bitmap, l: Float, t: Float, r: Float, b: Float): FloatArray? {
        val x0 = (bmp.width * l).toInt().coerceIn(0, bmp.width - 1)
        val y0 = (bmp.height * t).toInt().coerceIn(0, bmp.height - 1)
        val x1 = (bmp.width * r).toInt().coerceIn(x0 + 1, bmp.width)
        val y1 = (bmp.height * b).toInt().coerceIn(y0 + 1, bmp.height)
        val hist = FloatArray(64)
        val stepX = ((x1 - x0) / 24).coerceAtLeast(1)
        val stepY = ((y1 - y0) / 24).coerceAtLeast(1)
        var count = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val px = bmp.getPixel(x, y)
                val rBin = ((px shr 16) and 0xFF) / 64
                val gBin = ((px shr 8) and 0xFF) / 64
                val bBin = (px and 0xFF) / 64
                hist[rBin * 16 + gBin * 4 + bBin] += 1f
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return null
        for (i in hist.indices) hist[i] /= count.toFloat()
        return hist
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom > 0f) dot / denom else 0f
    }
}
