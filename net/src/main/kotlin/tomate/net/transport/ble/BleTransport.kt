package tomate.net.transport.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import tomate.core.model.AppError
import tomate.core.model.MessageEnvelope
import tomate.core.model.NearbyPeer
import tomate.core.model.Outcome
import tomate.core.model.TransportKind
import tomate.net.transport.EnvelopeCodec
import tomate.net.transport.IncomingEnvelope
import tomate.net.transport.NearbyTransport
import tomate.net.transport.TransportAvailability
import tomate.net.transport.wifi.WifiLocalTransport

/**
 * Bluetooth Low Energy transport: advertising + scanning for discovery, a GATT server to receive
 * envelopes and a GATT client to send them.
 *
 * Design notes / real-device caveats documented in docs/02_ARCHITECTURE.md §7:
 *  - BLE write payloads are bounded by the negotiated MTU, hence the framing in [EnvelopeCodec];
 *  - discovery stops as soon as the screen is off or sharing is stopped: TOMATE never scans
 *    continuously (battery and privacy);
 *  - Android 8-11 requires the location permission for BLE scanning. TOMATE requests it with
 *    `maxSdkVersion=30` and never derives a location; on API 31+ `neverForLocation` is used;
 *  - the peer identifier is a short rotating token derived from the advertised service data, not
 *    the hardware MAC address, so devices cannot be tracked across sessions.
 *
 * HONEST STATUS: compiles and is wired end-to-end, but **not exercised on physical devices** in
 * the cloud build environment. It is reported as untested, never as working.
 */
class BleTransport(private val context: Context) : NearbyTransport {

    override val kind: TransportKind = TransportKind.BluetoothLe

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _availability = MutableStateFlow(TransportAvailability.Stopped)
    override val availability: StateFlow<TransportAvailability> = _availability.asStateFlow()

    private val _peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    override val peers: StateFlow<List<NearbyPeer>> = _peers.asStateFlow()

    private val incomingFlow = MutableSharedFlow<IncomingEnvelope>(extraBufferCapacity = 16)
    private val reassembler = EnvelopeCodec.Reassembler()

    private val bluetoothManager: BluetoothManager? =
        context.applicationContext.getSystemService(BluetoothManager::class.java)

    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private var localToken: String = ""
    private var displayName: String = ""

    override fun incoming(): Flow<IncomingEnvelope> = incomingFlow.asSharedFlow()

    fun requiredPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun startSharing(displayName: String): Outcome<Unit> = withContext(Dispatchers.IO) {
        val btAdapter = adapter ?: run {
            _availability.value = TransportAvailability.Unsupported
            return@withContext Outcome.Failure(AppError.Validation("ble_unavailable", "Bluetooth indisponible."))
        }
        if (!hasPermissions()) {
            _availability.value = TransportAvailability.PermissionMissing
            return@withContext Outcome.Failure(
                AppError.Validation("ble_permission", "Autorisation Bluetooth requise."),
            )
        }
        if (!btAdapter.isEnabled) {
            _availability.value = TransportAvailability.Disabled
            return@withContext Outcome.Failure(
                AppError.Validation("bluetooth_off", "Activez le Bluetooth pour le partage de proximité."),
            )
        }
        if (_availability.value == TransportAvailability.Ready) return@withContext Outcome.Success(Unit)

        this@BleTransport.displayName = displayName.take(20)
        localToken = UUID.randomUUID().toString().take(8)

        return@withContext try {
            startGattServer()
            startAdvertising(btAdapter)
            startScanning(btAdapter)
            _availability.value = TransportAvailability.Ready
            Outcome.Success(Unit)
        } catch (t: Throwable) {
            _availability.value = TransportAvailability.Unsupported
            Outcome.Failure(AppError.Unknown("BLE indisponible: ${t.message}"))
        }
    }

    private fun startGattServer() {
        val manager = bluetoothManager ?: return
        val server = manager.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                if (value != null) {
                    val frame = String(value, Charsets.UTF_8).trim('\u0000', '\n')
                    reassembler.offer(frame)?.let { envelope ->
                        incomingFlow.tryEmit(
                            IncomingEnvelope(
                                envelope = envelope,
                                fromPeerId = envelope.senderId,
                                transport = TransportKind.BluetoothLe,
                            ),
                        )
                    }
                }
                if (responseNeeded && device != null) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
        })
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(characteristic)
        server.addService(service)
        gattServer = server
    }

    private fun startAdvertising(btAdapter: BluetoothAdapter) {
        val advertiser = btAdapter.bluetoothLeAdvertiser ?: run {
            _availability.value = TransportAvailability.Unsupported
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), localToken.toByteArray(Charsets.UTF_8))
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                _availability.value = TransportAvailability.Unsupported
            }
        }
        advertiseCallback = callback
        advertiser.startAdvertising(settings, data, callback)
    }

    private fun startScanning(btAdapter: BluetoothAdapter) {
        val scanner = btAdapter.bluetoothLeScanner ?: return
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val token = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
                    ?.toString(Charsets.UTF_8)
                    ?: return
                if (token == localToken) return
                val peer = NearbyPeer(
                    peerId = token,
                    displayName = token,
                    transport = TransportKind.BluetoothLe,
                    rssi = result.rssi,
                    lastSeenAt = System.currentTimeMillis(),
                )
                _peers.value = (_peers.value.filterNot { it.peerId == token } + peer)
            }
        }
        scanCallback = callback
        scanner.startScan(filters, settings, callback)
    }

    override suspend fun stopSharing() {
        withContext(Dispatchers.IO) {
            runCatching { adapter?.bluetoothLeScanner?.let { scanner -> scanCallback?.let(scanner::stopScan) } }
            scanCallback = null
            runCatching {
                adapter?.bluetoothLeAdvertiser?.let { advertiser ->
                    advertiseCallback?.let(advertiser::stopAdvertising)
                }
            }
            advertiseCallback = null
            runCatching { gattServer?.close() }
            gattServer = null
            _peers.value = emptyList()
            _availability.value = TransportAvailability.Stopped
        }
    }

    /** Pairing is confirmed in the UI (displayed token), so this only validates the peer exists. */
    override suspend fun pair(peerId: String): Outcome<Unit> =
        if (_peers.value.any { it.peerId == peerId }) {
            _peers.value = _peers.value.map { if (it.peerId == peerId) it.copy(paired = true) else it }
            Outcome.Success(Unit)
        } else {
            Outcome.Failure(AppError.NotFound("Appareil introuvable"))
        }

    override suspend fun unpair(peerId: String) {
        _peers.value = _peers.value.map { if (it.peerId == peerId) it.copy(paired = false) else it }
    }

    override suspend fun send(peerId: String, envelope: MessageEnvelope): Outcome<Unit> =
        withContext(Dispatchers.IO) {
            val btAdapter = adapter ?: return@withContext Outcome.Failure(AppError.Unknown("Bluetooth indisponible"))
            if (!hasPermissions()) {
                return@withContext Outcome.Failure(AppError.Validation("ble_permission", "Autorisation Bluetooth requise."))
            }
            val frames = EnvelopeCodec.frame(envelope)
            val device: BluetoothDevice = runCatching {
                btAdapter.bondedDevices.firstOrNull { it.address.endsWith(peerId) }
            }.getOrNull() ?: return@withContext Outcome.Failure(
                AppError.Validation(
                    "ble_peer_not_connected",
                    "Cet appareil n'est pas encore appairé au niveau système Bluetooth : utilisez le partage Wi-Fi local ou appairez-le dans les réglages Android.",
                ),
            )

            try {
                withTimeout(SEND_TIMEOUT_MS) {
                    val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {})
                    try {
                        frames.forEach { frame ->
                            val characteristic = gatt.getService(SERVICE_UUID)
                                ?.getCharacteristic(CHARACTERISTIC_UUID)
                                ?: error("Service TOMATE absent sur l'appareil distant")
                            gatt.writeCharacteristic(characteristic, frame.toByteArray(Charsets.UTF_8), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        }
                    } finally {
                        runCatching { gatt.disconnect(); gatt.close() }
                    }
                }
                Outcome.Success(Unit)
            } catch (t: Throwable) {
                Outcome.Failure(AppError.Unknown("Envoi Bluetooth échoué: ${t.message}"))
            }
        }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7f0e1c6a-2f3b-4c1e-9c1a-8a1f5c0d9001")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("7f0e1c6a-2f3b-4c1e-9c1a-8a1f5c0d9002")
        private const val SEND_TIMEOUT_MS = 20_000L
    }
}
