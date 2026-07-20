# Implementation Guide - Rescue360 Live Detector

## 🎯 Complete App - Ready to Deploy (After SDK Integration)

### Current Status
✅ **App Structure Complete** - All components built and connected
🔶 **SDK Integration Pending** - Waiting for Insta360 SDK
🔶 **Server Deployment Pending** - Will work when server is on GCP

---

## 📱 How the App Works

### Flow Diagram
```
[START BUTTON]
    ↓
[WiFi Selection Dialog]
    ↓
[Connect to Camera WiFi]
    ↓
[Connect to Insta360 X4 Camera]
    ↓
[Start Frame Stream (Live)]
    ↓
[Auto-Send Each Frame to Cloud]
    ↓
[Receive Detections from Server]
    ↓
[Draw Boxes on Screen (Real-Time)]
    ↓
[Display FPS + Detection Count]
```

### Key Features Implemented

| Feature | File | Status |
|---------|------|--------|
| WiFi Network Selection | `WiFiConnectionManager.kt` | ✅ Ready |
| Camera Connection | `Insta360CameraManager.kt` | 🔶 Skeleton |
| Live Frame Display | `MainActivity.kt` | ✅ Ready |
| Detection Overlay | `DetectionOverlayView.kt` | ✅ Ready |
| Cloud Communication | `DetectionClient.kt` | ✅ Ready |
| Real-time Processing | `FrameProcessor.kt` | ✅ Ready |

---

## 🔧 Integration Steps

### Step 1: Add Insta360 SDK

**File:** `app/build.gradle.kts`

Add this to `dependencies`:
```kotlin
// TODO: Replace with actual Insta360 SDK version
// Download from: https://github.com/Insta360Develop/Android-SDK
implementation("com.insta360:sdk-x4:1.x.x")
```

**Setup Maven Repository** (if needed):
```kotlin
// In build.gradle.kts repositories section
maven {
    url = uri("https://maven.insta360.com/repository/android")
}
```

---

### Step 2: Implement Camera Connection

**File:** `Insta360CameraManager.kt` - `connect()` function

**Location:** Line 50-80 (marked with TODO)

**What to implement:**

```kotlin
suspend fun connect(...): Boolean = withContext(Dispatchers.IO) {
    // 1. Discovery Phase
    val discoverer = Insta360CameraDiscovery()
    val devices = discoverer.discoverDevices(timeoutMs = 3000)
    val device = devices.firstOrNull()
        ?: throw Exception("No Insta360 camera found")

    // 2. Connection Phase
    camera = Insta360Camera(device)
    camera.connect()

    // 3. Frame Stream Phase
    camera.startFrameStream(
        format = FrameFormat.EQUIRECTANGULAR_YUV,
        resolution = Resolution.UHD_8K,
        frameRate = 30,
        onFrame = { yuvFrame ->
            val bitmap = yuvToBitmap(yuvFrame)
            currentFrame = bitmap
            frameCallback?.invoke(bitmap)  // Callback to MainActivity
        },
        onError = { error ->
            errorCallback?.invoke(error)
        }
    )

    isConnected = true
    return@withContext true
}
```

**Key Points:**
- Frame format: Full 360° equirectangular
- Resolution: Up to 8K (8192x4096)
- Frame rate: 30 FPS
- YUV output (needs conversion to Bitmap)

---

### Step 3: Implement YUV to Bitmap Conversion

**File:** `Insta360CameraManager.kt` - `yuvToBitmap()` function

**Why:** Insta360 outputs YUV 4:2:0 format, need to convert to Android Bitmap

**Implementation Options:**

#### Option A: Native Code (Fastest)
```kotlin
// Use native code (C++) for YUV conversion
// Faster than Java implementation
private external fun nativeYUVToBitmap(yuvData: ByteArray, width: Int, height: Int): Bitmap
```

#### Option B: RenderScript (Medium Speed)
```kotlin
// Use Android's RenderScript for GPU acceleration
private fun yuvToBitmap(yuvData: ByteArray, width: Int, height: Int): Bitmap {
    val rs = RenderScript.create(context)
    val script = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
    
    val input = Allocation.createSized(rs, Element.U8(rs), yuvData.size)
    input.copyFrom(yuvData)
    
    val output = Allocation.createSized(rs, Element.RGBA_8888(rs), width * height)
    script.setInput(input)
    script.forEach(output)
    
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    output.copyTo(bitmap)
    return bitmap
}
```

#### Option C: Java (Slowest)
```kotlin
// Pure Java - simple but slower
private fun yuvToBitmap(yuvData: ByteArray, width: Int, height: Int): Bitmap {
    val pixelCount = width * height
    val rgbData = IntArray(pixelCount)
    
    for (i in 0 until pixelCount) {
        val y = yuvData[i].toInt() and 0xFF
        val u = yuvData[pixelCount + (i / 4)].toInt() and 0xFF
        val v = yuvData[pixelCount + pixelCount / 4 + (i / 4)].toInt() and 0xFF
        
        // YUV to RGB conversion
        val r = (y + 1.402 * (v - 128)).toInt().coerceIn(0, 255)
        val g = (y - 0.344136 * (u - 128) - 0.714136 * (v - 128)).toInt().coerceIn(0, 255)
        val b = (y + 1.772 * (u - 128)).toInt().coerceIn(0, 255)
        
        rgbData[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }
    
    return Bitmap.createBitmap(rgbData, width, height, Bitmap.Config.ARGB_8888)
}
```

**Recommendation:** Use RenderScript for balance of speed and simplicity.

---

### Step 4: Configure Server URL

**File:** `DetectionClient.kt` - Constructor

```kotlin
class DetectionClient(
    private val serverUrl: String = "http://127.0.0.1:8000/"  // ← Change this
)
```

When server is deployed to GCP:
```kotlin
private val serverUrl: String = "http://[GCP_IP]:8000/"  // Your GCP VM IP
```

Or make it configurable:
```kotlin
// In MainActivity
val serverUrl = BuildConfig.SERVER_URL  // From build.gradle
val client = DetectionClient(serverUrl = serverUrl)
```

In `build.gradle.kts`:
```kotlin
buildTypes {
    debug {
        buildConfigField("String", "SERVER_URL", "\"http://127.0.0.1:8000/\"")
    }
    release {
        buildConfigField("String", "SERVER_URL", "\"http://[GCP_IP]:8000/\"")
    }
}
```

---

## 🚀 Build & Deploy

### 1. Build APK
```bash
cd android/
./gradlew clean build
```

### 2. Install on Device
```bash
./gradlew installDebug
```

### 3. Run
```bash
# The app will launch automatically
# Or tap the app icon in your device
```

---

## 📊 Testing Checklist

### Pre-SDK Integration
- [ ] App compiles without errors
- [ ] Layout displays correctly
- [ ] Buttons visible and clickable
- [ ] No crashes on startup

### Post-SDK Integration
- [ ] WiFi network list appears
- [ ] Can select camera network
- [ ] Connects to network
- [ ] Can discover Insta360 X4
- [ ] Frame stream starts
- [ ] Live preview shows frames
- [ ] FPS counter updates

### Post-Server Integration
- [ ] Frame sends to server successfully
- [ ] Server returns detections
- [ ] Bounding boxes draw correctly
- [ ] Confidence scores display
- [ ] Real-time updates smooth (≥10 FPS)

---

## 🐛 Debugging

### Logcat Filtering
```bash
adb logcat | grep "Rescue360"
```

### Common Issues

| Issue | Cause | Fix |
|-------|-------|-----|
| App crashes on start | SDK not integrated | Add SDK dependency |
| No WiFi networks show | Permission missing | Check AndroidManifest.xml |
| Can't connect to camera | Wrong network | Ensure device on camera's WiFi |
| No frames display | YUV conversion fails | Implement YUV→RGB conversion |
| Server connection fails | Wrong IP/port | Update ServerUrl in DetectionClient |
| Bounding boxes wrong position | Coordinate scaling issue | Check DetectionOverlayView.kt |

### Enable Debug Logging
```kotlin
// In MainActivity.onCreate()
if (BuildConfig.DEBUG) {
    Timber.plant(Timber.DebugTree())
}
```

---

## 📝 Important Notes

### Permissions
All required permissions already in `AndroidManifest.xml`:
- ✅ INTERNET (for cloud communication)
- ✅ CAMERA (for camera feed)
- ✅ CHANGE_NETWORK_STATE (for WiFi connection)
- ✅ ACCESS_NETWORK_STATE (for WiFi status)
- ✅ WRITE_EXTERNAL_STORAGE (if saving frames)

### Performance Tuning

**Frame Processing Rate:**
```kotlin
// In FrameProcessor.kt
private var detectionInterval = 100L  // Send every 100ms (10 FPS)
```

Adjust based on server latency:
- Faster server? Reduce to 50L (20 FPS)
- Slower server? Increase to 200L (5 FPS)

**FPS Display:**
Shows actual frame rate from camera (usually 30 FPS)
Detection rate shown in status (usually 10 FPS due to network)

---

## 🎨 UI Customization

### Colors (Bounding Boxes)
**File:** `DetectionOverlayView.kt` - `classColors` map

```kotlin
private val classColors = mapOf(
    "hand" to Color.parseColor("#00FFFF"),      // Cyan
    "arm" to Color.parseColor("#FFA500"),       // Orange
    "head" to Color.parseColor("#FF0000"),      // Red
    "leg" to Color.parseColor("#FF00FF"),       // Magenta
    "foot" to Color.parseColor("#FFFF00"),      // Yellow
    "person" to Color.parseColor("#00FF00")     // Green
)
```

### Font Size
**File:** `DetectionOverlayView.kt`

```kotlin
paintText.textSize = 20f  // Change this for larger/smaller labels
```

---

## 📦 Dependencies Summary

| Library | Purpose | Version |
|---------|---------|---------|
| Retrofit | HTTP client | 2.10.0 |
| OkHttp | Networking | 4.11.0 |
| Kotlin Coroutines | Async | 1.7.3 |
| Timber | Logging | 5.0.1 |
| AndroidX Lifecycle | UI lifecycle | 2.7.0 |
| Insta360 SDK | Camera | (TBD) |

---

## ✅ Success Criteria

### Phase 1: UI/Layout ✅
- App compiles
- All buttons work
- Status updates display

### Phase 2: Camera Integration 🔶
- Connects to camera WiFi
- Displays live frames
- Shows FPS counter

### Phase 3: Cloud Integration 🔶
- Sends frames to server
- Receives detections
- Draws bounding boxes
- Shows real-time results

---

## 📞 Support Resources

- **Insta360 SDK Docs**: https://insta360develop.github.io/Insta360-Developer_Docs/
- **Android Developer Docs**: https://developer.android.com/
- **Rescue360 Server**: `/path/to/app/main.py`

---

**Status:** Ready for integration ✅ | **Est. Time:** 2-3 days with SDK
