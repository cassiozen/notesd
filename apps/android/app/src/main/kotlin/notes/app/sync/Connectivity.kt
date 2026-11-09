package notes.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the phone has a network, as the system's default one comes and goes. */
class Connectivity {
    /** Settable for tests, which never [start] it. */
    val online = MutableStateFlow(true)

    private val _available = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** A network became the default: back online, or another one (Wi-Fi to mobile data). */
    val available: SharedFlow<Unit> = _available

    fun start(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        online.value = manager.activeNetwork != null
        manager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    online.value = true
                    _available.tryEmit(Unit)
                }

                override fun onLost(network: Network) {
                    online.value = false
                }
            }
        )
    }
}
