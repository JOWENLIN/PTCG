package com.pocketshot.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class CaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_INTERVAL = "interval"
        private const val ACTION_STOP = "com.pocketshot.app.STOP"
        private const val CHANNEL = "capture"
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var bg: Handler? = null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null          // 只在 bg 執行緒存取

    private lateinit var wm: WindowManager
    private var overlay: View? = null
    private var countView: TextView? = null
    private var autoView: TextView? = null

    private var autoOn = false
    private var intervalMs = 1500L
    private val count = AtomicInteger(0)
    private var lastSig: IntArray? = null      // 只在 bg 執行緒存取
    private val folder = "Pictures/PocketShots/" +
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private val autoRunnable = object : Runnable {
        override fun run() {
            if (!autoOn) return
            capture(manual = false)
            main.postDelayed(this, intervalMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY  // 已在執行

        startAsForeground()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val sec = intent?.getFloatExtra(EXTRA_INTERVAL, 1.5f) ?: 1.5f
        intervalMs = (sec * 1000).toLong().coerceAtLeast(500L)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        if (p == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        setupCapture(p)
        showOverlay()
        return START_NOT_STICKY
    }

    // ---------- 前景通知 ----------
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "截圖服務", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Pocket 截圖助手執行中")
            .setContentText("點「停止」結束")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    // ---------- 螢幕擷取 ----------
    private fun setupCapture(p: MediaProjection) {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        val w = m.widthPixels
        val h = m.heightPixels

        val t = HandlerThread("capture").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        bg = handler

        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader = r
        r.setOnImageAvailableListener({ ir ->
            val img: Image? = try {
                ir.acquireLatestImage()
            } catch (e: IllegalStateException) {
                null
            }
            if (img != null) {
                latest?.close()
                latest = img
            }
        }, handler)

        virtualDisplay = p.createVirtualDisplay(
            "pocketshot", w, h, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
    }

    private fun capture(manual: Boolean) {
        val handler = bg ?: return
        // 先把浮動按鈕藏起來，避免被拍進去
        overlay?.visibility = View.INVISIBLE
        main.postDelayed({
            handler.post {
                val img = latest
                val bmp = if (img != null) toBitmap(img) else null
                main.post { overlay?.visibility = View.VISIBLE }
                if (bmp != null) {
                    val dup = isDuplicate(bmp)
                    if (manual || !dup) {
                        val n = count.incrementAndGet()
                        save(bmp, n)
                        main.post { countView?.text = "$n 張" }
                    }
                    bmp.recycle()
                }
            }
        }, 250)
    }

    private fun toBitmap(img: Image): Bitmap? {
        return try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val ps = plane.pixelStride
            val rs = plane.rowStride
            val w = img.width
            val h = img.height
            if (ps != 4) return null
            val bytes = ByteArray(w * h * 4)
            for (y in 0 until h) {
                buf.position(y * rs)
                buf.get(bytes, y * w * 4, w * 4)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            bmp
        } catch (e: Exception) {
            null
        }
    }

    /** 縮小成 24x48 的灰階指紋，和上一張比對；幾乎一樣就算重複 */
    private fun isDuplicate(b: Bitmap): Boolean {
        val sw = 24
        val sh = 48
        val small = Bitmap.createScaledBitmap(b, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        small.recycle()
        val sig = IntArray(px.size) { i ->
            val c = px[i]
            (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10
        }
        val prev = lastSig
        lastSig = sig
        if (prev == null) return false
        var diff = 0L
        for (i in sig.indices) diff += abs(sig[i] - prev[i])
        return diff / sig.size < 2
    }

    private fun save(b: Bitmap, n: Int) {
        try {
            val name = String.format(Locale.US, "pocket_%04d.jpg", n)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, folder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return
            contentResolver.openOutputStream(uri)?.use { out ->
                b.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            main.post { Toast.makeText(this, "儲存失敗：${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    // ---------- 浮動按鈕 ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.argb(220, 20, 32, 46))
            }
        }

        fun item(label: String, onClick: (() -> Unit)?): TextView {
            val tv = TextView(this)
            tv.text = label
            tv.setTextColor(Color.WHITE)
            tv.textSize = 16f
            tv.setPadding(dp(10), dp(8), dp(10), dp(8))
            if (onClick != null) tv.setOnClickListener { onClick() }
            bar.addView(tv)
            return tv
        }

        val handle = item("⠿", null)
        item("📷") { capture(manual = true) }
        autoView = item("▶ 自動") { toggleAuto() }
        countView = item("0 張", null)
        item("✕") { stopSelf() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }

        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = sx + (e.rawX - tx).toInt()
                    params.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(bar, params)
                }
            }
            true
        }

        wm.addView(bar, params)
        overlay = bar
    }

    private fun toggleAuto() {
        autoOn = !autoOn
        autoView?.text = if (autoOn) "⏸ 暫停" else "▶ 自動"
        main.removeCallbacks(autoRunnable)
        if (autoOn) main.post(autoRunnable)
    }

    override fun onDestroy() {
        autoOn = false
        main.removeCallbacksAndMessages(null)
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
        virtualDisplay?.release()
        virtualDisplay = null
        val p = projection
        projection = null
        try { p?.stop() } catch (_: Exception) {}
        val r = reader
        bg?.post {
            latest?.close()
            latest = null
            r?.close()
        }
        thread?.quitSafely()
        super.onDestroy()
    }
}
