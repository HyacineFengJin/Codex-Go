package dev.codex.go.ui

import dev.codex.go.i18n.*

import android.app.KeyguardManager
import android.graphics.PixelFormat
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.TextWatcher
import android.text.Editable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import dev.codex.go.R
import dev.codex.go.runtime.*
import kotlin.math.abs

/** A user-enabled application overlay, independent of accessibility and wireless debugging. */
class ChatOverlay(private val engine: EngineService) {
    private val manager = engine.getSystemService(WindowManager::class.java)
    private val preferences = engine.getSharedPreferences("codex_go", 0)
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = false
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
    private var send: Button? = null
    private var hidden = 0
    private var x = preferences.getInt("chatBubbleX", -1)
    private var y = preferences.getInt("chatBubbleY", 240)
    private fun dp(value: Int) = (value * engine.resources.displayMetrics.density).toInt()
    private val ink get() = style.ink
    private fun action(label: String, click: () -> Unit) = style.action(label, click = click)
    fun update(state: EngineState, appVisible: Boolean) {
        val awake = engine.getSystemService(PowerManager::class.java).isInteractive && !engine.getSystemService(KeyguardManager::class.java).isDeviceLocked
        if (!state.chatOverlayEnabled || state.phase != EnginePhase.Ready || appVisible || !awake || !Settings.canDrawOverlays(engine)) { close(); return }
        if (root == null) showBubble()
        root?.visibility = if (hidden > 0) View.INVISIBLE else View.VISIBLE
        if (!expanded) return
        val same = target?.matches(engine.quickTarget()) == true
        val items = state.feed.filter { it.kind in setOf("user", "assistant") }.takeLast(8)
        val key = if (same) items.joinToString("|") { it.id + it.kind } else "changed:$destination"
        val follow = feedScroll?.canScrollVertically(1) != true
        if (key != feedKey) {
            output?.removeAllViews(); messages.clear(); feedKey = key
            if (!same || items.isEmpty()) output?.addView(style.text(if (same) tr(R.string.what_would_you_like_to_do_continue_your_chat_here) else tr(R.string.the_chat_changed_this_input_still_belongs_to_arg_collapse_and_reo , destination), 14f, secondary = true).apply { setPadding(dp(8), dp(24), dp(8), dp(24)) })
            else items.forEach { item ->
                val user = item.kind == "user"
                val block = LinearLayout(engine).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = style.shape(if (user) style.mint else style.inner, 20)
                }
                block.addView(style.text(if (user) tr(R.string.you) else "Codex", 11f, secondary = true, bold = true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
                val body = style.text("", 14f).apply { setTextIsSelectable(true) }
                block.addView(body); messages[item.id] = body
                output?.addView(block, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10); if (user) marginStart = dp(28) else marginEnd = dp(12) })
            }
        }
        if (same) items.forEach { item ->
            val content = (if (item.text.length > 6000) "…\n" else "") + item.text.takeLast(6000)
            messages[item.id]?.let { if (it.text.toString() != content) it.text = content }
        }
        if (follow) feedScroll?.post { feedScroll?.fullScroll(View.FOCUS_DOWN) }
        model?.text = modelName(state) + (state.selectedEffort?.let { " · $it" } ?: "")
        send?.text = if (!same) tr(R.string.save_for_review) else if (state.busy) tr(R.string.queue_send) else tr(R.string.send)
        status?.text = if (!same) tr(R.string.target_changed_input_will_not_go_to_the_new_chat) else if (state.prompts.isNotEmpty()) tr(R.string.waiting_for_approval_open_the_app) else if (state.busy) tr(R.string.task_running_new_input_will_be_queued) else if (state.quickMessages.isNotEmpty()) tr(R.string.pending_messages_open_the_app_to_review) else tr(R.string.current_chat_continue_anytime)
    }
    private fun add(view: View, width: Int, height: Int, focusable: Boolean): Boolean {
        val display = manager.currentWindowMetrics.bounds
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val p = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT; title = tr(R.string.codex_go_floating_chat)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            x = if (focusable) (display.width() - width - dp(12)).coerceAtLeast(dp(12)) else this@ChatOverlay.x.takeIf { it >= 0 }?.coerceIn(0, (display.width() - width).coerceAtLeast(0)) ?: (display.width() - width - dp(12)).coerceAtLeast(0)
            y = if (focusable) dp(48) else dp(this@ChatOverlay.y).coerceIn(dp(32), (display.height() - height - dp(48)).coerceAtLeast(dp(32)))
        }
        return runCatching { manager.addView(view, p); root = view; params = p; true }.getOrElse { engine.overlayUnavailable(); false }
    }
    private fun showBubble() {
        remove()
        expanded = false
        val bubble = ImageButton(engine).apply {
            contentDescription = tr(R.string.open_floating_chat); setImageResource(R.drawable.codex_brand); scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10)); background = style.shape(style.mint, 24, true); elevation = dp(8).toFloat()
            setOnClickListener { showPanel() }
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        val slop = ViewConfiguration.get(engine).scaledTouchSlop
        bubble.setOnTouchListener { view, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = p.x; startY = p.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    moved = moved || abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop
                    if (moved) { val bounds = manager.currentWindowMetrics.bounds; p.x = (startX + event.rawX - downX).toInt().coerceIn(0, (bounds.width() - dp(60)).coerceAtLeast(0)); p.y = (startY + event.rawY - downY).toInt().coerceIn(dp(32), (bounds.height() - dp(100)).coerceAtLeast(dp(32))); runCatching { manager.updateViewLayout(view, p) } }; true
                }
                MotionEvent.ACTION_UP -> { if (!moved) view.performClick() else { p.x = if (p.x + dp(30) < manager.currentWindowMetrics.bounds.width() / 2) dp(8) else manager.currentWindowMetrics.bounds.width() - dp(68); runCatching { manager.updateViewLayout(view, p) }; x = p.x; y = (p.y / engine.resources.displayMetrics.density).toInt(); preferences.edit().putInt("chatBubbleX", x).putInt("chatBubbleY", y).apply() }; true }
                else -> false
            }
        }
        add(bubble, dp(60), dp(60), false)
    }
    private fun showPanel() {
        remove()
        target = engine.quickTarget(); destination = "${engine.state.value.project.displayName} · ${engine.state.value.displayTitle}"
        val card = LinearLayout(engine).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = style.shape(style.surface, 32, true); elevation = dp(12).toFloat()
            isFocusableInTouchMode = true
        }
        val header = LinearLayout(engine).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(engine).apply { setImageResource(R.drawable.codex_brand); background = style.shape(style.mint, 14); setPadding(dp(5), dp(5), dp(5), dp(5)) }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
        val title = LinearLayout(engine).apply { orientation = LinearLayout.VERTICAL }
        title.addView(style.text("Codex Go", 19f, bold = true)); title.addView(style.text(tr(R.string.floating_chat), 11f, secondary = true).apply { setPadding(0, dp(3), 0, 0) })
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(style.icon("−", tr(R.string.collapse)) { showBubble() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(style.icon("×", tr(R.string.close)) { engine.setChatOverlayEnabled(false) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) }); card.addView(header)
        card.addView(style.text(destination, 12f, secondary = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(12), dp(9), dp(12), dp(9)); background = style.shape(style.blue, 16) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(10) })
        output = LinearLayout(engine).apply { orientation = LinearLayout.VERTICAL; contentDescription = tr(R.string.floating_chat_content); setPadding(0, dp(4), 0, dp(4)) }
        feedScroll = ScrollView(engine).apply { addView(output); isVerticalScrollBarEnabled = false; clipToPadding = false }
        card.addView(feedScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        status = style.text("", 11f, secondary = true)
        card.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(10) })
        val composer = LinearLayout(engine).apply { gravity = Gravity.BOTTOM; setPadding(dp(4), dp(4), dp(4), dp(4)); background = style.shape(style.inner, 24, true) }
        input = EditText(engine).apply {
            hint = tr(R.string.message_codex); contentDescription = tr(R.string.floating_message_input); setTextColor(ink); setHintTextColor(style.muted)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1; maxLines = 3; filters = arrayOf(InputFilter.LengthFilter(QuickMessage.MAX_TEXT)); setText(preferences.getString("chatOverlayDraft", ""))
            textSize = 16f; setPadding(dp(12), dp(10), dp(12), dp(10)); background = null
            addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} ; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { preferences.edit().putString("chatOverlayDraft", s?.toString().orEmpty()).apply() }; override fun afterTextChanged(s: Editable?) {} })
        }; composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        send = style.action(tr(R.string.send), true) {
            val value = input?.text?.toString().orEmpty()
            val result = engine.submitQuickText(value, target, "悬浮窗", destinationHint = destination)
            if (result.consumed) input?.setText("")
            status?.text = result.message
        }; composer.addView(send, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(4) }); card.addView(composer)
        val footer = LinearLayout(engine).apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(action(tr(R.string.open_app)) { engine.openQuickChatApp() }.apply { background = null; setTextColor(style.accent) }, LinearLayout.LayoutParams(-2, dp(44)))
        model = style.text("", 11f, secondary = true).apply { gravity = Gravity.END; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        footer.addView(model, LinearLayout.LayoutParams(0, -2, 1f)); card.addView(footer)
        val bounds = manager.currentWindowMetrics.bounds
        val compact = bounds.height() < dp(480)
        val frame: View = if (compact) ScrollView(engine).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; background = card.background; clipToOutline = true; addView(card) } else card
        val height = if (compact) bounds.height() - dp(80) else (bounds.height() * .56f).toInt().coerceAtMost(dp(560))
        if (add(frame, (bounds.width() - dp(24)).coerceAtMost(dp(420)), height.coerceAtLeast(dp(160)), true)) {
            expanded = true; update(engine.state.value, false); card.requestFocus()
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) { frame.alpha = 0f; frame.translationY = dp(12).toFloat(); frame.animate().alpha(1f).translationY(0f).setDuration(180).start() }
        }
    }
    fun hideForTool() { hidden++; input?.clearFocus(); root?.windowToken?.let { engine.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it, 0) }; root?.visibility = View.INVISIBLE }
    fun restoreAfterTool() { hidden = (hidden - 1).coerceAtLeast(0); if (hidden == 0) root?.visibility = View.VISIBLE }
    private fun remove() { root?.let { engine.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0); runCatching { manager.removeView(it) } }; root = null; params = null; input = null; output = null; feedKey = null; messages.clear(); feedScroll = null; status = null; send = null; model = null }
    fun close() { remove(); expanded = false }
}
