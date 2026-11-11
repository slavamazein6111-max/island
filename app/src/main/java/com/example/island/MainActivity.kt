package com.example.island

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var overlayStatus: TextView
    private lateinit var notifStatus: TextView
    private lateinit var toggleBtn: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (IslandService.running) {
            startService(Intent(this, IslandService::class.java).setAction(IslandService.ACTION_REFRESH))
        }
    }

    private fun text(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun card(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(0xFF1C1C20.toInt())
            cornerRadius = dp(16).toFloat()
        }
        views.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }
        col.addView(text("Island", 30f, bold = true))
        col.addView(text("Динамический остров для Android", 14f, 0xFFB0B0B8.toInt()))

        overlayStatus = text("", 14f)
        val overlayBtn = Button(this).apply {
            text = "Открыть настройки"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
        col.addView(card(text("1. Показ поверх других окон", 16f, bold = true), overlayStatus, overlayBtn))

        notifStatus = text("", 14f)
        val notifBtn = Button(this).apply {
            text = "Открыть настройки"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        val hint = text(
            "Нужен для плеера. Если Android не даёт включить (приложение не из магазина): " +
                "Настройки → Приложения → Island → ⋮ → «Разрешить ограниченные настройки», " +
                "потом вернись и включи доступ.",
            12f, 0xFF8A8A92.toInt()
        )
        col.addView(card(text("2. Доступ к уведомлениям", 16f, bold = true), notifStatus, notifBtn, hint))

        toggleBtn = Button(this).apply {
            setOnClickListener { toggle() }
        }
        col.addView(toggleBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0E0E10.toInt())
            addView(col)
        })
    }

    private fun toggle() {
        if (IslandService.running) {
            stopService(Intent(this, IslandService::class.java))
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала выдай показ поверх других окон", Toast.LENGTH_LONG).show()
                return
            }
            startForegroundService(Intent(this, IslandService::class.java))
        }
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 400)
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        overlayStatus.text = if (overlayOk) "✓ выдано" else "✗ не выдано"
        overlayStatus.setTextColor(if (overlayOk) 0xFF5BD66B.toInt() else 0xFFFF6B6B.toInt())

        val notifOk = MediaListenerService.accessGranted(this)
        notifStatus.text = if (notifOk) "✓ выдано" else "✗ не выдано (без него остров работает, но без плеера)"
        notifStatus.setTextColor(if (notifOk) 0xFF5BD66B.toInt() else 0xFFFFB347.toInt())

        toggleBtn.text = if (IslandService.running) "Выключить остров" else "Включить остров"
    }
}