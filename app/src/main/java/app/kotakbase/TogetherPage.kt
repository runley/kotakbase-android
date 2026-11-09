package app.kotakbase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.security.SecureRandom

/** A code typed in any format, or the end of an invite link (…/join#CODE or kotakbase://join/CODE) (desktop parseCode). */
fun parseCode(text: String): String? = text.trim().split('#', '/').last().uppercase().replace(Regex("[^A-Z0-9]"), "").takeIf { it.matches(Regex("[A-HJ-NP-Z2-9]{12}")) }
fun formatCode(code: String) = code.chunked(4).joinToString("-")
private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O or 1/I (desktop newCode)
fun newCode() = SecureRandom().let { r -> String(CharArray(12) { CODE_CHARS[r.nextInt(32)] }) }
fun validPin(pin: String) = pin.matches(Regex("\\d{4,8}"))

/** Watch Together (desktop togetherPage): start, join or come back to a room, who's in it, what it's watching, and its chat. */
@Composable
fun TogetherPage(nav: Nav, invite: String? = null) {
    val room = Room.state
    var name by remember { mutableStateOf(Room.name()) }
    var code by remember { mutableStateOf(invite.orEmpty()) }
    // an invite for a room saved here: with its PIN (desktop joinInvite)
    var pin by remember { mutableStateOf(invite?.let(::parseCode)?.let { c -> Room.saved.find { it.code == c }?.pin }.orEmpty()) }
    var roomName by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf("") }
    var forgetting by remember { mutableStateOf<Room.Saved?>(null) }
    var message by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val me = { name.trim().ifEmpty { Room.name() } }
    val numeric = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)

    forgetting?.let { r ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text(t("Delete this room?")) },
            text = { Text(t("{name} is removed from this device. Others who saved it keep it, and you can join again with its code and PIN.", "name" to Room.title(r))) },
            confirmButton = { TextButton({ Room.forget(r.code); forgetting = null }) { Text(t("Delete")) } },
            dismissButton = { TextButton({ forgetting = null }) { Text(t("Cancel")) } },
        )
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(t("Watch Together"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        Room.error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
        if (room == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MutedText(t("Watch with friends on other devices. The host picks the show, episode and source, and everyone's player follows: pausing, playing and seeking happen for everyone."))
                    OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(t("Your name")) })
                    Text(t("Start a room"), style = MaterialTheme.typography.titleMedium)
                    MutedText(t("You'll be the host. Share the invite link or the room code with your friends."))
                    Button({ Room.open(null, me()) }) { Text(t("Create room")) }
                    Text(t("Join a room"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    MutedText(t("Open the invite link someone shared, or enter their room code."))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(code, { code = it.take(40) }, Modifier.weight(1f), singleLine = true, label = { Text(t("Room code")) }, placeholder = { Text("XXXX-XXXX-XXXX") })
                        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, Modifier.width(110.dp), singleLine = true, label = { Text(t("PIN")) }, keyboardOptions = numeric)
                    }
                    MutedText(t("Joining a saved room? Add its PIN and it stays below."))
                    Button({
                        val c = parseCode(code)
                        formError = when {
                            c == null -> t("That isn't a room code. It looks like XXXX-XXXX-XXXX.")
                            pin.isNotEmpty() && !validPin(pin) -> t("The PIN is 4 to 8 digits.")
                            else -> ""
                        }
                        if (c != null && formError.isEmpty()) {
                            val known = Room.saved.find { it.code == c }
                            Room.open(c, me(), if (pin.isEmpty()) null else Room.Saved(c, pin, known?.takeIf { it.pin == pin }?.name))
                        }
                    }) { Text(t("Join")) }
                }
            }
            item { Text(t("Saved rooms"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
            item {
                Box {
                    Text(t("New saved room"), fontWeight = FontWeight.Bold)
                    MutedText(t("A room you and your friends can come back to. Anyone with its code and PIN can join, and whoever is in it first is the host."))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(roomName, { roomName = it.take(40) }, Modifier.weight(1f), singleLine = true, label = { Text(t("Room name")) })
                        OutlinedTextField(newPin, { newPin = it.filter(Char::isDigit).take(8) }, Modifier.width(110.dp), singleLine = true, label = { Text(t("PIN")) }, keyboardOptions = numeric)
                    }
                    MutedText(t("PIN (4 to 8 digits)"))
                    Button({
                        formError = when {
                            roomName.isBlank() -> t("Give the room a name.")
                            !validPin(newPin) -> t("The PIN is 4 to 8 digits.")
                            else -> ""
                        }
                        if (formError.isEmpty()) { val c = newCode(); Room.open(c, me(), Room.Saved(c, newPin, roomName.trim())) }
                    }) { Text(t("Create room")) }
                }
            }
            items(Room.saved, key = { it.code }) { r ->
                Box {
                    Text(Room.title(r), fontWeight = FontWeight.Bold)
                    MutedText(t("Room code") + ": " + formatCode(r.code))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ Room.open(r.code, me(), r) }) { Text(t("Join")) }
                        OutlinedButton({ forgetting = r }) { Text(t("Delete")) }
                    }
                }
            }
            if (formError.isNotEmpty()) item { Text(formError, color = MaterialTheme.colorScheme.error) }
            return@LazyColumn
        }
        val members = room.optJSONArray("members")?.let { a -> List(a.length()) { a.getJSONObject(it) } }.orEmpty()
        val self = room.optJSONObject("me")
        val hostId = room.optString("host")
        val isHost = room.optBoolean("isHost")
        val roomPin = room.optString("pin").takeIf { it.isNotEmpty() && it != "null" }
        item {
            Box {
                // a saved room: its name, and the PIN to share with the code (no invite link: that has no PIN)
                Text(if (roomPin != null) Room.title(Room.saved.find { it.code == room.optString("code") } ?: Room.Saved(room.optString("code"), roomPin, null)) + " · " + t("Room code") else t("Room code"), color = Muted)
                Text(room.optString("formatted"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (roomPin != null) MutedText(t("PIN: {pin}", "pin" to roomPin))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ clipboard.setText(AnnotatedString(room.optString("formatted"))); copied = true }) { Text(t("Copy code")) }
                    if (roomPin == null) OutlinedButton({ clipboard.setText(AnnotatedString(room.optString("invite"))); copied = true }) { Text(t("Copy invite link")) }
                    if (copied) MutedText(t("Copied"))
                }
                if (roomPin != null) MutedText(t("A saved room: share the code and the PIN. Whoever is in it first is the host.") +
                    if (members.isEmpty()) " " + t("Expected others here? Check the code and PIN.") else "")
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
        item { Person(self?.optString("name").orEmpty() + " " + t("(you)"), self?.optString("id") == hostId, null) }
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

/** A panel, like the desktop's .box. */
@Composable
private fun Box(content: @Composable () -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }

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
