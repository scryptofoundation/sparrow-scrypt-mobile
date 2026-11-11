package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.storage.VaultData

/** What "Add Taproot wallet" ended with: a refusal shown in the prompt as is, or the Collectibles screen to show next. */
internal sealed interface AddTaprootOutcome {
    data class Refused(val text: String) : AddTaprootOutcome
    /** [note]: a line to show on the screen, when the new wallet is not opened with the family this time. */
    data class Added(val next: Screen.Collectibles, val note: String? = null) : AddTaprootOutcome

    companion object {
        const val OPENS_LATER = "The Taproot wallet was added. It opens with the next unlock."
    }
}

/**
 * Adds the Taproot wallet of [home]'s public wallet with the password the user [typed], through [add] (the repository's
 * `addTaprootCompanion`), then reopens the family through [reopen] (the app's `homeFor`) from the public wallet's own
 * vault with the session's password [Screen.Home.password], never the typed one, so the new wallet joins the family
 * exactly as a fresh unlock would open it. Lands on Collectibles for the Public layer. A refusal reopens nothing. A
 * reopen that fails, or whose family lacks the new Taproot wallet or lost the Private one (a member that would not
 * unlock), is not taken: the family stays as it was and [AddTaprootOutcome.OPENS_LATER] says so. Slow (vaults are
 * opened and sealed): call it off the main thread. Nothing here syncs or contacts anything.
 */
internal fun addTaprootAndReopen(
    home: Screen.Home, typed: String,
    add: (parentName: String, password: String) -> String?,
    reopen: (name: String, data: VaultData, password: String) -> Screen.Home
): AddTaprootOutcome {
    val public = home.family.public
    val refused = try {
        add(public.name, typed)
    } catch(e: Exception) {
        TaprootCompanion.SAVE_FAILED
    }
    if(refused != null) return AddTaprootOutcome.Refused(refused)
    val reopened = runCatching { reopen(public.name, public.data, home.password) }.getOrNull()
        ?.takeIf { it.family.taproot != null && (home.family.private == null || it.family.private != null) }
        ?: return AddTaprootOutcome.Added(Screen.Collectibles(home.copy(layer = Layer.PUBLIC)), AddTaprootOutcome.OPENS_LATER)
    return AddTaprootOutcome.Added(Screen.Collectibles(Screen.Home(reopened.family, Layer.PUBLIC, reopened.password)))
}
