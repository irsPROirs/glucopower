package com.example.glucoble

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Looper
import android.os.ParcelUuid
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.AdditionalMatchers.aryEq
import org.mockito.Mockito.*
import org.mockito.ArgumentCaptor
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
    private fun worker(): Looper = (bridge.javaClass.getDeclaredField("bleHandler")
        .apply { isAccessible = true }.get(bridge) as android.os.Handler).looper
    private fun drain() { shadowOf(worker()).idle(); shadowOf(Looper.getMainLooper()).idle() }
    private fun subscribe(bytes: ByteArray = byteArrayOf(1, 0)) {
        callback.onDescriptorWriteRequest(device, 42, cccd, false, true, 0, bytes)
        drain()
    }
    private fun report(): String = invoke("diagnosticReport") as String

    @Before fun setUp() {
        // Attach a real Android service/context without starting its HTTP poller or radio.
        bridge = Robolectric.buildService(GlucoBleBridgeService::class.java).get()
        shadowOf(worker()).pause()
        server = mock(BluetoothGattServer::class.java)
        device = mock(BluetoothDevice::class.java)
        `when`(device.name).thenReturn("CC600 test client")
        `when`(device.address).thenReturn("02:00:00:00:00:01")
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_NONE)
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

    @Test fun cadenceCanBeReadWithoutSubscribingAndHasCrankOnlyFeature() {
        field("mode", BridgeMode.CADENCE_TEST)
        val service = invoke("createCyclingCadenceService") as BluetoothGattService
        val characteristic = service.getCharacteristic(GlucoBleBridgeService.CHAR_CSC_MEASUREMENT)
        assertEquals(BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            characteristic.properties)
        assertEquals(BluetoothGattCharacteristic.PERMISSION_READ, characteristic.permissions)
        assertArrayEquals(byteArrayOf(2, 0), service.getCharacteristic(GlucoBleBridgeService.CHAR_CSC_FEATURE).value)
        callback.onCharacteristicReadRequest(device, 81, 0, characteristic)
        drain()
        verify(server).sendResponse(eq(device), eq(81), eq(0), eq(0), aryEq(byteArrayOf(2, 0, 0, 0, 0)))
        assertTrue(report().contains("подписки: 0"))
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun upgradeRetiresPowerAndExperimentalSettingsWithoutLosingGlucose() {
        val prefs = bridge.getSharedPreferences("bridge", 0)
        prefs.edit().putString("mode", "POWER_TEST").putString("ui_profile", "POWER")
            .putBoolean("multiply", false).putString("adv_variant", "UUID_ONLY")
            .putBoolean("vpm_advertising", true).putBoolean("minimal_advertising", false)
            .putBoolean("advertise_name_primary", true).putString("mmol", "8.4")
            .putLong("timestamp", 1000L).putString("source", "xDrip+").commit()
        invoke("restoreSettings")
        assertEquals("CADENCE", prefs.getString("mode", null))
        assertEquals("CADENCE", prefs.getString("ui_profile", null))
        for (key in listOf("multiply", "adv_variant", "vpm_advertising", "minimal_advertising", "advertise_name_primary")) {
            assertFalse(prefs.contains(key))
        }
        assertEquals("84 об/мин", invoke("outputText"))
    }


    @Test fun redmiAndroid16HeartSequenceDeliversTwelveSamplesOnlyAfterCccd() {
        // HCI 20261019-021620-073: link, six model reads, CCCD 0100,
        // then twelve notifications. Replay callbacks with the new test value 55, not the radio.
        field("isBleRunning", true)
        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_CONNECTED)
        val info = invoke("createDeviceInformationService") as BluetoothGattService
        repeat(6) { index ->
            callback.onCharacteristicReadRequest(device, 100 + index, 0,
                info.getCharacteristic(GlucoBleBridgeService.CHAR_MODEL))
        }
        drain()
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
        subscribe()
        repeat(12) { index ->
            callback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
            drain()
            if (index < 11) invoke("broadcastMeasurement")
        }
        verify(server, times(12)).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false),
            aryEq(byteArrayOf(0, 55)))
        assertTrue(report().contains("завершил успешно: 12"))
        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_DISCONNECTED)
        drain()
        invoke("broadcastMeasurement")
        verify(server, times(12)).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun attResponseRunsOnBleWorkerNotUiThread() {
        var responseLooper: Looper? = null
        `when`(server.sendResponse(eq(device), anyInt(), anyInt(), anyInt(), nullable(ByteArray::class.java)))
            .thenAnswer { responseLooper = Looper.myLooper(); true }
        subscribe()
        assertSame(worker(), responseLooper)
        assertNotSame(Looper.getMainLooper(), responseLooper)
    }

    @Test fun bondedDeviceWithoutPreviousCccdNeverReceivesMeasurements() {
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        field("isBleRunning", true)
        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_CONNECTED)
        drain()
        invoke("broadcastMeasurement")
        assertTrue(report().contains("подписки: 0"))
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun bondingAfterSubscriptionSavesOnlyTheExistingSubscription() {
        subscribe()
        assertTrue(bridge.getSharedPreferences("ble_subscriptions", 0).all.isEmpty())
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        val receiver = bridge.javaClass.getDeclaredField("bluetoothEventsReceiver")
            .apply { isAccessible = true }.get(bridge) as android.content.BroadcastReceiver
        receiver.onReceive(bridge, android.content.Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            .putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_BONDED))
        drain()
        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_DISCONNECTED)
        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_CONNECTED)
        drain()
        assertTrue(report().contains("CCCD RESTORE"))
        assertTrue(report().contains("подписки: 1"))
    }

    @Test fun bondedClientReconnectsWithoutWritingCccdAgain() {
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        field("isBleRunning", true)
        subscribe()
        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_DISCONNECTED)
        drain()
        assertTrue(report().contains("подписки: 0"))
        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_CONNECTED)
        drain()
        callback.onDescriptorReadRequest(device, 43, 0, cccd)
        drain()
        verify(server).sendResponse(eq(device), eq(43), eq(0), eq(0), aryEq(byteArrayOf(1, 0)))
        verify(server, times(2)).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(0, 55)))
        assertTrue(report().contains("CCCD RESTORE"))
    }

    @Test fun nonBondedClientMustSubscribeAfterReconnect() {
        field("isBleRunning", true)
        subscribe()
        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_DISCONNECTED)
        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_CONNECTED)
        drain()
        assertTrue(report().contains("подписки: 0"))
        verify(server, times(1)).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun unbondRemovesSavedSubscriptionAndDoesNotRestoreItOnNewBond() {
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        subscribe()
        val receiver = bridge.javaClass.getDeclaredField("bluetoothEventsReceiver")
            .apply { isAccessible = true }.get(bridge) as android.content.BroadcastReceiver
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_NONE)
        receiver.onReceive(bridge, android.content.Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            .putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE))
        drain()
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_CONNECTED)
        drain()
        assertTrue(report().contains("подписки: 0"))
        assertTrue(bridge.getSharedPreferences("ble_subscriptions", 0).all.isEmpty())
    }

    @Test fun persistedHeartSubscriptionNeverEnablesCyclingPower() {
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        subscribe()
        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_DISCONNECTED)
        drain()
        field("mode", BridgeMode.CADENCE_TEST)
        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_CONNECTED)
        drain()
        assertTrue(report().contains("подписки: 0"))
        assertFalse(report().contains("CCCD RESTORE"))
    }

    @Test fun successfulDisableRemovesBondedSubscription() {
        `when`(device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        subscribe()
        subscribe(byteArrayOf(0, 0))
        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_DISCONNECTED)
        callback.onConnectionStateChange(device, 0, BluetoothProfile.STATE_CONNECTED)
        drain()
        assertTrue(report().contains("подписки: 0"))
        assertTrue(bridge.getSharedPreferences("ble_subscriptions", 0).all.isEmpty())
    }

    @Test fun connectionAloneDoesNotCountAsSubscriptionOrSendData() {
        callback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        drain()
        invoke("broadcastMeasurement")
        verify(server, never()).connect(any(), anyBoolean())
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
        assertTrue(report().contains("Соединения: 1; подписки: 0"))
        assertTrue(report().contains("CC600 test client"))
    }

    @Test fun cccdAckPrecedesFirstHeartMeasurementAndCompletionIsTracked() {
        subscribe()
        val order = inOrder(server)
        order.verify(server).sendResponse(device, 42, BluetoothGatt.GATT_SUCCESS, 0, null)
        order.verify(server).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(0, 55)))
        assertTrue(report().contains("Android принял: 1; завершил успешно: 0"))
        callback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        drain()
        assertTrue(report().contains("Android принял: 1; завершил успешно: 1"))
        assertTrue(report().contains("SUBSCRIBE"))
    }

    @Test fun periodicSamplesWaitForAndroidCompletion() {
        subscribe()
        repeat(3) { invoke("broadcastMeasurement") }
        verify(server, times(1)).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
        callback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        drain()
        verify(server, times(2)).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun cadenceSubscriptionSendsCrankEvents() {
        field("mode", BridgeMode.CADENCE)
        field("currentMmol", 8.4)
        field("lastReadingTime", 1000L)
        val service = invoke("createCyclingCadenceService") as BluetoothGattService
        `when`(server.getService(service.uuid)).thenReturn(service)
        measurement = service.getCharacteristic(GlucoBleBridgeService.CHAR_CSC_MEASUREMENT)
        cccd = measurement.getDescriptor(GlucoBleBridgeService.CCCD_UUID)
        subscribe()
        verify(server).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(2, 0, 0, 0, 0)))
        field("currentMmol", 9.3)
        invoke("broadcastMeasurement")
        callback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        drain()
        verify(server, times(2)).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(2, 0, 0, 0, 0)))
    }

    @Test fun failedCccdResponseDoesNotClaimSubscription() {
        `when`(server.sendResponse(eq(device), anyInt(), anyInt(), anyInt(), nullable(ByteArray::class.java))).thenReturn(false)
        subscribe()
        assertTrue(report().contains("подписки: 0"))
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun liveHeartProfileWaitsForGlucoseThenTransmitsXdripUpdatesTimesTen() {
        field("mode", BridgeMode.HEART_GLUCOSE)
        subscribe()
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
        val receiver = bridge.javaClass.getDeclaredField("glucoseReceiver")
            .apply { isAccessible = true }.get(bridge) as android.content.BroadcastReceiver
        fun reading(mmol: Double, time: Long) {
            receiver.onReceive(bridge, android.content.Intent("com.eveningoutpost.dexdrip.BgEstimate")
                .putExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", mmol * 18.0182)
                .putExtra("com.eveningoutpost.dexdrip.Extras.Time", time))
            drain()
            invoke("broadcastMeasurement")
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
        invoke("broadcastMeasurement") // Queued 55 must not survive the mode change.
        bridge.onStartCommand(android.content.Intent()
            .putExtra(GlucoBleBridgeService.EXTRA_MODE, BridgeMode.HEART_GLUCOSE.name), 0, 1)
        drain()
        verify(server, never()).close()
        assertTrue(report().contains("подписки: 1"))
        callback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        drain()
        verify(server, times(1)).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(0, 55)))
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
        invoke("broadcastMeasurement")
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

    @Test fun advertisingWaitsForBothServicesAndAdvertisesOnlyCadenceUuid() {
        field("mode", BridgeMode.CADENCE)
        field("isBleStarting", true)
        val advertiser = mock(android.bluetooth.le.BluetoothLeAdvertiser::class.java)
        field("advertiser", advertiser)
        `when`(server.addService(any())).thenReturn(true)
        val power = invoke("createCyclingCadenceService") as BluetoothGattService
        callback.onServiceAdded(BluetoothGatt.GATT_SUCCESS, power)
        drain()
        verify(server).addService(any())
        verifyNoInteractions(advertiser)
        val info = invoke("createDeviceInformationService") as BluetoothGattService
        callback.onServiceAdded(BluetoothGatt.GATT_SUCCESS, info)
        drain()
        val data = org.mockito.ArgumentCaptor.forClass(android.bluetooth.le.AdvertiseData::class.java)
        val response = org.mockito.ArgumentCaptor.forClass(android.bluetooth.le.AdvertiseData::class.java)
        val settings = org.mockito.ArgumentCaptor.forClass(android.bluetooth.le.AdvertiseSettings::class.java)
        verify(advertiser).startAdvertising(settings.capture(), data.capture(), response.capture(), any())
        assertEquals(listOf(android.os.ParcelUuid(GlucoBleBridgeService.SERVICE_CYCLING_CADENCE)), data.value.serviceUuids)
        assertTrue(response.value.serviceUuids.isEmpty())
        assertTrue(settings.value.isConnectable)
        assertTrue(settings.value.isDiscoverable)
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
        invoke("broadcastMeasurement")
        subscribe(byteArrayOf(0, 0))
        callback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        drain()
        invoke("broadcastMeasurement")
        verify(server, times(1)).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun gdhUnprefixedMgdlIsDeliveredAsPowerWithoutUnitGuessing() {
        field("mode", BridgeMode.CADENCE)
        val service = invoke("createCyclingCadenceService") as BluetoothGattService
        `when`(server.getService(service.uuid)).thenReturn(service)
        measurement = service.getCharacteristic(GlucoBleBridgeService.CHAR_CSC_MEASUREMENT)
        cccd = measurement.getDescriptor(GlucoBleBridgeService.CCCD_UUID)
        subscribe()
        val receiver = bridge.javaClass.getDeclaredField("glucoseReceiver")
            .apply { isAccessible = true }.get(bridge) as android.content.BroadcastReceiver
        receiver.onReceive(bridge, android.content.Intent("glucodata.Minute")
            .putExtra("mgdl", 168).putExtra("glucose", 168.0f)
            .putExtra("Time", System.currentTimeMillis()))
        drain()
        verify(server).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(2, 0, 0, 0, 0)))
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
        assertEquals("ТЕСТ: 55 в поле ЧСС, не реальные данные", invoke("outputText"))
        val value = bridge.javaClass.getDeclaredField("currentMmol").apply { isAccessible = true }.getDouble(bridge)
        assertEquals(8.4, value, 0.001)
    }

    @Test fun attributeAccessProvesConnectionWhenOemLinkCallbackIsMissingAndRetainsClientOnce() {
        `when`(server.connect(device, false)).thenReturn(true)
        subscribe()
        callback.onDescriptorReadRequest(device, 45, 0, cccd)
        drain()
        verify(server, times(1)).connect(device, false)
        assertTrue(report().contains("Соединения: 1; подписки: 1"))
        assertTrue(report().contains("GATT ACCESS"))
        assertTrue(report().contains("запрос принят=true"))
    }

    @Test fun systemAclAndBondEventsNeverCreateGattSubscription() {
        val receiver = bridge.javaClass.getDeclaredField("bluetoothEventsReceiver")
            .apply { isAccessible = true }.get(bridge) as android.content.BroadcastReceiver
        receiver.onReceive(bridge, android.content.Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device))
        receiver.onReceive(bridge, android.content.Intent("android.bluetooth.device.action.KEY_MISSING")
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device))
        drain()
        invoke("broadcastMeasurement")
        assertTrue(report().contains("ACL UP"))
        assertTrue(report().contains("KEY_MISSING"))
        assertTrue(report().contains("Соединения: 0; подписки: 0"))
        verify(server, never()).connect(any(), anyBoolean())
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun retainCannotInitiateConnectionWithoutAccessToOwnAttributes() {
        bridge.javaClass.getDeclaredMethod("retainClient", BluetoothDevice::class.java)
            .apply { isAccessible = true }.invoke(bridge, device)
        verify(server, never()).connect(any(), anyBoolean())
        assertTrue(report().contains("Соединения: 0; подписки: 0"))
    }

    private fun encryptionEvent(vararg extras: Pair<String, Any>) {
        val intent = android.content.Intent("android.bluetooth.device.action.ENCRYPTION_CHANGE")
            .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
        extras.forEach { (key, value) ->
            when (value) {
                is Int -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
            }
        }
        val receiver = bridge.javaClass.getDeclaredField("bluetoothEventsReceiver")
            .apply { isAccessible = true }.get(bridge) as android.content.BroadcastReceiver
        receiver.onReceive(bridge, intent)
        drain()
    }

    @Test fun successfulEncryptionLogsPublicApi36MetadataWithoutCreatingSubscription() {
        encryptionEvent(
            "android.bluetooth.device.extra.ENCRYPTION_STATUS" to 0,
            "android.bluetooth.device.extra.ENCRYPTION_ENABLED" to true,
            "android.bluetooth.device.extra.KEY_SIZE" to 16,
            "android.bluetooth.device.extra.EXTRA_ENCRYPTION_ALGORITHM" to 2,
            BluetoothDevice.EXTRA_TRANSPORT to 2,
            "private_test_extra" to "must-not-appear"
        )
        val diagnostic = report()
        assertTrue(diagnostic.contains("status=0 (HCI; 0=успех), enabled=true"))
        assertTrue(diagnostic.contains("keySize=16 байт, algorithm=AES (2), transport=2"))
        assertFalse(diagnostic.contains("must-not-appear"))
        assertTrue(diagnostic.contains("подписки: 0"))
        verify(server, never()).connect(any(), anyBoolean())
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun missingEncryptionFieldsAreNotReportedAsSuccessOrDisabledEncryption() {
        encryptionEvent()
        assertTrue(report().contains("status=не указан (HCI; 0=успех), enabled=не указан"))
        assertTrue(report().contains("keySize=не указан байт, algorithm=не указан, transport=не указан"))
    }

    @Test fun encryptionFailureRemainsHciStatusAndDoesNotAutomaticallyDisconnect() {
        encryptionEvent(
            "android.bluetooth.device.extra.ENCRYPTION_STATUS" to 6,
            "android.bluetooth.device.extra.ENCRYPTION_ENABLED" to false,
            "android.bluetooth.device.extra.EXTRA_ENCRYPTION_ALGORITHM" to 0
        )
        assertTrue(report().contains("status=6 (HCI; 0=успех), enabled=false"))
        assertTrue(report().contains("algorithm=NONE (0)"))
        verify(server, never()).cancelConnection(any())
    }

    @Test fun diagnosticReportIncludesCaptureTimeAndExplainsSharedConnectionCounter() {
        assertTrue(report().contains("Отчёт создан:"))
        assertTrue(report().contains("Счётчик соединений включает системные уведомления"))
    }

    @Test fun cadenceFeatureReportsOnlyCrankAndNoControlPoint() {
        val service = invoke("createCyclingCadenceService") as BluetoothGattService
        assertEquals(3, service.characteristics.size)
        callback.onCharacteristicReadRequest(device, 91, 0,
            service.getCharacteristic(GlucoBleBridgeService.CHAR_CSC_FEATURE))
        drain()
        verify(server).sendResponse(eq(device), eq(91), eq(BluetoothGatt.GATT_SUCCESS), eq(0),
            aryEq(byteArrayOf(2, 0)))
    }

    @Test fun cadenceProfileDoesNotPublishHeartRateService() {
        val service = invoke("createCyclingCadenceService") as BluetoothGattService
        assertEquals(GlucoBleBridgeService.SERVICE_CYCLING_CADENCE, service.uuid)
        assertNull(service.getCharacteristic(GlucoBleBridgeService.CHAR_HEART_MEASUREMENT))
    }

    private fun advertisedPackets(name: String): Pair<AdvertiseData, AdvertiseData> {
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        val advertiser = mock(BluetoothLeAdvertiser::class.java)
        `when`(manager.adapter).thenReturn(adapter)
        `when`(adapter.name).thenReturn(name)
        field("bluetoothManager", manager)
        field("advertiser", advertiser)
        field("mode", BridgeMode.CADENCE_TEST)
        invoke("advertiseService")
        val primary = ArgumentCaptor.forClass(AdvertiseData::class.java)
        val scan = ArgumentCaptor.forClass(AdvertiseData::class.java)
        val settings = ArgumentCaptor.forClass(AdvertiseSettings::class.java)
        verify(advertiser).startAdvertising(settings.capture(), primary.capture(), scan.capture(), any(AdvertiseCallback::class.java))
        assertTrue(settings.value.isConnectable)
        assertEquals(0, settings.value.timeout)
        assertEquals(listOf(ParcelUuid(GlucoBleBridgeService.SERVICE_CYCLING_CADENCE)), primary.value.serviceUuids)
        assertTrue(scan.value.serviceUuids.isNullOrEmpty())
        return primary.value to scan.value
    }


    @Test fun minimalAdvertisingUsesOnlyUuidInPrimaryAndAccepts29ByteScanName() {
        val packets = advertisedPackets("x".repeat(29))
        assertFalse(packets.first.includeDeviceName)
        assertTrue(packets.second.includeDeviceName)
        assertTrue(report().contains("UUID только в ADV"))
        assertFalse(BridgeProtocol.canIncludeName("x".repeat(30)))
    }



    @Test fun longUtf8NameFallsBackToScanResponseInsteadOfOverflowingPrimaryAdvertising() {
        val packets = advertisedPackets("Я".repeat(12)) // 24 bytes, not 12.
        assertFalse(packets.first.includeDeviceName)
        assertTrue(packets.second.includeDeviceName)
    }

    @Test fun oversizedNameIsOmittedButCadenceUuidRemainsAdvertised() {
        val packets = advertisedPackets("Я".repeat(15))
        assertFalse(packets.first.includeDeviceName)
        assertFalse(packets.second.includeDeviceName)
    }


    @Test fun stoppingDoesNotCancelAnObservedButUnretainedConnection() {
        callback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        drain()
        invoke("stopBlePeripheral")
        verify(server, never()).cancelConnection(any())
        verify(server).close()
    }

    @Test fun stoppingReleasesRetainedClientEvenWithoutLinkCallback() {
        `when`(server.connect(device, false)).thenReturn(true)
        subscribe() // Attribute access retains the client without a LINK callback.
        invoke("stopBlePeripheral")
        val order = inOrder(server)
        order.verify(server).cancelConnection(device)
        order.verify(server).close()
    }

    @Test fun malformedCccdLengthDoesNotCreateSubscription() {
        subscribe(byteArrayOf(1))
        verify(server).sendResponse(device, 42, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, 0, null)
        assertTrue(report().contains("подписки: 0"))
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun rejectedPreparedWriteCanBeCancelledWithoutWaitingForAttTimeout() {
        callback.onDescriptorWriteRequest(device, 60, cccd, true, true, 0, byteArrayOf(1, 0))
        drain()
        verify(server).sendResponse(device, 60, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        callback.onExecuteWrite(device, 61, false)
        callback.onExecuteWrite(device, 62, true)
        drain()
        verify(server).sendResponse(device, 61, BluetoothGatt.GATT_SUCCESS, 0, null)
        verify(server).sendResponse(device, 62, BluetoothGatt.GATT_SUCCESS, 0, null)
        assertTrue(report().contains("подписки: 0"))
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun cadenceTestSends65WithoutGlucose() {
        field("mode", BridgeMode.CADENCE_TEST)
        val service = invoke("createCyclingCadenceService") as BluetoothGattService
        `when`(server.getService(service.uuid)).thenReturn(service)
        measurement = service.getCharacteristic(GlucoBleBridgeService.CHAR_CSC_MEASUREMENT)
        cccd = measurement.getDescriptor(GlucoBleBridgeService.CCCD_UUID)
        subscribe()
        verify(server).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(2, 0, 0, 0, 0)))
        assertEquals("ТЕСТ: 65 об/мин, не реальные данные", invoke("outputText"))
        assertTrue(report().contains("тестовые 65; не зависят от входной глюкозы"))
    }

    @Test fun cadenceTestRestoresInNewServiceWithoutOverwritingRealReading() {
        field("mode", BridgeMode.CADENCE)
        field("isBleRunning", true)
        bridge.getSharedPreferences("bridge", android.content.Context.MODE_PRIVATE).edit()
            .putString("source", "GlucoDataHandler").putString("mmol", "6.2")
            .putLong("timestamp", 1000L).commit()
        invoke("restoreReading")
        // Test mode must persist without inserting fake glucose.
        bridge.onStartCommand(android.content.Intent()
            .putExtra(GlucoBleBridgeService.EXTRA_MODE, BridgeMode.CADENCE_TEST.name), 0, 1)
        drain()
        val restored = Robolectric.buildService(GlucoBleBridgeService::class.java).get()
        try {
            restored.javaClass.getDeclaredMethod("restoreSettings").apply { isAccessible = true }.invoke(restored)
            assertEquals(BridgeMode.CADENCE_TEST,
                restored.javaClass.getDeclaredField("mode").apply { isAccessible = true }.get(restored))
            assertEquals("ТЕСТ: 65 об/мин, не реальные данные",
                restored.javaClass.getDeclaredMethod("outputText").apply { isAccessible = true }.invoke(restored))
            assertEquals(6.2, restored.javaClass.getDeclaredField("currentMmol").apply { isAccessible = true }.getDouble(restored), 0.001)
            assertEquals("6.2", bridge.getSharedPreferences("bridge", android.content.Context.MODE_PRIVATE).getString("mmol", null))
        } finally { restored.onDestroy() }
    }

    @Test fun reportShowsActualSubscriberAndExplainsWaitingForInput() {
        field("mode", BridgeMode.HEART_GLUCOSE)
        subscribe()
        val diagnostic = report()
        assertTrue(diagnostic.contains("Подписчики измерений: клиент#1 (CC600 test client)"))
        assertTrue(diagnostic.contains("Клиенты, обратившиеся к GATT приложения: клиент#1"))
        assertTrue(diagnostic.contains("подписка есть; ожидается входное измерение"))
        assertTrue(diagnostic.contains("входное измерение ещё не получено"))
        assertFalse(diagnostic.contains("Нет GATT ACCESS/WRITE CCCD от CC600"))
        verify(server, never()).notifyCharacteristicChanged(any(), any(), anyBoolean(), any(ByteArray::class.java))
    }

    @Test fun switchingCadenceTestToLiveKeepsConnectionAndDropsQueuedTest() {
        field("mode", BridgeMode.CADENCE_TEST)
        field("isBleRunning", true)
        field("currentMmol", 8.4)
        field("lastReadingTime", System.currentTimeMillis())
        val service = invoke("createCyclingCadenceService") as BluetoothGattService
        `when`(server.getService(service.uuid)).thenReturn(service)
        measurement = service.getCharacteristic(GlucoBleBridgeService.CHAR_CSC_MEASUREMENT)
        cccd = measurement.getDescriptor(GlucoBleBridgeService.CCCD_UUID)
        subscribe()
        invoke("broadcastMeasurement")
        bridge.onStartCommand(android.content.Intent()
            .putExtra(GlucoBleBridgeService.EXTRA_MODE, BridgeMode.CADENCE.name), 0, 1)
        drain()
        callback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        drain()
        verify(server, never()).close()
        verify(server, times(2)).notifyCharacteristicChanged(eq(device), eq(measurement), eq(false), aryEq(byteArrayOf(2, 0, 0, 0, 0)))
    }
}
