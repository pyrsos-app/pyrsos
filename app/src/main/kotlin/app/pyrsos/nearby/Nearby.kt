// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.pyrsos.Messenger
import app.pyrsos.core.Crypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds other Pyrsos phones over Bluetooth LE and trades envelopes with each one it meets.
 *
 * Each phone advertises a short record: the protocol version, the number of the channel it
 * listens on, and a random nonce that changes every few minutes. A phone that sees another
 * opens a Bluetooth LE connection-oriented channel to it (no pairing: the envelopes protect
 * themselves) and both run [Sync]. The lower nonce dials, so two phones do not dial each other
 * at once; if the lower one stays silent, the higher dials after a while.
 *
 * Android 10 and 11 allow searching only with the location permission. Pyrsos does not ask for
 * it, so there a phone only announces itself and answers; newer phones nearby do the dialing.
 *
 * What an observer learns from the air: that a phone nearby runs Pyrsos. Nothing in the record
 * stays the same for long, but a dedicated listener can still tell devices apart by their radio.
 */
class Nearby(private val context: Context, private val messenger: Messenger, private val scope: CoroutineScope) {
    sealed interface State {
        object Stopped : State
        object NeedsPermission : State
        object BluetoothOff : State
        object Unsupported : State
        /**
         * [searching] is false on phones that can only wait to be found. [degraded]: announcing or
         * searching keeps failing, so phones may not find this one (or this one them).
         */
        class Running(val peers: Int, val searching: Boolean, val degraded: Boolean = false) : State
    }

    private val _state = MutableStateFlow<State>(State.Stopped)

    /**
     * True when phones are heard but every call to them has failed for a long time: the phone's own
     * Bluetooth has most likely got stuck, which only turning it off and on again cures. This app
     * never does that itself: a record anyone can fake must not be able to cut the person's other
     * Bluetooth devices off, so the screen asks the person to.
     */
    private val _stuck = MutableStateFlow(false)
    val stuck: StateFlow<Boolean> = _stuck

    /** Calls in a row that failed and count against the radio (see [callFailed]), and when one last worked. */
    private val failedInARow = AtomicInteger(0)
    @Volatile private var lastSuccess = SystemClock.elapsedRealtime()
    val state: StateFlow<State> = _state

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var server: BluetoothServerSocket? = null
    private var jobs = ArrayList<Job>()
    private var advertising: AdvertiseCallback? = null
    private var scanning: ScanCallback? = null
    @Volatile private var nonce = 0
    @Volatile private var psm = 0

    /** Failed starts in a row of announcing and of searching, and the retries waiting. Main thread. */
    private var advertiseFailures = 0
    private var scanFailures = 0
    private var advertiseRetry: Job? = null
    private var scanRetry: Job? = null
    /** When searching was last started, newest last: Android allows five starts per 30 seconds. */
    private val scanStarts = ArrayDeque<Long>()
    @Volatile private var degraded = false

    /** Peers by nonce: when last heard, and when first heard in this sighting. */
    private val seen = ConcurrentHashMap<Int, Pair<Long, Long>>()
    /** When this phone last met each peer it called. Keyed by the nonce it called. */
    private val lastSync = ConcurrentHashMap<Int, Long>()
    /**
     * When peers last called this phone, by the address of the channel they called on: the nonce in
     * their HELLO is only their word and must not be able to stop this phone's own calls.
     */
    private val answered = ConcurrentHashMap<String, Long>()
    private val dialing = ConcurrentHashMap.newKeySet<Int>()
    private val dialingAddresses = ConcurrentHashMap.newKeySet<String>()
    /** Failed calls in a row, per peer nonce; cleared by a call that works. */
    private val failures = ConcurrentHashMap<Int, Int>()

    /**
     * Per remote address, which a made-up record cannot change as cheaply as its nonce: the earliest
     * time to call it again, its failed calls in a row, and when a call to it last worked.
     */
    private val dialAfter = ConcurrentHashMap<String, Long>()
    private val addressFailures = ConcurrentHashMap<String, Int>()
    private val met = ConcurrentHashMap<String, Long>()
    /** Addresses whose call could not connect lately, and when; see [callFailed]. */
    private val unreachable = ConcurrentHashMap<String, Long>()
    @Volatile private var lastStrangerDial = Long.MIN_VALUE / 2

    /** Answering, per remote address: calls in progress, when to take the next, envelopes taken this hour. */
    private val answeringAddresses = ConcurrentHashMap.newKeySet<String>()
    private val answerAfter = ConcurrentHashMap<String, Long>()
    private val taken = ConcurrentHashMap<String, Pair<Long, Int>>()

    /** One outgoing call at a time; the radio is shared and the store is single-threaded anyway. */
    private val exchange = Mutex()
    private val answering = AtomicInteger(0)

    /** True between [start] and [stop]: the screen is open and wants phones found. */
    private var wanted = false

    /**
     * On screen: search and announce at full rate, so phones find each other in seconds. In the
     * background: the radio's own low-power modes, which listen and announce in short bursts and
     * let the phone sleep in between; meeting a phone may take a minute, which is fine for relaying.
     */
    @Volatile private var foreground = true

    /** Switches between the two rates above. Main thread only. */
    @SuppressLint("MissingPermission")
    fun setForeground(on: Boolean) {
        if (foreground == on) return
        foreground = on
        val a = adapter ?: return
        if (_state.value !is State.Running) return
        try {
            scanning?.let { a.bluetoothLeScanner?.stopScan(it) }
        } catch (_: IllegalStateException) {
        }
        scanning = null
        if (canSearch) scan(a)
        advertise(a)
    }

    /**
     * Bluetooth can go off under a running exchange (airplane mode, the quick settings tile) and
     * come back. Everything started on the old radio is dead then, so stop on the way down and
     * start again on the way up. Runs on the main thread, like [start] and [stop].
     */
    private val radio = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF -> if (wanted && _state.value is State.Running) {
                    halt()
                    _state.value = State.BluetoothOff
                }
                BluetoothAdapter.STATE_OFF -> {
                    if (wanted && _state.value is State.Running) {
                        halt()
                        _state.value = State.BluetoothOff
                    }
                }
                BluetoothAdapter.STATE_ON -> {
                    // A fresh radio: whatever was stuck is gone.
                    failedInARow.set(0)
                    lastSuccess = SystemClock.elapsedRealtime()
                    _stuck.value = false
                    if (wanted) start()
                }
            }
        }
    }

    init {
        // A new message should not wait out the pause between meetings with phones already met.
        scope.launch {
            messenger.outgoing.collect {
                lastSync.clear()
                answered.clear()
            }
        }
        ContextCompat.registerReceiver(
            context,
            radio,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun hasPermissions(): Boolean = permissions().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start() {
        wanted = true
        if (_state.value is State.Running) return
        val a = adapter
        when {
            a == null -> return run { _state.value = State.Unsupported }
            !hasPermissions() -> return run { _state.value = State.NeedsPermission }
            !a.isEnabled -> return run { _state.value = State.BluetoothOff }
        }
        val adapter = a!!
        if (!listen(adapter)) return run { _state.value = State.Unsupported }
        _state.value = State.Running(0, canSearch)

        jobs += scope.launch(Dispatchers.Main) {
            // A new nonce and a new channel now and then, so the record cannot be followed for long:
            // a channel number that stayed would tie the old nonce to the new one.
            while (isActive) {
                nonce = Crypto.random(4).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
                advertise(adapter)
                delay(NONCE_LIFETIME_MS)
                listen(adapter)
            }
        }
        advertiseFailures = 0
        scanFailures = 0
        degraded = false
        jobs += scope.launch {
            while (isActive) {
                updatePeers()
                delay(5_000)
            }
        }
        if (canSearch) scan(adapter)
    }

    /**
     * Opens a new channel to be called on and closes the previous one; exchanges already under
     * way on it carry on. Main thread only. False if no channel could be opened.
     */
    @SuppressLint("MissingPermission")
    private fun listen(adapter: BluetoothAdapter): Boolean {
        val listener = try {
            adapter.listenUsingInsecureL2capChannel()
        } catch (e: IOException) {
            Log.w(TAG, "cannot listen on an L2CAP channel: ${e.message}")
            return false
        }
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = listener
        psm = listener.psm
        jobs.removeAll { it.isCompleted }
        jobs += scope.launch(Dispatchers.IO) { acceptLoop(listener) }
        return true
    }

    fun stop() {
        wanted = false
        halt()
        _state.value = State.Stopped
    }

    @SuppressLint("MissingPermission")
    private fun halt() {
        jobs.forEach { it.cancel() }
        jobs = ArrayList()
        val a = adapter
        if (a != null && hasPermissions()) {
            // With the radio going down these may refuse; there is nothing left to stop then.
            try {
                advertising?.let { a.bluetoothLeAdvertiser?.stopAdvertising(it) }
                scanning?.let { a.bluetoothLeScanner?.stopScan(it) }
            } catch (_: IllegalStateException) {
            }
        }
        advertising = null
        scanning = null
        advertiseRetry?.cancel()
        scanRetry?.cancel()
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
        seen.clear()
        dialing.clear()
        dialingAddresses.clear()
        failures.clear()
    }

    @SuppressLint("MissingPermission")
    private fun advertise(adapter: BluetoothAdapter) {
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            // Some phones can listen but not announce: they still dial the ones that do.
            Log.w(TAG, "this phone cannot advertise")
            return
        }
        advertising?.let { advertiser.stopAdvertising(it) }
        val record = byteArrayOf(
            RECORD_VERSION.toByte(),
            (psm ushr 8).toByte(), psm.toByte(),
            (nonce ushr 24).toByte(), (nonce ushr 16).toByte(), (nonce ushr 8).toByte(), nonce.toByte(),
        )
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (foreground) AdvertiseSettings.ADVERTISE_MODE_BALANCED else AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(if (foreground) AdvertiseSettings.ADVERTISE_TX_POWER_HIGH else AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // The service UUID travels only inside the service data, which keeps the record within
        // the 31 bytes of a legacy advertisement.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(SERVICE, record)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                advertiseFailures = 0
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "advertising failed: $errorCode")
                if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) return
                if (advertising !== this) return // replaced since
                val n = advertiseFailures++
                if (n >= TROUBLE_AFTER) degraded = true
                advertiseRetry?.cancel()
                // Too many advertisers or an internal error clears on its own; an unsupported
                // feature does not, so that one is not retried.
                if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED) return run { degraded = true }
                val failed = this
                advertiseRetry = scope.launch(Dispatchers.Main) {
                    delay(retryDelay(n))
                    if (isActive && wanted && _state.value is State.Running && advertising === failed) advertise(adapter)
                }
            }
        }
        advertising = callback
        try {
            advertiser.startAdvertising(settings, data, callback)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "cannot start advertising: ${e.message}")
        }
    }

    /** Backoff for restarting announcing or searching: 5 s, doubling, up to 5 minutes. */
    private fun retryDelay(failures: Int) = minOf(RESTART_BASE_MS shl minOf(failures, 6), MAX_RESTART_WAIT_MS)

    @SuppressLint("MissingPermission")
    private fun scan(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner ?: return run { _state.value = State.Unsupported }
        // Past five starts in 30 seconds Android silently stops delivering results.
        val now = SystemClock.elapsedRealtime()
        while (scanStarts.isNotEmpty() && now - scanStarts.first() > SCAN_START_WINDOW_MS) scanStarts.removeFirst()
        if (scanStarts.size >= MAX_SCAN_STARTS) {
            scheduleScan(scanStarts.first() + SCAN_START_WINDOW_MS - now + 1_000)
            return
        }
        scanStarts.addLast(now)
        val filter = ScanFilter.Builder()
            .setServiceData(SERVICE, byteArrayOf(RECORD_VERSION.toByte()), byteArrayOf(0xff.toByte()))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (foreground) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = heard(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::heard)
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
                if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) return
                if (scanning !== this) return // replaced since
                scanning = null
                val n = scanFailures++
                if (n >= TROUBLE_AFTER) degraded = true
                scheduleScan(retryDelay(n))
            }
        }
        scanning = callback
        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "cannot start searching: ${e.message}")
            scanning = null
            scheduleScan(retryDelay(scanFailures++))
        }
    }

    /** Starts searching again after [wait], unless something else has by then. Main thread only. */
    private fun scheduleScan(wait: Long) {
        scanRetry?.cancel()
        scanRetry = scope.launch(Dispatchers.Main) {
            delay(wait)
            val a = adapter
            if (!isActive || a == null || !wanted || _state.value !is State.Running || scanning != null) return@launch
            scan(a)
            // No failure callback soon after a start means it took.
            val started = scanning ?: return@launch
            delay(SCAN_SETTLE_MS)
            if (scanning === started) {
                scanFailures = 0
                degraded = advertiseFailures >= TROUBLE_AFTER
            }
        }
    }

    private fun heard(result: ScanResult) {
        val record = result.scanRecord?.getServiceData(SERVICE) ?: return
        if (record.size != 7 || record[0].toInt() != RECORD_VERSION) return
        val peerPsm = ((record[1].toInt() and 0xff) shl 8) or (record[2].toInt() and 0xff)
        scanFailures = 0
        val peerNonce = ((record[3].toInt() and 0xff) shl 24) or ((record[4].toInt() and 0xff) shl 16) or
            ((record[5].toInt() and 0xff) shl 8) or (record[6].toInt() and 0xff)
        if (peerNonce == nonce) return
        val now = SystemClock.elapsedRealtime()
        val first = seen[peerNonce]?.second ?: now
        seen[peerNonce] = now to first

        val recent = (lastSync[peerNonce] ?: Long.MIN_VALUE / 2) > now - RESYNC_MS
        if (recent || peerNonce in dialing) return
        // Of two phones, the one with the lower nonce calls. The other calls only if that has not
        // happened for a while, as when the first cannot search.
        val mineFirst = Integer.compareUnsigned(nonce, peerNonce) < 0
        val address = result.device.address
        val calledMe = (answered[address] ?: Long.MIN_VALUE / 2) > now - RESYNC_MS
        if (now < (dialAfter[address] ?: Long.MIN_VALUE / 2)) return
        if (mineFirst || (now - first > DIAL_ANYWAY_MS && !calledMe)) dial(result.device, peerPsm, peerNonce)
    }

    @SuppressLint("MissingPermission")
    private fun dial(device: BluetoothDevice, peerPsm: Int, peerNonce: Int) {
        // Calls wait for one another; a crowd of records, real or made up, must not pile them up.
        // Nonces cost nothing to invent, so limits also run per address: one call at a time to it,
        // a pause between calls, and a longer one after each failure. Addresses never met get one
        // call at a time per few seconds and leave a slot free for those that have been met.
        val address = device.address
        val now = SystemClock.elapsedRealtime()
        val known = met.containsKey(address)
        if (!known && (now - lastStrangerDial < STRANGER_DIAL_GAP_MS || dialing.size >= MAX_DIALING - 1)) return
        if (dialing.size >= MAX_DIALING || !dialingAddresses.add(address)) return
        if (!dialing.add(peerNonce)) return run { dialingAddresses.remove(address) }
        if (!known) lastStrangerDial = now
        dialAfter[address] = now + ADDRESS_DIAL_GAP_MS
        scope.launch(Dispatchers.IO) {
            try {
                exchange.withLock {
                    if ((lastSync[peerNonce] ?: Long.MIN_VALUE / 2) > SystemClock.elapsedRealtime() - RESYNC_MS) return@withLock
                    val socket = try {
                        device.createInsecureL2capChannel(peerPsm)
                    } catch (e: IOException) {
                        // Bluetooth going down under us, typically; the next sighting tries again.
                        Log.w(TAG, "cannot open a channel: ${e.message}")
                        return@withLock
                    }
                    val outcome = talk(socket, peerNonce, address) { socket.connect() }
                    if (outcome == Outcome.WORKED) {
                        failures.remove(peerNonce)
                        addressFailures.remove(address)
                        unreachable.remove(address)
                        met[address] = SystemClock.elapsedRealtime()
                        dialAfter[address] = SystemClock.elapsedRealtime() + ADDRESS_DIAL_GAP_MS
                    } else {
                        // Only a call that could not even connect says anything about this phone's
                        // Bluetooth; a peer that breaks off an exchange says nothing about it. Nor
                        // does a record nobody answers, which anyone can make up: it counts only if
                        // the address was met before or several addresses have failed lately.
                        if (outcome == Outcome.NO_CONNECTION) {
                            unreachable[address] = SystemClock.elapsedRealtime()
                            if (known || unreachable.size >= STUCK_ADDRESSES) callFailed()
                        }
                        val m = addressFailures.merge(address, 1, Int::plus)!!
                        dialAfter[address] = SystemClock.elapsedRealtime() +
                            minOf(RETRY_AFTER_FAILURE_MS shl minOf(m - 1, 5), MAX_RETRY_WAIT_MS)
                        // A phone that cannot be reached now is tried again later, waiting twice as
                        // long after each failure: hammering an unreachable phone drains the battery
                        // and can wedge the phone's own Bluetooth.
                        val n = failures.merge(peerNonce, 1, Int::plus)!!
                        val wait = minOf(RETRY_AFTER_FAILURE_MS shl minOf(n - 1, 5), MAX_RETRY_WAIT_MS)
                        lastSync[peerNonce] = SystemClock.elapsedRealtime() - RESYNC_MS + wait
                    }
                }
            } finally {
                dialing.remove(peerNonce)
                dialingAddresses.remove(address)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun acceptLoop(listener: BluetoothServerSocket) {
        while (true) {
            val socket = try {
                listener.accept()
            } catch (_: IOException) {
                return // closed by stop()
            }
            // A phone that opens channel after channel gets only a few at a time, and one address
            // only one, after a pause, until its hourly allowance of envelopes is used up.
            val address = try {
                socket.remoteDevice.address
            } catch (_: Exception) {
                closeQuietly(socket)
                continue
            }
            if (SystemClock.elapsedRealtime() < (answerAfter[address] ?: Long.MIN_VALUE / 2) ||
                budgetLeft(address) <= 0 || !answeringAddresses.add(address)
            ) {
                closeQuietly(socket)
                continue
            }
            if (answering.incrementAndGet() > MAX_ANSWERING) {
                answering.decrementAndGet()
                answeringAddresses.remove(address)
                closeQuietly(socket)
                continue
            }
            // Not behind [exchange]: two phones dialing each other at once would each wait for the
            // other to answer while holding the lock that answering needs.
            scope.launch(Dispatchers.IO) {
                try {
                    val outcome = talk(socket, null, address) {}
                    // A call that went wrong waits longer before the next is taken.
                    val pause = if (outcome == Outcome.WORKED) ANSWER_GAP_MS else ANSWER_PENALTY_MS
                    answerAfter[address] = SystemClock.elapsedRealtime() + pause
                } finally {
                    answeringAddresses.remove(address)
                    answering.decrementAndGet()
                }
            }
        }
    }

    /** Envelopes this phone may still take from [address] this hour. */
    private fun budgetLeft(address: String): Int {
        val e = taken[address] ?: return MAX_TAKEN_PER_HOUR
        return if (SystemClock.elapsedRealtime() - e.first > HOUR_MS) MAX_TAKEN_PER_HOUR else maxOf(0, MAX_TAKEN_PER_HOUR - e.second)
    }

    private fun charge(address: String, n: Int) {
        if (n <= 0) return
        val now = SystemClock.elapsedRealtime()
        taken.compute(address) { _, e -> if (e == null || now - e.first > HOUR_MS) now to n else e.first to e.second + n }
    }

    /**
     * Counts a call that could not connect. After [SOFT_RESET_AFTER] in a row this app restarts its
     * own part of Bluetooth (announcing, searching, its channel). If calls still fail and none has
     * worked for [STUCK_AFTER_MS], the phone's Bluetooth is taken to be stuck and [stuck] asks the
     * person to turn it off and on. The app never does that itself, on any Android: turning the
     * radio off cuts every other Bluetooth device too, and the signal can be faked from outside.
     */
    private fun callFailed() {
        val n = failedInARow.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        val sinceSuccess = now - lastSuccess
        if (n >= STUCK_AFTER_FAILURES && sinceSuccess >= STUCK_AFTER_MS) {
            Log.w(TAG, "Bluetooth looks stuck: $n failed calls, none worked for ${sinceSuccess / 1000} s")
            _stuck.value = true
        } else if (n % SOFT_RESET_AFTER == 0) {
            Log.w(TAG, "$n failed calls in a row: restarting this app's Bluetooth")
            scope.launch(Dispatchers.Main) {
                if (_state.value is State.Running) {
                    halt()
                    _state.value = State.Stopped
                    start()
                }
            }
        }
    }

    private fun callWorked() {
        failedInARow.set(0)
        lastSuccess = SystemClock.elapsedRealtime()
        _stuck.value = false
    }

    private enum class Outcome { WORKED, NO_CONNECTION, FAILED }

    /**
     * Runs one exchange on [socket]. [dialed]: the nonce this phone called, which the other must
     * then give; null when it answered a call.
     */
    private suspend fun talk(socket: BluetoothSocket, dialed: Int?, address: String, open: () -> Unit): Outcome {
        // A blocking read ignores cancellation; closing the socket is what ends it. So the whole
        // exchange has a deadline, and Sync closes it sooner when the other phone goes quiet.
        val deadline = scope.launch {
            delay(if (dialed != null) EXCHANGE_TIMEOUT_MS else ANSWER_TIMEOUT_MS)
            closeQuietly(socket)
        }
        var connected = false
        var sync: Sync? = null
        return try {
            open()
            connected = true
            // A phone that called us may push only so much per hour, however often it comes back.
            sync = Sync(socket.inputStream, socket.outputStream, if (dialed == null) budgetLeft(address) else Int.MAX_VALUE, dialed == null) { closeQuietly(socket) }
            val r = sync.run(messenger, nonce, dialed)
            // Our GOT may still be on its way; closing now would drop it.
            delay(CLOSE_LINGER_MS)
            // Receipts for what opened go out later, after a random delay (see
            // Messenger.sealDueAcks); sealing them clears this and the phones meet again.
            if (dialed != null) lastSync[dialed] = SystemClock.elapsedRealtime() else answered[address] = SystemClock.elapsedRealtime()
            Log.i(TAG, "exchange: received ${r.received}, sent ${r.sent}, opened ${r.opened}")
            callWorked()
            Outcome.WORKED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "exchange failed: ${e.javaClass.simpleName}: ${e.message}")
            if (connected) Outcome.FAILED else Outcome.NO_CONNECTION
        } finally {
            if (dialed == null) charge(address, sync?.taken ?: 0)
            deadline.cancel()
            closeQuietly(socket)
        }
    }

    private fun closeQuietly(socket: BluetoothSocket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private fun updatePeers() {
        val now = SystemClock.elapsedRealtime()
        seen.entries.removeIf { now - it.value.first > PEER_TIMEOUT_MS }
        // Nothing here is kept longer than it can matter. Failure counts follow their backoff entry.
        lastSync.entries.removeIf { now - it.value > PRUNE_AFTER_MS }
        failures.keys.removeIf { !lastSync.containsKey(it) }
        answered.entries.removeIf { now - it.value > PRUNE_AFTER_MS }
        dialAfter.entries.removeIf { now - it.value > PRUNE_AFTER_MS }
        addressFailures.keys.removeIf { !dialAfter.containsKey(it) }
        met.entries.removeIf { now - it.value > MET_MEMORY_MS }
        unreachable.entries.removeIf { now - it.value > UNREACHABLE_MEMORY_MS }
        answerAfter.entries.removeIf { now > it.value }
        taken.entries.removeIf { now - it.value.first > HOUR_MS }
        // A phone that cannot search sees no one; it counts the phones that called it lately.
        // Only phones heard in the last few seconds: a phone that just changed its nonce would
        // otherwise count twice until its old one times out.
        val peers = if (canSearch) seen.values.count { now - it.first < SHOWN_MS } else answered.values.count { now - it < RESYNC_MS + PEER_TIMEOUT_MS }
        if (_state.value is State.Running) _state.value = State.Running(peers, canSearch, degraded)
    }

    companion object {
        private const val TAG = "Pyrsos"
        private val SERVICE = ParcelUuid(UUID.fromString("fced167d-5d0c-4f70-ae11-569d97d9f3e5"))
        private const val RECORD_VERSION = 1

        const val NONCE_LIFETIME_MS = 10 * 60 * 1000L
        const val RESYNC_MS = 30_000L
        const val DIAL_ANYWAY_MS = 12_000L
        const val PEER_TIMEOUT_MS = 30_000L
        const val SHOWN_MS = 10_000L
        const val EXCHANGE_TIMEOUT_MS = 90_000L
        /** Shorter for calls this phone answers: nobody has to be served for long. */
        const val ANSWER_TIMEOUT_MS = 45_000L
        const val ANSWER_GAP_MS = 5_000L
        const val ANSWER_PENALTY_MS = 60_000L
        /** Envelopes taken from one address in an hour, on top of the per-meeting limit. */
        const val MAX_TAKEN_PER_HOUR = 300
        const val ADDRESS_DIAL_GAP_MS = 5_000L
        const val STRANGER_DIAL_GAP_MS = 5_000L
        /** Distinct addresses whose calls could not connect lately before that counts against the radio. */
        const val STUCK_ADDRESSES = 3
        const val UNREACHABLE_MEMORY_MS = 10 * 60 * 1000L
        const val MET_MEMORY_MS = 6 * 60 * 60 * 1000L
        const val PRUNE_AFTER_MS = 60 * 60 * 1000L
        const val HOUR_MS = 60 * 60 * 1000L
        const val RESTART_BASE_MS = 5_000L
        const val MAX_RESTART_WAIT_MS = 5 * 60 * 1000L
        const val SCAN_START_WINDOW_MS = 30_000L
        const val MAX_SCAN_STARTS = 5
        const val SCAN_SETTLE_MS = 3_000L
        /** Failed starts in a row before the screen is told. */
        const val TROUBLE_AFTER = 3
        const val MAX_ANSWERING = 3
        const val MAX_DIALING = 3
        const val CLOSE_LINGER_MS = 500L
        const val RETRY_AFTER_FAILURE_MS = 10_000L
        const val MAX_RETRY_WAIT_MS = 5 * 60 * 1000L
        const val SOFT_RESET_AFTER = 10
        const val STUCK_AFTER_FAILURES = 20
        const val STUCK_AFTER_MS = 10 * 60 * 1000L

        /** Searching without the location permission came with Android 12. */
        val canSearch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        fun permissions(): List<String> =
            if (canSearch) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                emptyList()
            }
    }
}
