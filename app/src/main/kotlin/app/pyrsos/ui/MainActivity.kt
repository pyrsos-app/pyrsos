// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.ui

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import app.pyrsos.ApkProvider
import app.pyrsos.BuildConfig
import app.pyrsos.Messenger
import app.pyrsos.PyrsosApp
import app.pyrsos.R
import app.pyrsos.RelayPrefs
import app.pyrsos.RelayService
import app.pyrsos.data.Store
import app.pyrsos.nearby.Nearby
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlin.system.exitProcess
import kotlinx.coroutines.withContext

sealed interface Screen {
    object Home : Screen
    object Add : Screen
    object Settings : Screen
    class Chat(val contactId: String) : Screen
}

/** Keeps the open screen when the activity is rebuilt, e.g. after a language change. */
private val ScreenSaver = Saver<Screen, String>(
    save = {
        when (it) {
            Screen.Home -> "home"
            Screen.Add -> "add"
            Screen.Settings -> "settings"
            is Screen.Chat -> "chat:" + it.contactId
        }
    },
    restore = {
        when {
            it == "add" -> Screen.Add
            it == "settings" -> Screen.Settings
            it.startsWith("chat:") -> Screen.Chat(it.removePrefix("chat:"))
            else -> Screen.Home
        }
    },
)

class MainActivity : ComponentActivity() {
    private val app get() = application as PyrsosApp
    private var themeMode by mutableStateOf(ThemeMode.AUTO)

    /** Null until known; true while the PIN screen must cover everything. */
    private var locked by mutableStateOf<Boolean?>(null)
    private var hasPin by mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguagePrefs.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keeps message contents out of screenshots and the recent-apps preview.
        // Debug builds allow screenshots so device tests can be recorded.
        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        enableEdgeToEdge()
        themeMode = ThemePrefs.load(this)
        // Nothing here is for autofill services to see or save: not the message, not the PIN.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (app.dataLost) {
            window.isNavigationBarContrastEnforced = false
            setContent { PyrsosTheme(themeMode) { DataLostScreen(onStartFresh = ::wipe) } }
            return
        }
        lifecycleScope.launch {
            hasPin = app.messenger.hasPin()
            locked = hasPin
        }
        // The system's bars at the top and bottom draw over the app with no scrim of their own.
        window.isNavigationBarContrastEnforced = false
        setContent {
            val night = isNight(themeMode)
            LaunchedEffect(night) {
                val style = if (night) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            PyrsosTheme(themeMode) {
                // A tap anywhere that is not a field or a button closes the keyboard. The message
                // field is a platform view, so its focus and the keyboard are released here too.
                val focus = LocalFocusManager.current
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTapGestures {
                            focus.clearFocus()
                            window.currentFocus?.clearFocus()
                            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
                        }
                    },
                ) {
                    Root(
                        themeMode = themeMode,
                        onTheme = {
                            themeMode = it
                            ThemePrefs.save(this@MainActivity, it)
                        },
                        onLanguage = {
                            if (it != LanguagePrefs.load(this@MainActivity)) {
                                LanguagePrefs.save(this@MainActivity, it)
                                recreate()
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (app.dataLost) return
        // With a PIN the identity is in the locked vault; that a PIN exists means it was set up.
        lifecycleScope.launch { if (app.messenger.isLocked || app.messenger.me() != null) onScreen() }
    }

    override fun onStop() {
        super.onStop()
        if (app.dataLost) return
        // Leaving the screen locks: the vault closes and the PIN is asked again next time.
        if (hasPin) {
            locked = true
            app.scope.launch { app.messenger.lock() }
        }
        app.visible = false
        app.updateRadio()
    }

    /** The app is on screen with an identity: radio at full rate, relay on if wanted. */
    private fun onScreen() {
        app.visible = true
        app.updateRadio()
        syncRelay()
    }

    /** Deletes everything, then closes the app; the next start is a first start. Every step runs even if another fails. */
    private fun wipe() {
        fun step(block: () -> Unit) {
            try {
                block()
            } catch (e: Throwable) {
                Log.w("Pyrsos", "wipe step failed: ${e.javaClass.simpleName}")
            }
        }
        step { RelayService.stop(this) }
        app.visible = false
        app.relayRunning = false
        if (!app.dataLost) step { app.nearby.stop() }
        // Not tied to the screen: the wipe must finish even if the activity goes away.
        app.scope.launch {
            try {
                if (app.dataLost) step { Store.destroy(applicationContext) } else app.messenger.wipe()
            } finally {
                withContext(Dispatchers.IO + NonCancellable) {
                    // The cache holds the shared APK copy too.
                    step { cacheDir.deleteRecursively() }
                    step { File(applicationInfo.dataDir, "shared_prefs").list()?.forEach { deleteSharedPreferences(it.removeSuffix(".xml")) } }
                    step { getSystemService(NotificationManager::class.java).deleteNotificationChannel(RelayService.CHANNEL) }
                }
                // Back at the first screen, as if just installed: an app that simply vanished would
                // tell whoever is watching that something happened.
                step {
                    startActivity(
                        Intent(this@MainActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    )
                }
                exitProcess(0)
            }
        }
    }

    /**
     * Sends a copy of this app's own APK to a phone nearby with Android's Bluetooth file transfer,
     * with no internet. The copy is the installed file itself, signed as it was. Only Bluetooth is
     * offered: other ways in the share list may need the internet or an account.
     */
    private fun shareApp() {
        lifecycleScope.launch {
            val apk = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                File(dir, "Pyrsos-${BuildConfig.VERSION_NAME}.apk").also { File(applicationInfo.sourceDir).copyTo(it, overwrite = true) }
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", apk)
            val send = Intent(Intent.ACTION_SEND)
                .setType(ApkProvider.ZIP)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The system's Bluetooth app: com.android.bluetooth, or a vendor's name with "bluetooth" in it.
            // It must be a system app: any other app could name itself "bluetooth" and be handed the APK.
            val bluetooth = packageManager.queryIntentActivities(send, 0)
                .firstOrNull {
                    "bluetooth" in it.activityInfo.packageName &&
                        it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                }
            if (bluetooth == null) {
                Toast.makeText(this@MainActivity, R.string.share_no_bluetooth, Toast.LENGTH_LONG).show()
                return@launch
            }
            startActivity(send.setClassName(bluetooth.activityInfo.packageName, bluetooth.activityInfo.name))
        }
    }

    private fun syncRelay() {
        if (RelayPrefs.load(this) && app.nearby.hasPermissions()) RelayService.start(this) else RelayService.stop(this)
    }

    @Composable
    private fun Root(themeMode: ThemeMode, onTheme: (ThemeMode) -> Unit, onLanguage: (Language) -> Unit) {
        val messenger = app.messenger
        val nearby = app.nearby
        var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Home) }
        var setupDone by remember { mutableStateOf<Boolean?>(null) }
        var relay by remember { mutableStateOf(RelayPrefs.load(this)) }
        var welcome by remember { mutableStateOf(!getSharedPreferences("ui", MODE_PRIVATE).getBoolean(WELCOME_SHOWN, false)) }
        // Set by the welcome dialog's PIN button: Settings then opens on the new-PIN dialog, once.
        var pinFromWelcome by remember { mutableStateOf(false) }
        var securityVersion by remember { mutableStateOf(0) }
        val security by produceState(Security(), securityVersion, hasPin, locked) {
            if (locked == false) value = Security(messenger.wipeAfterTen(), messenger.hasDuressPin(), messenger.autoDeleteDays())
        }
        val version by messenger.version.collectAsState()
        val me by produceState<Messenger.Me?>(null, setupDone, version, locked) {
            if (locked == false) value = messenger.me().also { setupDone = it != null }
        }

        // Once a permission is refused for good, Android no longer asks; only its settings can.
        var refusedForGood by remember { mutableStateOf(false) }
        val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            refusedForGood = result.any { (p, granted) -> !granted && !shouldShowRequestPermissionRationale(p) }
            nearby.stop()
            onScreen()
        }
        val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            nearby.stop()
            onScreen()
        }
        val actions = NearbyActions(
            grant = {
                if (refusedForGood) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                } else {
                    askPermissions.launch(Nearby.permissions().toTypedArray())
                }
            },
            turnOn = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
        )
        val nav: @Composable () -> Unit = {
            NavBar(
                current = when (screen) {
                    Screen.Add -> Tab.ADD
                    Screen.Settings -> Tab.SETTINGS
                    else -> Tab.CONTACTS
                },
                onTab = {
                    screen = when (it) {
                        Tab.CONTACTS -> Screen.Home
                        Tab.ADD -> Screen.Add
                        Tab.SETTINGS -> Screen.Settings
                    }
                },
            )
        }

        when (locked) {
            null -> return
            true -> return PinScreen(messenger, onUnlocked = { locked = false }, onWipe = ::wipe)
            false -> Unit
        }
        when (setupDone) {
            null -> Unit
            false -> SetupScreen { name ->
                lifecycleScope.launch {
                    messenger.createIdentity(name)
                    setupDone = true
                    onScreen()
                }
            }
            true -> {
                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                LaunchedEffect(screen) { if (screen != Screen.Settings) pinFromWelcome = false }
                if (welcome) {
                    val done = {
                        welcome = false
                        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean(WELCOME_SHOWN, true).apply()
                    }
                    WelcomeDialog(onPin = { done(); pinFromWelcome = true; screen = Screen.Settings }, onDismiss = done)
                }
                val radio by nearby.state.collectAsState()
                NearbyDialog(radio, actions)
                val stuck by nearby.stuck.collectAsState()
                StuckDialog(stuck, onSettings = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
                when (val s = screen) {
                    Screen.Home -> HomeScreen(messenger, nearby, onOpen = { screen = Screen.Chat(it) }, nav = nav)
                    Screen.Add -> AddContactScreen(messenger, onBack = { screen = Screen.Home }, onContact = { screen = Screen.Chat(it) }, nav = nav)
                    Screen.Settings -> SettingsScreen(
                        me?.name ?: "",
                        me?.code ?: "",
                        onRename = { lifecycleScope.launch { messenger.rename(it) } },
                        themeMode, onTheme, LanguagePrefs.load(this), onLanguage,
                        relay = relay,
                        onRelay = {
                            relay = it
                            RelayPrefs.save(this, it)
                            syncRelay()
                        },
                        hasPin = hasPin,
                        onPin = { pin ->
                            lifecycleScope.launch {
                                try {
                                    if (messenger.setPin(pin)) {
                                        hasPin = pin != null
                                    } else {
                                        Toast.makeText(this@MainActivity, R.string.pin_is_duress, Toast.LENGTH_LONG).show()
                                    }
                                } catch (_: IllegalStateException) {
                                    // Locked in the meantime: the PIN screen is up, nothing was changed.
                                }
                            }
                        },
                        wipeAfterTen = security.wipeAfterTen,
                        onWipeAfterTen = { on -> lifecycleScope.launch { messenger.setWipeAfterTen(on); securityVersion++ } },
                        hasDuress = security.hasDuress,
                        onDuress = { pin ->
                            lifecycleScope.launch {
                                if (!messenger.setDuressPin(pin)) {
                                    Toast.makeText(this@MainActivity, R.string.duress_same, Toast.LENGTH_LONG).show()
                                }
                                securityVersion++
                            }
                        },
                        autoDeleteDays = security.autoDeleteDays,
                        onAutoDelete = { days -> lifecycleScope.launch { messenger.setAutoDeleteDays(days); securityVersion++ } },
                        onWipe = ::wipe,
                        onTestData = { lifecycleScope.launch { messenger.addTestConversations() } },
                        onResendNow = { lifecycleScope.launch { messenger.resendUndelivered(now = true) } },
                        onShareApp = ::shareApp,
                        onBack = { screen = Screen.Home },
                        nav = nav,
                        startWithPin = pinFromWelcome,
                    )
                    is Screen.Chat -> ChatScreen(messenger, s.contactId, onBack = { screen = Screen.Home })
                }
            }
        }
    }
}

private const val WELCOME_SHOWN = "welcome_shown"

/** What the security settings show. */
class Security(val wipeAfterTen: Boolean = false, val hasDuress: Boolean = false, val autoDeleteDays: Int = 0)

class NearbyActions(val grant: () -> Unit, val turnOn: () -> Unit)
