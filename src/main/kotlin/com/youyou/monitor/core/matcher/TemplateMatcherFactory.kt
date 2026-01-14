package com.youyou.monitor.core.matcher

import android.content.Context
import com.youyou.monitor.core.domain.repository.ConfigRepository
import com.youyou.monitor.infra.matcher.ChatWindowMatcher
import com.youyou.monitor.infra.matcher.GrayscaleMultiScaleMatcher

/**
 * 模板匹配器工厂
 * 根据配置动态创建匹配器实例
 */
object TemplateMatcherFactory {

    /**
     * 创建匹配器实例
     * @param matcherType 匹配器类型
     * @param context Android 上下文
     * @param configRepository 配置仓库
     * @return 匹配器实例
     */
    fun createMatcher(
        matcherType: String,
        context: Context,
        configRepository: ConfigRepository
    ): TemplateMatcher {
        return when (matcherType.lowercase()) {
            "grayscale", "grayscalemultiscale" -> {
                GrayscaleMultiScaleMatcher(context, configRepository)
            }
            "chat", "chatwindow" -> {
                ChatWindowMatcher(context, configRepository)
            }
            // 可以在这里添加其他匹配器类型
            // "color" -> ColorMatcher(context, configRepository)
            // "feature" -> FeatureMatcher(context, configRepository)
            else -> {
                // 默认使用聊天窗口匹配器（针对用户需求优化）
                ChatWindowMatcher(context, configRepository)
            }
        }
    }
}