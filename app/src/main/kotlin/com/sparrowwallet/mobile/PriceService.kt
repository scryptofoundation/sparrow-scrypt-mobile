package com.sparrowwallet.mobile

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.Json
import com.sparrowwallet.mobile.crypto.Coin
import android.content.Context
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * USD prices per coin from CoinGecko (no API key), by the coin's CoinGecko id: the current
 * spot price (cached a few minutes) and historical daily closes (cached permanently per
 * date — they never change). Fetches are best-effort: on rate limits or network failure
 * callers get null and show a placeholder.
 */
object PriceService {
    private const val CURRENT_URL = "https://api.coingecko.com/api/v3/simple/price?ids=%s&vs_currencies=usd"
    private const val HISTORY_URL = "https://api.coingecko.com/api/v3/coins/%s/history?date=%s&localization=false"

    private val current = HashMap<Coin, Pair<Double, Long>>() // price, fetchedAtMs
    private val mutex = Mutex()

    suspend fun currentUsd(coin: Coin): Double? = mutex.withLock {
        current[coin]?.takeIf { System.currentTimeMillis() - it.second < 5 * 60_000 }?.first
            ?: fetch(CURRENT_URL.format(coin.coingeckoId))?.let { parseSpot(it, coin) }
                ?.also { current[coin] = it to System.currentTimeMillis() }
    }

    internal fun parseSpot(json: String, coin: Coin): Double? = runCatching {
        Json.parseToJsonElement(json).jsonObject[coin.coingeckoId]?.jsonObject?.get("usd")?.jsonPrimitive?.double
    }.getOrNull()

    /** USD price of [coin] on the day of [unixSeconds], cached permanently. */
    suspend fun usdOn(context: Context, coin: Coin, unixSeconds: Long): Double? {
        val format = SimpleDateFormat("dd-MM-yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val day = format.format(Date(unixSeconds * 1000))
        val prefs = context.getSharedPreferences("prices", Context.MODE_PRIVATE)
        // Litecoin keeps the keys cached before other coins existed
        val key = if(coin == Coin.LITECOIN) "usd.$day" else "usd.${coin.id}.$day"
        if(prefs.contains(key)) {
            return prefs.getFloat(key, 0f).toDouble().takeIf { it > 0 }
        }
        val price = mutex.withLock {
            fetch(HISTORY_URL.format(coin.coingeckoId, day))?.let {
                runCatching {
                    JSONObject(it).getJSONObject("market_data").getJSONObject("current_price").getDouble("usd")
                }.getOrNull()
            }
        }
        if(price != null) {
            prefs.edit().putFloat(key, price.toFloat()).apply()
        }
        return price
    }

    internal suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        if(NetworkPolicy.blocked) return@withContext null // cached prices still show
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            // no pooled keep-alive socket: one left idle when air-gapped mode turns on
            // would still put its close on the wire afterwards
            connection.setRequestProperty("Connection", "close")
            try {
                if(connection.responseCode == 200) connection.inputStream.bufferedReader().readText() else null
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}
