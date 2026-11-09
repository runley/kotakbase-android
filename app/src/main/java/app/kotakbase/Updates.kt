package app.kotakbase

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Updates from this repo's GitHub releases (the desktop app updates itself the same way): at start and on request,
 * the latest release's APK is downloaded if it's newer, then installing it is offered. Android checks the APK is
 * signed with the same key as the installed app, and asks before installing.
 */
object Updates {
    const val REPO = "runley/kotakbase-android"
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** checking | downloading | ready | latest | error, or "" before the first check. */
    var status by mutableStateOf("")
        private set
    var version by mutableStateOf("")
        private set
    var error by mutableStateOf("")
        private set
    var dismissed by mutableStateOf(false)
    private val apk get() = File(app.cacheDir, "update.apk")

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        if (BuildConfig.DEBUG) return // debug builds are installed by hand, and signed with another key
        if (System.currentTimeMillis() - app.getSharedPreferences("updates", 0).getLong("checked", 0) > 6 * 3600_000) check()
    }

    /** "1.10.0" > "1.9.2": numbers compared part by part. */
    fun newer(a: String, b: String): Boolean {
        val x = a.trimStart('v').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.trimStart('v').split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    fun check() {
        if (status == "checking" || status == "downloading") return
        status = "checking"
        scope.launch {
            runCatching {
                val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest").header("Accept", "application/vnd.github+json").build()
                val release = AniList.http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw Exception("HTTP ${r.code}"); JSONObject(r.body?.string().orEmpty()) }
                app.getSharedPreferences("updates", 0).edit().putLong("checked", System.currentTimeMillis()).apply()
                val latest = release.optString("tag_name").trimStart('v')
                if (!newer(latest, BuildConfig.VERSION_NAME)) { status = "latest"; return@launch }
                val assets = release.getJSONArray("assets")
                val url = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }
                    ?.optString("browser_download_url") ?: throw Exception("no APK in release ${release.optString("tag_name")}")
                version = latest
                status = "downloading"
                val tmp = File(apk.path + ".part")
                AniList.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                    tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                }
                tmp.renameTo(apk)
                status = "ready"
            }.onFailure { error = it.message ?: it.toString(); status = "error" }
        }
    }

    /** Opens Android's installer on the downloaded APK (it asks the user, and allows only the same signing key). */
    fun install(context: Context) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
