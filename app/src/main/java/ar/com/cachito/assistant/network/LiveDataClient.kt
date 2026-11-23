package ar.com.cachito.assistant.network

import ar.com.cachito.assistant.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class LiveDataClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val argZone = ZoneId.of("America/Argentina/Mendoza")

    private val cityCoords = mapOf(
        "mendoza" to Triple(-32.8895, -68.8458, "Mendoza"),
        "godoy cruz" to Triple(-32.9287, -68.8442, "Godoy Cruz"),
        "guaymallen" to Triple(-32.8919, -68.7986, "Guaymallén"),
        "buenos aires" to Triple(-34.6037, -58.3816, "Buenos Aires"),
        "cordoba" to Triple(-31.4201, -64.1888, "Córdoba")
    )

    private val trackedTickers = listOf(
        "^GSPC" to "S&P 500",
        "QQQ" to "QQQ",
        "SMH" to "SMH",
        "NVDA" to "NVDA",
        "GOOGL" to "GOOGL",
        "AAPL" to "AAPL",
        "MSFT" to "MSFT",
        "META" to "META",
        "AMZN" to "AMZN",
        "MELI" to "MELI",
        "AMD" to "AMD",
        "O" to "O",
        "SHEL" to "SHEL",
        "CL=F" to "WTI"
    )

    fun isMarketHoursArgentina(): Boolean {
        val now = ZonedDateTime.now(argZone)
        val day = now.dayOfWeek
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false
        val t = now.toLocalTime()
        return !t.isBefore(LocalTime.of(9, 0)) && !t.isAfter(LocalTime.of(17, 0))
    }

    suspend fun fetchFullTickerSnapshot(city: String = "Mendoza"): TickerSnapshot = withContext(Dispatchers.IO) {
        val marketOpen = isMarketHoursArgentina()
        val weatherJob = async { fetchWeather(city) }
        val dollarJob = async { fetchDollars() }
        val marketsJob = async { if (marketOpen) fetchMarkets() else emptyList() }

        val timeNow = ZonedDateTime.now(argZone).format(DateTimeFormatter.ofPattern("HH:mm"))
        TickerSnapshot(
            weather = weatherJob.await(),
            dollars = dollarJob.await(),
            markets = marketsJob.await(),
            marketOpen = marketOpen,
            lastUpdated = timeNow
        )
    }

    fun fetchWeather(city: String = "Mendoza"): WeatherInfo {
        val key = city.trim().lowercase()
        val (lat, lon, label) = cityCoords[key] ?: Triple(-32.8895, -68.8458, city.ifBlank { "Mendoza" })
        val timeNow = ZonedDateTime.now(argZone).format(DateTimeFormatter.ofPattern("HH:mm"))

        try {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_gusts_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_gusts_10m_max" +
                "&timezone=America%2FArgentina%2FMendoza&forecast_days=1"

            val body = getString(url)
            val root = JSONObject(body)
            val cur = root.getJSONObject("current")
            val daily = root.getJSONObject("daily")

            val tempCur = cur.optDouble("temperature_2m", 0.0)
            val code = cur.optInt("weather_code", 0)
            val wind = cur.optDouble("wind_speed_10m", 0.0)
            val gusts = cur.optDouble("wind_gusts_10m", wind)
            val precip = cur.optDouble("precipitation", 0.0)

            val tMax = daily.optJSONArray("temperature_2m_max")?.optDouble(0, tempCur) ?: tempCur
            val tMin = daily.optJSONArray("temperature_2m_min")?.optDouble(0, tempCur) ?: tempCur
            val pProb = daily.optJSONArray("precipitation_probability_max")?.optInt(0, 0) ?: 0
            val maxGusts = daily.optJSONArray("wind_gusts_10m_max")?.optDouble(0, gusts) ?: gusts

            val (alertLevel, alertDesc) = evaluateWeatherAlert(code, tMax, tMin, maxGusts, precip, pProb)

            return WeatherInfo(
                city = label,
                tempCurrent = tempCur,
                tempMax = tMax,
                tempMin = tMin,
                weatherCode = code,
                conditionText = wmoDescription(code),
                windKmh = wind,
                precipitationProb = pProb,
                alertLevel = alertLevel,
                alertDescription = alertDesc,
                updatedAt = timeNow
            )
        } catch (_: Exception) {
            return try {
                val enc = URLEncoder.encode(label, "UTF-8")
                val raw = getString("https://wttr.in/$enc?format=j1")
                val j = JSONObject(raw)
                val cur = j.getJSONArray("current_condition").getJSONObject(0)
                val w0 = j.getJSONArray("weather").getJSONObject(0)
                val tempCur = cur.optString("temp_C", "0").toDoubleOrNull() ?: 0.0
                val tMax = w0.optString("maxtempC", "$tempCur").toDoubleOrNull() ?: tempCur
                val tMin = w0.optString("mintempC", "$tempCur").toDoubleOrNull() ?: tempCur
                val wind = cur.optString("windspeedKmph", "0").toDoubleOrNull() ?: 0.0
                val desc = cur.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value") ?: "Despejado"
                WeatherInfo(
                    city = label,
                    tempCurrent = tempCur,
                    tempMax = tMax,
                    tempMin = tMin,
                    weatherCode = 1,
                    conditionText = desc,
                    windKmh = wind,
                    precipitationProb = 0,
                    alertLevel = if (wind >= 55.0 || tMax >= 38.0) WeatherAlertLevel.YELLOW else WeatherAlertLevel.NONE,
                    alertDescription = if (wind >= 55.0) "Ráfagas fuertes" else "",
                    updatedAt = timeNow
                )
            } catch (_: Exception) {
                WeatherInfo(city = label, conditionText = "Sin conexión clima", updatedAt = timeNow)
            }
        }
    }

    private fun evaluateWeatherAlert(
        code: Int,
        tMax: Double,
        tMin: Double,
        maxGusts: Double,
        precip: Double,
        pProb: Int
    ): Pair<WeatherAlertLevel, String> {
        // Alerta Roja: granizo fuerte, tormenta severa, Zonda/ráfagas >= 80 km/h o calor extremo >= 41°C
        if (code in listOf(82, 96, 99) || maxGusts >= 80.0 || tMax >= 41.0 || precip >= 25.0) {
            val reason = when {
                code in listOf(96, 99) -> "Alerta Roja: Tormenta con granizo"
                maxGusts >= 80.0 -> "Alerta Roja: Ráfagas intensas (${maxGusts.toInt()} km/h)"
                tMax >= 41.0 -> "Alerta Roja: Calor extremo (${tMax.toInt()}°C)"
                else -> "Alerta Roja: Precipitaciones intensas"
            }
            return WeatherAlertLevel.RED to reason
        }
        // Alerta Amarilla: tormenta eléctrica, ráfagas >= 50 km/h (posible Zonda), calor >= 36°C o helada <= -2°C
        if (code in listOf(65, 81, 95) || maxGusts >= 50.0 || tMax >= 36.0 || tMin <= -2.0 || pProb >= 75) {
            val reason = when {
                code == 95 -> "Alerta Amarilla: Tormenta eléctrica"
                maxGusts >= 50.0 -> "Alerta Amarilla: Viento/Zonda (${maxGusts.toInt()} km/h)"
                tMax >= 36.0 -> "Alerta Amarilla: Temperatura elevada (${tMax.toInt()}°C)"
                tMin <= -2.0 -> "Alerta Amarilla: Heladas (${tMin.toInt()}°C)"
                else -> "Alerta Amarilla: Probabilidad alta de lluvia ($pProb%)"
            }
            return WeatherAlertLevel.YELLOW to reason
        }
        return WeatherAlertLevel.NONE to ""
    }

    fun fetchDollars(): List<DollarQuote> {
        return try {
            val arr = JSONArray(getString("https://dolarapi.com/v1/dolares"))
            val wanted = listOf("blue", "oficial", "bolsa", "contadoconliqui")
            val labels = mapOf(
                "blue" to "Blue",
                "oficial" to "Oficial",
                "bolsa" to "MEP",
                "contadoconliqui" to "CCL"
            )
            val map = mutableMapOf<String, DollarQuote>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val casa = o.optString("casa", "").lowercase()
                if (casa in wanted) {
                    map[casa] = DollarQuote(
                        name = labels[casa] ?: o.optString("nombre", casa),
                        buy = o.optDouble("compra", 0.0),
                        sell = o.optDouble("venta", 0.0)
                    )
                }
            }
            wanted.mapNotNull { map[it] }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun fetchMarkets(): List<MarketQuote> {
        val out = mutableListOf<MarketQuote>()
        for ((symbol, label) in trackedTickers) {
            try {
                val enc = URLEncoder.encode(symbol, "UTF-8")
                val url = "https://query1.finance.yahoo.com/v8/finance/chart/$enc?range=1d&interval=1d"
                val root = JSONObject(getString(url))
                val res = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
                val meta = res.getJSONObject("meta")
                val price = meta.optDouble("regularMarketPrice", 0.0)
                val prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", price))
                if (price > 0.0 && prev > 0.0) {
                    val pct = ((price - prev) / prev) * 100.0
                    out.add(MarketQuote(symbol = symbol, label = label, price = price, changePercent = pct))
                }
            } catch (_: Exception) {
                // Continuar con el resto de tickers
            }
        }
        return out
    }

    private fun getString(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) Cachito/2.0")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    private fun wmoDescription(code: Int): String = when (code) {
        0 -> "Despejado"
        1 -> "Mayormente despejado"
        2 -> "Parcialmente nublado"
        3 -> "Nublado"
        45, 48 -> "Niebla"
        51, 53, 55 -> "Llovizna"
        61, 63, 65 -> "Lluvia"
        71, 73, 75 -> "Nieve"
        80, 81, 82 -> "Chaparrones"
        95 -> "Tormenta eléctrica"
        96, 99 -> "Tormenta con granizo"
        else -> "Variable"
    }
}
