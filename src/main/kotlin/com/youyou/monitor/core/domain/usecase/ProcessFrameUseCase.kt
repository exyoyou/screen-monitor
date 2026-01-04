package com.youyou.monitor.core.domain.usecase

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.youyou.monitor.core.domain.model.ImageFrame
import com.youyou.monitor.core.domain.model.MatchResult
import com.youyou.monitor.core.domain.repository.ConfigRepository
import com.youyou.monitor.core.domain.repository.StorageRepository
import com.youyou.monitor.core.matcher.TemplateMatcher
import kotlinx.coroutines.*
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.minutes

/**
 * 处理帧用例（核心业务逻辑）
 * 
 * 职责：
 * 1. 图像质量检测
 * 2. 频率控制
 * 3. 模板匹配
 * 4. 结果保存
 */
class ProcessFrameUseCase(
    private val matcher: TemplateMatcher,
    private val storage: StorageRepository,
    private val config: ConfigRepository,
    private val scope: CoroutineScope = CoroutineScope(
        Dispatchers.Default.limitedParallelism(1) + SupervisorJob()
    )
) {
    private var lastMatchTime = 0L
    private var lastForceSaveTime = 0L
    private var lastFrameSignature = 0L
    
    /**
     * 处理单帧图像
     */
    fun processFrame(frame: ImageFrame) {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val cfg = config.getCurrentConfig()
                
                // 1. 频率控制
                val interval = if (cfg.detectPerSecond > 0) 
                    1000 / cfg.detectPerSecond else 500
                if (now - lastMatchTime < interval) return@launch
                
                // 2. 帧去重
                val signature = calculateSignature(frame)
                if (signature == lastFrameSignature) return@launch
                lastFrameSignature = signature
                
                // 3. 创建 Mat 和图像质量检测
                val bitmap = createBitmap(frame)
                val grayMat = Mat()
                Utils.bitmapToMat(bitmap, grayMat)
                Imgproc.cvtColor(grayMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
                
                if (!isValidImage(grayMat)) {
                    grayMat.release()
                    bitmap.recycle()
                    return@launch
                }
                
                // 4. 定期强制保存
                if (now - lastForceSaveTime > 30.minutes.inWholeMilliseconds) {
                    saveBitmap(bitmap, "periodic")
                    lastForceSaveTime = now
                }
                
                // 5. 匹配冷却检查
                if (lastMatchTime > 0 && now - lastMatchTime < cfg.matchCooldownMs) {
                    grayMat.release()
                    bitmap.recycle()
                    return@launch
                }
                
                // 6. 执行模板匹配
                val result = matcher.match(grayMat)
                if (result != null) {
                    saveBitmap(bitmap, result.templateName)
                    lastMatchTime = now
                }
                
                grayMat.release()
                bitmap.recycle()
                
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 计算帧签名（9点采样）
     */
    private fun calculateSignature(frame: ImageFrame): Long {
        val stride = frame.width * 4
        val halfWidth = frame.width / 2
        val halfHeight = frame.height / 2
        val lastRow = frame.height - 1
        val lastCol = frame.width - 1
        
        var sig = 0L
        val pixels = intArrayOf(
            0, halfWidth, lastCol,
            halfHeight * stride, halfHeight * stride + halfWidth * 4, halfHeight * stride + lastCol * 4,
            lastRow * stride, lastRow * stride + halfWidth * 4, lastRow * stride + lastCol * 4
        )
        
        for (i in pixels.indices) {
            val pos = pixels[i]
            if (pos + 2 < frame.data.size) {
                val r = frame.data[pos].toInt() and 0xFF
                val g = frame.data[pos + 1].toInt() and 0xFF
                val b = frame.data[pos + 2].toInt() and 0xFF
                sig = sig * 31 + (r + g + b)
            }
        }
        return sig
    }
    
    /**
     * 图像质量检测
     */
    private fun isValidImage(grayMat: Mat): Boolean {
        var roi: Mat? = null
        var mean: MatOfDouble? = null
        var stddev: MatOfDouble? = null
        return try {
            val startX = (grayMat.cols() * 0.1).toInt()
            val startY = (grayMat.rows() * 0.1).toInt()
            roi = grayMat.submat(
                startY, (grayMat.rows() * 0.9).toInt(),
                startX, (grayMat.cols() * 0.9).toInt()
            )
            
            mean = MatOfDouble()
            stddev = MatOfDouble()
            Core.meanStdDev(roi, mean, stddev)
            
            stddev.get(0, 0)[0] >= 5.0
        } catch (e: Exception) {
            false
        } finally {
            roi?.release()
            mean?.release()
            stddev?.release()
        }
    }
    
    /**
     * 创建 Bitmap
     */
    private fun createBitmap(frame: ImageFrame): Bitmap {
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(frame.data))
        return bitmap
    }
    
    /**
     * 保存截图
     */
    private suspend fun saveBitmap(bitmap: Bitmap, tag: String) {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        storage.saveScreenshot(output.toByteArray(), tag)
    }
    
    /**
     * 关闭
     */
    fun shutdown() {
        scope.cancel()
    }
}
