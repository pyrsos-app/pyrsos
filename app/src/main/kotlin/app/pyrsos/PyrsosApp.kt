// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos

import android.app.Application
import android.util.Log
import app.pyrsos.data.DbKey
import app.pyrsos.data.Store
import app.pyrsos.nearby.Nearby
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PyrsosApp : Application() {
    /**
     * Background work. A failure in one exchange is logged, not allowed to crash the app: a crash
     * would also stop the relay until the app is opened again.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("Pyrsos", "background task failed", e) },
    )
    lateinit var messenger: Messenger
        private set
    lateinit var nearby: Nearby
        private set

    /** The hardware key of the database is gone for good: nothing is open, and the app offers a fresh start. */
    var dataLost = false
        private set

    // Who wants the radio. Main thread only.
    /** The app is on screen and set up: search at full rate. */
    var visible = false
    /** [RelayService] runs: keep relaying at low power while the app is closed. */
    var relayRunning = false
    var batteryLow = false

    /** Starts, slows or stops the radio to match the flags above. */
    fun updateRadio() {
        when {
            visible -> {
                nearby.setForeground(true)
                nearby.start()
            }
            relayRunning && !batteryLow -> {
                nearby.setForeground(false)
                nearby.start()
            }
            else -> nearby.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // A copy of the APK left from sharing the app; it is only needed while the share runs.
        scope.launch(Dispatchers.IO) { File(cacheDir, "share").deleteRecursively() }
        val outer = try {
            Store.outer(this)
        } catch (e: DbKey.LostException) {
            Log.e(TAG, "database key lost: ${e.message}")
            dataLost = true
            return
        }
        messenger = Messenger(this, outer)
        nearby = Nearby(this, messenger, scope)
        // Housekeeping, for as long as the process lives (the relay keeps it alive in the background).
        // A round that fails is logged and tried again next time; it must not end the loop.
        every(HOUSEKEEPING_MS) { messenger.resendUndelivered() }
        // Often, so messages set to disappear do so on time.
        every(EXPIRY_MS) { messenger.dropExpired() }
        every(ACKS_MS) { messenger.sealDueAcks() }
    }

    private fun every(periodMs: Long, round: suspend () -> Unit) = scope.launch {
        while (isActive) {
            try {
                round()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "housekeeping failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            delay(periodMs)
        }
    }

    private companion object {
        const val TAG = "Pyrsos"
        const val HOUSEKEEPING_MS = 6 * 60 * 60 * 1000L
        const val EXPIRY_MS = 60_000L
        const val ACKS_MS = 30_000L
    }
}
