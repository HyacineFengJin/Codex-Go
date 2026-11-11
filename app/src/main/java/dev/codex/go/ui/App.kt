@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.codex.go.ui

import dev.codex.go.i18n.*

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import dev.codex.go.R
import androidx.compose.ui.text.font.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.codex.go.protocol.*
import dev.codex.go.runtime.*
import dev.codex.go.files.*
import kotlinx.coroutines.launch
import java.io.File

@Composable fun CodexGoApp(engine: EngineService?, start: () -> Unit) = GoTheme {
    if (engine == null) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@GoTheme }
    val state by engine.screen.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    val draft = state.draft.text
    var modelMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var controls by rememberSaveable { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    var managing by remember { mutableStateOf<Session?>(null) }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { engine.importAttachments(it) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { engine.importAttachments(it) }
    val workspaceImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { engine.importAttachments(it, false) }
    fun previewFile(file: File) { preview = WorkspaceFiles(engine.workspace).guest(file) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val drawerList = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val configuration = LocalConfiguration.current
    val snacks = remember { SnackbarHostState() }
    val colors = MaterialTheme.colorScheme
    val conversationStates = rememberSaveableStateHolder()
    val canBrowse = (!state.busy || state.runningTask?.threadId != null) && !state.importing && !state.loadingHistory && !state.accountBusy && state.sessionMutation == null && state.phase != EnginePhase.Preparing
    var displayedProject by rememberSaveable { mutableStateOf(state.project.id) }
    LaunchedEffect(state.project.id) {
        if (displayedProject != state.project.id) {
            displayedProject = state.project.id; preview = null; managing = null; actions = false; modelMenu = false
        }
    }
    LaunchedEffect(state.error) { state.error?.let { snacks.showSnackbar(it); engine.clearError() } }
    LaunchedEffect(state.notice) { state.notice?.let { snacks.showSnackbar(it); engine.clearNotice() } }
    LaunchedEffect(state.conversationRequest) { if (state.conversationRequest > 0) { page = 0; preview = null; actions = false; managing = null; modelMenu = false; drawer.close(); engine.consumeConversationRequest() } }
    LaunchedEffect(state.browsing) { modelMenu = false; managing = null }
    LaunchedEffect(state.shareRequest) { if (state.shareRequest > 0) { page = 4; engine.consumeShareRequest() } }
    LaunchedEffect(state.quickRequest) { if (state.quickRequest > 0) { page = 6; preview = null; modelMenu = false; actions = false; managing = null; drawer.close(); engine.consumeQuickRequest() } }
    BackHandler(page != 0 || drawer.isOpen) { if (drawer.isOpen) scope.launch { drawer.close() } else page = 0 }
    fun navigate(target: Int) { focus.clearFocus(); keyboard?.hide(); page = target; scope.launch { drawer.close() } }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = page == 0, drawerContent = {
        // Material's drawer requests initial focus. Keep it on the sheet instead of the search IME.
        ModalDrawerSheet(modifier = Modifier.width(328.dp).focusable(), drawerContainerColor = colors.background) {
            val search = state.sessionQuery
            LaunchedEffect(drawer.targetValue) {
                focus.clearFocus(); keyboard?.hide()
                if (drawer.targetValue == DrawerValue.Open) engine.refreshSessions()
            }
            val matches = state.sessions
            LaunchedEffect(drawer.isOpen, matches.firstOrNull()?.id, state.threadId, search) {
                if (drawer.isOpen) {
                    val selected = if (search.isBlank()) matches.indexOfFirst { it.id == state.threadId } else -1
                    drawerList.scrollToItem(selected.coerceAtLeast(0))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(painterResource(R.drawable.codex_brand), null, Modifier.size(36.dp))
                    Text("Codex Go", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = colors.primary)
                }
                IconButton(onClick = { engine.newChat(); navigate(0) }, enabled = canBrowse) { Icon(Icons.Outlined.BorderColor, tr(R.string.new_chat), Modifier.size(21.dp)) }
            }
            OutlinedTextField(search, { engine.filterSessions(it) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, enabled = state.phase == EnginePhase.Ready && state.sessionMutation == null,
                placeholder = { Text(tr(R.string.search_project_chat_titles_and_previews), fontSize = 14.sp) }, leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(19.dp)) },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { engine.filterSessions("") }) { Icon(Icons.Outlined.Close, tr(R.string.clear_search), Modifier.size(18.dp)) } }, shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = colors.outlineVariant, focusedBorderColor = colors.outline))
            Spacer(Modifier.height(10.dp))
            AnimatedVisibility(visible = search.isBlank() && !WindowInsets.isImeVisible) {
                // Reserve room for history on short screens and when searching with the IME.
                Column(Modifier.heightIn(max = (configuration.screenHeightDp * .4f).dp).verticalScroll(rememberScrollState())) {
                    DrawerLink(Icons.Outlined.Workspaces, tr(R.string.project_arg , state.project.displayName), { navigate(5) })
                    if (!state.browsing) DrawerLink(Icons.Outlined.Tune, tr(R.string.model_reasoning), { navigate(0); modelMenu = true })
                    DrawerLink(Icons.Outlined.FolderOpen, tr(R.string.workspace), { navigate(1) })
                    DrawerLink(Icons.Outlined.Smartphone, tr(R.string.phone_control), { navigate(2) })
                    DrawerLink(Icons.Outlined.Terminal, tr(R.string.local_environment), { navigate(3) })
                    DrawerLink(Icons.Outlined.Inbox, tr(R.string.share_inbox_arg , state.shares.size), { navigate(4) })
                    DrawerLink(Icons.Outlined.ChatBubbleOutline, tr(R.string.quick_chat), { navigate(6) })
                }
            }
            Row(Modifier.padding(start = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !state.archivedSessions, onClick = { engine.filterSessions(archived = false) }, enabled = state.phase == EnginePhase.Ready && state.sessionMutation == null, label = { Text(tr(R.string.recent_chats)) })
                FilterChip(selected = state.archivedSessions, onClick = { engine.filterSessions(archived = true) }, enabled = state.phase == EnginePhase.Ready && state.sessionMutation == null, label = { Text(tr(R.string.archived)) })
            }
            if (state.sessionsLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp), color = colors.primary)
            if (search.isNotBlank()) Text(if (state.sessionsLoading) tr(R.string.searching_arg_checked , state.sessionsScanned) else tr(R.string.arg_results_arg_checked , matches.size, state.sessionsScanned), Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            LazyColumn(state = drawerList, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) {
                if (matches.isEmpty() && !state.sessionsLoading && state.sessionsError == null) item { Text(if (search.isNotBlank()) tr(R.string.no_matching_chats) else if (state.phase == EnginePhase.Ready) { if (state.archivedSessions) tr(R.string.no_archived_chats_yet) else tr(R.string.no_chats_yet) } else tr(R.string.start_codex_to_load_local_history), Modifier.padding(12.dp), fontSize = 13.sp, color = colors.onSurfaceVariant) }
                items(matches, key = Session::id) { session ->
                    Surface(onClick = { if (state.archivedSessions && !state.busy && !state.browsing) managing = session else { engine.openSession(session.id); navigate(0) } }, enabled = canBrowse,
                        shape = RoundedCornerShape(12.dp), color = if (session.id == state.threadId) colors.surfaceContainerHigh else Color.Transparent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(session.title, Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, bottom = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                            IconButton(onClick = { managing = session }, enabled = !state.browsing && !state.busy && !state.importing && !state.loadingHistory && !state.accountBusy && state.sessionMutation == null) { Icon(Icons.Outlined.MoreHoriz, tr(R.string.chat_options_arg , session.title), Modifier.size(18.dp)) }
                        }
                    }
                }
                state.sessionsError?.let { error -> item { Text(error, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = colors.error); TextButton(onClick = { engine.refreshSessions() }) { Text(tr(R.string.retry_loading_history)) } } }
                if (state.sessionsCursor != null) item { TextButton(onClick = { engine.refreshSessions(true) }, enabled = !state.sessionsLoading) { Text(tr(R.string.show_more), color = colors.onSurfaceVariant) } }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.outlineVariant)
            Row(Modifier.fillMaxWidth().clickable { navigate(3) }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = colors.onSurface) { Icon(Icons.Outlined.Code, null, Modifier.padding(8.dp).size(20.dp), tint = colors.surface) }
                Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(tr(R.string.this_phone), fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(if (state.signedIn) metadata(state.account) else tr(R.string.set_up_codex), fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Icon(Icons.Outlined.Settings, tr(R.string.settings), Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            }
        }
    }) {
        Scaffold(containerColor = colors.background, snackbarHost = { SnackbarHost(snacks) }, topBar = {
            GoTopBar(page, state, { if (page == 0) scope.launch { drawer.open() } else page = 0 }, { modelMenu = true }) {
                if (page == 0) {
                    FilledTonalIconButton(onClick = { engine.newChat() }, enabled = canBrowse) { Icon(Icons.Outlined.BorderColor, tr(R.string.new_chat), Modifier.size(21.dp)) }
                    IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); controls = true }) { Icon(Icons.Outlined.Bolt, tr(R.string.quick_controls), Modifier.size(22.dp)) }
                    Box {
                        IconButton(onClick = { moreMenu = true }) { Icon(Icons.Outlined.MoreHoriz, tr(R.string.more), Modifier.size(22.dp)) }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            state.threadId?.let { id -> DropdownMenuItem(text = { Text(tr(R.string.manage_this_chat)) }, enabled = !state.browsing && !state.busy && !state.importing && !state.loadingHistory && state.sessionMutation == null, onClick = { moreMenu = false; managing = Session(id, state.title, "", 0) }) }
                            listOf(tr(R.string.projects) to 5, tr(R.string.workspace) to 1, tr(R.string.phone_control) to 2, tr(R.string.quick_chat) to 6, tr(R.string.settings) to 3, tr(R.string.share_inbox_arg , state.shares.size) to 4).forEach { (label, target) -> DropdownMenuItem(text = { Text(label) }, onClick = { page = target; moreMenu = false }) }
                        }
                    }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                if (state.browsing || page != 0) TaskStrip(state, engine)
                Box(Modifier.weight(1f)) { when (page) {
                    0 -> Conversation(state, draft, engine::updateDraft, { engine.submit() }, engine::interrupt, { page = 3 }, { actions = true }, conversationStates, engine.workspace, engine::removeAttachment, ::previewFile, engine, { modelMenu = true }, start) { focus.clearFocus(); keyboard?.hide(); controls = true }
                    1 -> key(state.project.id) { WorkspaceScreen(engine.workspace, state.workspaceRevision, { workspaceImport.launch(arrayOf("*/*")) }, { file -> engine.referenceFile(file); page = 0 }) }
                    2 -> DeviceScreen(state, engine::setPhoneEnabled, engine::setShellEnabled, engine::startPairing, engine::reconnectWireless, engine::connectWirelessPort, engine::observePhone, engine::selectShellBackend, engine::connectShellBackend, engine::testAndroidShell, engine::enablePairingOverlay, engine::cancelPairing, engine::setPhonePreRead, engine::previewPhoneReading, engine::setPhoneModelPreRead, engine::selectPreReadModel) { text -> engine.updateDraft(text); page = 0 }
                    3 -> SettingsScreen(state, start, engine) { page = 6 }
                    4 -> ShareInboxScreen(state, engine) { page = 0 }
                    5 -> ProjectsScreen(state, engine) { page = 0 }
                    6 -> QuickChatScreen(state, engine, start) { page = 0 }
                } }
            }
        }
    }
    if (controls) QuickControls(state, engine, start,
        device = { controls = false; navigate(2) }, settings = { controls = false; navigate(3) }, close = { controls = false })
    if (actions) ModalBottomSheet(onDismissRequest = { actions = false }, containerColor = colors.surface) {
        Column(Modifier.padding(bottom = 26.dp)) {
            DrawerLink(Icons.Outlined.Image, tr(R.string.add_image), { actions = false; photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, tr(R.string.preview_or_remove_before_sending))
            DrawerLink(Icons.Outlined.AttachFile, tr(R.string.add_file), { actions = false; documents.launch(arrayOf("*/*")) }, tr(R.string.up_to_8_attachments_64_mb_each))
            DrawerLink(Icons.Outlined.FolderOpen, tr(R.string.workspace_files), { actions = false; page = 1 }, tr(R.string.view_and_edit_files_created_by_codex))
            DrawerLink(Icons.Outlined.TouchApp, tr(R.string.phone_control), { actions = false; page = 2 }, tr(R.string.accessibility_wireless_debugging))
            DrawerLink(Icons.Outlined.Terminal, tr(R.string.local_codex), { actions = false; page = 3 }, metadata(state.status))
        }
    }
    if (modelMenu) ModelPicker(state, engine, start) { modelMenu = false }
    state.login?.let { DeviceLoginDialog(it, engine::cancelLogin) }
    managing?.let { session -> SessionActions(session, state.archivedSessions && state.threadId != session.id, { managing = null }, engine::renameSession, engine::archiveSession) { id -> engine.restoreSession(id); managing = null; navigate(0) } }
    preview?.let { path -> WorkspaceFiles(engine.workspace).resolve(path)?.let { FilePreview(it, engine.workspace, { preview = null }, engine::referenceFile) } }
    state.prompts.firstOrNull()?.let { prompt -> key(prompt.id.toString(), prompt.method) { PromptDialog(prompt, state.feed.firstOrNull { it.id == prompt.params.optString("itemId") }) { engine.answer(prompt, it) } } }
}

@Composable private fun GoTopBar(page: Int, state: EngineState, navigation: () -> Unit, models: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = navigation, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = colors.surfaceContainerLowest)) { Icon(if (page == 0) Icons.Outlined.Menu else Icons.AutoMirrored.Outlined.ArrowBack, if (page == 0) tr(R.string.open_sidebar) else tr(R.string.back_to_chat), Modifier.size(22.dp)) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(when (page) { 1 -> tr(R.string.workspace); 2 -> tr(R.string.phone_control); 3 -> tr(R.string.local_environment); 4 -> tr(R.string.share_inbox); 5 -> tr(R.string.projects); 6 -> tr(R.string.quick_chat); else -> if (state.threadId == null) "Codex Go" else state.title }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                if (page == 0 && state.project.id != dev.codex.go.files.Project.DEFAULT_ID) Text(state.project.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            actions()
        }
        if (page == 0) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = models, enabled = !state.browsing, shape = RoundedCornerShape(18.dp), color = LocalBentoColors.current.lavender, contentColor = LocalBentoColors.current.onLavender, modifier = Modifier.weight(1f).semantics { contentDescription = tr(R.string.choose_model) }) {
                Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(17.dp), tint = colors.primary)
                    Text(if (state.browsing) tr(R.string.browsing_chat) else modelName(state), Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = LocalBentoColors.current.onLavender)
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = colors.primary)
                }
            }
            if (!state.browsing) Surface(onClick = models, color = colors.surfaceContainerLowest, shape = CircleShape, modifier = Modifier.semantics { contentDescription = tr(R.string.choose_reasoning_effort) }) {
                val effort = state.models.firstOrNull { it.id == state.selectedModel }?.effort(state.selectedEffort)
                Text(tr(R.string.effort_arg , effort?.let(::effortLabel) ?: tr(R.string.default_label)), Modifier.padding(horizontal = 14.dp, vertical = 12.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun TaskStrip(state: EngineState, engine: EngineService) {
    val task = state.runningTask
    if (task == null && !state.browsing) return
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val status = when {
                    task == null -> tr(R.string.browsing_another_chat)
                    task.waiting > 0 -> tr(R.string.waiting_for_approval_arg_pending , task.waiting)
                    task.phonePaused -> tr(R.string.phone_control_paused)
                    task.busy -> tr(R.string.task_running)
                    task.outcome == TaskOutcome.Failed -> tr(R.string.task_failed)
                    task.outcome == TaskOutcome.Stopped -> tr(R.string.task_stopped)
                    task.outcome == TaskOutcome.Unconfirmed -> tr(R.string.task_needs_review)
                    else -> tr(R.string.task_complete)
                }
                Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                task?.let { Text("${it.project.displayName} · ${it.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            TextButton(onClick = { engine.returnToTask(task?.token) }) { Text(if (task == null) tr(R.string.back_to_original_chat) else tr(R.string.back_to_task)) }
        }
    }
}

@Composable private fun DrawerLink(icon: ImageVector, label: String, click: () -> Unit, detail: String? = null) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = click, shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = if (detail == null) 7.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BentoIcon(icon, size = if (detail == null) 34.dp else 40.dp)
            Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium); detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) } }
            if (detail == null) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
        }
    }
}
private data class FeedGroup(val id: String, val messages: List<ChatItem>, val tools: Boolean = false)
private fun groups(feed: List<ChatItem>): List<FeedGroup> {
    val result = mutableListOf<FeedGroup>()
    feed.forEach { item ->
        if (item.kind == "tool" && result.lastOrNull()?.tools == true) { val previous = result.removeAt(result.lastIndex); result += previous.copy(messages = previous.messages + item) }
        else result += FeedGroup(item.id, listOf(item), item.kind == "tool")
    }
    return result
}
@Composable private fun ConversationFeed(state: EngineState, root: File, preview: (File) -> Unit, changeDraft: (String) -> Unit, engine: EngineService, models: () -> Unit, modifier: Modifier) {
    val list = rememberLazyListState()
    var followReplies by rememberSaveable { mutableStateOf(true) }
    val setFollowReplies: (Boolean) -> Unit = { followReplies = it }
    val colors = MaterialTheme.colorScheme
    val feed = remember(state.feed) { groups(state.feed) }
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    var automaticScroll by remember { mutableStateOf(false) }
    val dragging by list.interactionSource.collectIsDraggedAsState()
    val viewport by remember(list) { derivedStateOf { list.layoutInfo.viewportEndOffset } }
    val measuredCount by remember(list) { derivedStateOf { list.layoutInfo.totalItemsCount } }
    val moreBelow by remember(list) { derivedStateOf { list.canScrollForward } }
    val scope = rememberCoroutineScope()
    val changeFollow by rememberUpdatedState(setFollowReplies)
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.roundToPx() }
    suspend fun showLatest() {
        if (feed.isEmpty()) return
        automaticScroll = true
        try {
            list.scrollToItem(feed.lastIndex)
            val height = list.layoutInfo.visibleItemsInfo.lastOrNull { it.index == feed.lastIndex }?.size ?: 0
            list.scrollBy(height.toFloat())
        } finally { automaticScroll = false }
    }
    LaunchedEffect(list) {
        var manualScroll = false
        snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            listOf(list.isScrollInProgress, dragging, automaticScroll,
                last != null && last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + margin)
        }.collect { (scrolling, dragged, automatic, nearBottom) ->
            // IME relocation and bring-into-view scrolling do not express reader intent.
            if (dragged && !automatic) { manualScroll = true; changeFollow(false) }
            if (manualScroll && !scrolling && !dragged) { manualScroll = false; changeFollow(nearBottom) }
        }
    }
    LaunchedEffect(feed.size, state.feed.lastOrNull()?.text, followReplies, viewport, measuredCount, moreBelow, dragging, state.workspaceRevision) {
        if (followReplies && !dragging) {
            // New items and asynchronous file cards can be measured after the feed update.
            withFrameNanos { }
            showLatest()
        }
    }
        if (state.loadingHistory) Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
        else if (feed.isEmpty()) WelcomeBento(state, changeDraft, models, modifier)
        else Box(modifier.fillMaxWidth()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = if (compact) 8.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(feed, key = FeedGroup::id) { group ->
                    if (group.tools) Column { WorkSummary(group.messages); group.messages.filter { !it.running }.forEach { Deliverables(it, root, state.workspaceRevision, preview) } }
                    else Message(group.messages.single(), root, state.workspaceRevision, preview, engine::editMessage)
                }
                if (!state.browsing && state.busy && state.feed.lastOrNull()?.running != true) item { Text(tr(R.string.thinking), fontSize = 13.sp, color = colors.onSurfaceVariant) }
            }
            if (!followReplies) SmallFloatingActionButton(onClick = { setFollowReplies(true); scope.launch { showLatest() } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp), shape = CircleShape, containerColor = colors.surfaceContainer) {
                Icon(Icons.Outlined.ArrowDownward, tr(R.string.jump_to_latest_reply), Modifier.size(19.dp))
            }
        }

}
@Composable private fun Conversation(state: EngineState, draft: String, changeDraft: (String) -> Unit, send: () -> Unit, stop: () -> Unit, settings: () -> Unit, actions: () -> Unit, conversationStates: androidx.compose.runtime.saveable.SaveableStateHolder, root: File, removeAttachment: (String) -> Unit, preview: (File) -> Unit, engine: EngineService, models: () -> Unit, start: () -> Unit, controls: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    var sendModes by remember(state.project.id, state.threadId) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        conversationStates.SaveableStateProvider(state.project.slot(state.threadId)) {
            ConversationFeed(state, root, preview, changeDraft, engine, models, Modifier.weight(1f))
        }
        if (!state.browsing && state.busy) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text("  ${state.feed.lastOrNull { it.kind == "tool" && it.running }?.title ?: tr(R.string.codex_is_working)}", Modifier.weight(1f), fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = stop) { Text(tr(R.string.stop), fontSize = 12.sp) }
        }
        QueuePanel(state, engine::withdraw, engine::continueQueue)
        if (state.phoneTaskActive) PhoneTaskPanel(state, engine::pausePhone, engine::resumePhone, engine::interrupt)
        if (!state.browsing) RecoveryPanel(state, engine, settings, models)
        if (!state.browsing && state.failedSubmission != null && state.failure == null && !state.busy) TextButton(onClick = engine::retrySubmission, Modifier.align(Alignment.CenterHorizontally)) { Text(tr(R.string.retry_last_message)) }
        if (state.phase != EnginePhase.Ready) FilledTonalButton(onClick = start, enabled = state.phase != EnginePhase.Preparing, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Terminal, null, Modifier.size(15.dp)); Text(if (state.phase == EnginePhase.Preparing) tr(R.string.preparing_local_codex) else tr(R.string.start_local_codex_66eecc), fontSize = 12.sp)
        }
        if (state.browsing) Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!compact) Text(if (state.busy) tr(R.string.a_task_is_running_you_can_prepare_a_draft) else tr(R.string.enter_this_chat_to_send), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (!state.busy) OutlinedButton(onClick = engine::activateReading, enabled = !state.busy && !state.loadingHistory && !state.importing && state.phase == EnginePhase.Ready && !state.accountBusy) { Text(tr(R.string.enter_this_chat)) }
        }
        if (state.importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        AttachmentStrip(state.draft.attachments, root, removeAttachment, preview)
        if (!state.browsing && state.busy && !state.draft.empty) Box(Modifier.padding(horizontal = 20.dp)) {
            TextButton(onClick = { sendModes = true }, enabled = !state.steering && !state.importing) { Text(if (state.steering) tr(R.string.steering) else tr(R.string.send_options), fontSize = 12.sp) }
            DropdownMenu(expanded = sendModes, onDismissRequest = { sendModes = false }) {
                listOf(tr(R.string.steer_without_interrupting) to SendMode.Steer, tr(R.string.queue_after_this_turn) to SendMode.Queue, tr(R.string.interrupt_send) to SendMode.Interrupt).forEach { (label, mode) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { sendModes = false; engine.submit(mode) })
                }
            }
        }
        Surface(shape = RoundedCornerShape(32.dp), color = colors.surfaceContainerLowest, shadowElevation = 1.dp, border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .6f)), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.Bottom) {
                FilledTonalIconButton(onClick = actions, Modifier.size(44.dp)) { Icon(Icons.Outlined.Add, tr(R.string.attachments_tools), Modifier.size(23.dp)) }
                Box(Modifier.weight(1f).heightIn(min = 42.dp).padding(vertical = 11.dp, horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(draft, changeDraft, Modifier.fillMaxWidth(), maxLines = 6, textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, fontSize = 15.sp), cursorBrush = SolidColor(colors.onSurface),
                        decorationBox = { inner -> if (draft.isEmpty()) Text(if (state.browsing) tr(R.string.draft_for_this_chat) else tr(R.string.message), fontSize = 15.sp, color = colors.onSurfaceVariant); inner() })
                }
                val stopping = !state.browsing && state.busy && state.draft.empty
                FilledIconButton(onClick = { if (stopping) stop() else send() }, Modifier.size(46.dp), enabled = !state.browsing && (stopping || (!state.draft.empty && state.phase == EnginePhase.Ready && !state.loadingHistory && !state.importing && !state.steering))) {
                    Icon(if (stopping) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward, if (stopping) tr(R.string.stop_task) else if (!state.browsing && state.busy) tr(R.string.steer) else tr(R.string.send_message), Modifier.size(22.dp))
                }
            }
        }
        Surface(onClick = controls, shape = CircleShape, color = colors.surfaceContainerLow, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp).semantics { contentDescription = tr(R.string.open_quick_controls) }) {
            Row(Modifier.heightIn(min = 34.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Bolt, null, Modifier.size(14.dp), tint = colors.primary)
                Text(when { state.phase == EnginePhase.Preparing -> tr(R.string.preparing); state.phoneEnabled && state.shellEnabled -> tr(R.string.phone_shell); state.phoneEnabled -> tr(R.string.phone_connected); state.phase == EnginePhase.Ready -> tr(R.string.ready); else -> tr(R.string.connect) }, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp))
            }
        }
    }
}
@Composable private fun Starter(icon: ImageVector, title: String, click: () -> Unit) {
    OutlinedButton(onClick = click, contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp), border = BorderStroke(.7.dp, MaterialTheme.colorScheme.outlineVariant), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
        Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Text(title, Modifier.padding(start = 6.dp), fontSize = 12.sp)
    }
}
@Composable private fun WorkSummary(messages: List<ChatItem>) {
    var expanded by rememberSaveable(messages.first().id) { mutableStateOf(false) }
    val running = messages.any { it.running }
    val failed = messages.any { it.failed }
    Column {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (running) Icons.Outlined.Timelapse else if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircleOutline, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (running) tr(R.string.running_arg_actions , messages.size) else tr(R.string.finished_arg_actionsarg , messages.size, if (failed) tr(R.string.has_failures) else ""), Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                messages.forEach { item -> Column { Text(metadata(item.title), fontSize = 12.sp, fontWeight = FontWeight.Medium); Text(item.text, Modifier.padding(top = 6.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
        }
    }
}
@Composable private fun Message(item: ChatItem, root: File, revision: Int, preview: (File) -> Unit, edit: (ChatItem) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    when (item.kind) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(horizontalAlignment = Alignment.End) {
                Surface(shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomEnd = 8.dp, bottomStart = 26.dp), color = colors.secondaryContainer, modifier = Modifier.widthIn(max = 330.dp)) { Column(Modifier.padding(horizontal = 17.dp, vertical = 12.dp)) { Markdown(item.text.substringBefore(tr(R.string.attached_workspace_files))) { WorkspaceFiles(root).resolve(it)?.let(preview) }; Deliverables(item, root, revision, preview) } }
                TextButton(onClick = { edit(item) }) { Text(tr(R.string.edit_resend), fontSize = 11.sp, color = colors.onSurfaceVariant) }
            }
        }
        "error" -> Text(item.text, color = colors.error, style = MaterialTheme.typography.bodyMedium)
        "notice" -> Text(item.text, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        else -> Column {
            Markdown(item.text) { WorkspaceFiles(root).resolve(it)?.let(preview) }
            Deliverables(item, root, revision, preview)
            if (!item.running) IconButton(onClick = { copy(context, item.text) }, Modifier.padding(top = 8.dp).size(30.dp)) { Icon(Icons.Outlined.ContentCopy, tr(R.string.copy_reply), Modifier.size(16.dp), tint = colors.onSurfaceVariant) }
        }
    }
}
