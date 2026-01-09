package com.iwo.mailclient.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Мониторинг состояния сети
 */
object NetworkMonitor {
    
    /**
     * Проверяет наличие сети синхронно
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    
    /**
     * Flow для отслеживания состояния сети в реальном времени
     * Использует BroadcastReceiver + NetworkCallback для мгновенной реакции
     */
    fun observeNetworkState(context: Context): Flow<Boolean> = callbackFlow {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // Отправляем текущее состояние
        trySend(isNetworkAvailable(context))
        
        // BroadcastReceiver для мгновенной реакции (как у Battery Saver)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                trySend(isNetworkAvailable(context))
            }
        }
        
        @Suppress("DEPRECATION")
        context.registerReceiver(
            receiver,
            IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        )
        
        // NetworkCallback как дополнительный источник
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }
            
            override fun onLost(network: Network) {
                trySend(isNetworkAvailable(context))
            }
            
            override fun onUnavailable() {
                trySend(false)
            }
        }
        
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
        }
        
        awaitClose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            try { connectivityManager.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
    }
}

/**
 * Composable для получения состояния сети в реальном времени
 */
@Composable
fun rememberNetworkState(): State<Boolean> {
    val context = LocalContext.current
    return produceState(initialValue = NetworkMonitor.isNetworkAvailable(context)) {
        NetworkMonitor.observeNetworkState(context).collect { value = it }
    }
}
