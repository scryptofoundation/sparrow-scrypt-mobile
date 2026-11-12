package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.AirGappedException
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.evm.EvmAccount
import com.sparrowwallet.mobile.crypto.evm.EvmAddress
import com.sparrowwallet.mobile.crypto.evm.NftMetadata
import com.sparrowwallet.mobile.crypto.evm.NftPicturePlan
import com.sparrowwallet.mobile.crypto.evm.SvgSubset
import com.sparrowwallet.mobile.crypto.evm.UInt256
import com.sparrowwallet.mobile.electrum.HttpEndpoint
import com.sparrowwallet.mobile.electrum.RateLimitedException
import com.sparrowwallet.mobile.electrum.litvm.Erc721
import com.sparrowwallet.mobile.electrum.litvm.Erc721Collection
import com.sparrowwallet.mobile.electrum.litvm.KnownCollections
import com.sparrowwallet.mobile.electrum.litvm.LitVmCalls
import com.sparrowwallet.mobile.electrum.litvm.LitVmException
import com.sparrowwallet.mobile.electrum.litvm.LitVmNetwork
import com.sparrowwallet.mobile.electrum.litvm.Nft
import com.sparrowwallet.mobile.electrum.litvm.NftLister
import com.sparrowwallet.mobile.electrum.litvm.TimedOut
import com.sparrowwallet.mobile.storage.VaultData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The LitVM account of a wallet: the address of m/44'/60'/0'/0/0 under the wallet's master key, which is the depth-0 key
 * of the wallet's own seed, which must not be blank (a BIP39 seed with its passphrase, or an Electrum seed's own seed bytes), as the desktop's
 * same-seed LitVM link derives it. Only the 20-byte address comes out: the master key's private key and chain code are
 * zeroed before this returns, and nothing here keeps, logs or throws with key material.
 */
internal fun litVmAddressOf(data: VaultData): EvmAddress {
    //An empty mnemonic would stretch to the well-known empty-seed account: refused, never shown as the wallet's
    require(data.mnemonic.isNotBlank()) { "The wallet has no seed on this phone" }
    val master = walletMasterKey(data)
    try {
        return EvmAccount.address(master)
    } finally {
        master.privKey.fill(0)
        master.chainCode.fill(0)
    }
}

/**
 * One built-in collection's answer for the account. [collection] is null when the contract could not be read as a
 * collection. LISTED: [tokens] were read, [more] were not looked up, [failed] got no answer. FAILED: nothing could be
 * read (the network, a rate limit, or a built-in contract that does not answer as a collection). NOT_REACHED: the
 * refresh's three minutes ran out first.
 */
class LitVmCollection(val contract: EvmAddress, val collection: Erc721Collection?, val status: Status, tokens: List<Nft>, val more: Int,
                      val failed: Int) {
    enum class Status { LISTED, FAILED, NOT_REACHED }

    /** A copy: the lister's list is its own. */
    val tokens: List<Nft> = tokens.toList()
}

/**
 * What one LitVM read gave for [address]: the [balance] in wei (null when not read), a [problem] that ended it early
 * (with the [reason], which the screen says only in the app's own fixed words), the built-in [collections] (empty when
 * the chain check or the balance failed), whether the refresh's three minutes cut it short ([stopped]), and the pictures
 * prepared for the shown tiles, by tile key. Never holds a key, nor any text from the server: only the address.
 */
class LitVmRead(val address: EvmAddress, val balance: UInt256?, val problem: Problem?, val reason: Reason?, collections: List<LitVmCollection>,
                val stopped: Boolean, pictures: Map<String, LitVmPicture>) {
    enum class Problem { CHAIN, BALANCE, RATE_LIMITED, AIR_GAPPED }

    /** Why the chain check or the balance failed, as the app says it (see [LitVmModel.reasonText]). */
    enum class Reason { WRONG_CHAIN, TIMED_OUT, RATE_LIMITED, UNREACHABLE }

    val collections: List<LitVmCollection> = collections.toList()
    val pictures: Map<String, LitVmPicture> = HashMap(pictures)
}

/** A token's picture as the screen may show it, decided off the main thread in the read. */
sealed interface LitVmPicture {
    /**
     * An SVG in the subset: its [svg] text (inside the metadata JSON, so at most 20,000 characters), [source] a hash of
     * it for the cache key, and the [tile] plan laid out in the read (null once the read is released; any other size,
     * the detail's included, is planned from the text when it is drawn, see [LitVmPictures.plan]).
     */
    class Drawn(val svg: String, val source: Long, val tile: NftPicturePlan.Plan?) : LitVmPicture

    /** PNG, JPEG, GIF or WebP bytes [PreviewRules] cleared, with the size their header declares; [source] hashes the bytes. */
    class Raster(bytes: ByteArray, val width: Int, val height: Int, val source: Long) : LitVmPicture {
        private val raw = bytes.copyOf()
        val bytes: ByteArray get() = raw.copyOf()
    }

    /** Hosted elsewhere: never fetched, only named. */
    data class Hosted(val host: String) : LitVmPicture

    data object None : LitVmPicture
}

/** A placeholder in place of a picture: the app's [text] and, for a hosted picture, the [host] shown on its own. */
data class LitVmPlaceholder(val text: String, val host: String?)

/** One of the app's own rows on a token's detail: built from the token id, the contract and the wallet's knowledge only. */
data class LitVmAppRow(val kind: Kind, val label: String?, val value: String) {
    enum class Kind { TOKEN_ID, CONTRACT, MARKER, SOULBOUND }
}

/**
 * A token's detail: the app's rows first, then what the collection says ([title], [collection], [description],
 * [attributes]), each value kept apart for a box of its own.
 */
data class LitVmDetail(val appRows: List<LitVmAppRow>, val title: String, val collection: String, val description: String,
                       val attributes: List<NftMetadata.Attribute>)

/** One tile. [label] and [collection] are the contract's text; [marker] is the app's. [key] identifies it in the visit. */
class LitVmTile(val key: String, val label: String, val collection: String, val marker: String, val picture: LitVmPicture,
                val detail: LitVmDetail, val contract: EvmAddress, val tokenId: UInt256)

/** A line under the tiles: a collection's [name] (contract text, or the app's short address; null for a line of its own) and the app's [text]. */
data class LitVmNote(val name: String?, val text: String)

/**
 * What the LitVM screen shows. [status] and [statusDetail] are only ever the app's own fixed sentences, never text from
 * the server or a contract. [empty] only when every built-in collection was read in full and holds nothing.
 */
class LitVmView(val address: String?, val balance: String?, val status: String?, val statusDetail: String?, val tiles: List<LitVmTile>,
                val notes: List<LitVmNote>, val empty: Boolean, val canRefresh: Boolean)

/**
 * What the LitVM screen shows, worked out from a read: plain Kotlin and deterministic, whatever the device locale. Names,
 * descriptions and attributes come from contracts anyone can deploy: they stay separate values, never joined with the
 * app's own words. Ported from the desktop's CollectiblesPresenter.
 */
object LitVmModel {
    const val BUTTON = "LitVM"
    const val TITLE = "LitVM LiteForge Testnet"
    const val NO_VALUE = "Test zkLTC and test tokens have no value."
    const val COPY_ADDRESS = "Copy address"
    const val AIR_GAPPED = "Off in air-gapped mode"
    const val SECTION = "Collectibles"
    const val EMPTY = "No collectibles in this account."
    const val BUILT_IN = "Built in"
    const val SOULBOUND = "Soulbound: cannot be sent"
    const val FROM_COLLECTION = "From the collection (not checked)"
    const val NO_PICTURE = "No picture"
    const val HOSTED_AT = "Picture not shown: hosted at"
    const val COULD_NOT_BE_READ = "Could not be read"
    const val STOPPED = "Stopped after 3 minutes. Refresh to continue."
    const val FOOTNOTE = "Anyone can send an NFT to any account, and a collection's name proves nothing. Only the collections built in are " +
        "listed, by contract address. Nothing here sends or signs."
    const val READING = "Reading..."
    const val CHAIN_FAILED = "Could not check the LitVM chain."
    const val BALANCE_FAILED = "Could not read the zkLTC balance."
    const val RATE_LIMITED = "The LitVM server is limiting requests. Refresh later."
    const val UNREACHABLE = "The LitVM server could not be reached."
    const val WRONG_CHAIN = "This server is for another chain."
    const val TIMED_OUT = "The LitVM server took too long."
    const val ADDRESS_FAILED = "The address could not be worked out."
    const val UNIT = "zkLTC"

    /** Every sentence the status area can show (the status line, then its second line). */
    val STATUS_SENTENCES = listOf(READING, AIR_GAPPED, ADDRESS_FAILED, CHAIN_FAILED, BALANCE_FAILED, RATE_LIMITED, STOPPED, WRONG_CHAIN,
        TIMED_OUT, UNREACHABLE)

    /** The most tiles shown, across every collection, in display order. */
    const val MAX_TILES = 96
    /** A hosted picture's host is cut from the front to this many characters: two lines of the narrowest tile. */
    const val MAX_HOST_CHARS = 24
    /** One refresh's time, and each collection's within it. */
    const val REFRESH_MS = 180_000L
    const val COLLECTION_MS = NftLister.DEADLINE_MS
    //Longer token ids are cut in the middle on a tile; the detail shows the whole id
    private const val MAX_TILE_ID_DIGITS = 14
    private const val WEI_DECIMALS = 18
    private const val SHOWN_DECIMALS = 6

    /**
     * Whether Home offers LitVM: a Litecoin TESTNET wallet with a seed on the phone, not MWEB, not a companion of
     * another wallet ([companionOf]) and shown on the Public layer. [info] is null when the wallet is not listed.
     */
    fun available(info: WalletRepository.WalletInfo?, hasSeed: Boolean, wallets: List<WalletRepository.WalletInfo>, layer: Layer): Boolean =
        info != null && mayOffer(info.coin, info.network, info.scriptType, hasSeed, layer) && info.links.parent == null &&
            wallets.none { companionOf(it, info, wallets) }

    /** What [available] decides without the wallet list: Home looks at the list only when this holds. */
    fun mayOffer(coin: Coin, network: Network, scriptType: String, hasSeed: Boolean, layer: Layer): Boolean =
        hasSeed && layer == Layer.PUBLIC && coin == Coin.LITECOIN && network == Network.TESTNET && scriptType != "MWEB"

    /**
     * Whether a read starts: never while air-gapped or while one runs (the read belongs to the visit, so opening a
     * detail does not stop it); otherwise when one was asked for ([requested], counting the open and each Refresh) and
     * not yet finished ([done]). Coming back from a detail asks for nothing, so it never starts one by itself.
     */
    fun shouldRead(airGapped: Boolean, requested: Int, done: Int, running: Boolean): Boolean = !airGapped && !running && requested != done

    /** The app's sentence for why the chain check or the balance failed. */
    fun reasonText(reason: LitVmRead.Reason): String = when(reason) {
        LitVmRead.Reason.WRONG_CHAIN -> WRONG_CHAIN
        LitVmRead.Reason.TIMED_OUT -> TIMED_OUT
        LitVmRead.Reason.RATE_LIMITED -> RATE_LIMITED
        LitVmRead.Reason.UNREACHABLE -> UNREACHABLE
    }

    /** Wei as zkLTC with up to six decimals, cut (never rounded up) and trimmed; exact integer arithmetic. */
    fun balanceText(wei: UInt256): String {
        val digits = wei.toString().padStart(WEI_DECIMALS + 1, '0')
        val whole = digits.substring(0, digits.length - WEI_DECIMALS)
        val fraction = digits.substring(digits.length - WEI_DECIMALS, digits.length - WEI_DECIMALS + SHOWN_DECIMALS).trimEnd('0')
        if(whole == "0" && fraction.isEmpty() && wei != UInt256.ZERO) {
            return "Less than 0.000001 $UNIT"
        }
        return (if(fraction.isEmpty()) whole else "$whole.$fraction") + " $UNIT"
    }

    fun moreLine(more: Int): String? = if(more > 0) "$more more not shown" else null

    fun shortAddress(address: EvmAddress): String {
        val text = address.toString()
        return text.substring(0, 6) + "..." + text.substring(text.length - 4)
    }

    /** The collection's name, else its symbol, else its short address. */
    fun collectionLabel(collection: Erc721Collection): String = when {
        collection.name.isNotBlank() -> collection.name
        collection.symbol.isNotBlank() -> collection.symbol
        else -> shortAddress(collection.contract)
    }

    /** The token's name, or "#ID" (a long id cut in the middle) when it has none. */
    fun tileLabel(nft: Nft): String {
        val name = nft.metadata?.name.orEmpty()
        if(name.isNotBlank()) {
            return name
        }
        var id = nft.tokenId.toString()
        if(id.length > MAX_TILE_ID_DIGITS) {
            id = id.substring(0, 6) + "..." + id.substring(id.length - 6)
        }
        return "#$id"
    }

    /** A tile's key in the visit: its contract and token id, never anything from its metadata. */
    fun tileKey(nft: Nft): String = "${nft.collection.contract}/${nft.tokenId}"

    /** A drawn picture's cache key: per chain, contract, token id, the picture's source (its hash) and pixel size. */
    fun pictureKey(network: LitVmNetwork, contract: EvmAddress, tokenId: UInt256, source: Long, pixels: Double): String =
        "${network.chainId}:$contract:$tokenId:${java.lang.Long.toHexString(source)}:${pixels.toInt()}"

    /** 64-bit FNV-1a of the bytes: tells one picture source from another for the cache, not a security hash. */
    fun sourceHash(bytes: ByteArray): Long {
        var hash = -0x340d631b7bdddcdbL
        for(b in bytes) {
            hash = (hash xor (b.toLong() and 0xFF)) * 0x100000001b3L
        }
        return hash
    }

    /**
     * The tokens shown, in the desktop's order (built-in collections first, then by collection name ignoring case, then
     * by contract, then by token id as a number), at most [MAX_TILES]; and how many more there are.
     */
    fun shownTokens(collections: List<LitVmCollection>): Pair<List<Nft>, Int> {
        val tokens = collections.flatMap { it.tokens }.sortedWith(
            compareBy<Nft> { !KnownCollections.isKnown(LitVmNetwork.LITEFORGE_TESTNET, it.collection.contract) }
                .thenBy { collectionLabel(it.collection).lowercase() }
                .thenBy { it.collection.contract.toString().lowercase() }
                .thenBy { it.tokenId })
        return if(tokens.size <= MAX_TILES) tokens to 0 else tokens.subList(0, MAX_TILES).toList() to tokens.size - MAX_TILES
    }

    /** What stands in for a picture that is not drawn, or null for one that is (or may be) drawn. */
    fun placeholder(picture: LitVmPicture): LitVmPlaceholder? = when(picture) {
        is LitVmPicture.Drawn, is LitVmPicture.Raster -> null
        is LitVmPicture.Hosted -> LitVmPlaceholder(HOSTED_AT, hostText(picture.host))
        LitVmPicture.None -> LitVmPlaceholder(NO_PICTURE, null)
    }

    /**
     * A host cut from the front when long, so the registrable end of the name stays: at most [MAX_HOST_CHARS], which the
     * tile shows on two lines.
     */
    fun hostText(host: String): String = if(host.length <= MAX_HOST_CHARS) host else "..." + host.takeLast(MAX_HOST_CHARS - 3)

    /** A read that could not start (anything thrown around the reader): the server could not be reached, nothing listed. */
    fun unreachable(address: EvmAddress): LitVmRead =
        LitVmRead(address, null, LitVmRead.Problem.CHAIN, LitVmRead.Reason.UNREACHABLE, emptyList(), false, emptyMap())

    /** The read without its picture plans, kept while a Refresh works: its tiles stay listed, their pictures planned again if needed. */
    fun released(read: LitVmRead): LitVmRead = LitVmRead(read.address, read.balance, read.problem, read.reason, read.collections, read.stopped,
        read.pictures.mapValues { (_, picture) -> LitVmPictures.released(picture) })

    /**
     * The collections' facts (name, symbol, enumerable, soulbound) kept for the visit after [read] (null: none yet), so a
     * Refresh does not ask the contract again: a failed chain check drops them all.
     */
    fun keptFacts(kept: Map<EvmAddress, Erc721Collection>, read: LitVmRead?): Map<EvmAddress, Erc721Collection> = when {
        read == null -> kept
        read.problem == LitVmRead.Problem.CHAIN -> emptyMap()
        else -> kept + read.collections.mapNotNull { c -> c.collection?.let { c.contract to it } }
    }

    /** The name each collection gave in this read, to name it later when it cannot be read. */
    fun names(read: LitVmRead): Map<EvmAddress, String> =
        read.collections.mapNotNull { c -> c.collection?.let { c.contract to collectionLabel(it) } }.toMap()

    fun detail(nft: Nft): LitVmDetail {
        val rows = ArrayList<LitVmAppRow>()
        rows.add(LitVmAppRow(LitVmAppRow.Kind.TOKEN_ID, "Token ID", nft.tokenId.toString()))
        rows.add(LitVmAppRow(LitVmAppRow.Kind.CONTRACT, "Contract", nft.collection.contract.toString()))
        rows.add(LitVmAppRow(LitVmAppRow.Kind.MARKER, null, BUILT_IN))
        if(nft.collection.soulbound) {
            rows.add(LitVmAppRow(LitVmAppRow.Kind.SOULBOUND, null, SOULBOUND))
        }
        val metadata = nft.metadata
        val title = if(metadata == null || metadata.name.isBlank()) "#${nft.tokenId}" else metadata.name
        return LitVmDetail(rows, title, collectionLabel(nft.collection), metadata?.description.orEmpty(), metadata?.attributes.orEmpty())
    }

    /**
     * The screen for [address] (null until derived) and [read] (null before the first): while [airGapped] the address
     * and "Off in air-gapped mode" only; [reading] keeps the last read shown under "Reading...". [names] are names seen
     * in earlier reads, for a collection that could not be read this time. [addressFailed]: the address could not be
     * worked out, so only that is said and nothing is read.
     */
    fun build(address: EvmAddress?, read: LitVmRead?, airGapped: Boolean, reading: Boolean = false,
              names: Map<EvmAddress, String> = emptyMap(), addressFailed: Boolean = false): LitVmView {
        val shownAddress = address?.toString()
        if(address == null && addressFailed) {
            return LitVmView(null, null, ADDRESS_FAILED, null, emptyList(), emptyList(), empty = false, canRefresh = false)
        }
        if(airGapped) {
            return LitVmView(shownAddress, null, AIR_GAPPED, null, emptyList(), emptyList(), empty = false, canRefresh = false)
        }
        val canRefresh = address != null && !reading
        if(read == null) {
            return LitVmView(shownAddress, null, if(reading || address == null) READING else null, null, emptyList(), emptyList(), false, canRefresh)
        }
        val (shown, hidden) = shownTokens(read.collections)
        val tiles = shown.map { nft ->
            val key = tileKey(nft)
            LitVmTile(key, tileLabel(nft), collectionLabel(nft.collection), BUILT_IN, read.pictures[key] ?: LitVmPicture.None, detail(nft),
                nft.collection.contract, nft.tokenId)
        }
        val notes = ArrayList<LitVmNote>()
        for(c in read.collections) {
            val name = c.collection?.let { collectionLabel(it) } ?: names[c.contract] ?: shortAddress(c.contract)
            when(c.status) {
                LitVmCollection.Status.FAILED, LitVmCollection.Status.NOT_REACHED -> notes.add(LitVmNote(name, COULD_NOT_BE_READ))
                LitVmCollection.Status.LISTED -> {
                    moreLine(c.more)?.let { notes.add(LitVmNote(name, it)) }
                    if(c.failed > 0) notes.add(LitVmNote(name, COULD_NOT_BE_READ))
                }
            }
        }
        moreLine(hidden)?.let { notes.add(LitVmNote(null, it)) }
        val status = when {
            reading -> READING
            read.problem == LitVmRead.Problem.CHAIN -> CHAIN_FAILED
            read.problem == LitVmRead.Problem.BALANCE -> BALANCE_FAILED
            read.problem == LitVmRead.Problem.RATE_LIMITED -> RATE_LIMITED
            read.problem == LitVmRead.Problem.AIR_GAPPED -> AIR_GAPPED
            read.stopped -> STOPPED
            else -> null
        }
        val detail = if(!reading && (read.problem == LitVmRead.Problem.CHAIN || read.problem == LitVmRead.Problem.BALANCE)) {
            read.reason?.let { reasonText(it) }
        } else null
        val empty = read.problem == null && !read.stopped && read.collections.isNotEmpty() && read.collections.all {
            it.status == LitVmCollection.Status.LISTED && it.tokens.isEmpty() && it.more == 0 && it.failed == 0
        }
        return LitVmView(shownAddress, read.balance?.let { balanceText(it) }, status, detail, tiles, notes, empty, canRefresh)
    }
}

/** Pictures as the read prepares them, off the main thread: the media type label is ignored, the bytes decide. */
object LitVmPictures {
    /**
     * The path commands one read's picture plans may hold in all. A plan costs about 60 bytes a command on the JVM, so
     * with one more picture over it (at most 50,000) a read holds about 15 MB of plans at worst.
     */
    const val READ_BUDGET = 200_000L

    /**
     * The pictures of [tokens], in order, each planned at [tilePixels] until the plans kept pass [READ_BUDGET] commands;
     * every token after that has no picture (it is still listed). Off the main thread.
     */
    suspend fun prepareAll(tokens: List<Nft>, tilePixels: Double): Map<String, LitVmPicture> {
        val pictures = LinkedHashMap<String, LitVmPicture>()
        val prepared = budgeted(tokens, READ_BUDGET, prepare = { prepare(it.metadata, tilePixels) }, cost = { cost(it) }, none = { LitVmPicture.None })
        for((i, nft) in tokens.withIndex()) {
            currentCoroutineContext().ensureActive()
            pictures[LitVmModel.tileKey(nft)] = prepared[i]
        }
        return pictures
    }

    /**
     * [items] prepared in order while the cost kept is under [budget]; once it is reached, the rest are [none] and are
     * not prepared at all. The total kept is at most the budget plus one item's cost.
     */
    fun <T, P> budgeted(items: List<T>, budget: Long, prepare: (T) -> P, cost: (P) -> Int, none: (T) -> P): List<P> {
        var spent = 0L
        return items.map { item ->
            if(spent >= budget) {
                none(item)
            } else {
                prepare(item).also { spent += cost(it) }
            }
        }
    }

    /** The path commands a picture's kept plan holds. */
    fun cost(picture: LitVmPicture): Int = (picture as? LitVmPicture.Drawn)?.tile?.cost ?: 0

    /** The picture without its plan. */
    fun released(picture: LitVmPicture): LitVmPicture =
        if(picture is LitVmPicture.Drawn && picture.tile != null) LitVmPicture.Drawn(picture.svg, picture.source, null) else picture

    /** The plan to draw [picture] at [pixels]: the kept tile plan when it is that size, else planned now from its text. Off the main thread. */
    fun plan(picture: LitVmPicture.Drawn, pixels: Double): NftPicturePlan.Plan? {
        picture.tile?.let { if(it.size == pixels) return it }
        return try {
            SvgSubset.parse(picture.svg)?.let { NftPicturePlan.prepare(it, pixels) }
        } catch(e: CancellationException) {
            throw e
        } catch(t: Throwable) {
            null
        }
    }

    /** [block]'s picture, or no picture when anything at all is thrown (out of memory included); cancellation goes on. */
    fun guard(block: () -> LitVmPicture): LitVmPicture = try {
        block()
    } catch(e: CancellationException) {
        throw e
    } catch(t: Throwable) {
        LitVmPicture.None
    }

    fun prepare(metadata: NftMetadata?, tilePixels: Double): LitVmPicture = guard { unguarded(metadata, tilePixels) }

    private fun unguarded(metadata: NftMetadata?, tilePixels: Double): LitVmPicture = when(val image = metadata?.image) {
        null, NftMetadata.Image.None -> LitVmPicture.None
        is NftMetadata.Image.Hosted -> LitVmPicture.Hosted(image.host)
        is NftMetadata.Image.Svg -> {
            val tile = SvgSubset.parse(image.source)?.let { NftPicturePlan.prepare(it, tilePixels) }
            if(tile == null) LitVmPicture.None
            else LitVmPicture.Drawn(image.source, LitVmModel.sourceHash(image.source.encodeToByteArray()), tile)
        }
        is NftMetadata.Image.Raster -> {
            val bytes = image.bytes
            val size = if(PreviewRules.kind(null, null, bytes) == PreviewRules.Kind.IMAGE) PreviewRules.dimensions(bytes) else null
            if(size == null) LitVmPicture.None else LitVmPicture.Raster(bytes, size.first, size.second, LitVmModel.sourceHash(bytes))
        }
    }
}

/** The numbers handed to android.graphics when a plan is drawn (the contract in [NftPicturePlan]'s KDoc), kept testable. */
object LitVmDrawing {
    /**
     * The item's affine (a, b, c, d, e, f) as the nine values of the 3x3 [a c e; b d f; 0 0 1] in row order, or null to
     * skip the item when the determinant is 0 or not finite (in doubles, or once made floats).
     */
    fun matrixValues(m: SvgSubset.Matrix): FloatArray? {
        val det = m.a * m.d - m.b * m.c
        if(det == 0.0 || !det.isFinite()) {
            return null
        }
        val values = floatArrayOf(m.a.toFloat(), m.c.toFloat(), m.e.toFloat(), m.b.toFloat(), m.d.toFloat(), m.f.toFloat(), 0f, 0f, 1f)
        if(values.any { !it.isFinite() }) {
            return null
        }
        val floatDet = values[0] * values[4] - values[3] * values[1]
        return if(floatDet == 0f || !floatDet.isFinite()) null else values
    }

    /** The paint colour: the ARGB with its alpha times the item's opacity, rounded half up. */
    fun color(argb: Int, opacity: Double): Int {
        val alpha = Math.round((argb ushr 24) * opacity).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (argb and 0xFFFFFF)
    }

    /** Whether a stroke of this width is drawn: never at a float width of 0, which Android draws as a hairline. */
    fun strokeShown(width: Double): Boolean {
        val w = width.toFloat()
        return w > 0f && w.isFinite()
    }

    /** A text stroke's paint width under the text's own scale (size / fontPixels), so it is [width] in shape units. */
    fun textStrokeWidth(width: Double, size: Double, fontPixels: Double): Float = (width * fontPixels / size).toFloat()
}

/**
 * Reads a LitVM account for the screen: the chain check first, then the balance, then each built-in collection with its
 * own minute inside one three-minute deadline for the whole refresh, then the pictures of the tiles shown. A rate limit
 * stops every request after it: the lister stops itself and says so ([NftLister.Listed.rateLimited]); one anywhere else
 * is thrown, and either way no later collection is asked. Failures are kept and said, never turned into "empty".
 * Cancellation is rethrown.
 */
object LitVmReader {
    suspend fun read(calls: LitVmCalls, verifyChain: suspend (LitVmCalls) -> Unit, owner: EvmAddress, tilePixels: Double,
                     collections: List<EvmAddress> = KnownCollections.on(calls.network),
                     known: Map<EvmAddress, Erc721Collection> = emptyMap()): LitVmRead {
        val start = calls.clock()
        val refresh = calls.until(start + LitVmModel.REFRESH_MS)
        try {
            verifyChain(refresh)
        } catch(e: Exception) {
            return failed(owner, e, LitVmRead.Problem.CHAIN)
        }
        val balance = try {
            refresh.balance(owner)
        } catch(e: Exception) {
            return failed(owner, e, LitVmRead.Problem.BALANCE)
        }
        var problem: LitVmRead.Problem? = null
        //Set only when the refresh's own deadline (not a collection's minute) ended a collection's read
        var cut = false
        val results = ArrayList<LitVmCollection>()
        for(contract in collections) {
            currentCoroutineContext().ensureActive()
            if(problem != null) {
                results.add(LitVmCollection(contract, null, LitVmCollection.Status.FAILED, emptyList(), 0, 0))
                continue
            }
            val now = calls.clock()
            if(now - start >= LitVmModel.REFRESH_MS) {
                results.add(LitVmCollection(contract, null, LitVmCollection.Status.NOT_REACHED, emptyList(), 0, 0))
                continue
            }
            //The collection's minute, or less when the refresh ends first: then the refresh's deadline is the one that binds
            val boundByRefresh = start + LitVmModel.REFRESH_MS - now <= LitVmModel.COLLECTION_MS
            val bounded = refresh.until(now + LitVmModel.COLLECTION_MS)
            var collection: Erc721Collection? = null
            val result = try {
                //Facts kept from this visit's earlier read are not asked again
                collection = known[contract] ?: Erc721.read(bounded, contract)
                //A built-in contract that does not answer as a collection is unread, never an empty one
                val read = collection
                if(read == null) {
                    LitVmCollection(contract, null, LitVmCollection.Status.FAILED, emptyList(), 0, 0)
                } else when(val listed = NftLister.list(bounded, read, owner)) {
                    is NftLister.Listed -> {
                        if(listed.rateLimited) problem = LitVmRead.Problem.RATE_LIMITED
                        LitVmCollection(contract, read, LitVmCollection.Status.LISTED, listed.tokens.toList(), listed.more, listed.failed)
                    }
                    else -> LitVmCollection(contract, read, LitVmCollection.Status.FAILED, emptyList(), 0, 0)
                }
            } catch(e: Exception) {
                val p = problem(e, null)
                if(p == LitVmRead.Problem.AIR_GAPPED || p == LitVmRead.Problem.RATE_LIMITED) problem = p
                LitVmCollection(contract, collection, LitVmCollection.Status.FAILED, emptyList(), 0, 0)
            }
            results.add(result)
            if(boundByRefresh && calls.clock() - start >= LitVmModel.REFRESH_MS &&
                (result.status == LitVmCollection.Status.FAILED || result.failed > 0)) {
                cut = true
            }
        }
        //Stopped: the refresh's three minutes left a collection unreached, or ended the one being read
        val stopped = problem == null && (cut || results.any { it.status == LitVmCollection.Status.NOT_REACHED })
        val pictures = LitVmPictures.prepareAll(LitVmModel.shownTokens(results).first, tilePixels)
        return LitVmRead(owner, balance, problem, null, results, stopped, pictures)
    }

    /** Cancellation goes on up; a rate limit and air-gapped mode are said as such; anything else is [otherwise]. */
    private fun problem(e: Exception, otherwise: LitVmRead.Problem?): LitVmRead.Problem? = when(e) {
        is CancellationException -> throw e
        is RateLimitedException -> LitVmRead.Problem.RATE_LIMITED
        is AirGappedException -> LitVmRead.Problem.AIR_GAPPED
        else -> otherwise
    }

    /** The chain check or the balance failed: air-gapped mode as such, anything else as [problem] with its reason. */
    private fun failed(owner: EvmAddress, e: Exception, stage: LitVmRead.Problem): LitVmRead {
        if(problem(e, stage) == LitVmRead.Problem.AIR_GAPPED) {
            return LitVmRead(owner, null, LitVmRead.Problem.AIR_GAPPED, null, emptyList(), false, emptyMap())
        }
        return LitVmRead(owner, null, stage, reason(e), emptyList(), false, emptyMap())
    }

    /**
     * Why it failed, as one of the app's reasons. The exception's message is never shown: the server writes most of it.
     * Only the client's own wrong-chain refusal, recognised by its exact words, is told apart; a server repeating those
     * words gets the same fixed sentence.
     */
    internal fun reason(e: Exception): LitVmRead.Reason = when(e) {
        is RateLimitedException -> LitVmRead.Reason.RATE_LIMITED
        is TimedOut, is HttpEndpoint.TookTooLong, is HttpEndpoint.DeadlinePassed, is java.net.SocketTimeoutException -> LitVmRead.Reason.TIMED_OUT
        is LitVmException -> if(e.message == LitVmModel.WRONG_CHAIN) LitVmRead.Reason.WRONG_CHAIN else LitVmRead.Reason.UNREACHABLE
        else -> LitVmRead.Reason.UNREACHABLE
    }
}

/**
 * One LitVM client for one read: none at all while air-gapped (null, nothing opened); otherwise [open]ed, used by
 * [block], and [close]d however the read ends. A socket read does not see cancellation, so a cancel closes the client at
 * once (from a closer started undispatched, so its finally always runs), which ends that read too.
 */
object LitVmClients {
    suspend fun <C, T> use(airGapped: Boolean, open: () -> C, close: (C) -> Unit, block: suspend (C) -> T): T? {
        if(airGapped) return null
        val client = open()
        return coroutineScope {
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    close(client)
                }
            }
            try {
                block(client)
            } finally {
                closer.cancel()
                close(client)
            }
        }
    }
}

/**
 * One run at a time per key: a caller asking while one runs shares its answer (or its failure). A run cancelled by its own
 * caller is not handed on: the next waiting caller runs it again, and a caller arriving after the cancel (the screen
 * opened again after it was left) runs at once rather than wait for the cancelled run to wind down.
 */
class Coalescer<K : Any, V> {
    private class Done<V>(val value: V?, val error: Throwable?)

    private class Entry<V>(val owner: Job?, val done: CompletableDeferred<Done<V>?>)

    private val inflight = java.util.concurrent.ConcurrentHashMap<K, Entry<V>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun run(key: K, block: suspend () -> V): V {
        while(true) {
            val mine = Entry<V>(currentCoroutineContext()[Job], CompletableDeferred())
            val other = inflight.putIfAbsent(key, mine)
            if(other != null) {
                if(other.owner?.isCancelled == true) {
                    //A run whose caller left: never joined; this caller takes the key, or tries again if another did
                    if(!inflight.replace(key, other, mine)) continue
                } else {
                    val done = other.done.await() ?: continue
                    done.error?.let { throw it }
                    return done.value as V
                }
            }
            try {
                val value = block()
                mine.done.complete(Done(value, null))
                return value
            } catch(e: CancellationException) {
                mine.done.complete(null)
                throw e
            } catch(e: Throwable) {
                mine.done.complete(Done(null, e))
                throw e
            } finally {
                inflight.remove(key, mine)
            }
        }
    }
}

/**
 * A thread-safe least-recently-used map bounded by entries and by bytes ([sizeOf] each), the least recently used out
 * first. A value larger than [maxBytes] is not kept.
 */
class SizedLru<V : Any>(private val capacity: Int, private val maxBytes: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<String, V>(16, 0.75f, true)
    private var total = 0L

    fun get(key: String): V? = synchronized(map) { map[key] }

    fun put(key: String, value: V) {
        synchronized(map) {
            val size = sizeOf(value)
            if(size > maxBytes) return
            map.put(key, value)?.let { total -= sizeOf(it) }
            total += size
            val eldest = map.entries.iterator()
            while((map.size > capacity || total > maxBytes) && eldest.hasNext()) {
                val entry = eldest.next()
                if(entry.key == key) continue
                total -= sizeOf(entry.value)
                eldest.remove()
            }
        }
    }

    fun bytes(): Long = synchronized(map) { total }
}
