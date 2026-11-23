package ar.com.cachito.assistant.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ar.com.cachito.assistant.ai.CachitoBrain
import ar.com.cachito.assistant.ai.NanoAvailability
import ar.com.cachito.assistant.data.LocalStorage
import ar.com.cachito.assistant.model.*
import ar.com.cachito.assistant.network.LiveDataClient
import ar.com.cachito.assistant.vision.VisionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class CachitoViewModel(app: Application) : AndroidViewModel(app) {

    val storage = LocalStorage(app)
    val brain = CachitoBrain(app, storage)
    val liveClient = LiveDataClient()
    val visionHelper = VisionHelper(app)

    private val _emotion = MutableStateFlow(CachoEmotion.NEUTRO)
    val emotion: StateFlow<CachoEmotion> = _emotion.asStateFlow()

    private val _ticker = MutableStateFlow(TickerSnapshot())
    val ticker: StateFlow<TickerSnapshot> = _ticker.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                id = "welcome",
                isUser = false,
                text = "¡Buenas! Soy Cachito v2.1. Listo para darte una mano con IA en el celu, escanear documentos a PDF, buscar tus cosas con la cámara o llevar las cuentas.",
                timestamp = nowTime(),
                engineUsed = "Cachito v2.1"
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _settings = MutableStateFlow(storage.loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _nanoStatus = MutableStateFlow(NanoAvailability(-1, "Verificando AICore...", false))
    val nanoStatus: StateFlow<NanoAvailability> = _nanoStatus.asStateFlow()

    private val _availableGoogleModels = MutableStateFlow<List<String>>(emptyList())
    val availableGoogleModels: StateFlow<List<String>> = _availableGoogleModels.asStateFlow()

    private val _availableGroqModels = MutableStateFlow<List<String>>(emptyList())
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

    private val _detectedTargets = MutableStateFlow<List<DetectedTarget>>(emptyList())
    val detectedTargets: StateFlow<List<DetectedTarget>> = _detectedTargets.asStateFlow()

    private val _referenceObjects = MutableStateFlow(loadReferenceFiles())
    val referenceObjects = _referenceObjects.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("es", "AR"))
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
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
                _nanoStatus.value = NanoAvailability(1, progressMsg, progressMsg.contains("listo"))
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

    fun handleScannedDocument(pdfUri: Uri?, pageImageUris: List<Uri>, sendToCachito: Boolean) {
        _lastScannedPdfUri.value = pdfUri
        if (pageImageUris.isEmpty()) return
        viewModelScope.launch {
            _emotion.value = CachoEmotion.CONCENTRADO
            val extracted = visionHelper.extractTextFromImages(pageImageUris)
            if (extracted.isNotBlank()) {
                _pendingDocName.value = "Escaneo_PDF_${pageImageUris.size}p.pdf"
                _pendingDocText.value = extracted
                _emotion.value = CachoEmotion.ALEGRE
                if (sendToCachito) {
                    sendMessage("Resumí y revisá los puntos clave de este documento escaneado.")
                }
            } else {
                _emotion.value = CachoEmotion.DUDA
            }
        }
    }

    fun clearPendingAttachment() {
        _pendingDocName.value = null
        _pendingDocText.value = null
    }

    fun saveReferenceObject(label: String, bitmap: Bitmap) {
        val cleanName = label.trim().ifBlank { "Objeto" }
        val file = File(storage.referenceObjectsDir, "${cleanName.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ _-]"), "_")}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        _referenceObjects.value = loadReferenceFiles()
    }

    fun deleteReferenceObject(label: String) {
        _referenceObjects.value[label]?.delete()
        _referenceObjects.value = loadReferenceFiles()
    }

    fun analyzeCameraImageForObjects(bitmap: Bitmap) {
        viewModelScope.launch {
            _emotion.value = CachoEmotion.CONCENTRADO
            val hits = visionHelper.detectAndMatchObjects(bitmap, _referenceObjects.value)
            _detectedTargets.value = hits
            _emotion.value = if (hits.any { it.isReferenceMatch }) CachoEmotion.ALEGRE else CachoEmotion.NEUTRO
        }
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
        tts?.stop()
        tts?.shutdown()
        super.onCleared()
    }
}
