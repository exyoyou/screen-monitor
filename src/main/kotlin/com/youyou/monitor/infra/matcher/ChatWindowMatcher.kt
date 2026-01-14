package com.youyou.monitor.infra.matcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.youyou.monitor.core.domain.model.MatchResult
import com.youyou.monitor.core.domain.repository.ConfigRepository
import com.youyou.monitor.core.matcher.TemplateMatcher
import com.youyou.monitor.infra.logger.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.max
import kotlin.math.min

/**
 * 聊天窗口专用多尺度匹配器
 *
 * 针对聊天应用窗口的特点优化：
 * - 聊天窗口通常有固定的UI布局模式
 * - 支持多区域分层匹配（头像区、消息区、时间戳区）
 * - 自适应不同屏幕分辨率和密度
 * - 优化的多尺度策略，针对聊天窗口的常见缩放比例
 * - 增强的相似度计算，考虑聊天窗口的特征
 *
 * 性能优化：
 * - 更精细的多尺度步长
 * - 区域优先匹配策略
 * - 早停机制优化
 */
class ChatWindowMatcher(
    private val context: Context,
    private val configRepository: ConfigRepository
) : TemplateMatcher {

    private val TAG = "ChatWindowMatcher"

    // 线程安全：ReadWriteLock 保护模板数据
    private val lock = ReentrantReadWriteLock()
    private var templateGrays: List<Mat> = emptyList()
    private var templateNames: List<String> = emptyList()

    // 优化后的聊天窗口专用尺度数组 - 减少数量，提高性能
    private val chatScales = floatArrayOf(
        1.0f,    // 原尺寸
        0.9f,    // 适中缩放
        0.8f,    // 显著缩放
        0.75f,   // 聊天窗口常见缩放
        0.7f,    // 跨设备缩放
        1.1f     // 轻微放大
    )

    // 区域权重 - 聊天窗口不同区域的重要性
    private data class RegionWeight(
        val rect: Rect,    // 相对矩形区域 (0-1坐标)
        val weight: Double // 权重
    )

    companion object {
        private const val WEAK_MATCH_OFFSET = 0.03 // 聊天窗口弱匹配阈值放宽
        private const val EARLY_EXIT_OFFSET = 0.15 // 早停阈值
        private const val MIN_TEMPLATE_SIZE = 50   // 聊天窗口最小尺寸
        private const val MAX_DIMENSION = 4000     // 支持更高分辨率
        private const val REGION_MATCH_THRESHOLD = 0.75 // 提高区域匹配阈值，减少验证
        private const val REGION_ENHANCE_THRESHOLD = 0.8 // 只有分数>0.8才进行区域增强
    }

    override suspend fun loadTemplates(): Pair<Int, List<String>> {
        val config = configRepository.getCurrentConfig()
        val baseDir = if (config.preferExternalStorage) {
            val ext = File("/storage/emulated/0", config.rootDir)
            if (ext.exists() && ext.canWrite()) {
                ext
            } else {
                File(context.filesDir, config.rootDir)
            }
        } else {
            File(context.filesDir, config.rootDir)
        }

        val templateDir = File(baseDir, config.templateDir).apply {
            if (!exists()) mkdirs()
        }

        Log.d(TAG, "Loading chat window templates from: ${templateDir.absolutePath}")

        if (!templateDir.exists() || !templateDir.isDirectory) {
            Log.e(TAG, "Template directory not found: ${templateDir.absolutePath}")
            return Pair(0, emptyList())
        }

        val files = templateDir.listFiles { f ->
            f.isFile && (f.name.endsWith(".png", ignoreCase = true) ||
                        f.name.endsWith(".jpg", ignoreCase = true))
        } ?: emptyArray()

        val bitmaps = mutableListOf<Bitmap>()
        val names = mutableListOf<String>()

        for (file in files) {
            try {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    bitmaps.add(bmp)
                    names.add(file.name)
                } else {
                    Log.w(TAG, "Failed to decode bitmap: ${file.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading template ${file.name}: ${e.message}")
            }
        }

        // 转换 Bitmap 为灰度 Mat
        val newTemplateGrays = bitmaps.mapNotNull { bmp ->
            convertBitmapToGrayMat(bmp)
        }

        // 线程安全更新
        val oldTemplateGrays = lock.write {
            val old = templateGrays
            templateGrays = newTemplateGrays
            templateNames = names
            old
        }

        // 延迟释放旧模板
        oldTemplateGrays.forEach { it.release() }

        // 回收所有 Bitmap
        bitmaps.forEach { it.recycle() }

        Log.d(TAG, "Loaded ${newTemplateGrays.size} chat window templates: $names")

        return Pair(newTemplateGrays.size, names)
    }

    override suspend fun match(grayMat: Mat): MatchResult? {
        val (templates, names) = lock.read {
            Pair(templateGrays, templateNames)
        }

        if (templates.isEmpty()) {
            Log.w(TAG, "No chat window templates loaded")
            return null
        }

        val config = configRepository.getCurrentConfig()
        val threshold = config.matchThreshold
        val weakThreshold = threshold - WEAK_MATCH_OFFSET

        for ((idx, tmpl) in templates.withIndex()) {
            val templateName = names.getOrNull(idx) ?: "template$idx"
            val templateStartTime = System.currentTimeMillis()

            // 聊天窗口专用多尺度匹配
            var bestScore = Double.NEGATIVE_INFINITY
            var bestScale = 1.0f
            val scaleScores = mutableListOf<Pair<Float, Double>>()

            // 遍历聊天窗口专用尺度
            for (scale in chatScales) {
                val scaledWidth = (tmpl.cols() * scale).toInt()
                val scaledHeight = (tmpl.rows() * scale).toInt()

                if (scaledWidth > grayMat.cols() || scaledHeight > grayMat.rows()) continue
                if (scaledWidth < MIN_TEMPLATE_SIZE || scaledHeight < MIN_TEMPLATE_SIZE) continue

                val score = matchAtScaleWithRegions(tmpl, grayMat, scale)
                scaleScores.add(Pair(scale, score))
                if (score > bestScore) {
                    bestScore = score
                    bestScale = scale
                }
            }

            // 早停优化
            if (bestScore < threshold - EARLY_EXIT_OFFSET) {
                Log.d(TAG, "[$templateName] Skipped detailed check (best=${String.format("%.3f", bestScore)} << threshold)")
                continue
            }

            val templateElapsed = System.currentTimeMillis() - templateStartTime

            // 调试输出
            if (bestScore > threshold - 0.08 && scaleScores.isNotEmpty()) {
                val scoresStr = scaleScores.sortedByDescending { it.second }
                    .take(3)
                    .joinToString(", ") { "${String.format("%.2f", it.first)}=${String.format("%.3f", it.second)}" }
                Log.d(TAG, "[$templateName] ${scaleScores.size} scales in ${templateElapsed}ms, best: [$scoresStr]")
            }

            // 匹配判断
            if (bestScore >= threshold) {
                Log.i(TAG, "✓ Chat window matched: $templateName (score=$bestScore, scale=${String.format("%.2f", bestScale)}, threshold=$threshold)")
                return MatchResult(
                    templateName = templateName,
                    score = bestScore,
                    scale = bestScale,
                    timeMs = templateElapsed,
                    isWeak = false
                )
            } else if (bestScore >= weakThreshold) {
                Log.i(TAG, "⚠ Weak chat window match: $templateName (score=$bestScore, scale=${String.format("%.2f", bestScale)}, threshold=$threshold)")
                return MatchResult(
                    templateName = "weak_$templateName",
                    score = bestScore,
                    scale = bestScale,
                    timeMs = templateElapsed,
                    isWeak = true
                )
            }
        }

        Log.d(TAG, "✗ No chat window match (threshold=$threshold)")
        return null
    }

    override suspend fun reloadTemplates() {
        loadTemplates()
    }

    override fun release() {
        val oldTemplateGrays = lock.write {
            val old = templateGrays
            templateGrays = emptyList()
            templateNames = emptyList()
            old
        }

        oldTemplateGrays.forEach { mat ->
            try {
                mat.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing Mat: ${e.message}")
            }
        }

        Log.d(TAG, "Released all chat window templates")
    }

    /**
     * 聊天窗口专用匹配算法 - 考虑区域权重
     */
    private fun matchAtScaleWithRegions(template: Mat, image: Mat, scale: Float): Double {
        var scaledTmpl: Mat? = null
        var result: Mat? = null
        return try {
            // 缩放模板
            scaledTmpl = if (scale != 1.0f) {
                val scaledWidth = (template.cols() * scale).toInt()
                val scaledHeight = (template.rows() * scale).toInt()
                Mat().apply {
                    val newSize = Size(scaledWidth.toDouble(), scaledHeight.toDouble())
                    Imgproc.resize(template, this, newSize, 0.0, 0.0, Imgproc.INTER_AREA)
                }
            } else {
                template
            }

            // 基础模板匹配
            val resultCols = image.cols() - scaledTmpl.cols() + 1
            val resultRows = image.rows() - scaledTmpl.rows() + 1

            if (resultCols <= 0 || resultRows <= 0) {
                return Double.NEGATIVE_INFINITY
            }

            result = Mat(resultRows, resultCols, CvType.CV_32FC1)
            Imgproc.matchTemplate(image, scaledTmpl, result, Imgproc.TM_CCOEFF_NORMED)

            val mm = Core.minMaxLoc(result)
            var baseScore = mm.maxVal

            // 只有基础分数足够高才进行区域增强，避免不必要的计算
            if (baseScore >= REGION_ENHANCE_THRESHOLD) {
                baseScore = enhanceChatWindowScore(baseScore, scaledTmpl, image, scale, mm.maxLoc)
            }

            baseScore
        } catch (e: Exception) {
            Log.e(TAG, "matchAtScaleWithRegions error at scale=$scale: ${e.message}")
            Double.NEGATIVE_INFINITY
        } finally {
            result?.release()
            if (scale != 1.0f) scaledTmpl?.release()
        }
    }

    /**
     * 聊天窗口分数增强 - 考虑聊天窗口的特征区域
     */
    private fun enhanceChatWindowScore(
        baseScore: Double,
        template: Mat,
        image: Mat,
        scale: Float,
        location: Point
    ): Double {
        try {
            // 定义聊天窗口的关键区域（相对坐标）
            val regions = listOf(
                RegionWeight(Rect(0, 0, template.cols(), (template.rows() * 0.3).toInt()), 1.2), // 顶部区域（通常是标题栏）
                RegionWeight(Rect(0, (template.rows() * 0.7).toInt(), template.cols(), (template.rows() * 0.3).toInt()), 1.1), // 底部区域（通常是输入框）
                RegionWeight(Rect((template.cols() * 0.1).toInt(), (template.rows() * 0.3).toInt(),
                                (template.cols() * 0.8).toInt(), (template.rows() * 0.4).toInt()), 1.0) // 中间消息区域
            )

            var enhancedScore = baseScore
            var totalWeight = 1.0

            for (region in regions) {
                val regionScore = matchRegion(template, image, scale, location, region.rect)
                if (regionScore >= REGION_MATCH_THRESHOLD) {
                    enhancedScore += (regionScore - baseScore) * region.weight * 0.1
                    totalWeight += region.weight * 0.1
                }
            }

            return enhancedScore / totalWeight
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enhance chat window score: ${e.message}")
            return baseScore
        }
    }

    /**
     * 匹配特定区域
     */
    private fun matchRegion(
        template: Mat,
        image: Mat,
        scale: Float,
        location: Point,
        relativeRect: Rect
    ): Double {
        try {
            // 计算绝对区域
            val absX = (location.x + relativeRect.x * scale).toInt()
            val absY = (location.y + relativeRect.y * scale).toInt()
            val absWidth = (relativeRect.width * scale).toInt()
            val absHeight = (relativeRect.height * scale).toInt()

            // 确保区域在图像范围内
            val safeX = max(0, absX)
            val safeY = max(0, absY)
            val safeWidth = min(image.cols() - safeX, absWidth)
            val safeHeight = min(image.rows() - safeY, absHeight)

            if (safeWidth <= 0 || safeHeight <= 0) return 0.0

            val region = Mat(image, Rect(safeX, safeY, safeWidth, safeHeight))
            val templateRegion = Mat(template, relativeRect)

            if (templateRegion.cols() <= 0 || templateRegion.rows() <= 0) {
                region.release()
                return 0.0
            }

            // 区域匹配
            val resultCols = region.cols() - templateRegion.cols() + 1
            val resultRows = region.rows() - templateRegion.rows() + 1

            if (resultCols <= 0 || resultRows <= 0) {
                region.release()
                return 0.0
            }

            val result = Mat(resultRows, resultCols, CvType.CV_32FC1)
            Imgproc.matchTemplate(region, templateRegion, result, Imgproc.TM_CCOEFF_NORMED)

            val mm = Core.minMaxLoc(result)

            region.release()
            result.release()

            return mm.maxVal
        } catch (e: Exception) {
            Log.w(TAG, "Region matching failed: ${e.message}")
            return 0.0
        }
    }

    /**
     * 将 Bitmap 转换为灰度 Mat
     */
    private fun convertBitmapToGrayMat(bmp: Bitmap): Mat? {
        var tmp: Mat? = null
        var resized: Mat? = null
        return try {
            tmp = Mat()
            Utils.bitmapToMat(bmp, tmp)

            val result = if (tmp.cols() > MAX_DIMENSION || tmp.rows() > MAX_DIMENSION) {
                val maxDim = maxOf(tmp.cols(), tmp.rows())
                val scaleFactor = MAX_DIMENSION.toFloat() / maxDim
                resized = Mat()
                val newSize = Size((tmp.cols() * scaleFactor).toDouble(), (tmp.rows() * scaleFactor).toDouble())
                Imgproc.resize(tmp, resized, newSize, 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.cvtColor(resized, resized, Imgproc.COLOR_RGBA2GRAY)
                resized
            } else {
                Imgproc.cvtColor(tmp, tmp, Imgproc.COLOR_RGBA2GRAY)
                tmp
            }

            if (result === tmp) tmp = null else resized = null
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert bitmap to Mat: ${e.message}")
            null
        } finally {
            tmp?.release()
            resized?.release()
        }
    }
}