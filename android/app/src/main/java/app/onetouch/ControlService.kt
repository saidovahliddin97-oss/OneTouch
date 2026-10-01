package app.onetouch

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

/**
 * «OneTouch — управление» in Settings → Accessibility. Android offers no other
 * way for an app to act outside its own screen, so this service:
 *  • performs taps, swipes, Back/Home/Recents and text input sent from the Mac
 *    while the phone's screen is mirrored there;
 *  • shows a floating button over any app: tap = screenshot of the current
 *    screen to the computer, hold = latest photo (Android does not let apps
 *    watch touches inside other apps, so a pinch there cannot be detected);
 *  • the system accessibility button does the screenshot too.
 */
class ControlService : AccessibilityService() {
    private var lastShot = 0L

    override fun onServiceConnected() {
        instance = this
        showBubble(getSharedPreferences("onetouch", MODE_PRIVATE).getBoolean("bubble", true))
        accessibilityButtonController.registerAccessibilityButtonCallback(object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) = screenshotToDesktop()
        })
    }

    override fun onDestroy() {
        showBubble(false)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- floating button: works on top of any app ----

    private var bubble: View? = null

    /** A small draggable OneTouch button. Tap: screenshot → computer. Hold: latest photo → computer. */
    fun showBubble(show: Boolean) {
        val wm = getSystemService(WindowManager::class.java)
        if (!show) {
            bubble?.let { runCatching { wm.removeView(it) } }
            bubble = null
            return
        }
        if (bubble != null) return
        val size = (52 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences("onetouch", MODE_PRIVATE)
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("bubbleX", 0)
            y = prefs.getInt("bubbleY", (resources.displayMetrics.heightPixels * 0.4).toInt())
        }
        val v = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            alpha = 0.85f
            elevation = 8f
            contentDescription = "OneTouch"
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val longPress = Runnable {
            if (!moved) {
                moved = true // consume: no tap after a long press
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                latestPhotoToDesktop()
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    v.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > 12 * resources.displayMetrics.density) { moved = true; v.removeCallbacks(longPress) }
                    if (moved) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (!moved) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        v.alpha = 0f // keep the button out of its own screenshot
                        v.postDelayed({ screenshotToDesktop(); v.postDelayed({ v.alpha = 0.85f }, 600) }, 80)
                    } else {
                        // Snap to the nearest side edge.
                        val w = resources.displayMetrics.widthPixels
                        lp.x = if (lp.x + size / 2 < w / 2) 0 else w - size
                        runCatching { wm.updateViewLayout(v, lp) }
                        prefs.edit().putInt("bubbleX", lp.x).putInt("bubbleY", lp.y).apply()
                    }
                }
                MotionEvent.ACTION_CANCEL -> v.removeCallbacks(longPress)
            }
            true
        }
        runCatching { wm.addView(v, lp); bubble = v }
    }

    private fun latestPhotoToDesktop() {
        val latest = runCatching { Media.recent(this, 1).firstOrNull() }.getOrNull()
        if (latest == null) {
            Toast.makeText(this, "Нет доступа к фото — откройте OneTouch", Toast.LENGTH_SHORT).show()
            return
        }
        OneTouchService.send(this, listOf(Media.job(this, latest.uri)))
        Toast.makeText(this, "Последнее фото → ${currentDesktop(this)?.name ?: "Mac"}", Toast.LENGTH_SHORT).show()
    }

    /** Captures exactly what is on screen and sends it to the current desktop. */
    fun screenshotToDesktop() {
        val now = System.currentTimeMillis()
        if (now - lastShot < 1500) return // the system allows about one capture per second
        lastShot = now
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                if (bmp == null) return
                val bytes = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
                val name = "Снимок экрана " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date()) + ".png"
                OneTouchService.send(this@ControlService, listOf(SendJob(name, bytes.size.toLong(), null) { ByteArrayInputStream(bytes) }))
                val to = currentDesktop(this@ControlService)?.name ?: "Mac"
                Toast.makeText(this@ControlService, "Снимок экрана → $to", Toast.LENGTH_SHORT).show()
            }

            override fun onFailure(errorCode: Int) {
                Toast.makeText(this@ControlService, "Не удалось сделать снимок ($errorCode)", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // ---- remote control from the Mac ----

    fun handle(o: JSONObject) {
        when (o.optString("t")) {
            "tap" -> tap(o.optDouble("x"), o.optDouble("y"), 40)
            "long" -> tap(o.optDouble("x"), o.optDouble("y"), 700)
            "swipe" -> swipe(o.optDouble("x1"), o.optDouble("y1"), o.optDouble("x2"), o.optDouble("y2"), o.optLong("ms", 250))
            "text" -> editFocused { it + o.optString("s") }
            "key" -> when (o.optString("k")) {
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "backspace" -> editFocused { it.dropLast(1) }
                "enter" -> focused()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
    }

    private fun toPx(x: Double, y: Double): Pair<Float, Float> {
        val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return (x.coerceIn(0.0, 1.0) * (b.width() - 1)).toFloat() to (y.coerceIn(0.0, 1.0) * (b.height() - 1)).toFloat()
    }

    private fun tap(x: Double, y: Double, ms: Long) {
        val (px, py) = toPx(x, y)
        stroke(Path().apply { moveTo(px, py) }, ms)
    }

    private fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, ms: Long) {
        val (ax, ay) = toPx(x1, y1)
        val (bx, by) = toPx(x2, y2)
        stroke(Path().apply { moveTo(ax, ay); lineTo(bx, by) }, ms.coerceIn(50, 3000))
    }

    private fun stroke(path: Path, ms: Long) {
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build(), null, null)
    }

    private fun focused(): AccessibilityNodeInfo? = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun editFocused(change: (String) -> String) {
        val node = focused() ?: return
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, change(current))
        })
    }

    companion object {
        @Volatile var instance: ControlService? = null

        /** True when the user has switched the service on in Accessibility settings. */
        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { it.endsWith("/" + ControlService::class.java.name) || it.endsWith("/.ControlService") }
        }
    }
}
