package com.example.island

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Нужен только ради одного: невидимая зона над камерой с типом окна
 * TYPE_ACCESSIBILITY_OVERLAY — оно рисуется ВЫШЕ статус-бара и получает касания
 * даже там, где обычный оверлей их не получает (MIUI/HyperOS и др.).
 * Никакие данные с экрана сервис не читает.
 */
class IslandAccessibilityService : AccessibilityService() {

    private var view: View? = null
    private lateinit var wm: WindowManager

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val sbh = if (id > 0) resources.getDimensionPixelSize(id) else dp(24)

        val v = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                if (IslandService.running) {
                    startService(
                        Intent(this@IslandAccessibilityService, IslandService::class.java)
                            .setAction(IslandService.ACTION_SHOW)
                    )
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            dp(72), sbh,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
            title = "IslandTriggerA11y"
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        try {
            wm.addView(v, lp)
            view = v
        } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
        return super.onUnbind(intent)
    }
}
