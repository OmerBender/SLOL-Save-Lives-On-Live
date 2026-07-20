# SLOL Android Field App

This Android application is the field-device client for SLOL.

It connects to an Insta360 X4 camera over Wi-Fi, displays the live preview, sends selected frames to the cloud WebSocket server, receives YOLO detections, and draws bounding boxes on the Android screen.

## Responsibilities

* Connect to Insta360 X4 Wi-Fi
* Show camera preview on the Android device
* Send JPEG frames to the cloud server
* Receive detection results over WebSocket
* Render detection overlays on screen
* Start/stop scenario recording on the server when requested

## Configuration

Copy:

```text
gradle.properties.example
```

to:

```text
gradle.properties
```

Then fill in:

```properties
INSTA360_MAVEN_USERNAME=your_username
INSTA360_MAVEN_PASSWORD=your_password
RESCUE360_SERVER_WS_URL=ws://<SERVER_HOST>:8000/ws
INSTA360_X4_WIFI_PASSWORD=
```

The Insta360 SDK Maven credentials and model/server access are controlled by the project owner.

## Build

```bash
cd android
./gradlew :app:assembleDebug
```

Install on a connected Android device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Network Modes

For cloud testing, the Android app should connect to:

```text
ws://<SERVER_HOST>:8000/ws
```

For local testing through USB ADB reverse:

```bash
adb reverse tcp:8000 tcp:8000
```

Then use:

```text
ws://127.0.0.1:8000/ws
```

## Important Notes

* Android source code is included in Git.
* Build outputs are excluded.
* `local.properties` is excluded.
* Private server URLs and passwords should be configured through `gradle.properties`, not committed to Git.
* The app does not contain the YOLO model. Inference runs on the cloud server.
