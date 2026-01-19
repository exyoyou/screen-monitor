package com.youyou.monitor.core.domain.usecase

import com.youyou.monitor.core.domain.repository.ModelInfo
import com.youyou.monitor.core.domain.repository.ModelRepository
import com.youyou.monitor.infra.logger.Log
import com.youyou.monitor.infra.network.WebDavClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 管理 AI 模型用例
 *
 * 功能：
 * - 同步远程模型
 * - 切换模型
 * - 下载/删除模型
 * - 列出可用模型
 */
class ManageModelsUseCase(
    private val modelRepository: ModelRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val TAG = "ManageModelsUseCase"

    /**
     * 设置 WebDAV 客户端
     */
    fun setWebDavClient(client: WebDavClient) {
        (modelRepository as? com.youyou.monitor.infra.repository.ModelRepositoryImpl)?.setWebDavClient(client)
    }

    /**
     * 同步远程模型
     */
    fun syncModelsFromRemote(onResult: (Result<Int>) -> Unit = {}) {
        scope.launch {
            try {
                Log.d(TAG, "Starting model sync from remote...")
                val result = modelRepository.syncModelsFromRemote()
                if (result.isSuccess) {
                    Log.i(TAG, "Model sync completed: ${result.getOrNull()} models synced")
                } else {
                    Log.e(TAG, "Model sync failed: ${result.exceptionOrNull()?.message}")
                }
                onResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Model sync error: ${e.message}", e)
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * 获取当前使用的模型
     */
    fun getCurrentModel(onResult: (ModelInfo?) -> Unit) {
        scope.launch {
            try {
                val model = modelRepository.getCurrentModel()
                Log.d(TAG, "Current model: ${model?.name ?: "none"}")
                onResult(model)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get current model: ${e.message}", e)
                onResult(null)
            }
        }
    }

    /**
     * 列出所有可用模型
     */
    fun listAvailableModels(onResult: (List<ModelInfo>) -> Unit) {
        scope.launch {
            try {
                val models = modelRepository.listAvailableModels()
                Log.d(TAG, "Available models: ${models.size}")
                models.forEach { model ->
                    Log.d(TAG, "  - ${model.name} (${model.id}) ${if (model.isDownloaded) "[DOWNLOADED]" else "[REMOTE]"}")
                }
                onResult(models)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list models: ${e.message}", e)
                onResult(emptyList())
            }
        }
    }

    /**
     * 切换到指定的模型
     */
    fun switchModel(modelId: String, onResult: (Result<ModelInfo>) -> Unit = {}) {
        scope.launch {
            try {
                Log.d(TAG, "Switching to model: $modelId")
                val result = modelRepository.switchModel(modelId)
                if (result.isSuccess) {
                    val model = result.getOrNull()!!
                    Log.i(TAG, "Switched to model: ${model.name}")
                } else {
                    Log.e(TAG, "Failed to switch model: ${result.exceptionOrNull()?.message}")
                }
                onResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Model switch error: ${e.message}", e)
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * 下载指定的模型
     */
    fun downloadModel(modelId: String, onResult: (Result<ModelInfo>) -> Unit = {}) {
        scope.launch {
            try {
                Log.d(TAG, "Downloading model: $modelId")
                val result = modelRepository.downloadModel(modelId)
                if (result.isSuccess) {
                    val model = result.getOrNull()!!
                    Log.i(TAG, "Model downloaded: ${model.name} (${model.fileSize} bytes)")
                } else {
                    Log.e(TAG, "Failed to download model: ${result.exceptionOrNull()?.message}")
                }
                onResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Model download error: ${e.message}", e)
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * 删除指定的模型
     */
    fun deleteModel(modelId: String, onResult: (Result<Unit>) -> Unit = {}) {
        scope.launch {
            try {
                Log.d(TAG, "Deleting model: $modelId")
                val result = modelRepository.deleteModel(modelId)
                if (result.isSuccess) {
                    Log.i(TAG, "Model deleted: $modelId")
                } else {
                    Log.e(TAG, "Failed to delete model: ${result.exceptionOrNull()?.message}")
                }
                onResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Model delete error: ${e.message}", e)
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * 同步模型配置到远程服务器
     */
    fun syncModelConfigToRemote(onResult: (Result<Unit>) -> Unit = {}) {
        scope.launch {
            try {
                Log.d(TAG, "Starting model config sync to remote...")
                val result = modelRepository.syncModelConfigToRemote()
                if (result.isSuccess) {
                    Log.i(TAG, "Model config sync completed")
                } else {
                    Log.e(TAG, "Model config sync failed: ${result.exceptionOrNull()?.message}")
                }
                onResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Model config sync error: ${e.message}", e)
                onResult(Result.failure(e))
            }
        }
    }

}
