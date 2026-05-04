package com.freddy.simpmang

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

class FloatingButtonService : Service() {

    companion object {
        const val NOTIFICATION_ID = 2
        @Volatile
        var instance: FloatingButtonService? = null
            private set

        fun postResult(blocks: List<OverlayBlock>) {
            instance?.showOverlay(blocks)
        }
    }

    data class OverlayBlock(
        val text: String,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int
    )

    private lateinit var windowManager: WindowManager
    private var buttonView: View? = null
    private var overlayView: View? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var isProcessing = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        showButton()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeButton()
        removeOverlay()
        instance = null
        // 悬浮按钮关闭时，一并终止 MediaProjection 会话
        stopService(Intent(this, ScreenCaptureService::class.java))
        super.onDestroy()
    }

    fun onCaptureDone() {
        isProcessing = false
        buttonView?.visibility = View.VISIBLE
    }

    private fun showButton() {
        val size = dpToPx(52f)
        buttonParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dpToPx(12f)
            y = resources.displayMetrics.heightPixels / 3
        }

        val button = object : View(this) {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E65F5F5F")
                style = Paint.Style.FILL
            }
            private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = dpToPx(18f).toFloat()
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val cx = width / 2f
                val cy = height / 2f
                val radius = width / 2f - dpToPx(2f).toFloat()
                canvas.drawCircle(cx, cy, radius, bgPaint)
                canvas.drawText("OCR", cx, cy + iconPaint.textSize / 3f, iconPaint)
            }
        }

        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = buttonParams!!.x
                    initialY = buttonParams!!.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    buttonParams!!.x = (initialX + event.rawX - touchStartX).toInt()
                    buttonParams!!.y = (initialY + event.rawY - touchStartY).toInt()
                    windowManager.updateViewLayout(button, buttonParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - touchStartX) < 10f &&
                        abs(event.rawY - touchStartY) < 10f
                    ) {
                        handleButtonClick()
                    }
                    true
                }
                else -> false
            }
        }

        buttonView = button
        windowManager.addView(button, buttonParams)
    }

    private fun handleButtonClick() {
        if (isProcessing) return
        isProcessing = true
        removeOverlay()
        buttonView?.visibility = View.INVISIBLE

        // Android 14+ 禁止复用 MediaProjection token，每次点击都需要重新授权。
        // 通过透明代理 Activity 弹出授权，然后截一次、识别一次、释放。
        val intent = Intent(this, CaptureProxyActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        startActivity(intent)
    }

    private fun showOverlay(blocks: List<OverlayBlock>) {
        removeOverlay()

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )

        val overlay = object : View(this) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#DDFFFFFF")
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
            }
            var renderBlocks = blocks

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                for (block in renderBlocks) {
                    val rect = RectF(
                        block.left.toFloat(), block.top.toFloat(),
                        (block.left + block.width).toFloat(),
                        (block.top + block.height).toFloat()
                    )
                    canvas.drawRect(rect, bgPaint)

                    var textSize = dpToPx(14f).toFloat()
                    while (textSize > dpToPx(8f).toFloat()) {
                        textPaint.textSize = textSize
                        if (textPaint.measureText(block.text) <= block.width * 0.9f &&
                            textPaint.descent() - textPaint.ascent() <= block.height * 0.85f
                        ) break
                        textSize -= 1f
                    }
                    textPaint.textSize = textSize
                    val textY =
                        rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                    canvas.drawText(block.text, rect.centerX(), textY, textPaint)
                }
            }
        }

        overlayView = overlay
        windowManager.addView(overlay, overlayParams)
    }

    private fun removeButton() {
        try { buttonView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
    }

    private fun removeOverlay() {
        try { overlayView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        overlayView = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "floating_button", "悬浮OCR", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, "floating_button")
            .setContentTitle("悬浮OCR")
            .setContentText("点击按钮截图识别")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
