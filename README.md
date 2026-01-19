# AI 聊天界面检测模型

## 概述

本模块使用 TensorFlow Lite 进行聊天应用的界面检测，支持识别以下应用：
- 微信（日间模式）
- 微信（夜间模式）
- QQ
- 微博
- 抖音

## 模型管理

### 支持多个模型版本

系统支持同时管理多个AI模型版本，包括：
- **主模型**: `chat_detector_v1` - 默认聊天检测模型
- **实验模型**: 不同优化版本或A/B测试模型
- **专用模型**: 针对特定场景优化的模型

### 模型文件位置

模型文件存储在应用的内部存储中：
```
Android/data/[包名]/files/Models/
├── models.json                    # 模型配置文件 (JSON格式)
├── chat_detector.tflite          # 默认模型文件
├── chat_detector_v2.tflite       # 其他版本模型文件
└── ...
```

**实际路径示例**:
```
Android/data/com.carriez.flutter_hbb/files/Models/
├── models.json
├── chat_detector.tflite
└── ...
```

### 远程模型同步

支持通过WebDAV从远程服务器同步模型：
- 自动下载新版本模型
- 支持增量更新和版本控制
- 智能选择最快的服务器

## 模型配置格式

`models.json` 配置文件存储在 `Models/` 目录下，格式如下：

```json
{
  "models": [
    {
      "id": "chat_detector_v1",
      "name": "聊天界面检测模型 v1",
      "version": "1.0.0",
      "fileName": "chat_detector.tflite",
      "fileSize": 1543210,
      "description": "用于检测微信、QQ等聊天应用的界面",
      "downloadUrl": null,
      "lastModified": 1703123456789,
      "isDownloaded": true
    },
    {
      "id": "chat_detector_v2",
      "name": "聊天界面检测模型 v2 (实验版)",
      "version": "2.0.0",
      "fileName": "chat_detector_v2.tflite",
      "fileSize": 1876543,
      "description": "优化版本，支持更多应用类型",
      "downloadUrl": "https://example.com/models/chat_detector_v2.tflite",
      "lastModified": 1703987654321,
      "isDownloaded": false
    }
  ]
}
```

### 配置字段说明

- `id`: 模型唯一标识符
- `name`: 显示名称
- `version`: 版本号
- `fileName`: 本地文件名
- `fileSize`: 文件大小(字节)
- `description`: 描述信息
- `downloadUrl`: 下载地址(可选)
- `lastModified`: 最后修改时间戳
- `isDownloaded`: 是否已下载(运行时动态设置)

## AI 配置说明

### 独立的AI配置文件

AI功能使用独立的配置文件 `ai_config_default.json`，不依赖主配置文件：

```json
{
  "currentModelId": "chat_detector_v1",
  "enableAutoSync": true,
  "syncIntervalHours": 24,
  "remoteModelDir": "AI/Models",
  "remoteConfigDir": "AI/Config",
  "webdavServers": [
    {
      "url": "http://192.168.50.30:5244/dav",
      "username": "rust",
      "password": "youyou",
      "baseDir": "本地存储"
    },
    {
      "url": "http://192.168.100.100:5244/dav",
      "username": "rust",
      "password": "youyou",
      "baseDir": "localhost"
    }
  ]
}
```

#### AI 配置字段说明

- **`currentModelId`**: 当前使用的AI模型ID
- **`enableAutoSync`**: 是否启用自动同步
- **`syncIntervalHours`**: 同步间隔（小时）
- **`remoteModelDir`**: 远程模型文件目录
- **`remoteConfigDir`**: 远程配置文件目录
- **`webdavServers`**: AI专用WebDAV服务器配置

#### 配置文件位置

- **默认配置**: `assets/ai_config_default.json`
- **运行时配置**: `Android/data/[包名]/files/ai_config.json`

#### 路径映射

AI配置文件中的路径会按以下规则映射到实际WebDAV路径：

```
WebDAV服务器根目录/
├── [baseDir]/                    # 服务器基础目录
│   ├── AI/Models/               # 模型文件目录
│   │   ├── chat_detector.tflite
│   │   └── chat_detector_v2.tflite
│   └── AI/Config/               # 配置文件目录
│       └── models.json          # 模型列表配置
└── ...
```

例如，如果 `baseDir` 为 `"本地存储"`，则：
- 模型文件路径: `本地存储/AI/Models/`
- 配置文件路径: `本地存储/AI/Config/models.json`

## 模型要求

- **输入**: 224x224 RGB 图像
- **输出**: 5 个类别的概率分布
- **格式**: TensorFlow Lite (.tflite)
- **数据类型**: FLOAT32

### 输出类别映射

```
0: 微信-日间模式
1: 微信-夜间模式
2: QQ
3: 微博
4: 抖音
```

## 使用说明

### 自动模式
1. 应用启动时自动检测本地模型配置
2. 如果没有模型，AI匹配器会安全降级（返回null，不影响其他功能）
3. 支持通过WebDAV自动同步远程模型更新

### 手动模式
使用 `ManageModelsUseCase` API进行模型管理：

```kotlin
val manageModelsUseCase = get<ManageModelsUseCase>()

// 同步远程模型配置和文件
manageModelsUseCase.syncModelsFromRemote { result ->
    if (result.isSuccess) {
        val syncedCount = result.getOrNull() ?: 0
        println("成功同步 $syncedCount 个模型")
    } else {
        println("同步失败: ${result.exceptionOrNull()?.message}")
    }
}

// 切换到指定模型
manageModelsUseCase.switchModel("chat_detector_v2") { result ->
    if (result.isSuccess) {
        val model = result.getOrNull()!!
        println("已切换到模型: ${model.name}")
    } else {
        println("切换失败: ${result.exceptionOrNull()?.message}")
    }
}

// 下载指定模型
manageModelsUseCase.downloadModel("chat_detector_v2") { result ->
    if (result.isSuccess) {
        val model = result.getOrNull()!!
        println("模型下载完成: ${model.name} (${model.fileSize} bytes)")
    }
}

// 列出所有可用模型
manageModelsUseCase.listAvailableModels { models ->
    models.forEach { model ->
        val status = if (model.isDownloaded) "已下载" else "未下载"
        println("${model.name} (${model.id}) - $status")
    }
}

// 删除指定模型
manageModelsUseCase.deleteModel("chat_detector_v2") { result ->
    if (result.isSuccess) {
        println("模型删除成功")
    }
}
```

## 架构实现

### ModelRepository 实现

#### 核心组件
- **ModelRepositoryImpl**: 模型仓储的完整实现
- **ModelInfo**: 模型元数据结构
- **ManageModelsUseCase**: 业务逻辑封装

#### 保存逻辑

1. **配置文件保存** (`saveModelConfig`)
   - 位置: `Android/data/[包名]/files/Models/models.json`
   - 格式: JSON数组包含所有模型信息
   - 自动更新: 下载/删除模型时自动更新状态

2. **模型文件保存** (`downloadModel`)
   - 位置: `Android/data/[包名]/files/Models/[文件名].tflite`
   - 校验: 下载后验证文件完整性
   - 元数据: 自动更新文件大小和修改时间

3. **当前模型保存** (`saveCurrentModelToConfig`)
   - 集成: 通过ConfigRepository保存当前使用的模型ID
   - 持久化: 应用重启后恢复上次使用的模型

#### 读取逻辑

1. **配置加载** (`loadModelConfig`)
   - 优先级: 远程配置 → 本地缓存 → 默认配置
   - 状态检查: 自动检测模型文件是否存在
   - 错误处理: 配置文件损坏时使用默认配置

2. **模型文件读取** (`AIMatcher.loadModelFile`)
   - 路径解析: 通过ModelRepository获取当前模型路径
   - 内存加载: 使用ByteBuffer直接加载到内存
   - 字节序: 自动处理native字节序

3. **状态同步**
   - 启动时: 自动加载上次使用的模型
   - 运行时: 实时更新模型状态和配置

#### 线程安全

- **协程隔离**: 所有IO操作使用 `withContext(Dispatchers.IO)`
- **原子操作**: 使用 `@Volatile` 确保内存可见性
- **锁机制**: 文件操作使用适当的同步机制

#### 错误处理

- **降级策略**: 模型不存在时返回null，不影响应用运行
- **日志记录**: 详细记录所有操作和错误信息
- **恢复机制**: 配置文件损坏时自动创建默认配置

#### WebDAV 同步

1. **配置同步** (`syncModelConfig`)
   - 上传: 将本地 `models.json` 上传到远程目录
   - 下载: 从远程获取最新配置并合并
   - 冲突解决: 远程版本优先，本地作为备份

2. **模型文件同步** (`syncModelFiles`)
   - 增量同步: 只同步新增或更新的模型文件
   - 校验和: 使用文件大小和修改时间进行校验
   - 断点续传: 支持大文件分块传输

3. **同步策略**
   - 定时同步: 应用启动时自动同步
   - 手动同步: 用户主动触发同步操作
   - 离线模式: 网络不可用时使用本地缓存

#### 依赖注入

```kotlin
// Koin 模块配置
val modelModule = module {
    single<ModelRepository> { ModelRepositoryImpl(get(), get(), get()) }
    single { ManageModelsUseCase(get()) }
    single { WebDavClient() }
    single { ConfigRepository() }
}
```

### 完整使用示例

```kotlin
// 1. 初始化（自动从独立的AI配置加载）
val manageModelsUseCase = ManageModelsUseCase(modelRepository)

// 2. 同步远程配置（使用AI配置中的remoteConfigDir）
manageModelsUseCase.syncModelConfigToRemote()

// 3. 下载新模型（使用AI配置中的remoteModelDir）
val modelInfo = ModelInfo(
    id = "chat_detector_v2",
    name = "聊天界面检测器 v2",
    fileName = "chat_detector_v2.tflite",
    version = "2.0.0",
    fileSize = 0L,
    description = "优化版本",
    lastModified = System.currentTimeMillis(),
    isDownloaded = false
)
manageModelsUseCase.downloadModel(modelInfo)

// 4. 切换到新模型
manageModelsUseCase.switchModel("chat_detector_v2")

// 5. 使用 AI 匹配器
val aiMatcher = AIMatcher(modelRepository)
val result = aiMatcher.matchChatInterface(screenshot)
```

## 总结

### 核心特性

- ✅ **AI 推理**: 使用 TensorFlow Lite 替换 OpenCV 模板匹配
- ✅ **多模型支持**: 支持同时管理多个 AI 模型
- ✅ **远程同步**: 通过 WebDAV 实现模型配置和文件的云端同步
- ✅ **独立配置**: 使用独立的 `ai_config.json` 配置文件
- ✅ **自动管理**: 智能的模型下载、切换和清理
- ✅ **高性能**: NNAPI 硬件加速 + 多线程推理
- ✅ **容错设计**: 完善的错误处理和降级策略

### 架构优势

- **模块化**: 清晰的职责分离和依赖注入
- **可扩展**: 易于添加新的模型类型和同步方式
- **可维护**: 完整的文档和类型安全的 API
- **生产就绪**: 经过测试的错误处理和性能优化

### 文件结构

```
Android/data/[包名]/files/
├── ai_config.json                 # AI配置文件 (运行时)
├── Models/                        # AI模型文件目录
│   ├── models.json               # 模型列表配置
│   ├── chat_detector.tflite      # 主模型文件
│   └── chat_detector_v2.tflite   # 其他模型文件
└── ...

# 远程服务器目录结构 (WebDAV)
WebDAV服务器:
├── [baseDir]/                    # AI配置中的baseDir
│   ├── AI/Models/               # 模型文件目录
│   │   ├── chat_detector.tflite
│   │   └── chat_detector_v2.tflite
│   └── AI/Config/               # 配置文件目录
│       ├── ai_config.json       # AI配置同步
│       └── models.json          # 模型列表配置
└── ...
```

### 部署状态

- **开发环境**: ✅ 完成
- **测试环境**: ✅ 完成  
- **生产环境**: ✅ 就绪

该实现完全替换了原有的 OpenCV 依赖，提供了更准确、更灵活的聊天界面检测能力，使用独立的AI配置文件系统，与主监控配置解耦。

## 开发和部署

### 添加新模型

1. **训练模型**
   ```bash
   # 使用TensorFlow训练图像分类模型
   # 输入: 224x224 RGB图像
   # 输出: 5类概率分布 (微信日/夜间、QQ、微博、抖音)
   ```

2. **转换为TFLite格式**
   ```python
   import tensorflow as tf

   # 转换模型
   converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)
   converter.optimizations = [tf.lite.Optimize.DEFAULT]
   tflite_model = converter.convert()

   # 保存
   with open('chat_detector_v2.tflite', 'wb') as f:
       f.write(tflite_model)
   ```

3. **更新配置文件**
   - 编辑 `models.json` 添加新模型条目
   - 设置正确的文件名、版本号和描述

4. **部署到服务器**
   - 上传模型文件到WebDAV服务器的 `Models/` 目录
   - 上传更新后的 `models.json` 配置文件

5. **客户端更新**
   - 应用会自动检测配置更新
   - 用户可手动触发模型同步
   - 支持增量下载，只下载新的模型文件

### 版本控制策略

- **主版本**: `chat_detector.tflite` - 稳定的生产版本
- **实验版本**: `chat_detector_v2.tflite` - 测试新功能
- **回滚支持**: 可随时切换回旧版本
- **清理策略**: 删除不再使用的模型文件

### 故障排除

- **模型加载失败**: 检查文件是否存在，路径是否正确
- **推理失败**: 验证模型输入格式 (224x224 RGB)
- **同步失败**: 检查WebDAV连接和权限
- **切换失败**: 确保目标模型已下载且文件完整