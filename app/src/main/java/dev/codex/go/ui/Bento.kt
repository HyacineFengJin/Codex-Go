package dev.codex.go.ui

import dev.codex.go.i18n.*
import dev.codex.go.R

import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import dev.codex.go.runtime.*

@Composable fun BentoIcon(icon: ImageVector, background: Color = MaterialTheme.colorScheme.primaryContainer, foreground: Color = MaterialTheme.colorScheme.onPrimaryContainer, size: Dp = 42.dp) {
    Surface(shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 6.dp), color = background) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(size * .5f), tint = foreground) }
    }
}

@Composable fun BentoHeading(title: String, icon: ImageVector) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BentoIcon(icon, size = 40.dp)
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable fun BentoTile(title: String, detail: String, icon: ImageVector, background: Color, foreground: Color, modifier: Modifier = Modifier, enabled: Boolean = true, description: String? = null, compact: Boolean = false, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val corner by animateDpAsState(if (pressed) 18.dp else 30.dp, spring(dampingRatio = .8f, stiffness = 500f), label = "tile corner")
    val scale by animateFloatAsState(if (pressed) .975f else 1f, spring(dampingRatio = .8f, stiffness = 500f), label = "tile press")
    Surface(onClick = onClick, enabled = enabled, interactionSource = interactions, color = background, contentColor = foreground,
        shape = RoundedCornerShape(topStart = corner, topEnd = corner, bottomEnd = corner, bottomStart = 12.dp),
        modifier = modifier.scale(scale).then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })) {
        if (compact) Row(Modifier.heightIn(min = 76.dp).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(27.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(Icons.Outlined.NorthEast, null, Modifier.size(18.dp))
        } else Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(27.dp))
                Icon(Icons.Outlined.NorthEast, null, Modifier.size(16.dp), tint = foreground.copy(alpha = .65f))
            }
            Spacer(Modifier.height(2.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = foreground.copy(alpha = .85f))
        }
    }
}

fun modelName(state: EngineState): String = when {
    state.selectedModel.isNotBlank() -> state.models.firstOrNull { it.id == state.selectedModel }?.name ?: state.selectedModel
    state.effectiveModel != null -> state.models.firstOrNull { it.id == state.effectiveModel }?.name ?: state.effectiveModel
    else -> tr(R.string.engine_default)
}

@Composable fun WelcomeBento(state: EngineState, changeDraft: (String) -> Unit, models: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val accents = LocalBentoColors.current
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val compact = maxHeight < 300.dp
        val largeText = LocalDensity.current.fontScale >= 1.3f && maxWidth < 600.dp
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = if (compact) 12.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!compact) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.what_shall_we_make), style = MaterialTheme.typography.displaySmall)
                    }
                    if (!largeText) Surface(shape = RoundedCornerShape(topStart = 30.dp, topEnd = 48.dp, bottomEnd = 30.dp, bottomStart = 48.dp), color = colors.primaryContainer) {
                        Icon(Icons.Outlined.Code, null, Modifier.padding(22.dp).size(38.dp), tint = colors.onPrimaryContainer)
                    }
                }
            } else Text(tr(R.string.what_shall_we_make), style = MaterialTheme.typography.titleLarge)
            if (largeText) {
                BentoTile(tr(R.string.write_code), "", Icons.Outlined.Code, colors.primaryContainer, colors.onPrimaryContainer, compact = true) { changeDraft(tr(R.string.help_me_build_a_small_tool_in_the_workspace_discuss_the_idea_with)) }
                BentoTile(tr(R.string.use_phone), "", Icons.Outlined.TouchApp, colors.tertiaryContainer, colors.onTertiaryContainer, compact = true) { changeDraft(tr(R.string.look_at_the_current_phone_screen_and_tell_me_what_you_see)) }
                BentoTile(tr(R.string.organize_files), "", Icons.Outlined.FolderOpen, accents.peach, accents.onPeach, compact = true) { changeDraft(tr(R.string.look_at_arg_and_tell_me_what_files_are_here , state.project.cwd)) }
                BentoTile(if (state.browsing) tr(R.string.chat_draft) else tr(R.string.choose_model), "", Icons.Outlined.Tune, accents.lavender, accents.onLavender, enabled = !state.browsing, description = tr(R.string.model_settings), compact = true) { models() }
            } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BentoTile(tr(R.string.write_code), "", Icons.Outlined.Code, colors.primaryContainer, colors.onPrimaryContainer, Modifier.weight(1.15f)) { changeDraft(tr(R.string.help_me_build_a_small_tool_in_the_workspace_discuss_the_idea_with)) }
                BentoTile(tr(R.string.use_phone), "", Icons.Outlined.TouchApp, colors.tertiaryContainer, colors.onTertiaryContainer, Modifier.weight(1f)) { changeDraft(tr(R.string.look_at_the_current_phone_screen_and_tell_me_what_you_see)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BentoTile(tr(R.string.organize_files), "", Icons.Outlined.FolderOpen, accents.peach, accents.onPeach, Modifier.weight(1f)) { changeDraft(tr(R.string.look_at_arg_and_tell_me_what_files_are_here , state.project.cwd)) }
                BentoTile(if (state.browsing) tr(R.string.chat_draft) else tr(R.string.choose_model), "", Icons.Outlined.Tune, accents.lavender, accents.onLavender, Modifier.weight(1.15f), enabled = !state.browsing, description = tr(R.string.model_settings)) { models() }
            }
            }
        }
    }
}

/** Advanced options keep their place in navigation without taking over the first screen. */
@Composable fun ExpandablePanel(title: String, summary: String? = null, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "section expansion")
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth()) {
            Surface(onClick = { expanded = !expanded }, color = androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = (if (expanded) tr(R.string.expanded) else tr(R.string.collapsed)) + (summary?.let { " · $it" } ?: "") }) {
                Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    summary?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.rotate(turn))
                }
            }
            AnimatedVisibility(expanded) { Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp), content = content) }
        }
    }
}

@Composable fun BentoPair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 360.dp && LocalDensity.current.fontScale < 1.3f) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f)) { first() }; Box(Modifier.weight(1f)) { second() }
        } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { first(); second() }
    }
}
