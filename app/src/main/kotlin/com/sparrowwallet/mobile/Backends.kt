package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.electrum.ChainBackend
import com.sparrowwallet.mobile.electrum.ElectrumBackend
import com.sparrowwallet.mobile.electrum.ElectrumClient
import com.sparrowwallet.mobile.electrum.EsploraBackend
import com.sparrowwallet.mobile.electrum.SocksProxy

/** Opens the backend a server setting describes: an Esplora API when it has a URL, else an Electrum server. */
object Backends {
    fun open(s: WalletRepository.ServerSettings, params: ChainParams): ChainBackend {
        val proxy = if(s.useProxy) SocksProxy(s.proxyHost, s.proxyPort) else null
        val url = s.esploraUrl
        return if(url != null) EsploraBackend(url, params, proxy)
               else ElectrumBackend(ElectrumClient(s.host, s.port, s.useTls, proxy, s.allowSelfSigned))
    }
}
