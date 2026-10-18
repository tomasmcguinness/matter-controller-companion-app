package com.tomasmcguinness.mcc.data

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {

    private fun node(json: String): Node = MccJson.decodeFromString(json)

    @Test
    fun `decodes a node id however cJSON printed it`() {
        assertEquals(5uL, node("""{"nodeId": 5}""").nodeId)
        assertEquals(1234567890123456768uL, node("""{"nodeId": 1.2345678901234567e+18}""").nodeId)
        assertEquals(18446744073709551615uL, node("""{"nodeId": "18446744073709551615"}""").nodeId)
    }

    @Test
    fun `unparseable ids decode as zero`() {
        assertEquals(0uL, node("""{"nodeId": "nope"}""").nodeId)
        assertEquals(0uL, node("""{"nodeId": -1}""").nodeId)
        assertEquals(0uL, node("""{"nodeId": 1, "extAddress": null}""").extAddress)
        assertEquals(0uL, node("""{"nodeId": 1, "extAddress": {}}""").extAddress)
    }

    @Test
    fun `missing and null fields fall back to defaults`() {
        val decoded = node("""{"nodeId": 1, "isIcd": null, "powerSource": null, "endpoints": null, "unknown": 1}""")

        assertEquals(false, decoded.isIcd)
        assertEquals(0, decoded.powerSource)
        assertEquals(emptyList<Endpoint>(), decoded.endpoints)
        assertNull(decoded.vendorName)
        assertNull(decoded.battery)
    }

    @Test
    fun `decodes the README example`() {
        val decoded = MccJson.decodeFromString<List<Node>>(
            """
            [{"nodeId": 1, "isIcd": false, "vendorName": "Acme", "productName": "Temperature Sensor",
              "nodeName": "Hallway", "powerSource": 1, "batteryPercent": 87, "batteryVoltage": 2950,
              "extAddress": 1.2345678901234567e+18, "hasSubscription": true,
              "endpoints": [{"endpointId": 1, "endpointName": "Temperature", "powerSource": 1,
                             "batteryPercent": 87, "batteryVoltage": 2950, "measuredValue": 2150,
                             "deviceTypes": [770, 4293984257]}]}]
            """,
        ).single()

        val endpoint = decoded.endpoints.single()

        assertEquals("Hallway", decoded.displayName)
        assertEquals("Acme · Temperature Sensor", decoded.subtitle)
        assertEquals(21.5, endpoint.scaledValue!!, 0.0001)
        assertEquals(listOf("Temperature Sensor", "Heat Meter"), endpoint.deviceTypeNames)
    }

    @Test
    fun `display name prefers the node name, then the product, then the id`() {
        assertEquals("Hallway", node("""{"nodeId": 1, "nodeName": "Hallway", "productName": "Sensor"}""").displayName)
        assertEquals("Sensor", node("""{"nodeId": 1, "nodeName": "", "productName": "Sensor"}""").displayName)
        assertEquals("Node 0xFF", node("""{"nodeId": 255}""").displayName)
        assertEquals("0xFFFFFFFFFFFFFFFF", node("""{"nodeId": "18446744073709551615"}""").nodeIdHex)
    }

    @Test
    fun `battery readings`() {
        assertNull(BatteryReading.of(null, null))
        assertEquals("87% · 2.95 V", BatteryReading.of(87, 2950).toString())
        assertEquals("3.00 V", BatteryReading.of(null, 3000).toString())
        assertEquals(100, BatteryReading.of(140, null)!!.percent)
        assertEquals(BatteryReading.Level.UNKNOWN, BatteryReading.of(null, 3000)!!.level)
        assertEquals(BatteryReading.Level.LOW, BatteryReading.of(15, null)!!.level)
        assertEquals(BatteryReading.Level.WARNING, BatteryReading.of(30, null)!!.level)
        assertEquals(BatteryReading.Level.GOOD, BatteryReading.of(31, null)!!.level)
    }

    @Test
    fun `connection urls need an http scheme and a host`() {
        assertEquals("http://192.168.1.250", Controller.connectionUrl("  http://192.168.1.250 \n"))
        assertEquals("HTTPS://hub.local:8443/", Controller.connectionUrl("HTTPS://hub.local:8443/"))
        assertNull(Controller.connectionUrl(""))
        assertNull(Controller.connectionUrl("192.168.1.250"))
        assertNull(Controller.connectionUrl("ftp://192.168.1.250"))
        assertNull(Controller.connectionUrl("http://"))
        assertNull(Controller.connectionUrl("http://bad host"))
    }
}
