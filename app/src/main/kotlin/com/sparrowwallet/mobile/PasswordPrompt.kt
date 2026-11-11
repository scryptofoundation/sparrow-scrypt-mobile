package com.sparrowwallet.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** A password field's keyboard: a password keyboard with no autocorrect, so nothing typed is suggested or learnt. */
internal val PasswordKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

/**
 * A password dialog's state: whether it is [open], what is [typed], the [error] shown and whether its confirm is [busy].
 * The typed password lives only here: cleared when the dialog closes, never printed.
 */
internal class PasswordPromptState {
    var open by mutableStateOf(false)
        private set
    var typed by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    fun show() {
        typed = ""
        error = null
        open = true
    }

    /** Closes and forgets what was typed; ignored while a confirm runs. */
    fun dismiss() {
        if(busy) return
        close()
    }

    /** The typed password for one confirm, or null while closed or while one runs (a second tap does nothing). */
    fun begin(): String? {
        if(!open || busy) return null
        busy = true
        error = null
        return typed
    }

    /** The confirm was refused: [text] shows in the dialog as is, and it stays open. */
    fun refused(text: String) {
        busy = false
        error = text
    }

    /** The confirm went through: the dialog closes and what was typed is forgotten. */
    fun done() {
        busy = false
        close()
    }

    private fun close() {
        typed = ""
        error = null
        open = false
    }

    override fun toString(): String = "PasswordPromptState(open=$open, busy=$busy)"
}

/**
 * A small modal password dialog: [title], an optional [text], one password field, the error line, Cancel and [confirm].
 * While [PasswordPromptState.busy] the field and both buttons are off and it cannot be dismissed (Back included).
 */
@Composable
internal fun PasswordPrompt(state: PasswordPromptState, title: String, confirm: String, onConfirm: (String) -> Unit, text: String? = null) {
    if(!state.open) return
    AlertDialog(
        onDismissRequest = { state.dismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                text?.let { Text(it) }
                OutlinedTextField(
                    value = state.typed, onValueChange = { state.typed = it },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !state.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = PasswordKeyboard,
                    modifier = Modifier.fillMaxWidth()
                )
                state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !state.busy, onClick = { state.begin()?.let(onConfirm) }) { Text(confirm) }
        },
        dismissButton = {
            TextButton(enabled = !state.busy, onClick = { state.dismiss() }) { Text("Cancel") }
        }
    )
}
