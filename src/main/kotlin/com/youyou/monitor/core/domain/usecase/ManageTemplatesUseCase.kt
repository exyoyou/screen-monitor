package com.youyou.monitor.core.domain.usecase

import com.youyou.monitor.core.domain.repository.TemplateRepository
import com.youyou.monitor.infra.network.WebDavClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 模板管理用例
 */
class ManageTemplatesUseCase(
    private val templateRepository: TemplateRepository,
    private var webdavClient: WebDavClient? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob())
) {
    /**
     * 设置 WebDAV 客户端
     */
    fun setWebDavClient(client: WebDavClient) {
        this.webdavClient = client
    }
    
    /**
     * 从远程同步模板
     */
    suspend fun syncTemplates(): Result<Unit> {
        val client = webdavClient ?: return Result.failure(
            IllegalStateException("WebDAV client not configured")
        )
        return templateRepository.syncFromRemote().map { Unit }
    }
    
    /**
     * 启动定时同步（每小时）
     */
    fun startAutoSync(intervalMinutes: Long = 60) {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(intervalMinutes * 60 * 1000)
                syncTemplates()
            }
        }
    }
}

/**
 * 存储清理用例
 */
class CleanStorageUseCase(
    private val storage: com.youyou.monitor.core.domain.repository.StorageRepository,
    private val config: com.youyou.monitor.core.domain.repository.ConfigRepository
) {
    /**
     * 清理超出限制的文件
     */
    suspend fun cleanup(): Int {
        val currentConfig = config.getCurrentConfig()
        val maxSize = currentConfig.maxStorageSizeMB * 1024 * 1024L
        val currentSize = storage.getTotalSize().getOrDefault(0L)
        
        return if (currentSize > maxSize) {
            val toDelete = currentSize - maxSize
            storage.deleteOldestFiles(toDelete).getOrDefault(0)
        } else {
            0
        }
    }
}
