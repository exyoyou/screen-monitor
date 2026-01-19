package com.youyou.monitor.di

import android.content.Context
import com.youyou.monitor.core.domain.repository.AiConfigRepository
import com.youyou.monitor.core.domain.repository.ConfigRepository
import com.youyou.monitor.core.domain.repository.ModelRepository
import com.youyou.monitor.core.domain.repository.StorageRepository
import com.youyou.monitor.core.domain.usecase.ManageModelsUseCase
import com.youyou.monitor.infra.logger.Log
import com.youyou.monitor.infra.repository.AiConfigRepositoryImpl
import com.youyou.monitor.infra.repository.ConfigRepositoryImpl
import com.youyou.monitor.infra.repository.ModelRepositoryImpl
import com.youyou.monitor.infra.processor.AdvancedFrameProcessor
import com.youyou.monitor.infra.repository.StorageRepositoryImpl
import com.youyou.monitor.infra.task.ScheduledTaskManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.error.KoinAppAlreadyStartedException
import org.koin.dsl.module

/**
 * Koin 依赖注入模块
 */
val monitorModule = module {
    // 单例：基础设施层
    single<ConfigRepository> { 
        ConfigRepositoryImpl(androidContext())
    }
    
    // 同时注册实现类（供 WebDavConfigManager 使用）
    single<ConfigRepositoryImpl> {
        get<ConfigRepository>() as ConfigRepositoryImpl
    }
    
    single<StorageRepository> {
        StorageRepositoryImpl(androidContext(), get())
    }
    
    single<AiConfigRepository> {
        AiConfigRepositoryImpl(androidContext())
    }

    single<ModelRepository> {
        ModelRepositoryImpl(androidContext(), get())
    }
    
    // 单例：定时任务管理器
    single<ScheduledTaskManager> {
        ScheduledTaskManager(get(), get())
    }

    // 单例：高级帧处理器
    single<AdvancedFrameProcessor> {
        AdvancedFrameProcessor(get(), get())
    }

    // 工厂：业务用例
    factory { ManageModelsUseCase(get()) }
}

/**
 * 初始化 Koin（包含日志系统）
 * 
 * 注意：如果 Log 已经在 MonitorService.init() 中初始化，这里会跳过重复初始化
 */
fun initKoin(context: Context) {
    // 初始化日志系统（如果已初始化则跳过）
    if (Log.getCurrentLogFile() == null) {
        Log.init(context)
    }
    
    try {
        startKoin {
            androidContext(context)
            modules(monitorModule)
        }
        Log.i("MonitorModule", "Koin initialized successfully")
    } catch (e: KoinAppAlreadyStartedException) {
        Log.w("MonitorModule", "Koin already started, skipping initialization")
    }
}
