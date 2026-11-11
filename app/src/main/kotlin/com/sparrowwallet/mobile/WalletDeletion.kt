package com.sparrowwallet.mobile

import java.io.File
import java.io.IOException

/** The line under the delete warning when the wallet has linked wallets. */
const val LINKED_SEED_WORDS = "Linked wallets can have their own seed words."

/** What the delete confirmation says when a delete did not finish. */
const val DELETE_FAILED = "The wallet could not be deleted."

/**
 * One answer of the delete confirmation: its [label], the exact wallets it deletes ([names], companions before the
 * public wallet) and the public wallet of the family it was offered for ([root]).
 */
data class DeleteChoice(val label: String, val names: List<String>, val root: String) {
    companion object {
        /** A wallet on its own, deleted alone: what the confirmation of a wallet without linked wallets does. */
        fun only(name: String) = DeleteChoice("Only $name", listOf(name), name)
    }
}

/**
 * The family [tapped] belongs to among [wallets], by name with its layer: the public wallet, then its Private and Taproot
 * wallets, each its companion as the list shows it ([companionOf]). Empty when the wallet has no linked wallets (a
 * one-sided link counts as none, but for an older Private pair), so the confirmation stays as it is for a wallet on its own.
 */
fun deleteFamily(tapped: WalletRepository.WalletInfo, wallets: List<WalletRepository.WalletInfo>): List<Pair<String, Layer>> {
    val byName = wallets.associateBy { it.name }
    val root = tapped.links.parent?.let { byName[it] }?.takeIf { linkedBack(it, tapped) } ?: tapped
    val companions = listOfNotNull(root.links.mweb?.let { it to Layer.PRIVATE }, root.links.taproot?.let { it to Layer.TAPROOT })
        .filter { (name, _) -> byName[name]?.let { companionOf(root, it, wallets) } == true }
    return if(companions.isEmpty()) emptyList() else listOf(root.name to Layer.PUBLIC) + companions
}

/**
 * The delete confirmation's choices for [tapped]: none for a wallet without linked wallets; otherwise "Only NAME" for
 * each member of its family (a companion with no row of its own among them), then "NAME and its linked wallets" for the
 * public wallet, which deletes the whole family. Each choice carries the names it deletes, so what is shown is deleted.
 */
fun deleteChoices(tapped: WalletRepository.WalletInfo, wallets: List<WalletRepository.WalletInfo>): List<DeleteChoice> {
    val family = deleteFamily(tapped, wallets)
    if(family.isEmpty()) return emptyList()
    val root = family.first().first
    val companions = family.drop(1).map { it.first }
    return family.map { (name, _) -> DeleteChoice("Only $name", listOf(name), root) } +
        DeleteChoice("$root and its linked wallets", companions + root, root)
}

/**
 * Deletes the wallets [choice] names, companions first and the public wallet last; true when every one of them went.
 * A name that is no longer a member of the family (its link changed since the choice was offered) is kept, not deleted,
 * and the result is false. A failed write or file delete stops the delete, and the result is false.
 *
 * Each wallet goes in four steps: the wallets that name it as their parent stop naming it (so the companions of a
 * public wallet deleted alone become ordinary rows, opened with their own password), it stops naming its parent, no
 * wallet names it as a companion any more, then its files go (caches, then vault, then meta). Stopped after any step,
 * nothing is hidden: a wallet is either still linked both ways under its listed parent, or listed on its own; a
 * wallet whose vault went is gone from the list, its leftover meta unread.
 */
fun deleteWallets(files: CompanionFiles, choice: DeleteChoice): Boolean {
    return try {
        val wallets = files.wallets()
        val root = wallets.firstOrNull { it.name == choice.root }
        val members = root?.let { r -> deleteFamily(r, wallets).map { it.first }.ifEmpty { listOf(r.name) } }.orEmpty()
        val names = choice.names.filter { it in members }
        val ordered = names.filter { it != choice.root } + names.filter { it == choice.root }
        for(name in ordered) deleteOne(files, name)
        names.size == choice.names.size
    } catch(e: Exception) {
        false
    }
}

private fun deleteOne(files: CompanionFiles, name: String) {
    val wallets = files.wallets()
    for(w in wallets) {
        if(w.name != name && w.links.parent == name) files.rewriteMeta(w.copy(links = w.links.copy(parent = null)))
    }
    wallets.firstOrNull { it.name == name }?.let { self ->
        if(self.links.parent != null) files.rewriteMeta(self.copy(links = self.links.copy(parent = null)))
    }
    for(w in files.wallets()) {
        if(w.name != name && name in w.links.companions) files.rewriteMeta(w.copy(links = w.links.without(name)))
    }
    files.deleteOne(name)
}

private fun WalletLinks.without(name: String) =
    copy(mweb = mweb?.takeIf { it != name }, taproot = taproot?.takeIf { it != name })

/**
 * Deletes a wallet's own files in [dir]: its vault, then its meta (a vault is never left without its meta), then any
 * temporary file a stopped [writeAtomically] left (`<name>.vault...tmp`, `<name>.meta...tmp`; a wallet name has no dot,
 * so these are its own). A file that exists and will not go throws, before anything after it is touched.
 */
internal fun deleteWalletFiles(dir: File, name: String) {
    fun remove(f: File) {
        if(f.exists() && !f.delete()) throw IOException("Could not delete ${f.name}")
    }
    remove(File(dir, "$name.vault"))
    remove(File(dir, "$name.meta"))
    dir.listFiles { f -> (f.name.startsWith("$name.vault.") || f.name.startsWith("$name.meta.")) && f.name.endsWith(".tmp") }
        .orEmpty().forEach { remove(it) }
}
