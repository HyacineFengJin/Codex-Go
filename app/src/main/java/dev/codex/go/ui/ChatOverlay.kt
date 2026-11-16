package dev.codex.go.ui

import dev.codex.go.i18n.*
import android.app.Dialog
import android.app.KeyguardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.PowerManager
import android.provider.Settings
import android.text.TextWatcher
import android.text.Editable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.graphics.drawable.RippleDrawable
import dev.codex.go.R
import dev.codex.go.runtime.*
import kotlin.math.abs

/** A user-enabled application overlay, independent of accessibility and wireless debugging. */
class ChatOverlay(private val engine: EngineService) {
    private val manager = engine.getSystemService(WindowManager::class.java)
    private val preferences get() = engine.getSharedPreferences(if (engine.engineKind == AgentEngine.Codex) "codex_go" else "antigravity_overlay", 0)
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var panel: Dialog? = null
    private var glass: GlassWindow? = null
    private var expanded = false
    private var compactPanel = false
    private var appVisible = false
    private var target: QuickTarget? = null
    private var destination = ""
    private var output: LinearLayout? = null
    private var feedKey: String? = null
    private val messages = mutableMapOf<String, TextView>()
    private var model: TextView? = null
    private val style get() = OverlayStyle(engine)
    private var feedScroll: ScrollView? = null
    private var status: TextView? = null
    private var input: EditText? = null
    private var send: ImageButton? = null
    private var hidden = 0
    private var x = preferences.getInt("chatBubbleX", -1)
    private var y = preferences.getInt("chatBubbleY", 240)
    private val bubbleSize = 56
    private fun dp(value: Int) = (value * engine.resources.displayMetrics.density).toInt()
    private fun lp(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)
    private fun ready() = engine.getSystemService(PowerManager::class.java).isInteractive && !engine.getSystemService(KeyguardManager::class.java).isDeviceLocked
    private fun icon(kind: OverlayGlyph.Kind, description: String, primary: Boolean = false, click: () -> Unit) = ImageButton(engine).apply {
        contentDescription = description; scaleType = ImageView.ScaleType.CENTER_INSIDE
        setImageDrawable(OverlayGlyph(kind, if (primary) if (style.dark) Color.rgb(0, 56, 38) else Color.WHITE else style.ink))
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = RippleDrawable(ColorStateList.valueOf(style.outline), style.shape(if (primary) style.accent else Color.TRANSPARENT, 22), null)
        setOnClickListener { click() }
    }
    fun update(state: EngineState, appVisible: Boolean) {
        this.appVisible = appVisible
        if (!state.chatOverlayEnabled || state.phase != EnginePhase.Ready || appVisible || !ready() || !Settings.canDrawOverlays(engine)) { close(); return }
        if (root == null) showBubble()
        root?.visibility = if (hidden > 0) View.INVISIBLE else View.VISIBLE
        if (!expanded) return
        val same = target?.matches(engine.quickTarget()) == true
        val items = state.feed.filter { it.kind in setOf("user", "assistant") }.takeLast(8)
        val key = if (same) items.joinToString("|") { it.id + it.kind } else "changed:$destination"
        val follow = feedScroll?.canScrollVertically(1) != true
        if (key != feedKey) {
            output?.removeAllViews(); messages.clear(); feedKey = key
            if (!same || items.isEmpty()) output?.addView(style.text(if (same) tr(R.string.what_would_you_like_to_do_continue_your_chat_here) else tr(R.string.the_chat_changed_this_input_still_belongs_to_arg_collapse_and_reo, destination), 14f, secondary = true).apply { setPadding(dp(4), dp(16), dp(4), dp(16)) })
            else items.forEach { item ->
                val user = item.kind == "user"
                val body = style.text("", 15f).apply {
                    setTextIsSelectable(true)
                    if (user) { setPadding(dp(14), dp(10), dp(14), dp(10)); background = style.shape(if (style.dark) Color.rgb(37, 56, 47) else Color.rgb(225, 239, 230), 20) }
                    else setPadding(dp(2), dp(8), dp(2), dp(8))
                }
                messages[item.id] = body
                output?.addView(body, lp(-1, -2).apply { bottomMargin = dp(12); if (user) marginStart = dp(28) else marginEnd = dp(4) })
            }
        }
        if (same) items.forEach { item ->
            val content = (if (item.text.length > 6000) "…\n" else "") + item.text.takeLast(6000)
            messages[item.id]?.let { if (it.text.toString() != content) it.text = content }
        }
        if (follow) feedScroll?.post { feedScroll?.fullScroll(View.FOCUS_DOWN) }
        model?.text = modelName(state) + (state.selectedEffort?.let { " · ${effortLabel(it)}" } ?: "")
        send?.contentDescription = if (!same) tr(R.string.save_for_review) else if (state.busy) tr(R.string.queue_send) else tr(R.string.send)
        val feedback = when {
            !same -> tr(R.string.target_changed_input_will_not_go_to_the_new_chat)
            state.prompts.isNotEmpty() -> tr(R.string.waiting_for_approval_open_the_app)
            state.busy -> tr(R.string.task_running_new_input_will_be_queued)
            state.quickMessages.isNotEmpty() -> tr(R.string.pending_messages_open_the_app_to_review)
            else -> ""
        }
        status?.apply { text = feedback; visibility = if (feedback.isBlank() || compactPanel) View.GONE else View.VISIBLE }
        updateSend()
    }
    private fun updateSend() { send?.apply { isEnabled = input?.text?.isNotBlank() == true; alpha = if (isEnabled) 1f else .35f } }
    private fun safeBounds(): android.graphics.Rect {
        val metrics = manager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return android.graphics.Rect(metrics.bounds.left + insets.left, metrics.bounds.top + insets.top, metrics.bounds.right - insets.right, metrics.bounds.bottom - insets.bottom)
    }
    private fun showBubble() {
        remove(); expanded = false
        val bounds = safeBounds()
        val bubble = ImageButton(engine).apply {
            contentDescription = tr(R.string.open_floating_chat); setImageResource(R.drawable.ic_codex_mark)
            imageTintList = ColorStateList.valueOf(style.ink); scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GlassSurface(style.dark, dp(28).toFloat()).apply { fillOpacity = 246 }
            elevation = dp(3).toFloat(); setOnClickListener { showPanel() }
        }
        val p = WindowManager.LayoutParams(dp(bubbleSize), dp(bubbleSize), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; title = tr(R.string.codex_go_floating_chat); setFitInsetsTypes(0)
            x = this@ChatOverlay.x.takeIf { it >= 0 }?.coerceIn(bounds.left, (bounds.right - dp(bubbleSize)).coerceAtLeast(bounds.left)) ?: (bounds.right - dp(bubbleSize + 12)).coerceAtLeast(bounds.left)
            y = dp(this@ChatOverlay.y).coerceIn(bounds.top, (bounds.bottom - dp(bubbleSize)).coerceAtLeast(bounds.top))
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        val slop = ViewConfiguration.get(engine).scaledTouchSlop
        bubble.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = p.x; startY = p.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    moved = moved || abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop
                    if (moved) {
                        val safe = safeBounds()
                        p.x = (startX + event.rawX - downX).toInt().coerceIn(safe.left, (safe.right - dp(bubbleSize)).coerceAtLeast(safe.left))
                        p.y = (startY + event.rawY - downY).toInt().coerceIn(safe.top, (safe.bottom - dp(bubbleSize)).coerceAtLeast(safe.top))
                        runCatching { manager.updateViewLayout(view, p) }
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick() else {
                        val safe = safeBounds()
                        p.x = if (p.x + dp(bubbleSize / 2) < safe.centerX()) safe.left + dp(8) else (safe.right - dp(bubbleSize + 8)).coerceAtLeast(safe.left)
                        runCatching { manager.updateViewLayout(view, p) }; x = p.x; y = (p.y / engine.resources.displayMetrics.density).toInt()
                        preferences.edit().putInt("chatBubbleX", x).putInt("chatBubbleY", y).apply()
                    }; true
                }
                MotionEvent.ACTION_CANCEL -> { moved = false; true }
                else -> false
            }
        }
        runCatching { manager.addView(bubble, p); root = bubble; params = p }
            .onFailure { runCatching { manager.removeViewImmediate(bubble) }; engine.overlayUnavailable() }
    }
    private fun showPanel(retarget: Boolean = true) {
        remove()
        if (retarget) { target = engine.quickTarget(); destination = "${engine.state.value.project.displayName} · ${engine.state.value.displayTitle}" }
        val colors = style
        val card = object : LinearLayout(engine) {
            var footer: View? = null
            var subtitle: View? = null
            var header: View? = null
            var inlineControls: View? = null
            var imeBottom = 0
            fun fitChrome(contentHeight: Int = height) {
                if (contentHeight <= 0) return
                val available = contentHeight - imeBottom
                val compact = available < dp(300)
                compactPanel = available < dp(180)
                header?.visibility = if (compactPanel) View.GONE else View.VISIBLE
                inlineControls?.visibility = if (compactPanel) View.VISIBLE else View.GONE
                footer?.visibility = if (compact) View.GONE else View.VISIBLE
                subtitle?.visibility = if (available < dp(220)) View.GONE else View.VISIBLE
                feedScroll?.visibility = if (available < dp(220)) View.GONE else View.VISIBLE
                input?.maxLines = if (compact) 1 else 3
                status?.maxLines = if (compact) 1 else Int.MAX_VALUE
                status?.visibility = if (compactPanel || status?.text.isNullOrBlank()) View.GONE else View.VISIBLE
                setPadding(dp(12), dp(if (compactPanel) 6 else 12), dp(12), dp(if (compactPanel) 6 else 10) + imeBottom)
            }
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                fitChrome(h)
            }
        }.apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(10)); isFocusableInTouchMode = true }
        card.setOnApplyWindowInsetsListener { _, insets ->
            card.imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
            card.fitChrome()
            insets
        }
        val header = LinearLayout(engine).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, 0, dp(10)) }
        header.addView(ImageView(engine).apply { setImageResource(R.drawable.ic_codex_mark); imageTintList = ColorStateList.valueOf(colors.ink) }, lp(dp(27), dp(27)).apply { marginEnd = dp(10) })
        header.addView(LinearLayout(engine).apply {
            orientation = LinearLayout.VERTICAL
            addView(colors.text(engine.engineKind.label, 18f, bold = true))
            addView(colors.text(destination, 11f, secondary = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0); card.subtitle = this })
        }, lp(0, -2).apply { weight = 1f })
        header.addView(icon(OverlayGlyph.Kind.Collapse, tr(R.string.collapse)) { showBubble() }, lp(dp(44), dp(44)))
        header.addView(icon(OverlayGlyph.Kind.Close, tr(R.string.close)) { engine.setChatOverlayEnabled(false) }, lp(dp(44), dp(44)))
        card.header = header; card.addView(header)
        output = LinearLayout(engine).apply { orientation = LinearLayout.VERTICAL; contentDescription = tr(R.string.floating_chat_content); setPadding(dp(14), dp(8), dp(14), dp(8)) }
        feedScroll = ScrollView(engine).apply {
            addView(output); isVerticalScrollBarEnabled = false; clipToPadding = false
            background = colors.shape(if (colors.dark) Color.rgb(19, 25, 21) else Color.rgb(251, 253, 250), 22)
            clipToOutline = true; isFillViewport = true
        }
        card.addView(feedScroll, lp(-1, 0).apply { weight = 1f })
        status = colors.text("", 11f, secondary = true).apply { visibility = View.GONE; ellipsize = android.text.TextUtils.TruncateAt.END }
        card.addView(status, lp(-1, -2).apply { topMargin = dp(8); marginStart = dp(8); marginEnd = dp(8) })
        val composer = LinearLayout(engine).apply {
            gravity = Gravity.BOTTOM; setPadding(dp(4), dp(4), dp(4), dp(4))
            background = colors.shape(colors.inner, 26)
        }
        input = EditText(engine).apply {
            hint = tr(R.string.agent_message_arg, engine.engineKind.label); contentDescription = tr(R.string.floating_message_input); setTextColor(colors.ink); setHintTextColor(colors.muted)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            minLines = 1; maxLines = 3; setText(preferences.getString("chatOverlayDraft", ""))
            textSize = 16f; setPadding(dp(12), dp(10), dp(8), dp(10)); background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { preferences.edit().putString("chatOverlayDraft", s?.toString().orEmpty()).apply(); updateSend() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }; composer.addView(input, lp(0, -2).apply { weight = 1f })
        val inlineControls = LinearLayout(engine).apply {
            visibility = View.GONE
            addView(icon(OverlayGlyph.Kind.Collapse, tr(R.string.collapse)) { showBubble() }, lp(dp(44), dp(44)))
            addView(icon(OverlayGlyph.Kind.Close, tr(R.string.close)) { engine.setChatOverlayEnabled(false) }, lp(dp(44), dp(44)))
        }
        card.inlineControls = inlineControls; composer.addView(inlineControls, lp(dp(88), dp(44)))
        send = icon(OverlayGlyph.Kind.Send, tr(R.string.send), primary = true) {
            val value = input?.text?.toString().orEmpty()
            val result = engine.submitQuickText(value, target, "悬浮窗", destinationHint = destination)
            if (result.consumed) input?.setText("")
            status?.apply { text = result.message; visibility = if (text.isBlank() || compactPanel) View.GONE else View.VISIBLE }
        }; composer.addView(send, lp(dp(44), dp(44)))
        card.addView(composer, lp(-1, -2).apply { topMargin = dp(10) })
        val footer = LinearLayout(engine).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), 0, 0) }
        model = colors.text("", 11f, secondary = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        footer.addView(model, lp(0, -2).apply { weight = 1f })
        footer.addView(colors.action(tr(R.string.open_app)) { engine.openQuickChatApp() }.apply { background = null; setTextColor(colors.accent); setPadding(dp(10), 0, dp(6), 0) }, lp(-2, dp(40)))
        card.footer = footer; card.addView(footer)
        val safe = safeBounds(); val margin = dp(12)
        val width = minOf(dp(380), safe.width() - margin * 2).coerceAtLeast(1)
        val availableHeight = (safe.height() - margin * 2).coerceAtLeast(1)
        val height = if (safe.height() < dp(480)) availableHeight else minOf(dp(560), (safe.height() * .64f).toInt())
        val candidate = Dialog(engine, R.style.FloatingChatTheme).apply { setCanceledOnTouchOutside(false); setContentView(card) }
        val window = candidate.window ?: return
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply { gravity = Gravity.TOP or Gravity.LEFT; x = safe.right - width - margin; y = safe.top + margin; title = tr(R.string.codex_go_floating_chat); setFitInsetsTypes(0); windowAnimations = 0 }
        val material = GlassWindow(engine, window, colors.dark, dp(28).toFloat())
        candidate.setOnDismissListener {
            if (panel === candidate) { material.close(); glass = null; panel = null; root = null; params = null; expanded = false; update(engine.state.value, appVisible) }
        }
        runCatching {
            candidate.show(); window.setLayout(width, height); panel = candidate; glass = material
            root = window.decorView; params = window.attributes; expanded = true
            update(engine.state.value, appVisible); card.requestFocus()
            card.post { card.fitChrome() }
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) { card.alpha = 0f; card.translationY = dp(8).toFloat(); card.animate().alpha(1f).translationY(0f).setDuration(160).start() }
        }.onFailure { material.close(); candidate.setOnDismissListener(null); runCatching { candidate.dismiss() }; engine.overlayUnavailable() }
    }
    /** Configuration changes preserve the original destination and the unsubmitted draft. */
    fun refreshPresentation(state: EngineState, appVisible: Boolean) {
        val reopen = expanded
        remove(); expanded = false; update(state, appVisible)
        if (reopen && root != null) showPanel(retarget = false)
    }
    fun hideForTool() { hidden++; input?.clearFocus(); root?.windowToken?.let { engine.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it, 0) }; root?.visibility = View.INVISIBLE }
    fun restoreAfterTool() { hidden = (hidden - 1).coerceAtLeast(0); if (hidden == 0) root?.visibility = View.VISIBLE }
    private fun remove() {
        val oldRoot = root; val oldPanel = panel
        panel = null; root = null; params = null
        oldRoot?.let { engine.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        glass?.close(); glass = null
        if (oldPanel != null) { oldPanel.setOnDismissListener(null); runCatching { oldPanel.dismiss() } }
        else if (oldRoot != null) runCatching { manager.removeViewImmediate(oldRoot) }
        input = null; output = null; feedKey = null; messages.clear(); feedScroll = null; status = null; send = null; model = null
        compactPanel = false
    }
    fun close() { remove(); expanded = false }
}
