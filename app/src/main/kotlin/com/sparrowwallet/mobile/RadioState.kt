package com.sparrowwallet.mobile

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** What could still reach the outside world while air-gapped. [bluetooth] is null when unreadable. */
data class Radios(val airplaneMode: Boolean, val transports: Set<String>, val bluetooth: Boolean?)

/** Broadcasts for radio switches the network callback does not see (Bluetooth, airplane mode with no network). */
val RADIO_CHANGE_ACTIONS = listOf(BluetoothAdapter.ACTION_STATE_CHANGED, Intent.ACTION_AIRPLANE_MODE_CHANGED)

private val TRANSPORT_ORDER = listOf("WiFi", "Mobile data", "Ethernet", "VPN", "Network")

/** "Radios on: ..." for the air-gapped pill, or null when the phone is fully offline. */
fun radioWarning(r: Radios): String? {
    val parts = buildList {
        if(!r.airplaneMode) add("Airplane mode off")
        TRANSPORT_ORDER.filter { it in r.transports }.forEach { add(it) }
        if(r.bluetooth == true) add("Bluetooth")
    }
    return if(parts.isEmpty()) null else "Radios on: " + parts.joinToString(", ")
}

fun readRadios(context: Context): Radios {
    val airplane = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    }.getOrDefault(false)
    val transports = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        buildSet {
            if(caps != null) {
                if(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WiFi")
                if(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Mobile data")
                if(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
                if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
                if(isEmpty()) add("Network")
            }
        }
    }.getOrDefault(emptySet())
    // no runtime permission is requested for this; if the platform refuses, leave it out
    val bluetooth = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
    }.getOrNull()
    return Radios(airplane, transports, bluetooth)
}

/** Radio state, re-read on resume, when the active network changes, and on Bluetooth or airplane mode switches. */
@Composable
fun rememberRadios(): State<Radios> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(readRadios(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_RESUME) state.value = readRadios(context)
        }
        lifecycle.addObserver(observer)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { state.value = readRadios(context) }
            override fun onLost(network: Network) { state.value = readRadios(context) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                state.value = readRadios(context)
            }
        }
        val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { state.value = readRadios(context) }
        }
        val filter = IntentFilter().apply { RADIO_CHANGE_ACTIONS.forEach { addAction(it) } }
        val receiving = runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        onDispose {
            lifecycle.removeObserver(observer)
            if(registered) runCatching { cm.unregisterNetworkCallback(callback) }
            if(receiving) runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return state
}
