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
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 灰度多尺度模板匹配器
 * 
 * 特性：
 * - 灰度图像匹配 (TM_CCOEFF_NORMED)
 * - 两阶段多尺度搜索 (粗搜索 + 细搜索)
 * - 早停优化 (粗搜索分数过低时跳过细搜索)
 * - 弱匹配支持 (阈值 - 0.04)
 * - 线程安全的模板热更新 (ReadWriteLock)
 * 
 * 架构改进：
 * - 依赖注入：通过构造函数接收 ConfigRepository 和 Log
 * - 线程安全：使用 ReadWriteLock 保护 templateGrays/templateNames
 * - 资源管理：确保所有 Mat 对象正确释放
 */
class GrayscaleMultiScaleMatcher(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val logger: Log
) : TemplateMatcher {
    
    private val TAG = "GrayscaleMultiScaleMatcher"
    
    // 线程安全：ReadWriteLock 保护模板数据
    private val lock = ReentrantReadWriteLock()
    private var templateGrays: List<Mat> = emptyList()
    private var templateNames: List<String> = emptyList()
    
    // 性能优化：预分配尺度数组，避免重复创建
    private val coarseScales = floatArrayOf(1.0f, 0.7f, 0.5f)
    private val fineScalesHigh = floatArrayOf(0.95f, 0.9f, 0.85f)
    private val fineScalesLow = floatArrayOf(0.55f, 0.48f, 0.45f)
    
    // 线程局部的细搜索中档尺度数组（避免并发问题）
    private val fineScalesMidLocal = ThreadLocal.withInitial { FloatArray(2) }
    
    companion object {
        private const val WEAK_MATCH_OFFSET = 0.04
        private const val EARLY_EXIT_OFFSET = 0.20
        private const val MIN_TEMPLATE_SIZE = 30
        private const val MAX_DIMENSION = 3200
    }
    
    override suspend fun loadTemplates(): Pair<Int, List<String>> {
        // 直接根据配置计算模板目录路径
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
        
        logger.d(TAG, "Loading templates from: ${templateDir.absolutePath}")
        
        if (!templateDir.exists() || !templateDir.isDirectory) {
            logger.e(TAG, "Template directory not found: ${templateDir.absolutePath}")
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
                    logger.w(TAG, "Failed to decode bitmap: ${file.name}")
                }
            } catch (e: Exception) {
                logger.e(TAG, "Error loading template ${file.name}: ${e.message}")
            }
        }
        
        // 转换 Bitmap 为灰度 Mat
        val newTemplateGrays = bitmaps.mapNotNull { bmp ->
            convertBitmapToGrayMat(bmp)
        }
        
        // 线程安全更新：先获取写锁，保存旧数据，更新新数据，释放旧数据
        val oldTemplateGrays = lock.write {
            val old = templateGrays
            templateGrays = newTemplateGrays
            templateNames = names
            old
        }
        
        // 延迟释放旧模板（避免影响正在进行的匹配）
        oldTemplateGrays.forEach { it.release() }
        
        // 回收所有 Bitmap，防止内存泄漏
        bitmaps.forEach { it.recycle() }
        
        logger.d(TAG, "Loaded ${newTemplateGrays.size} templates: $names")
        
        return Pair(newTemplateGrays.size, names)
    }
    
    override suspend fun match(grayMat: Mat): MatchResult? {
        // 线程安全：读锁保护，本地缓存引用
        val (templates, names) = lock.read {
            Pair(templateGrays, templateNames)
        }
        
        if (templates.isEmpty()) {
            logger.w(TAG, "No templates loaded")
            return null
        }
        
        val config = configRepository.getCurrentConfig()
        val threshold = config.matchThreshold
        val weakThreshold = threshold - WEAK_MATCH_OFFSET
        
        for ((idx, tmpl) in templates.withIndex()) {
            val templateName = names.getOrNull(idx) ?: "template$idx"
            val templateStartTime = System.currentTimeMillis()
            
            // 智能多尺度匹配：两阶段策略
            var bestScore = Double.NEGATIVE_INFINITY
            var bestScale = 1.0f
            val scaleScores = mutableListOf<Pair<Float, Double>>()
            
            // 阶段1：粗搜索
            for (scale in coarseScales) {
                val scaledWidth = (tmpl.cols() * scale).toInt()
                val scaledHeight = (tmpl.rows() * scale).toInt()
                
                if (scaledWidth > grayMat.cols() || scaledHeight > grayMat.rows()) continue
                if (scaledWidth < MIN_TEMPLATE_SIZE || scaledHeight < MIN_TEMPLATE_SIZE) continue
                
                val score = matchAtScale(tmpl, grayMat, scale)
                scaleScores.add(Pair(scale, score))
                if (score > bestScore) {
                    bestScore = score
                    bestScale = scale
                }
            }
            
            // 提前退出优化：如果粗搜索分数很低，跳过细搜索
            if (bestScore < threshold - EARLY_EXIT_OFFSET) {
                if (scaleScores.size == coarseScales.size) {
                    logger.d(TAG, "[$templateName] Skipped fine search (coarse best=${String.format("%.3f", bestScore)} << threshold)")
                }
                continue
            }
            
            // 阶段2：细搜索 - 在最佳尺度附近细化
            val fineScales = when {
                bestScale >= 0.9f -> fineScalesHigh
                bestScale >= 0.65f -> {
                    // 使用 ThreadLocal 避免并发冲突
                    val fineScalesMid = fineScalesMidLocal.get()!!
                    fineScalesMid[0] = bestScale + 0.05f
                    fineScalesMid[1] = bestScale - 0.05f
                    fineScalesMid
                }
                else -> fineScalesLow
            }
            
            for (scale in fineScales) {
                if (scale == bestScale) continue  // 跳过已测试的
                
                val scaledWidth = (tmpl.cols() * scale).toInt()
                val scaledHeight = (tmpl.rows() * scale).toInt()
                
                if (scaledWidth > grayMat.cols() || scaledHeight > grayMat.rows()) continue
                if (scaledWidth < MIN_TEMPLATE_SIZE || scaledHeight < MIN_TEMPLATE_SIZE) continue
                
                val score = matchAtScale(tmpl, grayMat, scale)
                scaleScores.add(Pair(scale, score))
                if (score > bestScore) {
                    bestScore = score
                    bestScale = scale
                }
            }
            
            val templateElapsed = System.currentTimeMillis() - templateStartTime
            
            // 调试输出
            if (bestScore > threshold - 0.10 && scaleScores.isNotEmpty()) {
                val scoresStr = scaleScores.sortedByDescending { it.second }
                    .take(5)
                    .joinToString(", ") { "${String.format("%.2f", it.first)}=${String.format("%.3f", it.second)}" }
                logger.d(TAG, "[$templateName] ${scaleScores.size} scales in ${templateElapsed}ms, best: [$scoresStr]")
            }
            
            // 匹配判断
            if (bestScore >= threshold) {
                logger.i(TAG, "✓ Matched: $templateName (score=$bestScore, scale=${String.format("%.2f", bestScale)}, threshold=$threshold)")
                return MatchResult(
                    templateName = templateName,
                    score = bestScore,
                    scale = bestScale,
                    timeMs = templateElapsed,
                    isWeak = false
                )
            } else if (bestScore >= weakThreshold) {
                logger.i(TAG, "⚠ Weak match: $templateName (score=$bestScore, scale=${String.format("%.2f", bestScale)}, threshold=$threshold, diff=${String.format("%.3f", threshold - bestScore)})")
                return MatchResult(
                    templateName = "weak_$templateName",
                    score = bestScore,
                    scale = bestScale,
                    timeMs = templateElapsed,
                    isWeak = true
                )
            }
        }
        
        // 无匹配
        logger.d(TAG, "✗ No match (threshold=$threshold)")
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
        
        // 释放 Mat 对象
        oldTemplateGrays.forEach { mat ->
            try {
                mat.release()
            } catch (e: Exception) {
                logger.e(TAG, "Error releasing Mat: ${e.message}")
            }
        }
        
        // 清理 ThreadLocal，防止内存泄漏
        fineScalesMidLocal.remove()
        
        logger.d(TAG, "Released all templates")
    }
    
    /**
     * 在指定尺度下执行模板匹配
     * @return 匹配分数 (0-1)
     */
    private fun matchAtScale(template: Mat, image: Mat, scale: Float): Double {
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
            
            // 模板匹配
            val resultCols = image.cols() - scaledTmpl.cols() + 1
            val resultRows = image.rows() - scaledTmpl.rows() + 1
            
            if (resultCols <= 0 || resultRows <= 0) {
                return Double.NEGATIVE_INFINITY
            }
            
            result = Mat(resultRows, resultCols, CvType.CV_32FC1)
            Imgproc.matchTemplate(image, scaledTmpl, result, Imgproc.TM_CCOEFF_NORMED)
            
            val mm = Core.minMaxLoc(result)
            mm.maxVal
        } catch (e: Exception) {
            logger.e(TAG, "matchAtScale error at scale=$scale: ${e.message}")
            Double.NEGATIVE_INFINITY
        } finally {
            result?.release()
            if (scale != 1.0f) scaledTmpl?.release()
        }
    }
    
    /**
     * 将 Bitmap 转换为灰度 Mat
     * @return 成功返回 Mat，失败返回 null
     */
    private fun convertBitmapToGrayMat(bmp: Bitmap): Mat? {
        var tmp: Mat? = null
        var resized: Mat? = null
        return try {
            tmp = Mat()
            Utils.bitmapToMat(bmp, tmp)
            
            // 模板也缩小到相同基准（与匹配图像一致）
            val result = if (tmp.cols() > MAX_DIMENSION || tmp.rows() > MAX_DIMENSION) {
                val maxDim = maxOf(tmp.cols(), tmp.rows())
                val scale = MAX_DIMENSION.toFloat() / maxDim
                resized = Mat()
                val newSize = Size((tmp.cols() * scale).toDouble(), (tmp.rows() * scale).toDouble())
                Imgproc.resize(tmp, resized, newSize, 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.cvtColor(resized, resized, Imgproc.COLOR_RGBA2GRAY)
                resized
            } else {
                Imgproc.cvtColor(tmp, tmp, Imgproc.COLOR_RGBA2GRAY)
                tmp
            }
            
            // 成功创建后，标记为 null 避免 finally 释放
            if (result === tmp) tmp = null else resized = null
            result
        } catch (e: Exception) {
            logger.e(TAG, "Failed to convert bitmap to Mat: ${e.message}")
            null
        } finally {
            // 确保异常时释放未使用的 Mat
            tmp?.release()
            resized?.release()
        }
    }
}
