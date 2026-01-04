package com.youyou.monitor.infra.config

import com.youyou.monitor.infra.logger.Log
import com.youyou.monitor.infra.network.WebDavClient
import com.youyou.monitor.infra.repository.ConfigRepositoryImpl
import com.youyou.monitor.infra.repository.TemplateRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * WebDAV 配置管理器
 * 
 * 负责：
 * - 解析 WebDAV 配置
 * - 创建 WebDAV 客户端
 * - 配置到各个 Repository
 */
class WebDavConfigManager(
    private val configRepo: ConfigRepositoryImpl,
    private val templateRepo: TemplateRepositoryImpl
) {
    companion object {
        const val TAG = "WebDavConfigManager"
    }
    
    /**
     * 同步远程数据（配置和模板）
     */
    suspend fun syncRemoteData(client: WebDavClient, templateDir: String) {
        syncAll(client)
    }
    
    /**
     * 同步所有内容
     */
    private suspend fun syncAll(client: WebDavClient) {
        try {
            Log.d(TAG, "Syncing config and templates...")
            
            // 同步配置
            configRepo.syncFromRemote().onFailure {
                Log.w(TAG, "Config sync failed: ${it.message}")
            }
            
            // 同步模板
            templateRepo.syncFromRemote().onSuccess {
                Log.i(TAG, "Templates synced successfully")
            }.onFailure {
                Log.w(TAG, "Template sync failed: ${it.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}", e)
        }
    }
}
