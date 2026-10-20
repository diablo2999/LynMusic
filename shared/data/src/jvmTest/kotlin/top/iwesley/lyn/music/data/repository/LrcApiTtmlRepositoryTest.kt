package top.iwesley.lyn.music.data.repository

import androidx.room.Room
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.iwesley.lyn.music.core.model.LyricsHttpClient
import top.iwesley.lyn.music.core.model.LyricsHttpResponse
import top.iwesley.lyn.music.core.model.LyricsRequest
import top.iwesley.lyn.music.core.model.LyricsSearchApplyMode
import top.iwesley.lyn.music.core.model.SecureCredentialStore
import top.iwesley.lyn.music.core.model.Track
import top.iwesley.lyn.music.data.db.LynMusicDatabase
import top.iwesley.lyn.music.data.db.LyricsCacheEntity
import top.iwesley.lyn.music.data.db.LyricsSourceConfigEntity
import top.iwesley.lyn.music.data.db.buildLynMusicDatabase
import top.iwesley.lyn.music.domain.LRCAPI_JSON_MAP_EXTRACTOR
import top.iwesley.lyn.music.domain.MANAGED_LRCAPI_SOURCE_ID
import top.iwesley.lyn.music.domain.parseEnhancedLyricsPresentation

class LrcApiTtmlRepositoryTest {
    private val track = Track(
        id = "track:local:ttml", sourceId = "local", title = "Song", artistName = "Artist", albumTitle = "Album",
        durationMs = 180_000L, mediaLocator = "file:///missing/Song.flac", relativePath = "Song.flac",
    )
    private val ttml = """<歌词:tt xmlns:歌词="http://www.w3.org/ns/ttml" xmlns:m="urn:metadata"><歌词:body><歌词:p begin="1s" end="3s"><歌词:span begin="1s" end="2s">Word</歌词:span> <歌词:span begin="2s" end="3s">line</歌词:span><歌词:span m:role="x-translation">译文</歌词:span></歌词:p></歌词:body></歌词:tt>"""

    @Test
    fun `automatic lookup manual search and database reopen preserve Unicode prefixed TTML with one request per lookup`() = runTest {
        val directory = Files.createTempDirectory("lynmusic-lrcapi-ttml")
        val path = directory.resolve("lyrics.db").toString()
        fun open() = buildLynMusicDatabase(Room.databaseBuilder<LynMusicDatabase>(name = path))
        var database = open()
        try {
            // Use the existing, persisted extractor unchanged: no settings migration is needed.
            database.lyricsSourceConfigDao().upsert(source())
            val client = RecordingTtmlClient(response(ttml))
            val repository = DefaultLyricsRepository(database, client, emptyCredentials)
            val automatic = assertNotNull(repository.getLyrics(track))
            assertEquals(1, client.requests.size)
            assertEquals("Word line", automatic.document.lines.single().text)
            assertEquals(ttml, automatic.document.rawPayload)
            assertTrue(repository.saveOffset(track, automatic.version, 350L))

            val candidate = repository.searchLyricsCandidates(track, includeTrackProvidedCandidate = false).single()
            assertEquals(2, client.requests.size)
            assertEquals(automatic.document, candidate.document)
            assertEquals("song-1", candidate.itemId)
            val applied = repository.applyLyricsCandidate(track.id, candidate, LyricsSearchApplyMode.LYRICS_ONLY)
            assertEquals(ttml, assertNotNull(applied.document).rawPayload)
            assertEquals(automatic.version, applied.version)

            database.close()
            database = open()
            val offline = RecordingTtmlClient(null)
            val reloaded = assertNotNull(DefaultLyricsRepository(database, offline, emptyCredentials).getLyrics(track))
            assertEquals(ttml, reloaded.document.rawPayload)
            val line = assertNotNull(parseEnhancedLyricsPresentation(ttml, reloaded.document)).lines.single()
            assertEquals("译文", line.translationText)
            assertEquals(2, line.segments.size)
            assertEquals(automatic.version, reloaded.version)
            assertEquals(350L, database.readLyricsOffset(track, reloaded.version))
            assertTrue(offline.requests.isEmpty())
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `broken TTML falls back to LRC on automatic lookup and manual search`() = runTest {
        withDatabase { database ->
            database.lyricsSourceConfigDao().upsert(source())
            val client = RecordingTtmlClient(response("<tt><body><p>broken"))
            val repository = DefaultLyricsRepository(database, client, emptyCredentials)

            val automatic = assertNotNull(repository.getLyrics(track))
            val candidate = repository.searchLyricsCandidates(track, includeTrackProvidedCandidate = false).single()

            assertEquals("LRC fallback", automatic.document.lines.single().text)
            assertEquals(automatic.document, candidate.document)
            assertEquals(2, client.requests.size)
            assertEquals("[00:01.00]LRC fallback", automatic.document.rawPayload)
        }
    }

    @Test
    fun `existing LRC cache remains usable offline without refreshing to TTML`() = runTest {
        withDatabase { database ->
            database.lyricsSourceConfigDao().upsert(source())
            database.lyricsCacheDao().upsert(LyricsCacheEntity(track.id, MANAGED_LRCAPI_SOURCE_ID, "[00:01.00]Old LRC", 1L))
            val client = RecordingTtmlClient(null)
            val resolved = assertNotNull(DefaultLyricsRepository(database, client, emptyCredentials).getLyrics(track))

            assertEquals("Old LRC", resolved.document.lines.single().text)
            assertTrue(client.requests.isEmpty())
        }
    }

    private fun source() = LyricsSourceConfigEntity(
        id = MANAGED_LRCAPI_SOURCE_ID, name = "LrcAPI", method = "GET", urlTemplate = "https://lyrics.example/jsonapi",
        headersTemplate = "", queryTemplate = "title={title}&artist={artist}", bodyTemplate = "", responseFormat = "JSON",
        extractor = LRCAPI_JSON_MAP_EXTRACTOR, priority = 110, enabled = true,
    )

    private fun response(raw: String) = buildJsonArray {
        add(buildJsonObject {
            put("id", "song-1")
            put("title", "Song")
            put("artist", "Artist")
            put("album", "Album")
            put("duration", 180)
            put("lrc_ttml", raw)
            put("lrc", "[00:01.00]LRC fallback")
        })
    }.toString()

    private suspend fun withDatabase(block: suspend (LynMusicDatabase) -> Unit) {
        val directory = Files.createTempDirectory("lynmusic-lrcapi-ttml")
        val database = buildLynMusicDatabase(Room.databaseBuilder<LynMusicDatabase>(name = directory.resolve("lyrics.db").toString()))
        try {
            block(database)
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }
}

private class RecordingTtmlClient(private val payload: String?) : LyricsHttpClient {
    val requests = mutableListOf<LyricsRequest>()
    override suspend fun request(request: LyricsRequest): Result<LyricsHttpResponse> {
        requests += request
        return payload?.let { Result.success(LyricsHttpResponse(statusCode = 200, body = it)) }
            ?: Result.failure(IllegalStateException("Offline"))
    }
}

private val emptyCredentials = object : SecureCredentialStore {
    override suspend fun get(key: String): String? = null
    override suspend fun put(key: String, value: String) = Unit
    override suspend fun remove(key: String) = Unit
}
