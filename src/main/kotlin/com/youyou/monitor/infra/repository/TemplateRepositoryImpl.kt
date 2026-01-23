package com.youyou.monitor.infra.repository

import android.content.Context
import com.youyou.monitor.core.domain.model.MonitorConfig
// ConfigRepository no longer injected here; updates are done via updateConfig()
import com.youyou.monitor.core.domain.repository.TemplateRepository
import com.youyou.monitor.core.matcher.TemplateMatcherManager
import com.youyou.monitor.core.domain.repository.StorageRepository
import com.youyou.monitor.infra.logger.Log
import com.youyou.monitor.infra.network.WebDavClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
// config flow subscription removed; use explicit updateConfig() instead
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 模板仓储实现
 * 
 * 功能：
 * - 本地模板文件管理
 * - 远程模板同步（WebDAV）
 * - 模板变更通知
 * - 动态存储路径（支持外部存储）
 */
class TemplateRepositoryImpl(
    private val context: Context,
    private val storageRepository: StorageRepository,
    private val matcherManager: TemplateMatcherManager
) : TemplateRepository {
    
    companion object {
        const val TAG = "TemplateRepository"
        const val TEMPLATE_DIR = "Templates"
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    @Volatile
    private var currentConfig: MonitorConfig = MonitorConfig.default()
    
    /**
     * 外部显式更新配置（由 MonitorService 在配置变化时调用）
     * 保持与之前内部订阅相同的行为：当 matcherType 变更且已配置 WebDAV 客户端时，更新 remoteTemplateDir 并异步同步模板。
     */
    override fun updateConfig(config: MonitorConfig) {
        try {
            val oldConfig = currentConfig
            currentConfig = config

            if (config.matcherType != oldConfig.matcherType && webdavClient != null) {
                val baseRemoteDir = webdavClient?.let {
                    val currentRemoteDir = remoteTemplateDir
                    if (currentRemoteDir.contains("/")) {
                        currentRemoteDir.substringBeforeLast("/")
                    } else {
                        TEMPLATE_DIR
                    }
                } ?: TEMPLATE_DIR

                remoteTemplateDir = "$baseRemoteDir/${config.matcherType}"
                Log.d(TAG, "由于匹配器类型更改，已更新remoteTemplateDir: $remoteTemplateDir")

                scope.launch(Dispatchers.IO) {
                    Log.i(TAG, "匹配器类型已更改，正在从新的远程目录同步模板...")
                    try {
                        syncFromRemote()
                    } catch (e: Exception) {
                        Log.w(TAG, "同步模板失败: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateConfig 处理失败: ${e.message}")
        }
    }
    
    private val templateDir: File
        get() = File(storageRepository.getRootDir(), currentConfig.templateDir).apply {
            if (!exists()) mkdirs()
        }
    
    // WebDAV 客户端（可选）
    private var webdavClient: WebDavClient? = null
    private var remoteTemplateDir: String = "Templates"
    
    /**
     * 内部方法：保存模板文件
     */
    private suspend fun save(name: String, data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val file = File(templateDir, name)
            file.writeBytes(data)
            Log.d(TAG, "模板已保存: $name (${data.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "保存模板失败: $name - ${e.message}", e)
        }
    }

    /**
     * 将远程文件名转换为本地存储名（外部存储时使用 .tmp_<ext> 规则）
     */
    private fun remoteToLocalName(remoteName: String): String {
        return if (currentConfig.preferExternalStorage) {
            val idx = remoteName.lastIndexOf('.')
            if (idx <= 0) return remoteName
            val base = remoteName.substring(0, idx)
            val ext = remoteName.substring(idx + 1)
            "$base.tmp_$ext"
        } else remoteName
    }

    /**
     * 将本地文件名规范化为远程文件名：把 ".tmp_<ext>" 转回 ".<ext>"
     */
    private fun normalizeLocalToRemote(localName: String): String {
        val tmpIndex = localName.lastIndexOf(".tmp_")
        return if (tmpIndex != -1) {
            localName.substring(0, tmpIndex) + "." + localName.substring(tmpIndex + 5)
        } else {
            localName
        }
    }
    
    override suspend fun syncFromRemote(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val client = webdavClient
            if (client == null) {
                Log.w(TAG, "WebDAV客户端未配置")
                return@withContext Result.failure(Exception("WebDAV client not configured"))
            }
            
            Log.d(TAG, "正在从远程同步模板: $remoteTemplateDir")
            
            // 1. 列出远程模板（包含大小），用于跳过已存在且相同大小的文件
            val remoteFilesWithSizes = client.listDirectoryWithSizes(remoteTemplateDir)
            if (remoteFilesWithSizes.isEmpty()) {
                Log.w(TAG, "未找到远程模板")
                return@withContext Result.success(0)
            }

            Log.d(TAG, "发现 ${remoteFilesWithSizes.size} 个远程模板")
            
            // 2. 清理本地模板目录（只保留远程存在的模板）
            try {
                val localDir = templateDir
                if (localDir.exists() && localDir.isDirectory) {
                    // 支持外部存储使用的 ".tmp_<ext>" 命名
                    val localFiles = localDir.listFiles { file ->
                        file.isFile && com.youyou.monitor.infra.matcher.TemplateFileUtil.isLocalImageFile(file)
                    } ?: emptyArray()

                    val remoteFileNames = remoteFilesWithSizes.map { it.first }.toSet()

                    // 将本地文件名规范化为远程名称用于比较（将 .tmp_<ext> -> .<ext>）
                    val filesToDelete = localFiles.filter { local ->
                        val normalized = com.youyou.monitor.infra.matcher.TemplateFileUtil.normalizeLocalToRemote(local.name)
                        !remoteFileNames.contains(normalized)
                    }
                    
                    if (filesToDelete.isNotEmpty()) {
                        Log.d(TAG, "正在清理 ${filesToDelete.size} 个过时的本地模板")
                        filesToDelete.forEach { file ->
                            try {
                                if (file.delete()) {
                                    Log.d(TAG, "已删除过时的模板: ${file.name}")
                                } else {
                                    Log.w(TAG, "删除过时的模板失败: ${file.name}")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "删除过时的模板出错 ${file.name}: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "清理本地模板出错: ${e.message}")
            }
            
            // 3. 下载并保存模板
            var syncCount = 0
            for ((fileName, remoteSize) in remoteFilesWithSizes) {
                try {
                    Log.d(TAG, "正在下载模板: $fileName 从 $remoteTemplateDir")
                    // 如果本地已有同名（按 local 命名规则）且大小匹配，则跳过下载
                    val localName = remoteToLocalName(fileName)
                    val localFile = File(templateDir, localName)
                    if (localFile.exists() && localFile.length() == remoteSize) {
                        Log.d(TAG, "跳过下载（已存在且大小匹配）: $localName")
                        continue
                    }

                    val data = client.downloadFile(remoteTemplateDir, fileName)
                    if (data.isNotEmpty()) {
                        save(localName, data)
                        syncCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "下载模板失败: $fileName - ${e.message}")
                }
            }
            
            Log.i(TAG, "模板同步完成: $syncCount/${remoteFilesWithSizes.size} 已同步")
            
            // 3. 重新加载模板到matcher
            if (syncCount > 0) {
                Log.d(TAG, "正在重新加载模板到匹配器...")
                notifyTemplatesUpdated()
            }
            
            Result.success(syncCount)
        } catch (e: Exception) {
            Log.e(TAG, "模板同步失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    override fun notifyTemplatesUpdated() {
        try {
            Log.d(TAG, "正在通知模板更新...")
            scope.launch(Dispatchers.IO) {
                matcherManager.getMatcher().reloadTemplates()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify template update: ${e.message}", e)
        }
    }
    
    /**
     * 设置 WebDAV 客户端
     */
    fun setWebDavClient(client: WebDavClient, baseRemoteDir: String = "Templates") {
        this.webdavClient = client
        // 根据匹配器类型设置远程模板目录
        val matcherType = currentConfig.matcherType
        this.remoteTemplateDir = "$baseRemoteDir/$matcherType"
        Log.d(TAG, "WebDAV configured: baseRemoteDir=$baseRemoteDir, matcherType=$matcherType, remoteTemplateDir=$remoteTemplateDir")
    }
    
}
