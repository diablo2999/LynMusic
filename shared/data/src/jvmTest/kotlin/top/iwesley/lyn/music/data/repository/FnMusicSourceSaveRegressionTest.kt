package top.iwesley.lyn.music.data.repository

import androidx.room.Room
import androidx.room.useWriterConnection
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import top.iwesley.lyn.music.core.model.FnMusicConnectionMode
import top.iwesley.lyn.music.core.model.FnMusicSourceDraft
import top.iwesley.lyn.music.core.model.ImportScanPhase
import top.iwesley.lyn.music.core.model.ImportScanProgressSink
import top.iwesley.lyn.music.core.model.ImportScanReport
import top.iwesley.lyn.music.core.model.ImportSourceGateway
import top.iwesley.lyn.music.core.model.ImportedTrackCandidate
import top.iwesley.lyn.music.core.model.LocalFolderSelection
import top.iwesley.lyn.music.core.model.LyricsHttpClient
import top.iwesley.lyn.music.core.model.LyricsHttpResponse
import top.iwesley.lyn.music.core.model.LyricsRequest
import top.iwesley.lyn.music.core.model.NavidromeSourceDraft
import top.iwesley.lyn.music.core.model.SambaSourceDraft
import top.iwesley.lyn.music.core.model.SecureCredentialStore
import top.iwesley.lyn.music.core.model.WebDavSourceDraft
import top.iwesley.lyn.music.core.model.buildFnMusicSongLocator
import top.iwesley.lyn.music.data.db.LynMusicDatabase
import top.iwesley.lyn.music.data.db.LyricsOffsetEntity
import top.iwesley.lyn.music.data.db.buildLynMusicDatabase
import top.iwesley.lyn.music.domain.RemoteSourceAddressTestException
import top.iwesley.lyn.music.domain.fnMusicAccessCodeCredentialKey
import top.iwesley.lyn.music.domain.scanFnMusicLibrary
import top.iwesley.lyn.music.domain.testFnMusicConnection

class FnMusicSourceSaveRegressionTest {
    @Test
    fun `WAN probes keep their remote timeout through testing import update and rescan`() = runTest {
        for (withLan in listOf(false, true)) {
            val database = createFnRegressionDatabase()
            try {
                val http = SlowWanFnMusicHttpClient()
                val repository = RoomImportSourceRepository(database, FnRegressionGateway(http), FnRegressionCredentialStore())
                val draft = FnMusicSourceDraft(
                    "WAN", baseUrl = if (withLan) "http://lan.invalid:5666" else "", wanBaseUrl = "https://wan.invalid/fn",
                    username = "user", password = "password", accessCode = "code",
                )
                val tested = repository.testFnMusicSource(draft)
                if (withLan) {
                    // A failed LAN remains visible in test results, while WAN must succeed.
                    assertTrue((tested.exceptionOrNull() as? RemoteSourceAddressTestException)?.anySucceeded == true)
                } else {
                    tested.getOrThrow()
                }
                val sourceId = repository.addFnMusicSource(draft).getOrThrow().sourceId
                val edited = draft.copy(label = "Updated", password = "")
                val updatedTest = repository.testUpdatedFnMusicSource(sourceId, edited)
                if (withLan) {
                    assertTrue((updatedTest.exceptionOrNull() as? RemoteSourceAddressTestException)?.anySucceeded == true)
                } else {
                    updatedTest.getOrThrow()
                }
                repository.updateFnMusicSource(sourceId, edited).getOrThrow()
                repository.rescanSource(sourceId).getOrThrow()

                val wanProbes = http.requests.filter {
                    URI(it.url).host == "wan.invalid" && (it.url.endsWith("/access_code_verify") || it.url.endsWith("/sys/config"))
                }
                assertTrue(wanProbes.isNotEmpty())
                assertTrue(wanProbes.all { it.timeoutMillis == 10_000L })
                if (withLan) assertTrue(http.requests.any { URI(it.url).host == "lan.invalid" && it.timeoutMillis == 3_000L })
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun `a rejected SQLite commit restores source tracks password and access code`() = runTest {
        for (mode in FnMusicConnectionMode.entries) {
            for (savedCode in listOf("", "saved-code")) {
                for (clear in listOf(false, true)) {
                    val database = createFnRegressionDatabase()
                    try {
                        val credentials = FnRegressionCredentialStore()
                        val repository = RoomImportSourceRepository(database, FnRegressionGateway(), credentials)
                        val original = fnRegressionDraft(mode).copy(accessCode = savedCode)
                        val sourceId = repository.addFnMusicSource(original).getOrThrow().sourceId
                        val source = assertNotNull(database.importSourceDao().getById(sourceId))
                        val tracks = database.trackDao().getBySourceId(sourceId)
                        val secrets = credentials.values.toMap()
                        database.rejectFnRegressionUpdate()

                        val edited = original.copy(label = "Updated", baseUrl = "https://new-nas.invalid", fnId = "new-nas",
                            username = "new-user", password = "new-password", accessCode = "replacement", clearAccessCode = clear)
                        val result = repository.updateFnMusicSource(sourceId, edited)

                        assertTrue(result.isFailure)
                        assertEquals(source, database.importSourceDao().getById(sourceId))
                        assertEquals(tracks, database.trackDao().getBySourceId(sourceId))
                        assertEquals(secrets, credentials.values)

                        database.rejectFnRegressionUpdate(enabled = false)
                        repository.updateFnMusicSource(sourceId, edited).getOrThrow()
                        assertEquals("Updated", database.importSourceDao().getById(sourceId)?.label)
                        assertEquals("new-password", credentials.get(source.credentialKey!!))
                        assertEquals(if (clear) null else "replacement", credentials.get(fnMusicAccessCodeCredentialKey(sourceId)))
                    } finally {
                        database.close()
                    }
                }
            }
        }
    }

    @Test
    fun `a failure at the persistence boundary restores credentials and keeps its original error`() = runTest {
        val database = createFnRegressionDatabase()
        try {
            val credentials = FnRegressionCredentialStore()
            val repository = RoomImportSourceRepository(database, FnRegressionGateway(), credentials)
            val original = fnRegressionDraft(FnMusicConnectionMode.ADDRESS)
            val sourceId = repository.addFnMusicSource(original).getOrThrow().sourceId
            val secrets = credentials.values.toMap()
            val failure = CancellationException("cancel at persistence")
            val result = repository.updateFnMusicSource(
                sourceId, original.copy(baseUrl = "https://new-nas.invalid", password = "new-password", clearAccessCode = true),
                progressSink = ImportScanProgressSink {
                    if (it.phase == ImportScanPhase.Persisting) {
                        assertEquals(null, credentials.values[fnMusicAccessCodeCredentialKey(sourceId)])
                        throw failure
                    }
                },
            )
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(failure.message, result.exceptionOrNull()?.message)
            assertEquals(secrets, credentials.values)
            assertEquals(original.baseUrl, database.importSourceDao().getById(sourceId)?.rootReference)
        } finally {
            database.close()
        }
    }

    @Test
    fun `post-commit cleanup failure keeps credentials consistent with the committed source`() = runTest {
        val database = createFnRegressionDatabase()
        try {
            val credentials = FnRegressionCredentialStore()
            val repository = RoomImportSourceRepository(database, FnRegressionGateway(), credentials)
            val original = fnRegressionDraft(FnMusicConnectionMode.ADDRESS)
            val sourceId = repository.addFnMusicSource(original).getOrThrow().sourceId
            val track = database.trackDao().getBySourceId(sourceId).single()
            database.lyricsOffsetDao().upsert(LyricsOffsetEntity(track.id, "obsolete-version", 100))
            database.useWriterConnection { connection ->
                connection.usePrepared("""
                    CREATE TRIGGER reject_offset_cleanup BEFORE DELETE ON lyrics_offset
                    BEGIN SELECT RAISE(ABORT, 'forced post-commit cleanup failure'); END
                """.trimIndent()) { it.step() }
            }

            val result = repository.updateFnMusicSource(
                sourceId, original.copy(label = "Updated", baseUrl = "https://new-nas.invalid", password = "new-password", clearAccessCode = true),
            )

            assertTrue(result.isFailure)
            assertEquals("https://new-nas.invalid", database.importSourceDao().getById(sourceId)?.rootReference)
            assertEquals("new-password", credentials.get("credential-$sourceId"))
            assertEquals(null, credentials.get(fnMusicAccessCodeCredentialKey(sourceId)))
        } finally {
            database.close()
        }
    }

    @Test
    fun `rollback finishes even when the saving coroutine is cancelled`() = runTest {
        for (mode in FnMusicConnectionMode.entries) {
            val database = createFnRegressionDatabase()
            try {
                val credentials = FnRegressionCredentialStore()
                val repository = RoomImportSourceRepository(database, FnRegressionGateway(), credentials)
                val original = fnRegressionDraft(mode)
                val sourceId = repository.addFnMusicSource(original).getOrThrow().sourceId
                val source = database.importSourceDao().getById(sourceId)
                val secrets = credentials.values.toMap()
                var reachedPersistence = false
                val saving = async {
                    val job = currentCoroutineContext()[Job]!!
                    repository.updateFnMusicSource(
                        sourceId, original.copy(label = "Updated", baseUrl = "https://new-nas.invalid", fnId = "new-nas",
                            password = "new-password", clearAccessCode = true),
                        progressSink = ImportScanProgressSink {
                            if (it.phase == ImportScanPhase.Persisting) {
                                reachedPersistence = true
                                job.cancel(CancellationException("cancel saving coroutine"))
                            }
                        },
                    )
                }
                assertFailsWith<CancellationException> { saving.await() }
                assertTrue(reachedPersistence)
                assertEquals(secrets, credentials.values)
                assertEquals(source, database.importSourceDao().getById(sourceId))
            } finally {
                database.close()
            }
        }
    }
}

private fun createFnRegressionDatabase(): LynMusicDatabase = buildLynMusicDatabase(
    Room.databaseBuilder<LynMusicDatabase>(name = Files.createTempFile("lyn-fn-save-regression", ".db").toString()),
)

private fun fnRegressionDraft(mode: FnMusicConnectionMode) = FnMusicSourceDraft(
    "Original", mode, baseUrl = "https://old-nas.invalid", fnId = "old-nas", username = "user", password = "password", accessCode = "saved-code",
)

private suspend fun LynMusicDatabase.rejectFnRegressionUpdate(enabled: Boolean = true) {
    useWriterConnection { connection ->
        for (event in listOf("INSERT", "UPDATE")) {
            val name = "reject_fn_save_${event.lowercase()}"
            val sql = if (enabled) """
                CREATE TRIGGER $name BEFORE $event ON import_source WHEN NEW.label = 'Updated'
                BEGIN SELECT RAISE(ABORT, 'forced source commit failure'); END
            """.trimIndent() else "DROP TRIGGER $name"
            connection.usePrepared(sql) { it.step() }
        }
    }
}

private class FnRegressionCredentialStore : SecureCredentialStore {
    val values = linkedMapOf<String, String>()
    override suspend fun get(key: String): String? = values[key]
    override suspend fun put(key: String, value: String) { yield(); values[key] = value }
    override suspend fun remove(key: String) { yield(); values.remove(key) }
}

private class SlowWanFnMusicHttpClient : LyricsHttpClient {
    val requests = mutableListOf<LyricsRequest>()
    override suspend fun request(request: LyricsRequest): Result<LyricsHttpResponse> {
        requests += request
        return runCatching {
            if (URI(request.url).host == "lan.invalid") throw ConnectException("LAN is unreachable")
            // A healthy WAN needs four seconds, within its ten-second probe budget.
            if ((request.timeoutMillis ?: 30_000L) < 4_000L) throw SocketTimeoutException("WAN response needs 4s")
            when {
                request.url.endsWith("/access_code_verify") -> LyricsHttpResponse(204, "")
                request.url.endsWith("/user/password-login") -> LyricsHttpResponse(200, """{"code":0,"data":{"userToken":"token"}}""")
                request.url.contains("/track/list") -> LyricsHttpResponse(200, """{"code":0,"data":{"list":[],"total":0}}""")
                else -> LyricsHttpResponse(200, """{"code":0,"data":{}}""")
            }
        }
    }
}

private class FnRegressionGateway(private val http: LyricsHttpClient? = null) : ImportSourceGateway {
    override suspend fun pickLocalFolder(): LocalFolderSelection? = error("Unexpected local picker")
    override suspend fun scanLocalFolder(selection: LocalFolderSelection, sourceId: String): ImportScanReport = error("Unexpected local scan")
    override suspend fun testSamba(draft: SambaSourceDraft) = error("Unexpected Samba test")
    override suspend fun scanSamba(draft: SambaSourceDraft, sourceId: String): ImportScanReport = error("Unexpected Samba scan")
    override suspend fun testWebDav(draft: WebDavSourceDraft) = error("Unexpected WebDAV test")
    override suspend fun scanWebDav(draft: WebDavSourceDraft, sourceId: String): ImportScanReport = error("Unexpected WebDAV scan")
    override suspend fun testNavidrome(draft: NavidromeSourceDraft) = error("Unexpected Navidrome test")
    override suspend fun scanNavidrome(draft: NavidromeSourceDraft, sourceId: String): ImportScanReport = error("Unexpected Navidrome scan")
    override suspend fun testFnMusic(draft: FnMusicSourceDraft, deviceId: String) {
        http?.let { testFnMusicConnection(draft, deviceId, it, timeoutMillis = 20_000L) }
    }
    override suspend fun scanFnMusic(draft: FnMusicSourceDraft, sourceId: String, deviceId: String): ImportScanReport =
        scanFnMusic(draft, sourceId, deviceId, ImportScanProgressSink.NoOp)
    override suspend fun scanFnMusic(draft: FnMusicSourceDraft, sourceId: String, deviceId: String, progressSink: ImportScanProgressSink): ImportScanReport =
        http?.let { scanFnMusicLibrary(draft, sourceId, deviceId, it, setOf("flac"), progressSink = progressSink, timeoutMillis = 20_000L) }
            ?: ImportScanReport(tracks = listOf(ImportedTrackCandidate(
                title = draft.label, mediaLocator = buildFnMusicSongLocator(sourceId, "guid"), relativePath = "song.flac",
            )))
}
