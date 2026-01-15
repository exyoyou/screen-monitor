package com.youyou.monitor.core.matcher

import com.youyou.monitor.core.domain.model.MatchResult
import org.opencv.core.Mat

/**
 * 模板匹配器接口（核心算法抽象）
 */
interface TemplateMatcher {
    /**
     * 加载模板
     * @return 加载的模板数量和名称列表
     */
    suspend fun loadTemplates(): Pair<Int, List<String>>
    
    /**
     * 执行模板匹配
     * @param grayMat 灰度图像（单通道）
     * @param scale 图像缩放比例（1=全分辨率，2=半分辨率）
     * @return 匹配结果，未匹配返回 null
     */
    suspend fun match(grayMat: Mat, scale: Int = 1): MatchResult?
    
    /**
     * 重新加载模板（热更新）
     */
    suspend fun reloadTemplates()
    
    /**
     * 释放资源
     */
    fun release()
}
