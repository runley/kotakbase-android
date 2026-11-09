package app.kotakbase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Settings → Extensions (desktop's Extensions page): add by manifest link, switch on and off, delete. */
@Composable
fun ExtensionsSection() {
    Extensions.version.intValue
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val configs = Extensions.configs
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t("Extensions"), style = MaterialTheme.typography.titleMedium)
        MutedText(t("Paste a Hayase extension manifest URL (https://…/index.json, gh:user/repo or npm:package)."))
        OutlinedTextField(url, { url = it; error = null }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(t("Manifest URL")) })
        Button({
            busy = true
            scope.launch {
                runCatching { Extensions.importRepo(url) }.onSuccess { url = "" }.onFailure { error = it.message ?: it.toString() }
                busy = false
            }
        }, enabled = url.isNotBlank() && !busy) { Text(if (busy) t("Installing…") else t("Add")) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (configs.length() == 0) MutedText(t("No extensions installed."))
        configs.keys().asSequence().toList().forEach { id ->
            val c = configs.getJSONObject(id)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.optString("name", id), fontWeight = FontWeight.Bold)
                    MutedText(listOf(c.optString("version"), c.optString("type"), t("{level} accuracy", "level" to t(c.optString("accuracy")))).filter { it.isNotEmpty() }.joinToString(" · "))
                }
                Switch(Extensions.enabled(id), { Extensions.setEnabled(id, it) })
                TextButton({ scope.launch { Extensions.remove(id) } }) { Text(t("Delete")) }
            }
        }
    }
}
