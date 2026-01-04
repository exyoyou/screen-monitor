package com.youyou.monitor.infra.repository

import android.content.Context
import com.youyou.monitor.core.domain.model.MonitorConfig
import com.youyou.monitor.core.domain.repository.ConfigRepository
import com.youyou.monitor.core.domain.repository.TemplateRepository
import com.youyou.monitor.core.matcher.TemplateMatcher
import com.youyou.monitor.infra.logger.Log
import com.youyou.monitor.infra.network.WebDavClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    private val configRepository: ConfigRepository,
    private val matcher: TemplateMatcher
) : TemplateRepository {
    
    companion object {
        const val TAG = "TemplateRepository"
        const val TEMPLATE_DIR = "Templates"
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    @Volatile
    private var currentConfig: MonitorConfig = MonitorConfig.default()
    
    @Volatile
    private var isMigrating = false
    
    init {
        // 监听配置变更
        configRepository.getConfigFlow()
            .onEach { newConfig ->
                val oldConfig = currentConfig
                if (newConfig.preferExternalStorage != oldConfig.preferExternalStorage ||
                    newConfig.rootDir != oldConfig.rootDir ||
                    newConfig.templateDir != oldConfig.templateDir) {
                    Log.i(TAG, "Template path changed, migrating...")
                    
                    // 异步迁移模板
                    migrateTemplatesAsync(oldConfig, newConfig)
                }
                currentConfig = newConfig
            }
            .launchIn(scope)
    }
    
    /**
     * 获取根目录（支持优先外部存储）
     */
    private fun getRootDir(): File {
        val config = currentConfig
        val baseDir = if (config.preferExternalStorage) {
            val ext = File("/storage/emulated/0", config.rootDir)
            if (ext.exists() && ext.canWrite()) {
                ext
            } else {
                Log.w(TAG, "External storage not available, using internal")
                File(context.filesDir, config.rootDir)
            }
        } else {
            File(context.filesDir, config.rootDir)
        }
        if (!baseDir.exists()) baseDir.mkdirs()
        return baseDir
    }
    
    private val templateDir: File
        get() = File(getRootDir(), currentConfig.templateDir).apply {
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
            Log.d(TAG, "Template saved: $name (${data.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save template: $name - ${e.message}", e)
        }
    }
    
    override suspend fun syncFromRemote(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val client = webdavClient
            if (client == null) {
                Log.w(TAG, "WebDAV client not configured")
                return@withContext Result.failure(Exception("WebDAV client not configured"))
            }
            
            Log.d(TAG, "Syncing templates from remote: $remoteTemplateDir")
            
            // 1. 列出远程模板
            val remoteFiles = client.listDirectory(remoteTemplateDir)
            if (remoteFiles.isEmpty()) {
                Log.w(TAG, "No remote templates found")
                return@withContext Result.success(0)
            }
            
            Log.d(TAG, "Found ${remoteFiles.size} remote templates")
            
            // 2. 下载并保存模板
            var syncCount = 0
            for (fileName in remoteFiles) {
                try {
                    Log.d(TAG, "Downloading template: $fileName from $remoteTemplateDir")
                    val data = client.downloadFile(remoteTemplateDir, fileName)
                    if (data.isNotEmpty()) {
                        save(fileName, data)
                        syncCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download template: $fileName - ${e.message}")
                }
            }
            
            Log.i(TAG, "Template sync completed: $syncCount/${remoteFiles.size} synced")
            
            // 3. 重新加载模板到matcher
            if (syncCount > 0) {
                Log.d(TAG, "Reloading templates into matcher...")
                notifyTemplatesUpdated()
            }
            
            Result.success(syncCount)
        } catch (e: Exception) {
            Log.e(TAG, "Template sync failed: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    override fun notifyTemplatesUpdated() {
        try {
            Log.d(TAG, "Notifying template update...")
            scope.launch(Dispatchers.IO) {
                matcher.reloadTemplates()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify template update: ${e.message}", e)
        }
    }
    
    /**
     * 设置 WebDAV 客户端
     */
    fun setWebDavClient(client: WebDavClient, remoteDir: String = "Templates") {
        this.webdavClient = client
        this.remoteTemplateDir = remoteDir
        Log.d(TAG, "WebDAV configured: remoteDir=$remoteDir")
    }
    
    /**
     * 异步迁移模板文件
     */
    private fun migrateTemplatesAsync(oldConfig: MonitorConfig, newConfig: MonitorConfig) {
        if (isMigrating) {
            Log.w(TAG, "Template migration already in progress, skipping")
            return
        }
        
        scope.launch(Dispatchers.IO) {
            try {
                isMigrating = true
                migrateTemplates(oldConfig, newConfig)
            } catch (e: Exception) {
                Log.e(TAG, "Template migration failed: ${e.message}", e)
            } finally {
                isMigrating = false
            }
        }
    }
    
    /**
     * 迁移模板文件
     */
    private suspend fun migrateTemplates(oldConfig: MonitorConfig, newConfig: MonitorConfig) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting template migration...")
        
        // 计算旧路径
        val oldBaseDir = if (oldConfig.preferExternalStorage) {
            val ext = File("/storage/emulated/0", oldConfig.rootDir)
            if (ext.exists()) ext else File(context.filesDir, oldConfig.rootDir)
        } else {
            File(context.filesDir, oldConfig.rootDir)
        }
        
        val oldTemplateDir = File(oldBaseDir, oldConfig.templateDir)
        val newTemplateDir = templateDir
        
        // 检查是否需要迁移
        if (oldTemplateDir.absolutePath == newTemplateDir.absolutePath) {
            Log.d(TAG, "Template paths are the same, no migration needed")
            return@withContext
        }
        
        if (!oldTemplateDir.exists()) {
            Log.d(TAG, "Old template directory doesn't exist, nothing to migrate")
            return@withContext
        }
        
        // 确保新目录存在
        if (!newTemplateDir.exists()) {
            newTemplateDir.mkdirs()
        }
        
        var movedCount = 0
        var failedCount = 0
        
        // 获取所有模板文件
        val files = oldTemplateDir.listFiles { file ->
            file.isFile && (file.name.endsWith(".png") || file.name.endsWith(".jpg"))
        } ?: emptyArray()
        
        Log.d(TAG, "Found ${files.size} templates to migrate")
        
        for (file in files) {
            try {
                val targetFile = File(newTemplateDir, file.name)
                
                // 如果目标已存在且大小相同，删除源文件
                if (targetFile.exists() && targetFile.length() == file.length()) {
                    file.delete()
                    Log.d(TAG, "Deleted duplicate template: ${file.name}")
                } else if (file.renameTo(targetFile)) {
                    movedCount++
                    Log.d(TAG, "Moved template: ${file.name}")
                } else {
                    // 尝试复制+删除
                    file.inputStream().use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (file.delete()) {
                        movedCount++
                        Log.d(TAG, "Copied and deleted template: ${file.name}")
                    } else {
                        failedCount++
                        Log.w(TAG, "Failed to delete template after copy: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                failedCount++
                Log.e(TAG, "Failed to migrate template ${file.name}: ${e.message}")
            }
        }
        
        Log.i(TAG, "Template migration completed: moved=$movedCount, failed=$failedCount")
        
        // 清理旧目录
        if (failedCount == 0 && oldTemplateDir.listFiles()?.isEmpty() == true) {
            oldTemplateDir.delete()
            Log.d(TAG, "Cleaned up old template directory")
        }
        
        // 通知模板更新
        notifyTemplatesUpdated()
    }
}
