package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.hexToBytes
import java.io.IOException

/**
 * A raw transaction a server answered for [txid], as bytes, only if it really is that transaction:
 * a previous transaction goes into a PSBT, and a signer trusts what it proves about the spent output.
 */
fun verifiedRawTransaction(txid: String, hex: String): ByteArray {
    val clean = hex.trim().lowercase()
    if(clean.isEmpty() || clean.length % 2 != 0 || clean.length > HttpEndpoint.MAX_BODY || !clean.all { it in '0'..'9' || it in 'a'..'f' }) {
        throw IOException("The server's transaction $txid is not valid hex")
    }
    val bytes = clean.hexToBytes()
    val parsed = try {
        RawTx.parse(bytes)
    } catch(e: Exception) {
        throw IOException("The server's transaction $txid could not be read", e)
    }
    if(!parsed.txid.equals(txid, ignoreCase = true)) throw IOException("The server returned a different transaction for $txid")
    return bytes
}
