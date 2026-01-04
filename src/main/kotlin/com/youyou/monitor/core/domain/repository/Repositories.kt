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
 * 模板仓储接口
 */
interface TemplateRepository {
    /**
     * 从远程同步模板
     */
    suspend fun syncFromRemote(): Result<Int>
    
    /**
     * 通知模板已更新（触发重新加载）
     */
    fun notifyTemplatesUpdated()
}

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
