package com.example.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Остров: пилюля сверху экрана (время, батарея, трек),
 * по тапу раскрывается в плеер + громкость + быстрые действия.
 */
class IslandService : Service() {

    companion object {
        @Volatile
        var running = false
        const val ACTION_REFRESH = "com.example.island.REFRESH"
        private const val CHANNEL = "island_service"
    }

    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var topRow: LinearLayout
    private lateinit var spacer: View
    private lateinit var timeView: TextView
    private lateinit var trackView: TextView
    private lateinit var batteryView: TextView
    private lateinit var panel: LinearLayout
    private lateinit var artView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var controlsRow: LinearLayout
    private lateinit var playBtn: ImageButton
    private lateinit var volume: SeekBar
    private lateinit var torchChip: TextView

    private var attached = false
    private var expanded = false
    private var trackText = ""

    // вырез камеры (верхний), в пикселях; null — выреза нет
    private var cutout: Rect? = null

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ---- медиа ----
    private lateinit var msm: MediaSessionManager
    private var controller: MediaController? = null
    private var sessionsListenerRegistered = false
    private var hasMediaAccess = false

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessions(list) }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = updateMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateMedia()
        override fun onSessionDestroyed() = startMedia()
    }

    // ---- фонарик ----
    private var torchId: String? = null
    private var torchOn = false
    private lateinit var camManager: CameraManager
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) {
                torchOn = enabled
                updateTorchChip()
            }
        }
    }

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateTime()
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateBattery(i)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        camManager = getSystemService(CAMERA_SERVICE) as CameraManager

        buildUi()
        readInitialCutout()
        applyGeometry(update = false)
        wm.addView(root, lp)
        attached = true

        updateTime()
        val sticky = reg(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) updateBattery(sticky)
        reg(tickReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        initTorch()
        startMedia()
        setExpanded(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH && attached) startMedia()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        if (attached) {
            try { wm.removeView(root) } catch (_: Exception) {}
            attached = false
            try { unregisterReceiver(tickReceiver) } catch (_: Exception) {}
            try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
            try { camManager.unregisterTorchCallback(torchCallback) } catch (_: Exception) {}
            controller?.unregisterCallback(controllerCallback)
            if (sessionsListenerRegistered) {
                try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
            }
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ foreground

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Island", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Island работает")
            .setContentText("Нажми, чтобы открыть настройки")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun reg(r: BroadcastReceiver, f: IntentFilter): Intent? =
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(r, f)

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun buildUi() {
        lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(6)
            title = "Island"
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_OUTSIDE && expanded) setExpanded(false)
                false
            }
        }

        // ---- шапка (всегда видна) ----
        // Первая строка: [время] [пустое место под камеру] [батарея].
        // Текст никогда не попадает под вырез. Трек — отдельной строкой НИЖЕ выреза.
        header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(7))
            setOnClickListener { setExpanded(!expanded) }
        }
        timeView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        batteryView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        spacer = View(this)
        topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(timeView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        topRow.addView(spacer, LinearLayout.LayoutParams(0, 1))
        topRow.addView(batteryView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        header.addView(topRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        trackView = TextView(this).apply {
            setTextColor(0xFFB0B0B8.toInt())
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        header.addView(trackView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(header)

        // вырез узнаём из insets окна (на случай поворота / смены экрана)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 28) {
                val r = topCutoutRect(insets.displayCutout)
                if (r != cutout) {
                    cutout = r
                    if (attached) applyGeometry(update = true)
                }
            }
            v.onApplyWindowInsets(insets)
        }

        // ---- раскрытая панель ----
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(14))
        }

        // плеер
        val mediaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        artView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = bg(0xFF2A2A2E.toInt(), dp(14))
            clipToOutline = true
        }
        mediaRow.addView(artView, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
            rightMargin = dp(12)
        })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        artistView = TextView(this).apply {
            setTextColor(0xFFB0B0B8.toInt())
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(titleView)
        texts.addView(artistView)
        mediaRow.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(mediaRow)

        controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = mediaButton(android.R.drawable.ic_media_previous) {
            controller?.transportControls?.skipToPrevious()
        }
        playBtn = mediaButton(android.R.drawable.ic_media_play) {
            val c = controller ?: return@mediaButton
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
            else c.transportControls.play()
        }
        val next = mediaButton(android.R.drawable.ic_media_next) {
            controller?.transportControls?.skipToNext()
        }
        for (b in listOf(prev, playBtn, next)) {
            controlsRow.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        panel.addView(controlsRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // громкость
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        volume = SeekBar(this).apply {
            max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        panel.addView(volume, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        // быстрые действия
        torchChip = chip("Фонарик") { toggleTorch() }
        val row1 = chipRow(
            torchChip,
            chip("Интернет") {
                launch(
                    if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    else Intent(Settings.ACTION_WIFI_SETTINGS)
                )
            },
            chip("Bluetooth") { launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        )
        val row2 = chipRow(
            chip("Камера") { launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) },
            chip("Звонилка") { launch(Intent(Intent.ACTION_DIAL)) },
            chip("Настройки") { launch(Intent(Settings.ACTION_SETTINGS)) }
        )
        panel.addView(row1, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
        panel.addView(row2, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        root.addView(panel)
    }

    private fun mediaButton(icon: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        background = null
        setOnClickListener { onClick() }
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        background = bg(0xFF2A2A2E.toInt(), dp(14))
        setPadding(dp(4), dp(12), dp(4), dp(12))
        setOnClickListener { onClick() }
    }

    private fun chipRow(vararg chips: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        chips.forEachIndexed { i, c ->
            addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
        }
    }

    private fun setExpanded(e: Boolean) {
        expanded = e
        panel.visibility = if (e) View.VISIBLE else View.GONE
        root.background = bg(Color.BLACK, if (e) dp(30) else dp(20))
        updateTrackVisibility()

        if (e) {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            volume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            startMedia()
        }
        applyGeometry(update = true)

        root.alpha = 0.4f
        root.translationY = -dp(8).toFloat()
        root.animate().alpha(1f).translationY(0f).setDuration(180).start()
    }

    // ------------------------------------------------------------------ вырез камеры

    private fun topCutoutRect(dc: android.view.DisplayCutout?): Rect? {
        if (dc == null || Build.VERSION.SDK_INT < 28) return null
        val r = if (Build.VERSION.SDK_INT >= 29) dc.boundingRectTop
                else dc.boundingRects.firstOrNull { it.top <= 0 }
        return if (r == null || r.isEmpty) null else Rect(r)
    }

    private fun readInitialCutout() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                cutout = topCutoutRect(wm.currentWindowMetrics.windowInsets.displayCutout)
            } catch (_: Exception) {}
        }
    }

    /**
     * Раскладка относительно выреза:
     *  - вырез по центру  -> остров "обнимает" камеру: время слева, батарея справа;
     *  - вырез сбоку/нет  -> остров сидит ниже выреза, без пустой середины.
     */
    private fun applyGeometry(update: Boolean) {
        val screenW = resources.displayMetrics.widthPixels
        val cut = cutout
        val centered = cut != null &&
            Math.abs(cut.centerX() - screenW / 2) < screenW * 0.12f

        val gapPx: Int
        val rowMin: Int
        if (cut != null && centered) {
            gapPx = cut.width() + dp(16)
            rowMin = maxOf(cut.bottom + dp(6), dp(34))
            lp.y = 0
        } else {
            gapPx = dp(8)
            rowMin = dp(34)
            lp.y = if (cut != null) cut.bottom + dp(4) else dp(6)
        }

        spacer.layoutParams = LinearLayout.LayoutParams(gapPx, 1)
        topRow.minimumHeight = rowMin

        lp.width = if (expanded) {
            minOf(dp(360), screenW - dp(24))
        } else {
            // симметрично: слева и справа от зазора одинаковая ширина
            dp(28) + gapPx + 2 * dp(56)
        }
        if (update && attached) wm.updateViewLayout(root, lp)
    }

    private fun launch(i: Intent) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            setExpanded(false)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ время и батарея

    private fun updateTime() {
        timeView.text = timeFmt.format(Date())
    }

    private fun updateBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (level >= 0 && scale > 0) {
            batteryView.text = "${level * 100 / scale}%" + if (charging) " ⚡" else ""
        }
    }

    // ------------------------------------------------------------------ медиа

    private fun startMedia() {
        try {
            val cn = ComponentName(this, MediaListenerService::class.java)
            if (!sessionsListenerRegistered) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, cn)
                sessionsListenerRegistered = true
            }
            hasMediaAccess = true
            onSessions(msm.getActiveSessions(cn))
        } catch (e: SecurityException) {
            hasMediaAccess = false
            updateMedia()
        }
    }

    private fun onSessions(list: List<MediaController>?) {
        val pick = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        if (pick?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = pick
            pick?.registerCallback(controllerCallback)
        }
        updateMedia()
    }

    private fun updateMedia() {
        val c = controller
        if (c == null) {
            titleView.text = if (hasMediaAccess) "Ничего не играет" else "Нужен доступ к уведомлениям"
            artistView.text = if (hasMediaAccess) "" else "Выдай его в приложении Island"
            controlsRow.visibility = View.GONE
            artView.visibility = View.GONE
            trackText = ""
        } else {
            val md = c.metadata
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            val art: Bitmap? = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING

            titleView.text = title ?: "Без названия"
            artistView.text = artist ?: ""
            controlsRow.visibility = View.VISIBLE
            playBtn.setImageResource(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            if (art != null) {
                artView.setImageBitmap(art)
                artView.visibility = View.VISIBLE
            } else {
                artView.visibility = View.GONE
            }
            trackText = if (playing && title != null) "♪ $title" else ""
        }
        trackView.text = trackText
        updateTrackVisibility()
    }

    private fun updateTrackVisibility() {
        // трек — отдельная строка под выемкой; только в свёрнутом виде и пока что-то играет
        // (в раскрытом названием занимается плеер)
        trackView.visibility = if (!expanded && trackText.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ фонарик

    private fun initTorch() {
        try {
            torchId = camManager.cameraIdList.firstOrNull { id ->
                val ch = camManager.getCameraCharacteristics(id)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            camManager.registerTorchCallback(torchCallback, null)
        } catch (_: Exception) {
        }
    }

    private fun toggleTorch() {
        val id = torchId
        if (id == null) {
            Toast.makeText(this, "Фонарика нет", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            camManager.setTorchMode(id, !torchOn)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось включить фонарик", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTorchChip() {
        torchChip.background = bg(if (torchOn) Color.WHITE else 0xFF2A2A2E.toInt(), dp(14))
        torchChip.setTextColor(if (torchOn) Color.BLACK else Color.WHITE)
    }
}
