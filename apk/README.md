# OmtCam & OMT Ecosystem Package

Welcome to **OmtCam** — the ultra-low latency Open Media Transport camera broadcasting and viewing suite.

---

## 📁 Folder Structure

```
apk/
├── OmtCam-v1.0.0-debug.apk         <- Ready-to-install Android Camera & Viewer APK
├── VERSIONS.json                   <- Machine-readable version & build tracking catalog
├── CHANGELOG.md                    <- Human-readable version release notes
├── android_viewer/
│   └── OmtViewer-v1.0.0-debug.apk  <- Installable Android Viewer APK
└── computer_app/
    ├── omt_receiver.py             <- Standalone Python PC test receiver
    └── omt_receiver.js             <- Standalone Node.js PC test receiver
```

---

## 🚀 1. Installing on Android
1. Download `OmtCam-v1.0.0-debug.apk` onto your Android phone.
2. Enable "Install from unknown sources" if prompted, and install.
3. Open **OmtCam**:
   - The camera preview begins **immediately** upon launch.
   - Tap **"GO LIVE"** to start streaming over UDP / OMT to your destination.
   - Tap the **Settings** icon to configure your destination PC IP address, port, and encoding preset.
   - Tap the **TV** icon in the bottom dock to toggle into **OMT Viewer Mode** on Android!

---

## 💻 2. Testing on Your Computer

You mentioned you already have OMT on your PC to test. If you also want quick standalone diagnostic receivers:

### Option A: Python Test Receiver
```bash
python3 apk/computer_app/omt_receiver.py 9998
```
Or pipe directly into FFplay / VLC / OBS:
```bash
python3 apk/computer_app/omt_receiver.py 9998 --pipe | ffplay -f h264 -i -
```

### Option B: Node.js Test Receiver
```bash
node apk/computer_app/omt_receiver.js 9998
```

Both utilities display real-time telemetry: incoming bitrate (kbps), live FPS, packet counter, and data volume.

---

## 📱 3. Android Viewer Mode

The app contains a **dual mode architecture**:
1. **Camera Mode (Sender)**: Streams live H.264 camera video via OMT or RTP.
2. **Viewer Mode (Receiver)**: Listens for incoming OMT packets on any configured port and displays live source IP, FPS, and bitrate telemetry. Tap the **TV** icon in the dock to switch to Viewer Mode, or the **CAM** button to return.

---

## 🏷️ 4. Version System
The version tracking system lives in:
- `VERSIONS.json`: tracks release versions, build codes, dates, and feature flags.
- `CHANGELOG.md`: records detailed release history.
- The in-app Settings sheet automatically shows the current active version: **`OmtCam v1.0.0 (Build 1)`**.
