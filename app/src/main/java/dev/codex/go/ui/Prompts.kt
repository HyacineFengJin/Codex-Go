package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.*
import dev.codex.go.protocol.*
import dev.codex.go.runtime.*
import org.json.JSONObject

@Composable fun DeviceLoginDialog(login: DeviceLogin, submitCode: (String) -> Unit, cancel: () -> Unit) {
    val context = LocalContext.current
    var code by remember(login.id) { mutableStateOf("") }
    AlertDialog(onDismissRequest = cancel, title = { Text(if (login.provider == "Google") tr(R.string.sign_in_google) else tr(R.string.sign_in_to_chatgpt)) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (login.code.isNotBlank()) Text(tr(R.string.copy_the_device_code_and_enter_it_in_your_browser_after_signing_i))
            if (login.code.isNotBlank()) Text(login.code, fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            if (login.requiresCode) {
                Text(tr(R.string.agent_paste_auth_code), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(code, { code = it.take(8192) }, label = { Text(tr(R.string.agent_auth_code)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { submitCode(code); code = "" }, enabled = code.isNotBlank()) { Text(tr(R.string.agent_submit_auth_code)) }
            }
            Text(if (login.requiresCode) Uri.parse(login.url).host.orEmpty() else login.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { Button(onClick = { if (login.code.isNotBlank()) copy(context, login.code); runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(login.url))) } }) { Text(if (login.code.isBlank()) tr(R.string.open_sign_in) else tr(R.string.copy_open_verification_page)) } }, dismissButton = { TextButton(onClick = cancel) { Text(tr(R.string.cancel_sign_in)) } })
}

@Composable fun PromptDialog(prompt: ClientPrompt, changes: ChatItem? = null, answer: (JSONObject) -> Unit) {
    val question = prompt.method.endsWith("requestUserInput")
    if (question) {
        val questions = remember(prompt) { prompt.params.optJSONArray("questions")?.objects().orEmpty() }
        val answers = remember(prompt) { mutableStateMapOf<String, String>() }
        AlertDialog(onDismissRequest = { answer(obj("answers" to obj())) }, title = { Text(tr(R.string.codex_needs_your_input)) }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                questions.forEach { value ->
                    val id = value.optString("id")
                    Text(value.optString("question"), fontWeight = FontWeight.Medium)
                    value.optJSONArray("options")?.objects()?.forEach { option ->
                        val label = option.optString("label")
                        OutlinedButton(onClick = { answers[id] = label }, Modifier.fillMaxWidth()) {
                            Column { Text((if (answers[id] == label) "✓ " else "") + label); option.optString("description").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
                        }
                    }
                    OutlinedTextField(answers[id].orEmpty(), { answers[id] = it }, Modifier.fillMaxWidth(), placeholder = { Text(tr(R.string.enter_your_answer)) },
                        visualTransformation = if (value.optBoolean("isSecret")) PasswordVisualTransformation() else VisualTransformation.None)
                }
            }
        }, confirmButton = { TextButton(onClick = {
            val result = obj(); questions.forEach { result.put(it.optString("id"), obj("answers" to array(answers[it.optString("id")].orEmpty()))) }
            answer(obj("answers" to result))
        }, enabled = questions.all { !answers[it.optString("id")].isNullOrBlank() }) { Text(tr(R.string.reply)) } }, dismissButton = { TextButton(onClick = { answer(obj("answers" to obj())) }) { Text(tr(R.string.cancel)) } })
    } else {
        val params = prompt.params
        val network = params.optJSONObject("networkApprovalContext")
        val acp = prompt.method == "antigravity/requestPermission"
        val androidShell = prompt.method == "android_shell"
        val fileChange = prompt.method.contains("fileChange")
        val canApprove = (!acp || !params.optBoolean("truncated")) && (!fileChange || (changes != null && changes.reviewComplete && changes.text.isNotBlank()))
        val title = if (acp) tr(R.string.agent_permission) else if (androidShell) tr(R.string.run_a_command_on_android) else if (network != null) tr(R.string.allow_network_access) else if (prompt.method.contains("fileChange")) tr(R.string.allow_file_changes) else tr(R.string.allow_command_execution)
        val body = if (androidShell) params.optJSONObject("arguments")?.optString("command").orEmpty() else if (network != null) "${network.optString("protocol")}://${network.optString("host")}" else params.optString("command").ifBlank { params.optString("grantRoot", tr(R.string.codex_requests_a_file_operation)) }
        AlertDialog(onDismissRequest = { answer(obj("decision" to "decline")) }, title = { Text(title) }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (acp) { ReadableText(params.optString("command")); ReadableText(params.optString("details"), style = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace)); if (!canApprove) Text(tr(R.string.agent_incomplete_review), color = MaterialTheme.colorScheme.error) }
                if (androidShell) Text(tr(R.string.runs_on_this_phone_s_android_shell_arg , metadata(params.optString("_shellLabel", tr(R.string.wireless_debugging)))), style = MaterialTheme.typography.bodySmall)
                if (prompt.method.contains("fileChange")) {
                    if (!canApprove) Text(tr(R.string.the_complete_diff_has_not_arrived_decline_and_ask_codex_to_provid), color = MaterialTheme.colorScheme.error)
                    if (changes != null && changes.text.isNotBlank()) DiffText(changes.text)
                    params.stringOrNull("grantRoot")?.let { Text(tr(R.string.requested_directory_arg , it), fontFamily = FontFamily.Monospace) }
                } else if (!acp) ReadableText(body, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                params.stringOrNull("cwd")?.let { Text(tr(R.string.directory_arg , it), style = MaterialTheme.typography.bodySmall) }
                params.stringOrNull("reason")?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }, confirmButton = { Button(enabled = canApprove, onClick = { answer(obj("decision" to "accept")) }) { Text(tr(R.string.allow_once)) } }, dismissButton = { TextButton(onClick = { answer(obj("decision" to "decline")) }) { Text(tr(R.string.decline)) } })
    }
}

@Composable private fun DiffText(text: String) {
    if (dev.codex.go.protocol.TextPages.needsReader(text)) {
        ReadableText(text, style = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp))
        return
    }
    val color = MaterialTheme.colorScheme.onSurface
    val annotated = buildAnnotatedString {
        text.lineSequence().forEach { line ->
            withStyle(SpanStyle(color = when { line.startsWith("+") -> Color(0xff21854a); line.startsWith("-") -> Color(0xffbd3d3d); else -> color })) { append(line); append('\n') }
        }
    }
    androidx.compose.foundation.text.selection.SelectionContainer { Text(annotated, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp) }
}
