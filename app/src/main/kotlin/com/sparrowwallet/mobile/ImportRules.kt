package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.ScriptType

/** Which desktop wallets the phone can take, by coin and script type. */
object ImportRules {
    /** A user-facing reason the wallet can't be imported, or null. A missing script type is Native Segwit, as older files are. */
    fun problem(coin: Coin, scriptType: String?, linkedMweb: Boolean): String? {
        if(linkedMweb && coin != Coin.LITECOIN) return "Only Litecoin wallets have a linked MWEB wallet"
        val type = scriptType ?: ScriptType.P2WPKH.name
        if(type == "MWEB") return if(coin.mweb) null else "${coin.displayName} has no MWEB wallets"
        //Taproot imports open up with the app UI (Task 7)
        val known = ScriptType.entries.firstOrNull { it.name == type && it != ScriptType.P2TR } ?: return "Mobile does not support $type wallets yet"
        return if(coin.supports(known)) null else "${coin.displayName} has no $type wallets"
    }
}
