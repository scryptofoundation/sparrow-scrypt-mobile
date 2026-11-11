package com.sparrowwallet.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Restores a phrase from another coin's wallet app with that app's path and built-in passphrase.
 * The passphrase is never shown; the coin and address type pickers appear only when there is a choice.
 */
@Composable
internal fun OtherWalletRestoreScreen(
    repo: WalletRepository,
    /** Opens the restored wallet and its family (slow: called off the main thread). */
    openFamily: (name: String, password: String) -> Screen.Home,
    onRestored: (Screen.Home) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var app by remember { mutableStateOf(OtherWallets.ALL.first()) }
    var coin by remember { mutableStateOf(app.coins().first()) }
    var account by remember { mutableStateOf(app.accountsFor(coin).first()) }
    var includeMweb by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val defaultName = "${app.name} ${coin.displayName}"

    fun pickCoin(c: com.sparrowwallet.mobile.crypto.Coin) { coin = c; account = app.accountsFor(c).first(); error = null }

    ScreenColumn {
        Text("Restore from another wallet app", style = MaterialTheme.typography.headlineSmall)
        RadioList(OtherWallets.ALL, app, { it.name }) { app = it; pickCoin(it.coins().first()) }
        Text(app.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(app.coins().size > 1) {
            RadioList(app.coins(), coin, { "${it.displayName} (${it.ticker})" }) { pickCoin(it) }
        }
        if(app.accountsFor(coin).size > 1) {
            RadioList(app.accountsFor(coin), account, { it.label }) { account = it }
        }
        if(app.mwebOption && coin == com.sparrowwallet.mobile.crypto.Coin.LITECOIN) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { includeMweb = !includeMweb }) {
                Checkbox(checked = includeMweb, onCheckedChange = { includeMweb = it })
                Text("Include MWEB (Private) balance", style = MaterialTheme.typography.bodyMedium)
            }
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Wallet name") }, placeholder = { Text(defaultName) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = phrase, onValueChange = { phrase = it; error = null },
            label = { Text("Recovery phrase from ${app.name}") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Wallet password (encrypts on this device)") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = PasswordKeyboard,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                if(password.isEmpty()) { error = "Choose a password"; return@Button }
                busy = true; error = null
                scope.launch {
                    try {
                        val walletName = name.trim().ifEmpty { defaultName }
                        val restored = withContext(Dispatchers.Default) {
                            val vaults = OtherWallets.restoreVaults(app, account, phrase, walletName, includeMweb)
                            val saved = repo.restoreFromApp(vaults, password)
                            openFamily(saved, password)
                        }
                        onRestored(restored)
                    } catch(t: Throwable) {
                        error = t.message ?: "Restore failed"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && phrase.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if(busy) "Restoring…" else "Restore wallet") }
        BackButton(enabled = !busy, onClick = onBack)
    }
}
