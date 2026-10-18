package top.iwesley.lyn.music.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import top.iwesley.lyn.music.core.model.ImportSourceType

class TvFnMusicFocusTest {
    @Test
    fun `both connection modes reach access code before credentials`() {
        for (fnConnect in listOf(false, true)) {
            val rows = remoteSourceDialogFocusRows("fn", ImportSourceType.FN_MUSIC, fnConnect = fnConnect)
            assertEquals(listOf(
                listOf("fn:label"), listOf("fn:fnconnect"), listOf(if (fnConnect) "fn:fnid" else "fn:root"),
                listOf("fn:access"), listOf("fn:username", "fn:password"), listOf("fn:test", "fn:cancel", "fn:submit"),
            ), rows)
        }
    }

    @Test
    fun `clearing skips disabled input and keeps the clear switch reachable`() {
        for (fnConnect in listOf(false, true)) {
            for (clear in listOf(false, true)) {
                val rows = remoteSourceDialogFocusRows("fn", ImportSourceType.FN_MUSIC, fnConnect = fnConnect, isEditing = true, clearAccessCode = clear)
                assertEquals(listOfNotNull(
                    listOf("fn:label"), listOf("fn:fnconnect"), listOf(if (fnConnect) "fn:fnid" else "fn:root"),
                    if (clear) null else listOf("fn:access"), listOf("fn:clear-access"), listOf("fn:username", "fn:password"),
                    listOf("fn:test", "fn:cancel", "fn:submit"),
                ), rows)
            }
        }
    }
}
