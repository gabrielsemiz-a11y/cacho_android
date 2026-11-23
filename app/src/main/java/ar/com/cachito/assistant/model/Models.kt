package ar.com.cachito.assistant.model

enum class CachoEmotion(val id: String, val label: String) {
    NEUTRO("neutro", "Listo para ayudar"),
    PENSANDO("pensando", "Pensando..."),
    ALEGRE("alegre", "Todo en orden"),
    CONCENTRADO("concentrado", "Analizando en detalle"),
    DUDA("duda", "Revisando opciones"),
    ALERTA("alerta", "Atención")
    ;

    companion object {
        fun fromId(raw: String): CachoEmotion =
            entries.firstOrNull { it.id.equals(raw.trim(), ignoreCase = true) } ?: NEUTRO
    }
}

enum class AiEngineMode(val id: String, val label: String) {
    LOCAL_OFFLINE("local_offline", "IA Local Offline (Qwen 2.5 / Gemma)"),
    OPENROUTER("openrouter", "OpenRouter (Nemotron Gratis / Claude)"),
    GOOGLE_AI_STUDIO("google_ai", "Google AI Studio (API Key + Search)"),
    GROQ_API("groq", "Groq Cloud Gratis (API Key)"),
    GEMINI_APP_INTENT("gemini_intent", "App Gemini del teléfono (0 API Key)"),
    GEMINI_NANO("gemini_nano", "Gemini Nano Offline (Solo AICore)")
}

enum class WeatherAlertLevel {
    NONE, YELLOW, RED
}

data class WeatherInfo(
    val city: String = "Mendoza",
    val tempCurrent: Double = 0.0,
    val tempMax: Double = 0.0,
    val tempMin: Double = 0.0,
    val weatherCode: Int = 0,
    val conditionText: String = "Cargando clima...",
    val windKmh: Double = 0.0,
    val precipitationProb: Int = 0,
    val alertLevel: WeatherAlertLevel = WeatherAlertLevel.NONE,
    val alertDescription: String = "",
    val updatedAt: String = ""
)

data class DollarQuote(
    val name: String,
    val buy: Double,
    val sell: Double
)

data class MarketQuote(
    val symbol: String,
    val label: String,
    val price: Double,
    val changePercent: Double
)

data class TickerSnapshot(
    val weather: WeatherInfo = WeatherInfo(),
    val dollars: List<DollarQuote> = emptyList(),
    val markets: List<MarketQuote> = emptyList(),
    val marketOpen: Boolean = false,
    val lastUpdated: String = ""
)

data class ChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val timestamp: String,
    val engineUsed: String = "",
    val attachmentLabel: String? = null,
    val showGeminiHandoff: Boolean = false
)

data class RagTurn(
    val user: String,
    val assistant: String,
    val timestamp: String
)

data class RagSolution(
    val id: String,
    val title: String,
    val problem: String,
    val solution: String,
    val tags: List<String> = emptyList(),
    val createdAt: String = ""
)

data class FinanceMovement(
    val id: String,
    val kind: String,
    val amount: Double,
    val currency: String,
    val category: String,
    val description: String,
    val date: String
)

data class FinanceGoal(
    val id: String,
    val name: String,
    val targetAmount: Double,
    val currency: String,
    val savedAmount: Double,
    val createdAt: String
)

data class FinanceBook(
    val lastUsdRate: Double = 1200.0,
    val movements: List<FinanceMovement> = emptyList(),
    val goals: List<FinanceGoal> = emptyList()
)

data class AppSettings(
    val preferredEngine: AiEngineMode = AiEngineMode.OPENROUTER,
    val personalityPreset: String = "abuelito",
    val humorLevel: Int = 5,
    val localModelUrl: String = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
    val localModelName: String = "Qwen2.5-1.5B-Instruct.task",
    val openRouterApiKey: String = "",
    val openRouterModel: String = "nvidia/nemotron-3.5-lightning:free",
    val groqApiKey: String = "",
    val groqModel: String = "llama-3.3-70b-versatile",
    val googleApiKey: String = "",
    val googleModel: String = "gemini-3.5-flash-lite",
    val autoSpeak: Boolean = false,
    val city: String = "Mendoza"
)

data class DetectedTarget(
    val label: String,
    val confidence: Float,
    val leftRatio: Float,
    val topRatio: Float,
    val rightRatio: Float,
    val bottomRatio: Float,
    val isReferenceMatch: Boolean = false
)
