package com.freddy.simpmang

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.remember
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

class MainActivity : ComponentActivity() {
    private var statusText by mutableStateOf(
        "先在 local.properties 中配置 baidu.ocr.apiKey 和 baidu.ocr.secretKey，然后初始化 OCR。"
    )
    private var resultText by mutableStateOf("")
    private var isInitializing by mutableStateOf(false)
    private var isRecognizing by mutableStateOf(false)
    private var isOcrReady by mutableStateOf(false)

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
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        OCR.getInstance(applicationContext).release()
        super.onDestroy()
    }

    private fun initializeOcr() {
        if (isInitializing) {
            return
        }

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

    private fun recognizeImage(uri: Uri) {
        if (isRecognizing) {
            return
        }
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
                        statusText = "识别完成，共 ${result.wordsResultNumber} 段，direction=${result.direction}"
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

    private fun copyUriToCache(uri: Uri): File {
        val targetFile = File(cacheDir, "ocr-input-${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri)?.use { inputStream ->
            targetFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: error("无法打开所选图片")
        return targetFile
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
            text = "当前只验证最小链路：AK/SK 鉴权 + 选图识别 + 返回文字位置。",
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
            onClick = { pickerLauncher.launch("image/*") },
            enabled = isOcrReady && !isInitializing && !isRecognizing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isRecognizing) "识别中..." else "选择图片并识别")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "状态", style = MaterialTheme.typography.titleMedium)
                Text(text = statusText, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            resultText = "1. 測試文字  [left=12, top=24, width=180, height=42]",
            isInitializing = false,
            isRecognizing = false,
            isOcrReady = true,
            onInitClick = {},
            onImageSelected = {}
        )
    }
}
