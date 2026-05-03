# 百度 OCR 悬浮识别翻译技术设计文档

## 1. 文档目标

本文档用于承接根目录需求文档 `REQUIREMENTS_BAIDU_OCR_FLOATING_TRANSLATION.md`，输出可落地的 Android 技术实现方案，覆盖以下目标：

- 悬浮可拖动按钮
- 点击后截取当前屏幕
- 使用百度 OCR SDK 识别繁体中文与文字位置
- 使用 OpenCC 将繁体转换为简体
- 在原文字区域覆盖显示简体结果
- 支持横排和基础竖排文本

本文档聚焦本项目的一期实现，不追求复杂排版和连续实时识别。

## 2. 总体方案

### 2.1 核心思路

功能采用“悬浮入口 + 截图识别 + 覆盖渲染”的分层方案：

1. 通过系统悬浮窗展示一个可拖动按钮。
2. 用户点击按钮后，借助 `MediaProjection` 获取当前屏幕截图。
3. 将截图送入百度 OCR SDK，获取文字内容与位置信息。
4. 将识别结果中的繁体中文通过 OpenCC 转换为简体。
5. 根据 OCR 返回坐标，构建结果覆盖层，并在对应区域绘制简体文本。
6. 覆盖层绘制时优先保持原排版方向，支持横排和基础竖排。

### 2.2 总体架构

建议按以下模块拆分：

- `MainActivity`
  - 权限入口
  - 启动/停止悬浮功能
  - 触发截图授权
- `FloatingOverlayService`
  - 前台服务或常驻服务入口
  - 管理悬浮按钮与结果覆盖层
- `FloatingButtonView`
  - 负责按钮展示、拖动、点击
- `ScreenCaptureCoordinator`
  - 封装 `MediaProjection`、`VirtualDisplay`、`ImageReader`
  - 输出一张当前屏幕截图
- `BaiduOcrRepository`
  - 封装 SDK 初始化和 OCR 调用
- `TextConverter`
  - 封装 OpenCC 繁转简能力
- `OverlayLayoutEngine`
  - 处理 OCR 坐标、方向、文本排版和字号适配
- `TranslationOverlayView`
  - 真正负责将简体文本绘制到悬浮覆盖层上

## 3. 模块设计

### 3.1 MainActivity

职责：

- 检查并申请悬浮窗权限。
- 引导用户完成屏幕截图授权。
- 初始化百度 OCR。
- 启动悬浮服务。

建议：

- 不把识别逻辑直接堆在 `Activity` 中。
- `Activity` 主要承担授权和功能开关入口。

关键状态：

- 悬浮窗权限是否已获取。
- 截图授权结果是否可用。
- OCR 初始化是否完成。

### 3.2 FloatingOverlayService

职责：

- 持有悬浮按钮和结果覆盖层的窗口实例。
- 接收按钮点击事件并串行触发识别流程。
- 管理识别任务状态，避免并发点击。

建议：

- 使用前台服务，降低系统回收风险。
- 统一由服务持有 `WindowManager`。
- 按钮层与结果层拆分为两个 View，便于分别控制点击穿透与显示时机。

服务内状态机建议：

- `Idle`
- `Capturing`
- `Recognizing`
- `Rendering`
- `Failed`

同一时刻只允许一个任务处于 `Capturing` 到 `Rendering` 之间。

### 3.3 FloatingButtonView

职责：

- 显示按钮
- 处理拖动
- 处理点击

实现建议：

- 用 `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` 添加。
- 通过触摸事件区分“点击”和“拖动”：
  - 位移小于阈值视为点击
  - 位移大于阈值视为拖动
- 拖动时更新悬浮窗坐标

推荐能力：

- 识别中显示忙碌态
- 支持边缘吸附
- 支持记住最后位置

### 3.4 ScreenCaptureCoordinator

职责：

- 统一封装截图链路
- 输出 `Bitmap` 或等效图像对象

实现建议：

- 使用 `MediaProjectionManager` 申请授权。
- 授权成功后创建 `MediaProjection`。
- 通过 `ImageReader` + `VirtualDisplay` 获取当前屏幕帧。
- 从图像缓冲区转换得到 `Bitmap`。

注意点：

- 截图前临时隐藏结果覆盖层，避免覆盖内容被再次识别。
- 可视情况在截图瞬间隐藏悬浮按钮，避免按钮进入 OCR 图像。
- 需处理屏幕旋转、状态栏、导航栏和像素密度。

输出建议：

- 统一输出原始屏幕坐标系下的截图尺寸。
- 记录以下信息：
  - `bitmapWidth`
  - `bitmapHeight`
  - `screenWidth`
  - `screenHeight`
  - `densityDpi`

### 3.5 BaiduOcrRepository

职责：

- 初始化百度 OCR SDK
- 执行单次识别
- 将 SDK 结果转换为本项目内部数据结构

初始化方案：

- 开发调试阶段可先使用 `AK/SK`
- 正式版本建议使用 `assets/aip-ocr.license`

建议封装的数据模型：

```kotlin
data class OcrTextBlock(
    val text: String,
    val polygon: List<PointF>,
    val boundingBox: RectF,
    val directionHint: TextDirectionHint?
)

enum class TextDirectionHint {
    HORIZONTAL,
    VERTICAL,
    UNKNOWN
}
```

接口建议：

```kotlin
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): List<OcrTextBlock>
}
```

识别接口选择建议：

- 优先选择百度 OCR 中能返回文字位置的能力。
- 如果 SDK 提供“含位置版”或多点坐标结果，优先使用。
- 本期不追求表格、文档结构化等复杂能力。

### 3.6 TextConverter

职责：

- 将 OCR 输出的繁体文本转换为简体文本

建议接口：

```kotlin
interface TextConverter {
    fun toSimplified(text: String): String
}
```

实现建议：

- 单独封装 OpenCC，不让业务层直接依赖第三方 API。
- 以“文本块”为粒度调用 OpenCC。
- 对空文本、纯数字、纯符号直接短路返回。

建议补充一个简单过滤：

- 若文本中不包含 CJK 字符，可直接原样返回。

### 3.7 OverlayLayoutEngine

职责：

- 将 OCR 坐标转换为覆盖层坐标
- 判断横排或竖排
- 计算字号、换行、绘制方向

输入：

- 屏幕截图尺寸
- OCR 文本块坐标
- 转换后的简体文本

输出：

- 可直接绘制的布局结果

建议内部数据结构：

```kotlin
data class OverlayRenderBlock(
    val text: String,
    val area: RectF,
    val direction: RenderDirection,
    val textSizePx: Float,
    val lineCount: Int
)

enum class RenderDirection {
    HORIZONTAL,
    VERTICAL
}
```

### 3.8 TranslationOverlayView

职责：

- 绘制遮罩背景
- 绘制简体文本
- 按方向渲染横排和基础竖排

实现建议：

- 基于自定义 `View` 的 `Canvas` 绘制实现一期功能。
- 绘制逻辑尽量纯函数化，避免在 `onDraw` 内做重识别或重转换。
- 结果层使用透明背景，仅在文字块区域内绘制遮罩与文本。

## 4. 关键流程设计

### 4.1 初始化流程

1. 用户打开主界面。
2. 系统检查悬浮窗权限。
3. 若缺失，跳转系统授权页。
4. 系统请求截图授权。
5. OCR SDK 完成初始化。
6. 启动悬浮服务并显示按钮。

### 4.2 单次识别流程

1. 用户点击悬浮按钮。
2. 服务进入忙碌态，禁用再次点击。
3. 临时隐藏结果层，必要时隐藏按钮。
4. 执行截图并拿到 `Bitmap`。
5. 调用百度 OCR 识别，获得文字块列表。
6. 对每个文字块调用 OpenCC 进行繁转简。
7. 布局引擎根据坐标与方向生成渲染块。
8. 结果层刷新并绘制。
9. 服务回到待机态。

### 4.3 清除结果流程

可选提供以下任一机制：

- 再次点击按钮时先清空旧结果后重新识别
- 长按按钮清空结果
- 新识别结果直接覆盖旧结果

一期推荐：

- 新结果直接替换旧结果，逻辑最简单。

## 5. 坐标与绘制设计

### 5.1 坐标系统一

必须统一三个坐标系：

- 系统真实屏幕坐标
- 截图 `Bitmap` 坐标
- 覆盖层 `View` 坐标

一期建议：

- 截图分辨率尽量与当前屏幕像素一致。
- 结果覆盖层铺满全屏。
- OCR 返回坐标直接映射到覆盖层，减少额外缩放误差。

若无法做到完全一致，则统一通过比例换算：

```text
overlayX = ocrX * overlayWidth / bitmapWidth
overlayY = ocrY * overlayHeight / bitmapHeight
```

### 5.2 文本区域定义

优先使用 OCR 返回的矩形框或四边形框。

策略：

- 如果只有矩形框，直接以矩形为覆盖区域。
- 如果有四点坐标，一期先取外接矩形。
- 后续如需更高贴合度，再引入多边形裁剪。

### 5.3 遮罩绘制

建议每个文本块先绘制一层半透明浅色或纯色底。

目的：

- 压住原繁体文字
- 提高简体覆盖文字可读性

建议视觉策略：

- 底色透明度适中，避免过于突兀
- 文本颜色与底色保持高对比
- 可选加细描边增强复杂背景下的清晰度

## 6. 横排与竖排支持设计

### 6.1 横排文本

判断方式：

- OCR 提供方向信息时直接使用
- 若无方向信息，宽大于高且字符分布横向更宽时判为横排

排版策略：

- 优先单行显示
- 区域不足时允许换行
- 字号以“尽量填满区域且不裁切”为原则

### 6.2 基础竖排文本

本期基础竖排的技术定义：

- 一个文本块整体方向较稳定
- 文本框高明显大于宽
- 字符数量不大，或可按逐字竖向排布实现

判断策略：

- OCR 明确给出竖排方向时优先采纳
- 无明确信息时，满足以下条件之一可判为竖排：
  - 高宽比超过阈值
  - 文本较短且区域狭长
  - 多个字符在纵向排列更自然

绘制策略：

- 一期采用“逐字竖排”即可，不做复杂字形旋转规则。
- 标点、数字、英文不追求完全符合传统排版。
- 如判定不稳定，降级为横排覆盖。

### 6.3 基础竖排示意

例如 OCR 返回文本 `學校公告`，若被判定为竖排，可绘制为：

```text
学
校
公
告
```

这类方案的优点是实现简单、稳定，缺点是排版美观度一般，但满足一期“可读优先”。

## 7. 线程与并发设计

### 7.1 线程划分

- 主线程：
  - 按钮交互
  - 悬浮层展示
  - 结果层刷新
- 后台线程：
  - 截图数据转换
  - OCR 调用
  - OpenCC 转换
  - 布局计算

建议使用协程：

- `Dispatchers.Main` 处理 UI
- `Dispatchers.IO` 处理 OCR 与文本转换
- 必要时单独串行调度识别任务

### 7.2 并发控制

为避免重复点击造成多个截图任务并发，建议：

- 服务层持有 `isProcessing` 标记或互斥锁
- 新点击到来时：
  - 若当前正在处理，直接忽略
  - 或提示“识别中”

一期推荐直接忽略重复点击。

## 8. 权限与系统能力设计

### 8.1 悬浮窗权限

用途：

- 显示悬浮按钮
- 显示结果覆盖层

实现要点：

- 通过 `Settings.canDrawOverlays(context)` 检查
- 未授权时跳转 `ACTION_MANAGE_OVERLAY_PERMISSION`

### 8.2 截图授权

用途：

- 通过 `MediaProjection` 获取屏幕图像

实现要点：

- 由 `MediaProjectionManager.createScreenCaptureIntent()` 发起授权
- 授权结果交回 `Activity`
- 将授权结果传递给服务或截图协调器

### 8.3 网络权限

用途：

- 调用百度 OCR 在线识别服务

Manifest 至少需要：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## 9. 百度 OCR 接入设计

### 9.1 SDK 初始化时机

建议在应用启动后的功能入口阶段初始化，而不是等用户第一次点击按钮时才初始化。

原因：

- 避免首次识别等待过长
- 便于尽早暴露鉴权错误

### 9.2 鉴权模式选择

开发阶段：

- 可使用 `initAccessTokenWithAkSk`

正式阶段：

- 优先使用 `initAccessToken`
- 配套 `assets/aip-ocr.license`

### 9.3 OCR 结果抽象

不要在 UI 层直接消费百度 SDK 原始对象，统一转换为本项目的数据模型。

好处：

- 降低第三方依赖渗透范围
- 后续切换 OCR 实现时代价更小

## 10. OpenCC 接入设计

### 10.1 转换粒度

一期按“文字块”处理，不做全文上下文纠错。

优点：

- 与 OCR 区域映射关系最直接
- 覆盖绘制更稳定

### 10.2 转换策略

- 对疑似繁体文本进行转换
- 对简体、数字、符号等保持原样
- 不做词典级人工纠错

## 11. 异常与降级设计

### 11.1 权限异常

- 无悬浮窗权限：不启动服务
- 无截图授权：点击后提示重新授权

### 11.2 OCR 异常

- SDK 未初始化：提示初始化失败
- 鉴权失败：提示检查应用配置、包名、签名或 license
- 网络失败：提示网络异常
- 无识别结果：提示未识别到文字

### 11.3 转换异常

- OpenCC 初始化失败：降级为直接显示 OCR 原文
- 单个文本块转换失败：只影响当前块，不影响整体流程

### 11.4 排版异常

- 文本过长放不下：缩小字号后再尝试
- 仍放不下：允许换行
- 再不行：截断显示并保留省略策略
- 竖排判断失败：降级为横排

## 12. 数据结构建议

一期建议至少有以下模型：

```kotlin
data class CaptureFrame(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int
)

data class OcrTextBlock(
    val text: String,
    val polygon: List<PointF>,
    val boundingBox: RectF,
    val directionHint: TextDirectionHint?
)

data class TranslatedBlock(
    val originalText: String,
    val simplifiedText: String,
    val boundingBox: RectF,
    val directionHint: TextDirectionHint?
)

data class OverlayRenderBlock(
    val text: String,
    val area: RectF,
    val direction: RenderDirection,
    val textSizePx: Float,
    val backgroundAlpha: Float
)
```

## 13. 开发拆分建议

建议按以下顺序落地，保证每一步都可验证：

### 阶段 1：悬浮按钮

- 完成悬浮窗权限申请
- 显示可拖动按钮
- 实现点击回调

验收：

- 按钮可显示、拖动、点击

### 阶段 2：截图能力

- 打通 `MediaProjection`
- 点击按钮后拿到屏幕 `Bitmap`

验收：

- 成功截到当前屏幕

### 阶段 3：OCR 接入

- 接入百度 OCR SDK
- 完成初始化与一次识别

验收：

- 能拿到文字内容与位置

### 阶段 4：繁转简

- 接入 OpenCC
- 对 OCR 文本做转换

验收：

- 能输出简体文本

### 阶段 5：覆盖绘制

- 在原区域绘制遮罩和简体文本

验收：

- 横排文本可读、位置基本正确

### 阶段 6：基础竖排

- 加入竖排判断和逐字竖排绘制

验收：

- 基础竖排内容可读

## 14. 风险点

### 14.1 OCR 返回方向信息不足

风险：

- 无法可靠区分横排和竖排

应对：

- 一期通过区域形状和字符分布做启发式判断
- 允许降级为横排

### 14.2 截图中包含自身悬浮层

风险：

- OCR 识别到自己的按钮或覆盖文本

应对：

- 截图前临时隐藏相关 View
- 截图完成后再恢复

### 14.3 识别耗时较长

风险：

- 用户感知卡顿

应对：

- 状态提示
- 后台线程执行
- 后续按需裁剪局部区域

### 14.4 竖排覆盖美观度有限

风险：

- 结果能看但不够自然

应对：

- 一期以可读为准
- 后续再引入更复杂版面排版

## 15. 测试建议

需要重点覆盖以下测试场景：

- 悬浮窗权限已授权 / 未授权
- 截图授权已授权 / 拒绝授权
- 网络正常 / 网络异常
- OCR 初始化成功 / 失败
- 横排繁体文本识别覆盖
- 基础竖排繁体文本识别覆盖
- 多块文本混合排版
- 深色背景 / 浅色背景下的覆盖可读性
- 重复快速点击按钮
- 屏幕旋转或分辨率变化

## 16. 一期实现建议结论

结合当前目标，一期最稳妥的技术路线是：

1. 使用悬浮服务管理按钮与覆盖层。
2. 使用 `MediaProjection` 截取全屏。
3. 使用百度 OCR 返回文字及位置结果。
4. 使用 OpenCC 按文字块做繁转简。
5. 使用自定义覆盖层按矩形区域绘制简体文本。
6. 横排优先准确覆盖，竖排先采用基础逐字竖排策略。

该方案实现成本可控，验证路径清晰，也方便后续继续扩展到局部识别、连续识别和更高精度排版。
