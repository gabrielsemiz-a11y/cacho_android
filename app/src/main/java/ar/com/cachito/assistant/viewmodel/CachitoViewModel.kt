package ar.com.cachito.assistant.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ar.com.cachito.assistant.ai.CachitoBrain
import ar.com.cachito.assistant.ai.NanoAvailability
import ar.com.cachito.assistant.data.LocalStorage
import ar.com.cachito.assistant.model.*
import ar.com.cachito.assistant.network.LiveDataClient
import ar.com.cachito.assistant.vision.VisionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.max

class CachitoViewModel(app: Application) : AndroidViewModel(app) {

    val storage = LocalStorage(app)
    val brain = CachitoBrain(app, storage)
    val liveClient = LiveDataClient()
    val visionHelper = VisionHelper(app)

    private val _emotion = MutableStateFlow(CachoEmotion.NEUTRO)
    val emotion: StateFlow<CachoEmotion> = _emotion.asStateFlow()

    private val _ticker = MutableStateFlow(TickerSnapshot())
    val ticker: StateFlow<TickerSnapshot> = _ticker.asStateFlow()

    private val _messages = MutableStateFlow(
        listOf(
            ChatMessage(
                id = "welcome",
                isUser = false,
                text = "¡Buenas! Soy Cachito v2.42. Traigo IA Local Offline (LiteRT GPU/CPU), Escáner CameraX HD con enfoque macro y linterna, noticias en vivo, claves cifradas y radar de objetos.",
                timestamp = nowTime(),
                engineUsed = "Cachito v2.42"
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _settings = MutableStateFlow(storage.loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _nanoStatus = MutableStateFlow(NanoAvailability(-1, "Verificando...", false, false))
    val nanoStatus: StateFlow<NanoAvailability> = _nanoStatus.asStateFlow()

    private val _localModelDownloaded = MutableStateFlow(false)
    val localModelDownloaded: StateFlow<Boolean> = _localModelDownloaded.asStateFlow()

    private val _localModelProgress = MutableStateFlow(0f)
    val localModelProgress: StateFlow<Float> = _localModelProgress.asStateFlow()

    private val _localModelStatus = MutableStateFlow("")
    val localModelStatus: StateFlow<String> = _localModelStatus.asStateFlow()

    private val _isDownloadingLocalModel = MutableStateFlow(false)
    val isDownloadingLocalModel: StateFlow<Boolean> = _isDownloadingLocalModel.asStateFlow()

    private val _availableOpenRouterModels = MutableStateFlow(
        listOf(
            "nvidia/nemotron-3.5-lightning:free",
            "nvidia/nemotron-3.5-super-vl:free",
            "nvidia/nemotron-3-ultra-550b-a55b:free",
            "openrouter/free"
        )
    )
    val availableOpenRouterModels: StateFlow<List<String>> = _availableOpenRouterModels.asStateFlow()

    private val _availableGoogleModels = MutableStateFlow(emptyList<String>())
    val availableGoogleModels: StateFlow<List<String>> = _availableGoogleModels.asStateFlow()

    private val _availableGroqModels = MutableStateFlow(emptyList<String>())
    val availableGroqModels: StateFlow<List<String>> = _availableGroqModels.asStateFlow()

    private val _modelsFetchStatus = MutableStateFlow("")
    val modelsFetchStatus: StateFlow<String> = _modelsFetchStatus.asStateFlow()

    private val _financeBook = MutableStateFlow(storage.loadFinanceBook())
    val financeBook: StateFlow<FinanceBook> = _financeBook.asStateFlow()

    private val _ragCount = MutableStateFlow(storage.ragCount())
    val ragCount: StateFlow<Int> = _ragCount.asStateFlow()

    private val _pendingDocName = MutableStateFlow<String?>(null)
    val pendingDocName: StateFlow<String?> = _pendingDocName.asStateFlow()

    private val _pendingDocText = MutableStateFlow<String?>(null)
    val pendingDocText: StateFlow<String?> = _pendingDocText.asStateFlow()

    private val _lastScannedPdfUri = MutableStateFlow<Uri?>(null)
    val lastScannedPdfUri: StateFlow<Uri?> = _lastScannedPdfUri.asStateFlow()

    private val _detectedTargets = MutableStateFlow(emptyList<DetectedTarget>())
    val detectedTargets: StateFlow<List<DetectedTarget>> = _detectedTargets.asStateFlow()

    private val _referenceObjects = MutableStateFlow(loadReferenceFiles())
    val referenceObjects = _referenceObjects.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        refreshLocalModelState()

        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val res = engine.setLanguage(Locale("es", "AR"))
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                engine.setSpeechRate(0.85f)
                engine.setPitch(0.85f)
                try {
                    val spanishVoices = engine.voices?.filter {
                        it.locale.language.equals("es", ignoreCase = true) && !it.isNetworkConnectionRequired
                    }.orEmpty()
                    val maleHints = listOf("male", "es-us-x-esd", "es-us-x-sfb", "es-es-x-eef", "es-es-x-eed", "jorge", "diego", "carlos", "tomas")
                    val maleVoice = spanishVoices.firstOrNull { v ->
                        maleHints.any { hint -> v.name.contains(hint, ignoreCase = true) }
                    }
                    if (maleVoice != null) {
                        engine.voice = maleVoice
                    }
                } catch (_: Exception) {}
            }
        }

        viewModelScope.launch {
            _nanoStatus.value = brain.checkGeminiNanoStatus()
            if (_settings.value.googleApiKey.isNotBlank()) {
                loadGoogleModelsFromApi(_settings.value.googleApiKey)
            }
        }

        viewModelScope.launch {
            while (isActive) {
                refreshLiveTicker()
                delay(5 * 60 * 1000L)
            }
        }
    }

    fun refreshLocalModelState() {
        val f = storage.getLocalModelFile(_settings.value.localModelName)
        val ok = f.exists() && f.length() > 10_000_000L
        _localModelDownloaded.value = ok
        _localModelStatus.value = if (ok) {
            "✓ Descargado (${f.length() / (1024 * 1024)} MB) — Listo sin internet"
        } else {
            "No descargado (~1.5 GB, se baja una sola vez)"
        }
    }

    fun downloadLocalOfflineModel(url: String, fileName: String) {
        if (_isDownloadingLocalModel.value) return
        val updated = _settings.value.copy(localModelUrl = url.trim(), localModelName = fileName.trim())
        storage.saveSettings(updated)
        _settings.value = updated

        viewModelScope.launch {
            _isDownloadingLocalModel.value = true
            _localModelProgress.value = 0f
            val dest = storage.getLocalModelFile(updated.localModelName)
            val res = brain.downloadLocalModel(updated.localModelUrl, dest) { pct, msg ->
                _localModelProgress.value = pct
                _localModelStatus.value = msg
            }
            _isDownloadingLocalModel.value = false
            res.fold(
                onSuccess = { refreshLocalModelState() },
                onFailure = { err -> _localModelStatus.value = "Error: ${err.message}" }
            )
        }
    }

    fun deleteLocalOfflineModel() {
        brain.releaseLocalLlm()
        val f = storage.getLocalModelFile(_settings.value.localModelName)
        if (f.exists()) f.delete()
        refreshLocalModelState()
    }

    fun resetConversation() {
        tts?.stop()
        storage.clearSessionOnly()
        _pendingDocName.value = null
        _pendingDocText.value = null
        _lastScannedPdfUri.value = null
        _emotion.value = CachoEmotion.NEUTRO
        _messages.value = listOf(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                isUser = false,
                text = "Conversación reiniciada. ¿En qué tema arrancamos ahora?",
                timestamp = nowTime(),
                engineUsed = "Cachito v2.42"
            )
        )
    }

    fun loadOpenRouterModelsFromApi(apiKey: String) {
        viewModelScope.launch {
            _modelsFetchStatus.value = "Consultando modelos en OpenRouter..."
            val res = brain.fetchAvailableOpenRouterModels(apiKey)
            res.fold(
                onSuccess = { list ->
                    _availableOpenRouterModels.value = list
                    _modelsFetchStatus.value = "✓ ${list.size} modelos listados en OpenRouter."
                },
                onFailure = { err ->
                    _modelsFetchStatus.value = "Error OpenRouter: ${err.message}"
                }
            )
        }
    }

    fun loadGoogleModelsFromApi(apiKey: String) {
        viewModelScope.launch {
            _modelsFetchStatus.value = "Consultando modelos en Google AI Studio..."
            val res = brain.fetchAvailableGoogleModels(apiKey)
            res.fold(
                onSuccess = { list ->
                    _availableGoogleModels.value = list
                    _modelsFetchStatus.value = "✓ ${list.size} modelos disponibles en tu cuenta Google."
                },
                onFailure = { err ->
                    _modelsFetchStatus.value = "Error Google: ${err.message}"
                }
            )
        }
    }

    fun loadGroqModelsFromApi(apiKey: String) {
        viewModelScope.launch {
            _modelsFetchStatus.value = "Consultando modelos en Groq..."
            val res = brain.fetchAvailableGroqModels(apiKey)
            res.fold(
                onSuccess = { list ->
                    _availableGroqModels.value = list
                    _modelsFetchStatus.value = "✓ ${list.size} modelos disponibles en Groq."
                },
                onFailure = { err ->
                    _modelsFetchStatus.value = "Error Groq: ${err.message}"
                }
            )
        }
    }

    fun refreshLiveTicker() {
        viewModelScope.launch {
            val snap = liveClient.fetchFullTickerSnapshot(_settings.value.city)
            _ticker.value = snap
            if (snap.weather.alertLevel == WeatherAlertLevel.RED && _emotion.value == CachoEmotion.NEUTRO) {
                _emotion.value = CachoEmotion.ALERTA
            }
        }
    }

    fun refreshNanoStatus() {
        viewModelScope.launch {
            _nanoStatus.value = brain.checkGeminiNanoStatus()
        }
    }

    fun downloadGeminiNano() {
        viewModelScope.launch {
            _emotion.value = CachoEmotion.CONCENTRADO
            brain.triggerGeminiNanoDownload { progressMsg ->
                _nanoStatus.value = NanoAvailability(1, progressMsg, progressMsg.contains("listo"), true)
            }
            _nanoStatus.value = brain.checkGeminiNanoStatus()
            _emotion.value = CachoEmotion.NEUTRO
        }
    }

    fun sendMessage(rawText: String) {
        val text = rawText.trim()
        val docText = _pendingDocText.value
        val docName = _pendingDocName.value
        if (text.isBlank() && docText.isNullOrBlank()) return
        if (_isSending.value) return

        val displayMsg = if (text.isNotBlank()) text else "Analizá el documento escaneado ($docName)."
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            isUser = true,
            text = displayMsg,
            timestamp = nowTime(),
            attachmentLabel = docName
        )
        _messages.value = _messages.value + userMsg
        _pendingDocName.value = null
        _pendingDocText.value = null
        _isSending.value = true
        _emotion.value = CachoEmotion.PENSANDO

        viewModelScope.launch {
            brain.generateReply(displayMsg, docText, _ticker.value).collect { (rawReply, engineLabel) ->
                val (parsedEmo, cleanReply) = extractEmotionAndClean(rawReply)
                _emotion.value = parsedEmo
                val needsGeminiBtn = engineLabel.contains("App Gemini") || engineLabel.contains("Fallback") || engineLabel.contains("Error")
                val botMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    isUser = false,
                    text = cleanReply,
                    timestamp = nowTime(),
                    engineUsed = engineLabel,
                    showGeminiHandoff = needsGeminiBtn
                )
                _messages.value = _messages.value + botMsg
                storage.appendTurnToRag(displayMsg, cleanReply)
                _ragCount.value = storage.ragCount()
                _isSending.value = false

                if (_settings.value.autoSpeak) {
                    speakText(cleanReply)
                }
            }
        }
    }

    fun openPromptInGeminiApp(prompt: String) {
        brain.openInPhoneGeminiApp(prompt)
    }

    private suspend fun buildHighResPdfFromFiles(pageFiles: List<File>): Uri? = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val outFile = File(app.cacheDir, "Cachito_Escaneo_HD.pdf")
        if (pageFiles.isEmpty()) return@withContext null
        val pdfDoc = PdfDocument()
        try {
            var pageNum = 1
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            for (file in pageFiles) {
                if (!file.exists()) continue
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val maxDim = max(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (maxDim / sample > 2480) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: continue
                val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, pageNum++).create()
                val page = pdfDoc.startPage(pageInfo)
                page.canvas.drawBitmap(bmp, null, Rect(0, 0, bmp.width, bmp.height), paint)
                pdfDoc.finishPage(page)
                bmp.recycle()
            }
            if (pageNum > 1) {
                FileOutputStream(outFile).use { pdfDoc.writeTo(it) }
                pdfDoc.close()
                return@withContext FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", outFile)
            }
        } catch (_: Exception) {
        } finally {
            try { pdfDoc.close() } catch (_: Exception) {}
        }
        null
    }

    fun handleCameraXCapturedPages(pageFiles: List<File>, sendToCachito: Boolean) {
        if (pageFiles.isEmpty()) return
        viewModelScope.launch {
            _emotion.value = CachoEmotion.CONCENTRADO
            _lastScannedPdfUri.value = buildHighResPdfFromFiles(pageFiles)
            val uris = pageFiles.map { Uri.fromFile(it) }
            val extracted = visionHelper.extractTextFromImages(uris)
            _pendingDocName.value = "Escaneo_HD_${pageFiles.size}p.pdf"
            _pendingDocText.value = extracted.ifBlank { null }
            _emotion.value = CachoEmotion.ALEGRE
            if (sendToCachito && extracted.isNotBlank()) {
                sendMessage("Leé y resumí los datos clave de este documento escaneado.")
            }
        }
    }

    fun clearPendingAttachment() {
        _pendingDocName.value = null
        _pendingDocText.value = null
        _lastScannedPdfUri.value = null
    }

    fun saveReferenceObject(label: String, bitmap: Bitmap) {
        val cleanName = label.trim().ifBlank { "Objeto" }
        val file = File(storage.referenceObjectsDir, "${cleanName.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ _-]"), "_")}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        _referenceObjects.value = loadReferenceFiles()
    }

    fun deleteReferenceObject(label: String) {
        _referenceObjects.value[label]?.delete()
        _referenceObjects.value = loadReferenceFiles()
    }

    suspend fun analyzeLiveFrame(bitmap: Bitmap): List<DetectedTarget> {
        val hits = visionHelper.detectAndMatchObjects(bitmap, _referenceObjects.value)
        _detectedTargets.value = hits
        return hits
    }

    private fun loadReferenceFiles(): Map<String, File> {
        val dir = storage.referenceObjectsDir
        if (!dir.exists()) return emptyMap()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg", "png") }
            ?.associate { it.nameWithoutExtension to it }
            ?: emptyMap()
    }

    fun clearRag() {
        storage.clearAllRag()
        _ragCount.value = 0
        _emotion.value = CachoEmotion.ALEGRE
    }

    fun saveVerifiedSolution(title: String, problem: String, solution: String) {
        storage.saveVerifiedSolution(title, problem, solution)
        _ragCount.value = storage.ragCount()
    }

    fun updateSettings(newSettings: AppSettings) {
        storage.saveSettings(newSettings)
        _settings.value = newSettings
        refreshLocalModelState()
        refreshLiveTicker()
        refreshNanoStatus()
    }

    fun toggleAutoSpeak() {
        val updated = _settings.value.copy(autoSpeak = !_settings.value.autoSpeak)
        updateSettings(updated)
        if (!updated.autoSpeak) tts?.stop()
    }

    fun speakText(text: String) {
        if (!ttsReady) return
        val clean = text.replace(Regex("[*#`>]"), "")
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun addFinanceMovement(kind: String, amount: Double, currency: String, category: String, description: String) {
        if (amount <= 0.0) return
        val book = _financeBook.value
        val blueSell = _ticker.value.dollars.firstOrNull { it.name == "Blue" }?.sell ?: book.lastUsdRate
        val newMov = FinanceMovement(
            id = UUID.randomUUID().toString(),
            kind = kind,
            amount = amount,
            currency = currency,
            category = category.ifBlank { "General" },
            description = description.trim(),
            date = LocalDate.now().toString()
        )
        val updated = book.copy(
            lastUsdRate = if (blueSell > 0) blueSell else book.lastUsdRate,
            movements = listOf(newMov) + book.movements
        )
        storage.saveFinanceBook(updated)
        _financeBook.value = updated
    }

    fun deleteFinanceMovement(id: String) {
        val book = _financeBook.value
        val updated = book.copy(movements = book.movements.filterNot { it.id == id })
        storage.saveFinanceBook(updated)
        _financeBook.value = updated
    }

    fun addFinanceGoal(name: String, targetAmount: Double, currency: String, savedAmount: Double) {
        if (name.isBlank() || targetAmount <= 0.0) return
        val book = _financeBook.value
        val newGoal = FinanceGoal(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            targetAmount = targetAmount,
            currency = currency,
            savedAmount = savedAmount.coerceAtLeast(0.0),
            createdAt = LocalDate.now().toString()
        )
        val updated = book.copy(goals = book.goals + newGoal)
        storage.saveFinanceBook(updated)
        _financeBook.value = updated
    }

    fun addProgressToGoal(goalId: String, delta: Double) {
        val book = _financeBook.value
        val updatedGoals = book.goals.map { g ->
            if (g.id == goalId) g.copy(savedAmount = (g.savedAmount + delta).coerceAtLeast(0.0)) else g
        }
        val updated = book.copy(goals = updatedGoals)
        storage.saveFinanceBook(updated)
        _financeBook.value = updated
    }

    private fun extractEmotionAndClean(raw: String): Pair<CachoEmotion, String> {
        val rx = Regex("\\[EMOTION\\s*:\\s*([a-zA-Z]+)\\s*\\]", RegexOption.IGNORE_CASE)
        val match = rx.find(raw)
        val emo = if (match != null) CachoEmotion.fromId(match.groupValues[1]) else CachoEmotion.NEUTRO
        val clean = raw.replace(rx, "").trim()
        return emo to clean
    }

    private fun nowTime(): String =
        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

    override fun onCleared() {
        brain.releaseLocalLlm()
        tts?.stop()
        tts?.shutdown()
        super.onCleared()
    }
}
