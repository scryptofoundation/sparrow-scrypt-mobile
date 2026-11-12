package com.sparrowwallet.mobile.crypto.evm

import org.bouncycastle.crypto.digests.KeccakDigest

actual fun keccak256(data: ByteArray): ByteArray {
    val digest = KeccakDigest(256)
    digest.update(data, 0, data.size)
    val out = ByteArray(32)
    digest.doFinal(out, 0)
    return out
}
