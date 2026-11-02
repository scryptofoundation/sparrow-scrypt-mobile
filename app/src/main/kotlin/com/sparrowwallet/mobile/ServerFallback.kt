package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.BackendKind
import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlinx.coroutines.CancellationException

/**
 * Public servers used when the user has not set one, per coin and network, from desktop's
 * PublicElectrumServer list (checked 2026-10-31). Electrum entries hold servers whose TLS
 * certificates verify against the system CAs; a coin's self-signed one goes last.
 * ltc.rentonisk.com, the old Litecoin default, stopped answering then and stays last in case it
 * returns. Esplora entries carry the API URL.
 */
object PublicServers {
    private fun tls(host: String, port: Int, selfSigned: Boolean = false) =
        WalletRepository.ServerSettings(host, port, true, selfSigned, false, "127.0.0.1", 9050)

    private fun esplora(url: String) =
        WalletRepository.ServerSettings(java.net.URI(url).host, 443, true, false, false, "127.0.0.1", 9050, esploraUrl = url)

    private val mainnet = listOf(
        tls("electrum1.cipig.net", 20063),
        tls("fallacy.fiatfaucet.com", 50002),
        tls("electrum.jhoenicke.de", 50091),
        tls("electrum.petrkr.net", 60002),
        tls("electrum.jochen-hoenicke.de", 50091),
        tls("ltc.rentonisk.com", 50002)
    )

    private val testnet = listOf(
        tls("electrum.ltc.xurious.com", 51002, selfSigned = true),
        tls("electrum-ltc.bysh.me", 51002, selfSigned = true)
    )

    fun forCoin(coin: Coin, network: Network): List<WalletRepository.ServerSettings> = when(network) {
        Network.MAINNET -> when(coin) {
            Coin.LITECOIN -> mainnet
            Coin.DOGECOIN -> listOf(
                tls("doge.electrum1.cipig.net", 20060),
                tls("doge.electrum2.cipig.net", 20060),
                tls("dogecoin.stackwallet.com", 50022),
                tls("doge.aftrek.org", 50002, selfSigned = true)
            )
            Coin.DINGOCOIN -> listOf(
                tls("seed1.dingocoin.com", 3342),
                tls("seed3.dingocoin.com", 3342),
                tls("elecx2.kmsoftware.org", 3342)
            )
            Coin.BELLSCOIN -> listOf(esplora("https://api.nintondo.io"))
            Coin.PEPECOIN -> listOf(esplora("https://peppool.space/api"))
            Coin.LUCKYCOIN -> listOf(esplora("https://luckyscan.org/api"))
            Coin.JUNKCOIN -> listOf(esplora("https://explorer.junk-coin.com/api")) // reads only, no broadcast
        }
        Network.TESTNET -> when(coin) {
            Coin.LITECOIN -> testnet
            Coin.PEPECOIN -> listOf(esplora("https://pepe-testnet-api.nintondo.io"))
            Coin.JUNKCOIN -> listOf(esplora("https://jkc-testnet-api.s3na.xyz"))
            Coin.DOGECOIN, Coin.LUCKYCOIN, Coin.DINGOCOIN, Coin.BELLSCOIN -> emptyList() // your own node only
        }
    }

    fun forNetwork(network: Network): List<WalletRepository.ServerSettings> = forCoin(Coin.LITECOIN, network)

    /** The list with the last server that worked moved to the front. */
    fun ordered(coin: Coin, network: Network, lastGoodHost: String?): List<WalletRepository.ServerSettings> {
        val all = forCoin(coin, network)
        val last = all.firstOrNull { it.host == lastGoodHost } ?: return all
        return listOf(last) + (all - last)
    }

    fun ordered(network: Network, lastGoodHost: String?) = ordered(Coin.LITECOIN, network, lastGoodHost)
}

/** What the Server screen shows: the saved or default server, or that the coin needs the user's own. */
sealed interface ServerScreenState {
    data object NeedsOwnServer : ServerScreenState
    data class Configured(val settings: WalletRepository.ServerSettings) : ServerScreenState
}

fun serverScreenState(settings: WalletRepository.ServerSettings?): ServerScreenState =
    settings?.let { ServerScreenState.Configured(it) } ?: ServerScreenState.NeedsOwnServer

/** testConnection's result in words: [software, protocol] for Electrum, ["Esplora", tip] for Esplora. */
fun connectionSummary(result: List<String>): String =
    if(result.firstOrNull() == "Esplora") "Esplora, tip ${result.getOrNull(1)}"
    else "Electrum ${result.getOrNull(0)}, protocol ${result.getOrNull(1)}"

/**
 * The kind of server the Server screen starts on: the saved one's kind, else the coin's usual kind.
 * Any coin can use either: your own node may run ElectrumX where the public servers are Esplora.
 */
fun serverKindFor(coin: Coin, saved: WalletRepository.ServerSettings?): BackendKind =
    saved?.let { if(it.esploraUrl != null) BackendKind.ESPLORA else BackendKind.ELECTRUM } ?: coin.backend

/** What the Server screen's fields hold, for either kind of server. */
data class ServerFields(
    val kind: BackendKind,
    val host: String = "",
    val port: String = "50002",
    val useTls: Boolean = true,
    val selfSigned: Boolean = false,
    val esploraUrl: String = "",
    val useProxy: Boolean = false,
    val proxyHost: String = "127.0.0.1",
    val proxyPort: String = "9050"
) {
    /** The settings these fields describe, or null while one is missing or malformed. */
    fun settings(): WalletRepository.ServerSettings? {
        val pp = proxyPort.toIntOrNull() ?: return null
        if(useProxy && proxyHost.isBlank()) return null
        if(kind == BackendKind.ESPLORA) return esploraSettings(esploraUrl, useProxy, proxyHost.trim(), pp)
        val p = port.toIntOrNull() ?: return null
        if(host.isBlank()) return null
        return WalletRepository.ServerSettings(host.trim(), p, useTls, selfSigned, useProxy, proxyHost.trim(), pp)
    }
}

/** Settings for an Esplora API base URL (http or https), or null when it is not one. */
fun esploraSettings(url: String, useProxy: Boolean, proxyHost: String, proxyPort: Int): WalletRepository.ServerSettings? {
    val clean = url.trim().trimEnd('/')
    val uri = runCatching { java.net.URI(clean) }.getOrNull() ?: return null
    if(uri.scheme != "https" && uri.scheme != "http") return null
    val host = uri.host ?: return null
    val port = if(uri.port > 0) uri.port else if(uri.scheme == "https") 443 else 80
    return WalletRepository.ServerSettings(host, port, uri.scheme == "https", false, useProxy, proxyHost, proxyPort, esploraUrl = clean)
}

object ServerFallback {
    /**
     * Runs [attempt] against each candidate in turn and returns the first that succeeds with its
     * result. Air-gapped mode and cancellation stop at once; otherwise the last error is thrown
     * when every candidate fails.
     */
    suspend fun <S, T> firstWorking(candidates: List<S>, attempt: suspend (S) -> T): Pair<S, T> {
        require(candidates.isNotEmpty()) { "No servers to try" }
        var last: Exception? = null
        for(candidate in candidates) {
            try {
                return candidate to attempt(candidate)
            } catch(e: AirGappedException) {
                throw e
            } catch(e: CancellationException) {
                throw e
            } catch(e: Exception) {
                last = e
            }
        }
        throw last!!
    }
}
