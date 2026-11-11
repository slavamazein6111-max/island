package com.example.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
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
        const val ACTION_SHOW = "com.example.island.SHOW"
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

    // ---- появление / прятанье ----
    private val handler = Handler(Looper.getMainLooper())
    private var islandVisible = false   // остров сейчас показан (или выезжает)
    private var prog = 0f               // 0 = спрятан в камере, 1 = стоит на месте
    private var anim: ValueAnimator? = null
    private var lastTrackKey = ""
    private val hideRunnable = Runnable { if (!expanded) hideIsland() }
    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = showIsland()
    }
    private var expanded = false
    private var trackText = ""

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
        reg(eventReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        initTorch()
        startMedia()
        setExpanded(false)
        root.visibility = View.GONE   // сначала спрятан в камере
        showIsland()                  // и красиво выезжает при запуске
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH && attached) startMedia()
        if (intent?.action == ACTION_SHOW && attached) showIsland()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        anim?.cancel()
        if (attached) {
            try { wm.removeView(root) } catch (_: Exception) {}
            attached = false
            try { unregisterReceiver(tickReceiver) } catch (_: Exception) {}
            try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
            try { unregisterReceiver(eventReceiver) } catch (_: Exception) {}
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
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_launcher),
                    "Показать остров",
                    PendingIntent.getService(
                        this, 1,
                        Intent(this, IslandService::class.java).setAction(ACTION_SHOW),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                ).build()
            )
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

    private val cTile = 0xFF17171B.toInt()
    private val cTileOn = 0xFFFFC857.toInt()
    private val cStroke = 0xFF2C2C32.toInt()
    private val cMuted = 0xFF9A9AA3.toInt()
    private val cAccent = 0xFFFF9A3C.toInt()

    private fun bg(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    /** Плитка: тёмный фон + тонкая обводка, как в оригинальном Island. */
    private fun tileBg(color: Int, radius: Int) = bg(color, radius).apply {
        setStroke(dp(1), cStroke)
    }

    private fun islandBg(radius: Int) = bg(Color.BLACK, radius).apply {
        setStroke(dp(1), cStroke)
    }

    private fun buildUi() {
        lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
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
            setTextColor(cMuted)
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        header.addView(trackView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(header)

        // ---- раскрытая панель ----
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(16))
        }

        // плеер
        val mediaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        artView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = bg(cTile, dp(18))
            clipToOutline = true
        }
        mediaRow.addView(artView, LinearLayout.LayoutParams(dp(84), dp(84)).apply {
            rightMargin = dp(14)
        })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        artistView = TextView(this).apply {
            setTextColor(cMuted)
            textSize = 14f
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
        playBtn.apply {
            setColorFilter(Color.BLACK)
            background = bg(Color.WHITE, dp(26))
        }
        controlsRow.addView(prev, LinearLayout.LayoutParams(0, dp(48), 1f))
        controlsRow.addView(playBtn, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            leftMargin = dp(8); rightMargin = dp(8)
        })
        controlsRow.addView(next, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(controlsRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // громкость
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        volume = SeekBar(this).apply {
            max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            progressTintList = ColorStateList.valueOf(cAccent)
            progressBackgroundTintList = ColorStateList.valueOf(cStroke)
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
        torchChip = chip("🔦", "Фонарик") { toggleTorch() }
        val row1 = chipRow(
            torchChip,
            chip("📶", "Интернет") {
                launch(
                    if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    else Intent(Settings.ACTION_WIFI_SETTINGS)
                )
            },
            chip("🎧", "Bluetooth") { launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        )
        val row2 = chipRow(
            chip("📷", "Камера") { launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) },
            chip("📞", "Звонилка") { launch(Intent(Intent.ACTION_DIAL)) },
            chip("⚙️", "Настройки") { launch(Intent(Settings.ACTION_SETTINGS)) }
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

    private fun chip(icon: String, label: String, onClick: () -> Unit) = TextView(this).apply {
        text = "$icon\n$label"
        setTextColor(Color.WHITE)
        textSize = 12f
        gravity = Gravity.CENTER
        background = tileBg(cTile, dp(18))
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
        root.background = islandBg(if (e) dp(32) else dp(20))
        updateTrackVisibility()

        if (e) {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            volume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            startMedia()
        }
        applyGeometry(update = true)

        handler.removeCallbacks(hideRunnable)
        if (!e && islandVisible) handler.postDelayed(hideRunnable, 1500)
    }

    // ------------------------------------------------------------------ появление из камеры

    /** Выезжает из «кружка камеры» и опускается на своё место. Через 4 с уезжает обратно. */
    private fun showIsland() {
        if (!attached) return
        handler.removeCallbacks(hideRunnable)
        if (!islandVisible || prog < 1f) {
            islandVisible = true
            root.visibility = View.VISIBLE
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            animateIsland(show = true, end = null)
        }
        if (!expanded) handler.postDelayed(hideRunnable, 4000)
    }

    private fun hideIsland() {
        if (!attached || !islandVisible) return
        animateIsland(show = false) {
            root.visibility = View.GONE
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            islandVisible = false
            try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
        }
    }

    private fun animateIsland(show: Boolean, end: (() -> Unit)?) {
        anim?.cancel()
        var canceled = false
        anim = ValueAnimator.ofFloat(prog, if (show) 1f else 0f).apply {
            duration = if (show) 520L else 320L
            interpolator = if (show) OvershootInterpolator(0.9f) else AccelerateInterpolator()
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: Animator) { canceled = true }
                override fun onAnimationEnd(a: Animator) { if (!canceled) end?.invoke() }
            })
            start()
        }
    }

    private fun applyProgress(p: Float) {
        prog = p
        val shownY = statusBarHeight() + dp(4)
        val h = if (root.height > 0) root.height else dp(41)
        val hiddenY = statusBarHeight() / 2 - h / 2   // центр острова = центр строки статуса (камера)
        lp.y = (hiddenY + (shownY - hiddenY) * p).toInt()
        val sc = 0.12f + 0.88f * p.coerceIn(0f, 1.1f)
        root.pivotX = root.width / 2f
        root.pivotY = root.height / 2f
        root.scaleX = sc
        root.scaleY = sc
        root.alpha = (p * 2.5f).coerceIn(0f, 1f)
        try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ геометрия

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    /**
     * Остров ВСЕГДА стоит ниже строки статуса (и ниже выреза камеры).
     * Выше — на прошивках вроде MIUI/HyperOS статус-бар рисуется поверх оверлея:
     * текст налезает на иконки, а нажатия перехватывает статус-бар.
     */
    private fun applyGeometry(update: Boolean) {
        val screenW = resources.displayMetrics.widthPixels
        if (anim?.isRunning != true) lp.y = statusBarHeight() + dp(4)
        spacer.layoutParams = LinearLayout.LayoutParams(dp(24), 1)
        topRow.minimumHeight = dp(34)
        lp.width = if (expanded) minOf(dp(360), screenW - dp(24)) else dp(28) + dp(24) + 2 * dp(56)
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
            lastTrackKey = ""
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
            val key = if (playing && title != null) "$title|$artist" else ""
            if (key.isNotEmpty() && key != lastTrackKey) showIsland()
            lastTrackKey = key
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
        torchChip.background = if (torchOn) bg(cTileOn, dp(18)) else tileBg(cTile, dp(18))
        torchChip.setTextColor(if (torchOn) Color.BLACK else Color.WHITE)
    }
}
