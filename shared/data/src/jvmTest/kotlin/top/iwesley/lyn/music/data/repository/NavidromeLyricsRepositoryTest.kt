package top.iwesley.lyn.music.data.repository

import androidx.room.Room
import io.ktor.http.parseUrl
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import top.iwesley.lyn.music.core.model.ImportSourceType
import top.iwesley.lyn.music.core.model.LyricsDocument
import top.iwesley.lyn.music.core.model.LyricsHttpClient
import top.iwesley.lyn.music.core.model.LyricsHttpResponse
import top.iwesley.lyn.music.core.model.LyricsRequest
import top.iwesley.lyn.music.core.model.LyricsSearchApplyMode
import top.iwesley.lyn.music.core.model.SecureCredentialStore
import top.iwesley.lyn.music.core.model.Track
import top.iwesley.lyn.music.core.model.buildSubsonicCompatibleSongLocator
import top.iwesley.lyn.music.data.db.ImportSourceEntity
import top.iwesley.lyn.music.data.db.LyricsCacheEntity
import top.iwesley.lyn.music.data.db.LynMusicDatabase
import top.iwesley.lyn.music.data.db.buildLynMusicDatabase
import top.iwesley.lyn.music.domain.lyricsSourceIdFor
import top.iwesley.lyn.music.domain.parseEnhancedLyricsPresentation

class NavidromeLyricsRepositoryTest {
    @Test
    fun `server eslrc stays enhanced on first load cache reload and manual apply`() = runTest {
        serverTypes.forEach { type ->
            listOf(false, true).forEach { legacy ->
                withServerLyricsFixture(type) { database, track, credentials ->
                    val client = ServerLyricsHttpClient(legacy = legacy)
                    val repository = DefaultLyricsRepository(database, client, credentials)
                    val sourceId = lyricsSourceIdFor(type)

                    val first = assertNotNull(repository.getLyrics(track)).document
                    assertWordLyrics(first, sourceId)
                    assertEquals(if (legacy) listOf("getLyricsBySongId", "getLyrics") else listOf("getLyricsBySongId"), client.endpoints)
                    val cache = assertNotNull(database.lyricsCacheDao().getByTrackIdAndSourceId(track.id, sourceId))
                    assertEquals(first.rawPayload, cache.rawPayload)

                    val offlineClient = ServerLyricsHttpClient(allowRequests = false)
                    val cachedRepository = DefaultLyricsRepository(database, offlineClient, credentials)
                    val cached = assertNotNull(cachedRepository.getLyrics(track)).document
                    assertEquals(first, cached)
                    assertWordLyrics(cached, sourceId)
                    assertTrue(offlineClient.endpoints.isEmpty())

                    val candidate = repository.searchLyricsCandidates(track, includeTrackProvidedCandidate = true).single()
                    assertTrue(candidate.isTrackProvided)
                    assertEquals(first, candidate.document)
                    assertWordLyrics(candidate.document, sourceId)
                    val applied = repository.applyLyricsCandidate(track.id, candidate, LyricsSearchApplyMode.LYRICS_ONLY)
                    assertEquals(first, applied.document)
                    val reloaded = assertNotNull(cachedRepository.getLyrics(track)).document
                    assertEquals(first, reloaded)
                    assertWordLyrics(reloaded, sourceId)
                    assertTrue(offlineClient.endpoints.isEmpty())
                }
            }
        }
    }

    @Test
    fun `existing structured and raw eslrc caches are reinterpreted without server requests`() = runTest {
        serverTypes.forEach { type ->
            listOf(STRUCTURED_ESLRC_RESPONSE, RAW_ESLRC).forEach { rawPayload ->
                withServerLyricsFixture(type) { database, track, credentials ->
                    val sourceId = lyricsSourceIdFor(type)
                    database.lyricsCacheDao().upsert(
                        LyricsCacheEntity(track.id, sourceId, rawPayload, updatedAt = 1L),
                    )
                    val client = ServerLyricsHttpClient(allowRequests = false)
                    val repository = DefaultLyricsRepository(database, client, credentials)
                    val cached = assertNotNull(repository.getLyrics(track)).document

                    assertWordLyrics(cached, sourceId)
                    assertEquals(rawPayload, cached.rawPayload)
                    assertTrue(client.endpoints.isEmpty())
                }
            }
        }
    }

    @Test
    fun `legacy mixed lrc keeps repeated lines sorted on first load and cache reload`() = runTest {
        val rawPayload = "[00:01.00][00:03.00]重复句\n[00:02.00]中间句\n未同步说明"
        serverTypes.forEach { type ->
            withServerLyricsFixture(type) { database, track, credentials ->
                val client = ServerLyricsHttpClient(legacy = true, legacyPayload = rawPayload)
                val repository = DefaultLyricsRepository(database, client, credentials)
                val first = assertNotNull(repository.getLyrics(track)).document

                assertEquals(listOf("getLyricsBySongId", "getLyrics"), client.endpoints)
                assertEquals(lyricsSourceIdFor(type), first.sourceId)
                assertEquals(rawPayload, first.rawPayload)
                assertEquals(listOf(1_000L, 2_000L, 3_000L, null), first.lines.map { it.timestampMs })
                assertEquals(listOf("重复句", "中间句", "重复句", "未同步说明"), first.lines.map { it.text })

                val offlineClient = ServerLyricsHttpClient(allowRequests = false)
                val cachedRepository = DefaultLyricsRepository(database, offlineClient, credentials)
                val cached = assertNotNull(cachedRepository.getLyrics(track)).document
                assertEquals(first, cached)
                assertTrue(offlineClient.endpoints.isEmpty())
            }
        }
    }
}

private val serverTypes = listOf(ImportSourceType.NAVIDROME, ImportSourceType.SUBSONIC)

private fun assertWordLyrics(document: LyricsDocument, sourceId: String) {
    assertEquals(sourceId, document.sourceId)
    assertEquals(125L, document.offsetMs)
    assertEquals("Talk to me", document.lines.single().text)
    assertEquals(8_921L, document.lines.single().timestampMs)
    val presentation = assertNotNull(parseEnhancedLyricsPresentation(document.rawPayload, document))
    val line = presentation.lines.single()
    assertEquals(125L, presentation.offsetMs)
    assertEquals("向我倾诉吧", line.translationText)
    assertEquals(listOf("Talk ", "to ", "me"), line.segments.map { it.text })
    assertEquals(listOf(8_921L, 9_670L, 10_150L), line.segments.map { it.startTimeMs })
    assertEquals(listOf(9_670L, 10_150L, 10_631L), line.segments.map { it.endTimeMs })
    assertEquals(10_631L, line.lineEndTimeMs)
}

private suspend fun withServerLyricsFixture(
    type: ImportSourceType,
    block: suspend (LynMusicDatabase, Track, SecureCredentialStore) -> Unit,
) {
    val directory = Files.createTempDirectory("lynmusic-server-lyrics")
    val database = buildLynMusicDatabase(
        Room.databaseBuilder<LynMusicDatabase>(name = directory.resolve("lyrics.db").absolutePathString()),
    )
    try {
        database.importSourceDao().upsert(
            ImportSourceEntity(
                id = "server-source",
                type = type.name,
                label = "Server",
                rootReference = "https://server.example",
                server = null,
                shareName = null,
                directoryPath = null,
                username = "test-user",
                credentialKey = "server-credential",
                allowInsecureTls = false,
                lastScannedAt = null,
                createdAt = 1L,
            ),
        )
        val track = Track(
            id = "server-track",
            sourceId = "server-source",
            title = "Song",
            mediaLocator = buildSubsonicCompatibleSongLocator(type, "server-source", "song-1"),
            relativePath = "Song.flac",
        )
        val credentials = object : SecureCredentialStore {
            override suspend fun put(key: String, value: String) = error("Unexpected credential write")
            override suspend fun get(key: String): String? = "test-password".takeIf { key == "server-credential" }
            override suspend fun remove(key: String) = error("Unexpected credential removal")
        }
        block(database, track, credentials)
    } finally {
        database.close()
        directory.toFile().deleteRecursively()
    }
}

private class ServerLyricsHttpClient(
    private val legacy: Boolean = false,
    private val allowRequests: Boolean = true,
    private val legacyPayload: String = RAW_ESLRC,
) : LyricsHttpClient {
    val endpoints = mutableListOf<String>()

    override suspend fun request(request: LyricsRequest): Result<LyricsHttpResponse> {
        val url = assertNotNull(parseUrl(request.url))
        val endpoint = url.encodedPath.substringAfterLast('/')
        endpoints += endpoint
        check(allowRequests) { "Cached lyrics must not request the server" }
        return Result.success(when (endpoint) {
            "getLyricsBySongId" -> {
                assertEquals("song-1", url.parameters["id"])
                assertEquals("true", url.parameters["enhanced"])
                if (legacy) LyricsHttpResponse(404, "{}") else LyricsHttpResponse(200, STRUCTURED_ESLRC_RESPONSE)
            }
            "getLyrics" -> LyricsHttpResponse(
                200,
                """{"subsonic-response":{"status":"ok","lyrics":{"value":${JsonPrimitive(legacyPayload)}}}}""",
            )
            else -> error("Unexpected lyrics endpoint: $endpoint")
        })
    }
}

private const val RAW_ESLRC = "[offset:+125]\n" +
    "[00:08.921]Talk [00:09.670]to [00:10.150]me[00:10.631]\n" +
    "[00:08.921]向[00:08.921]我[00:08.921]倾[00:08.921]诉[00:08.921]吧[00:08.921]"

private const val STRUCTURED_ESLRC_RESPONSE = """
    {"subsonic-response":{"status":"ok","lyricsList":{"structuredLyrics":[{
        "synced":true,
        "offset":125,
        "line":[
            {"start":8921,"value":"Talk [00:09.670]to [00:10.150]me[00:10.631]"},
            {"start":8921,"value":"向[00:08.921]我[00:08.921]倾[00:08.921]诉[00:08.921]吧[00:08.921]"}
        ]
    }]}}}
"""
