package com.example.glucoble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Главный экран приложения GlucoBLE Power Bridge.
 * Передаёт глюкозу в поле каденса (об/мин) на велокомпьютере Geoid CC600.
 */
enum class BridgeMode { CADENCE, CADENCE_TEST, HEART_GLUCOSE, HEART_TEST, POWER, POWER_TEST;
    val heartProfile: Boolean get() = this == HEART_GLUCOSE || this == HEART_TEST
    val powerProfile: Boolean get() = this == POWER || this == POWER_TEST
    val profileLabel: String get() = if (heartProfile) "ЧСС" else if (powerProfile) "Мощность" else "Каденс"
    val test: Boolean get() = this == CADENCE_TEST || this == HEART_TEST || this == POWER_TEST
    companion object {
        fun restore(value: String?): BridgeMode = entries.firstOrNull { it.name == value } ?: CADENCE
    }
}

object BridgeProtocol {
    fun mmol(raw: Double, mgdl: Boolean): Double = if (mgdl) raw / 18.0182 else raw
    fun watts(mmol: Double, multiply: Boolean): Int =
        (mmol * if (multiply) 10.0 else 1.0).roundToInt().coerceIn(0, 32767)
    // ADV: flags 3 + UUID 4 + name header 2. Scan response: UUID 4 + name header 2.
    fun canIncludeName(name: String?, primary: Boolean = false, uuidInScanResponse: Boolean = true): Boolean =
        !name.isNullOrEmpty() && name.toByteArray(Charsets.UTF_8).size <=
            if (primary) 22 else if (uuidInScanResponse) 25 else 29
    fun serviceUuid(heartProfile: Boolean): Int = if (heartProfile) 0x180d else 0x1816
    fun serviceUuid(mode: BridgeMode): Int = if (mode.powerProfile) 0x1818 else serviceUuid(mode.heartProfile)
    fun powerMeasurement(value: Int): ByteArray {
        require(value in -32768..32767)
        return byteArrayOf(0, 0, value.toByte(), (value shr 8).toByte())
    }
    fun heartMeasurement(value: Int): ByteArray {
        require(value in 0..65535)
        return if (value <= 255) byteArrayOf(0, value.toByte())
            else byteArrayOf(1, value.toByte(), (value ushr 8).toByte())
    }
    fun heartTestMeasurement(): ByteArray = heartMeasurement(93)
    fun accepts(value: Double, timestamp: Long, previous: Long, now: Long): Boolean =
        value.isFinite() && value > 0 && timestamp > 0 && timestamp >= previous && timestamp <= now + 60000
}

/** One outstanding Android GATT notification; queued samples are coalesced per client. */
class BleNotificationQueue<T : Any> {
    private val waiting = linkedMapOf<T, ByteArray>()
    var pending: T? = null
        private set
    fun offer(client: T, bytes: ByteArray) { waiting[client] = bytes.copyOf() }
    fun next(): Pair<T, ByteArray>? {
        if (pending != null || waiting.isEmpty()) return null
        val first = waiting.entries.first()
        val item = first.key to first.value
        waiting.remove(first.key)
        pending = item.first
        return item
    }
    fun complete(client: T): Boolean {
        if (pending != client) return false
        pending = null
        return true
    }
    fun remove(client: T, disconnected: Boolean = false) {
        waiting.remove(client)
        if (disconnected && pending == client) pending = null
    }
    fun clear() { waiting.clear(); pending = null }
}

/**
 * Фоновый сервис, принимающий глюкозу из xDrip+ / GlucoDataHandler и транслирующий её
 * по стандартному профилю BLE Cycling Speed and Cadence (UUID 0x1816, характеристика 0x2A5B) на Geoid CC600.
 */
@SuppressLint("MissingPermission", "WakelockTimeout")
class GlucoBleBridgeService : Service() {

    companion object {
        private const val TAG = "GlucoBleBridge"
        private const val CHANNEL_ID = "gluco_ble_power_channel"
        private const val NOTIFICATION_ID = 1001

        // Почему используется 18.0182:
        // Внутри кода Android-трансляций (Broadcast) xDrip+ всегда передаёт сырое число в мг/дл (mg/dL),
        // даже если на экране телефона выбраны ммоль/л. Например, при сахаре 9.3 ммоль/л xDrip+ присылает
        // число 167.57 (левая шкала графика). Деление на 18.0182 переводит 167.57 обратно в 9.3 ммоль/л.
        private const val MGDL_TO_MMOL = 18.0182

        const val EXTRA_MULTIPLY_BY_10 = "extra_multiply_by_10"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_TEST_MMOL = "extra_test_mmol"
        const val EXTRA_ADVERTISE_NAME_PRIMARY = "extra_advertise_name_primary"
        const val EXTRA_VPM_ADVERTISING = "extra_vpm_advertising"
        const val EXTRA_ADV_VARIANT = "extra_adv_variant"
        const val EXTRA_MINIMAL_ADVERTISING = "extra_minimal_advertising"
        const val ACTION_UI_UPDATE = "com.example.glucoble.UI_UPDATE"
        // Public API 36 broadcast metadata; OEMs may omit individual fields.
        private const val ACTION_ENCRYPTION_CHANGE = "android.bluetooth.device.action.ENCRYPTION_CHANGE"
        private const val EXTRA_ENCRYPTION_STATUS = "android.bluetooth.device.extra.ENCRYPTION_STATUS"
        private const val EXTRA_ENCRYPTION_ENABLED = "android.bluetooth.device.extra.ENCRYPTION_ENABLED"
        private const val EXTRA_ENCRYPTION_KEY_SIZE = "android.bluetooth.device.extra.KEY_SIZE"
        private const val EXTRA_ENCRYPTION_ALGORITHM = "android.bluetooth.device.extra.EXTRA_ENCRYPTION_ALGORITHM"

        // 1. Локальная трансляция xDrip+ (включена у вас на 2-м скриншоте)
        private const val ACTION_XDRIP_BG = "com.eveningoutpost.dexdrip.BgEstimate"
        private const val EXTRA_XDRIP_BG_MGDL = "com.eveningoutpost.dexdrip.Extras.BgEstimate"

        // 2. Трансляция GlucoDataHandler / Juggluco
        private const val ACTION_GLUCODATA_MINUTE = "glucodata.Minute"
        private const val ACTION_GDH_GLUCODATA = "de.michelinside.glucodatahandler.GLUCODATA"
        private const val EXTRA_GLUCODATA_MGDL = "mgdl"

        // Стандартные 16-битные UUID Bluetooth SIG для датчика каденса (Cycling Speed and Cadence)
        private fun bleUuid(shortUuid: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", shortUuid))

        val CCCD_UUID: UUID = bleUuid(0x2902)
        val SERVICE_CYCLING_POWER: UUID = bleUuid(0x1818)
        val CHAR_POWER_MEASUREMENT: UUID = bleUuid(0x2A63)
        val CHAR_POWER_FEATURE: UUID = bleUuid(0x2A65)
        val SERVICE_CYCLING_CADENCE: UUID = bleUuid(0x1816)
        val CHAR_CSC_MEASUREMENT: UUID = bleUuid(0x2A5B)
        val CHAR_CSC_FEATURE: UUID = bleUuid(0x2A5C)
        val CHAR_SENSOR_LOCATION: UUID = bleUuid(0x2A5D)
        val SERVICE_HEART_RATE: UUID = bleUuid(0x180D)
        val CHAR_HEART_MEASUREMENT: UUID = bleUuid(0x2A37)
        val SERVICE_DEVICE_INFORMATION: UUID = bleUuid(0x180A)
        val CHAR_MANUFACTURER: UUID = bleUuid(0x2A29)
        val CHAR_MODEL: UUID = bleUuid(0x2A24)
    }

    private var bluetoothManager: BluetoothManager? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val subscribedDevices = CopyOnWriteArraySet<BluetoothDevice>()
    private var bleStatus = "не запущен"
    private val cadenceEncoder = CadenceEncoder()
    private var mode = BridgeMode.CADENCE
    private val heartTest: Boolean get() = mode == BridgeMode.HEART_TEST
    private val heartGlucose: Boolean get() = mode == BridgeMode.HEART_GLUCOSE
    private val heartProfile: Boolean get() = mode.heartProfile
    private val modeLabel: String get() = when (mode) {
        BridgeMode.POWER -> "Мощность"
        BridgeMode.POWER_TEST -> "ТЕСТ мощности 93 Вт (не реальные данные)"
        BridgeMode.CADENCE -> "Каденс"
        BridgeMode.CADENCE_TEST -> "ТЕСТ каденса 93 об/мин (не реальные данные)"
        BridgeMode.HEART_GLUCOSE -> "Глюкоза через ЧСС ×10 (не пульс)"
        BridgeMode.HEART_TEST -> "ТЕСТ пульсометра 93 (не реальные данные)"
    }
    private val notificationQueue = BleNotificationQueue<BluetoothDevice>()
    private val bleEvents = java.util.ArrayDeque<String>()
    private val peerIds = linkedMapOf<BluetoothDevice, Int>()
    private var submitted = 0
    private var completed = 0
    private var sendErrors = 0
    private var pendingSince = 0L
    private var lastTransfer = "ожидание подписки"
    @Volatile private var destroyed = false
    private var bleGeneration = 0
    private var currentAdvertiseCallback: AdvertiseCallback? = null
    private val activeService: UUID get() = bleUuid(BridgeProtocol.serviceUuid(mode))
    private val activeMeasurement: UUID get() = if (heartProfile) CHAR_HEART_MEASUREMENT else if (mode.powerProfile) CHAR_POWER_MEASUREMENT else CHAR_CSC_MEASUREMENT
    private val connectedDevices = CopyOnWriteArraySet<BluetoothDevice>()
    private val gattAccessClients = CopyOnWriteArraySet<BluetoothDevice>()
    private val retainedClients = CopyOnWriteArraySet<BluetoothDevice>()
    private val aclDevices = CopyOnWriteArraySet<BluetoothDevice>()
    private var systemReceiverRegistered = false
    private val startupTimeout = Runnable {
        if (isBleStarting) {
            val stage = bleStatus
            stopBlePeripheral()
            bleStatus = "Нет ответа Bluetooth за 20 с ($stage). Выключите/включите Bluetooth и повторите запуск"
            refreshStatus()
        }
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationRefresh = 0L
    private val savedReading by lazy { getSharedPreferences("bridge", Context.MODE_PRIVATE) }
    private val savedSubscriptions by lazy {
        BondedSubscriptions(getSharedPreferences("ble_subscriptions", Context.MODE_PRIVATE))
    }

    private var multiplyBy10: Boolean = true
    private var advertiseNamePrimary: Boolean = true
    private var minimalAdvertising: Boolean = true
    private var vpmAdvertising: Boolean = true
    private var advertisingVariant = AdvertisingVariant.EXISTING
    private var isBleRunning: Boolean = false

    private var isBleStarting: Boolean = false

    @Volatile
    private var currentMmol: Double = 0.0

    private var lastReadingTime: Long = 0L

    @Volatile
    private var lastSource: String = "ожидание пакета от xDrip+ / GDH"

    // All BLE state, callbacks and samples are serialized independently of UI rendering.
    private val bleThread = HandlerThread("GlucoPower-BLE").apply { start() }
    private val bleHandler = Handler(bleThread.looper)
    private val httpExecutor = Executors.newSingleThreadScheduledExecutor()

    // Ежесекундная отправка счётчиков каденса на велокомпьютер Geoid CC600.
    private val bleTickRunnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            checkNotificationTimeout()
            broadcastPowerMeasurement()
            notifyUi()
            if (System.currentTimeMillis() - lastNotificationRefresh >= 60000) refreshStatus()
            bleHandler.postDelayed(this, 1000L)
        }
    }

    // Приёмник широковещательных пакетов от xDrip+ и GlucoDataHandler
    private val glucoseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    bleHandler.post { if (!isBleRunning && !isBleStarting) startBlePowerPeripheral() }
                } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                    bleHandler.post {
                        stopBlePeripheral()
                        bleStatus = "Bluetooth выключен"
                        refreshStatus()
                    }
                }
                return
            }
            val extras = intent.extras ?: return

            when (action) {
                ACTION_XDRIP_BG -> {
                    val mgdl = extras.getDouble(EXTRA_XDRIP_BG_MGDL, Double.NaN)
                    if (!mgdl.isNaN() && mgdl > 0) {
                        updateGlucoseValue(mgdl, "xDrip+ Broadcast", true,
                            extras.getLong("com.eveningoutpost.dexdrip.Extras.Time", System.currentTimeMillis()))
                    }
                }
                ACTION_GLUCODATA_MINUTE, ACTION_GDH_GLUCODATA -> {
                    // GDH/Juggluco uses unprefixed keys. "glucose" depends on the
                    // user's units, whereas "mgdl" is unambiguous. Accept old senders too.
                    @Suppress("DEPRECATION")
                    val mgdl = (extras.get(EXTRA_GLUCODATA_MGDL) as? Number)?.toDouble()
                        ?: (extras.get("glucodata.Minute.mgdl") as? Number)?.toDouble()
                    @Suppress("DEPRECATION")
                    val timestamp = (extras.get("Time") as? Number)?.toLong() ?: System.currentTimeMillis()
                    if (mgdl != null && mgdl.isFinite() && mgdl > 0) {
                        updateGlucoseValue(mgdl, "GlucoDataHandler (mg/dL)", true, timestamp)
                    }
                }
            }
        }
    }

    // These protected system broadcasts distinguish the radio link from GATT access
    // and CCCD subscription. They must never manufacture a measurement subscription.
    private val bluetoothEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (Looper.myLooper() != bleHandler.looper) {
                val copy = Intent(intent)
                bleHandler.post { if (!destroyed) onReceive(context, copy) }
                return
            }
            if (destroyed) return
            val device = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    aclDevices.add(device)
                    recordBle("ACL UP ${peer(device)}: transport=${intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, 0)}; это ещё не подписка")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    aclDevices.remove(device)
                    recordBle("ACL DOWN ${peer(device)}")
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    recordBle("BOND ${peer(device)}: " +
                        "${intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)} -> $state")
                    if (state == BluetoothDevice.BOND_NONE) {
                        savedSubscriptions.forget(device.address)
                        subscribedDevices.remove(device)
                        notificationQueue.remove(device)
                    } else if (state == BluetoothDevice.BOND_BONDED && subscribedDevices.contains(device)) {
                        saveSubscription(device, true)
                    }
                }
                "android.bluetooth.device.action.KEY_MISSING" -> recordBle("KEY_MISSING ${peer(device)}: ключ не восстановлен; проверьте системный запрос сопряжения (Android 17 может сначала пытаться восстановить пару)")
                ACTION_ENCRYPTION_CHANGE -> recordBle("ENCRYPTION_CHANGE ${peer(device)}: ${encryptionDetails(intent)}")
            }
            notifyUi()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        restoreSettings()
        registerGlucoseBroadcasts()
        registerBluetoothEvents()
        startLocalWebServerPoller()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Meet the foreground-service deadline on the main thread without reading worker state.
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GlucoPower: запуск трансляции")
            .setSmallIcon(com.example.glucoble.R.drawable.ic_notification)
            .setOngoing(true).build())
        val command = intent?.let { Intent(it) }
        bleHandler.post { if (!destroyed) applyStartCommand(command) }
        return START_STICKY
    }

    private fun applyStartCommand(intent: Intent?) {
        val requestedMode = if (intent == null) mode else BridgeMode.restore(intent.getStringExtra(EXTRA_MODE))
        // Migrate the old in-memory power-test Intent to a persistent test mode.
        val nextMode = if (requestedMode == BridgeMode.CADENCE && intent?.hasExtra(EXTRA_TEST_MMOL) == true)
            BridgeMode.CADENCE_TEST else requestedMode
        val nextNamePrimary = intent?.getBooleanExtra(EXTRA_ADVERTISE_NAME_PRIMARY, advertiseNamePrimary)
            ?: advertiseNamePrimary
        val nextMinimal = intent?.getBooleanExtra(EXTRA_MINIMAL_ADVERTISING, minimalAdvertising)
            ?: minimalAdvertising
        val nextVariant = intent?.getStringExtra(EXTRA_ADV_VARIANT)?.let { AdvertisingVariant.restore(it) } ?: advertisingVariant
        if (nextVariant != advertisingVariant) {
            stopBlePeripheral()
            advertisingVariant = nextVariant
            recordBle("Смена варианта рекламы: ${nextVariant.label}")
        }
        savedReading.edit().putString("adv_variant", advertisingVariant.name).apply()
        val nextVpm = intent?.getBooleanExtra(EXTRA_VPM_ADVERTISING, vpmAdvertising) ?: vpmAdvertising
        if (nextNamePrimary != advertiseNamePrimary || nextMinimal != minimalAdvertising || nextVpm != vpmAdvertising) {
            stopBlePeripheral()
            recordBle("Смена рекламы: имя в основном пакете=$nextNamePrimary; минимальная=$nextMinimal")
        }
        vpmAdvertising = nextVpm
        savedReading.edit().putBoolean("vpm_advertising", vpmAdvertising).apply()
        minimalAdvertising = nextMinimal
        savedReading.edit().putBoolean("minimal_advertising", minimalAdvertising).apply()
        advertiseNamePrimary = nextNamePrimary
        savedReading.edit().putBoolean("advertise_name_primary", advertiseNamePrimary).apply()
        if (nextMode != mode) {
            if (BridgeProtocol.serviceUuid(nextMode) != BridgeProtocol.serviceUuid(mode)) stopBlePeripheral()
            else {
                // Live/test modes of the same profile retain the client's subscription.
                // Drop queued test values, but let any in-flight Android notification finish.
                subscribedDevices.forEach { notificationQueue.remove(it) }
            }
            recordBle("Смена режима: $mode -> $nextMode")
        }
        mode = nextMode
        savedReading.edit().putString("mode", mode.name).apply()
        if (lastSource.startsWith("ТЕСТ")) restoreReading()
        multiplyBy10 = intent?.getBooleanExtra(EXTRA_MULTIPLY_BY_10, multiplyBy10) ?: multiplyBy10
        savedReading.edit().putBoolean("multiply", multiplyBy10).apply()

        refreshStatus()
        // BLE-пакеты идут раз в секунду, в том числе при выключенном экране.
        // Удерживаем CPU только пока пользователь запустил foreground-сервис.
        if (wakeLock?.isHeld != true) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlucoPower:bridge").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (!isBleRunning && !isBleStarting) {
            startBlePowerPeripheral()
        }

        bleHandler.removeCallbacks(bleTickRunnable)
        bleHandler.post(bleTickRunnable)

    }

    override fun onDestroy() {
        destroyed = true
        bleHandler.removeCallbacksAndMessages(null)
        httpExecutor.shutdownNow()
        runCatching { unregisterReceiver(glucoseReceiver) }
        if (systemReceiverRegistered) runCatching { unregisterReceiver(bluetoothEventsReceiver) }
        bleHandler.post {
            stopBlePeripheral()
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            bleThread.quitSafely()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // The flag overload is guarded by API 33; older devices require the legacy overload.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerGlucoseBroadcasts() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(ACTION_XDRIP_BG)
            addAction(ACTION_GLUCODATA_MINUTE)
            addAction(ACTION_GDH_GLUCODATA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(glucoseReceiver, filter, null, bleHandler, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(glucoseReceiver, filter, null, bleHandler)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerBluetoothEvents() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            // Older systems do not emit these actions; registration is harmless.
            addAction("android.bluetooth.device.action.KEY_MISSING")
            addAction(ACTION_ENCRYPTION_CHANGE)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothEventsReceiver, filter, null, bleHandler, Context.RECEIVER_EXPORTED)
        else registerReceiver(bluetoothEventsReceiver, filter, null, bleHandler)
        systemReceiverRegistered = true
    }

    private fun retainClient(device: BluetoothDevice) {
        // connect(false) may INITIATE a new link. Use it only to retain an existing
        // peer that has accessed our attributes, never to invite an arbitrary bond.
        if (!gattAccessClients.contains(device) || retainedClients.contains(device)) return
        val accepted = runCatching { gattServer?.connect(device, false) == true }.getOrDefault(false)
        if (accepted) retainedClients.add(device)
        recordBle("GATT RETAIN ${peer(device)} (обращение к сервису): запрос принят=$accepted; не подтверждает подписку")
    }

    private fun observeGattAccess(device: BluetoothDevice) {
        gattAccessClients.add(device)
        if (connectedDevices.add(device)) recordBle("GATT ACCESS ${peer(device)}: запрос к сервису подтверждает соединение")
        retainClient(device)
        restoreSubscription(device)
    }

    private fun saveSubscription(device: BluetoothDevice, enabled: Boolean) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            savedSubscriptions.record(device.address, activeService, activeMeasurement, enabled)
        } else if (device.bondState == BluetoothDevice.BOND_NONE) {
            savedSubscriptions.forget(device.address)
        }
    }

    private fun restoreSubscription(device: BluetoothDevice) {
        if (subscribedDevices.contains(device) || device.bondState != BluetoothDevice.BOND_BONDED ||
            !savedSubscriptions.enabled(device.address, activeService, activeMeasurement)) return
        // This is a previously accepted CCCD, not an inferred subscription from LINK/bond.
        subscribedDevices.add(device)
        recordBle("CCCD RESTORE ${peer(device)}: сохранённая подписка $activeMeasurement")
    }

    private fun encryptionDetails(intent: Intent): String {
        fun number(key: String): String = if (intent.hasExtra(key))
            intent.getIntExtra(key, -1).toString() else "не указан"
        val enabled = if (intent.hasExtra(EXTRA_ENCRYPTION_ENABLED))
            intent.getBooleanExtra(EXTRA_ENCRYPTION_ENABLED, false).toString() else "не указан"
        val algorithm = if (!intent.hasExtra(EXTRA_ENCRYPTION_ALGORITHM)) "не указан" else {
            val value = intent.getIntExtra(EXTRA_ENCRYPTION_ALGORITHM, -1)
            val label = when (value) { 0 -> "NONE"; 1 -> "E0"; 2 -> "AES"; else -> "UNKNOWN" }
            "$label ($value)"
        }
        // HCI status 0 is success. An event alone, or a missing extra, proves neither
        // encryption success nor missing keys. Never dump arbitrary extras/keys.
        return "status=${number(EXTRA_ENCRYPTION_STATUS)} (HCI; 0=успех), enabled=$enabled, " +
            "keySize=${number(EXTRA_ENCRYPTION_KEY_SIZE)} байт, algorithm=$algorithm, " +
            "transport=${number(BluetoothDevice.EXTRA_TRANSPORT)}"
    }

    private fun restoreSettings() {
        restoreReading()
        mode = BridgeMode.restore(savedReading.getString("mode", null))
        multiplyBy10 = savedReading.getBoolean("multiply", true)
        advertiseNamePrimary = savedReading.getBoolean("advertise_name_primary", true)
        advertisingVariant = AdvertisingVariant.restore(savedReading.getString("adv_variant", null))
        vpmAdvertising = savedReading.getBoolean("vpm_advertising", true)
        minimalAdvertising = savedReading.getBoolean("minimal_advertising", true)
    }

    private fun restoreReading() {
        lastSource = savedReading.getString("source", null) ?: "ожидание xDrip+"
        // Older versions could persist a manually generated test as the last reading.
        val realReading = !lastSource.startsWith("ТЕСТ")
        currentMmol = if (realReading) savedReading.getString("mmol", null)?.toDoubleOrNull() ?: 0.0 else 0.0
        lastReadingTime = if (realReading) savedReading.getLong("timestamp", 0L) else 0L
        if (!realReading) lastSource = "ожидание xDrip+"
    }

    private fun updateGlucoseValue(rawValue: Double, source: String, mgdl: Boolean,
                                   timestamp: Long = System.currentTimeMillis()) {
        val mmol = BridgeProtocol.mmol(rawValue, mgdl)
        bleHandler.post {
            if (destroyed) return@post
            val previous = if (lastSource.startsWith("ТЕСТ") && !source.startsWith("ТЕСТ")) 0L else lastReadingTime
            if (!BridgeProtocol.accepts(mmol, timestamp, previous, System.currentTimeMillis())) {
                recordBle("GLUCOSE REJECTED: $source; недопустимое значение или время измерения")
                return@post
            }
            if (timestamp != lastReadingTime || source != lastSource)
                recordBle("GLUCOSE INPUT: $source; время=$timestamp (значение в журнал не записывается)")
            currentMmol = mmol
            lastReadingTime = timestamp
            lastSource = source
            if (!source.startsWith("ТЕСТ")) {
                savedReading.edit().putString("mmol", mmol.toString())
                    .putLong("timestamp", timestamp).putString("source", source).apply()
            }
            refreshStatus()
            broadcastPowerMeasurement()
        }
    }

    private fun checkNotificationTimeout() {
        if (notificationQueue.pending == null ||
            SystemClock.elapsedRealtime() - pendingSince < 10000) return
        sendErrors++
        recordBle("NOTIFY timeout: Android не завершил отправку за 10 с; перезапуск GATT")
        // Never just release pending: a late callback could complete a newer packet.
        // Close the entire session so its callbacks cannot affect the replacement.
        stopBlePeripheral()
        bleStatus = "Восстановление BLE после зависания отправки; ожидается повторное подключение"
        refreshStatus()
        val recoveryGeneration = bleGeneration
        bleHandler.postDelayed({
            if (!destroyed && recoveryGeneration == bleGeneration && !isBleStarting && !isBleRunning)
                startBlePowerPeripheral()
        }, 2000L)
    }

    private fun getWattsValue(): Int = BridgeProtocol.watts(currentMmol, multiplyBy10)
    private fun outputText(): String = when {
        mode == BridgeMode.POWER_TEST -> "ТЕСТ: 93 Вт, не реальные данные"
        mode == BridgeMode.CADENCE_TEST -> "ТЕСТ: 93 об/мин, не реальные данные"
        heartTest -> "ТЕСТ: 93 в поле ЧСС, не реальные данные"
        lastReadingTime == 0L -> "ожидание измерения"
        heartGlucose -> "${BridgeProtocol.watts(currentMmol, true)} в поле ЧСС — глюкоза ×10, не пульс"
        mode.powerProfile -> "${getWattsValue()} в поле мощности — глюкоза, не реальные ватты"
        else -> "${getWattsValue()} об/мин"
    }

    private fun readingTime(): String = if (lastReadingTime == 0L) "ещё нет данных" else
        SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(lastReadingTime))

    private fun refreshStatus() {
        if (destroyed) return
        lastNotificationRefresh = System.currentTimeMillis()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
        notifyUi()
    }

    private fun notifyUi() {
        if (destroyed) return
        val uiIntent = Intent(ACTION_UI_UPDATE).apply {
            setPackage(packageName)
            putExtra("running", isBleRunning || isBleStarting)
            putExtra("mode", mode.name)
            putExtra("mmol", currentMmol)
            putExtra("output", outputText())
            putExtra("source", lastSource)
            putExtra("connected", subscribedDevices.size)
            putExtra("ble", "$modeLabel. " +
                bleStatus + "; соединений: ${connectedDevices.size} (могут быть другие устройства)" +
                "\nОтправка: ${transferStage()}; Android принял: $submitted, завершил: $completed, ошибок: $sendErrors")
            putExtra("diagnostics", diagnosticReport())
            putExtra("updated", readingTime())
        }
        sendBroadcast(uiIntent)
    }

    private fun peer(device: BluetoothDevice): String {
        val id = peerIds.getOrPut(device) { peerIds.size + 1 }
        val name = runCatching { device.name }.getOrNull() ?: "имя неизвестно"
        // No Bluetooth addresses or glucose readings in the shareable log.
        return "клиент#$id ($name)"
    }

    private fun recordBle(event: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
        bleEvents.addLast("$time [сеанс $bleGeneration] $event")
        while (bleEvents.size > 100) bleEvents.removeFirst()
        Log.i(TAG, event)
    }

    private fun diagnosticReport(): String = buildString {
        appendLine("GlucoPower 1.20; ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Отчёт создан: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(Date())}")
        appendLine("Прошивка телефона: ${Build.DISPLAY}")
        if (mode.powerProfile) appendLine("Appearance 0x0484: не добавлен в ADV — публичный Android API не поддерживает AD 0x19")
        appendLine("Вариант рекламы: ${if (mode.powerProfile) AdvertisingVariant.UUID_ONLY.label else advertisingVariant.label}; A–F имеют приоритет над прежними настройками ниже")
        appendLine("VPM advertising: $vpmAdvertising (при включении имеет приоритет над настройками ниже)")
        appendLine("Реклама: ${if (minimalAdvertising) "UUID только в ADV, имя только в scan response" else "UUID в ADV и scan response"}")
        appendLine("Имя в основном BLE-объявлении: $advertiseNamePrimary; длинное имя переносится в scan response")
        appendLine("Режим: $modeLabel; профиль: $activeService; характеристика: $activeMeasurement")
        appendLine("Соединения: ${connectedDevices.size}; подписки: ${subscribedDevices.size}")
        appendLine("Счётчик соединений включает системные уведомления о клиентах других приложений")
        appendLine("Наблюдаемых системных ACL: ${aclDevices.size} (включая другие Bluetooth-устройства)")
        appendLine("Сохранённые сопряжения: " + runCatching {
            bluetoothManager?.adapter?.bondedDevices?.joinToString { peer(it) } ?: "нет"
        }.getOrDefault("нет доступа"))
        appendLine("Клиенты, обратившиеся к GATT приложения: " +
            gattAccessClients.takeIf { it.isNotEmpty() }?.joinToString { peer(it) }.orEmpty().ifEmpty { "пока нет" })
        appendLine("Подписчики измерений: " +
            subscribedDevices.takeIf { it.isNotEmpty() }?.joinToString { peer(it) }.orEmpty().ifEmpty { "пока нет" })
        appendLine("Данные: " + when {
            mode.test -> "тестовые 93; не зависят от входной глюкозы"
            lastReadingTime == 0L -> "входное измерение ещё не получено; источник: $lastSource"
            else -> "измерение получено; источник: $lastSource; время: ${readingTime()}"
        })
        appendLine("Android принял: $submitted; завершил успешно: $completed; ошибок: $sendErrors")
        appendLine("$bleStatus; ${transferStage()}")
        bleEvents.forEach { appendLine(it) }
    }

    private fun transferStage(): String = when {
        gattServer == null -> "GATT не запущен"
        subscribedDevices.isEmpty() && gattAccessClients.isEmpty() -> "ожидание обращения клиента к сервису и подписки"
        subscribedDevices.isEmpty() -> "клиент обратился к GATT; ожидается подписка CCCD"
        !mode.test && lastReadingTime == 0L -> "подписка есть; ожидается входное измерение xDrip+/GDH"
        else -> lastTransfer
    }

    /**
     * Резервный опрос встроенного веб-сервера xDrip+ / GlucoDataHandler (127.0.0.1:17580/sgv.json).
     */
    private fun startLocalWebServerPoller() {
        httpExecutor.scheduleWithFixedDelay({
            try {
                val url = URL("http://127.0.0.1:17580/sgv.json?count=1")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2000
                    readTimeout = 2000
                    requestMethod = "GET"
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(body)
                    if (jsonArray.length() > 0) {
                        val sgvMgdl = jsonArray.getJSONObject(0).optDouble("sgv", Double.NaN)
                        if (!sgvMgdl.isNaN() && sgvMgdl > 0) {
                            val timestamp = jsonArray.getJSONObject(0).optLong("date", 0L)
                            // Не выдаём повторный ответ со старым измерением за новые данные.
                            if (timestamp > 0) updateGlucoseValue(sgvMgdl, "Локальный сервер :17580", true, timestamp)
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
                // Игнорируем, если используется обычная локальная трансляция
            }
        }, 2, 15, java.util.concurrent.TimeUnit.SECONDS)
    }

    // ========================================================================
    // BLE GATT Server — Профиль датчика каденса (Cycling Speed and Cadence 0x1816)
    // ========================================================================

    private fun startBlePowerPeripheral() {
        if (destroyed) return
        stopBlePeripheral()

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            bleStatus = "Включите Bluetooth и снова нажмите запуск"
            refreshStatus()
            return
        }
        // Do not restore a subscription whose bond was removed while the app was stopped.
        runCatching { adapter.bondedDevices.map { it.address }.toSet() }
            .onSuccess { savedSubscriptions.prune(it) }
        advertiser = adapter.bluetoothLeAdvertiser
        // Поддержка нескольких рекламодателей не нужна для одного экземпляра.
        if (advertiser == null) {
            bleStatus = "Телефон не поддерживает BLE Peripheral / Advertising"
            refreshStatus()
            return
        }
        isBleStarting = true
        recordBle("Запуск профиля $activeService")
        bleStatus = "регистрация GATT-сервиса"
        bleHandler.postDelayed(startupTimeout, 20000)
        try {
            gattServer = bluetoothManager?.openGattServer(this, createGattCallback(bleGeneration))
            val service = if (heartProfile) createHeartService() else if (mode.powerProfile) createCyclingPowerService() else createCyclingCadenceService()
            if (gattServer?.addService(service) != true) {
                stopBlePeripheral()
                bleStatus = "Не удалось зарегистрировать GATT-сервис; повторите запуск"
            }
        } catch (e: RuntimeException) {
            stopBlePeripheral()
            bleStatus = "Ошибка GATT: ${e.javaClass.simpleName}: ${e.message}"
        }
        refreshStatus()
    }

    private fun advertisePowerService() {
        val variant = if (mode.powerProfile) AdvertisingVariant.UUID_ONLY else advertisingVariant
        if (variant != AdvertisingVariant.EXISTING) {
            try {
                val (data, scan) = variant.packets(activeService, bluetoothManager?.adapter?.name)
                val callback = createAdvertiseCallback(bleGeneration)
                currentAdvertiseCallback = callback
                recordBle("ADV ${variant.label}: UUID=$activeService; TX=MEDIUM; connectable legacy; UUID_ADV=${data.serviceUuids}; UUID_SCAN=${scan?.serviceUuids}; NAME_ADV=${data.includeDeviceName}; NAME_SCAN=${scan?.includeDeviceName}; SERVICE_DATA=${data.serviceData.keys}; Appearance не задан; Flags определяет Android")
                if (scan == null) advertiser?.startAdvertising(variant.settings(), data, callback)
                else advertiser?.startAdvertising(variant.settings(), data, scan, callback)
            } catch (e: RuntimeException) {
                stopBlePeripheral()
                bleStatus = "Ошибка варианта рекламы: ${e.message}"
                recordBle(bleStatus)
                refreshStatus()
            }
            return
        }
        if (vpmAdvertising) {
            advertiseVpmService()
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .apply { if (Build.VERSION.SDK_INT >= 34) setDiscoverable(true) }
            .build()
        // UUID должен быть в основном пакете: CC600 ищет датчики каденса 0x1816.
        // Имя телефона может быть длиннее лимита legacy advertising (31 байт).
        val name = bluetoothManager?.adapter?.name
        val nameInPrimary = !minimalAdvertising && advertiseNamePrimary && BridgeProtocol.canIncludeName(name, primary = true)
        val nameInScanResponse = !nameInPrimary && BridgeProtocol.canIncludeName(name, uuidInScanResponse = !minimalAdvertising)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(nameInPrimary)
            .addServiceUuid(ParcelUuid(activeService))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .apply { if (!minimalAdvertising) addServiceUuid(ParcelUuid(activeService)) }
            .setIncludeDeviceName(nameInScanResponse)
            .build()
        val namePosition = when {
            nameInPrimary -> "в основном пакете"
            nameInScanResponse -> "в scan response"
            else -> "не помещается; используйте имя Bluetooth до 22 байт UTF-8"
        }
        recordBle("ADV legacy/connectable/discoverable: UUID=$activeService ${if (minimalAdvertising) "только в ADV" else "в ADV и scan response"}; имя $namePosition")
        bleStatus = "GATT готов; запускается реклама ${if (heartProfile) "0x180D" else if (mode.powerProfile) "0x1818" else "0x1816"}; имя: ${name ?: "нет"}"
        refreshStatus()
        try {
            val callback = createAdvertiseCallback(bleGeneration)
            currentAdvertiseCallback = callback
            advertiser?.startAdvertising(settings, data, scanResponse, callback)
        } catch (e: RuntimeException) {
            stopBlePeripheral()
            bleStatus = "Ошибка запуска рекламы: ${e.javaClass.simpleName}: ${e.message}"
            refreshStatus()
        }
    }

    private fun advertiseVpmService() {
        val name = bluetoothManager?.adapter?.name
        if (name.isNullOrEmpty() || name.toByteArray(Charsets.UTF_8).size > 19) {
            stopBlePeripheral()
            bleStatus = "VPM: задайте в настройках Bluetooth имя до 19 байт UTF-8 (например GlucoPower) или отключите VPM"
            recordBle(bleStatus)
            refreshStatus()
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(activeService))
            .setIncludeDeviceName(true).setIncludeTxPowerLevel(true).build()
        recordBle("ADV VPM: UUID=$activeService; имя=$name; TX power в ADV; scan response отсутствует")
        try {
            val callback = createAdvertiseCallback(bleGeneration)
            currentAdvertiseCallback = callback
            advertiser?.startAdvertising(settings, data, callback)
        } catch (e: RuntimeException) {
            stopBlePeripheral()
            bleStatus = "VPM advertising error: ${e.javaClass.simpleName}: ${e.message}"
            recordBle(bleStatus)
            refreshStatus()
        }
    }

    private fun stopBlePeripheral() {
        cadenceEncoder.reset()
        bleGeneration++
        bleHandler.removeCallbacks(startupTimeout)
        runCatching { currentAdvertiseCallback?.let { advertiser?.stopAdvertising(it) } }
        currentAdvertiseCallback = null
        // A connection callback is an observation, not ownership. Only release
        // links explicitly retained after access to our own attributes.
        retainedClients.forEach { device -> runCatching { gattServer?.cancelConnection(device) } }
        runCatching { gattServer?.close() }
        subscribedDevices.clear()
        connectedDevices.clear()
        gattAccessClients.clear()
        retainedClients.clear()
        notificationQueue.clear()
        submitted = 0
        completed = 0
        sendErrors = 0
        lastTransfer = "ожидание подписки"
        isBleRunning = false
        isBleStarting = false
        gattServer = null
        advertiser = null
    }

    private fun createHeartService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_HEART_RATE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val measurement = BluetoothGattCharacteristic(CHAR_HEART_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        measurement.addDescriptor(BluetoothGattDescriptor(CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(measurement)
        return service
    }

    private fun createDeviceInformationService(): BluetoothGattService =
        BluetoothGattService(SERVICE_DEVICE_INFORMATION, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            listOf(CHAR_MANUFACTURER, CHAR_MODEL).forEach { uuid ->
                addCharacteristic(BluetoothGattCharacteristic(uuid,
                    BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            }
        }

    private fun createCyclingPowerService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_CYCLING_POWER, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val measurement = BluetoothGattCharacteristic(
            CHAR_POWER_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        measurement.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )

        val feature = BluetoothGattCharacteristic(
            CHAR_POWER_FEATURE,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val location = BluetoothGattCharacteristic(
            CHAR_SENSOR_LOCATION,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        feature.value = byteArrayOf(0, 0, 0, 0)
        location.value = byteArrayOf(5)
        measurement.value = BridgeProtocol.powerMeasurement(0)
        service.addCharacteristic(measurement)
        service.addCharacteristic(feature)
        service.addCharacteristic(location)
        return service
    }

    private fun createCyclingCadenceService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_CYCLING_CADENCE, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Характеристика измерения каденса (0x2A5B) — Notify
        val measurement = BluetoothGattCharacteristic(
            CHAR_CSC_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0
        )
        measurement.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )

        // Характеристика возможностей датчика каденса (0x2A5C) — Read (2 байта, crank only)
        val feature = BluetoothGattCharacteristic(
            CHAR_CSC_FEATURE,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        // Расположение датчика (0x2A5D) — Read (1 байт = 5: Left Crank / Левый шатун)
        val location = BluetoothGattCharacteristic(
            CHAR_SENSOR_LOCATION,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(measurement)
        service.addCharacteristic(feature)
        service.addCharacteristic(location)
        return service
    }

    private fun broadcastPowerMeasurement() {
        if (destroyed || isBleStarting || gattServer == null || subscribedDevices.isEmpty()) return
        if (!mode.test && lastReadingTime == 0L) {
            val waiting = "подписка есть; ожидается входное измерение xDrip+/GDH"
            if (lastTransfer != waiting) recordBle("DATA WAIT: $waiting")
            lastTransfer = waiting
            return
        }
        val payload = when (mode) {
            BridgeMode.POWER -> BridgeProtocol.powerMeasurement(getWattsValue())
            BridgeMode.POWER_TEST -> BridgeProtocol.powerMeasurement(93)
            BridgeMode.CADENCE -> cadenceEncoder.sample(getWattsValue(), SystemClock.elapsedRealtime())
            BridgeMode.CADENCE_TEST -> cadenceEncoder.sample(93, SystemClock.elapsedRealtime())
            BridgeMode.HEART_GLUCOSE -> BridgeProtocol.heartMeasurement(BridgeProtocol.watts(currentMmol, true))
            BridgeMode.HEART_TEST -> BridgeProtocol.heartTestMeasurement()
        }
        subscribedDevices.forEach { notificationQueue.offer(it, payload) }
        sendNextNotification()
    }

    private fun sendNextNotification() {
        val server = gattServer ?: return
        val characteristic = server.getService(activeService)?.getCharacteristic(activeMeasurement)
        if (characteristic == null) {
            lastTransfer = "Характеристика измерения отсутствует в GATT"
            recordBle(lastTransfer)
            return
        }
        while (true) {
            val (device, payload) = notificationQueue.next() ?: return
            if (!subscribedDevices.contains(device)) {
                notificationQueue.complete(device)
                continue
            }
            pendingSince = SystemClock.elapsedRealtime()
            val status = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, characteristic, false, payload)
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = payload
                    @Suppress("DEPRECATION")
                    if (server.notifyCharacteristicChanged(device, characteristic, false)) 0 else -1
                }
            } catch (e: RuntimeException) {
                recordBle("NOTIFY exception ${e.javaClass.simpleName}: ${e.message}")
                -1
            }
            if (status == 0) {
                submitted++
                lastTransfer = "ожидание завершения Android"
                if (submitted == 1 || submitted % 30 == 0) recordBle("NOTIFY ${peer(device)}: принято Android (#$submitted), ${payload.size} байт")
                return // Only onNotificationSent may release the next notification.
            }
            sendErrors++
            lastTransfer = "Android отклонил отправку: $status"
            recordBle("NOTIFY ${peer(device)}: код ошибки $status")
            notificationQueue.complete(device)
        }
    }

    private fun createAdvertiseCallback(session: Int) = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            bleHandler.post {
                if (destroyed || session != bleGeneration || !isBleStarting) return@post
                bleHandler.removeCallbacks(startupTimeout)
                isBleRunning = true
                isBleStarting = false
                connectedDevices.forEach { restoreSubscription(it) }
                broadcastPowerMeasurement()
                recordBle("ADV: Android подтвердил запуск")
                bleStatus = "реклама ${if (heartProfile) "0x180D (ЧСС)" else if (mode.powerProfile) "0x1818 (мощность)" else "0x1816 (каденс)"} запущена; ищите имя телефона / адрес"
                refreshStatus()
            }
        }

        override fun onStartFailure(errorCode: Int) {
            bleHandler.post {
                if (destroyed || session != bleGeneration || !isBleStarting) return@post
                stopBlePeripheral()
                bleStatus = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "BLE: рекламный пакет слишком большой"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "BLE: слишком много рекламодателей; закройте другие BLE-приложения"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE Advertising не поддерживается телефоном"
                    else -> "Ошибка BLE Advertising: $errorCode; повторите запуск"
                }
                recordBle(bleStatus)
                Log.e(TAG, bleStatus)
                refreshStatus()
            }
        }
    }

    private fun createGattCallback(session: Int) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            bleHandler.post {
                if (destroyed || session != bleGeneration || !isBleStarting || gattServer == null) return@post
                recordBle("GATT: зарегистрирован ${service.uuid}, status=$status, характеристики=" +
                    service.characteristics.joinToString { "${it.uuid}/${it.instanceId}" })
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    stopBlePeripheral()
                    bleStatus = "Ошибка регистрации GATT: $status"
                    refreshStatus()
                } else if (service.uuid == activeService) {
                    // Register services sequentially; Android addService is asynchronous.
                    if (gattServer?.addService(createDeviceInformationService()) != true) {
                        stopBlePeripheral()
                        bleStatus = "Ошибка регистрации сведений о датчике"
                        refreshStatus()
                    }
                } else if (service.uuid == SERVICE_DEVICE_INFORMATION) {
                    advertisePowerService()
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            bleHandler.post {
                if (destroyed || session != bleGeneration) return@post
                recordBle("LINK ${peer(device)}: state=$newState, status=$status")
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connectedDevices.add(device)
                    restoreSubscription(device)
                    if (isBleRunning) broadcastPowerMeasurement()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedDevices.remove(device)
                    gattAccessClients.remove(device)
                    retainedClients.remove(device)
                    subscribedDevices.remove(device)
                    notificationQueue.remove(device, disconnected = true)
                    sendNextNotification()
                }
                notifyUi()
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int,
                                                offset: Int, characteristic: BluetoothGattCharacteristic) {
            bleHandler.post {
                if (destroyed || session != bleGeneration) return@post
                observeGattAccess(device)
                recordBle("READ ${peer(device)}: ${characteristic.uuid}/${characteristic.instanceId}, offset=$offset")
                val bytes = when (characteristic.uuid) {
                    CHAR_POWER_FEATURE -> byteArrayOf(0, 0, 0, 0)
                    CHAR_CSC_FEATURE -> byteArrayOf(2, 0)
                    CHAR_SENSOR_LOCATION -> byteArrayOf(5)
                    CHAR_MANUFACTURER -> "GlucoPower".toByteArray(Charsets.UTF_8)
                    CHAR_MODEL -> "Glucose Power Bridge".toByteArray(Charsets.UTF_8)
                    else -> null
                }
                val status = when {
                    bytes == null -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    offset < 0 || offset > bytes.size -> BluetoothGatt.GATT_INVALID_OFFSET
                    else -> BluetoothGatt.GATT_SUCCESS
                }
                gattServer?.sendResponse(device, requestId, status, offset,
                    if (status == BluetoothGatt.GATT_SUCCESS) bytes!!.copyOfRange(offset, bytes.size) else null)
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int,
                                             offset: Int, descriptor: BluetoothGattDescriptor) {
            bleHandler.post {
                if (destroyed || session != bleGeneration) return@post
                observeGattAccess(device)
                recordBle("READ CCCD ${peer(device)}: ${descriptor.characteristic.uuid}, offset=$offset")
                val known = descriptor.uuid == CCCD_UUID && descriptor.characteristic.uuid == activeMeasurement
                val value = if (subscribedDevices.contains(device)) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                val status = when {
                    !known -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    offset < 0 || offset > value.size -> BluetoothGatt.GATT_INVALID_OFFSET
                    else -> BluetoothGatt.GATT_SUCCESS
                }
                gattServer?.sendResponse(device, requestId, status, offset,
                    if (status == BluetoothGatt.GATT_SUCCESS) value.copyOfRange(offset, value.size) else null)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
                                              descriptor: BluetoothGattDescriptor, preparedWrite: Boolean,
                                              responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val bytes = value.copyOf()
            bleHandler.post {
                if (destroyed || session != bleGeneration) return@post
                observeGattAccess(device)
                recordBle("WRITE CCCD ${peer(device)}: ${descriptor.characteristic.uuid}, value=" +
                    bytes.joinToString("") { "%02x".format(it.toInt() and 255) } +
                    ", offset=$offset, prepared=$preparedWrite, response=$responseNeeded")
                val enabled = bytes.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val disabled = bytes.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                val status = when {
                    preparedWrite || descriptor.uuid != CCCD_UUID || descriptor.characteristic.uuid != activeMeasurement -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                    bytes.size != 2 -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                    !enabled && !disabled -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    else -> BluetoothGatt.GATT_SUCCESS
                }
                if (responseNeeded && gattServer?.sendResponse(device, requestId, status, 0, null) != true) {
                    recordBle("CCCD: Android не принял ответ на запись")
                    return@post
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    recordBle("CCCD: запись отклонена, status=$status")
                    return@post
                }
                saveSubscription(device, enabled)
                if (enabled) {
                    subscribedDevices.add(device)
                    recordBle("SUBSCRIBE ${peer(device)}: уведомления включены")
                    lastTransfer = "подписка получена"
                    // Respond to CCCD first, then send the initial sample without waiting for the next tick.
                    broadcastPowerMeasurement()
                } else {
                    subscribedDevices.remove(device)
                    notificationQueue.remove(device)
                    recordBle("UNSUBSCRIBE ${peer(device)}")
                }
                notifyUi()
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            bleHandler.post {
                if (destroyed || session != bleGeneration) return@post
                // All prepared writes are rejected, so this queue is empty.
                // ATT 3.4.6.3 requires a successful response for an empty queue,
                // for both Execute and Cancel, without changing any attributes.
                val status = BluetoothGatt.GATT_SUCCESS
                recordBle("EXECUTE WRITE ${peer(device)}: execute=$execute, status=$status")
                gattServer?.sendResponse(device, requestId, status, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            bleHandler.post {
                if (destroyed || session != bleGeneration || !notificationQueue.complete(device)) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    completed++
                    lastTransfer = "Android завершил отправку"
                    if (completed == 1 || completed % 30 == 0) recordBle("SENT ${peer(device)}: status=0 (#$completed)")
                } else {
                    sendErrors++
                    lastTransfer = "ошибка завершения отправки: $status"
                    recordBle("SENT ${peer(device)}: status=$status")
                }
                sendNextNotification()
                notifyUi()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GlucoBLE Датчик каденса",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val text = String.format("Сахар: %.1f ммоль/л → %s (%s)", currentMmol, outputText(), lastSource)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val stale = lastReadingTime > 0 && System.currentTimeMillis() - lastReadingTime > 15 * 60000L
        val updateText = "Измерение: ${readingTime()}" + if (stale) " — нет свежих данных" else ""
        val details = if (mode.test) "${outputText()}.\nДля возврата нажмите «Запустить датчик каденса».\nBLE: $bleStatus\n${transferStage()}"
            else "$text\n$updateText\nBLE: $bleStatus\nПодписки: ${subscribedDevices.size}; ${transferStage()}"
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return builder
            .setContentTitle(if (mode.test) "GlucoPower: ${outputText()}" else if (lastReadingTime == 0L) "GlucoPower: ожидание xDrip+" else
                String.format("%.1f ммоль/л → %s", currentMmol, outputText()))
            .setContentText(if (mode.test) "ТЕСТ BLE, не реальные данные" else updateText)
            .setStyle(Notification.BigTextStyle().bigText(details))
            .setWhen(lastReadingTime)
            .setShowWhen(lastReadingTime > 0)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setSmallIcon(com.example.glucoble.R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }
}
