package com.huihou.glasses

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import huihou.core.GlassesText
import huihou.core.Lens
import huihou.core.Minutes
import huihou.core.Todo
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * 镜片画面，按需求文档第七章“眼镜端显示规范”：
 * - 只放在视野底部边缘，中间不放内容；黑底（光波导上黑色 = 透明），不用大块底色，只用细边框
 * - 主行不超过 15 个字，最多再加一行小字
 * - 蓝色是提醒和答案，橙色是答应别人的事
 * - 约 5 秒自动消失，点镜腿看详情
 *
 * 状态：…（生成中）→ 通知（蓝）→ 我的待办（橙，可翻页）→ 原话（蓝，小字是原话）
 * 操作：单击 = 往下看一层；双击 / 返回键 = 退一层；前后滑动 = 翻条；长按 = 念给我听，任意操作 = 停
 *
 * 眼镜镜腿到底发什么按键还不知道，所以按键、触摸、触控板滚动都接了，并全部打到 logcat（tag: Huihou）。
 */
class GlassesActivity : Activity() {

    private enum class Mode { BLANK, WORKING, NOTICE, TODO, QUOTE, MESSAGE }

    private lateinit var box: LinearLayout
    private lateinit var mainLine: TextView
    private lateinit var subLine: TextView
    private lateinit var border: GradientDrawable
    private val ui = Handler(Looper.getMainLooper())

    private var mode = Mode.BLANK
    private var flashBack: Mode? = null
    private var minutes: Minutes? = null
    private var items: List<Todo> = emptyList()
    private var index = 0
    private var hasUser = false
    private var feishuFailed = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    private lateinit var gestures: GestureDetector
    private var pendingKeyTap: Runnable? = null
    private var centerLongPressed = false
    private var lastScrollAt = 0L

    private val autoClose = Runnable {
        if (mode == Mode.WORKING) show(GlassesText.stillWorking(), COOL, Mode.MESSAGE, MESSAGE_MS) else close()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        border = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(2), COOL)
        }
        mainLine = TextView(this).apply {
            setTextColor(COOL)
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, MAIN_SP)
        }
        subLine = TextView(this).apply {
            setTextColor(SUB)
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SUB_SP)
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            setPadding(0, dp(6), 0, 0)
        }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = border
            setPadding(dp(22), dp(10), dp(22), dp(10))
            addView(mainLine, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(subLine, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            visibility = View.INVISIBLE
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(box, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (resources.displayMetrics.heightPixels * BOTTOM_MARGIN).toInt()
            })
        }
        setContentView(root)
        hideSystemBars()

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { logInput("触摸：单击"); onTap(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { logInput("触摸：双击"); onDoubleTap(); return true }
            override fun onLongPress(e: MotionEvent) { logInput("触摸：长按"); onLongPress() }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val forward = if (abs(vx) >= abs(vy)) vx < 0 else vy < 0
                logInput("触摸：滑动 vx=$vx vy=$vy → ${if (forward) "下一条" else "上一条"}")
                if (forward) onNext() else onPrev()
                return true
            }
        })

        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ───────────── 入口 ─────────────

    private fun handle(i: Intent?) {
        when (i?.getStringExtra(EXTRA_MODE)) {
            MODE_WORKING -> showWorking()
            MODE_MINUTES -> openMeeting(i.getStringExtra(EXTRA_DIR)?.let(::File))
            MODE_MESSAGE -> show(Lens(i.getStringExtra(EXTRA_TEXT).orEmpty(), i.getStringExtra(EXTRA_SUB).orEmpty()), COOL, Mode.MESSAGE, MESSAGE_MS)
            else -> openMeeting(MeetingStore(this).latestWithMinutes()) // 从桌面打开：看最近一场
        }
    }

    private fun openMeeting(dir: File?) {
        val store = MeetingStore(this)
        val m = dir?.let { store.loadMinutes(it) }
        if (dir == null || m == null) {
            show(GlassesText.noMinutes(), COOL, Mode.MESSAGE, MESSAGE_MS)
            return
        }
        minutes = m
        hasUser = AppConfig.load(this).hasUser
        items = GlassesText.items(m, hasUser)
        feishuFailed = store.feishuFailed(dir)
        index = 0
        showNotice()
    }

    // ───────────── 画面 ─────────────

    private fun show(lens: Lens, color: Int, newMode: Mode, closeAfterMs: Long, marqueeSub: Boolean = false) {
        mode = newMode
        if (newMode != Mode.MESSAGE) flashBack = null
        mainLine.setTextColor(color)
        border.setStroke(dp(2), (color and 0x00FFFFFF) or (0x99 shl 24))
        fitMain(lens.main)
        if (lens.sub.isEmpty()) {
            subLine.visibility = View.GONE
        } else {
            subLine.visibility = View.VISIBLE
            subLine.text = lens.sub
            if (marqueeSub) {
                subLine.ellipsize = TextUtils.TruncateAt.MARQUEE
                subLine.marqueeRepeatLimit = -1
                subLine.isSelected = true
            } else {
                subLine.ellipsize = TextUtils.TruncateAt.END
                subLine.isSelected = false
            }
        }
        box.visibility = View.VISIBLE
        ui.removeCallbacks(autoClose)
        if (closeAfterMs > 0) ui.postDelayed(autoClose, closeAfterMs)
        Log.i(TAG, "镜片[$newMode] ${lens.main}${if (lens.sub.isNotEmpty()) " ｜ ${lens.sub}" else ""}")
    }

    private fun showWorking() = show(GlassesText.working(), COOL, Mode.WORKING, WORKING_MAX_MS)

    private fun showNotice() {
        val m = minutes ?: return show(GlassesText.noMinutes(), COOL, Mode.MESSAGE, MESSAGE_MS)
        show(GlassesText.notice(m, hasUser, feishuFailed), COOL, Mode.NOTICE, NOTICE_MS)
    }

    private fun showTodo() {
        if (items.isEmpty()) return showNotice()
        index = index.coerceIn(0, items.lastIndex)
        show(GlassesText.todo(items[index], index, items.size), WARM, Mode.TODO, BROWSE_MS)
    }

    private fun showQuote() {
        val t = items.getOrNull(index) ?: return
        show(GlassesText.quote(t), COOL, Mode.QUOTE, BROWSE_MS, marqueeSub = true)
    }

    /** 短暂提示一下，然后回到刚才的画面。 */
    private fun flash(lens: Lens) {
        val back = if (mode == Mode.MESSAGE) flashBack else mode
        show(lens, COOL, Mode.MESSAGE, 0)
        flashBack = back
        ui.postDelayed({ if (mode == Mode.MESSAGE && flashBack != null) render(flashBack!!) }, FLASH_MS)
    }

    private fun render(m: Mode) = when (m) {
        Mode.NOTICE -> showNotice()
        Mode.TODO -> showTodo()
        Mode.QUOTE -> showQuote()
        Mode.WORKING -> showWorking()
        else -> close()
    }

    /** 主行字号 22，放不下再往下缩，最小 17。主行本身已限制在 15 个字以内。 */
    private fun fitMain(text: String) {
        val width = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        val paint = TextPaint(mainLine.paint)
        var sp = MAIN_SP
        while (sp > MIN_SP) {
            paint.textSize = spToPx(sp)
            if (paint.measureText(text) <= width) break
            sp -= 1f
        }
        mainLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        mainLine.text = text
    }

    private fun close() {
        stopSpeaking()
        ui.removeCallbacksAndMessages(null)
        mode = Mode.BLANK
        box.visibility = View.INVISIBLE
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    // ───────────── 操作 ─────────────

    private fun onTap() {
        stopSpeaking()
        when (mode) {
            Mode.NOTICE -> if (items.isNotEmpty()) { index = 0; showTodo() } else flash(GlassesText.sentToFeishu())
            Mode.TODO -> showQuote()
            Mode.QUOTE -> showTodo()
            Mode.MESSAGE -> flashBack?.let { render(it) } ?: close()
            Mode.WORKING, Mode.BLANK -> Unit
        }
    }

    private fun onDoubleTap() {
        stopSpeaking()
        when (mode) {
            Mode.QUOTE -> showTodo()
            Mode.TODO -> showNotice()
            else -> close() // 生成中也可以先关掉，好了会再弹
        }
    }

    private fun onNext() {
        stopSpeaking()
        when (mode) {
            Mode.NOTICE -> if (items.isNotEmpty()) { index = 0; showTodo() }
            Mode.TODO, Mode.QUOTE -> if (index < items.lastIndex) { index++; showTodo() } else flash(GlassesText.lastOne())
            else -> Unit
        }
    }

    private fun onPrev() {
        stopSpeaking()
        when (mode) {
            Mode.TODO, Mode.QUOTE -> if (index > 0) { index--; showTodo() } else showNotice()
            else -> Unit
        }
    }

    private fun onBack() {
        stopSpeaking()
        when (mode) {
            Mode.QUOTE -> showTodo()
            Mode.TODO -> showNotice()
            else -> close()
        }
    }

    private fun onLongPress() {
        val m = minutes ?: return
        speak(GlassesText.speech(m, hasUser))
    }

    // ───────────── 输入：按键 / 触摸 / 触控板 ─────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        logInput("按键按下 ${KeyEvent.keyCodeToString(keyCode)}($keyCode) repeat=${event.repeatCount} source=0x${Integer.toHexString(event.source)}")
        return when (keyCode) {
            in CENTER_KEYS -> {
                if (event.repeatCount == 0) {
                    centerLongPressed = false
                    event.startTracking()
                }
                true
            }
            in NEXT_KEYS -> { onNext(); true }
            in PREV_KEYS -> { onPrev(); true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { onBack(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in CENTER_KEYS) {
            logInput("按键长按 ${KeyEvent.keyCodeToString(keyCode)}")
            centerLongPressed = true
            pendingKeyTap?.let { ui.removeCallbacks(it) }
            pendingKeyTap = null
            onLongPress()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in CENTER_KEYS) {
            if (!centerLongPressed && !event.isCanceled) keyTap()
            centerLongPressed = false
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) return true
        return super.onKeyUp(keyCode, event)
    }

    /** 按键也要分单击和双击：300ms 内第二下算双击。 */
    private fun keyTap() {
        val pending = pendingKeyTap
        if (pending != null) {
            ui.removeCallbacks(pending)
            pendingKeyTap = null
            logInput("按键：双击")
            onDoubleTap()
        } else {
            val r = Runnable { pendingKeyTap = null; logInput("按键：单击"); onTap() }
            pendingKeyTap = r
            ui.postDelayed(r, DOUBLE_TAP_MS)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        return true
    }

    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL) {
            val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = ev.getAxisValue(MotionEvent.AXIS_HSCROLL)
            logInput("滚动 v=$v h=$h source=0x${Integer.toHexString(ev.source)}")
            val now = SystemClock.uptimeMillis()
            if (now - lastScrollAt < SCROLL_DEBOUNCE_MS) return true
            lastScrollAt = now
            val forward = if (abs(h) > abs(v)) h > 0 else v < 0
            if (forward) onNext() else onPrev()
            return true
        }
        if (ev.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_UP) {
                logInput("触控板 ${MotionEvent.actionToString(ev.actionMasked)} x=${ev.x} y=${ev.y}")
            }
            gestures.onTouchEvent(ev)
            return true
        }
        return super.onGenericMotionEvent(ev)
    }

    // ───────────── 念给我听 ─────────────

    private fun speak(text: String) {
        if (tts == null) {
            pendingSpeech = text
            tts = TextToSpeech(this) { status ->
                val engine = tts
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    val r = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) { ui.post { rearmAutoClose() } }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { ui.post { rearmAutoClose() } }
                    })
                }
                ui.post {
                    val p = pendingSpeech
                    pendingSpeech = null
                    if (ttsReady && p != null) doSpeak(p) else flash(GlassesText.ttsUnavailable())
                }
            }
            return
        }
        if (ttsReady) doSpeak(text) else flash(GlassesText.ttsUnavailable())
    }

    private fun doSpeak(text: String) {
        ui.removeCallbacks(autoClose) // 念的时候不自动消失
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "huihou")
        Log.i(TAG, "朗读：$text")
    }

    private fun stopSpeaking() {
        if (tts?.isSpeaking == true) {
            tts?.stop()
            rearmAutoClose()
        }
    }

    private fun rearmAutoClose() {
        ui.removeCallbacks(autoClose)
        ui.postDelayed(autoClose, if (mode == Mode.NOTICE) NOTICE_MS else BROWSE_MS)
    }

    // ───────────── 工具 ─────────────

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun logInput(msg: String) = Log.i(TAG, "输入 $msg")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun spToPx(sp: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    companion object {
        private const val TAG = "Huihou"

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_DIR = "dir"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_SUB = "sub"
        private const val MODE_WORKING = "working"
        private const val MODE_MINUTES = "minutes"
        private const val MODE_MESSAGE = "message"

        /** 蓝色 = 提醒和答案 */
        private val COOL = Color.parseColor("#5AC8FA")
        /** 橙色 = 答应别人的事（待办） */
        private val WARM = Color.parseColor("#FFB547")
        /** 小字 */
        private val SUB = Color.parseColor("#C8C8C8")

        private const val MAIN_SP = 22f
        private const val MIN_SP = 17f
        private const val SUB_SP = 17f
        /** 提示框离视野底边的距离（占屏幕高度的比例） */
        private const val BOTTOM_MARGIN = 0.05f

        private const val NOTICE_MS = 5_000L
        private const val BROWSE_MS = 8_000L
        private const val MESSAGE_MS = 4_000L
        private const val FLASH_MS = 1_500L
        private const val WORKING_MAX_MS = 150_000L
        private const val DOUBLE_TAP_MS = 300L
        private const val SCROLL_DEBOUNCE_MS = 300L

        private val CENTER_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        )
        private val NEXT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_TAB,
        )
        private val PREV_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )

        fun showWorking(ctx: Context) = launch(ctx, MODE_WORKING, notify = null) {}

        fun showMinutes(ctx: Context, dir: File, notice: Lens) =
            launch(ctx, MODE_MINUTES, notify = notice.main) { putExtra(EXTRA_DIR, dir.absolutePath) }

        fun showMessage(ctx: Context, lens: Lens) =
            launch(ctx, MODE_MESSAGE, notify = lens.main) { putExtra(EXTRA_TEXT, lens.main); putExtra(EXTRA_SUB, lens.sub) }

        fun showMessage(ctx: Context, text: String) = showMessage(ctx, GlassesText.message(text))

        /** 从后台把画面弹到镜片上；没有悬浮窗权限时系统可能拦截，就再发一条系统通知兜底。 */
        private fun launch(ctx: Context, mode: String, notify: String?, extras: Intent.() -> Unit) {
            val i = Intent(ctx, GlassesActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_MODE, mode)
                .apply(extras)
            val canPop = Settings.canDrawOverlays(ctx)
            runCatching { ctx.startActivity(i) }.onFailure { Log.w(TAG, "弹不出镜片画面", it) }
            if (!canPop) {
                Log.w(TAG, "没有悬浮窗权限，后台弹出可能被拦截：adb shell appops set com.huihou.glasses SYSTEM_ALERT_WINDOW allow")
                if (notify != null) Notifier.result(ctx, notify, i)
            }
        }
    }
}
