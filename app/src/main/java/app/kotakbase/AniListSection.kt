package app.kotakbase

import android.annotation.SuppressLint
import android.text.format.DateUtils
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings → AniList (desktop createSync settings()): sign in, what the sync is doing, Sync now, sign out. */
@Composable
fun AniListSection() {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    var confirmOut by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } } // "Synced 2 min ago" moves on
    val sync = AniSync.anime
    sync.version; AniSync.manga.version
    val user = Auth.user

    fun signIn() = scope.launch {
        error = ""
        val r = Auth.signIn()
        if (r != null) { error = r; return@launch }
        // its first sync, then the manga list's, so the two questions don't overlap
        AniSync.anime.start(first = true)
        while (AniSync.anime.syncing || AniSync.asking != null) delay(200)
        AniSync.manga.start(first = true)
    }
    fun signOut() { Auth.signOut(); AniSync.anime.signedOut(); AniSync.manga.signedOut() }

    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AniList", style = MaterialTheme.typography.titleMedium)
        when {
            !Auth.configured -> MutedText(t("AniList sync isn't set up in this build (no AniList Client ID)."))
            user == null -> {
                MutedText(t("Keep your progress, Your List and ratings in sync with your AniList list. Kotakbase only changes the entries of shows you watch, list or rate here."))
                Button(::signIn) { Text(t("Sign in with AniList")) }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    user.avatar?.let { AsyncImage(it, null, Modifier.size(36.dp).clip(CircleShape)) }
                    Text(user.name, fontWeight = FontWeight.Bold)
                    if (!Auth.expired) MutedText(status(sync, now))
                }
                if (Auth.expired) Text(t("Your AniList sign-in expired or was revoked, so nothing is being synced."), color = MaterialTheme.colorScheme.error)
                if (sync.note.isNotEmpty()) MutedText(sync.note)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (Auth.expired) Button(::signIn) { Text(t("Sign in again")) }
                    else OutlinedButton({ AniSync.anime.syncNow(); AniSync.manga.syncNow() }, enabled = !sync.syncing) { Text(t("Sync now")) }
                    OutlinedButton({ if (sync.pending() + AniSync.manga.pending() > 0) confirmOut = true else signOut() }) { Text(t("Sign out")) }
                }
            }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    }
    if (confirmOut) AlertDialog({ confirmOut = false },
        confirmButton = { TextButton({ confirmOut = false; signOut() }) { Text(t("Sign out")) } },
        dismissButton = { TextButton({ confirmOut = false }) { Text(t("Cancel")) } },
        title = { Text(t("Sign out of AniList?")) },
        text = { Text(t("{n} changes haven't reached AniList yet and will be dropped.", "n" to (sync.pending() + AniSync.manga.pending()))) })
}

private fun status(s: AniSync, now: Long): String {
    val n = s.pending()
    val at = s.base()?.optLong("at") ?: 0
    return when {
        s.syncing -> t("Syncing…")
        n > 0 -> "${t("{n} changes waiting", "n" to n)}, ${s.problem.ifEmpty { t("sending…") }}"
        s.problem.isNotEmpty() -> t("Couldn't sync: {error}", "error" to s.problem)
        at > 0 -> t("Synced: {when}", "when" to if (now - at < 60_000) t("just now") else DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS))
        else -> ""
    }
}

/** AniList's sign-in page; its redirect back (with the token) is caught before it loads, as on desktop. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignInDialog() {
    val done = Auth.signingIn ?: return
    val scope = rememberCoroutineScope()
    Dialog({ done.complete("") }, DialogProperties(usePlatformDefaultWidth = false)) {
        AndroidView({ context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true // AniList's sign-in page needs it
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val r = Auth.parseRedirect(request.url.toString()) ?: return false
                        r.fold({ (token, expires) -> scope.launch { done.complete(Auth.finish(token, expires)) } }, { done.complete(it.message) })
                        return true
                    }
                }
                loadUrl(Auth.authorizeUrl)
            }
        }, Modifier.fillMaxSize())
    }
}

/** The first sync's question (desktop ask()): what will move each way; Cancel signs out and changes nothing. */
@Composable
fun FirstSyncDialog() {
    val (title, lines, answer) = AniSync.asking ?: return
    AlertDialog({ answer.complete(false) },
        confirmButton = { TextButton({ answer.complete(true) }) { Text(t("Sync")) } },
        dismissButton = { TextButton({ answer.complete(false) }) { Text(t("Cancel")) } },
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { lines.forEach { Text(it) } } })
}
