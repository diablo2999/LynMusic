package top.iwesley.lyn.music.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

@Composable
internal actual fun PlayerLyricsSettingsWindow(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val activityWindow = LocalContext.current.lyricsActivity()?.window
    DisposableEffect(activityWindow) {
        val previous = activityWindow?.attributes?.softInputMode
        activityWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        onDispose { if (previous != null) activityWindow.setSoftInputMode(previous) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0f)
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            onDispose { }
        }
        content()
    }
}

private fun Context.lyricsActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.lyricsActivity()
    else -> null
}
