package com.youyou.monitor.core.domain.repository

import android.graphics.Bitmap
import com.youyou.monitor.core.domain.model.MonitorConfig
import com.youyou.monitor.infra.network.WebDavClient
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * 配置仓储接口
 */
interface ConfigRepository {
    /**
     * 获取当前配置（Flow 自动更新）
     */
    fun getConfigFlow(): Flow<MonitorConfig>
    
    /**
     * 获取当前配置值（同步）
     */
    suspend fun getCurrentConfig(): MonitorConfig
    
    /**
     * 更新配置
     */
    suspend fun updateConfig(config: MonitorConfig)
    
    /**
     * 从远程同步配置
     */
    suspend fun syncFromRemote(): Result<Unit>
}

/**
 * AI 模型仓储接口
 */
interface ModelRepository {
    /**
     * 获取当前使用的模型信息
     */
    suspend fun getCurrentModel(): ModelInfo?

    /**
     * 列出所有可用的模型
     */
    suspend fun listAvailableModels(): List<ModelInfo>

    /**
     * 切换到指定的模型
     */
    suspend fun switchModel(modelId: String): Result<ModelInfo>

    /**
     * 从远程同步模型
     */
    suspend fun syncModelsFromRemote(): Result<Int>

    /**
     * 同步模型配置到远程服务器
     */
    suspend fun syncModelConfigToRemote(): Result<Unit>

    /**
     * 下载指定模型
     */
    suspend fun downloadModel(modelId: String): Result<ModelInfo>

    /**
     * 删除指定的模型
     */
    suspend fun deleteModel(modelId: String): Result<Unit>
}

/**
 * AI 配置仓储接口
 */
interface AiConfigRepository {
    /**
     * 获取AI配置流
     */
    fun getAiConfigFlow(): kotlinx.coroutines.flow.Flow<com.youyou.monitor.core.domain.model.AiConfig>

    /**
     * 获取当前AI配置
     */
    suspend fun getCurrentAiConfig(): com.youyou.monitor.core.domain.model.AiConfig

    /**
     * 更新AI配置
     */
    suspend fun updateAiConfig(config: com.youyou.monitor.core.domain.model.AiConfig)

    /**
     * 从远程同步AI配置
     */
    suspend fun syncFromRemote(): Result<Unit>

    /**
     * 同步AI配置到远程
     */
    suspend fun syncToRemote(): Result<Unit>
}

/**
 * 模型信息
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val version: String,
    val fileName: String,
    val fileSize: Long,
    val description: String? = null,
    val isDownloaded: Boolean = false,
    val downloadUrl: String? = null,
    val lastModified: Long = 0L
)

/**
 * 存储仓储接口
 */
interface StorageRepository {
    /**
     * 保存截图（ByteArray）
     */
    suspend fun saveScreenshot(data: ByteArray, tag: String): Result<String>
    
    /**
     * 保存截图（Bitmap）
     */
    suspend fun saveScreenshot(bitmap: Bitmap, filename: String): Result<String>
    
    /**
     * 获取总存储大小
     */
    suspend fun getTotalSize(): Result<Long>
    
    /**
     * 删除最旧的文件（释放指定字节数）
     */
    suspend fun deleteOldestFiles(bytes: Long): Result<Int>
    
    /**
     * 获取待上传文件列表
     */
    suspend fun getPendingUploadFiles(): List<String>
    
    /**
     * 获取根目录路径
     */
    fun getRootDirPath(): String
    
    /**
     * 列出所有截图文件
     */
    suspend fun listScreenshots(): Result<List<File>>
    
    /**
     * 列出所有视频文件
     */
    suspend fun listVideos(): Result<List<File>>
    
    /**
     * 获取截图目录
     */
    fun getScreenshotDirectory(): File
    
    /**
     * 获取视频目录
     */
    fun getVideoDirectory(): File
}
