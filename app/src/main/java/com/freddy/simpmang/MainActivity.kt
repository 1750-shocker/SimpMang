package com.freddy.simpmang

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.baidu.ocr.sdk.OCR
import com.baidu.ocr.sdk.OnResultListener
import com.baidu.ocr.sdk.exception.OCRError
import com.baidu.ocr.sdk.model.AccessToken
import com.baidu.ocr.sdk.model.GeneralParams
import com.baidu.ocr.sdk.model.GeneralResult
import com.baidu.ocr.sdk.model.Word
import com.baidu.ocr.sdk.model.WordSimple
import com.freddy.simpmang.ui.theme.SimpMangTheme
import com.github.houbb.opencc4j.util.ZhConverterUtil
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private var statusText by mutableStateOf(
        "先在 local.properties 中配置 baidu.ocr.apiKey 和 baidu.ocr.secretKey，然后初始化 OCR。"
    )
    private var resultText by mutableStateOf("")
    private var isInitializing by mutableStateOf(false)
    private var isRecognizing by mutableStateOf(false)
    private var isOcrReady by mutableStateOf(false)

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private var captureProjection: MediaProjection? = null
    private var captureImageReader: ImageReader? = null
    private var captureDisplay: VirtualDisplay? = null

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startScreenCapture(result.resultCode, result.data!!)
        } else {
            statusText = "截图权限被拒绝"
            isRecognizing = false
            stopService(Intent(this, ScreenCaptureService::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SimpMangTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    OcrDebugScreen(
                        statusText = statusText,
                        resultText = resultText,
                        isInitializing = isInitializing,
                        isRecognizing = isRecognizing,
                        isOcrReady = isOcrReady,
                        onInitClick = ::initializeOcr,
                        onImageSelected = ::recognizeImage,
                        onScreenCapture = ::captureScreen,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        cleanupScreenCapture()
        OCR.getInstance(applicationContext).release()
        super.onDestroy()
    }

    private fun initializeOcr() {
        if (isInitializing) return

        val apiKey = BuildConfig.BAIDU_OCR_API_KEY
        val secretKey = BuildConfig.BAIDU_OCR_SECRET_KEY
        if (apiKey.isBlank() || secretKey.isBlank()) {
            statusText = "缺少百度 OCR 凭据。请在 local.properties 中添加 baidu.ocr.apiKey 和 baidu.ocr.secretKey。"
            return
        }

        isInitializing = true
        statusText = "正在初始化 OCR..."
        OCR.getInstance(applicationContext).initAccessTokenWithAkSk(
            object : OnResultListener<AccessToken> {
                override fun onResult(result: AccessToken) {
                    runOnUiThread {
                        isInitializing = false
                        isOcrReady = true
                        val tokenPreview = result.accessToken?.take(12).orEmpty()
                        statusText = "OCR 初始化成功，token 前缀: $tokenPreview"
                    }
                }

                override fun onError(error: OCRError) {
                    runOnUiThread {
                        isInitializing = false
                        isOcrReady = false
                        statusText = buildString {
                            append("OCR 初始化失败")
                            error.message?.takeIf { it.isNotBlank() }?.let {
                                append("：")
                                append(it)
                            }
                        }
                    }
                }
            },
            applicationContext,
            apiKey,
            secretKey
        )
    }

    private fun captureScreen() {
        if (!isOcrReady) {
            statusText = "请先初始化 OCR。"
            return
        }
        if (isRecognizing) return

        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        startForegroundService(serviceIntent)
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        isRecognizing = true
        statusText = "正在截屏..."

        val projection =
            mediaProjectionManager.getMediaProjection(resultCode, data) ?: run {
                isRecognizing = false
                statusText = "截图失败：无法获取 MediaProjection"
                stopService(Intent(this, ScreenCaptureService::class.java))
                return
            }

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                runOnUiThread {
                    isRecognizing = false
                    statusText = "截图服务被系统终止"
                    cleanupScreenCapture()
                }
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val handlerThread = HandlerThread("ScreenCapture")
        handlerThread.start()
        val handler = Handler(handlerThread.looper)

        var captured = false

        imageReader.setOnImageAvailableListener({ reader ->
            if (captured) return@setOnImageAvailableListener
            captured = true

            val image = reader.acquireLatestImage()
            if (image == null) {
                handlerThread.quitSafely()
                runOnUiThread {
                    isRecognizing = false
                    statusText = "截图失败：无法获取图像"
                    cleanupScreenCapture()
                }
                return@setOnImageAvailableListener
            }

            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val fullBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height, Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)

                val bitmap = if (rowPadding > 0) {
                    val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, width, height)
                    fullBitmap.recycle()
                    cropped
                } else {
                    fullBitmap
                }
                image.close()

                runOnUiThread {
                    statusText = "截图完成，正在保存..."
                    val file =
                        File(cacheDir, "ocr-screenshot-${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                    }
                    bitmap.recycle()
                    cleanupScreenCapture()
                    recognizeFile(file)
                }
            } catch (e: Exception) {
                image.close()
                runOnUiThread {
                    isRecognizing = false
                    statusText = "截图失败：${e.message}"
                    cleanupScreenCapture()
                }
            } finally {
                handlerThread.quitSafely()
            }
        }, handler)

        val virtualDisplay = projection.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null, null
        )

        captureProjection = projection
        captureImageReader = imageReader
        captureDisplay = virtualDisplay
    }

    private fun cleanupScreenCapture() {
        try { captureDisplay?.release() } catch (_: Exception) {}
        try { captureImageReader?.close() } catch (_: Exception) {}
        try { captureProjection?.stop() } catch (_: Exception) {}
        captureDisplay = null
        captureImageReader = null
        captureProjection = null
        stopService(Intent(this, ScreenCaptureService::class.java))
    }

    private fun recognizeImage(uri: Uri) {
        if (isRecognizing) return
        if (!isOcrReady) {
            statusText = "请先初始化 OCR。"
            return
        }

        val imageFile = try {
            copyUriToCache(uri)
        } catch (error: Exception) {
            statusText = "读取图片失败：${error.message ?: "未知错误"}"
            return
        }
        recognizeFile(imageFile)
    }

    private fun copyUriToCache(uri: Uri): File {
        val targetFile = File(cacheDir, "ocr-input-${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri)?.use { inputStream ->
            targetFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: error("无法打开所选图片")
        return targetFile
    }

    private fun recognizeFile(imageFile: File) {
        val params = GeneralParams().apply {
            setDetectDirection(true)
            setVertexesLocation(true)
            setRecognizeGranularity(GeneralParams.GRANULARITY_SMALL)
            setImageFile(imageFile)
        }

        isRecognizing = true
        resultText = ""
        statusText = "正在识别图片..."
        OCR.getInstance(applicationContext).recognizeGeneral(
            params,
            object : OnResultListener<GeneralResult> {
                override fun onResult(result: GeneralResult) {
                    runOnUiThread {
                        isRecognizing = false
                        statusText =
                            "识别完成，共 ${result.wordsResultNumber} 段，direction=${result.direction}"
                        resultText = formatResult(result)
                    }
                }

                override fun onError(error: OCRError) {
                    runOnUiThread {
                        isRecognizing = false
                        statusText = buildString {
                            append("识别失败")
                            error.message?.takeIf { it.isNotBlank() }?.let {
                                append("：")
                                append(it)
                            }
                        }
                    }
                }
            }
        )
    }

    private fun formatResult(result: GeneralResult): String {
        if (result.wordList.isNullOrEmpty()) {
            return "未识别到文字。"
        }

        return buildString {
            result.wordList.forEachIndexed { index, word ->
                val original = word.words.orEmpty()
                append(index + 1)
                append(". ")
                append(original)
                val simplified = try {
                    ZhConverterUtil.toSimple(original)
                } catch (_: Exception) {
                    original
                }
                if (simplified != original) {
                    append("  →  ")
                    append(simplified)
                }
                if (word is Word) {
                    word.location?.let { location ->
                        append("  [left=")
                        append(location.left)
                        append(", top=")
                        append(location.top)
                        append(", width=")
                        append(location.width)
                        append(", height=")
                        append(location.height)
                        append(']')
                    }
                }
                appendLine()
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun OcrDebugScreen(
    statusText: String,
    resultText: String,
    isInitializing: Boolean,
    isRecognizing: Boolean,
    isOcrReady: Boolean,
    onInitClick: () -> Unit,
    onImageSelected: (Uri) -> Unit,
    onScreenCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            if (uri != null) {
                onImageSelected(uri)
            }
        }
    )
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "百度 OCR SDK 调试页",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "验证链路：AK/SK 鉴权 → 截图/选图 → OCR → 繁转简。",
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = onInitClick,
            enabled = !isInitializing && !isRecognizing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isInitializing) "初始化中..." else "初始化 OCR")
        }

        Button(
            onClick = onScreenCapture,
            enabled = isOcrReady && !isInitializing && !isRecognizing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isRecognizing) "处理中..." else "截图识别")
        }

        Button(
            onClick = { pickerLauncher.launch("image/*") },
            enabled = isOcrReady && !isInitializing && !isRecognizing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isRecognizing) "处理中..." else "选择图片并识别")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "状态", style = MaterialTheme.typography.titleMedium)
                Text(text = statusText, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "识别结果", style = MaterialTheme.typography.titleMedium)
                if (resultText.isBlank()) {
                    Text(text = "暂无结果", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        text = resultText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "local.properties 示例：\nbaidu.ocr.apiKey=你的AK\nbaidu.ocr.secretKey=你的SK",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Preview(showBackground = true)
@androidx.compose.runtime.Composable
fun GreetingPreview() {
    SimpMangTheme {
        OcrDebugScreen(
            statusText = "状态示例",
            resultText = "1. 測試文字 → 测试文字  [left=12, top=24, width=180, height=42]",
            isInitializing = false,
            isRecognizing = false,
            isOcrReady = true,
            onInitClick = {},
            onImageSelected = {},
            onScreenCapture = {}
        )
    }
}
