// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package app.pyrsos.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.doAfterTextChanged
import app.pyrsos.BuildConfig
import app.pyrsos.Messenger
import app.pyrsos.R
import app.pyrsos.core.Payload
import app.pyrsos.nearby.Nearby
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
private fun Bar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    brand: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = {
            if (brand) {
                Brand()
            } else {
                Column {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) IdentityCodeText(subtitle)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back), fontSize = 16.sp) }
        },
        actions = { actions() },
        // Solid, in the screen's own colour (black at night), also behind the status bar.
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

/** An identity code in a fixed-width face, so codes line up and read character by character. */
@Composable
private fun IdentityCodeText(code: String, large: Boolean = false) {
    Text(
        code,
        fontFamily = FontFamily.Monospace,
        style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodySmall,
        color = if (large) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// Setup ------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(onDone: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    // With the keyboard open the logo shrinks and the page scrolls, so the button stays reachable.
    val keyboard = WindowInsets.isImeVisible
    Scaffold { pad ->
        // The bar insets are already in [pad]: consumed here, they are not added again with the
        // keyboard's, which left an empty band over the button.
        BoxWithConstraints(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Brand(height = if (keyboard) 48.dp else 88.dp, stacked = true)
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.setup_text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 24) name = it },
                label = { Text(stringResource(R.string.setup_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onDone(name) },
                enabled = Messenger.cleanName(name) != "?",
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.setup_start), fontSize = 18.sp) }
        }
        }
    }
}

// Home -------------------------------------------------------------------------------------------

@Composable
fun HomeScreen(
    messenger: Messenger,
    nearby: Nearby,
    onOpen: (String) -> Unit,
    nav: @Composable () -> Unit,
) {
    val version by messenger.version.collectAsState()
    val contacts by produceState(emptyList<Messenger.ContactRow>(), version) { value = messenger.contacts() }
    val state by nearby.state.collectAsState()

    Scaffold(
        topBar = {
            Bar(stringResource(R.string.app_name), brand = true) {
                (state as? Nearby.State.Running)?.let { NearbyBadge(it.peers) }
            }
        },
        bottomBar = nav,
    ) { pad ->
        // The list runs under the see-through bars; the padding only keeps its ends clear of them.
        Column(Modifier.fillMaxSize()) {
            if (contacts.isEmpty()) {
                Column(Modifier.padding(pad).fillMaxSize()) {
                    PassiveNote(state)
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.home_empty), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
                    item { PassiveNote(state) }
                    items(contacts, key = { it.id }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        c.name,
                                        Modifier.weight(1f, fill = false),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IdentityChip(c.code)
                                }
                                val sub = if (!c.confirmed) stringResource(R.string.home_not_finished) else c.lastText
                                if (sub != null) {
                                    Text(
                                        sub,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (c.unread > 0) {
                                Box(
                                    Modifier.padding(start = 12.dp).size(26.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) { Text("${c.unread}", color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp) }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** On Android 10 and 11, why this phone only waits to be found. Once closed, it stays closed. */
@Composable
private fun PassiveNote(state: Nearby.State) {
    val prefs = LocalContext.current.getSharedPreferences("ui", Context.MODE_PRIVATE)
    var closed by remember { mutableStateOf(prefs.getBoolean(PASSIVE_NOTE_CLOSED, false)) }
    if (closed || state !is Nearby.State.Running || state.searching) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 20.dp, top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
            Text(
                stringResource(R.string.status_passive),
                Modifier.weight(1f).padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = {
                closed = true
                prefs.edit().putBoolean(PASSIVE_NOTE_CLOSED, true).apply()
            }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) }
        }
    }
}

private const val PASSIVE_NOTE_CLOSED = "passive_note_closed"

/**
 * Without Bluetooth nothing arrives, so a missing radio or permission is a dialog in the middle of
 * the screen, not a line at the top. "Later" hides it until the state changes again.
 */
@Composable
fun NearbyDialog(state: Nearby.State, actions: NearbyActions) {
    var hiddenFor by rememberSaveable { mutableStateOf<String?>(null) }
    val key = state::class.simpleName
    if (state is Nearby.State.Running || state is Nearby.State.Stopped) {
        hiddenFor = null
        return
    }
    if (hiddenFor == key) return
    val (title, text, action) = when (state) {
        Nearby.State.BluetoothOff -> Triple(R.string.status_bt_off, R.string.bt_needed, R.string.status_bt_turn_on to actions.turnOn)
        Nearby.State.NeedsPermission -> Triple(R.string.permission_title, R.string.status_permission, R.string.status_grant to actions.grant)
        else -> Triple(R.string.unsupported_title, R.string.status_unsupported, null)
    }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(text), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            if (action != null) {
                Button(onClick = action.second) { Text(stringResource(action.first)) }
            } else {
                TextButton(onClick = { hiddenFor = key }) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = if (action != null) {
            { TextButton(onClick = { hiddenFor = key }) { Text(stringResource(R.string.later)) } }
        } else {
            null
        },
    )
}

/** Bluetooth calls keep failing although phones are heard: ask for Bluetooth off and on again. */
@Composable
fun StuckDialog(stuck: Boolean, onSettings: () -> Unit) {
    var hidden by rememberSaveable { mutableStateOf(false) }
    if (!stuck) {
        hidden = false
        return
    }
    if (hidden) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.stuck_title)) },
        text = { Text(stringResource(R.string.stuck_text), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { Button(onClick = { hidden = true; onSettings() }) { Text(stringResource(R.string.stuck_action)) } },
        dismissButton = { TextButton(onClick = { hidden = true }) { Text(stringResource(R.string.later)) } },
    )
}

/** Shown once, after the first setup: what to know before relying on Pyrsos. */
@Composable
fun WelcomeDialog(onPin: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.welcome_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(R.string.welcome_bluetooth, R.string.welcome_contacts, R.string.welcome_open, R.string.welcome_pin).forEach {
                    Text(stringResource(it), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = { Button(onClick = onPin) { Text(stringResource(R.string.pin_set)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

enum class Tab { CONTACTS, ADD, SETTINGS }

/** The menu at the bottom of the main screens: a floating bar, with adding a contact in the middle. */
@Composable
fun NavBar(current: Tab, onTab: (Tab) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val go = { tab: Tab ->
        if (tab != current) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onTab(tab)
    }
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(36.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shadowElevation = 8.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavItem(current == Tab.CONTACTS, Icons.Filled.Person, stringResource(R.string.tab_contacts)) { go(Tab.CONTACTS) }
                Surface(
                    onClick = { go(Tab.ADD) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = if (current == Tab.ADD) 0.dp else 4.dp,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.tab_add),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                NavItem(current == Tab.SETTINGS, Icons.Filled.Settings, stringResource(R.string.settings_title)) { go(Tab.SETTINGS) }
            }
        }
    }
}

@Composable
private fun NavItem(selected: Boolean, icon: ImageVector, label: String, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            // Both sides the same width, whatever the label, so the + sits in the middle.
            .width(96.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** A thin outline, visible on both the white day and the black night background. */
@Composable
private fun hairline() = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))

/** How many Pyrsos phones are in reach, in a small frame with a dot: green when there is one. */
@Composable
private fun NearbyBadge(peers: Int) {
    Surface(shape = RoundedCornerShape(50), border = hairline(), color = Color.Transparent, modifier = Modifier.padding(end = 12.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).background(
                    if (peers > 0) NEARBY_GREEN else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    CircleShape,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.status_nearby, peers), style = MaterialTheme.typography.labelLarge)
        }
    }
}

private val NEARBY_GREEN = Color(0xFF3FB950)

/** A contact's identity code as a small framed tag next to the name. */
@Composable
private fun IdentityChip(code: String) {
    Surface(shape = RoundedCornerShape(6.dp), border = hairline(), color = Color.Transparent) {
        Text(
            code,
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Adding a contact -------------------------------------------------------------------------------

private sealed interface AddStep {
    object Choose : AddStep
    class Showing(val code: String) : AddStep
    class Answering(val code: String, val contactId: String, val name: String, val identityCode: String) : AddStep
}

@Composable
fun AddContactScreen(messenger: Messenger, onBack: () -> Unit, onContact: (String) -> Unit, nav: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<AddStep>(AddStep.Choose) }
    var error by remember { mutableStateOf<Int?>(null) }
    var scanning by remember { mutableStateOf(false) }

    fun handle(text: String) {
        scanning = false
        scope.launch {
            when (val r = messenger.scanned(text, showing = step is AddStep.Showing)) {
                is Messenger.ScanResult.ShowReply -> {
                    error = null
                    step = AddStep.Answering(r.code, r.contactId, r.name, r.identityCode)
                }
                is Messenger.ScanResult.Completed -> onContact(r.contactId)
                is Messenger.ScanResult.Refused -> error = when (r.reason) {
                    Messenger.ScanResult.Reason.NOT_PYRSOS -> R.string.err_not_pyrsos
                    Messenger.ScanResult.Reason.OTHER_VERSION -> R.string.err_version
                    Messenger.ScanResult.Reason.OWN_CODE -> R.string.err_own
                    Messenger.ScanResult.Reason.FORGED -> R.string.err_forged
                    Messenger.ScanResult.Reason.REPLY_WITHOUT_INVITE -> R.string.err_reply_without_invite
                    Messenger.ScanResult.Reason.REPLY_DOES_NOT_MATCH -> R.string.err_bad_reply
                    Messenger.ScanResult.Reason.STALE_INVITE -> R.string.err_stale_invite
                    Messenger.ScanResult.Reason.ALREADY_CONTACT -> R.string.err_already_contact
                    Messenger.ScanResult.Reason.BOTH_SHOWING -> R.string.err_both_showing
                }
            }
        }
    }
    val context = LocalContext.current
    val askCamera = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else error = R.string.err_camera
    }
    fun startScan() {
        error = null
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanning = true
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    val leave = {
        if (scanning) {
            scanning = false
        } else {
            scope.launch { if (step is AddStep.Showing) messenger.cancelInvite() }
            onBack()
        }
    }
    androidx.activity.compose.BackHandler(onBack = leave)

    Scaffold(topBar = { Bar(stringResource(R.string.add_title), onBack = leave) }, bottomBar = nav) { pad ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            if (scanning) {
                Scanner(onCode = ::handle)
                OutlinedButton(onClick = { scanning = false }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(stringResource(R.string.add_scan_cancel), fontSize = 18.sp)
                }
                return@Column
            }
            when (val s = step) {
                AddStep.Choose -> {
                    Text(stringResource(R.string.add_intro), style = MaterialTheme.typography.bodyLarge)
                    Button(
                        onClick = { scope.launch { error = null; step = AddStep.Showing(messenger.newInvite()) } },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text(stringResource(R.string.add_show), fontSize = 18.sp) }
                    OutlinedButton(onClick = ::startScan, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(stringResource(R.string.add_scan), fontSize = 18.sp)
                    }
                }
                is AddStep.Showing -> {
                    QrCode(s.code)
                    Text(stringResource(R.string.add_show_steps), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = ::startScan, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(stringResource(R.string.add_scan_reply), fontSize = 18.sp)
                    }
                }
                is AddStep.Answering -> {
                    Column {
                        Text(s.name, style = MaterialTheme.typography.titleLarge)
                        IdentityCodeText(s.identityCode, large = true)
                    }
                    QrCode(s.code)
                    Text(stringResource(R.string.add_reply_steps), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { onContact(s.contactId) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(stringResource(R.string.add_done), fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

// Chat -------------------------------------------------------------------------------------------

private enum class Forget { CHAT, CONTACT }

/** Asks before something that cannot be undone; the action button is in the error colour. */
@Composable
fun ConfirmDialog(title: String, text: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ChatScreen(messenger: Messenger, contactId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Forget?>(null) }
    val version by messenger.version.collectAsState()
    val contact by produceState<Messenger.ContactRow?>(null, version) { value = messenger.contact(contactId) }
    val messages by produceState(emptyList<Messenger.MessageRow>(), version) { value = messenger.messages(contactId) }
    // Not saved: a draft must not outlive a lock, nor sit in the saved state of the activity.
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    // Messages with a date line before the first one of each day.
    val rows = remember(messages) { withDays(messages) }

    LaunchedEffect(messages.size) {
        if (messages.any { !it.outgoing }) messenger.markRead(contactId)
        // The last message, after the "not finished" note when it shows.
        if (messages.isNotEmpty()) list.scrollToItem(rows.size - 1 + if (contact?.confirmed == false) 1 else 0)
    }

    confirm?.let { what ->
        ConfirmDialog(
            title = stringResource(if (what == Forget.CHAT) R.string.chat_clear else R.string.chat_remove),
            text = stringResource(if (what == Forget.CHAT) R.string.chat_clear_note else R.string.chat_remove_note),
            action = stringResource(R.string.delete),
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                scope.launch {
                    // Locked in the meantime or already gone: nothing to forget.
                    try {
                        if (what == Forget.CHAT) messenger.clearChat(contactId) else messenger.removeContact(contactId)
                    } catch (_: IllegalStateException) {
                    } catch (_: NoSuchElementException) {
                    }
                    if (what == Forget.CONTACT) onBack()
                }
            },
        )
    }

    Scaffold(
        topBar = {
            Bar(contact?.name ?: "", onBack = onBack, subtitle = contact?.code) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_clear)) }, onClick = { menu = false; confirm = Forget.CHAT })
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_remove)) }, onClick = { menu = false; confirm = Forget.CONTACT })
                    }
                }
            }
        },
        bottomBar = {
            Composer(
                draft = draft,
                onDraft = { draft = it },
                onSend = {
                    val text = draft
                    draft = ""
                    scope.launch {
                        // Locked as it was sent, or the contact just removed: keep what was written.
                        try {
                            messenger.send(contactId, text)
                        } catch (_: IllegalStateException) {
                            if (draft.isEmpty()) draft = text
                        } catch (_: NoSuchElementException) {
                            if (draft.isEmpty()) draft = text
                        }
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = pad.calculateTopPadding() + 8.dp,
                bottom = pad.calculateBottomPadding() + 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val c = contact
            if (c != null && !c.confirmed) item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.chat_not_finished, c.name),
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (messages.isEmpty()) item {
                Text(
                    stringResource(R.string.chat_empty),
                    Modifier.fillMaxWidth().padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is ChatRow.Day -> DayLine(row.day)
                    is ChatRow.Message -> Bubble(row.m, timeFormat.format(Date(row.m.at)))
                }
            }
        }
    }
}

/**
 * Writing a message: a roomy rounded field that grows to a few lines, and a round send button.
 * It sits on a solid strip (black at night) above the keyboard and the system's navigation bar.
 */
@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, onSend: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (draft.length > Payload.MAX_TEXT_CHARS - 100) {
                Text(
                    stringResource(R.string.chat_counter, draft.length, Payload.MAX_TEXT_CHARS),
                    Modifier.padding(start = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                PrivateTextField(
                    value = draft,
                    onValueChange = onDraft,
                    hint = stringResource(R.string.chat_hint),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onSend()
                    },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
                }
            }
        }
    }
}

private sealed class ChatRow(val key: String) {
    class Day(val day: LocalDate) : ChatRow("day-${day.toEpochDay()}")
    class Message(val m: Messenger.MessageRow) : ChatRow(m.id)
}

private fun withDays(messages: List<Messenger.MessageRow>): List<ChatRow> {
    val zone = ZoneId.systemDefault()
    val out = ArrayList<ChatRow>(messages.size + 8)
    var last: LocalDate? = null
    for (m in messages) {
        val day = Instant.ofEpochMilli(m.at).atZone(zone).toLocalDate()
        if (day != last) out += ChatRow.Day(day)
        last = day
        out += ChatRow.Message(m)
    }
    return out
}

/** "Today", "Yesterday", or the date (with the year only when it is not this year), centred. */
@Composable
private fun DayLine(day: LocalDate) {
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now()
    val label = when (day) {
        today -> stringResource(R.string.day_today)
        today.minusDays(1) -> stringResource(R.string.day_yesterday)
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEEE d MMMM" else "d MMMM yyyy", locale))
            .replaceFirstChar { it.titlecase(locale) }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                label,
                Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Bubble(m: Messenger.MessageRow, time: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (m.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (m.outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                val status = when {
                    !m.outgoing -> time
                    m.delivered -> "$time · " + stringResource(R.string.msg_delivered)
                    m.handed > 0 -> "$time · " + pluralStringResource(R.plurals.msg_passed, m.handed, m.handed)
                    else -> "$time · " + stringResource(R.string.msg_waiting)
                }
                Text(status, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Normal)
            }
        }
    }
}

// Settings ---------------------------------------------------------------------------------------

/**
 * The Bluetooth screen that has the name on it. On Pixel-like phones the public Bluetooth action
 * opens pairing instead; the screen with the name answers to the dashboard action, which other
 * phones (Samsung) do not have.
 */
private fun bluetoothNameSettings(context: Context): Intent {
    val dashboard = Intent("android.settings.BLUETOOTH_DASHBOARD_SETTINGS")
    return if (dashboard.resolveActivity(context.packageManager) != null) dashboard else Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
}

/** This phone's Bluetooth name, which phones that connect to it may read; null if not allowed. */
@SuppressLint("MissingPermission")
private fun bluetoothName(context: Context): String? = try {
    context.getSystemService(BluetoothManager::class.java)?.adapter?.name
} catch (_: SecurityException) {
    null
}

@Composable
fun SettingsScreen(
    name: String,
    code: String,
    onRename: (String) -> Unit,
    mode: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    language: Language,
    onLanguage: (Language) -> Unit,
    relay: Boolean,
    onRelay: (Boolean) -> Unit,
    hasPin: Boolean,
    onPin: (String?) -> Unit,
    wipeAfterTen: Boolean,
    onWipeAfterTen: (Boolean) -> Unit,
    hasDuress: Boolean,
    onDuress: (String?) -> Unit,
    autoDeleteDays: Int,
    onAutoDelete: (Int) -> Unit,
    onWipe: () -> Unit,
    onTestData: () -> Unit,
    onResendNow: () -> Unit,
    onShareApp: () -> Unit,
    onBack: () -> Unit,
    nav: @Composable () -> Unit,
    /** Opens with the new-PIN dialog already showing (from the welcome dialog's button). */
    startWithPin: Boolean = false,
) {
    var pinDialog by remember { mutableStateOf(startWithPin) }
    var duressDialog by remember { mutableStateOf(false) }
    var wipeDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    if (pinDialog) {
        // Without a screen lock the hardware secret opens at any time, so the PIN alone guards it.
        val secure = remember { context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure ?: true }
        NewPinDialog(
            stringResource(R.string.pin_set),
            warning = if (secure) null else stringResource(R.string.pin_no_screen_lock),
            onDismiss = { pinDialog = false },
            onSet = { pinDialog = false; onPin(it) },
        )
    }
    if (duressDialog) {
        NewPinDialog(stringResource(R.string.duress_title), onDismiss = { duressDialog = false }, onSet = { duressDialog = false; onDuress(it) })
    }
    if (wipeDialog) {
        ConfirmDialog(
            title = stringResource(R.string.wipe_title),
            text = stringResource(R.string.wipe_note),
            action = stringResource(R.string.wipe_action),
            onDismiss = { wipeDialog = false },
            onConfirm = { wipeDialog = false; onWipe() },
        )
    }
    Scaffold(topBar = { Bar(stringResource(R.string.settings_title), onBack = onBack) }, bottomBar = nav) { pad ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section(stringResource(R.string.section_you)) {
                Setting(stringResource(R.string.settings_name), stringResource(R.string.settings_name_note)) {
                    var editing by rememberSaveable { mutableStateOf(false) }
                    var saved by rememberSaveable { mutableStateOf(false) }
                    if (editing) {
                        RenameDialog(
                            name = name,
                            onDismiss = { editing = false },
                            onSave = {
                                editing = false
                                saved = true
                                onRename(it)
                            },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        OutlinedButton(onClick = { editing = true }) { Text(stringResource(R.string.settings_name_change)) }
                    }
                    if (saved) {
                        Text(stringResource(R.string.settings_name_saved), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Setting(stringResource(R.string.settings_code), stringResource(R.string.settings_code_note)) {
                    IdentityCodeText(code, large = true)
                }
                Setting(stringResource(R.string.bt_name_title), stringResource(R.string.bt_name_note)) {
                    val btName = remember { bluetoothName(context) }
                    Text(btName ?: stringResource(R.string.bt_name_unknown), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { context.startActivity(bluetoothNameSettings(context)) }) {
                        Text(stringResource(R.string.bt_name_change))
                    }
                }
            }

            Section(stringResource(R.string.section_messages)) {
                Setting(stringResource(R.string.autodelete_title), stringResource(R.string.autodelete_note)) {
                    ChipRow {
                        listOf(0 to R.string.autodelete_never, 1 to R.string.autodelete_day, 7 to R.string.autodelete_week, 30 to R.string.autodelete_month)
                            .forEach { (days, label) ->
                                FilterChip(selected = autoDeleteDays == days, onClick = { onAutoDelete(days) }, label = { Text(stringResource(label)) })
                            }
                    }
                }
                Setting(stringResource(R.string.settings_relay), stringResource(R.string.settings_relay_note)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (relay) R.string.state_on else R.string.state_off),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        val haptic = LocalHapticFeedback.current
                        Switch(
                            checked = relay,
                            onCheckedChange = {
                                haptic.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                onRelay(it)
                            },
                        )
                    }
                }
            }

            Section(stringResource(R.string.section_security)) {
                Setting(stringResource(R.string.pin_title), stringResource(if (hasPin) R.string.pin_note else R.string.pin_note_off)) {
                    Text(
                        stringResource(if (hasPin) R.string.pin_is_set else R.string.pin_not_set),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pinDialog = true }) {
                            Text(stringResource(if (hasPin) R.string.pin_change else R.string.pin_set))
                        }
                        if (hasPin) TextButton(onClick = { onPin(null) }) { Text(stringResource(R.string.pin_remove)) }
                    }
                }
                if (hasPin) {
                    Setting(stringResource(R.string.wipe_after_title), stringResource(R.string.wipe_after_note)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(if (wipeAfterTen) R.string.state_on else R.string.state_off),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Switch(checked = wipeAfterTen, onCheckedChange = onWipeAfterTen)
                        }
                    }
                    Setting(stringResource(R.string.duress_title), stringResource(R.string.duress_note)) {
                        Text(
                            stringResource(if (hasDuress) R.string.duress_is_set else R.string.duress_not_set),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { duressDialog = true }) {
                                Text(stringResource(if (hasDuress) R.string.pin_change else R.string.pin_set))
                            }
                            if (hasDuress) TextButton(onClick = { onDuress(null) }) { Text(stringResource(R.string.pin_remove)) }
                        }
                    }
                }
                Setting(stringResource(R.string.wipe_title), stringResource(R.string.wipe_short)) {
                    Button(
                        onClick = { wipeDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    ) { Text(stringResource(R.string.wipe_action)) }
                }
            }

            Section(stringResource(R.string.section_look)) {
                Setting(stringResource(R.string.settings_theme)) {
                    ChipRow {
                        listOf(
                            ThemeMode.AUTO to R.string.theme_auto,
                            ThemeMode.DAY to R.string.theme_day,
                            ThemeMode.NIGHT to R.string.theme_night,
                        ).forEach { (m, label) ->
                            FilterChip(selected = mode == m, onClick = { onTheme(m) }, label = { Text(stringResource(label)) })
                        }
                    }
                }
                Setting(stringResource(R.string.settings_language)) {
                    ChipRow {
                        listOf(
                            Language.AUTO to stringResource(R.string.language_auto),
                            // Each language is named in itself, so it can be found from any language.
                            Language.GREEK to "Ελληνικά",
                            Language.ENGLISH to "English",
                        ).forEach { (l, label) ->
                            FilterChip(selected = language == l, onClick = { onLanguage(l) }, label = { Text(label) })
                        }
                    }
                }
            }

            Section(stringResource(R.string.section_share)) {
                Setting(stringResource(R.string.share_title), stringResource(R.string.share_note)) {
                    Button(onClick = onShareApp) { Text(stringResource(R.string.share_action)) }
                }
            }

            if (BuildConfig.DEBUG) {
                Section("Debug") {
                    OutlinedButton(onClick = onTestData) { Text("+25 test conversations") }
                    OutlinedButton(onClick = onResendNow) { Text("Resend undelivered now") }
                }
            }

            Section(stringResource(R.string.settings_about)) {
                Text(stringResource(R.string.about_text, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                var licences by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { licences = true }) { Text(stringResource(R.string.licences_title)) }
                if (licences) LicencesDialog(onDismiss = { licences = false })
            }
        }
    }
}

/** Choice chips side by side, going on to a second line where the screen is too narrow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** A group of settings: a small coloured heading over a rounded card. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
        }
    }
}

/** One setting: its name, an optional line saying what it does, and its control. */
@Composable
private fun Setting(title: String, note: String? = null, control: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        control()
    }
}

// PIN ----------------------------------------------------------------------------------------------

private const val PIN_MIN = 4
private const val PIN_MAX = 16

@Composable
private fun PinField(value: String, onValue: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.length <= PIN_MAX && v.all { it.isDigit() }) onValue(v) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        modifier = modifier,
    )
}

/** Chooses a PIN: typed twice, [PIN_MIN] to [PIN_MAX] digits. */
@Composable
private fun NewPinDialog(title: String, warning: String? = null, onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val ok = first.length >= PIN_MIN && first == second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (warning != null) Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                PinField(first, { first = it }, stringResource(R.string.pin_new))
                PinField(second, { second = it }, stringResource(R.string.pin_again))
                if (second.length >= PIN_MIN && first != second) {
                    Text(stringResource(R.string.pin_mismatch), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { Button(onClick = { onSet(first) }, enabled = ok) { Text(stringResource(R.string.settings_name_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Shown instead of everything else until the PIN is entered: dots for the digits typed and a
 * large keypad, like a phone's own lock screen. Wrong tries beyond five wait out a countdown.
 */
@Composable
fun PinScreen(messenger: Messenger, onUnlocked: () -> Unit, onWipe: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var waitUntil by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var checking by remember { mutableStateOf(false) }
    var phoneLocked by remember { mutableStateOf(false) }
    var keyLost by remember { mutableStateOf(false) }
    LaunchedEffect(waitUntil) {
        while (SystemClock.elapsedRealtime() < waitUntil) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
        now = SystemClock.elapsedRealtime()
    }
    val waiting = now < waitUntil
    val enabled = !checking && !waiting

    fun submit() {
        checking = true
        scope.launch {
            when (val r = messenger.unlock(pin)) {
                Messenger.PinCheck.Ok -> {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onUnlocked()
                }
                Messenger.PinCheck.Wrong -> {
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                    wrong = true
                }
                // Says nothing different on screen: whoever holds the phone sees an ordinary start.
                Messenger.PinCheck.Wipe -> onWipe()
                Messenger.PinCheck.PhoneLocked -> {
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                    phoneLocked = true
                }
                Messenger.PinCheck.KeyLost -> {
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                    keyLost = true
                }
                is Messenger.PinCheck.Wait -> {
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                    wrong = true
                    waitUntil = SystemClock.elapsedRealtime() + r.millis
                }
            }
            pin = ""
            checking = false
        }
    }

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Brand(height = 64.dp, stacked = true)
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.pin_prompt), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.height(16.dp)) {
                repeat(maxOf(PIN_MIN, pin.length)) { i ->
                    val filled = i < pin.length
                    Box(
                        Modifier.size(14.dp).clip(CircleShape)
                            .background(if (filled) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .border(1.5.dp, if (wrong && pin.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                when {
                    keyLost -> stringResource(R.string.pin_key_lost)
                    phoneLocked -> stringResource(R.string.pin_phone_locked)
                    waiting -> stringResource(R.string.pin_wait, ((waitUntil - now + 999) / 1000).toInt())
                    wrong -> stringResource(R.string.pin_wrong)
                    else -> ""
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.height(24.dp),
            )
            Spacer(Modifier.weight(1f))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    row.forEach { key ->
                        val ok = key == "✓"
                        val active = enabled && when (key) {
                            "⌫" -> pin.isNotEmpty()
                            "✓" -> pin.length >= PIN_MIN
                            else -> pin.length < PIN_MAX
                        }
                        Surface(
                            onClick = {
                                wrong = false
                                if (key != "✓") haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                                when (key) {
                                    "⌫" -> pin = pin.dropLast(1)
                                    "✓" -> submit()
                                    else -> pin += key
                                }
                            },
                            enabled = active,
                            shape = CircleShape,
                            color = when {
                                ok && active -> MaterialTheme.colorScheme.primary
                                ok -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                key == "⌫" -> Color.Transparent
                                else -> MaterialTheme.colorScheme.secondaryContainer
                            },
                            contentColor = if (ok) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(76.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(key, fontSize = if (key.first().isDigit()) 28.sp else 24.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Changes your name: the field starts with the current one. */
@Composable
private fun RenameDialog(name: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var edited by rememberSaveable { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_name_change_title)) },
        text = {
            OutlinedTextField(
                value = edited,
                onValueChange = { if (it.length <= 24) edited = it },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onSave(edited) },
                enabled = Messenger.cleanName(edited) != "?" && Messenger.fitName(edited) != name,
            ) { Text(stringResource(R.string.settings_name_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * A text field the keyboard is asked not to learn from: no new words in its dictionary, no
 * suggestions built from what was typed here. Compose cannot set that flag, so this is the
 * platform's own field, dressed to look like the rest of the app. Keeps to the message limits.
 */
@Composable
private fun PrivateTextField(value: String, onValueChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val text = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val onChange by rememberUpdatedState(onValueChange)
    Box(
        modifier
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        AndroidView(
            factory = { context ->
                EditText(context).apply {
                    background = null
                    setPadding(0, 0, 0, 0)
                    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    maxLines = 6
                    textSize = 16f
                    typeface = ResourcesCompat.getFont(context, R.font.commissioner)
                    filters = arrayOf(
                        InputFilter.LengthFilter(Payload.MAX_TEXT_CHARS),
                        // The byte limit too: the same number of characters can be more bytes.
                        InputFilter { source, start, end, dest, dstart, dend ->
                            val result = dest.substring(0, dstart) + source.substring(start, end) + dest.substring(dend)
                            if (result.toByteArray(Charsets.UTF_8).size > Payload.MAX_TEXT_BYTES) "" else null
                        },
                    )
                    doAfterTextChanged { onChange(it?.toString() ?: "") }
                }
            },
            update = { field ->
                field.setTextColor(text)
                field.setHintTextColor(hintColor)
                field.hint = hint
                if (field.text.toString() != value) field.setText(value)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The licences of the work by others inside the app, read from assets/licenses: a list first,
 * then the full text of the one picked.
 */
@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    val assets = LocalContext.current.assets
    val files = remember { assets.list("licenses").orEmpty().sortedBy { if (it.startsWith("THIRD_PARTY")) "" else it } }
    var open by remember { mutableStateOf<String?>(null) }
    val text = remember(open) { open?.let { name -> assets.open("licenses/$name").bufferedReader().use { it.readText() } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(open?.removeSuffix(".txt") ?: stringResource(R.string.licences_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (text != null) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                } else {
                    files.forEach { name ->
                        TextButton(onClick = { open = name }) { Text(name.removeSuffix(".txt")) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (open != null) open = null else onDismiss() }) {
                Text(stringResource(if (open != null) R.string.back else R.string.close))
            }
        },
    )
}


/**
 * Shown instead of everything when the phone's hardware key for the database is gone for good
 * (the phone was reset, or its files restored from elsewhere): the data cannot be opened again.
 */
@Composable
fun DataLostScreen(onStartFresh: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Brand(height = 64.dp, stacked = true)
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.lost_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.lost_note), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { confirm = true }) { Text(stringResource(R.string.lost_action)) }
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.lost_title),
            text = stringResource(R.string.lost_confirm),
            action = stringResource(R.string.lost_action),
            onDismiss = { confirm = false },
            onConfirm = { confirm = false; onStartFresh() },
        )
    }
}
