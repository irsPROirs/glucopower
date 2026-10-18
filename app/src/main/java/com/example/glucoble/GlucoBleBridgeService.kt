package com.example.glucoble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

@SuppressLint("MissingPermission")
class GlucoBleBridgeService : Service() {

    companion object {
        private const val TAG = "GlucoBlePower"
        private const val CHANNEL_ID = "gluco_ble_channel"
        private const val NOTIFICATION_ID = 101

        // xDrip Broadcast константы
        private const val ACTION_XDRIP_BG = "com.eveningoutpost.dexdrip.BgEstimate"
        private const val EXTRA_BG_ESTIMATE = "com.eveningoutpost.dexdrip.Extras.BgEstimate"

        // Bluetooth SIG UUIDs для Cycling Power Service (CPS)
        val CPS_SERVICE_UUID: UUID = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")
        val CPS_MEASUREMENT_UUID: UUID = UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb")
        val CPS_FEATURE_UUID: UUID = UUID.fromString("00002a65-0000-1000-8000-00805f9b34fb")
        val SENSOR_LOCATION_UUID: UUID = UUID.fromString("00002a5d-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var powerMeasurementChar: BluetoothGattCharacteristic? = null

    private val connectedDevices = CopyOnWriteArraySet<BluetoothDevice>()
    private val handler = Handler(Looper.getMainLooper())

    // Текущее значение глюкозы для передачи в виде Ватт (по умолчанию 120 для теста до прихода пакета xDrip)
    @Volatile
    private var currentGlucoseWatts: Int = 120

    // Счетчики для эмуляции вращения педалей (некоторые велокомпьютеры требуют изменения счетчика событий)
    private var cumulativeCrankRevs: Int = 0
    private var lastCrankEventTime: Int = 0 // в 1/1024 секунды

    // Приемник широковещательных сообщений xDrip
    private val xdripReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_XDRIP_BG) {
                val bgMgDl = intent.getDoubleExtra(EXTRA_BG_ESTIMATE, -1.0)
                if (bgMgDl > 0) {
                    // Если нужно передавать в ммоль/л (например, 5.5 -> 55 Вт или 5 Вт), раскомментируйте нужную формулу:
                    // currentGlucoseWatts = Math.round((bgMgDl / 18.0182) * 10).toInt() // 5.5 ммоль/л -> 55 Вт
                    currentGlucoseWatts = Math.round(bgMgDl).toInt() // мг/дл напрямую (например, 120 мг/дл -> 120 Вт)
                    Log.d(TAG, "Получены данные xDrip: $bgMgDl mg/dL -> $currentGlucoseWatts W")
                    updateNotification("Глюкоза: $currentGlucoseWatts (Подключено: ${connectedDevices.size})")
                }
            }
        }
    }

    // Периодическая отправка данных каждую секунду (1 Гц — стандарт для датчиков мощности)
    private val broadcastRunnable = object : Runnable {
        override fun run() {
            sendPowerNotification()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Запуск датчика мощности..."))

        registerXdripReceiver()
        initBluetooth()
        handler.postDelayed(broadcastRunnable, 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Позволяет передавать тестовое значение через Intent из Activity, если нужно
        intent?.getIntExtra("TEST_VALUE", -1)?.let {
            if (it > 0) currentGlucoseWatts = it
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(xdripReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отписки ресивера", e)
        }
        stopBle()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerXdripReceiver() {
        val filter = IntentFilter(ACTION_XDRIP_BG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // На Android 13–16 обязательно указывать RECEIVER_EXPORTED для внешних Broadcast
            registerReceiver(xdripReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(xdripReceiver, filter)
        }
    }

    private fun initBluetooth() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth выключен или недоступен")
            updateNotification("Ошибка: включите Bluetooth")
            return
        }

        // Короткое имя помогает велокомпьютерам быстрее парсить Scan Response
        try {
            bluetoothAdapter?.name = "GlucoPower"
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось изменить имя адаптера", e)
        }

        setupGattServer()
        startAdvertising()
    }

    private fun setupGattServer() {
        gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
        val service = BluetoothGattService(CPS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // 1. Обязательная характеристика: Cycling Power Measurement (0x2A63) - NOTIFY
        powerMeasurementChar = BluetoothGattCharacteristic(
            CPS_MEASUREMENT_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            val cccd = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            addDescriptor(cccd)
        }

        // 2. Обязательная характеристика: Cycling Power Feature (0x2A65) - READ
        // Без нее велокомпьютеры (Garmin/Wahoo/iGPSPORT) не считают устройство датчиком мощности!
        val powerFeatureChar = BluetoothGattCharacteristic(
            CPS_FEATURE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        // 3. Обязательная характеристика: Sensor Location (0x2A5D) - READ
        val sensorLocationChar = BluetoothGattCharacteristic(
            SENSOR_LOCATION_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        service.addCharacteristic(powerMeasurementChar)
        service.addCharacteristic(powerFeatureChar)
        service.addCharacteristic(sensorLocationChar)

        gattServer?.addService(service)
    }

    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE Advertising не поддерживается")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // ВАЖНО для Vivo: В основном пакете передаем ТОЛЬКО UUID сервиса (без имени!),
        // чтобы не превысить лимит в 31 байт.
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(CPS_SERVICE_UUID))
            .build()

        // Имя устройства передаем во втором пакете (Scan Response)
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Реклама датчика мощности (0x1818) успешно запущена")
            updateNotification("Датчик мощности активен (${currentGlucoseWatts}W)")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Ошибка запуска рекламы BLE: код $errorCode")
            updateNotification("Ошибка BLE рекламы: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Устройство подключилось: ${device.address}")
                connectedDevices.add(device)
                handler.post {
                    updateNotification("Подключено: ${device.address} (${currentGlucoseWatts}W)")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Устройство отключилось: ${device.address}")
                connectedDevices.remove(device)
                handler.post {
                    updateNotification("Ожидание подключения (${currentGlucoseWatts}W)")
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                CPS_FEATURE_UUID -> {
                    // 4 байта (uint32): Бит 3 (0x00000008) = Crank Revolution Data Supported
                    val featureBytes = byteArrayOf(0x08, 0x00, 0x00, 0x00)
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, featureBytes)
                }
                SENSOR_LOCATION_UUID -> {
                    // 1 байт: 5 = Left Crank (Левый шатун)
                    val locationBytes = byteArrayOf(0x05)
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, locationBytes)
                }
                else -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val value = if (connectedDevices.contains(device)) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                if (value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    Log.d(TAG, "Устройство ${device.address} подписалось на уведомления мощности")
                    connectedDevices.add(device)
                } else if (value != null && value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    Log.d(TAG, "Устройство ${device.address} отписалось от уведомлений")
                }

                // СНАЧАЛА отправляем подтверждение записи велокомпьютеру
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }

                // Отправляем первый пакет данных с небольшой задержкой, чтобы не заблокировать GATT стек на Vivo
                handler.postDelayed({ sendPowerNotification() }, 150L)
            } else if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }
    }

    private fun sendPowerNotification() {
        val server = gattServer ?: return
        val characteristic = powerMeasurementChar ?: return
        if (connectedDevices.isEmpty()) return

        // Формируем пакет Cycling Power Measurement (0x2A63)
        // Flags (2 байта, uint16): 0x0020 (Бит 5 = Crank Revolution Data Present)
        // Instantaneous Power (2 байта, sint16): значение глюкозы в Ваттах
        // Cumulative Crank Revolutions (2 байта, uint16): увеличиваем на 1 каждую секунду (60 об/мин)
        // Last Crank Event Time (2 байта, uint16): шаг 1/1024 секунды (+1024 каждую секунду)
        cumulativeCrankRevs = (cumulativeCrankRevs + 1) and 0xFFFF
        lastCrankEventTime = (lastCrankEventTime + 1024) and 0xFFFF

        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0020.toShort()) // Flags
            putShort(currentGlucoseWatts.coerceIn(0, 2000).toShort()) // Мощность (глюкоза)
            putShort(cumulativeCrankRevs.toShort()) // Обороты шатуна
            putShort(lastCrankEventTime.toShort()) // Время события
        }.array()

        for (device in connectedDevices) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // КРИТИЧНО ДЛЯ ANDROID 13 - 16 (Vivo X300): новый метод API 33+
                    val result = server.notifyCharacteristicChanged(device, characteristic, false, payload)
                    Log.d(TAG, "Отправлено ${currentGlucoseWatts}W на ${device.address}, статус: $result")
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = payload
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, characteristic, false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отправки уведомления на ${device.address}", e)
            }
        }
    }

    private fun stopBle() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка остановки BLE", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GlucoBLE Power Sensor",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GlucoPower BLE")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}