# DF-OmtCamera Version History & Changelog

## [1.1.0] - 2026-09-18

### 🚀 Highlights
- **OMT TCP Server Transport**: Listens on port 6400 (or custom) for connections from official OMT receivers, OBS Studio OMT plugin, and vMix.
- **OMT Unicast TCP Client**: Sends standard length-prefixed `OMTMediaFrame` payloads (magic `0x4F4D`, media type, flags, PTS) over persistent TCP to receiver listeners.
- **mDNS / DNS-SD Service Publishing**: Native Android `NsdManager` registers `_omt._tcp` service automatically when live, enabling zero-config discovery in desktop receivers.
- **Enhanced Companion Receiver**: `apk/computer_app/omt_receiver.py` now supports `--tcp`, `--connect <PHONE_IP>`, and `--udp`.
- **App Version & Packaging**: Bumper version to `v1.1.0 (Build 2)`.

---

## [1.0.0] - 2026-09-18

### 🚀 Highlights
- **Direct Camera Start**: App launches immediately into live camera preview.
- **Hardware H.264 Encoder**: Uses `MediaCodec` in Baseline Profile (`KEY_LATENCY = 0`) to achieve zero B-frame latency.
- **Dual Network Packetizer**:
  - **OMT UDP**: Custom lightweight 20-byte header with microsecond PTS timestamps and keyframe flags.
  - **RFC 6184 RTP**: Industry standard RTP streaming compatible with OBS, vMix, GStreamer, and FFmpeg.
- **Live Telemetry & HUD**: Instantaneous FPS calculation, network throughput (kbps), estimated latency, and packet counters.
- **Audio Pipeline**: Real-time microphone capture with low-latency AAC encoding.
- **Packaging & Tools**:
  - `apk/DF-OmtCamera-v1.1.0-debug.apk`: Latest installable Android APK.
  - `apk/computer_app/`: Universal test receiver scripts (Python + Node.js) for testing on PC.
  - `apk/android_viewer/`: Android OMT Viewer client source code & APK.
