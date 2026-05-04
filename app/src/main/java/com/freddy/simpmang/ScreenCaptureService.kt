package com.freddy.simpmang

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import com.baidu.ocr.sdk.OCR
import com.baidu.ocr.sdk.OnResultListener
import com.baidu.ocr.sdk.exception.OCRError
import com.baidu.ocr.sdk.model.GeneralParams
import com.baidu.ocr.sdk.model.GeneralResult
import com.baidu.ocr.sdk.model.Word
import com.github.houbb.opencc4j.util.ZhConverterUtil
import java.io.File
import java.io.FileOutputStream

/**
 * 每次截图都是短命的一次会话：
 * [CaptureProxyActivity] 拿到授权 -> 把 resultCode/data 用 `ACTION_CAPTURE`
 * 起本服务 -> 本服务在 `onStartCommand` 里一口气：
 *   startForeground(FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
 *   -> getMediaProjection
 *   -> 立刻 createVirtualDisplay 截一帧
 *   -> OCR -> 投递给悬浮层
 *   -> stopForeground + stopSelf
 *
 * Android 14+ 规则：
 *  - MediaProjection token 一次性，不能复用
 *  - getMediaProjection 必须发生在已经 startForeground(mediaProjection) 的服务里
 *  - 同一个 projection 只能 createVirtualDisplay 一次
 */
class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "screen_capture"
        const val NOTIFICATION_ID = 1

        const val ACTION_CAPTURE = "com.freddy.simpmang.ACTION_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        interface Listener {
            fun onCaptureFailed(message: String) {}
            fun onOcrFailed(message: String) {}
            fun onDone() {}
        }

        @Volatile
        private var listener: Listener? = null

        fun setListener(l: Listener?) {
            listener = l
        }

        private fun notifyCaptureFailed(msg: String) {
            listener?.onCaptureFailed(msg)
        }

        private fun notifyOcrFailed(msg: String) {
            listener?.onOcrFailed(msg)
        }

        private fun notifyDone() {
            listener?.onDone()
        }
    }

    private val mediaProjectionManager: MediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "截屏服务",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 getMediaProjection 之前 startForeground 并声明 mediaProjection 类型，
        // 否则 Android 14+ 会立刻 stop 掉 MediaProjection。
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        if (intent?.action != ACTION_CAPTURE) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            notifyCaptureFailed("授权数据无效")
            finishSession()
            return START_NOT_STICKY
        }

        val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
        if (projection == null) {
            notifyCaptureFailed("无法获取 MediaProjection")
            finishSession()
            return START_NOT_STICKY
        }

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 系统/我们主动停了，不再做任何事
            }
        }, mainHandler)

        runCapture(projection)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runCapture(projection: MediaProjection) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val thread = HandlerThread("ScreenCapture").apply { start() }
        val captureHandler = Handler(thread.looper)

        var captured = false
        var virtualDisplay: VirtualDisplay? = null

        fun teardown() {
            try { virtualDisplay?.release() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            try { projection.stop() } catch (_: Exception) {}
            thread.quitSafely()
        }

        reader.setOnImageAvailableListener({ r ->
            if (captured) return@setOnImageAvailableListener
            captured = true

            val image = r.acquireLatestImage()
            if (image == null) {
                teardown()
                mainHandler.post {
                    notifyCaptureFailed("无法获取图像")
                    finishSession()
                }
                return@setOnImageAvailableListener
            }

            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val full = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height, Bitmap.Config.ARGB_8888
                )
                full.copyPixelsFromBuffer(buffer)

                val bitmap = if (rowPadding > 0) {
                    val cropped = Bitmap.createBitmap(full, 0, 0, width, height)
                    full.recycle()
                    cropped
                } else {
                    full
                }
                image.close()

                mainHandler.post {
                    val file = File(
                        cacheDir,
                        "ocr-screenshot-${System.currentTimeMillis()}.jpg"
                    )
                    FileOutputStream(file).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                    }
                    bitmap.recycle()
                    teardown()
                    recognize(file)
                }
            } catch (e: Exception) {
                image.close()
                teardown()
                mainHandler.post {
                    notifyCaptureFailed(e.message ?: "未知错误")
                    finishSession()
                }
            }
        }, captureHandler)

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null, null
            )
        } catch (e: Exception) {
            teardown()
            mainHandler.post {
                notifyCaptureFailed(e.message ?: "无法创建 VirtualDisplay")
                finishSession()
            }
        }
    }

    private fun recognize(imageFile: File) {
        val params = GeneralParams().apply {
            setDetectDirection(true)
            setVertexesLocation(true)
            setRecognizeGranularity(GeneralParams.GRANULARITY_SMALL)
            setImageFile(imageFile)
        }

        OCR.getInstance(applicationContext).recognizeGeneral(
            params,
            object : OnResultListener<GeneralResult> {
                override fun onResult(result: GeneralResult) {
                    mainHandler.post {
                        val blocks = buildBlocks(result)
                        if (blocks.isNotEmpty()) {
                            FloatingButtonService.postResult(blocks)
                        }
                        finishSession()
                    }
                }

                override fun onError(error: OCRError) {
                    mainHandler.post {
                        notifyOcrFailed(error.message ?: "未知错误")
                        finishSession()
                    }
                }
            }
        )
    }

    private fun finishSession() {
        notifyDone()
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    private fun buildBlocks(result: GeneralResult): List<FloatingButtonService.OverlayBlock> {
        val wordList = result.wordList ?: return emptyList()
        val blocks = mutableListOf<FloatingButtonService.OverlayBlock>()
        for (wordSimple in wordList) {
            if (wordSimple !is Word) continue
            val location = wordSimple.location ?: continue
            val original = wordSimple.words.orEmpty()
            val simplified = try {
                ZhConverterUtil.toSimple(original)
            } catch (_: Exception) {
                original
            }
            blocks.add(
                FloatingButtonService.OverlayBlock(
                    text = simplified,
                    left = location.left,
                    top = location.top,
                    width = location.width,
                    height = location.height
                )
            )
        }
        return blocks
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("正在截屏并识别")
            .setContentText("完成后会自动停止")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
