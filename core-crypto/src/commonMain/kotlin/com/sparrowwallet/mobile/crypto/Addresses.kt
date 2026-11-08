package com.sparrowwallet.mobile.crypto

/**
 * Litecoin address encoding, matching desktop drongo byte-for-byte
 * (see AddressParityTest against golden vectors).
 */
object Addresses {
    fun p2pkh(pubKey: ByteArray, version: Int = Litecoin.P2PKH_VERSION): String {
        return Base58.encodeChecked(byteArrayOf(version.toByte()) + hash160(pubKey))
    }

    fun p2shP2wpkh(pubKey: ByteArray, version: Int = Litecoin.P2SH_VERSION): String {
        val redeemScript = byteArrayOf(0x00, 0x14) + hash160(pubKey) // OP_0 PUSH20 <pkh>
        return Base58.encodeChecked(byteArrayOf(version.toByte()) + hash160(redeemScript))
    }

    fun p2wpkh(pubKey: ByteArray, hrp: String = Litecoin.BECH32_HRP): String {
        return Bech32.encodeWitness(hrp, 0, hash160(pubKey))
    }

    /**
     * MWEB stealth address for the given index (drongo MwebAddressDeriver):
     *   m  = BLAKE3('A' || le32(index) || scanSecret)
     *   Bi = spendPubKey + G*m
     *   Ai = scanSecret * Bi
     * encoded as bech32 version 0 over Ai || Bi with HRP "ltcmweb".
     */
    fun mwebStealth(scanSecret: ByteArray, spendPubKey: ByteArray, index: Int, hrp: String = Litecoin.MWEB_HRP): String {
        val message = ByteArray(37)
        message[0] = 'A'.code.toByte()
        message[1] = (index and 0xFF).toByte()
        message[2] = ((index ushr 8) and 0xFF).toByte()
        message[3] = ((index ushr 16) and 0xFF).toByte()
        message[4] = ((index ushr 24) and 0xFF).toByte()
        scanSecret.copyInto(message, 5)
        val m = Blake3.hash(message)
        val b = secpPubKeyTweakAdd(spendPubKey, m)
        val a = secpPubKeyTweakMul(b, scanSecret)
        return Bech32.encodeWitness(hrp, 0, a + b)
    }

    /**
     * MWEB wallet-node → stealth address index (drongo MwebUtils.getAddressIndex):
     * all change nodes share index 0; receive node i uses index i+1.
     */
    fun mwebAddressIndex(change: Boolean, nodeIndex: Int): Int {
        return if(change) 0 else nodeIndex + 1
    }

    /** The receive address of [pubKey] for a wallet of [scriptType] on [params]' chain. */
    fun forKey(pubKey: ByteArray, scriptType: ScriptType, params: ChainParams): String = when(scriptType) {
        ScriptType.P2PKH -> p2pkh(pubKey, params.p2pkhVersion)
        ScriptType.P2SH_P2WPKH -> p2shP2wpkh(pubKey, params.p2shVersion)
        ScriptType.P2WPKH -> p2wpkh(pubKey, requireNotNull(params.bech32Hrp) { "${params.coin.displayName} has no segwit addresses" })
        ScriptType.P2TR -> Bech32.encodeWitness(
            requireNotNull(params.bech32Hrp) { "${params.coin.displayName} has no segwit addresses" }, 1, Taproot.outputKey(pubKey)
        )
    }

    /** Litecoin decoding, kept for the call sites that predate the coin axis. */
    fun toScriptPubKey(address: String, network: Network = Network.MAINNET): ByteArray =
        toScriptPubKey(address, network.litecoin)

    /**
     * Decodes an address of [params]' coin and network to its scriptPubKey, for building
     * transaction outputs. Accepts bech32 segwit (v0 P2WPKH/P2WSH and v1+ per BIP350) where the
     * coin has segwit, base58 P2PKH, and base58 P2SH (plus Litecoin's deprecated Bitcoin-shared
     * version). Throws IllegalArgumentException with a user-facing message on anything else.
     * The coin comes from the wallet: a Dogecoin address decodes for a Dingocoin wallet, since
     * the two chains share every address byte.
     */
    fun toScriptPubKey(address: String, params: ChainParams): ByteArray {
        val addr = address.trim()
        val lower = addr.lowercase()
        val sameCoin = Network.entries.map { ChainParams.of(params.coin, it) }
        if(sameCoin.any { p -> p.mwebHrp?.let { lower.startsWith(it + "1") } == true }) {
            throw IllegalArgumentException("MWEB destinations aren't supported on mobile yet")
        }
        val hrp = params.bech32Hrp
        if(hrp != null) {
            val wrongNet = sameCoin.firstOrNull { p -> p.network != params.network && p.bech32Hrp?.let { lower.startsWith(it + "1") } == true }
            if(wrongNet != null && !lower.startsWith(hrp + "1")) {
                throw IllegalArgumentException("That's a ${wrongNet.network.displayName} address, but this wallet is on ${params.network.displayName}")
            }
            if(lower.startsWith(hrp + "1")) {
                val witness = Bech32.decodeWitness(addr)
                require(witness.hrp == hrp) { "Wrong address network: ${witness.hrp}" }
                return when {
                    witness.version == 0 && (witness.program.size == 20 || witness.program.size == 32) ->
                        byteArrayOf(0x00, witness.program.size.toByte()) + witness.program
                    witness.version in 1..16 && witness.program.size in 2..40 -> {
                        require(params.taprootDestinations) { "Taproot is not active on ${params.coin.displayName} ${params.network.displayName}" }
                        byteArrayOf((0x50 + witness.version).toByte(), witness.program.size.toByte()) + witness.program
                    }
                    else -> throw IllegalArgumentException("Invalid witness program length: ${witness.program.size}")
                }
            }
        }
        val notOurs = "Not a ${params.coin.displayName} address"
        val payload = Base58.decodeChecked(addr)
        require(payload.size == 21) { "Invalid address payload length: ${payload.size}" }
        val hash = payload.copyOfRange(1, 21)
        return when(payload[0].toInt() and 0xFF) {
            params.p2pkhVersion ->
                byteArrayOf(0x76.toByte(), 0xA9.toByte(), 0x14) + hash + byteArrayOf(0x88.toByte(), 0xAC.toByte())
            params.p2shVersion, params.p2shLegacyVersion ->
                byteArrayOf(0xA9.toByte(), 0x14) + hash + byteArrayOf(0x87.toByte())
            else -> {
                val version = payload[0].toInt() and 0xFF
                val wrongNet = sameCoin.firstOrNull { p ->
                    p.network != params.network && (p.p2pkhVersion == version || p.p2shVersion == version || p.p2shLegacyVersion == version)
                }
                throw IllegalArgumentException(
                    if(wrongNet != null) "That's a ${wrongNet.network.displayName} address, but this wallet is on ${params.network.displayName}"
                    else notOurs
                )
            }
        }
    }

    /**
     * Other coins on the wallet's network where this base58 address is also well formed. An address
     * never says which chain it is for when this is non-empty (Dogecoin P2PKH and Bellscoin P2SH are
     * both 30), so a send must warn. Bech32 and undecodable input return an empty list.
     */
    fun coinsSharingEncoding(address: String, params: ChainParams): List<Coin> {
        val payload = runCatching { Base58.decodeChecked(address.trim()) }.getOrNull() ?: return emptyList()
        if(payload.size != 21) return emptyList()
        return ChainParams.coinsSharingAddressEncoding(params.coin, payload[0].toInt() and 0xFF, params.network)
    }

    fun fromScript(script: ByteArray, network: Network): String? = fromScript(script, network.litecoin)

    /**
     * The address a scriptPubKey pays on [params]' chain, for showing outputs in a PSBT review;
     * null for anything with no address form there (OP_RETURN, MWEB peg-ins, segwit on a chain
     * without it, nonstandard scripts).
     */
    fun fromScript(script: ByteArray, params: ChainParams): String? {
        val op = { i: Int -> script[i].toInt() and 0xFF }
        val hrp = params.bech32Hrp
        return when {
            hrp != null && script.size == 22 && op(0) == 0x00 && op(1) == 0x14 ->
                Bech32.encodeWitness(hrp, 0, script.copyOfRange(2, 22))
            hrp != null && script.size == 34 && op(0) == 0x00 && op(1) == 0x20 ->
                Bech32.encodeWitness(hrp, 0, script.copyOfRange(2, 34))
            hrp != null && script.size == 34 && op(0) == 0x51 && op(1) == 0x20 ->
                Bech32.encodeWitness(hrp, 1, script.copyOfRange(2, 34))
            script.size == 25 && op(0) == 0x76 && op(1) == 0xA9 && op(2) == 0x14 && op(23) == 0x88 && op(24) == 0xAC ->
                Base58.encodeChecked(byteArrayOf(params.p2pkhVersion.toByte()) + script.copyOfRange(3, 23))
            script.size == 23 && op(0) == 0xA9 && op(1) == 0x14 && op(22) == 0x87 ->
                Base58.encodeChecked(byteArrayOf(params.p2shVersion.toByte()) + script.copyOfRange(2, 22))
            else -> null
        }
    }
}
