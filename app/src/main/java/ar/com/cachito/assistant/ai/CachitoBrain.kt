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
    private var cachedGoogleModels: List<String> = emptyList()

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

    /**
     * Ejecuta el modelo local con ruta LiteRT moderna:
     * intenta GPU primero y si el driver no lo soporta pasa automático a CPU LiteRT.
     */
    private fun runLocalOfflineLlm(modelFile: File, sysPrompt: String, userBlock: String): Result<Pair<String, String>> {
        if (!modelFile.exists() || modelFile.length() < 10_000_000L) {
            return Result.failure(IllegalStateException("El modelo local todavía no está descargado. Andá a ⚙️ Ajustes → IA Local Offline y tocalo para descargar."))
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
            if (raw.isBlank()) {
                Result.failure(IllegalStateException("El modelo local devolvió respuesta vacía."))
            } else {
                Result.success(cleanThinkTags(raw) to loadedLocalBackend)
            }
        } catch (e: Throwable) {
            Log.e("CachitoBrain", "Error MediaPipe LLM: ${e.message}")
            Result.failure(IllegalStateException("Error ejecutando modelo local: ${e.message}"))
        }
    }

    /**
     * Consulta titulares reales en vivo desde Google News Argentina cuando se piden noticias o actualidad.
     */
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

    /**
     * Búsqueda web general gratuita en vivo vía DuckDuckGo HTML (sin API Key).
     * Funciona para OpenRouter, Groq y Modelo Local (si hay señal; si no hay señal falla en silencio).
     */
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

    /**
     * Decide automáticamente si la consulta requiere noticias en vivo o búsqueda web general.
     */
    private fun fetchLiveWebOrNewsIfNeeded(userQuery: String, isCompactLocal: Boolean): String {
        val low = userQuery.lowercase()
        val newsTriggers = listOf("noticia", "noticias", "novedades", "paso hoy", "pasó hoy", "actualidad", "diario", "titulares", "ultimas", "últimas")
        if (newsTriggers.any { low.contains(it) }) {
            return fetchLiveNewsContext(userQuery, maxItems = if (isCompactLocal) 3 else 6)
        }

        val webTriggers = listOf(
            "busca", "buscá", "buscar", "en la web", "en al web", "internet", "google",
            "pasaje", "pasajes", "vuelo", "vuelos", "precio", "precios", "cuanto sale", "cuánto sale",
            "cuanto cuesta", "cuánto cuesta", "donde comprar", "dónde comprar", "barato", "economico", "económico",
            "horario", "horarios", "telefono", "teléfono", "direccion", "dirección", "resultado", "partido",
            "hoy", "actual", "2026", "quien gano", "quién ganó", "cotizacion", "cotización"
        )
        if (webTriggers.any { low.contains(it) }) {
            return fetchLiveDuckDuckGoContext(
                userQuery = userQuery,
                maxItems = if (isCompactLocal) 3 else 5,
                maxSnippetLen = if (isCompactLocal) 120 else 220
            )
        }
        return ""
    }

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
                val nemotronFree = mutableListOf<String>()
                val otherFree = mutableListOf<String>()
                val paidPopular = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val id = arr.getJSONObject(i).optString("id", "")
                    if (id.isBlank() || id.contains("embed", ignoreCase = true) || id.contains("guard", ignoreCase = true)) continue
                    when {
                        id.contains("nemotron", ignoreCase = true) && id.endsWith(":free") -> nemotronFree.add(id)
                        id.endsWith(":free") -> otherFree.add(id)
                        id.contains("claude", ignoreCase = true) || id.contains("nemotron", ignoreCase = true) -> paidPopular.add(id)
                    }
                }
                val combined = (listOf("openrouter/free") + nemotronFree.sorted() + otherFree.sorted().take(15) + paidPopular.sorted().take(10)).distinct()
                Result.success(combined)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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
                val models = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val name = m.optString("name", "").removePrefix("models/")
                    val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                    if (name.contains("gemini", ignoreCase = true) &&
                        methods.contains("generateContent") &&
                        !name.contains("embedding", ignoreCase = true) &&
                        !name.contains("imagen", ignoreCase = true) &&
                        !name.contains("veo", ignoreCase = true) &&
                        !name.contains("tts", ignoreCase = true) &&
                        !name.contains("robotics", ignoreCase = true) &&
                        !name.contains("computer-use", ignoreCase = true)
                    ) {
                        models.add(name)
                    }
                }
                if (models.isEmpty()) {
                    Result.failure(IllegalStateException("La clave respondió OK pero no devolvió modelos generateContent."))
                } else {
                    val sorted = models.sortedDescending()
                    cachedGoogleModels = sorted
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
                val models = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val id = arr.getJSONObject(i).optString("id", "")
                    if (id.isNotBlank() && !id.contains("whisper", ignoreCase = true) && !id.contains("guard", ignoreCase = true)) {
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
            appendLine("Si te pasan 'Titulares reales en vivo' o 'Resultados web en vivo' en el contexto, usalos y resumilos directamente con claridad sin decir que no tenés acceso a internet.")
            appendLine("Encabezá cada respuesta con una etiqueta de emoción válida: [EMOTION:neutro], [EMOTION:pensando], [EMOTION:alegre], [EMOTION:concentrado], [EMOTION:duda] o [EMOTION:alerta].")
            appendLine("Datos en vivo: $liveWeather $liveDollar")
            if (financeCtx.isNotBlank()) appendLine(financeCtx)
        }.trim()
    }

    private fun isBusyOrRateLimited(msg: String): Boolean {
        val low = msg.lowercase()
        return low.contains("high demand") ||
            low.contains("overloaded") ||
            low.contains("unavailable") ||
            low.contains("resource has been exhausted") ||
            low.contains("quota") ||
            low.contains("rate limit") ||
            low.contains("503") ||
            low.contains("429") ||
            low.contains("not found") ||
            low.contains("404")
    }

    private fun runOpenRouterWithFailover(
        apiKey: String,
        preferredModel: String,
        sysPrompt: String,
        userBlock: String
    ): Result<Pair<String, String>> {
        val candidates = LinkedHashSet<String>()
        if (preferredModel.isNotBlank()) candidates.add(preferredModel.trim())
        candidates.add("nvidia/nemotron-3.5-lightning:free")
        candidates.add("nvidia/nemotron-3.5-super-vl:free")
        candidates.add("nvidia/nemotron-3-ultra-550b-a55b:free")
        candidates.add("openrouter/free")

        var lastError: Throwable = IllegalStateException("No se pudo conectar con OpenRouter.")
        for (mod in candidates) {
            val res = runOpenRouterApi(apiKey, mod, sysPrompt, userBlock)
            if (res.isSuccess) {
                return Result.success(res.getOrThrow() to mod)
            }
            val err = res.exceptionOrNull() ?: lastError
            lastError = err
            if (!isBusyOrRateLimited(err.message ?: "")) {
                return Result.failure(err)
            }
            Log.w("CachitoBrain", "OpenRouter $mod ocupado (${err.message}), probando siguiente...")
        }
        return Result.failure(lastError)
    }

    private suspend fun runGoogleWithAutoFailover(
        apiKey: String,
        preferredModel: String,
        sysPrompt: String,
        userBlock: String
    ): Result<Pair<String, String>> {
        if (cachedGoogleModels.isEmpty()) {
            fetchAvailableGoogleModels(apiKey)
        }
        val cleanPref = preferredModel.trim().removePrefix("models/")
        val candidates = LinkedHashSet<String>()
        if (cleanPref.isNotBlank()) candidates.add(cleanPref)

        val priorityHints = listOf("flash-lite", "flash", "pro", "gemini")
        for (hint in priorityHints) {
            cachedGoogleModels.filter { it.contains(hint, ignoreCase = true) }.forEach { candidates.add(it) }
        }
        if (candidates.isEmpty()) {
            candidates.add("gemini-3.5-flash-lite")
            candidates.add("gemini-3.5-flash")
        }

        var lastError: Throwable = IllegalStateException("No se pudo conectar con Google AI Studio.")
        for (modelName in candidates.take(8)) {
            val res = runGoogleAiStudio(apiKey, modelName, sysPrompt, userBlock, enableSearch = true)
            if (res.isSuccess) {
                return Result.success(res.getOrThrow() to modelName)
            }
            val err = res.exceptionOrNull() ?: lastError
            lastError = err
            if (!isBusyOrRateLimited(err.message ?: "")) {
                return Result.failure(err)
            }
            Log.w("CachitoBrain", "Modelo $modelName ocupado (${err.message}), probando siguiente...")
        }
        return Result.failure(lastError)
    }

    fun generateReply(
        userMessage: String,
        attachedDocText: String?,
        ticker: TickerSnapshot
    ): Flow<Pair<String, String>> = flow {
        val settings = storage.loadSettings()
        val isLocalMode = settings.preferredEngine == AiEngineMode.LOCAL_OFFLINE
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

        when (settings.preferredEngine) {
            AiEngineMode.LOCAL_OFFLINE -> {
                val modelFile = storage.getLocalModelFile(settings.localModelName)
                val res = runLocalOfflineLlm(modelFile, sysPrompt, fullUserBlock)
                res.fold(
                    onSuccess = { (txt, backend) ->
                        val webTag = if (liveWebBlock.isNotBlank()) "+Web" else ""
                        emit(txt to "Offline $backend$webTag (${settings.localModelName.removeSuffix(".task")})")
                    },
                    onFailure = { err ->
                        emit("[EMOTION:alerta] ${err.message}" to "Error Offline")
                    }
                )
            }

            AiEngineMode.OPENROUTER -> {
                if (settings.openRouterApiKey.isBlank()) {
                    emit(tryOtherEnginesOrHandoff(settings, sysPrompt, fullUserBlock))
                } else {
                    val res = runOpenRouterWithFailover(settings.openRouterApiKey, settings.openRouterModel, sysPrompt, fullUserBlock)
                    if (res.isSuccess) {
                        val (replyText, usedModel) = res.getOrThrow()
                        val webTag = if (liveWebBlock.isNotBlank()) " +Web" else ""
                        emit(replyText to "OpenRouter$webTag ($usedModel)")
                    } else {
                        val fallback = tryFallbacksExcluding(AiEngineMode.OPENROUTER, settings, sysPrompt, fullUserBlock)
                        if (fallback != null) {
                            emit(fallback)
                        } else {
                            emit("[EMOTION:alerta] Error OpenRouter: ${res.exceptionOrNull()?.message}\n\nRevisá tu clave en ⚙️ Ajustes o probá el modo **IA Local Offline**." to "Error API")
                        }
                    }
                }
            }

            AiEngineMode.GOOGLE_AI_STUDIO -> {
                if (settings.googleApiKey.isBlank()) {
                    emit(tryOtherEnginesOrHandoff(settings, sysPrompt, fullUserBlock))
                } else {
                    val res = runGoogleWithAutoFailover(settings.googleApiKey, settings.googleModel, sysPrompt, fullUserBlock)
                    if (res.isSuccess) {
                        val (replyText, usedModel) = res.getOrThrow()
                        emit(replyText to "Google AI ($usedModel)")
                    } else {
                        val fallback = tryFallbacksExcluding(AiEngineMode.GOOGLE_AI_STUDIO, settings, sysPrompt, fullUserBlock)
                        if (fallback != null) {
                            emit(fallback)
                        } else {
                            val err = res.exceptionOrNull()
                            emit("[EMOTION:alerta] Modelos de Google saturados ahora (${err?.message}).\n\nPodés usar **OpenRouter**, **Groq** o **IA Local Offline** en ⚙️ Ajustes." to "Error API")
                        }
                    }
                }
            }

            AiEngineMode.GROQ_API -> {
                if (settings.groqApiKey.isBlank()) {
                    emit(tryOtherEnginesOrHandoff(settings, sysPrompt, fullUserBlock))
                } else {
                    val res = runGroqApi(settings.groqApiKey, settings.groqModel, sysPrompt, fullUserBlock)
                    res.fold(
                        onSuccess = {
                            val webTag = if (liveWebBlock.isNotBlank()) " +Web" else ""
                            emit(it to "Groq$webTag (${settings.groqModel})")
                        },
                        onFailure = { err ->
                            val fallback = tryFallbacksExcluding(AiEngineMode.GROQ_API, settings, sysPrompt, fullUserBlock)
                            if (fallback != null) {
                                emit(fallback)
                            } else {
                                emit("[EMOTION:alerta] Error Groq (${settings.groqModel}): ${err.message}" to "Error Groq")
                            }
                        }
                    )
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
                emit(tryOtherEnginesOrHandoff(settings, sysPrompt, fullUserBlock))
            }

            AiEngineMode.GEMINI_APP_INTENT -> {
                emit("[EMOTION:pensando] Abrí esta consulta directo en la app oficial de Gemini de tu teléfono con el botón de abajo (usa tu cuenta activa, sin claves extra)." to "App Gemini")
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun tryFallbacksExcluding(
        excluded: AiEngineMode,
        settings: AppSettings,
        sysPrompt: String,
        fullUserBlock: String
    ): Pair<String, String>? {
        if (excluded != AiEngineMode.OPENROUTER && settings.openRouterApiKey.isNotBlank()) {
            runOpenRouterWithFailover(settings.openRouterApiKey, settings.openRouterModel, sysPrompt, fullUserBlock).getOrNull()?.let {
                return it.first to "OpenRouter Auto (${it.second})"
            }
        }
        if (excluded != AiEngineMode.GROQ_API && settings.groqApiKey.isNotBlank()) {
            runGroqApi(settings.groqApiKey, settings.groqModel, sysPrompt, fullUserBlock).getOrNull()?.let {
                return it to "Groq Auto (${settings.groqModel})"
            }
        }
        if (excluded != AiEngineMode.GOOGLE_AI_STUDIO && settings.googleApiKey.isNotBlank()) {
            runGoogleWithAutoFailover(settings.googleApiKey, settings.googleModel, sysPrompt, fullUserBlock).getOrNull()?.let {
                return it.first to "Google AI Auto (${it.second})"
            }
        }
        val localFile = storage.getLocalModelFile(settings.localModelName)
        if (excluded != AiEngineMode.LOCAL_OFFLINE && localFile.exists() && localFile.length() > 10_000_000L) {
            runLocalOfflineLlm(localFile, sysPrompt, fullUserBlock).getOrNull()?.let { (txt, backend) ->
                return txt to "Offline $backend (${settings.localModelName.removeSuffix(".task")})"
            }
        }
        return null
    }

    private suspend fun tryOtherEnginesOrHandoff(
        settings: AppSettings,
        sysPrompt: String,
        fullUserBlock: String
    ): Pair<String, String> {
        tryFallbacksExcluding(AiEngineMode.GEMINI_APP_INTENT, settings, sysPrompt, fullUserBlock)?.let { return it }
        return "[EMOTION:duda] Todavía no configuraste una API Key ni descargaste el modelo local en ⚙️ Ajustes. También podés tocar **Abrir en App Gemini** abajo." to "Fallback Gemini App"
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

    private fun runOpenRouterApi(apiKey: String, model: String, sysPrompt: String, userBlock: String): Result<String> {
        return try {
            val mod = model.ifBlank { "nvidia/nemotron-3.5-lightning:free" }
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
                    val msg = try {
                        JSONObject(raw).optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                    } catch (_: Exception) { "HTTP ${resp.code}" }
                    return Result.failure(IllegalStateException(msg))
                }
                val j = JSONObject(raw)
                val content = j.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                Result.success(cleanThinkTags(content))
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
                val content = j.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                Result.success(cleanThinkTags(content))
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
        val mod = model.trim().removePrefix("models/").ifBlank { "gemini-3.5-flash-lite" }
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
                val parts = j.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                val text = buildString {
                    for (i in 0 until parts.length()) {
                        val t = parts.getJSONObject(i).optString("text", "")
                        if (t.isNotBlank()) append(t)
                    }
                }
                Result.success(cleanThinkTags(text))
            }
        } catch (e: Exception) {
            Result.failure(e)
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
