package ar.com.cachito.assistant.data

import android.content.Context
import ar.com.cachito.assistant.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class LocalStorage(private val context: Context) {
    private val dataDir = File(context.filesDir, "cachito_data").apply { mkdirs() }
    val modelsDir = File(context.filesDir, "cachito_models").apply { mkdirs() }
    private val sessionFile = File(dataDir, "sesion_actual.json")
    private val chatLogFile = File(dataDir, "memoria_charlas.jsonl")
    private val solutionsFile = File(dataDir, "soluciones_verificadas.json")
    private val financeFile = File(dataDir, "finanzas.json")
    private val settingsFile = File(dataDir, "config.json")
    private val secretsFile = File(dataDir, "secrets_enc.json")
    val referenceObjectsDir = File(dataDir, "objetos_referencia").apply { mkdirs() }

    private val stopwords = setOf(
        "de", "la", "que", "el", "en", "y", "a", "los", "del", "se", "las",
        "por", "un", "para", "con", "no", "una", "su", "al", "lo", "como",
        "mas", "pero", "sus", "le", "ya", "o", "este", "si", "porque", "esta",
        "entre", "cuando", "muy", "sin", "sobre", "tambien", "me", "hasta",
        "hay", "donde", "quien", "desde", "todo", "nos", "durante", "todos",
        "uno", "les", "ni", "contra", "otros", "ese", "eso", "ante", "ellos",
        "e", "esto", "mi", "antes", "algunos", "unos", "yo", "otro", "otras"
    )

    fun getLocalModelFile(fileName: String): File {
        val clean = fileName.trim().ifBlank { "Qwen2.5-1.5B-Instruct.task" }
        return File(modelsDir, clean)
    }

    fun loadSettings(): AppSettings {
        val defaults = AppSettings()
        if (!settingsFile.exists() && !secretsFile.exists()) return defaults
        return try {
            val obj = if (settingsFile.exists()) JSONObject(settingsFile.readText(Charsets.UTF_8)) else JSONObject()
            val sec = if (secretsFile.exists()) JSONObject(secretsFile.readText(Charsets.UTF_8)) else JSONObject()

            val engId = obj.optString("preferredEngine", defaults.preferredEngine.id)
            val eng = AiEngineMode.entries.firstOrNull { it.id == engId } ?: defaults.preferredEngine

            var orKey = SecureKeyStore.decrypt(sec.optString("openRouterApiKeyEnc", ""))
            var groqKey = SecureKeyStore.decrypt(sec.optString("groqApiKeyEnc", ""))
            var googleKey = SecureKeyStore.decrypt(sec.optString("googleApiKeyEnc", ""))

            val legacyOr = obj.optString("openRouterApiKey", "")
            val legacyGroq = obj.optString("groqApiKey", "")
            val legacyGoogle = obj.optString("googleApiKey", "")
            var needsMigration = false

            if (orKey.isBlank() && legacyOr.isNotBlank()) { orKey = legacyOr; needsMigration = true }
            if (groqKey.isBlank() && legacyGroq.isNotBlank()) { groqKey = legacyGroq; needsMigration = true }
            if (googleKey.isBlank() && legacyGoogle.isNotBlank()) { googleKey = legacyGoogle; needsMigration = true }

            val loaded = AppSettings(
                preferredEngine = eng,
                personalityPreset = obj.optString("personalityPreset", defaults.personalityPreset),
                humorLevel = obj.optInt("humorLevel", defaults.humorLevel).coerceIn(0, 10),
                localModelUrl = obj.optString("localModelUrl", defaults.localModelUrl),
                localModelName = obj.optString("localModelName", defaults.localModelName),
                openRouterApiKey = orKey,
                openRouterModel = obj.optString("openRouterModel", defaults.openRouterModel),
                groqApiKey = groqKey,
                groqModel = obj.optString("groqModel", defaults.groqModel),
                googleApiKey = googleKey,
                googleModel = obj.optString("googleModel", defaults.googleModel),
                autoSpeak = obj.optBoolean("autoSpeak", defaults.autoSpeak),
                city = obj.optString("city", defaults.city)
            )
            if (needsMigration) {
                saveSettings(loaded)
            }
            loaded
        } catch (_: Exception) {
            defaults
        }
    }

    fun saveSettings(s: AppSettings) {
        val obj = JSONObject().apply {
            put("preferredEngine", s.preferredEngine.id)
            put("personalityPreset", s.personalityPreset)
            put("humorLevel", s.humorLevel)
            put("localModelUrl", s.localModelUrl.trim())
            put("localModelName", s.localModelName.trim())
            put("openRouterModel", s.openRouterModel.trim())
            put("groqModel", s.groqModel.trim())
            put("googleModel", s.googleModel.trim())
            put("autoSpeak", s.autoSpeak)
            put("city", s.city.trim())
        }
        settingsFile.writeText(obj.toString(2), Charsets.UTF_8)

        val sec = JSONObject().apply {
            put("openRouterApiKeyEnc", SecureKeyStore.encrypt(s.openRouterApiKey.trim()))
            put("groqApiKeyEnc", SecureKeyStore.encrypt(s.groqApiKey.trim()))
            put("googleApiKeyEnc", SecureKeyStore.encrypt(s.googleApiKey.trim()))
        }
        secretsFile.writeText(sec.toString(2), Charsets.UTF_8)
    }

    fun getSessionTurns(): Pair<String, List<RagTurn>> {
        if (!sessionFile.exists()) return "" to emptyList()
        return try {
            val obj = JSONObject(sessionFile.readText(Charsets.UTF_8))
            val summary = obj.optString("running_summary", "")
            val arr = obj.optJSONArray("turns") ?: JSONArray()
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    add(
                        RagTurn(
                            user = t.optString("user"),
                            assistant = t.optString("assistant"),
                            timestamp = t.optString("timestamp")
                        )
                    )
                }
            }
            summary to list
        } catch (_: Exception) {
            "" to emptyList()
        }
    }

    fun clearSessionOnly() {
        sessionFile.writeText("""{"running_summary":"","turns":[]}""", Charsets.UTF_8)
    }

    fun appendTurnToRag(userText: String, assistantText: String) {
        val now = Instant.now().toString()
        val (prevSummary, turns) = getSessionTurns()
        val updated = (turns + RagTurn(userText, assistantText, now)).takeLast(6)

        val obj = JSONObject().apply {
            put("running_summary", prevSummary)
            put("turns", JSONArray().apply {
                updated.forEach { t ->
                    put(JSONObject().apply {
                        put("user", t.user)
                        put("assistant", t.assistant)
                        put("timestamp", t.timestamp)
                    })
                }
            })
        }
        sessionFile.writeText(obj.toString(2), Charsets.UTF_8)

        val logLine = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("user", userText)
            put("assistant", assistantText)
            put("timestamp", now)
        }
        chatLogFile.appendText(logLine.toString() + "\n", Charsets.UTF_8)
    }

    fun getVerifiedSolutions(): List<RagSolution> {
        if (!solutionsFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(solutionsFile.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val tagsArr = o.optJSONArray("tags") ?: JSONArray()
                    val tags = (0 until tagsArr.length()).map { tagsArr.optString(it) }
                    add(
                        RagSolution(
                            id = o.optString("id"),
                            title = o.optString("title"),
                            problem = o.optString("problem"),
                            solution = o.optString("solution"),
                            tags = tags,
                            createdAt = o.optString("created_at")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveVerifiedSolution(title: String, problem: String, solution: String, tags: List<String> = emptyList()) {
        val current = getVerifiedSolutions().toMutableList()
        current.add(
            RagSolution(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                problem = problem.trim(),
                solution = solution.trim(),
                tags = tags,
                createdAt = LocalDate.now().toString()
            )
        )
        val arr = JSONArray().apply {
            current.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("problem", s.problem)
                    put("solution", s.solution)
                    put("tags", JSONArray(s.tags))
                    put("created_at", s.createdAt)
                })
            }
        }
        solutionsFile.writeText(arr.toString(2), Charsets.UTF_8)
    }

    fun searchRag(query: String, topK: Int = 3): List<String> {
        val qTokens = tokenize(query).toSet()
        if (qTokens.isEmpty()) return emptyList()

        val scored = mutableListOf<Pair<Double, String>>()
        getVerifiedSolutions().forEach { sol ->
            val docTokens = tokenize("${sol.title} ${sol.problem} ${sol.solution} ${sol.tags.joinToString(" ")}")
            val hits = docTokens.count { it in qTokens }
            if (hits > 0) {
                val score = (hits * 2.0) / (1.0 + docTokens.size * 0.1)
                scored.add(score to "[Solución guardada - ${sol.title}]: ${sol.solution.take(260)}")
            }
        }

        if (chatLogFile.exists()) {
            chatLogFile.readLines(Charsets.UTF_8).takeLast(120).forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    val o = JSONObject(line)
                    val u = o.optString("user")
                    val a = o.optString("assistant")
                    val docTokens = tokenize("$u $a")
                    val hits = docTokens.count { it in qTokens }
                    if (hits >= 2) {
                        val score = hits.toDouble() / (1.0 + docTokens.size * 0.12)
                        scored.add(score to "[Charla previa]: P: ${u.take(100)} -> R: ${a.take(180)}")
                    }
                } catch (_: Exception) {}
            }
        }

        return scored.sortedByDescending { it.first }.take(topK).map { it.second }
    }

    fun clearAllRag() {
        clearSessionOnly()
        chatLogFile.writeText("", Charsets.UTF_8)
        solutionsFile.writeText("[]", Charsets.UTF_8)
    }

    fun ragCount(): Int {
        val logLines = if (chatLogFile.exists()) chatLogFile.readLines().count { it.isNotBlank() } else 0
        return getVerifiedSolutions().size + logLines
    }

    private fun tokenize(text: String): List<String> {
        val norm = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return Regex("[a-z0-9_]{2,}").findAll(norm)
            .map { it.value }
            .filter { it !in stopwords }
            .toList()
    }

    fun loadFinanceBook(): FinanceBook {
        if (!financeFile.exists()) return FinanceBook()
        return try {
            val obj = JSONObject(financeFile.readText(Charsets.UTF_8))
            val rate = obj.optDouble("last_usd_rate", 1200.0)
            val mArr = obj.optJSONArray("movements") ?: JSONArray()
            val movements = buildList {
                for (i in 0 until mArr.length()) {
                    val m = mArr.getJSONObject(i)
                    add(
                        FinanceMovement(
                            id = m.optString("id"),
                            kind = m.optString("kind", "gasto"),
                            amount = m.optDouble("amount", 0.0),
                            currency = m.optString("currency", "ARS"),
                            category = m.optString("category", "General"),
                            description = m.optString("description", ""),
                            date = m.optString("date", LocalDate.now().toString())
                        )
                    )
                }
            }
            val gArr = obj.optJSONArray("goals") ?: JSONArray()
            val goals = buildList {
                for (i in 0 until gArr.length()) {
                    val g = gArr.getJSONObject(i)
                    add(
                        FinanceGoal(
                            id = g.optString("id"),
                            name = g.optString("name"),
                            targetAmount = g.optDouble("target_amount", 0.0),
                            currency = g.optString("currency", "USD"),
                            savedAmount = g.optDouble("saved_amount", 0.0),
                            createdAt = g.optString("created_at", LocalDate.now().toString())
                        )
                    )
                }
            }
            FinanceBook(lastUsdRate = rate, movements = movements, goals = goals)
        } catch (_: Exception) {
            FinanceBook()
        }
    }

    fun saveFinanceBook(book: FinanceBook) {
        val obj = JSONObject().apply {
            put("last_usd_rate", book.lastUsdRate)
            put("movements", JSONArray().apply {
                book.movements.forEach { m ->
                    put(JSONObject().apply {
                        put("id", m.id)
                        put("kind", m.kind)
                        put("amount", m.amount)
                        put("currency", m.currency)
                        put("category", m.category)
                        put("description", m.description)
                        put("date", m.date)
                    })
                }
            })
            put("goals", JSONArray().apply {
                book.goals.forEach { g ->
                    put(JSONObject().apply {
                        put("id", g.id)
                        put("name", g.name)
                        put("target_amount", g.targetAmount)
                        put("currency", g.currency)
                        put("saved_amount", g.savedAmount)
                        put("created_at", g.createdAt)
                    })
                }
            })
        }
        financeFile.writeText(obj.toString(2), Charsets.UTF_8)
    }

    fun buildFinancePromptContext(): String {
        val book = loadFinanceBook()
        if (book.movements.isEmpty() && book.goals.isEmpty()) return ""
        val ingArs = book.movements.filter { it.kind == "ingreso" && it.currency == "ARS" }.sumOf { it.amount }
        val gasArs = book.movements.filter { it.kind == "gasto" && it.currency == "ARS" }.sumOf { it.amount }
        val ingUsd = book.movements.filter { it.kind == "ingreso" && it.currency == "USD" }.sumOf { it.amount }
        val gasUsd = book.movements.filter { it.kind == "gasto" && it.currency == "USD" }.sumOf { it.amount }
        val goalsTxt = book.goals.joinToString("; ") {
            "${it.name}: ${it.savedAmount}/${it.targetAmount} ${it.currency}"
        }
        return "Resumen Cacho Contador (cotización ref $${book.lastUsdRate}): " +
            "Balance ARS ${"%.0f".format(ingArs - gasArs)} (Ing $${"%.0f".format(ingArs)} / Gas $${"%.0f".format(gasArs)}), " +
            "Balance USD ${"%.2f".format(ingUsd - gasUsd)}. " +
            (if (goalsTxt.isNotBlank()) "Objetivos: $goalsTxt." else "")
    }
}
