package com.pocketshot.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val reqCapture = 1001
    private lateinit var status: TextView
    private lateinit var intervalInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun addText(t: String, size: Float = 15f): TextView {
            val v = TextView(ctx)
            v.text = t
            v.textSize = size
            v.setPadding(0, pad / 2, 0, pad / 2)
            root.addView(v)
            return v
        }

        fun addButton(t: String, onClick: () -> Unit) {
            val b = Button(ctx)
            b.text = t
            b.setOnClickListener { onClick() }
            root.addView(b)
        }

        addText("Pocket 截圖助手", 22f)
        addText(
            "用法：開始後畫面上會出現一個浮動按鈕。\n" +
            "📷 = 立刻截一張\n" +
            "▶ 自動 = 每隔幾秒自動截圖（你自己慢慢往下滑卡片收藏頁）\n" +
            "畫面沒變化時會自動略過，不會存一堆重複圖。\n" +
            "截圖存在：相簿 / Pictures / PocketShots"
        )
        status = addText("")

        addButton("① 允許「顯示在其他應用程式上層」") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            addButton("② 允許通知（用來顯示「停止」按鈕）") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }

        addText("自動截圖間隔（秒）：")
        intervalInput = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("1.5")
        }
        root.addView(intervalInput)

        addButton("▶ 開始（系統會詢問螢幕錄製權限，請選「整個螢幕」）") { startCapture() }
        addButton("■ 停止") {
            stopService(Intent(ctx, CaptureService::class.java))
            Toast.makeText(ctx, "已停止", Toast.LENGTH_SHORT).show()
        }

        setContentView(ScrollView(ctx).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (Settings.canDrawOverlays(this))
            "✅ 浮動按鈕權限已開啟" else "⚠ 請先按 ① 開啟浮動按鈕權限"
    }

    private fun startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "請先開啟 ① 浮動按鈕權限", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "沒有取得螢幕錄製權限", Toast.LENGTH_SHORT).show()
            return
        }
        val seconds = intervalInput.text.toString().toFloatOrNull() ?: 1.5f
        val svc = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data)
            .putExtra(CaptureService.EXTRA_INTERVAL, seconds)
        startForegroundService(svc)
        Toast.makeText(this, "已開始，切到遊戲吧！", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }
}
