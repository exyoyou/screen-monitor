package com.youyou.monitor.infra.processor

import android.graphics.Bitmap
import com.youyou.monitor.core.domain.model.ImageFrame
import com.youyou.monitor.core.domain.model.MatchResult
import com.youyou.monitor.core.domain.repository.ConfigRepository
import com.youyou.monitor.core.domain.repository.StorageRepository
import com.youyou.monitor.core.matcher.TemplateMatcher
import com.youyou.monitor.infra.logger.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 高级帧处理器 - 迁移自 ScreenMonitor.kt
 * 
 * 功能：
 * - 频率限制 (detectPerSecond)
 * - 帧签名去重 (signature-based deduplication)
 * - 队列堆积检测 (isProcessing flag)
 * - 图像质量检测 (isValidImage)
 * - 定期强制保存 (30分钟)
 * - 匹配冷却期 (matchCooldownMs)
 * - 图像缩放优化 (MAX_DIMENSION)
 */
class AdvancedFrameProcessor(
    private val configRepository: ConfigRepository,
    private val storageRepository: StorageRepository,
    private val templateMatcher: TemplateMatcher,
    private val logger: Log
) {
    private val TAG = "AdvancedFrameProcessor"
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 缓存配置（避免每次调用 suspend 方法）
    @Volatile
    private var cachedConfig: com.youyou.monitor.core.domain.model.MonitorConfig? = null
    
    init {
        // 监听配置变化并缓存
        scope.launch {
            try {
                configRepository.getConfigFlow().collect { config ->
                    cachedConfig = config
                    logger.d(TAG, "Config updated: detectPerSecond=${config.detectPerSecond}")
                }
            } catch (e: Exception) {
                logger.e(TAG, "Error collecting config: ${e.message}", e)
            }
        }
    }
    
    // 状态管理（线程安全）
    private val lastDetectTime = AtomicLong(0L)
    private val lastForceSaveTime = AtomicLong(0L)
    private val lastFrameSignature = AtomicLong(0L)
    private val lastMatchTime = AtomicLong(0L)
    private val frameCallCount = AtomicLong(0L)
    private val lastLogTime = AtomicLong(0L)
    private val isProcessing = AtomicBoolean(false)
    private var running = true
    
    // ThreadLocal SimpleDateFormat (线程安全)
    private val timestampFormat = ThreadLocal.withInitial { 
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) 
    }
    
    companion object {
        private const val FORCE_SAVE_INTERVAL = 30 * 60 * 1000L  // 30分钟
        private const val MAX_DIMENSION = 2160  // 超过2160p才缩小
        private const val LOG_INTERVAL = 10000L  // 10秒统计日志
        private const val MIN_STDDEV = 5.0  // 图像质量阈值
    }
    
    /**
     * 帧回调入口 - 高性能设计
     * 迁移自 ScreenMonitor.onFrameAvailable()
     */
    suspend fun onFrameAvailable(frame: ImageFrame): Boolean = withContext(Dispatchers.IO) {
        val count = frameCallCount.incrementAndGet()
        
        // 1. 队列堆积检查（最优先）
        if (isProcessing.get()) {
            if (count % 50 == 0L) {
                logger.d(TAG, "[Skip] Previous frame still processing")
            }
            return@withContext false
        }
        
        val now = System.currentTimeMillis()
        
        // 2. 定期统计日志
        if (now - lastLogTime.get() > LOG_INTERVAL) {
            logger.i(TAG, "[Stats] Total calls: $count, running: $running, isProcessing: ${isProcessing.get()}")
            lastLogTime.set(now)
        }
        
        // 3. 频率限制
        val config = cachedConfig ?: com.youyou.monitor.core.domain.model.MonitorConfig.default()
        val interval = if (config.detectPerSecond > 0) 1000 / config.detectPerSecond else 500
        if (now - lastDetectTime.get() < interval) {
            if (count % 100 == 0L) {
                logger.d(TAG, "[Skip] Rate limit: ${now - lastDetectTime.get()}ms < ${interval}ms")
            }
            return@withContext false
        }
        
        if (!running) {
            logger.w(TAG, "[Skip] Processor not running")
            return@withContext false
        }
        
        lastDetectTime.set(now)
        
        // 4. 快速帧签名计算（采样9个点）
        val signature = calculateFrameSignature(frame)
        if (signature == null) {
            logger.e(TAG, "[Skip] Signature calculation failed")
            return@withContext false
        }
        
        // 5. 帧去重
        if (signature == lastFrameSignature.get()) {
            if (count % 50 == 0L) {
                logger.d(TAG, "[Skip] Duplicate frame (signature: $signature)")
            }
            return@withContext false
        }
        
        logger.d(TAG, "[Process] Frame accepted: ${frame.width}x${frame.height}, signature=$signature")
        
        // 6. 更新签名并异步处理
        lastFrameSignature.set(signature)
        isProcessing.set(true)
        
        try {
            processFrameInternal(frame, now, config)
            true
        } finally {
            isProcessing.set(false)
        }
    }
    
    /**
     * 计算帧签名（采样9个点）
     */
    private fun calculateFrameSignature(frame: ImageFrame): Long? {
        return try {
            val width = frame.width
            val height = frame.height
            val buffer = ByteBuffer.wrap(frame.data)
            
            val halfWidth = width / 2
            val halfHeight = height / 2
            val lastRow = height - 1
            val lastCol = width - 1
            
            var sig = 0L
            // 9个采样点：上(左中右) 中(左中右) 下(左中右)
            val pixels = intArrayOf(
                0, halfWidth, lastCol,
                halfHeight * width, halfHeight * width + halfWidth, halfHeight * width + lastCol,
                lastRow * width, lastRow * width + halfWidth, lastRow * width + lastCol
            )
            
            for (i in pixels.indices) {
                val offset = pixels[i] * 4
                if (offset + 2 < buffer.capacity()) {
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    val gray = (r + g + b) / 3
                    sig = sig or (gray.toLong() shl (i * 7))
                }
            }
            sig
        } catch (e: Exception) {
            logger.e(TAG, "Signature calculation error: ${e.message}")
            null
        }
    }
    
    /**
     * 内部帧处理逻辑
     */
    private suspend fun processFrameInternal(
        frame: ImageFrame,
        now: Long,
        config: com.youyou.monitor.core.domain.model.MonitorConfig
    ) {
        var bmp: Bitmap? = null
        var mat: Mat? = null
        var resized: Mat? = null
        
        try {
            // 创建 Bitmap
            bmp = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            val buf = ByteBuffer.wrap(frame.data)
            bmp.copyPixelsFromBuffer(buf)
            
            // 转换为 Mat
            mat = Mat()
            Utils.bitmapToMat(bmp, mat)
            
            // 图像缩放优化
            // 策略：如果 scale=2（半分辨率），图像已缩小，无需再缩小
            //      如果 scale=1（原始分辨率），超过 2160p 才缩小以加速匹配
            val maxDimension = if (frame.scale == 2) {
                Int.MAX_VALUE  // 已是半分辨率，保持原样
            } else {
                MAX_DIMENSION  // 原始分辨率时才检查
            }
            
            val needResize = frame.width > maxDimension || frame.height > maxDimension
            val processedMat = if (needResize) {
                val maxDim = maxOf(frame.width, frame.height)
                val resizeScale = maxDimension.toFloat() / maxDim
                resized = Mat()
                val newSize = org.opencv.core.Size(
                    (mat.cols() * resizeScale).toDouble(),
                    (mat.rows() * resizeScale).toDouble()
                )
                Imgproc.resize(mat, resized, newSize, 0.0, 0.0, Imgproc.INTER_AREA)
                logger.d(TAG, "Resized: ${frame.width}x${frame.height} -> ${resized.cols()}x${resized.rows()}")
                resized
            } else {
                mat
            }
            
            // 转为灰度图
            Imgproc.cvtColor(processedMat, processedMat, Imgproc.COLOR_RGBA2GRAY)
            
            // 图像质量检测
            if (!isValidImage(processedMat)) {
                logger.w(TAG, "Frame is blank/monochrome, skipping")
                return
            }
            
            // 定期强制保存
            if (now - lastForceSaveTime.get() > FORCE_SAVE_INTERVAL) {
                saveBitmap(bmp, "forced")
                lastForceSaveTime.set(now)
                logger.i(TAG, "Force saved valid screenshot")
            }
            
            // 匹配冷却期检查
            val timeSinceLastMatch = now - lastMatchTime.get()
            if (lastMatchTime.get() > 0 && timeSinceLastMatch < config.matchCooldownMs) {
                logger.d(TAG, "Skip matching: in cooldown (${timeSinceLastMatch}ms / ${config.matchCooldownMs}ms)")
                return
            }
            
            // 执行模板匹配
            val matchResult = templateMatcher.match(processedMat)
            if (matchResult != null) {
                saveBitmap(bmp, matchResult.templateName)
                lastMatchTime.set(now)
                logger.i(TAG, "Match saved: ${matchResult.templateName}")
            }
        } catch (e: Exception) {
            logger.e(TAG, "processFrame error: ${e.message}")
        } finally {
            // 确保资源被释放（即使 release 抛异常也要继续）
            try {
                resized?.release()
            } catch (e: Exception) {
                logger.e(TAG, "Error releasing resized mat: ${e.message}")
            }
            
            try {
                mat?.release()
            } catch (e: Exception) {
                logger.e(TAG, "Error releasing mat: ${e.message}")
            }
            
            try {
                bmp?.recycle()
            } catch (e: Exception) {
                logger.e(TAG, "Error recycling bitmap: ${e.message}")
            }
        }
    }
    
    /**
     * 图像质量检测 - 避免保存黑屏/纯色画面
     */
    private fun isValidImage(grayMat: Mat): Boolean {
        var roi: Mat? = null
        var mean: MatOfDouble? = null
        var stddev: MatOfDouble? = null
        
        return try {
            // 采样中心80%区域
            val startX = (grayMat.cols() * 0.1).toInt()
            val startY = (grayMat.rows() * 0.1).toInt()
            roi = grayMat.submat(
                startY, (grayMat.rows() * 0.9).toInt(),
                startX, (grayMat.cols() * 0.9).toInt()
            )
            
            mean = MatOfDouble()
            stddev = MatOfDouble()
            Core.meanStdDev(roi, mean, stddev)
            
            val stdVal = stddev.get(0, 0)[0]
            val isValid = stdVal >= MIN_STDDEV
            
            if (!isValid) {
                logger.d(TAG, "Invalid frame: stdDev=$stdVal (too low)")
            }
            isValid
        } catch (e: Exception) {
            logger.e(TAG, "isValidImage error: ${e.message}")
            false
        } finally {
            roi?.release()
            mean?.release()
            stddev?.release()
        }
    }
    
    /**
     * 保存 Bitmap 到存储
     */
    private suspend fun saveBitmap(bmp: Bitmap, templateName: String) {
        try {
            val timestamp = timestampFormat.get()!!.format(Date())
            val nameNoExt = templateName.substringBeforeLast('.')
            val filename = "capture_${nameNoExt}_$timestamp.png"
            
            // 使用 StorageRepository 保存
            val result = storageRepository.saveScreenshot(bmp, filename)
            result.onSuccess {
                logger.i(TAG, "Saved: $filename")
            }.onFailure {
                logger.e(TAG, "Save failed: ${it.message}")
            }
        } catch (e: Exception) {
            logger.e(TAG, "saveBitmap error: ${e.message}")
        }
    }
    
    /**
     * 重置处理器状态
     */
    fun reset() {
        running = true
        lastDetectTime.set(0L)
        lastForceSaveTime.set(0L)
        lastFrameSignature.set(0L)
        lastMatchTime.set(0L)
        frameCallCount.set(0L)
        lastLogTime.set(0L)
        isProcessing.set(false)
        logger.d(TAG, "AdvancedFrameProcessor reset")
    }
    
    /**
     * 停止处理器
     */
    fun shutdown() {
        running = false
        timestampFormat.remove()  // 清理 ThreadLocal，避免内存泄漏
        scope.cancel()  // 取消协程作用域
        logger.d(TAG, "AdvancedFrameProcessor shutdown")
    }
}
