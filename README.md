# Screen Monitor Module

Android screen monitoring module with template matching and WebDAV integration.

## Features

- **Clean Architecture**: Domain/Infrastructure layers with dependency injection (Koin)
- **Template Matching**: Multi-scale grayscale template matching with OpenCV
- **WebDAV Integration**: Remote config sync and file upload
- **Frame Processing**: Advanced frame validation with deduplication
- **Resource Management**: Proper lifecycle management for Mat, Bitmap, and network connections

## Architecture

```
com.youyou.monitor/
├── core/
│   ├── domain/          # Business logic & interfaces
│   │   ├── model/       # Domain models
│   │   ├── repository/  # Repository interfaces
│   │   └── usecase/     # Use cases
│   └── matcher/         # Template matching abstractions
├── infra/               # Infrastructure implementations
│   ├── repository/      # Repository implementations
│   ├── network/         # WebDAV client
│   ├── matcher/         # OpenCV matcher
│   ├── processor/       # Frame processing
│   ├── logger/          # Logging utilities
│   └── task/            # Scheduled tasks
├── di/                  # Dependency injection (Koin)
└── MonitorService.kt    # Public facade
```

## Dependencies

- **Kotlin Coroutines**: Async operations
- **Koin**: Dependency injection
- **OpenCV**: Template matching
- **Sardine**: WebDAV client
- **OkHttp**: HTTP connections

## Integration

### 1. Add to `settings.gradle`:
```gradle
include ':screen-monitor'
```

### 2. Add dependency in `app/build.gradle`:
```gradle
dependencies {
    implementation project(':screen-monitor')
}
```

### 3. Initialize Koin module:
```kotlin
import com.youyou.monitor.di.monitorModule

startKoin {
    modules(monitorModule)
}
```

### 4. Inject MonitorService:
```kotlin
private val monitorService: MonitorService by inject()
```

### 5. Setup device ID provider:
```kotlin
MonitorService.deviceIdProvider = { 
    // Return device ID from your FFI/native layer
    YourFFI.getDeviceId()
}
```

### 6. Pass frames:
```kotlin
imageReader.setOnImageAvailableListener({ reader ->
    reader.acquireLatestImage()?.use { image ->
        val buffer = image.planes[0].buffer
        val scale = 1 // or 2 for downscaling
        monitorService.onFrameAvailable(buffer, image.width, image.height, scale)
    }
}, handler)
```

## Configuration

Place `monitor_config_default.json` in `src/main/assets/`:

```json
{
  "threshold": 0.92,
  "enabled": true,
  "autoLoad": true,
  "webdav_servers": [
    {
      "url": "http://example.com/dav",
      "username": "user",
      "password": "pass",
      "monitor_dir": "monitor",
      "upload_dir": "monitor/upload"
    }
  ]
}
```

## Usage

```kotlin
// Start monitoring
monitorService.start()

// Update WebDAV config
monitorService.updateWebDavServers(servers)

// Stop monitoring
monitorService.stop()
```

## Upload Path Structure

```
/{remoteUploadDir}/{deviceId}/{YYYY-MM-DD}/{timestamp}_{signature}.png
```

Example: `/monitor/upload/ABC123/2026-01-04/20260104_103045_match_wx1.png`

## License

See parent project license.
