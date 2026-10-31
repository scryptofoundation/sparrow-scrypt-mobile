package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.Network
import kotlinx.coroutines.CancellationException

/**
 * Public Electrum-LTC servers used when the user has not set one. Mainnet holds only servers whose
 * TLS certificates verify against the system CAs (checked 2026-10-31); ltc.rentonisk.com, the old
 * default, stopped answering then and stays last in case it returns.
 */
object PublicServers {
    private fun tls(host: String, port: Int, selfSigned: Boolean = false) =
        WalletRepository.ServerSettings(host, port, true, selfSigned, false, "127.0.0.1", 9050)

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

    fun forNetwork(network: Network): List<WalletRepository.ServerSettings> = when(network) {
        Network.MAINNET -> mainnet
        Network.TESTNET -> testnet
    }

    /** The list with the last server that worked moved to the front. */
    fun ordered(network: Network, lastGoodHost: String?): List<WalletRepository.ServerSettings> {
        val all = forNetwork(network)
        val last = all.firstOrNull { it.host == lastGoodHost } ?: return all
        return listOf(last) + (all - last)
    }
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
