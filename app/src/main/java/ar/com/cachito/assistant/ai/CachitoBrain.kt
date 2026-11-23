package ar.com.cachito.assistant.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import ar.com.cachito.assistant.data.LocalStorage
import ar.com.cachito.assistant.model.*
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
import java.util.concurrent.TimeUnit

data class NanoAvailability(
    val statusCode: Int,
    val label: String,
    val isReady: Boolean
)

class CachitoBrain(
    private val context: Context,
    private val storage: LocalStorage
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private var nanoClient: GenerativeModel? = null
    private var cachedGoogleModels: List<String> = emptyList()

    private fun getOrCreateNanoClient(): GenerativeModel {
        return nanoClient ?: Generation.getClient().also { nanoClient = it }
    }

    suspend fun checkGeminiNanoStatus(): NanoAvailability = withContext(Dispatchers.IO) {
        try {
            val client = getOrCreateNanoClient()
            val status = client.checkStatus()
            when (status) {
                FeatureStatus.AVAILABLE -> NanoAvailability(status, "Gemini Nano listo (Offline)", true)
                FeatureStatus.DOWNLOADABLE -> NanoAvailability(status, "Gemini Nano descargable en AICore", false)
                FeatureStatus.DOWNLOADING -> NanoAvailability(status, "Descargando Gemini Nano...", false)
                else -> NanoAvailability(status, "AICore no habilitado por el fabricante en este teléfono", false)
            }
        } catch (e: Throwable) {
            Log.w("CachitoBrain", "AICore checkStatus error: ${e.message}")
            NanoAvailability(-1, "AICore no disponible en este hardware", false)
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
            val res = runGoogleAiStudio(apiKey, modelName, sysPrompt, userBlock)
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
        val ragHits = storage.searchRag(userMessage, topK = 3)
        val (_, recentTurns) = storage.getSessionTurns()
        val sysPrompt = buildSystemPrompt(settings, ticker)

        val fullUserBlock = buildString {
            if (ragHits.isNotEmpty()) {
                appendLine("Memoria relevante:")
                ragHits.forEach { appendLine(it) }
                appendLine()
            }
            if (recentTurns.isNotEmpty()) {
                appendLine("Contexto reciente:")
                recentTurns.takeLast(3).forEach {
                    appendLine("Usuario: ${it.user.take(160)}")
                    appendLine("Cachito: ${it.assistant.take(200)}")
                }
                appendLine()
            }
            if (!attachedDocText.isNullOrBlank()) {
                appendLine("Texto adjunto/escaneado:")
                appendLine(attachedDocText.take(3500))
                appendLine()
            }
            append("Consulta actual: $userMessage")
        }

        when (settings.preferredEngine) {
            AiEngineMode.GOOGLE_AI_STUDIO -> {
                if (settings.googleApiKey.isBlank()) {
                    emit(tryOtherEnginesOrHandoff(settings, sysPrompt, fullUserBlock))
                } else {
                    val res = runGoogleWithAutoFailover(settings.googleApiKey, settings.googleModel, sysPrompt, fullUserBlock)
                    if (res.isSuccess) {
                        val (replyText, usedModel) = res.getOrThrow()
                        emit(replyText to "Google AI ($usedModel)")
                    } else {
                        if (settings.groqApiKey.isNotBlank()) {
                            val groqRes = runGroqApi(settings.groqApiKey, settings.groqModel, sysPrompt, fullUserBlock)
                            if (groqRes.isSuccess) {
                                emit(groqRes.getOrThrow() to "Groq Auto (${settings.groqModel})")
                                return@flow
                            }
                        }
                        val err = res.exceptionOrNull()
                        emit("[EMOTION:alerta] Todos los modelos de Google están con alta demanda ahora (${err?.message}).\n\nPodés abrir directo en la **App Gemini** abajo o cargar una clave gratuita de **Groq** en ⚙️ Ajustes como respaldo automático." to "Error API")
                    }
                }
            }

            AiEngineMode.GROQ_API -> {
                if (settings.groqApiKey.isBlank()) {
                    emit(tryOtherEnginesOrHandoff(settings, sysPrompt, fullUserBlock))
                } else {
                    val res = runGroqApi(settings.groqApiKey, settings.groqModel, sysPrompt, fullUserBlock)
                    res.fold(
                        onSuccess = { emit(it to "Groq (${settings.groqModel})") },
                        onFailure = { err ->
                            if (settings.googleApiKey.isNotBlank()) {
                                val gRes = runGoogleWithAutoFailover(settings.googleApiKey, settings.googleModel, sysPrompt, fullUserBlock)
                                if (gRes.isSuccess) {
                                    val (replyText, usedModel) = gRes.getOrThrow()
                                    emit(replyText to "Google AI Auto ($usedModel)")
                                    return@flow
                                }
                            }
                            emit("[EMOTION:alerta] Error Groq (${settings.groqModel}): ${err.message}" to "Error Groq")
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

    private suspend fun tryOtherEnginesOrHandoff(
        settings: AppSettings,
        sysPrompt: String,
        fullUserBlock: String
    ): Pair<String, String> {
        if (settings.groqApiKey.isNotBlank()) {
            runGroqApi(settings.groqApiKey, settings.groqModel, sysPrompt, fullUserBlock).getOrNull()?.let {
                return it to "Groq (${settings.groqModel})"
            }
        }
        if (settings.googleApiKey.isNotBlank()) {
            runGoogleWithAutoFailover(settings.googleApiKey, settings.googleModel, sysPrompt, fullUserBlock).getOrNull()?.let {
                return it.first to "Google AI (${it.second})"
            }
        }
        return "[EMOTION:duda] Todavía no configuraste una API Key en ⚙️ Ajustes (Google AI Studio o Groq gratis). También podés tocar **Abrir en App Gemini** abajo para resolverlo gratis con tu cuenta de Google del teléfono." to "Fallback Gemini App"
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

    private fun runGroqApi(apiKey: String, model: String, sysPrompt: String, userBlock: String): Result<String> {
        return try {
            val mod = model.ifBlank { "llama-3.3-70b-versatile" }
            val payload = JSONObject().apply {
                put("model", mod)
                put("temperature", 0.4)
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

    private fun runGoogleAiStudio(apiKey: String, model: String, sysPrompt: String, userBlock: String): Result<String> {
        val mod = model.trim().removePrefix("models/").ifBlank { "gemini-3.5-flash-lite" }
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$mod:generateContent"
            val payload = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sysPrompt))))
                put("contents", JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userBlock)))
                ))
                put("generationConfig", JSONObject().put("temperature", 0.4).put("maxOutputTokens", 1536))
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
                    val msg = try {
                        JSONObject(raw).optJSONObject("error")?.optString("message") ?: "HTTP ${resp.code}"
                    } catch (_: Exception) { "HTTP ${resp.code}" }
                    return Result.failure(IllegalStateException(msg))
                }
                val j = JSONObject(raw)
                val text = j.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getString("text")
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
