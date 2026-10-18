package com.example.glucoble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Главный экран приложения GlucoBLE Power Bridge.
 * Настроен специально на передачу глюкозы (ммоль/л) в поле «Мощность (Вт)» на велокомпьютер Geoid CC600.
 */
enum class BridgeMode { POWER, POWER_TEST, HEART_GLUCOSE, HEART_TEST;
    val heartProfile: Boolean get() = this == HEART_GLUCOSE || this == HEART_TEST
    val test: Boolean get() = this == POWER_TEST || this == HEART_TEST
    companion object {
        fun restore(value: String?): BridgeMode = entries.firstOrNull { it.name == value } ?: POWER
    }
}

object BridgeProtocol {
    fun mmol(raw: Double, mgdl: Boolean): Double = if (mgdl) raw / 18.0182 else raw
    fun watts(mmol: Double, multiply: Boolean): Int =
        (mmol * if (multiply) 10.0 else 1.0).roundToInt().coerceIn(0, 32767)

    // Классический 4-байтный пакет для unit-тестов
    fun measurement(watts: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN).putShort(0).putShort(watts.toShort()).array()

    // Расширенный 8-байтный пакет Cycling Power (0x2A63) с данными вращения шатуна (Crank Revolution Data).
    // Велокомпьютеры Geoid CC600 / Magene часто игнорируют пакеты мощности без движения шатунов!
    fun measurementWithCrank(watts: Int, crankRevs: Int, crankEventTime1024: Int): ByteArray =
        ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x0020) // Bit 5: Crank Revolution Data Present
            .putShort(watts.coerceIn(0, 32767).toShort())
            .putShort((crankRevs and 0xFFFF).toShort())
            .putShort((crankEventTime1024 and 0xFFFF).toShort())
            .array()

    // ADV: flags 3 + UUID 4 + name header 2. Scan response: name header 2.
    fun canIncludeName(name: String?, primary: Boolean = false): Boolean =
        !name.isNullOrEmpty() && name.toByteArray(Charsets.UTF_8).size <= if (primary) 18 else 26

    fun serviceUuid(heartProfile: Boolean): Int = if (heartProfile) 0x180d else 0x1818

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

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var checkMultiply10: CheckBox
    private lateinit var checkAdvertiseName: CheckBox
    private var lastDiagnostics = "GlucoPower 1.12: сначала запустите BLE-сервис"

    private val uiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == GlucoBleBridgeService.ACTION_UI_UPDATE) {
                val mmol = intent.getDoubleExtra("mmol", 0.0)
                val output = intent.getStringExtra("output") ?: "ожидание"
                val source = intent.getStringExtra("source") ?: "ожидание"
                val connected = intent.getIntExtra("connected", 0)
                val ble = intent.getStringExtra("ble") ?: "ожидание"
                lastDiagnostics = intent.getStringExtra("diagnostics") ?: lastDiagnostics
                val updated = intent.getStringExtra("updated") ?: "ещё нет данных"
                statusText.text = String.format(
                    "Текущий сахар: %.1f ммоль/л\n%s\nИсточник: %s\nПодписано BLE-клиентов: %d\nBLE: %s\nИзмерение: %s",
                    mmol, output, source, connected, ble, updated
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val titleView = TextView(this).apply {
            text = "GlucoPower 1.12 → Geoid CC600"
            textSize = 20f
            setPadding(0, 0, 0, 24)
        }
        root.addView(titleView)

        statusText = TextView(this).apply {
            text = "Сервис не запущен. Данные xDrip+ ещё не получены."
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }
        root.addView(statusText)

        checkMultiply10 = CheckBox(this).apply {
            text = "Мощность: умножать ммоль/л на 10 (9.3 → 93 Вт)"
            isChecked = true
            setPadding(0, 16, 0, 24)
        }
        root.addView(checkMultiply10)

        checkAdvertiseName = CheckBox(this).apply {
            text = "Имя телефона в основном BLE-объявлении"
            isChecked = getSharedPreferences("bridge", Context.MODE_PRIVATE)
                .getBoolean("advertise_name_primary", false) // По умолчанию false для 100% совместимости с CC600!
        }
        root.addView(checkAdvertiseName)
        root.addView(TextView(this).apply {
            text = "Рекомендуется оставить галочку СНЯТОЙ: тогда UUID мощности 0x1818 гарантированно помещается в основной пакет, а имя идёт в Scan Response."
        })

        val btnStart = Button(this).apply {
            text = "1. Запустить датчик мощности (GlucoPower)"
            setOnClickListener {
                if (ensureBlePermissions()) {
                    startBridgeService(BridgeMode.POWER)
                }
            }
        }
        root.addView(btnStart)
        root.addView(Button(this).apply {
            text = "Глюкоза через ЧСС: ммоль/л ×10"
            setOnClickListener {
                if (ensureBlePermissions()) startBridgeService(BridgeMode.HEART_GLUCOSE)
            }
        })
        root.addView(TextView(this).apply {
            text = "В режиме ЧСС 84 означает 8,4 ммоль/л. Это глюкоза, не пульс; на CC600 используется поле ЧСС."
        })

        val btnTest = Button(this).apply {
            text = "2. Тест мощности: постоянно 93 Вт"
            setOnClickListener {
                if (ensureBlePermissions()) {
                    startBridgeService(BridgeMode.POWER_TEST)
                }
            }
        }
        root.addView(btnTest)
        root.addView(TextView(this).apply {
            text = "Запустите поиск и подключение датчика на CC600. Оба профиля (Мощность и ЧСС) теперь активны одновременно!"
        })

        root.addView(Button(this).apply {
            text = "Диагностика BLE: тестовый пульсометр 93"
            setOnClickListener {
                if (ensureBlePermissions()) startBridgeService(BridgeMode.HEART_TEST)
            }
        }))

        val btnStop = Button(this).apply {
            text = "Остановить трансляцию"
            setOnClickListener {
                stopService(Intent(this@MainActivity, GlucoBleBridgeService::class.java))
                statusText.text = "Статус: трансляция остановлена"
                Toast.makeText(this@MainActivity, "Трансляция остановлена", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(btnStop)
        root.addView(Button(this).apply {
            text = "Скопировать диагностику BLE"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("GlucoPower BLE", lastDiagnostics))
                Toast.makeText(this@MainActivity, "Журнал скопирован — его можно вставить в сообщение", Toast.LENGTH_LONG).show()
            }
        })

        val btnBattery = Button(this).apply {
            text = "3. Разрешить работу в фоне (батарея)"
            setOnClickListener {
                val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (power.isIgnoringBatteryOptimizations(packageName)) {
                    Toast.makeText(this@MainActivity, "Оптимизация уже отключена. Проверьте также автозапуск в настройках телефона.", Toast.LENGTH_LONG).show()
                } else {
                    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"))
                    if (request.resolveActivity(packageManager) != null) startActivity(request)
                    else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
        }
        root.addView(btnBattery)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(GlucoBleBridgeService.ACTION_UI_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(uiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(uiReceiver, filter)
        }
    }

    override fun onPause() {
        runCatching { unregisterReceiver(uiReceiver) }
        super.onPause()
    }

    private fun ensureBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = mutableListOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val missing = needed.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), 101)
                return false
            }
        }
        return true
    }

    private fun startBridgeService(mode: BridgeMode = BridgeMode.POWER) {
        val serviceIntent = Intent(this, GlucoBleBridgeService::class.java).apply {
            putExtra(GlucoBleBridgeService.EXTRA_MODE, mode.name)
            putExtra(GlucoBleBridgeService.EXTRA_MULTIPLY_BY_10, checkMultiply10.isChecked)
            putExtra(GlucoBleBridgeService.EXTRA_ADVERTISE_NAME_PRIMARY, checkAdvertiseName.isChecked)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Запуск BLE: статус появится на экране", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Фоновый сервис, принимающий глюкозу из xDrip+ / GlucoDataHandler и транслирующий её
 * одновременно по профилям BLE Cycling Power (0x1818) и Heart Rate (0x180D) на Geoid CC600.
 */
@SuppressLint("MissingPermission", "WakelockTimeout")
class GlucoBleBridgeService : Service() {

    companion object {
        private const val TAG = "GlucoBleBridge"
        private const val CHANNEL_ID = "gluco_ble_power_channel"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_MULTIPLY_BY_10 = "extra_multiply_by_10"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_TEST_MMOL = "extra_test_mmol"
        const val EXTRA_ADVERTISE_NAME_PRIMARY = "extra_advertise_name_primary"
        const val ACTION_UI_UPDATE = "com.example.glucoble.UI_UPDATE"

        private const val ACTION_ENCRYPTION_CHANGE = "android.bluetooth.device.action.ENCRYPTION_CHANGE"
        private const val EXTRA_ENCRYPTION_STATUS = "android.bluetooth.device.extra.ENCRYPTION_STATUS"
        private const val EXTRA_ENCRYPTION_ENABLED = "android.bluetooth.device.extra.ENCRYPTION_ENABLED"
        private const val EXTRA_ENCRYPTION_KEY_SIZE = "android.bluetooth.device.extra.KEY_SIZE"
        private const val EXTRA_ENCRYPTION_ALGORITHM = "android.bluetooth.device.extra.EXTRA_ENCRYPTION_ALGORITHM"

        private const val ACTION_XDRIP_BG = "com.eveningoutpost.dexdrip.BgEstimate"
        private const val EXTRA_XDRIP_BG_MGDL = "com.eveningoutpost.dexdrip.Extras.BgEstimate"

        private const val ACTION_GLUCODATA_MINUTE = "glucodata.Minute"
        private const val ACTION_GDH_GLUCODATA = "de.michelinside.glucodatahandler.GLUCODATA"
        private const val EXTRA_GLUCODATA_MGDL = "mgdl"

        private fun bleUuid(shortUuid: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", shortUuid))

        val CCCD_UUID: UUID = bleUuid(0x2902)
        val SERVICE_CYCLING_POWER: UUID = bleUuid(0x1818)
        val CHAR_POWER_MEASUREMENT: UUID = bleUuid(0x2A63)
        val CHAR_POWER_FEATURE: UUID = bleUuid(0x2A65)
        val CHAR_SENSOR_LOCATION: UUID = bleUuid(0x2A5D)
        val SERVICE_HEART_RATE: UUID = bleUuid(0x180D)
        val CHAR_HEART_MEASUREMENT: UUID = bleUuid(0x2A37)
        val CHAR_BODY_SENSOR_LOCATION: UUID = bleUuid(0x2A38)
        val SERVICE_DEVICE_INFORMATION: UUID = bleUuid(0x180A)
        val CHAR_MANUFACTURER: UUID = bleUuid(0x2A29)
        val CHAR_MODEL: UUID = bleUuid(0x2A24)
    }

    private var bluetoothManager: BluetoothManager? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    // Раздельные списки подписчиков для Мощности (0x2A63) и ЧСС (0x2A37)
    private val powerSubscribedDevices = CopyOnWriteArraySet<BluetoothDevice>()
    private val heartSubscribedDevices = CopyOnWriteArraySet<BluetoothDevice>()
    private val subscribedDevices: Set<BluetoothDevice>
        get() = (powerSubscribedDevices + heartSubscribedDevices)

    private var bleStatus = "не запущен"
    private var mode = BridgeMode.POWER
    private val heartTest: Boolean get() = mode == BridgeMode.HEART_TEST
    private val heartGlucose: Boolean get() = mode == BridgeMode.HEART_GLUCOSE
    private val heartProfile: Boolean get() = mode.heartProfile
    private val modeLabel: String get() = when (mode) {
        BridgeMode.POWER -> "Мощность (0x1818 + 0x180D)"
        BridgeMode.POWER_TEST -> "ТЕСТ мощности 93 Вт (0x1818 + 0x180D)"
        BridgeMode.HEART_GLUCOSE -> "Глюкоза через ЧСС ×10 (0x180D + 0x1818)"
        BridgeMode.HEART_TEST -> "ТЕСТ пульсометра 93 (0x180D + 0x1818)"
    }

    private val bleEvents = java.util.ArrayDeque<String>()
    private val peerIds = linkedMapOf<BluetoothDevice, Int>()
    private var submitted = 0
    private var completed = 0
    private var sendErrors = 0
    private var lastTransfer = "ожидание подписки"
    private var destroyed = false
    private var bleGeneration = 0
    private var currentAdvertiseCallback: AdvertiseCallback? = null
    private val activeService: UUID get() = bleUuid(BridgeProtocol.serviceUuid(heartProfile))
    private val activeMeasurement: UUID get() = if (heartProfile) CHAR_HEART_MEASUREMENT else CHAR_POWER_MEASUREMENT
    private val connectedDevices = CopyOnWriteArraySet<BluetoothDevice>()
    private val gattAccessClients = CopyOnWriteArraySet<BluetoothDevice>()
    private val aclDevices = CopyOnWriteArraySet<BluetoothDevice>()
    private var systemReceiverRegistered = false

    // Счётчики вращения педалей (Crank Revolution Data) для эмуляции живого измерителя мощности (80 RPM)
    private var cumulativeCrankRevs = 0
    private var lastCrankEventTime1024 = 0

    // Очередь последовательного добавления GATT-сервисов
    private val serviceInitQueue = java.util.ArrayDeque<BluetoothGattService>()

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

    private var multiplyBy10: Boolean = true
    private var advertiseNamePrimary: Boolean = false
    private var isBleRunning: Boolean = false
    private var isBleStarting: Boolean = false

    @Volatile
    private var currentMmol: Double = 0.0

    private var lastReadingTime: Long = 0L

    @Volatile
    private var lastSource: String = "ожидание пакета от xDrip+ / GDH"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpExecutor = Executors.newSingleThreadScheduledExecutor()

    // Ежесекундная отправка данных на велокомпьютер Geoid CC600
    private val bleTickRunnable = object : Runnable {
        override fun run() {
            broadcastPowerMeasurement()
            notifyUi()
            if (System.currentTimeMillis() - lastNotificationRefresh >= 60000) refreshStatus()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private val glucoseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    mainHandler.post { if (!isBleRunning && !isBleStarting) startBlePowerPeripheral() }
                } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                    mainHandler.post {
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

    private val bluetoothEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val device = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    aclDevices.add(device)
                    recordBle("ACL UP ${peer(device)}: transport=${intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, 0)}")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    aclDevices.remove(device)
                    recordBle("ACL DOWN ${peer(device)}")
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> recordBle("BOND ${peer(device)}: " +
                    "${intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)} -> ${intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)}")
                "android.bluetooth.device.action.KEY_MISSING" -> recordBle("KEY_MISSING ${peer(device)}: удалите устройство из сопряжённых и переподключите")
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
        val requestedMode = if (intent == null) mode else BridgeMode.restore(intent.getStringExtra(EXTRA_MODE))
        val nextMode = if (requestedMode == BridgeMode.POWER && intent?.hasExtra(EXTRA_TEST_MMOL) == true)
            BridgeMode.POWER_TEST else requestedMode
        val nextNamePrimary = intent?.getBooleanExtra(EXTRA_ADVERTISE_NAME_PRIMARY, advertiseNamePrimary)
            ?: advertiseNamePrimary

        val needRestartAdv = (nextNamePrimary != advertiseNamePrimary) || (nextMode.heartProfile != heartProfile)
        advertiseNamePrimary = nextNamePrimary
        savedReading.edit().putBoolean("advertise_name_primary", advertiseNamePrimary).apply()

        if (nextMode != mode) {
            recordBle("Смена режима: $mode -> $nextMode")
        }
        mode = nextMode
        savedReading.edit().putString("mode", mode.name).apply()

        if (needRestartAdv && (isBleRunning || isBleStarting)) {
            stopBlePeripheral()
        }

        if (lastSource.startsWith("ТЕСТ")) restoreReading()
        multiplyBy10 = intent?.getBooleanExtra(EXTRA_MULTIPLY_BY_10, multiplyBy10) ?: multiplyBy10
        savedReading.edit().putBoolean("multiply", multiplyBy10).apply()

        startForeground(NOTIFICATION_ID, buildNotification())
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

        mainHandler.removeCallbacks(bleTickRunnable)
        mainHandler.post(bleTickRunnable)

        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        httpExecutor.shutdownNow()
        runCatching { unregisterReceiver(glucoseReceiver) }
        if (systemReceiverRegistered) runCatching { unregisterReceiver(bluetoothEventsReceiver) }
        stopBlePeripheral()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerGlucoseBroadcasts() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(ACTION_XDRIP_BG)
            addAction(ACTION_GLUCODATA_MINUTE)
            addAction(ACTION_GDH_GLUCODATA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(glucoseReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(glucoseReceiver, filter)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerBluetoothEvents() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction("android.bluetooth.device.action.KEY_MISSING")
            addAction(ACTION_ENCRYPTION_CHANGE)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothEventsReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(bluetoothEventsReceiver, filter)
        systemReceiverRegistered = true
    }

    private fun observeGattAccess(device: BluetoothDevice) {
        gattAccessClients.add(device)
        connectedDevices.add(device)
        // ВАЖНО: Не вызываем gattServer?.connect(device, false) на Vivo/Android 16!
        // Это создаёт встречный L2CAP-запрос и блокирует передачу уведомлений на OriginOS.
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
        return "status=${number(EXTRA_ENCRYPTION_STATUS)}, enabled=$enabled, " +
            "keySize=${number(EXTRA_ENCRYPTION_KEY_SIZE)}, algorithm=$algorithm"
    }

    private fun restoreSettings() {
        restoreReading()
        mode = BridgeMode.restore(savedReading.getString("mode", null))
        multiplyBy10 = savedReading.getBoolean("multiply", true)
        advertiseNamePrimary = savedReading.getBoolean("advertise_name_primary", false)
    }

    private fun restoreReading() {
        lastSource = savedReading.getString("source", null) ?: "ожидание xDrip+"
        val realReading = !lastSource.startsWith("ТЕСТ")
        currentMmol = if (realReading) savedReading.getString("mmol", null)?.toDoubleOrNull() ?: 0.0 else 0.0
        lastReadingTime = if (realReading) savedReading.getLong("timestamp", 0L) else 0L
        if (!realReading) lastSource = "ожидание xDrip+"
    }

    private fun updateGlucoseValue(rawValue: Double, source: String, mgdl: Boolean,
                                   timestamp: Long = System.currentTimeMillis()) {
        val mmol = BridgeProtocol.mmol(rawValue, mgdl)
        mainHandler.post {
            if (destroyed) return@post
            val previous = if (lastSource.startsWith("ТЕСТ") && !source.startsWith("ТЕСТ")) 0L else lastReadingTime
            if (!BridgeProtocol.accepts(mmol, timestamp, previous, System.currentTimeMillis())) {
                recordBle("GLUCOSE REJECTED: $source; недопустимое значение или время измерения")
                return@post
            }
            if (timestamp != lastReadingTime || source != lastSource)
                recordBle("GLUCOSE INPUT: $source; время=$timestamp")
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

    private fun getWattsValue(): Int = BridgeProtocol.watts(currentMmol, multiplyBy10)
    private fun outputText(): String = when {
        mode == BridgeMode.POWER_TEST -> "ТЕСТ: 93 Вт (и 93 ЧСС)"
        heartTest -> "ТЕСТ: 93 в поле ЧСС (и 93 Вт)"
        lastReadingTime == 0L -> "ожидание измерения"
        heartGlucose -> "${BridgeProtocol.watts(currentMmol, true)} в поле ЧСС — глюкоза ×10"
        else -> "${getWattsValue()} Вт"
    }

    private fun readingTime(): String = if (lastReadingTime == 0L) "ещё нет данных" else
        SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(lastReadingTime))

    private fun refreshStatus() {
        lastNotificationRefresh = System.currentTimeMillis()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
        notifyUi()
    }

    private fun notifyUi() {
        val uiIntent = Intent(ACTION_UI_UPDATE).apply {
            setPackage(packageName)
            putExtra("mmol", currentMmol)
            putExtra("output", outputText())
            putExtra("source", lastSource)
            putExtra("connected", subscribedDevices.size)
            putExtra("ble", "$modeLabel. " +
                bleStatus + "; соединений: ${connectedDevices.size} (подписок Мощность: ${powerSubscribedDevices.size}, ЧСС: ${heartSubscribedDevices.size})" +
                "\nОтправка: ${transferStage()}; отправлено: $submitted, подтверждено: $completed, ошибок: $sendErrors")
            putExtra("diagnostics", diagnosticReport())
            putExtra("updated", readingTime())
        }
        sendBroadcast(uiIntent)
    }

    @Synchronized
    private fun peer(device: BluetoothDevice): String {
        val id = peerIds.getOrPut(device) { peerIds.size + 1 }
        val name = runCatching { device.name }.getOrNull() ?: "имя неизвестно"
        return "клиент#$id ($name)"
    }

    @Synchronized
    private fun recordBle(event: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
        bleEvents.addLast("$time [сеанс $bleGeneration] $event")
        while (bleEvents.size > 100) bleEvents.removeFirst()
        Log.i(TAG, event)
    }

    @Synchronized
    private fun diagnosticReport(): String = buildString {
        appendLine("GlucoPower 1.12; ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Отчёт создан: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(Date())}")
        appendLine("Прошивка телефона: ${Build.DISPLAY}")
        appendLine("Имя в основном BLE-объявлении: $advertiseNamePrimary")
        appendLine("Режим: $modeLabel; приоритетный профиль: $activeService")
        appendLine("Соединения: ${connectedDevices.size}; подписки Мощность: ${powerSubscribedDevices.size}, ЧСС: ${heartSubscribedDevices.size}")
        appendLine("Наблюдаемых системных ACL: ${aclDevices.size}")
        appendLine("Клиенты, обратившиеся к GATT приложения: " +
            gattAccessClients.takeIf { it.isNotEmpty() }?.joinToString { peer(it) }.orEmpty().ifEmpty { "пока нет" })
        appendLine("Подписчики измерений: " +
            subscribedDevices.takeIf { it.isNotEmpty() }?.joinToString { peer(it) }.orEmpty().ifEmpty { "пока нет" })
        appendLine("Данные: " + when {
            mode.test -> "тестовые 93; не зависят от входной глюкозы"
            lastReadingTime == 0L -> "входное измерение ещё не получено; источник: $lastSource"
            else -> "измерение получено; источник: $lastSource; время: ${readingTime()}"
        })
        appendLine("Отправлено: $submitted; подтверждено: $completed; ошибок: $sendErrors")
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
                            if (timestamp > 0) updateGlucoseValue(sgvMgdl, "Локальный сервер :17580", true, timestamp)
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
            }
        }, 2, 15, java.util.concurrent.TimeUnit.SECONDS)
    }

    // ========================================================================
    // BLE GATT Server — Одновременная поддержка Cycling Power (0x1818) и Heart Rate (0x180D)
    // ========================================================================

    private fun startBlePowerPeripheral() {
        stopBlePeripheral()

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            bleStatus = "Включите Bluetooth и снова нажмите запуск"
            refreshStatus()
            return
        }

        // Пытаемся задать короткое имя адаптера (на случай длинного имени Vivo X300 Ultra)
        runCatching {
            if ((adapter.name?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 18) {
                adapter.name = "GlucoPM"
            }
        }

        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            bleStatus = "Телефон не поддерживает BLE Peripheral / Advertising"
            refreshStatus()
            return
        }
        isBleStarting = true
        recordBle("Запуск GATT (оба профиля: 0x1818 Power + 0x180D HR)")
        bleStatus = "регистрация GATT-сервисов"
        mainHandler.postDelayed(startupTimeout, 20000)
        try {
            gattServer = bluetoothManager?.openGattServer(this, createGattCallback(bleGeneration))
            serviceInitQueue.clear()
            // Регистрируем ВСЕ ТРИ сервиса по очереди!
            // Благодаря этому CC600 всегда найдёт 0x1818 даже если закэшировал 0x180D.
            serviceInitQueue.add(createCyclingPowerService())
            serviceInitQueue.add(createHeartService())
            serviceInitQueue.add(createDeviceInformationService())

            val firstService = serviceInitQueue.pollFirst()
            if (firstService == null || gattServer?.addService(firstService) != true) {
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
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .apply { if (Build.VERSION.SDK_INT >= 34) setDiscoverable(true) }
            .build()

        val name = bluetoothManager?.adapter?.name
        val nameInPrimary = advertiseNamePrimary && BridgeProtocol.canIncludeName(name, primary = true)
        val nameInScanResponse = !nameInPrimary && BridgeProtocol.canIncludeName(name, primary = false)

        // КРИТИЧНО ДЛЯ GEOID CC600:
        // В основном пакете передаём ОБА UUID (0x1818 и 0x180D) — это занимает всего 6 байт!
        // Тогда CC600 одновременно видит телефон и в поиске Мощности, и в поиске ЧСС!
        val dataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(nameInPrimary)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE_CYCLING_POWER))
            .addServiceUuid(ParcelUuid(SERVICE_HEART_RATE))

        val data = dataBuilder.build()

        // В Scan Response кладем ТОЛЬКО имя (без дублирования UUID, чтобы не сбивать парсер CC600)
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(nameInScanResponse)
            .setIncludeTxPowerLevel(false)
            .build()

        val namePosition = when {
            nameInPrimary -> "в основном пакете"
            nameInScanResponse -> "в scan response"
            else -> "скрыто (слишком длинное имя Bluetooth)"
        }
        recordBle("ADV: UUIDs=[0x1818, 0x180D] в основном пакете; имя ($name) $namePosition")
        bleStatus = "реклама запущена (0x1818 Мощность + 0x180D ЧСС); имя: ${name ?: "нет"}"
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

    private fun stopBlePeripheral() {
        bleGeneration++
        mainHandler.removeCallbacks(startupTimeout)
        serviceInitQueue.clear()
        runCatching { currentAdvertiseCallback?.let { advertiser?.stopAdvertising(it) } }
        currentAdvertiseCallback = null
        runCatching { gattServer?.close() }
        powerSubscribedDevices.clear()
        heartSubscribedDevices.clear()
        connectedDevices.clear()
        gattAccessClients.clear()
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
        val measurement = BluetoothGattCharacteristic(
            CHAR_HEART_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        measurement.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        val location = BluetoothGattCharacteristic(
            CHAR_BODY_SENSOR_LOCATION,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(measurement)
        service.addCharacteristic(location)
        return service
    }

    private fun createDeviceInformationService(): BluetoothGattService =
        BluetoothGattService(SERVICE_DEVICE_INFORMATION, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            listOf(CHAR_MANUFACTURER, CHAR_MODEL).forEach { uuid ->
                addCharacteristic(
                    BluetoothGattCharacteristic(
                        uuid,
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ
                    )
                )
            }
        }

    private fun createCyclingPowerService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_CYCLING_POWER, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Характеристика измерения мощности (0x2A63) — Notify + Read
        val measurement = BluetoothGattCharacteristic(
            CHAR_POWER_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        measurement.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )

        // Характеристика возможностей датчика мощности (0x2A65) — Read
        // Bit 3 (0x00000008) = Crank Revolution Data Supported
        val feature = BluetoothGattCharacteristic(
            CHAR_POWER_FEATURE,
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

    private fun currentPowerPayload(): ByteArray {
        val watts = if (mode.test) 93 else getWattsValue()
        // Каждую секунду увеличиваем счётчик оборотов шатуна на 1 (~60 об/мин) и время на 1024 (1 сек в единицах 1/1024 с),
        // чтобы велокомпьютер Geoid CC600 видел, что педали крутятся, и не уходил в прочерки "--"!
        cumulativeCrankRevs = (cumulativeCrankRevs + 1) and 0xFFFF
        lastCrankEventTime1024 = (lastCrankEventTime1024 + 1024) and 0xFFFF
        return BridgeProtocol.measurementWithCrank(watts, cumulativeCrankRevs, lastCrankEventTime1024)
    }

    private fun currentHeartPayload(): ByteArray {
        val bpm = if (mode.test) 93 else BridgeProtocol.watts(currentMmol, true)
        return BridgeProtocol.heartMeasurement(bpm)
    }

    private fun broadcastPowerMeasurement() {
        val server = gattServer ?: return
        // На Vivo X300 Ultra некоторые прошивки Geoid CC600 ждут Notify сразу после подключения или чтения,
        // поэтому если устройство подключено и читало GATT, но не завершило CCCD, тоже пробуем слать в активный профиль.
        val powerTargets = if (powerSubscribedDevices.isNotEmpty()) powerSubscribedDevices
        else if (!heartProfile && gattAccessClients.isNotEmpty()) gattAccessClients else emptySet()

        val heartTargets = if (heartSubscribedDevices.isNotEmpty()) heartSubscribedDevices
        else if (heartProfile && gattAccessClients.isNotEmpty()) gattAccessClients else emptySet()

        if (powerTargets.isEmpty() && heartTargets.isEmpty()) return

        if (!mode.test && lastReadingTime == 0L) {
            val waiting = "подписка есть; ожидается входное измерение xDrip+/GDH"
            if (lastTransfer != waiting) recordBle("DATA WAIT: $waiting")
            lastTransfer = waiting
            return
        }

        // 1. Отправляем пакет мощности всем подписчикам 0x2A63
        if (powerTargets.isNotEmpty()) {
            val cpChar = server.getService(SERVICE_CYCLING_POWER)?.getCharacteristic(CHAR_POWER_MEASUREMENT)
            if (cpChar != null) {
                val payload = currentPowerPayload()
                for (device in powerTargets) {
                    sendNotificationDirect(server, device, cpChar, payload, "POWER")
                }
            }
        }

        // 2. Отправляем пакет ЧСС всем подписчикам 0x2A37
        if (heartTargets.isNotEmpty()) {
            val hrChar = server.getService(SERVICE_HEART_RATE)?.getCharacteristic(CHAR_HEART_MEASUREMENT)
            if (hrChar != null) {
                val payload = currentHeartPayload()
                for (device in heartTargets) {
                    sendNotificationDirect(server, device, hrChar, payload, "HR")
                }
            }
        }
    }

    private fun sendNotificationDirect(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        tag: String
    ) {
        val status = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, payload)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                if (server.notifyCharacteristicChanged(device, characteristic, false)) BluetoothGatt.GATT_SUCCESS else -1
            }
        } catch (e: RuntimeException) {
            recordBle("NOTIFY($tag) exception ${e.javaClass.simpleName}: ${e.message}")
            -1
        }
        if (status == BluetoothGatt.GATT_SUCCESS) {
            submitted++
            lastTransfer = "отправлено ($tag)"
            if (submitted == 1 || submitted % 30 == 0) {
                recordBle("NOTIFY($tag) ${peer(device)}: принято Android (#$submitted), ${payload.size} байт")
            }
        } else {
            sendErrors++
            lastTransfer = "ошибка отправки ($tag): $status"
            recordBle("NOTIFY($tag) ${peer(device)}: код ошибки $status")
        }
    }

    private fun createAdvertiseCallback(session: Int) = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            mainHandler.post {
                if (session != bleGeneration || !isBleStarting) return@post
                mainHandler.removeCallbacks(startupTimeout)
                isBleRunning = true
                isBleStarting = false
                recordBle("ADV: Android подтвердил запуск")
                bleStatus = "реклама 0x1818 (Мощность) + 0x180D (ЧСС) активна"
                refreshStatus()
            }
        }

        override fun onStartFailure(errorCode: Int) {
            mainHandler.post {
                if (session != bleGeneration || !isBleStarting) return@post
                stopBlePeripheral()
                bleStatus = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "BLE: рекламный пакет слишком большой (снимите галочку имени в основном пакете)"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "BLE: слишком много рекламодателей; перезагрузите Bluetooth"
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
            mainHandler.post {
                if (session != bleGeneration || !isBleStarting || gattServer == null) return@post
                recordBle("GATT: зарегистрирован ${service.uuid}, status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    stopBlePeripheral()
                    bleStatus = "Ошибка регистрации GATT: $status"
                    refreshStatus()
                    return@post
                }
                val nextService = serviceInitQueue.pollFirst()
                if (nextService != null) {
                    if (gattServer?.addService(nextService) != true) {
                        stopBlePeripheral()
                        bleStatus = "Ошибка регистрации сервиса ${nextService.uuid}"
                        refreshStatus()
                    }
                } else {
                    // Все сервисы (0x1818, 0x180D, 0x180A) добавлены! Запускаем рекламу.
                    advertisePowerService()
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (session != bleGeneration) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device)
                recordBle("LINK UP ${peer(device)}: status=$status")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device)
                gattAccessClients.remove(device)
                powerSubscribedDevices.remove(device)
                heartSubscribedDevices.remove(device)
                recordBle("LINK DOWN ${peer(device)}: status=$status")
            }
            mainHandler.post { notifyUi() }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (session != bleGeneration) return
            observeGattAccess(device)

            // КРИТИЧНО ДЛЯ VIVO X300 ULTRA: отвечаем синхронно прямо в Binder-потоке без mainHandler.post!
            val bytes = when (characteristic.uuid) {
                // Bit 3 (0x08) = Crank Revolution Data Supported
                CHAR_POWER_FEATURE -> byteArrayOf(0x08, 0x00, 0x00, 0x00)
                CHAR_SENSOR_LOCATION -> byteArrayOf(5) // 5 = Left Crank
                CHAR_BODY_SENSOR_LOCATION -> byteArrayOf(1) // 1 = Chest
                CHAR_POWER_MEASUREMENT -> currentPowerPayload()
                CHAR_HEART_MEASUREMENT -> currentHeartPayload()
                CHAR_MANUFACTURER -> "GlucoPower".toByteArray(Charsets.UTF_8)
                CHAR_MODEL -> "GlucoPM".toByteArray(Charsets.UTF_8)
                else -> null
            }
            val status = when {
                bytes == null -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                offset < 0 || offset > bytes.size -> BluetoothGatt.GATT_INVALID_OFFSET
                else -> BluetoothGatt.GATT_SUCCESS
            }
            val responseSlice = if (status == BluetoothGatt.GATT_SUCCESS) bytes!!.copyOfRange(offset, bytes.size) else null
            gattServer?.sendResponse(device, requestId, status, offset, responseSlice)

            recordBle("READ ${peer(device)}: ${characteristic.uuid}, offset=$offset, status=$status")
            mainHandler.post { notifyUi() }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (session != bleGeneration) return
            observeGattAccess(device)

            val charUuid = descriptor.characteristic.uuid
            val known = descriptor.uuid == CCCD_UUID &&
                (charUuid == CHAR_POWER_MEASUREMENT || charUuid == CHAR_HEART_MEASUREMENT)
            val isSub = if (charUuid == CHAR_POWER_MEASUREMENT) powerSubscribedDevices.contains(device)
            else heartSubscribedDevices.contains(device)

            val value = if (isSub) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE

            val status = when {
                !known -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                offset < 0 || offset > value.size -> BluetoothGatt.GATT_INVALID_OFFSET
                else -> BluetoothGatt.GATT_SUCCESS
            }
            val responseSlice = if (status == BluetoothGatt.GATT_SUCCESS) value.copyOfRange(offset, value.size) else null
            gattServer?.sendResponse(device, requestId, status, offset, responseSlice)

            recordBle("READ CCCD ${peer(device)}: $charUuid, status=$status")
            mainHandler.post { notifyUi() }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (session != bleGeneration) return
            observeGattAccess(device)

            val bytes = value.copyOf()
            val charUuid = descriptor.characteristic.uuid
            val enabled = bytes.isNotEmpty() && (bytes[0].toInt() and 0x03) != 0
            val disabled = bytes.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)

            val status = when {
                descriptor.uuid != CCCD_UUID -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                else -> BluetoothGatt.GATT_SUCCESS
            }

            // КРИТИЧНО ДЛЯ VIVO X300 ULTRA: отправляем sendResponse МГНОВЕННО в том же потоке!
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, status, 0, null)
            }

            recordBle("WRITE CCCD ${peer(device)}: $charUuid, value=" +
                bytes.joinToString("") { "%02x".format(it.toInt() and 255) } + ", enabled=$enabled")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (charUuid == CHAR_POWER_MEASUREMENT) {
                    if (enabled) {
                        powerSubscribedDevices.add(device)
                        lastTransfer = "подписка на Мощность (0x2A63) получена"
                    } else if (disabled) {
                        powerSubscribedDevices.remove(device)
                    }
                } else if (charUuid == CHAR_HEART_MEASUREMENT) {
                    if (enabled) {
                        heartSubscribedDevices.add(device)
                        lastTransfer = "подписка на ЧСС (0x2A37) получена"
                    } else if (disabled) {
                        heartSubscribedDevices.remove(device)
                    }
                }
            }

            // Первое уведомление отправляем через 150 мс после ответа на CCCD, чтобы контроллер Vivo завершил ATT Write Response
            mainHandler.postDelayed({
                if (session == bleGeneration) {
                    broadcastPowerMeasurement()
                    notifyUi()
                }
            }, 150L)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            if (session != bleGeneration) return
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            recordBle("EXECUTE WRITE ${peer(device)}: execute=$execute")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (session != bleGeneration) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                completed++
                lastTransfer = "доставлено на велокомпьютер"
                if (completed == 1 || completed % 30 == 0) {
                    recordBle("SENT ${peer(device)}: status=0 (#$completed)")
                }
            } else {
                sendErrors++
                lastTransfer = "ошибка доставки: $status"
                recordBle("SENT ${peer(device)}: status=$status")
            }
            mainHandler.post { notifyUi() }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GlucoBLE Датчик мощности",
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
        val details = if (mode.test) "${outputText()}.\nДля возврата нажмите «Запустить датчик мощности».\nBLE: $bleStatus\n${transferStage()}"
        else "$text\n$updateText\nBLE: $bleStatus\nПодписки: ${subscribedDevices.size}; ${transferStage()}"
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return builder
            .setContentTitle(
                if (mode.test) "GlucoPower: ${outputText()}"
                else if (lastReadingTime == 0L) "GlucoPower: ожидание xDrip+"
                else String.format("%.1f ммоль/л → %s", currentMmol, outputText())
            )
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
