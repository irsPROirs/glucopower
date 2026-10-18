package com.example.glucoble

import android.bluetooth.*
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.AdditionalMatchers.aryEq
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BleDeliveryTest {
    private lateinit var bridge: GlucoBleBridgeService
    private lateinit var server: BluetoothGattServer
    private lateinit var device: BluetoothDevice
    private lateinit var callback: BluetoothGattServerCallback
    private lateinit var measurement: BluetoothGattCharacteristic
    private lateinit var cccd: BluetoothGattDescriptor

    private fun field(name: String, value: Any) {
        bridge.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(bridge, value)
    }
    private fun invoke(name: String): Any? = bridge.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(bridge)
    private fun drain() { shadowOf(Looper.getMainLooper()).idle() }
    private fun subscribe(bytes: ByteArray = byteArrayOf(1, 0)) {
        callback.onDescriptorWriteRequest(device, 42, cccd, false, true, 0, bytes)
        drain()
    }
    private fun report(): String = invoke("diagnosticReport") as String

    @Before fun setUp() {
        // Attach a real Android service/context without starting its HTTP poller or radio.
        bridge = Robolectric.buildService(GlucoBleBridgeService::class.java).get()
        server = mock(BluetoothGattServer::class.java)
        device = mock(BluetoothDevice::class.java)
        `when`(device.name).thenReturn("CC600 test client")
        `when`(server.sendResponse(eq(device), anyInt(), anyInt(), anyInt(), nullable(ByteArray::class.java))).thenReturn(true)
        field("mode", BridgeMode.HEART_TEST)
        field("bleGeneration", 1)
        field("gattServer", server)
        val service = invoke("createHeartService") as BluetoothGattService
        `when`(server.getService(service.uuid)).thenReturn(service)
        measurement = service.getCharacteristic(GlucoBleBridgeService.CHAR_HEART_MEASUREMENT)
        cccd = measurement.getDescriptor(GlucoBleBridgeService.CCCD_UUID)
        callback = bridge.javaClass.getDeclaredMethod("createGattCallback", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(bridge, 1) as BluetoothGattServerCallback
    }
    @After fun tearDown() { bridge.onDestroy(); drain() }

    @Test fun connectionAloneDoesNotCountAsSubscriptionOrSendData() {
        callback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        drain()
        invoke("broadcastPowerMeasurement")
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
        assertTrue(report().contains("Соединения: 1; подписки: 0"))
        assertTrue(report().contains("CC600 test client"))
    }

    @Test fun cccdAckPrecedesFirstHeartMeasurementAndCompletionIsTracked() {
        subscribe()
        val order = inOrder(server)
        order.verify(server).sendResponse(device, 42, BluetoothGatt.GATT_SUCCESS, 0, null)
        order.verify(server).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(0, 93)))
        assertTrue(report().contains("Android принял: 1; завершил успешно: 0"))
        callback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        drain()
        assertTrue(report().contains("Android принял: 1; завершил успешно: 1"))
        assertTrue(report().contains("SUBSCRIBE"))
    }

    @Test fun periodicSamplesWaitForAndroidCompletion() {
        subscribe()
        repeat(3) { invoke("broadcastPowerMeasurement") }
        verify(server, times(1)).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
        callback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        drain()
        verify(server, times(2)).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun cyclingPowerSubscriptionSendsLatestGlucoseAsWatts() {
        field("mode", BridgeMode.POWER)
        field("currentMmol", 8.4)
        field("lastReadingTime", 1000L)
        val service = invoke("createCyclingPowerService") as BluetoothGattService
        `when`(server.getService(service.uuid)).thenReturn(service)
        measurement = service.getCharacteristic(GlucoBleBridgeService.CHAR_POWER_MEASUREMENT)
        cccd = measurement.getDescriptor(GlucoBleBridgeService.CCCD_UUID)
        subscribe()
        verify(server).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(0, 0, 84, 0)))
        field("currentMmol", 9.3)
        invoke("broadcastPowerMeasurement")
        callback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        drain()
        verify(server).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(0, 0, 93, 0)))
    }

    @Test fun failedCccdResponseDoesNotClaimSubscription() {
        `when`(server.sendResponse(eq(device), anyInt(), anyInt(), anyInt(), nullable(ByteArray::class.java))).thenReturn(false)
        subscribe()
        assertTrue(report().contains("подписки: 0"))
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun liveHeartProfileWaitsForGlucoseThenTransmitsXdripUpdatesTimesTen() {
        field("mode", BridgeMode.HEART_GLUCOSE)
        field("multiplyBy10", false) // Power setting must not change the HR glucose scale.
        subscribe()
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
        val receiver = bridge.javaClass.getDeclaredField("glucoseReceiver")
            .apply { isAccessible = true }.get(bridge) as android.content.BroadcastReceiver
        fun reading(mmol: Double, time: Long) {
            receiver.onReceive(bridge, android.content.Intent("com.eveningoutpost.dexdrip.BgEstimate")
                .putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", mmol * 18.0182)
                .putExtra("com.eveningoutpost.dexdrip.Extras.Time", time))
            drain()
            invoke("broadcastPowerMeasurement")
        }
        val now = System.currentTimeMillis()
        reading(8.4, now - 1000)
        verify(server).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(0, 84)))
        reading(9.3, now)
        callback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        drain()
        verify(server).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(0, 93)))
        assertTrue(report().contains("Глюкоза через ЧСС"))
    }

    @Test fun oldSavedTestReadingIsNotUsedAsLiveGlucose() {
        bridge.getSharedPreferences("bridge", android.content.Context.MODE_PRIVATE).edit()
            .putString("source", "ТЕСТ — не данные xDrip+").putString("mmol", "9.3")
            .putLong("timestamp", 1000L).commit()
        field("mode", BridgeMode.HEART_GLUCOSE)
        invoke("restoreReading")
        subscribe()
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
        assertEquals("ожидание измерения", invoke("outputText"))
    }

    @Test fun switchingHeartTestToGlucoseKeepsSubscriptionAndReplacesQueuedTest() {
        field("isBleRunning", true)
        field("currentMmol", 8.4)
        field("lastReadingTime", System.currentTimeMillis())
        subscribe()
        invoke("broadcastPowerMeasurement") // Queued 93 must not survive the mode change.
        bridge.onStartCommand(android.content.Intent()
            .putExtra(GlucoBleBridgeService.EXTRA_MODE, BridgeMode.HEART_GLUCOSE.name), 0, 1)
        drain()
        verify(server, never()).close()
        assertTrue(report().contains("подписки: 1"))
        callback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        drain()
        verify(server, times(1)).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(0, 93)))
        verify(server, times(1)).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(0, 84)))
        assertEquals("HEART_GLUCOSE", bridge.getSharedPreferences("bridge", android.content.Context.MODE_PRIVATE)
            .getString("mode", null))
    }

    @Test fun unsupportedIndicationSubscriptionDoesNotSendNotifications() {
        subscribe(byteArrayOf(2, 0))
        verify(server).sendResponse(device, 42, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
        assertTrue(report().contains("подписки: 0"))
    }

    @Test fun callbacksFromStoppedSessionCannotSubscribeNewProfile() {
        field("bleGeneration", 2)
        subscribe()
        verify(server, never()).sendResponse(any(), anyInt(), anyInt(), anyInt(), nullable(ByteArray::class.java))
        assertTrue(report().contains("подписки: 0"))
    }

    @Test fun rejectedNotificationIsRecordedAndDoesNotBlockLaterSample() {
        `when`(server.notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))).thenReturn(5, 0)
        subscribe()
        assertTrue(report().contains("ошибок: 1"))
        assertTrue(report().contains("код ошибки 5"))
        invoke("broadcastPowerMeasurement")
        verify(server, times(2)).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
        assertTrue(report().contains("Android принял: 1"))
    }

    @Test fun missingCompletionClosesSessionAndRejectsLateCallback() {
        subscribe()
        field("pendingSince", android.os.SystemClock.elapsedRealtime() - 10001L)
        invoke("checkNotificationTimeout")
        verify(server).close()
        assertTrue(report().contains("NOTIFY timeout"))
        assertTrue(report().contains("подписки: 0"))
        callback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        drain()
        assertTrue(report().contains("завершил успешно: 0"))
        verify(server, times(1)).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun advertisingWaitsForBothServicesAndAdvertisesOnlyPowerUuid() {
        field("mode", BridgeMode.POWER)
        field("isBleStarting", true)
        val advertiser = mock(android.bluetooth.le.BluetoothLeAdvertiser::class.java)
        field("advertiser", advertiser)
        `when`(server.addService(any())).thenReturn(true)
        val power = invoke("createCyclingPowerService") as BluetoothGattService
        callback.onServiceAdded(BluetoothGatt.GATT_SUCCESS, power)
        drain()
        verify(server).addService(any())
        verifyNoInteractions(advertiser)
        val info = invoke("createDeviceInformationService") as BluetoothGattService
        callback.onServiceAdded(BluetoothGatt.GATT_SUCCESS, info)
        drain()
        val data = org.mockito.ArgumentCaptor.forClass(android.bluetooth.le.AdvertiseData::class.java)
        verify(advertiser).startAdvertising(any(), data.capture(), any(), any())
        assertEquals(listOf(android.os.ParcelUuid(GlucoBleBridgeService.SERVICE_CYCLING_POWER)), data.value.serviceUuids)
        assertFalse(data.value.includeDeviceName)
    }

    @Test fun deviceInformationReadHonorsOffset() {
        val info = invoke("createDeviceInformationService") as BluetoothGattService
        callback.onCharacteristicReadRequest(device, 50, 5,
            info.getCharacteristic(GlucoBleBridgeService.CHAR_MANUFACTURER))
        drain()
        verify(server).sendResponse(eq(device), eq(50), eq(BluetoothGatt.GATT_SUCCESS), eq(5),
            aryEq("Power".toByteArray()))
    }

    @Test fun unsubscribeDropsQueuedMeasurement() {
        subscribe()
        invoke("broadcastPowerMeasurement")
        subscribe(byteArrayOf(0, 0))
        callback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        drain()
        invoke("broadcastPowerMeasurement")
        verify(server, times(1)).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun gdhUnprefixedMgdlIsDeliveredAsPowerWithoutUnitGuessing() {
        field("mode", BridgeMode.POWER)
        val service = invoke("createCyclingPowerService") as BluetoothGattService
        `when`(server.getService(service.uuid)).thenReturn(service)
        measurement = service.getCharacteristic(GlucoBleBridgeService.CHAR_POWER_MEASUREMENT)
        cccd = measurement.getDescriptor(GlucoBleBridgeService.CCCD_UUID)
        subscribe()
        val receiver = bridge.javaClass.getDeclaredField("glucoseReceiver")
            .apply { isAccessible = true }.get(bridge) as android.content.BroadcastReceiver
        receiver.onReceive(bridge, android.content.Intent("glucodata.Minute")
            .putExtra("mgdl", 168).putExtra("glucose", 168.0f)
            .putExtra("Time", System.currentTimeMillis()))
        drain()
        verify(server).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(0, 0, 93, 0)))
    }

    @Test fun gdhStaleTimestampCannotReplaceNewerGlucose() {
        field("currentMmol", 8.4)
        val now = System.currentTimeMillis()
        field("lastReadingTime", now)
        val receiver = bridge.javaClass.getDeclaredField("glucoseReceiver")
            .apply { isAccessible = true }.get(bridge) as android.content.BroadcastReceiver
        receiver.onReceive(bridge, android.content.Intent("glucodata.Minute")
            .putExtra("mgdl", 168).putExtra("Time", now - 1000))
        drain()
        assertEquals("ТЕСТ: 93 в поле ЧСС, не реальные данные", invoke("outputText"))
        val value = bridge.javaClass.getDeclaredField("currentMmol").apply { isAccessible = true }.getDouble(bridge)
        assertEquals(8.4, value, 0.001)
    }
}
