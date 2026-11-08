package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.ScriptType

/** Which desktop wallets the phone can take, by coin and script type. */
object ImportRules {
    /** A user-facing reason the wallet can't be imported, or null. A missing script type is Native Segwit, as older files are. */
    fun problem(coin: Coin, network: Network, scriptType: String?, linkedMweb: Boolean): String? {
        if(linkedMweb && coin != Coin.LITECOIN) return "Only Litecoin wallets have a linked MWEB wallet"
        val type = scriptType ?: ScriptType.P2WPKH.name
        if(type == "MWEB") return if(coin.mweb) null else "${coin.displayName} has no MWEB wallets"
        val known = ScriptType.entries.firstOrNull { it.name == type } ?: return "Mobile does not support $type wallets yet"
        if(ChainParams.of(coin, network).supports(known)) return null
        return if(known == ScriptType.P2TR) "Taproot is not active on ${coin.displayName} ${network.displayName}"
               else "${coin.displayName} has no $type wallets"
    }
}
