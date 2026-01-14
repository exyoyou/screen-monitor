package com.youyou.monitor.core.matcher

import android.content.Context
import com.youyou.monitor.core.domain.model.MonitorConfig
import com.youyou.monitor.core.domain.repository.ConfigRepository
import com.youyou.monitor.infra.logger.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 模板匹配器管理器
 * 负责根据配置动态管理匹配器实例
 */
class TemplateMatcherManager(
    private val context: Context,
    private val configRepository: ConfigRepository
) {
    private val TAG = "TemplateMatcherManager"
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    @Volatile
    private var currentMatcher: TemplateMatcher? = null
    @Volatile
    private var currentConfig: MonitorConfig = MonitorConfig.default()
    
    init {
        // 监听配置变更
        configRepository.getConfigFlow()
            .onEach { newConfig ->
                val oldConfig = currentConfig
                if (newConfig.matcherType != oldConfig.matcherType) {
                    Log.i(TAG, "Matcher type changed from ${oldConfig.matcherType} to ${newConfig.matcherType}, recreating matcher...")
                    recreateMatcher(newConfig)
                }
                currentConfig = newConfig
            }
            .launchIn(scope)
        
        // 初始化匹配器
        recreateMatcher(currentConfig)
    }
    
    /**
     * 获取当前匹配器
     */
    fun getMatcher(): TemplateMatcher {
        // 确保返回的匹配器与当前配置匹配
        val matcher = currentMatcher
        return if (matcher != null && isMatcherTypeValid(matcher, currentConfig.matcherType)) {
            matcher
        } else {
            synchronized(this) {
                // 双重检查
                currentMatcher?.let { existing ->
                    if (isMatcherTypeValid(existing, currentConfig.matcherType)) {
                        return existing
                    }
                }
                
                // 创建新匹配器
                Log.d(TAG, "Creating new matcher for type: ${currentConfig.matcherType}")
                val newMatcher = TemplateMatcherFactory.createMatcher(
                    currentConfig.matcherType, context, configRepository
                )
                currentMatcher = newMatcher
                
                // 异步加载模板
                scope.launch(Dispatchers.IO) {
                    try {
                        newMatcher.loadTemplates()
                        Log.d(TAG, "Templates loaded for matcher: ${currentConfig.matcherType}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load templates: ${e.message}", e)
                    }
                }
                
                newMatcher
            }
        }
    }
    
    /**
     * 检查匹配器类型是否与配置匹配
     */
    private fun isMatcherTypeValid(matcher: TemplateMatcher, expectedType: String): Boolean {
        // 这里可以根据匹配器实例判断类型
        // 简单起见，我们假设如果配置变了，就重新创建
        return true // 暂时总是返回true，让配置变更驱动重新创建
    }
    
    /**
     * 重新创建匹配器
     */
    private fun recreateMatcher(config: MonitorConfig) {
        synchronized(this) {
            try {
                // 释放旧匹配器
                currentMatcher?.release()
                
                // 创建新匹配器
                val newMatcher = TemplateMatcherFactory.createMatcher(
                    config.matcherType, context, configRepository
                )
                
                currentMatcher = newMatcher
                
                Log.i(TAG, "Matcher switched to: ${config.matcherType}")
                
                // 异步加载模板
                scope.launch(Dispatchers.IO) {
                    try {
                        val (count, names) = newMatcher.loadTemplates()
                        Log.i(TAG, "Loaded $count templates for ${config.matcherType}: $names")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load templates for ${config.matcherType}: ${e.message}", e)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recreate matcher: ${e.message}", e)
            }
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        currentMatcher?.release()
        currentMatcher = null
    }
}