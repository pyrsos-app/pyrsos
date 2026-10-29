// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.pyrsos.ui.LanguagePrefs
import app.pyrsos.ui.MainActivity

/**
 * Keeps trading envelopes with phones nearby while the app is closed, so messages travel from
 * pocket to pocket.
 *
 * Android stops Bluetooth work of an app that is not on screen unless it runs as a foreground
 * service, and a foreground service must show a notice. The notice here is as quiet as Android
 * allows: no sound, no vibration, lowest importance, hidden on the lock screen, and it never says
 * anything about messages. People still open the app to see whether something arrived.
 *
 * The radio itself runs in [app.pyrsos.nearby.Nearby]'s low-power mode while the screen is not
 * showing the app, and pauses while the battery is low.
 */
class RelayService : Service() {
    private val app get() = application as PyrsosApp

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            app.batteryLow = intent.action == Intent.ACTION_BATTERY_LOW
            app.updateRadio()
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LanguagePrefs.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        try {
            ServiceCompat.startForeground(this, NOTICE_ID, notice(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Refused, e.g. restarted by the system while Android does not allow it: the app
            // starts the relay again the next time it is opened.
            Log.w(TAG, "cannot run in the background: ${e.message}")
            stopSelf()
            return
        }
        ContextCompat.registerReceiver(
            this,
            battery,
            IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        app.batteryLow = batteryLowNow()
        app.relayRunning = true
        app.updateRadio()
    }

    /** Android sends "battery low" only on the way down, so check once where it is now. */
    private fun batteryLowNow(): Boolean {
        val bm = getSystemService(BatteryManager::class.java) ?: return false
        return !bm.isCharging && bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in 0..LOW_BATTERY_PERCENT
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (app.relayRunning) {
            unregisterReceiver(battery)
            app.relayRunning = false
            app.updateRadio()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notice(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, getString(R.string.relay_channel), NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.relay_notice))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    companion object {
        private const val TAG = "Pyrsos"
        private const val CHANNEL = "relay"
        private const val NOTICE_ID = 1
        private const val LOW_BATTERY_PERCENT = 15

        /** Starts the relay; only call while the app is on screen (Android refuses otherwise). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "cannot start the relay: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
}

/** Whether the relay runs when the app is closed. On unless the person turns it off. */
object RelayPrefs {
    private const val FILE = "ui"
    private const val KEY = "relay"

    fun load(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun save(context: Context, on: Boolean) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
}
