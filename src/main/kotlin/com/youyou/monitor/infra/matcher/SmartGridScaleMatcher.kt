package com.youyou.monitor.infra.matcher

import android.content.Context
import android.graphics.BitmapFactory
import com.youyou.monitor.core.domain.model.MatchResult
import com.youyou.monitor.core.domain.model.MonitorConfig
import com.youyou.monitor.core.domain.repository.ConfigRepository
import com.youyou.monitor.core.matcher.TemplateMatcher
import com.youyou.monitor.infra.logger.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 智能九宫格+昼夜感知模板匹配器
 * 优化点：
 * 1. 修复 MatchResult 构造参数 (包含 scale, timeMs, isWeak)
 * 2. 加回详细的诊断日志
 * 3. 严格管理 Mat 内存
 */
class SmartGridScaleMatcher(
    private val context: Context,
    private val configRepository: ConfigRepository
) : TemplateMatcher {

    private val TAG = "SmartGridMatcher"
    private val lock = ReentrantReadWriteLock()
    private val scales = floatArrayOf(1.0f, 0.95f, 1.05f)

    private class SmartTemplate(
        val name: String,
        val baseMat: Mat,
        val area: String,
        val mode: String,
        val scaledCache: ConcurrentHashMap<Float, Mat> = ConcurrentHashMap()
    ) {
        fun release() {
            baseMat.release()
            scaledCache.values.forEach { it.release() }
            scaledCache.clear()
        }
    }

    private var activeTemplates = mutableListOf<SmartTemplate>()

    override suspend fun loadTemplates(): Pair<Int, List<String>> {
        val config = configRepository.getCurrentConfig()
        val templateDir = getTemplateDirectory(config)

        Log.d(TAG, "开始加载模板目录: ${templateDir.absolutePath}")

        val files = templateDir.listFiles { f ->
            f.isFile && f.extension.lowercase() == "png"
        } ?: emptyArray()

        val newTemplates = mutableListOf<SmartTemplate>()
        val loadedNames = mutableListOf<String>()

        for (file in files) {
            try {
                val parts = file.nameWithoutExtension.split("_")
                val area = parts.getOrNull(2)?.lowercase() ?: "full"
                val mode = parts.getOrNull(3)?.lowercase() ?: "all"

                val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                val grayMat = Mat()
                Utils.bitmapToMat(bmp, grayMat)
                Imgproc.cvtColor(grayMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
                bmp.recycle()

                newTemplates.add(SmartTemplate(file.nameWithoutExtension, grayMat, area, mode))
                loadedNames.add(file.name)
            } catch (e: Exception) {
                Log.e(TAG, "加载模板出错: ${file.name}", e)
            }
        }

        lock.write {
            activeTemplates.forEach { it.release() }
            activeTemplates.clear()
            activeTemplates.addAll(newTemplates)
        }
        Log.i(TAG, "模板加载完成，共计: ${newTemplates.size} 个")
        return Pair(newTemplates.size, loadedNames)
    }

    override suspend fun match(grayMat: Mat, scale: Int): MatchResult? {
        if (grayMat.empty()) return null
        val config = configRepository.getCurrentConfig()
        val threshold = config.matchThreshold
        val startTime = System.currentTimeMillis()

        return lock.read {
            if (activeTemplates.isEmpty()) {
                Log.v(TAG, "匹配跳过: 模板库为空")
                return@read null
            }

            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val primaryMode = if (hour in 6..18) "light" else "dark"

            // 模式匹配策略：优先匹配当前昼夜模式或通用模式
            val sortedTemplates = activeTemplates.sortedByDescending {
                it.mode == primaryMode || it.mode == "all"
            }

            for (tmpl in sortedTemplates) {
                val roiRect = getRoiRect(grayMat.cols(), grayMat.rows(), tmpl.area)
                val safeRect = calculateSafeRect(grayMat, roiRect)

                // submat 不拷贝像素数据，但需要手动 release 句柄
                val roiMat = grayMat.submat(safeRect)

                val innerMatch = performMultiScaleMatch(tmpl, roiMat, threshold, scale)
                roiMat.release()

                if (innerMatch != null) {
                    val elapsed = System.currentTimeMillis() - startTime

                    Log.i(TAG, "✓ 匹配成功: ${tmpl.name} | 分数: ${String.format("%.3f", innerMatch.score)} | 尺度: ${innerMatch.scale} | 耗时: ${elapsed}ms")

                    // 这里严格对应你报错信息中的参数需求
                    return MatchResult(
                        templateName = tmpl.name,
                        score = innerMatch.score,
                        scale = innerMatch.scale,
                        timeMs = elapsed,
                        isWeak = innerMatch.score < (threshold + 0.1)
                    )
                }
            }
            null
        }
    }

    private fun performMultiScaleMatch(tmpl: SmartTemplate, roi: Mat, threshold: Double, frameScale: Int): InnerMatch? {
        var best: InnerMatch? = null
        var maxScoreSeen = -1.0

        // 根据 frameScale 调整基准尺度
        val baseScale = 1.0f / frameScale.toFloat()

        // 只使用基准尺度，避免不必要的资源浪费
        val scale = baseScale
        val templateAtScale = tmpl.scaledCache.getOrPut(scale) {
            val scaled = Mat()
            Imgproc.resize(tmpl.baseMat, scaled, Size(), scale.toDouble(), scale.toDouble(), Imgproc.INTER_AREA)
            scaled
        }

        if (roi.cols() >= templateAtScale.cols() && roi.rows() >= templateAtScale.rows()) {
            val resultMat = Mat()
            Imgproc.matchTemplate(roi, templateAtScale, resultMat, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(resultMat)
            val maxVal = mm.maxVal
            resultMat.release()

            maxScoreSeen = maxVal

            if (maxVal >= threshold) {
                best = InnerMatch(maxVal, scale)
            }
        }

        // 如果没有达到阈值，打印当前最高分，方便通过 Logcat 调试阈值
        if (best == null && maxScoreSeen > 0.1) {
            Log.v(TAG, "  └─ 未命中 ${tmpl.name}: 最高分 ${String.format("%.3f", maxScoreSeen)} (阈值: $threshold)")
        }

        return best
    }

    private data class InnerMatch(val score: Double, val scale: Float)

    private fun getRoiRect(srcW: Int, srcH: Int, area: String): Rect {
        val w = srcW / 3
        val h = srcH / 3
        return when (area.lowercase()) {
            "lt" -> Rect(0, 0, w, h)
            "ct" -> Rect(w, 0, w, h)
            "rt" -> Rect(w * 2, 0, w, h)
            "lc" -> Rect(0, h, w, h)
            "cc" -> Rect(w, h, w, h)
            "rc" -> Rect(w * 2, h, w, h)
            "lb" -> Rect(0, h * 2, w, h)
            "cb" -> Rect(w, h * 2, w, h)
            "rb" -> Rect(w * 2, h * 2, w, h)
            "top" -> Rect(0, 0, srcW, h)
            "bottom" -> Rect(0, h * 2, srcW, h)
            else -> Rect(0, 0, srcW, srcH)
        }
    }

    private fun calculateSafeRect(src: Mat, roi: Rect): Rect {
        val x = roi.x.coerceIn(0, src.cols() - 1)
        val y = roi.y.coerceIn(0, src.rows() - 1)
        val w = roi.width.coerceAtMost(src.cols() - x)
        val h = roi.height.coerceAtMost(src.rows() - y)
        return Rect(x, y, w, h)
    }

    private fun getTemplateDirectory(config: MonitorConfig): File {
        val root = if (config.preferExternalStorage) {
            File("/storage/emulated/0", config.rootDir)
        } else {
            File(context.filesDir, config.rootDir)
        }
        return File(root, config.templateDir).apply { if (!exists()) mkdirs() }
    }

    override suspend fun reloadTemplates() { loadTemplates() }

    override fun release() {
        lock.write {
            activeTemplates.forEach { it.release() }
            activeTemplates.clear()
        }
        Log.i(TAG, "Matcher 资源已手动释放")
    }
}