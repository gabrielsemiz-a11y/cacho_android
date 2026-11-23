package ar.com.cachito.assistant.network

import ar.com.cachito.assistant.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class LiveDataClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun fetchFullTickerSnapshot(city: String): TickerSnapshot = withContext(Dispatchers.IO) {
        val weather = fetchWeather(city)
        val dollars = fetchDollars()
        val now = LocalDateTime.now()
        val isMarketOpen = now.dayOfWeek !in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) && now.hour in 9..17
        TickerSnapshot(
            weather = weather,
            dollars = dollars,
            markets = emptyList(),
            marketOpen = isMarketOpen,
            lastUpdated = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        )
    }

    private fun fetchWeather(city: String): WeatherInfo {
        return try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=-32.8895&longitude=-68.8458&current=temperature_2m,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=America/Argentina/Mendoza"
            http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return WeatherInfo(city = city)
                val j = JSONObject(resp.body?.string() ?: "{}")
                val cur = j.optJSONObject("current") ?: JSONObject()
                val daily = j.optJSONObject("daily") ?: JSONObject()
                val temp = cur.optDouble("temperature_2m", 0.0)
                val code = cur.optInt("weather_code", 0)
                val wind = cur.optDouble("wind_speed_10m", 0.0)
                val maxArr = daily.optJSONArray("temperature_2m_max")
                val minArr = daily.optJSONArray("temperature_2m_min")
                val precArr = daily.optJSONArray("precipitation_probability_max")
                WeatherInfo(
                    city = city.ifBlank { "Mendoza" },
                    tempCurrent = temp,
                    tempMax = maxArr?.optDouble(0, temp) ?: temp,
                    tempMin = minArr?.optDouble(0, temp) ?: temp,
                    weatherCode = code,
                    conditionText = if (code >= 51) "Lluvia/Tormenta" else if (code >= 2) "Nublado" else "Despejado",
                    windKmh = wind,
                    precipitationProb = precArr?.optInt(0, 0) ?: 0,
                    alertLevel = if (wind >= 50.0) WeatherAlertLevel.RED else if (wind >= 35.0) WeatherAlertLevel.YELLOW else WeatherAlertLevel.NONE,
                    alertDescription = if (wind >= 35.0) "Viento fuerte (${wind.toInt()} km/h)" else ""
                )
            }
        } catch (_: Exception) {
            WeatherInfo(city = city)
        }
    }

    private fun fetchDollars(): List<DollarQuote> {
        return try {
            http.newCall(Request.Builder().url("https://dolarapi.com/v1/dolares").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val arr = JSONArray(resp.body?.string() ?: "[]")
                val wanted = mapOf("oficial" to "Oficial", "blue" to "Blue", "bolsa" to "MEP", "contadoconliqui" to "CCL")
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val casa = o.optString("casa").lowercase()
                        val label = wanted[casa] ?: continue
                        add(DollarQuote(label, o.optDouble("compra", 0.0), o.optDouble("venta", 0.0)))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}