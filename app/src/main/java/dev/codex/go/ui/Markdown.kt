package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Codex Go", text))
}
@Composable fun Markdown(text: String, openFile: (String) -> Unit = {}) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val chunks = text.split("```")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        chunks.forEachIndexed { index, part ->
            if (part.isEmpty()) return@forEachIndexed
            if (index % 2 == 1) {
                val code = if (part.contains('\n')) part.substringAfter('\n') else part
                Surface(shape = RoundedCornerShape(14.dp), color = colors.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(part.substringBefore('\n').take(20).ifBlank { "code" }, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Text(tr(R.string.copy), modifier = Modifier.clickable { copy(context, code.trimEnd()) }.padding(start = 18.dp), fontSize = 12.sp, color = colors.primary)
                        }
                        Spacer(Modifier.height(10.dp))
                        SelectionContainer { Text(code.trimEnd(), Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp) }
                    }
                }
            } else SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    part.trim().lines().forEach { line ->
                        val heading = line.startsWith('#')
                        val displayed = if (heading) line.trimStart('#', ' ') else line
                        val annotated = buildAnnotatedString {
                            val regex = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^]]+)]\\(((?:https?://|sandbox:/(?:workspace|projects)/|/(?:workspace|projects)/)[^)]+)\\)")
                            var offset = 0
                            regex.findAll(displayed).forEach { match ->
                                append(displayed.substring(offset, match.range.first))
                                when {
                                    match.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.groupValues[1]) }
                                    match.groups[2] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceContainerHigh)) { append(match.groupValues[2]) }
                                    else -> {
                                        val url = match.groupValues[4]
                                        val style = TextLinkStyles(style = SpanStyle(color = colors.primary))
                                        val link = if (url.startsWith("http")) LinkAnnotation.Url(url, style) else LinkAnnotation.Clickable(url, style) { openFile(url) }
                                        withLink(link) { append(match.groupValues[3]) }
                                    }
                                }
                                offset = match.range.last + 1
                            }
                            append(displayed.substring(offset))
                        }
                        Text(annotated, style = if (heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                            fontWeight = if (heading) FontWeight.SemiBold else null)
                    }
                }
            }
        }
    }
}
