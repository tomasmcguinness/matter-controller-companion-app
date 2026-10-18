package com.tomasmcguinness.mcc.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class MccClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** With a trailing slash, the way a user might type it. */
    private fun client() = MccClient(Controller(name = "Hub", url = server.url("/").toString()))

    @Test
    fun `a trailing slash on the address doesn't double up`() {
        val client = MccClient(Controller(name = "Hub", url = "http://192.168.1.250/"))

        assertEquals("http://192.168.1.250/api/companion/info", client.url("/api/companion/info").toString())
    }

    @Test
    fun `lists nodes`() = runTest {
        server.enqueue(MockResponse().setBody("""[{"nodeId": 2}, {"nodeId": 1}]"""))

        val nodes = client().nodes()

        assertEquals(listOf(2uL, 1uL), nodes.map { it.nodeId })

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/companion/nodes", request.path)
        assertEquals("application/json", request.getHeader("Accept"))
    }

    @Test
    fun `commissions on network`() = runTest {
        server.enqueue(MockResponse().setBody("""{"nodeId": 5}"""))

        val nodeId = client().commissionOnNetwork("fd12:3456::1", 5540, 20202021)

        assertEquals(5uL, nodeId)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(
            """{"inUse":true,"passcode":20202021,"ipAddress":"fd12:3456::1","port":5540}""",
            request.body.readUtf8(),
        )
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun `renames and unpairs by decimal node id`() = runTest {
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())

        client().rename(18446744073709551615uL, "Hallway")
        client().unpair(7uL)

        val rename = server.takeRequest()
        assertEquals("PUT", rename.method)
        assertEquals("/api/companion/nodes/18446744073709551615/update", rename.path)
        assertEquals("""{"name":"Hallway"}""", rename.body.readUtf8())

        val unpair = server.takeRequest()
        assertEquals("DELETE", unpair.method)
        assertEquals("/api/companion/nodes/7", unpair.path)
    }

    @Test
    fun `reports the controller's error text`() = runTest {
        server.enqueue(MockResponse().setResponseCode(504).setBody("Timed out commissioning"))

        try {
            client().checkReachable()
            fail("Expected an error")
        } catch (e: ClientError.HttpError) {
            assertEquals(504, e.status)
            assertEquals("The controller returned 504: Timed out commissioning", e.message)
        }
    }

    @Test
    fun `reports an unreadable response`() = runTest {
        server.enqueue(MockResponse().setBody("not json"))

        try {
            client().nodes()
            fail("Expected an error")
        } catch (e: ClientError.DecodingFailed) {
            assertEquals("Couldn't read the controller's response.", e.message)
        }
    }

    @Test
    fun `reports an unreachable controller`() = runTest {
        val address = server.url("/").toString()
        server.shutdown()

        try {
            MccClient(Controller(name = "Hub", url = address)).checkReachable()
            fail("Expected an error")
        } catch (e: ClientError.Unreachable) {
            assertTrue(e.message!!.startsWith("Couldn't reach $address"))
        }
    }
}
