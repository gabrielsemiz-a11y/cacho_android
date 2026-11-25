package ar.com.cachito.assistant.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.util.Log
import ar.com.cachito.assistant.data.LocalStorage
import ar.com.cachito.assistant.model.*
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.util.concurrent.TimeUnit

data class NanoAvailability(
    val statusCode: Int,
    val label: String,
    val isReady: Boolean,
    val isSupportedByHardware: Boolean = false
)

class CachitoBrain(
    private val context: Context,
    private val storage: LocalStorage
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val webSearchHttp = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val downloadHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    private var nanoClient: GenerativeModel? = null
    private var localLlm: LlmInference? = null
    private var loadedLocalModelPath: String? = null
    private var loadedLocalBackend: String = ""

    private fun getOrCreateNanoClient(): GenerativeModel {
        return nanoClient ?: Generation.getClient().also { nanoClient = it }
    }

    suspend fun checkGeminiNanoStatus(): NanoAvailability = withContext(Dispatchers.IO) {
        try {
            val client = getOrCreateNanoClient()
            val status = client.checkStatus()
            when (status) {
                FeatureStatus.AVAILABLE -> NanoAvailability(status, "Gemini Nano listo (Offline)", true, true)
                FeatureStatus.DOWNLOADABLE -> NanoAvailability(status, "Gemini Nano descargable en AICore", false, true)
                FeatureStatus.DOWNLOADING -> NanoAvailability(status, "Descargando Gemini Nano...", false, true)
                else -> NanoAvailability(status, "AICore no habilitado", false, false)
            }
        } catch (e: Throwable) {
            Log.w("CachitoBrain", "AICore checkStatus error: ${e.message}")
            NanoAvailability(-1, "AICore no disponible", false, false)
        }
    }

    suspend fun triggerGeminiNanoDownload(onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = getOrCreateNanoClient()
            var ok = false
            client.download().collect { st ->
                when (st) {
                    is DownloadStatus.DownloadStarted -> onProgress("Iniciando descarga de Gemini Nano...")
                    is DownloadStatus.DownloadProgress -> onProgress("Descargando modelo AICore: ${st.totalBytesDownloaded / (1024 * 1024)} MB")
                    DownloadStatus.DownloadCompleted -> {
                        ok = true
                        onProgress("Gemini Nano descargado y listo.")
                    }
                    is DownloadStatus.DownloadFailed -> {
                        onProgress("Error descargando AICore: ${st.e.message}")
                    }
                }
            }
            ok
        } catch (e: Throwable) {
            onProgress("Este equipo no soporta descarga AICore (${e.javaClass.simpleName}).")
            false
        }
    }

    suspend fun downloadLocalModel(
        url: String,
        destFile: File,
        onProgress: (Float, String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http")) {
            return@withContext Result.failure(IllegalArgumentException("URL de modelo inválida."))
        }
        val tmpFile = File(destFile.parentFile, "${destFile.name}.part")
        try {
            onProgress(0f, "Conectando al servidor del modelo...")
            val req = Request.Builder().url(cleanUrl).get().build()
            downloadHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("Error HTTP ${resp.code} al descargar modelo"))
                }
                val body = resp.body ?: return@withContext Result.failure(IllegalStateException("Respuesta vacía"))
                val totalBytes = body.contentLength()
                var downloaded = 0L
                val buf = ByteArray(64 * 1024)
                var lastUiUpdate = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            output.write(buf, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate > 350L) {
                                lastUiUpdate = now
                                val mbDone = downloaded / (1024 * 1024)
                                if (totalBytes > 0) {
                                    val mbTotal = totalBytes / (1024 * 1024)
                                    val pct = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                    onProgress(pct, "Descargando: $mbDone / $mbTotal MB (${(pct * 100).toInt()}%)")
                                } else {
                                    onProgress(0.5f, "Descargando: $mbDone MB...")
                                }
                            }
                        }
                    }
                }
            }
            if (destFile.exists()) destFile.delete()
            tmpFile.renameTo(destFile)
            releaseLocalLlm()
            onProgress(1f, "✓ Modelo listo (${destFile.length() / (1024 * 1024)} MB)")
            Result.success(destFile)
        } catch (e: Exception) {
            if (tmpFile.exists()) tmpFile.delete()
            Result.failure(e)
        }
    }

    fun releaseLocalLlm() {
        try {
            localLlm?.close()
        } catch (_: Exception) {}
        localLlm = null
        loadedLocalModelPath = null
        loadedLocalBackend = ""
    }

    private fun runLocalOfflineLlm(modelFile: File, sysPrompt: String, userBlock: String): Result<Pair<String, String>> {
        if (!modelFile.exists() || modelFile.length() < 10_000_000L) {
            return Result.failure(IllegalStateException("El modelo local todavía no está descargado. Andá a ⚙️ Ajustes → IA Local Offline para bajarlo."))
        }
        return try {
            if (localLlm == null || loadedLocalModelPath != modelFile.absolutePath) {
                releaseLocalLlm()
                var created: LlmInference? = null
                var usedBackend = "CPU"
                try {
                    val gpuOpts = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelFile.absolutePath)
                        .setMaxTokens(1024)
                        .setPreferredBackend(LlmInference.Backend.GPU)
                        .build()
                    created = LlmInference.createFromOptions(context, gpuOpts)
                    usedBackend = "GPU"
                } catch (gpuErr: Throwable) {
                    Log.w("CachitoBrain", "GPU LiteRT no disponible (${gpuErr.message}), usando CPU LiteRT...")
                    val cpuOpts = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelFile.absolutePath)
                        .setMaxTokens(1024)
                        .setPreferredBackend(LlmInference.Backend.CPU)
                        .build()
                    created = LlmInference.createFromOptions(context, cpuOpts)
                    usedBackend = "CPU"
                }
                localLlm = created
                loadedLocalModelPath = modelFile.absolutePath
                loadedLocalBackend = usedBackend
            }
            val compactUser = userBlock.take(1800)
            val prompt = "<|im_start|>system\n$sysPrompt\n<|im_end|>\n<|im_start|>user\n$compactUser\n<|im_end|>\n<|im_start|>assistant\n"
            val raw = localLlm!!.generateResponse(prompt)?.trim() ?: ""
            val cleaned = cleanThinkTags(raw)
            if (cleaned.isBlank() || cleaned.equals("null", ignoreCase = true)) {
                Result.failure(IllegalStateException("El modelo local devolvió respuesta vacía."))
            } else {
                Result.success(cleaned to loadedLocalBackend)
            }
        } catch (e: Throwable) {
            Log.e("CachitoBrain", "Error MediaPipe LLM: ${e.message}")
            Result.failure(IllegalStateException("Error ejecutando modelo local: ${e.message}"))
        }
    }

    private fun needsLiveWebOrNews(userQuery: String): Boolean {
        val low = userQuery.lowercase()
        val triggers = listOf(
            "noticia", "noticias", "novedades", "paso hoy", "pasó hoy", "actualidad", "diario", "titulares", "ultimas", "últimas",
            "busca", "buscá", "buscar", "en la web", "en al web", "internet", "google",
            "pasaje", "pasajes", "vuelo", "vuelos", "precio", "precios", "cuanto sale", "cuánto sale",
            "cuanto cuesta", "cuánto cuesta", "donde comprar", "dónde comprar", "barato", "economico", "económico",
            "horario", "horarios", "telefono", "teléfono", "direccion", "dirección", "resultado", "partido",
            "hoy", "actual", "2026", "quien gano", "quién ganó", "cotizacion", "cotización", "nasdaq", "bolsa", "acciones"
        )
        return triggers.any { low.contains(it) }
    }

    private fun fetchLiveNewsContext(userQuery: String, maxItems: Int): String {
        return try {
            val cleanTopic = userQuery
                .replace(Regex("(?i)(busca|buscá|dame|decime|cuales son las|cuáles son las|ultimas|últimas|noticias|novedades|de|sobre|en)"), " ")
                .trim()
            val queryEncoded = Uri.encode(if (cleanTopic.length >= 3) cleanTopic else "Mendoza Argentina")
            val rssUrl = "https://news.google.com/rss/search?q=$queryEncoded&hl=es-419&gl=AR&ceid=AR:es-419"

            val req = Request.Builder().url(rssUrl).get().build()
            webSearchHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val xml = resp.body?.string() ?: return ""
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(StringReader(xml))

                val titles = mutableListOf<String>()
                var inItem = false
                var tag = ""
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT && titles.size < maxItems) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            tag = parser.name ?: ""
                            if (tag == "item") inItem = true
                        }
                        XmlPullParser.TEXT -> {
                            if (inItem && tag == "title") {
                                val t = parser.text?.trim().orEmpty()
                                if (t.isNotBlank()) titles.add(t)
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name == "item") inItem = false
                            tag = ""
                        }
                    }
                    event = parser.next()
                }
                if (titles.isEmpty()) "" else {
                    "Titulares reales en vivo (Google News AR):\n" +
                        titles.joinToString("\n") { "- $it" }
                }
            }
        } catch (e: Exception) {
            Log.w("CachitoBrain", "Error leyendo Google News RSS: ${e.message}")
            ""
        }
    }

    private fun fetchLiveDuckDuckGoContext(userQuery: String, maxItems: Int, maxSnippetLen: Int): String {
        return try {
            val cleanQuery = userQuery
                .replace(Regex("(?i)(busca en la web|buscá en la web|busca en al web|busca en internet|buscá en internet|buscar en la web|busca|buscá)"), " ")
                .trim()
                .ifBlank { userQuery.trim() }
            val url = "https://html.duckduckgo.com/html/?q=${Uri.encode(cleanQuery)}&kl=ar-es"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
                .get()
                .build()

            webSearchHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val html = resp.body?.string() ?: return ""

                val titleRegex = Regex("""<a[^>]*class="result__a"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                val snippetRegex = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

                val titles = titleRegex.findAll(html).map { stripHtml(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
                val snippets = snippetRegex.findAll(html).map { stripHtml(it.groupValues[1]) }.toList()

                val count = minOf(titles.size, maxItems)
                if (count == 0) return ""

                val lines = (0 until count).map { i ->
                    val t = titles[i].take(110)
                    val s = snippets.getOrNull(i)?.take(maxSnippetLen).orEmpty()
                    if (s.isNotBlank()) "- $t: $s" else "- $t"
                }
                "Resultados web en vivo (DuckDuckGo):\n" + lines.joinToString("\n")
            }
        } catch (e: Exception) {
            Log.w("CachitoBrain", "Error DuckDuckGo Web Search: ${e.message}")
            ""
        }
    }

    private fun stripHtml(raw: String): String {
        val noTags = raw.replace(Regex("<[^>]*>"), " ")
        val decoded = Html.fromHtml(noTags, Html.FROM_HTML_MODE_LEGACY).toString()
        return decoded.replace(Regex("\\s+"), " ").trim()
    }

    private fun fetchLiveWebOrNewsIfNeeded(userQuery: String, isCompactLocal: Boolean): String {
        if (!needsLiveWebOrNews(userQuery)) return ""
        val low = userQuery.lowercase()
        val newsTriggers = listOf("noticia", "noticias", "novedades", "paso hoy", "pasó hoy", "actualidad", "diario", "titulares", "ultimas", "últimas", "nasdaq")
        if (newsTriggers.any { low.contains(it) }) {
            return fetchLiveNewsContext(userQuery, maxItems = if (isCompactLocal) 3 else 6)
        }
        return fetchLiveDuckDuckGoContext(
            userQuery = userQuery,
            maxItems = if (isCompactLocal) 3 else 5,
            maxSnippetLen = if (isCompactLocal) 120 else 220
        )
    }

    /**
     * Lista SOLO modelos gratuitos (:free) y ultra-baratos (<= USD 0.50 por millón de tokens de entrada).
     * Excluye automáticamente modelos caros (Opus, Sonnet, o3, etc.).
     */
    suspend fun fetchAvailableOpenRouterModels(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder()
                .url("https://openrouter.ai/api/v1/models")
                .get()
            if (apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
            }
            http.newCall(reqBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("Error OpenRouter HTTP ${resp.code}"))
                }
                val arr = JSONObject(raw).optJSONArray("data") ?: JSONArray()
                val freeList = mutableListOf<String>()
                val cheapPaid = mutableListOf<Pair<Double, String>>()
                val bannedWords = listOf("embed", "guard", "whisper", "tts", "audio", "image", "moderation", "opus", "sonnet")

                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val id = m.optString("id", "").trim()
                    if (id.isBlank()) continue
                    val lowId = id.lowercase()
                    if (bannedWords.any { lowId.contains(it) }) continue

                    val pricing = m.optJSONObject("pricing")
                    val promptPrice = pricing?.optString("prompt", "")?.toDoubleOrNull()
                    val promptPerMillion = if (promptPrice != null && promptPrice >= 0.0) promptPrice * 1_000_000.0 else Double.MAX_VALUE

                    if (id.endsWith(":free") || promptPerMillion == 0.0) {
                        freeList.add(id)
                    } else if (promptPerMillion <= 0.50) {
                        val isPreferredFamily = listOf("deepseek", "inkling", "qwen", "gemini", "llama", "gpt-4o-mini", "mistral", "nemotron", "gemma")
                            .any { lowId.contains(it) }
                        if (isPreferredFamily) {
                            cheapPaid.add(promptPerMillion to id)
                        }
                    }
                }

                val sortedFree = freeList.sortedWith(
                    compareBy<String> {
                        when {
                            it.contains("deepseek", true) -> 0
                            it.contains("qwen", true) -> 1
                            it.contains("gemini", true) -> 2
                            it.contains("llama", true) -> 3
                            it.contains("nemotron", true) -> 4
                            else -> 5
                        }
                    }.thenBy { it }
                ).take(14)

                val sortedCheap = cheapPaid.sortedBy { it.first }.map { it.second }.take(16)
                val combined = (listOf("openrouter/free") + sortedFree + sortedCheap).distinct()
                Result.success(combined)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Filtra Google AI Studio descartando variantes experimentales/imagen/audio (banana, nano, omni, exp, live, etc.).
     */
    suspend fun fetchAvailableGoogleModels(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Pegá primero tu API Key de Google AI Studio."))
        }
        try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models")
                .header("x-goog-api-key", cleanKey)
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val errMsg = try {
                        JSONObject(raw).optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                    } catch (_: Exception) {
                        "HTTP ${resp.code}"
                    }
                    return@withContext Result.failure(IllegalStateException(errMsg))
                }
                val j = JSONObject(raw)
                val arr = j.optJSONArray("models") ?: JSONArray()
                val excluded = listOf(
                    "embedding", "imagen", "veo", "tts", "robotics", "computer-use",
                    "banana", "nano", "omni", "audio", "live", "vision", "aqa", "learnlm", "exp", "bison", "gecko"
                )
                val models = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val name = m.optString("name", "").removePrefix("models/")
                    val low = name.lowercase()
                    val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                    if (low.startsWith("gemini-") &&
                        methods.contains("generateContent") &&
                        excluded.none { low.contains(it) } &&
                        (low.contains("flash") || low.contains("pro"))
                    ) {
                        models.add(name)
                    }
                }
                if (models.isEmpty()) {
                    Result.failure(IllegalStateException("La clave respondió OK pero no devolvió modelos de chat Gemini estables."))
                } else {
                    val sorted = models.sortedWith(
                        compareBy<String> {
                            when {
                                it.contains("preview", true) -> 2
                                it.contains("flash-lite", true) -> 0
                                it.contains("flash", true) -> 1
                                else -> 3
                            }
                        }.thenByDescending { it }
                    )
                    Result.success(sorted)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchAvailableGroqModels(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Pegá primero tu API Key gratuita de console.groq.com."))
        }
        try {
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .header("Authorization", "Bearer $cleanKey")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("Error Groq HTTP ${resp.code}"))
                }
                val arr = JSONObject(raw).optJSONArray("data") ?: JSONArray()
                val excluded = listOf("whisper", "guard", "tts", "playai", "distil")
                val models = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val id = arr.getJSONObject(i).optString("id", "")
                    if (id.isNotBlank() && excluded.none { id.contains(it, ignoreCase = true) }) {
                        models.add(id)
                    }
                }
                Result.success(models.sorted())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun buildSystemPrompt(settings: AppSettings, ticker: TickerSnapshot): String {
        val persona = when (settings.personalityPreset.lowercase()) {
            "tecnico" -> "Sos Cachito en modo Técnico: directo, preciso y estructurado en voseo argentino. Sin relleno: datos concretos y pasos claros."
            "copado" -> "Sos Cachito en modo Copado: cercano, ágil y resolutivo en voseo argentino natural, sin repetir frases hechas."
            "profe" -> "Sos Cachito en modo Profe: claro y didáctico en voseo argentino; explicás paso a paso con ejemplos simples."
            else -> "Sos Cachito, un técnico argentino veterano de taller, práctico, ingenioso y con mucha calle. Hablás en voseo rioplatense natural (vos, fijate, hacé, probá), sin usar nunca 'oye' ni 'tú'. Priorizá SIEMPRE dar la respuesta concreta, útil y directa en las primeras líneas. Humor sutil y variado sin repetir muletillas ni saludos fijos. Nunca describas tu personaje. Sabés a fondo de PC, netbooks DGE, autos, máquinas, electrodomésticos, plomería y finanzas."
        }

        val liveWeather = "Clima actual en ${ticker.weather.city}: ${ticker.weather.tempCurrent}°C (máx ${ticker.weather.tempMax}°C), ${ticker.weather.conditionText}."
        val liveDollar = if (ticker.dollars.isNotEmpty()) {
            "Cotización dólar hoy: " + ticker.dollars.joinToString(" | ") { "${it.name} $${it.sell.toInt()}" } + "."
        } else ""
        val financeCtx = storage.buildFinancePromptContext()

        return buildString {
            appendLine(persona)
            appendLine("Escribí EXCLUSIVAMENTE en español usando alfabeto latino estándar (nunca mezcles letras cirílicas, chinas ni de otros alfabetos).")
            appendLine("REGLA ESTRICTA DE HONESTIDAD: NUNCA inventes sitios web, dominios, enlaces, aplicaciones ni comandos que no conozcas con certeza absoluta. Si no conocés un programa, app o página web que te nombran, decí directamente 'No tengo información verificada sobre ese programa o sitio' en lugar de adivinar. Tampoco recomiendes desactivar el antivirus.")
            appendLine("Si te pasan 'Titulares reales en vivo' o 'Resultados web en vivo' en el contexto, o usás búsqueda web nativa, usalos y resumilos directamente con claridad sin decir que no tenés acceso a internet.")
            appendLine("Encabezá cada respuesta con una etiqueta de emoción válida: [EMOTION:neutro], [EMOTION:pensando], [EMOTION:alegre], [EMOTION:concentrado], [EMOTION:duda] o [EMOTION:alerta].")
            appendLine("Datos en vivo: $liveWeather $liveDollar")
            if (financeCtx.isNotBlank()) appendLine(financeCtx)
        }.trim()
    }

    fun generateReply(
        userMessage: String,
        attachedDocText: String?,
        ticker: TickerSnapshot
    ): Flow<Pair<String, String>> = flow {
        val settings = storage.loadSettings()
        val isLocalMode = settings.preferredEngine == AiEngineMode.LOCAL_OFFLINE
        val wantsWeb = needsLiveWebOrNews(userMessage)
        val ragHits = storage.searchRag(userMessage, topK = if (isLocalMode) 2 else 3)
        val (_, recentTurns) = storage.getSessionTurns()
        val liveWebBlock = fetchLiveWebOrNewsIfNeeded(userMessage, isCompactLocal = isLocalMode)
        val sysPrompt = buildSystemPrompt(settings, ticker)

        val fullUserBlock = buildString {
            if (liveWebBlock.isNotBlank()) {
                appendLine(liveWebBlock)
                appendLine()
            }
            if (ragHits.isNotEmpty()) {
                appendLine("Memoria relevante:")
                ragHits.forEach { appendLine(it) }
                appendLine()
            }
            if (recentTurns.isNotEmpty()) {
                appendLine("Contexto reciente:")
                recentTurns.takeLast(if (isLocalMode) 2 else 3).forEach {
                    appendLine("Usuario: ${it.user.take(140)}")
                    appendLine("Cachito: ${it.assistant.take(180)}")
                }
                appendLine()
            }
            if (!attachedDocText.isNullOrBlank()) {
                appendLine("Texto adjunto/escaneado:")
                appendLine(attachedDocText.take(if (isLocalMode) 1200 else 3500))
                appendLine()
            }
            append("Consulta actual: $userMessage")
        }

        // 1) Intentar primero EXACTAMENTE el motor y modelo elegido por el usuario
        when (settings.preferredEngine) {
            AiEngineMode.LOCAL_OFFLINE -> {
                val modelFile = storage.getLocalModelFile(settings.localModelName)
                val res = runLocalOfflineLlm(modelFile, sysPrompt, fullUserBlock)
                if (res.isSuccess) {
                    val (txt, backend) = res.getOrThrow()
                    val webTag = if (liveWebBlock.isNotBlank()) "+Web" else ""
                    emit(txt to "Offline $backend$webTag (${settings.localModelName.removeSuffix(".task")})")
                } else {
                    val fb = tryFallbacksExcluding(AiEngineMode.LOCAL_OFFLINE, settings, sysPrompt, fullUserBlock, wantsWeb)
                    if (fb != null) emit(fb)
                    else emit("[EMOTION:alerta] ${res.exceptionOrNull()?.message}" to "Error Offline")
                }
            }

            AiEngineMode.OPENROUTER -> {
                val res = if (settings.openRouterApiKey.isNotBlank()) {
                    runOpenRouterSmart(settings.openRouterApiKey, settings.openRouterModel, sysPrompt, fullUserBlock, wantsWeb)
                } else {
                    Result.failure(IllegalStateException("Falta API Key de OpenRouter."))
                }
                if (res.isSuccess) {
                    val (txt, usedModel, usedNativeWeb) = res.getOrThrow()
                    val webTag = if (usedNativeWeb || liveWebBlock.isNotBlank()) " +Web" else ""
                    emit(txt to "OpenRouter$webTag ($usedModel)")
                } else {
                    val fb = tryFallbacksExcluding(AiEngineMode.OPENROUTER, settings, sysPrompt, fullUserBlock, wantsWeb)
                    if (fb != null) emit(fb)
                    else emit("[EMOTION:alerta] Error OpenRouter (${settings.openRouterModel}): ${res.exceptionOrNull()?.message}\n\nRevisá tu clave en ⚙️ Ajustes o cargá otro motor de respaldo." to "Error API")
                }
            }

            AiEngineMode.GOOGLE_AI_STUDIO -> {
                val res = if (settings.googleApiKey.isNotBlank()) {
                    // Respeta EXACTAMENTE el modelo elegido por el usuario (sin cambiarlo a escondidas)
                    runGoogleAiStudio(settings.googleApiKey, settings.googleModel, sysPrompt, fullUserBlock, enableSearch = true)
                } else {
                    Result.failure(IllegalStateException("Falta API Key de Google AI Studio."))
                }
                if (res.isSuccess) {
                    emit(res.getOrThrow() to "Google AI (${settings.googleModel})")
                } else {
                    val fb = tryFallbacksExcluding(AiEngineMode.GOOGLE_AI_STUDIO, settings, sysPrompt, fullUserBlock, wantsWeb)
                    if (fb != null) emit(fb)
                    else emit("[EMOTION:alerta] Google AI (${settings.googleModel}) no respondió: ${res.exceptionOrNull()?.message}\n\nCargá OpenRouter, Groq o el modelo local en ⚙️ Ajustes para tener respaldo automático." to "Error API")
                }
            }

            AiEngineMode.GROQ_API -> {
                val res = if (settings.groqApiKey.isNotBlank()) {
                    runGroqApi(settings.groqApiKey, settings.groqModel, sysPrompt, fullUserBlock)
                } else {
                    Result.failure(IllegalStateException("Falta API Key de Groq."))
                }
                if (res.isSuccess) {
                    val webTag = if (liveWebBlock.isNotBlank()) " +Web" else ""
                    emit(res.getOrThrow() to "Groq$webTag (${settings.groqModel})")
                } else {
                    val fb = tryFallbacksExcluding(AiEngineMode.GROQ_API, settings, sysPrompt, fullUserBlock, wantsWeb)
                    if (fb != null) emit(fb)
                    else emit("[EMOTION:alerta] Error Groq (${settings.groqModel}): ${res.exceptionOrNull()?.message}" to "Error Groq")
                }
            }

            AiEngineMode.GEMINI_NANO -> {
                val nano = checkGeminiNanoStatus()
                if (nano.isReady) {
                    val txt = runGeminiNano("$sysPrompt\n\n$fullUserBlock")
                    if (txt != null) {
                        emit(txt to "Gemini Nano (Offline)")
                        return@flow
                    }
                }
                val fb = tryFallbacksExcluding(AiEngineMode.GEMINI_NANO, settings, sysPrompt, fullUserBlock, wantsWeb)
                if (fb != null) emit(fb)
                else emit("[EMOTION:duda] Gemini Nano no está disponible y no hay otro motor cargado en ⚙️ Ajustes." to "Fallback Gemini App")
            }

            AiEngineMode.GEMINI_APP_INTENT -> {
                emit("[EMOTION:pensando] Abrí esta consulta directo en la app oficial de Gemini de tu teléfono con el botón de abajo (usa tu cuenta activa, sin claves extra)." to "App Gemini")
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Cascada automática entre motores cuando el elegido devuelve error o respuesta nula/vacía:
     * OpenRouter -> Groq -> Google AI Studio -> IA Local Offline.
     */
    private suspend fun tryFallbacksExcluding(
        excluded: AiEngineMode,
        settings: AppSettings,
        sysPrompt: String,
        fullUserBlock: String,
        wantsWeb: Boolean
    ): Pair<String, String>? {
        if (excluded != AiEngineMode.OPENROUTER && settings.openRouterApiKey.isNotBlank()) {
            runOpenRouterSmart(settings.openRouterApiKey, settings.openRouterModel, sysPrompt, fullUserBlock, wantsWeb).getOrNull()?.let { (txt, mod, nativeWeb) ->
                val w = if (nativeWeb) "+Web" else ""
                return txt to "OpenRouter$w (Respaldo: $mod)"
            }
        }
        if (excluded != AiEngineMode.GROQ_API && settings.groqApiKey.isNotBlank()) {
            runGroqApi(settings.groqApiKey, settings.groqModel, sysPrompt, fullUserBlock).getOrNull()?.let { txt ->
                return txt to "Groq (Respaldo: ${settings.groqModel})"
            }
        }
        if (excluded != AiEngineMode.GOOGLE_AI_STUDIO && settings.googleApiKey.isNotBlank()) {
            runGoogleAiStudio(settings.googleApiKey, settings.googleModel, sysPrompt, fullUserBlock, enableSearch = true).getOrNull()?.let { txt ->
                return txt to "Google AI (Respaldo: ${settings.googleModel})"
            }
        }
        val localFile = storage.getLocalModelFile(settings.localModelName)
        if (excluded != AiEngineMode.LOCAL_OFFLINE && localFile.exists() && localFile.length() > 10_000_000L) {
            runLocalOfflineLlm(localFile, sysPrompt, fullUserBlock).getOrNull()?.let { (txt, backend) ->
                return txt to "Offline $backend (Respaldo: ${settings.localModelName.removeSuffix(".task")})"
            }
        }
        return null
    }

    /**
     * Ejecuta el modelo elegido en OpenRouter con búsqueda web nativa cuando corresponde.
     * Si el modelo gratuito elegido devuelve content: null o está saturado, intenta openrouter/free una vez antes de saltar de motor.
     */
    private fun runOpenRouterSmart(
        apiKey: String,
        preferredModel: String,
        sysPrompt: String,
        userBlock: String,
        wantsWeb: Boolean
    ): Result<Triple<String, String, Boolean>> {
        val chosen = preferredModel.trim().ifBlank { "openrouter/free" }
        val firstTry = runOpenRouterApi(apiKey, chosen, sysPrompt, userBlock, enableWebPlugin = wantsWeb)
        if (firstTry.isSuccess) {
            val (txt, usedWeb) = firstTry.getOrThrow()
            return Result.success(Triple(txt, chosen, usedWeb))
        }
        if (chosen != "openrouter/free" && chosen.endsWith(":free")) {
            val backupFree = runOpenRouterApi(apiKey, "openrouter/free", sysPrompt, userBlock, enableWebPlugin = false)
            if (backupFree.isSuccess) {
                val (txt, usedWeb) = backupFree.getOrThrow()
                return Result.success(Triple(txt, "openrouter/free", usedWeb))
            }
        }
        return Result.failure(firstTry.exceptionOrNull() ?: IllegalStateException("Error OpenRouter"))
    }

    private fun runOpenRouterApi(
        apiKey: String,
        model: String,
        sysPrompt: String,
        userBlock: String,
        enableWebPlugin: Boolean
    ): Result<Pair<String, Boolean>> {
        return try {
            val mod = model.ifBlank { "openrouter/free" }
            val payload = JSONObject().apply {
                put("model", mod)
                put("temperature", 0.35)
                put("max_tokens", 1536)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", sysPrompt))
                    put(JSONObject().put("role", "user").put("content", userBlock))
                })
                if (enableWebPlugin) {
                    put("plugins", JSONArray().put(JSONObject().put("id", "web").put("max_results", 3)))
                }
            }
            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("HTTP-Referer", "https://cachito.app")
                .header("X-Title", "Cachito Android")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    // Si el plugin web falla por falta de créditos (402) o incompatibilidad (400), reintenta sin plugin web
                    if (enableWebPlugin && (resp.code == 400 || resp.code == 402)) {
                        return runOpenRouterApi(apiKey, model, sysPrompt, userBlock, enableWebPlugin = false)
                    }
                    val msg = try {
                        JSONObject(raw).optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                    } catch (_: Exception) { "HTTP ${resp.code}" }
                    return Result.failure(IllegalStateException(msg))
                }
                val j = JSONObject(raw)
                val msgObj = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val rawContent = if (msgObj == null || msgObj.isNull("content")) "" else msgObj.optString("content", "")
                val cleaned = cleanThinkTags(rawContent)
                if (cleaned.isBlank() || cleaned.equals("null", ignoreCase = true)) {
                    return Result.failure(IllegalStateException("El modelo devolvió respuesta vacía (content null)."))
                }
                Result.success(cleaned to enableWebPlugin)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun runGroqApi(apiKey: String, model: String, sysPrompt: String, userBlock: String): Result<String> {
        return try {
            val mod = model.ifBlank { "llama-3.3-70b-versatile" }
            val payload = JSONObject().apply {
                put("model", mod)
                put("temperature", 0.35)
                put("max_tokens", 1536)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", sysPrompt))
                    put(JSONObject().put("role", "user").put("content", userBlock))
                })
            }
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val msg = try {
                        JSONObject(raw).optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                    } catch (_: Exception) { "HTTP ${resp.code}" }
                    return Result.failure(IllegalStateException(msg))
                }
                val j = JSONObject(raw)
                val msgObj = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val rawContent = if (msgObj == null || msgObj.isNull("content")) "" else msgObj.optString("content", "")
                val cleaned = cleanThinkTags(rawContent)
                if (cleaned.isBlank() || cleaned.equals("null", ignoreCase = true)) {
                    return Result.failure(IllegalStateException("Groq devolvió respuesta vacía."))
                }
                Result.success(cleaned)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun runGoogleAiStudio(
        apiKey: String,
        model: String,
        sysPrompt: String,
        userBlock: String,
        enableSearch: Boolean
    ): Result<String> {
        val mod = model.trim().removePrefix("models/").ifBlank { "gemini-2.5-flash" }
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$mod:generateContent"
            val payload = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sysPrompt))))
                put("contents", JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userBlock)))
                ))
                put("generationConfig", JSONObject().put("temperature", 0.35).put("maxOutputTokens", 1536))
                if (enableSearch) {
                    put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                }
            }
            val req = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey.trim())
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    if (enableSearch && resp.code == 400) {
                        return runGoogleAiStudio(apiKey, model, sysPrompt, userBlock, enableSearch = false)
                    }
                    val msg = try {
                        JSONObject(raw).optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                    } catch (_: Exception) { "HTTP ${resp.code}" }
                    return Result.failure(IllegalStateException(msg))
                }
                val j = JSONObject(raw)
                val parts = j.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val text = buildString {
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val t = parts.getJSONObject(i).optString("text", "")
                            if (t.isNotBlank()) append(t)
                        }
                    }
                }
                val cleaned = cleanThinkTags(text)
                if (cleaned.isBlank() || cleaned.equals("null", ignoreCase = true)) {
                    return Result.failure(IllegalStateException("Google AI devolvió respuesta vacía."))
                }
                Result.success(cleaned)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun runGeminiNano(prompt: String): String? {
        return try {
            val client = getOrCreateNanoClient()
            val req = generateContentRequest(com.google.mlkit.genai.prompt.TextPart(prompt)) {
                temperature = 0.4f
                maxOutputTokens = 1024
            }
            val resp = client.generateContent(req)
            val text = resp.candidates.firstOrNull()?.text?.trim()
            if (!text.isNullOrBlank()) cleanThinkTags(text) else null
        } catch (e: Throwable) {
            Log.e("CachitoBrain", "Error Gemini Nano: ${e.message}")
            null
        }
    }

    fun cleanThinkTags(raw: String): String {
        return raw
            .replace(Regex("<(?:think|thought|reasoning)>.*?</(?:think|thought|reasoning)>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("</?(?:think|thought|reasoning)>", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    fun openInPhoneGeminiApp(promptText: String) {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, promptText)
                setPackage("com.google.android.apps.bard")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(sendIntent)
        } catch (_: Exception) {
            try {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://gemini.google.com/app?q=${Uri.encode(promptText)}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            } catch (_: Exception) {}
        }
    }
}
