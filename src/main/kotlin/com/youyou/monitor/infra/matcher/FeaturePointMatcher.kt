package com.youyou.monitor.infra.matcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.youyou.monitor.core.domain.model.MatchResult
import com.youyou.monitor.core.domain.repository.ConfigRepository
import com.youyou.monitor.core.matcher.TemplateMatcher
import com.youyou.monitor.infra.logger.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 极速特征点匹配器 (基于 ORB 算法)
 * 适配：无视分辨率、无视颜色变化、严格遵循外部配置阈值
 */
class FeaturePointMatcher(
    private val context: Context,
    private val configRepository: ConfigRepository
) : TemplateMatcher {

    private val TAG = "FeaturePointMatcher"
    private val lock = ReentrantReadWriteLock()

    // ORB 探测器：专门用于提取物体的轮廓特征点
    private val detector = ORB.create(500)
    // 匹配器：使用 NORM_HAMMING 算法，这是 ORB 的标准配对
    private val matcher = BFMatcher.create(Core.NORM_HAMMING, true)

    private class FeatureComponent(
        val fileName: String,
        val appName: String,
        val keypoints: MatOfKeyPoint,
        val descriptors: Mat
    ) {
        fun release() {
            keypoints.release()
            descriptors.release()
        }
    }

    private var activeFeatures = mutableListOf<FeatureComponent>()
    private var isInitialized = false

    companion object {
        // 特征描述符的汉明距离阈值。40-50 之间较好。
        // 越小表示要求特征“长得越像”
        private const val HAMMING_DIST_LIMIT = 45f
    }

    override suspend fun loadTemplates(): Pair<Int, List<String>> {
        val config = configRepository.getCurrentConfig()
        val baseDir = File(context.filesDir, config.rootDir)
        val componentDir = File(baseDir, config.templateDir).apply { if (!exists()) mkdirs() }

        val newFeatures = mutableListOf<FeatureComponent>()
        val files = componentDir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: emptyArray()

        for (file in files) {
            try {
                val appName = file.nameWithoutExtension.substringBefore("_")
                val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                val grayMat = Mat()
                Utils.bitmapToMat(bmp, grayMat)
                Imgproc.cvtColor(grayMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

                val keypoints = MatOfKeyPoint()
                val descriptors = Mat()

                // 预计算：在加载时就完成特征点提取，匹配时零开销
                detector.detectAndCompute(grayMat, Mat(), keypoints, descriptors)

                if (!descriptors.empty()) {
                    newFeatures.add(FeatureComponent(file.name, appName, keypoints, descriptors))
                }

                grayMat.release()
                bmp.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "模板特征预分析失败: ${file.name}")
            }
        }

        lock.write {
            activeFeatures.forEach { it.release() }
            activeFeatures.clear()
            activeFeatures.addAll(newFeatures)
            isInitialized = activeFeatures.isNotEmpty()
        }

        return Pair(activeFeatures.size, activeFeatures.map { it.fileName })
    }

    override suspend fun match(grayMat: Mat, scale: Int): MatchResult? {
        return lock.read {
            if (!isInitialized) return@read null
            Log.d(TAG, "特征匹配! ")
            val startTime = System.currentTimeMillis()

            // 1. 获取外部动态配置的阈值 (0.0 - 1.0)
            val config = configRepository.getCurrentConfig()
            val userThreshold = config.matchThreshold

            // 2. 提取截屏特征点 (只提一次)
            val screenKeypoints = MatOfKeyPoint()
            val screenDescriptors = Mat()
            detector.detectAndCompute(grayMat, Mat(), screenKeypoints, screenDescriptors)

            if (screenDescriptors.empty()) {
                screenKeypoints.release()
                screenDescriptors.release()
                Log.d(TAG, "特征匹配! screenDescriptors error")
                return@read null
            }

            var bestResult: MatchResult? = null
            var bestScore = 0.0

            // 3. 遍历特征模板进行比对
            for (comp in activeFeatures) {
                val matches = MatOfDMatch()
                matcher.match(comp.descriptors, screenDescriptors, matches)

                val matchArray = matches.toArray()
                if (matchArray.isEmpty()) {
                    matches.release()
                    continue
                }

                // 计算匹配度：筛选出距离足够近的特征点对
                val goodMatches = matchArray.filter { it.distance < HAMMING_DIST_LIMIT }

                // 核心分值：(好点数 / 模板总点数)
                // 这直接对应了图标的完整程度
                val currentScore = goodMatches.size.toDouble() / matchArray.size.toDouble()
                Log.d(TAG, "currentScore: $currentScore")

                // 4. 严格执行 config.matchThreshold 校验
                if (currentScore >= userThreshold && currentScore > bestScore) {
                    bestScore = currentScore
                    bestResult = MatchResult(
                        templateName = comp.appName, // 返回应用名
                        score = currentScore,
                        scale = 1.0f, // 特征匹配无须 scale 循环，ORB 自带尺度不变性
                        timeMs = System.currentTimeMillis() - startTime,
                        isWeak = currentScore < (userThreshold + 0.15)
                    )
                }
                matches.release()
            }

            screenKeypoints.release()
            screenDescriptors.release()

            if (bestResult != null) {
                Log.d(TAG, "特征匹配通过! 目标: ${bestResult.templateName}, 分数: $bestScore, 阈值: $userThreshold")
            }else
                Log.d(TAG, "特征匹配通过? nei")

            bestResult
        }
    }

    override suspend fun reloadTemplates() { loadTemplates() }

    override fun release() {
        lock.write {
            activeFeatures.forEach { it.release() }
            activeFeatures.clear()
            isInitialized = false
        }
    }
}