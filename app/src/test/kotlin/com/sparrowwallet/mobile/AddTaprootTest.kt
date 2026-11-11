package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.storage.VaultData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddTaprootTest {
    private val public = Member("w", VaultData(mnemonic = "abandon"))
    private val private = Member("w Private", VaultData(mnemonic = "abandon", scriptType = "MWEB"))
    private val taproot = Member("w Taproot", VaultData(mnemonic = "abandon", scriptType = "P2TR"))
    private val home = Screen.Home(WalletFamily(public, private), Layer.PUBLIC, "session password")

    @Test
    fun aRefusalIsShownAsIsAndNothingIsReopened() {
        var reopened = false
        val outcome = addTaprootAndReopen(home, "typed", add = { _, _ -> TaprootCompanion.WRONG_PASSWORD },
            reopen = { _, _, _ -> reopened = true; home })
        assertEquals(AddTaprootOutcome.Refused(TaprootCompanion.WRONG_PASSWORD), outcome)
        assertFalse(reopened)
    }

    /** The typed password goes only to the add; the family is reopened with the session's password, on the Public layer. */
    @Test
    fun theFamilyIsReopenedWithTheSessionPasswordOnThePublicLayer() {
        val added = mutableListOf<Pair<String, String>>()
        val reopenedWith = mutableListOf<Triple<String, VaultData, String>>()
        val outcome = addTaprootAndReopen(home, "typed",
            add = { name, password -> added += name to password; null },
            reopen = { name, data, password ->
                reopenedWith += Triple(name, data, password)
                Screen.Home(WalletFamily(public, private, taproot), Layer.PUBLIC, password)
            })
        assertEquals(listOf("w" to "typed"), added)
        assertEquals(listOf(Triple("w", public.data, "session password")), reopenedWith)
        val next = assertIs<AddTaprootOutcome.Added>(outcome).next
        assertEquals(Layer.PUBLIC, next.home.layer)
        assertEquals(taproot, next.home.family.taproot)
        assertEquals("session password", next.home.password)
        assertEquals(listOf(Layer.PUBLIC, Layer.TAPROOT), CollectiblesModel.shownLayers(next.home.family, next.home.layer))
    }

    @Test
    fun aReopenThatFailsStillLeavesTheScreenOnThePublicLayer() {
        val outcome = addTaprootAndReopen(home, "typed", add = { _, _ -> null }, reopen = { _, _, _ -> throw IllegalStateException("x") })
        assertEquals(Screen.Collectibles(home), assertIs<AddTaprootOutcome.Added>(outcome).next)
    }

    @Test
    fun anAddThatThrowsIsASaveFailure() {
        val outcome = addTaprootAndReopen(home, "typed", add = { _, _ -> throw IllegalStateException("disk") }, reopen = { _, _, _ -> home })
        assertEquals(AddTaprootOutcome.Refused(TaprootCompanion.SAVE_FAILED), outcome)
    }

    @Test
    fun thePromptTakesOneConfirmAtATime() {
        val prompt = PasswordPromptState()
        assertNull(prompt.begin(), "nothing to confirm while closed")
        prompt.show()
        assertTrue(prompt.open)
        prompt.typed = "secret"
        assertEquals("secret", prompt.begin())
        assertTrue(prompt.busy)
        //A second tap while the first runs takes nothing: one wallet is created
        assertNull(prompt.begin())
        //Nor can it be dismissed while it runs
        prompt.dismiss()
        assertTrue(prompt.open)
    }

    @Test
    fun aRefusalKeepsThePromptOpenWithTheError() {
        val prompt = PasswordPromptState()
        prompt.show()
        prompt.typed = "wrong"
        prompt.begin()
        prompt.refused(TaprootCompanion.WRONG_PASSWORD)
        assertTrue(prompt.open)
        assertFalse(prompt.busy)
        assertEquals("Wrong password", prompt.error)
        //and a new try clears the old error
        prompt.typed = "right"
        assertEquals("right", prompt.begin())
        assertNull(prompt.error)
    }

    @Test
    fun successAndDismissClearTheTypedPassword() {
        val prompt = PasswordPromptState()
        prompt.show()
        prompt.typed = "secret"
        prompt.begin()
        prompt.done()
        assertFalse(prompt.open)
        assertEquals("", prompt.typed)
        assertNull(prompt.error)

        prompt.show()
        prompt.typed = "half typed"
        prompt.dismiss()
        assertFalse(prompt.open)
        assertEquals("", prompt.typed)
        //opening again starts empty
        prompt.show()
        assertEquals("", prompt.typed)
        assertFalse(prompt.toString().contains("half typed"))
    }

    @Test
    fun thePromptNeverPrintsWhatIsTyped() {
        val prompt = PasswordPromptState()
        prompt.show()
        prompt.typed = "secret"
        assertFalse(prompt.toString().contains("secret"))
    }
}
