# Quick Start - Rescue360 Android App

## 🚀 5-Minute Setup

### 1. **Clone & Open**
```bash
cd android/
# Open with Android Studio
```

### 2. **Current Functionality** (Ready to Test)
- ✅ UI fully designed
- ✅ Button handlers implemented
- ✅ Logging setup
- ✅ Coroutine structure
- ✅ Permission declarations
- ⏳ **Camera integration** (TODO: add Insta360 SDK)

### 3. **Before Integration - Test UI**
```bash
# Build APK
./gradlew build

# Install on device
./gradlew installDebug

# Run on emulator or device
```

**You'll see:**
- Three buttons: Connect, Capture, Send
- Status messages
- Error handling (graceful failures)
- Dark UI theme

---

## 📋 What's Ready

| Component | Status | Details |
|-----------|--------|---------|
| UI Layout | ✅ Done | ImageView + 3 buttons |
| Main Activity | ✅ Done | Button handlers, logging |
| Camera Manager | 🔶 Skeleton | Ready for SDK integration |
| Permissions | ✅ Done | WiFi, Camera, Network |
| Dependencies | ✅ Done | Retrofit, Coroutines, Timber |
| Logging | ✅ Done | Timber debug logs |
| Error Handling | ✅ Done | Try-catch, user feedback |

---

## 🔧 What Needs SDK Integration

### 1. **Insta360 Camera Connection**
**File:** `Insta360CameraManager.kt` - `connect()` function

Where to add:
```kotlin
// Around line 50
// TODO: Implement actual connection
```

What to implement:
- Scan for Insta360_X4 on network
- Establish WiFi connection
- Subscribe to frame stream
- Handle connection errors

### 2. **Frame Streaming**
**File:** `Insta360CameraManager.kt` - Frame callback

Receive raw YUV frames from camera, convert to Bitmap

### 3. **Cloud Send** 
**File:** `MainActivity.kt` - `sendFrameToCloud()` function

Around line 150 - TODO comment marks the spot

What to add:
```kotlin
// 1. Compress frame to JPEG
// 2. Create multipart request
// 3. POST /api/detect-frame
// 4. Display results
```

---

## 📚 Insta360 SDK Resources

1. **Download SDK:**
   ```
   https://github.com/Insta360Develop/Android-SDK
   ```

2. **Documentation:**
   ```
   https://insta360develop.github.io/Insta360-Developer_Docs/
   → X Series → Android SDK → Overview
   ```

3. **Add to Project:**
   ```kotlin
   // In build.gradle.kts
   implementation("com.insta360:sdk-x4:1.x.x")  // Version TBD
   ```

4. **Key Classes to Use:**
   - `Insta360Camera` - Main camera instance
   - `FrameCallback` - Receive frames
   - `CameraDiscovery` - Find camera on network

---

## 🎯 Next Steps (After SDK Integration)

### Step 1: Camera Connection
- [ ] Add Insta360 SDK dependency
- [ ] Implement `Insta360CameraManager.connect()`
- [ ] Test: Can connect, get frames, display in preview
- [ ] Test: Can disconnect cleanly

### Step 2: Frame Capture
- [ ] Implement YUV → Bitmap conversion
- [ ] Test: Capture button saves frame
- [ ] Test: Frame quality acceptable

### Step 3: Cloud Integration (When Server Ready)
- [ ] Implement `sendFrameToCloud()`
- [ ] Create Retrofit API client
- [ ] Test: POST to server endpoint
- [ ] Parse detection response
- [ ] Draw bounding boxes

---

## 🧪 Testing Checklist

### Pre-SDK (UI Only)
- [ ] App launches
- [ ] Buttons visible
- [ ] Status text updates
- [ ] Connect button shows graceful error
- [ ] Logs appear in Logcat

### Post-SDK Integration
- [ ] Can scan for Insta360_X4
- [ ] Camera connects
- [ ] Frames appear in preview
- [ ] Capture button works
- [ ] Can disconnect

### Post-Server Integration
- [ ] Send button active after capture
- [ ] Frame POSTs to server
- [ ] Server returns detections
- [ ] Bounding boxes drawn
- [ ] Status shows results

---

## 🐛 Debugging Tips

### Logcat Filtering
```bash
adb logcat | grep "Rescue360"
```

### Common Issues

| Error | Fix |
|-------|-----|
| "Camera not found" | Ensure WiFi connected to Insta360_X4 |
| "No frame available" | Check SDK is integrated and streaming |
| "Send failed" | Verify server URL in code matches actual server |

### Mock Testing (No Camera)
Current code gracefully handles missing SDK - buttons won't crash

---

## 📞 Quick Reference

### Files to Edit for Integration

1. **Camera Connection:**
   ```
   Insta360CameraManager.kt (line ~50)
   ```

2. **Frame Processing:**
   ```
   Insta360CameraManager.kt (line ~115)
   ```

3. **Cloud Send:**
   ```
   MainActivity.kt (line ~150)
   ```

### Key Functions

```kotlin
// In MainActivity.kt
connectToCamera()       // Connect flow
captureFrame()          // Capture flow
sendFrameToCloud()      // Cloud flow (TODO)

// In Insta360CameraManager.kt
connect()               // SDK connection (TODO)
captureFrame()          // Get current frame
disconnect()            // Cleanup
```

---

## ✅ Success Criteria

### Phase 1: UI ✅
- App runs without crash ✅
- Buttons respond ✅
- Status updates ✅

### Phase 2: Camera (TODO)
- Connects to Insta360_X4
- Displays live frame
- Captures on demand

### Phase 3: Cloud (TODO)
- Sends frame to server
- Displays detections
- Shows bounding boxes

---

**🎯 Current Stage:** Phase 1 ✅ | **Next:** Phase 2 (SDK Integration)

**Est. Time to Full Integration:** 2-3 days (with SDK ready)
