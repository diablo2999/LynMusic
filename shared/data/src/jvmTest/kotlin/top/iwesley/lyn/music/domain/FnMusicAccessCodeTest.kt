package top.iwesley.lyn.music.domain

import java.net.SocketTimeoutException
import java.net.URI
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import top.iwesley.lyn.music.core.model.FnMusicSourceDraft
import top.iwesley.lyn.music.core.model.LyricsHttpClient
import top.iwesley.lyn.music.core.model.LyricsHttpResponse
import top.iwesley.lyn.music.core.model.LyricsRequest
import top.iwesley.lyn.music.core.model.NetworkConnectionState
import top.iwesley.lyn.music.core.model.NetworkConnectionType
import top.iwesley.lyn.music.core.model.NetworkConnectionTypeProvider
import top.iwesley.lyn.music.core.model.RequestMethod

class FnMusicAccessCodeTest {
    @Test
    fun `address mode verifies access before login and reuses the probe for APIs and media`() = runTest {
        for (mount in listOf("", "/fn", "/nas%20mount")) {
            for (suffix in listOf("", "/", "/music", "/music/", "/music/api/v1", "/music/api/v1/")) {
                for (status in listOf(200, 204, 404)) {
                    for (code in listOf("", "访问码+ ?")) {
                        val server = AccessCodeHttpClient { request ->
                            if (request.path.endsWith("/access_code_verify")) LyricsHttpResponse(status, "") else null
                        }
                        val client = fnMusicClient(
                            draft("https://nas.invalid:5667$mount$suffix", code).toFnMusicConnection("", "device"),
                            server,
                            addressSelector = null,
                        )
                        repeat(2) { client.call("playlist/list", "/playlist/list") }
                        val media = listOf("/track/stream", "/static/cover").flatMap { client.mediaRequests(it, emptyList()) }

                        val paths = server.requests.map { it.path }
                        assertEquals(
                            listOf("$mount/access_code_verify", "$mount/music/api/v1/sys/config", "$mount/music/api/v1/user/password-login",
                                "$mount/music/api/v1/playlist/list", "$mount/music/api/v1/playlist/list"),
                            paths,
                        )
                        val verify = server.requests.first()
                        assertEquals(RequestMethod.GET, verify.method)
                        assertEquals(3_000L, verify.timeoutMillis)
                        assertNull(verify.headers["authx"])
                        assertNull(verify.headers["Cookie"])
                        assertNotNull(server.requests[1].headers["authx"])
                        val expectedCode = code.takeIf { it.isNotEmpty() }?.let { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
                        for (headers in server.requests.map { it.headers } + media.map { it.headers }) {
                            assertEquals(expectedCode, headers["x-access-code"])
                            assertEquals(if (expectedCode == null) null else "app", headers["x-access-source"])
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `access refusal at the gateway or configuration stops before login and address fallback`() = runTest {
        for (stage in listOf("/access_code_verify", "/sys/config")) {
            for (status in listOf(401, 403, 429)) {
                for (code in listOf("", "wrong")) {
                    val server = AccessCodeHttpClient { request ->
                        if (request.path.endsWith(stage)) LyricsHttpResponse(status, "") else null
                    }
                    val connection = draft("https://nas.invalid/fn/music/api/v1", code)
                        .copy(wanBaseUrl = "https://wan.invalid/fn").toFnMusicConnection("", "device")
                    assertFailsWith<FnMusicAccessCodeException> {
                        fnMusicClient(connection, server, null).call("playlist/list", "/playlist/list")
                    }
                    assertEquals(0, server.loginCount)
                    assertTrue(server.requests.all { URI(it.url).host == "nas.invalid" })
                    assertEquals(if (stage == "/access_code_verify") 1 else 2, server.requests.size)
                }
            }
        }
    }

    @Test
    fun `verification errors and cancellation never send login and never cache success`() = runTest {
        for (stage in listOf("/access_code_verify", "/sys/config")) {
            for (failure in listOf<Any>(500, SocketTimeoutException("timed out"), CancellationException("cancelled"))) {
                val server = AccessCodeHttpClient { request ->
                    if (!request.path.endsWith(stage)) null
                    else if (failure is Throwable) throw failure
                    else LyricsHttpResponse(failure as Int, "")
                }
                val client = fnMusicClient(draft().toFnMusicConnection("", "device"), server, null)
                repeat(2) {
                    val actual = assertFailsWith<Throwable> { client.call("playlist/list", "/playlist/list") }
                    if (failure is CancellationException) assertTrue(actual === failure)
                }
                assertEquals(0, server.loginCount)
                assertEquals(2, server.requests.count { it.path.endsWith(stage) })
            }
        }
    }

    @Test
    fun `a failed probe can fall back to WAN before sending a password`() = runTest {
        for (useSelector in listOf(false, true)) {
            val server = AccessCodeHttpClient { request ->
                if (URI(request.url).host == "nas.invalid") LyricsHttpResponse(500, "") else null
            }
            val connection = draft().copy(wanBaseUrl = "https://wan.invalid/fn").toFnMusicConnection("access-fallback-$useSelector", "device")
            try {
                val client = fnMusicClient(connection, server, if (useSelector) RemoteSourceAddressSelector() else null)
                client.call("playlist/list", "/playlist/list")
                assertEquals(1, server.loginCount)
                assertTrue(server.requests.filter { it.path.endsWith("/user/password-login") }.all { URI(it.url).host == "wan.invalid" })
                assertEquals(listOf("/fn/access_code_verify", "/fn/music/api/v1/sys/config", "/fn/music/api/v1/user/password-login", "/fn/music/api/v1/playlist/list"),
                    server.requests.filter { URI(it.url).host == "wan.invalid" }.map { it.path })
            } finally {
                FnMusicSessions.invalidate(connection.sourceId)
            }
        }
    }

    @Test
    fun `probe cache is shared until network credentials or authentication changes`() = runTest {
        val network = MutableStateFlow(NetworkConnectionState(NetworkConnectionType.WIFI, version = 1))
        val selector = RemoteSourceAddressSelector(object : NetworkConnectionTypeProvider {
            override val networkConnectionState = network
        })
        val server = AccessCodeHttpClient()
        val connection = draft().toFnMusicConnection("access-probe-cache", "device")
        try {
            val client = fnMusicClient(connection, server, selector)
            client.call("playlist/list", "/playlist/list")
            fnMusicClient(connection, server, selector).call("playlist/list", "/playlist/list")
            assertEquals(1, server.requests.count { it.path.endsWith("/access_code_verify") })

            network.value = network.value.copy(version = 2)
            client.call("playlist/list", "/playlist/list")
            assertEquals(2, server.requests.count { it.path.endsWith("/access_code_verify") })
            assertEquals(1, server.loginCount)

            client.reauthorize("token-1")
            assertEquals(3, server.requests.count { it.path.endsWith("/access_code_verify") })
            assertEquals(2, server.loginCount)

            fnMusicClient(connection.copy(accessCode = "new-code"), server, selector).call("playlist/list", "/playlist/list")
            assertEquals(4, server.requests.count { it.path.endsWith("/access_code_verify") })
            assertEquals(Base64.getEncoder().encodeToString("new-code".toByteArray()), server.requests.last().headers["x-access-code"])
        } finally {
            FnMusicSessions.invalidate(connection.sourceId)
        }
    }

    @Test
    fun `parallel calls wait for one access verification and one login`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val server = AccessCodeHttpClient { request ->
            if (request.path.endsWith("/access_code_verify")) {
                entered.complete(Unit)
                release.await()
            }
            null
        }
        val client = fnMusicClient(draft().toFnMusicConnection("", "device"), server, null)
        val first = async { client.call("playlist/list", "/playlist/list") }
        entered.await()
        val second = async { client.call("playlist/list", "/playlist/list") }
        release.complete(Unit)
        awaitAll(first, second)
        assertEquals(1, server.requests.count { it.path.endsWith("/access_code_verify") })
        assertEquals(1, server.loginCount)
    }

    @Test
    fun `explicit clearing overrides a newly entered code before any request`() = runTest {
        val prepared = prepareFnMusicDraft(draft(code = "new-code").copy(clearAccessCode = true))
        assertEquals("", prepared.accessCode)
        assertNull(prepared.toFnMusicConnection("", "device").accessCode)
        val server = AccessCodeHttpClient()
        testFnMusicConnection(prepared, "device", server)
        assertTrue(server.requests.all { it.headers["x-access-code"] == null && it.headers["x-access-source"] == null })
    }

    private fun draft(url: String = "https://nas.invalid/fn", code: String = "") =
        FnMusicSourceDraft("NAS", baseUrl = url, username = "user", password = "password", accessCode = code)
}

private val LyricsRequest.path: String get() = URI(url).rawPath

private class AccessCodeHttpClient(
    private val response: suspend (LyricsRequest) -> LyricsHttpResponse? = { null },
) : LyricsHttpClient {
    val requests = mutableListOf<LyricsRequest>()
    var loginCount = 0
        private set

    override suspend fun request(request: LyricsRequest): Result<LyricsHttpResponse> {
        requests += request
        return runCatching {
            response(request) ?: when {
                request.path.endsWith("/access_code_verify") -> LyricsHttpResponse(204, "")
                request.path.endsWith("/user/password-login") -> {
                    loginCount += 1
                    LyricsHttpResponse(200, """{"code":0,"data":{"userToken":"token-$loginCount"}}""")
                }
                else -> LyricsHttpResponse(200, """{"code":0,"data":{}}""")
            }
        }
    }
}
