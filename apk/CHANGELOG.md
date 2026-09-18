# OmtCam Version History & Changelog

## [1.0.0] - 2026-09-18

### 🚀 Highlights
- **Direct Camera Start**: App launches immediately into live camera preview.
- **Hardware H.264 Encoder**: Uses `MediaCodec` in Baseline Profile (`KEY_LATENCY = 0`) to achieve zero B-frame latency.
- **Dual Network Packetizer**:
  - **OMT (Open Media Transport)**: Custom lightweight 20-byte header with microsecond PTS timestamps and keyframe flags.
  - **RFC 6184 RTP**: Industry standard RTP streaming compatible with OBS, vMix, GStreamer, and FFmpeg.
- **Live Telemetry & HUD**: Instantaneous FPS calculation, network throughput (kbps), estimated latency, and packet counters.
- **Audio Pipeline**: Real-time microphone capture with low-latency AAC encoding.
- **Packaging & Tools**:
  - `apk/OmtCam-v1.0.0-debug.apk`: Current installable Android APK.
  - `apk/computer_app/`: Universal test receiver scripts (Python + Node.js) for testing on PC.
  - `apk/android_viewer/`: Android OMT Viewer client source code & APK.
