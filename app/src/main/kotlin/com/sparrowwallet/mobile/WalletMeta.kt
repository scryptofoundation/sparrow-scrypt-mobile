package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The longest wallet file name, in characters. */
const val WALLET_NAME_MAX = 40

/** A wallet's file name on disk: unsafe characters become _, at most [WALLET_NAME_MAX] characters. */
fun walletFileName(name: String): String =
    name.trim().map { if(it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
        .joinToString("").take(WALLET_NAME_MAX)

/**
 * Replaces [file] with [bytes] in one step: they are written and synced to `<name>.tmp` beside it (the wallet list
 * reads only `.vault` files, see [walletVaultName]), which is then moved over [file]. A crash leaves the old file or the
 * new one, never a torn one; a `.tmp` a crash left behind is removed by the next write of the same file, so they never
 * pile up. On failure the temporary file is removed and the error is thrown.
 */
internal fun writeAtomically(file: File, bytes: ByteArray,
                             write: (File, ByteArray) -> Unit = { f, b -> FileOutputStream(f).use { it.write(b); it.fd.sync() } }) {
    val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
    tmp.delete()
    try {
        write(tmp, bytes)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        tmp.delete()
    }
}

/**
 * One lock over the wallet files for the whole process, shared by the activity's repository and the background worker's.
 * A delete holds it, and so does every write that must not outlive a delete: a session registration, saved sync
 * credentials, a vault re-seal. Nothing slow (a key derivation, a network call) runs under it.
 */
internal val walletFilesLock = Any()

/** The vault of the wallet [name] (as typed, or as the list names it) in [walletsDir]. */
internal fun vaultFileOf(walletsDir: File, name: String): File = File(walletsDir, "${walletFileName(name)}.vault")

/**
 * Runs [keep] under [walletFilesLock] while [vault] exists and returns true; otherwise runs [gone] (removing what was
 * kept for the wallet) and returns false. For what slow work (an unlock's key derivation, a background scan) keeps for a
 * wallet that a delete may have removed meanwhile: a deleted wallet is never written back.
 */
internal fun whileVaultExists(vault: File, gone: () -> Unit = {}, keep: () -> Unit): Boolean =
    synchronized(walletFilesLock) {
        if(vault.exists()) {
            keep()
            true
        } else {
            gone()
            false
        }
    }

/** A write for a wallet that is no longer on this phone. */
class WalletGoneException : IOException("This wallet is no longer on this phone.")

/**
 * Re-seals an existing wallet's [vault] with [bytes] in one step ([writeAtomically]), under [walletFilesLock]: a crash
 * leaves the old vault or the new one, and a wallet deleted meanwhile throws [WalletGoneException] rather than being
 * written back as a vault without its meta.
 */
internal fun rewriteVault(vault: File, bytes: ByteArray, write: ((File, ByteArray) -> Unit)? = null) {
    synchronized(walletFilesLock) {
        if(!vault.exists()) throw WalletGoneException()
        if(write == null) writeAtomically(vault, bytes) else writeAtomically(vault, bytes, write)
    }
}

/**
 * A wallet's links to other wallets on this phone, by name. A public wallet names its companions
 * ([mweb], the Private wallet, and [taproot], the same seed's Taproot wallet); a companion names its [parent].
 */
data class WalletLinks(val mweb: String? = null, val taproot: String? = null, val parent: String? = null) {
    /** The companions this wallet names: MWEB, then Taproot. */
    val companions: List<String> get() = listOfNotNull(mweb, taproot)
    val isCompanion: Boolean get() = parent != null

    /** The MWEB counterpart, as the single link of the previous build meant it: the parent of an MWEB wallet, else its MWEB companion. */
    fun mwebCounterpart(scriptType: String): String? = if(scriptType == "MWEB") parent else mweb

    companion object {
        /** Reads the previous build's single `linkedWallet` name, which only ever linked an MWEB pair. */
        fun fromOldField(linkedWallet: String?, scriptType: String): WalletLinks {
            val name = linkedWallet?.takeIf { it.isNotEmpty() } ?: return WalletLinks()
            return if(scriptType == "MWEB") WalletLinks(parent = name) else WalletLinks(mweb = name)
        }
    }
}

/**
 * The unencrypted `.meta` file beside each vault: what the wallet list needs without a password.
 * Files written before the coin field read as Litecoin; an unknown coin is an error. Balances never go here.
 * Links are written as a `links` object, and the MWEB counterpart also as the old `linkedWallet` field,
 * so the previous build still opens an MWEB pair. Files with only the old field read as an MWEB link.
 */
object WalletMeta {
    data class Decoded(val network: Network, val coin: Coin, val scriptType: String, val links: WalletLinks = WalletLinks())

    fun encode(network: Network, coin: Coin, scriptType: String, links: WalletLinks = WalletLinks()): String =
        buildJsonObject {
            put("network", network.id)
            put("coin", coin.id)
            put("scriptType", scriptType)
            links.mwebCounterpart(scriptType)?.let { put("linkedWallet", it) }
            if(links != WalletLinks()) put("links", buildJsonObject {
                links.mweb?.let { put("mweb", it) }
                links.taproot?.let { put("taproot", it) }
                links.parent?.let { put("parent", it) }
            })
        }.toString()

    fun decode(json: String): Decoded {
        val obj = Json.parseToJsonElement(json).jsonObject
        fun str(key: String) = obj[key]?.jsonPrimitive?.content
        val scriptType = str("scriptType") ?: "P2WPKH"
        return Decoded(
            Network.fromId(str("network") ?: throw IllegalArgumentException("No network")),
            str("coin")?.let { Coin.fromId(it) } ?: Coin.LITECOIN,
            scriptType,
            readLinks(obj["links"]) ?: WalletLinks.fromOldField(str("linkedWallet"), scriptType)
        )
    }

    /** The `links` object, or null when it is missing or not an object; a value that is not a non-empty string reads as no link. */
    private fun readLinks(element: JsonElement?): WalletLinks? {
        val obj = element as? JsonObject ?: return null
        fun name(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        return WalletLinks(name("mweb"), name("taproot"), name("parent"))
    }
}
