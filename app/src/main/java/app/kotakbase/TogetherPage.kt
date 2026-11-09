package app.kotakbase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** A code typed in any format, or the end of an invite link (…/join#CODE or kotakbase://join/CODE) (desktop parseCode). */
fun parseCode(text: String): String? = text.trim().split('#', '/').last().uppercase().replace(Regex("[^A-Z0-9]"), "").takeIf { it.matches(Regex("[A-HJ-NP-Z2-9]{12}")) }

/** Watch Together (desktop togetherPage): start or join a room, who's in it, what it's watching, and its chat. */
@Composable
fun TogetherPage(nav: Nav, invite: String? = null) {
    val room = Room.state
    var name by remember { mutableStateOf(Room.name()) }
    var code by remember { mutableStateOf(invite.orEmpty()) }
    var message by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(t("Watch Together"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        Room.error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
        if (room == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(t("Your name")) })
                    Text(t("Start a room"), style = MaterialTheme.typography.titleMedium)
                    Button({ Room.open(null, name.ifBlank { Room.name() }) }) { Text(t("Create room")) }
                    Text(t("Join a room"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    MutedText(t("Open the invite link someone shared, or enter their room code."))
                    OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(t("Room code")) })
                    Button({ parseCode(code)?.let { Room.open(it, name.ifBlank { Room.name() }) } }, enabled = parseCode(code) != null) { Text(t("Join")) }
                }
            }
            return@LazyColumn
        }
        val members = room.optJSONArray("members")?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()
        val me = room.optJSONObject("me")
        val hostId = room.optString("host")
        val isHost = room.optBoolean("isHost")
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("Room code"), color = Muted)
                Text(room.optString("formatted"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ clipboard.setText(AnnotatedString(room.optString("formatted"))); copied = true }) { Text(t("Copy code")) }
                    OutlinedButton({ clipboard.setText(AnnotatedString(room.optString("invite"))); copied = true }) { Text(t("Copy invite link")) }
                    if (copied) MutedText(t("Copied"))
                }
                if (room.optString("problem").isNotEmpty()) Text(room.optString("problem"), color = MaterialTheme.colorScheme.error)
                if (members.isEmpty()) MutedText(t("Waiting for others to join…"))
            }
        }
        item {
            val load = room.optJSONObject("load")
            Text(t("Now watching"), style = MaterialTheme.typography.titleMedium)
            if (load == null) MutedText(t("Nothing yet. The host picks a show and episode as usual."))
            else MutedText("${load.optString("title").ifEmpty { "#" + load.optInt("mediaId") }} · ${t("Episode {n}", "n" to load.optInt("episode"))}")
            // the host's own player came first; anyone else who closed theirs can open it again here
            if (load != null && !isHost) TextButton({ nav.open(load.optInt("mediaId")) }) { Text(t("Watch with the room")) }
        }
        item { Text(t("People") + " · " + t("{n} people in the room", "n" to members.size + 1), style = MaterialTheme.typography.titleMedium) }
        item { Person(me?.optString("name").orEmpty() + " " + t("(you)"), me?.optString("id") == hostId, null) }
        items(members, key = { it.optString("id") }) { p ->
            val id = p.optString("id")
            Person(if (p.optBoolean("known")) p.optString("name") else t("Connecting…"), id == hostId, if (isHost) id else null)
        }
        item { Text(t("Chat"), style = MaterialTheme.typography.titleMedium) }
        val chat = room.optJSONArray("chat")?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()
        if (chat.isEmpty()) item { MutedText(t("No messages yet. While watching, the people icon at the top of the player opens a box to write in.")) }
        items(chat.takeLast(100)) { c -> Text("${c.optString("name")}: ${c.optString("text")}", color = if (c.optBoolean("mine")) Accent else TextColor) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(message, { message = it.take(300) }, Modifier.weight(1f), singleLine = true, placeholder = { Text(t("Say something…")) })
                Button({ Room.say(message); message = "" }, enabled = message.isNotBlank()) { Text(t("Send")) }
            }
        }
        item { OutlinedButton({ Room.leave() }) { Text(t("Leave room")) } }
    }
}

@Composable
private fun Person(name: String, host: Boolean, manage: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, Modifier.weight(1f))
        if (host) Text(t("Host"), color = Accent, fontWeight = FontWeight.Bold)
        manage?.let { id ->
            TextButton({ Room.makeHost(id) }) { Text(t("Make host")) }
            TextButton({ Room.kick(id, false) }) { Text(t("Kick")) }
        }
    }
}
