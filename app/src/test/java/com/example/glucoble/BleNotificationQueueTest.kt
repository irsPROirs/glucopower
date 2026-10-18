package com.example.glucoble

import org.junit.Assert.*
import org.junit.Test

class BleNotificationQueueTest {
    @Test fun onlyOneClientCanHaveAnOutstandingNotification() {
        val queue = BleNotificationQueue<String>()
        queue.offer("a", byteArrayOf(1)); queue.offer("b", byteArrayOf(2))
        assertEquals("a", queue.next()!!.first)
        assertNull(queue.next())
        assertFalse(queue.complete("b"))
        assertNull(queue.next())
        assertTrue(queue.complete("a"))
        assertEquals("b", queue.next()!!.first)
    }
    @Test fun slowClientGetsNewestSampleWithoutBuildingUnboundedBacklog() {
        val queue = BleNotificationQueue<String>()
        queue.offer("a", byteArrayOf(1)); queue.next()
        for (i in 2..100) queue.offer("a", byteArrayOf(i.toByte()))
        queue.complete("a")
        assertArrayEquals(byteArrayOf(100), queue.next()!!.second)
        queue.complete("a")
        assertNull(queue.next())
    }
    @Test fun disconnectDropsWaitingSamplesAndUnblocksOtherClients() {
        val queue = BleNotificationQueue<String>()
        queue.offer("a", byteArrayOf(1)); queue.next()
        queue.offer("a", byteArrayOf(2)); queue.offer("b", byteArrayOf(3))
        queue.remove("a", disconnected = true)
        assertEquals("b", queue.next()!!.first)
    }
    @Test fun unsubscribeStillWaitsForAlreadySubmittedNotification() {
        val queue = BleNotificationQueue<String>()
        queue.offer("a", byteArrayOf(1)); queue.next()
        queue.offer("b", byteArrayOf(2)); queue.remove("a")
        assertNull(queue.next())
        queue.complete("a")
        assertEquals("b", queue.next()!!.first)
    }
}
