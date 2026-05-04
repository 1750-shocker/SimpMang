package com.freddy.simpmang

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.baidu.ocr.sdk.OCR
import com.baidu.ocr.sdk.OnResultListener
import com.baidu.ocr.sdk.exception.OCRError
import com.baidu.ocr.sdk.model.AccessToken

/**
 * 透明代理 Activity：
 * 1. 保证 OCR 已初始化
 * 2. 弹一次 MediaProjection 授权
 * 3. 授权成功后把 resultCode/data 以 `ACTION_CAPTURE` 启动 [ScreenCaptureService]
 * 4. 注册 [ScreenCaptureService.Listener]，等服务 onDone 后 finish
 */
class CaptureProxyActivity : ComponentActivity() {

    private val mediaProjectionManager: MediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val captureListener = object : ScreenCaptureService.Companion.Listener {
        override fun onCaptureFailed(message: String) {
            runOnUiThread {
                Toast.makeText(
                    this@CaptureProxyActivity,
                    "截图失败：$message",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        override fun onOcrFailed(message: String) {
            runOnUiThread {
                Toast.makeText(
                    this@CaptureProxyActivity,
                    "识别失败：$message",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        override fun onDone() {
            runOnUiThread { finishAndNotify() }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val code = result.resultCode
        val data = result.data
        if (code != RESULT_OK || data == null) {
            Toast.makeText(this, "截图权限被拒绝", Toast.LENGTH_SHORT).show()
            finishAndNotify()
            return@registerForActivityResult
        }
        // 把授权结果交给服务；服务会在 onStartCommand 内一口气完成截图
        ScreenCaptureService.setListener(captureListener)
        val svcIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_CAPTURE
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, code)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(svcIntent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ensureOcrReady()) {
            return
        }
        requestScreenCapture()
    }

    override fun onDestroy() {
        ScreenCaptureService.setListener(null)
        super.onDestroy()
    }

    private fun requestScreenCapture() {
        permissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun finishAndNotify() {
        FloatingButtonService.instance?.onCaptureDone()
        ScreenCaptureService.setListener(null)
        finish()
    }

    private fun ensureOcrReady(): Boolean {
        val ocr = OCR.getInstance(applicationContext)
        if (ocr.accessToken != null) return true

        val apiKey = BuildConfig.BAIDU_OCR_API_KEY
        val secretKey = BuildConfig.BAIDU_OCR_SECRET_KEY
        if (apiKey.isBlank() || secretKey.isBlank()) {
            Toast.makeText(
                this,
                "请在 local.properties 中配置 baidu.ocr.apiKey 和 baidu.ocr.secretKey",
                Toast.LENGTH_LONG
            ).show()
            finishAndNotify()
            return false
        }

        Toast.makeText(this, "正在初始化 OCR...", Toast.LENGTH_SHORT).show()
        ocr.initAccessTokenWithAkSk(
            object : OnResultListener<AccessToken> {
                override fun onResult(result: AccessToken) {
                    runOnUiThread { requestScreenCapture() }
                }

                override fun onError(error: OCRError) {
                    runOnUiThread {
                        Toast.makeText(
                            this@CaptureProxyActivity,
                            "OCR 初始化失败：${error.message ?: "未知错误"}",
                            Toast.LENGTH_LONG
                        ).show()
                        finishAndNotify()
                    }
                }
            },
            applicationContext,
            apiKey,
            secretKey
        )
        return false
    }
}
