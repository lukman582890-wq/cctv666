# Ruang Kamera

A local-first web viewer for IP cameras that expose ONVIF and RTSP. The responsive interface is designed for desktop and mobile browsers on the same trusted home network.

## Current MVP

- Connect one camera by local IP address.
- Query ONVIF for a stream URI, then try common V380 RTSP paths.
- Convert RTSP to browser-friendly MJPEG with FFmpeg.
- Keep camera credentials in runtime memory only.
- Use a startup pairing PIN for local access.

Camera firmware varies. A visible ONVIF setting does not guarantee that RTSP streaming is enabled.

## Requirements

- Node.js 18 or newer
- A Windows FFmpeg build containing `ffmpeg.exe`
- PC and camera on the same local network

FFmpeg is not included. Download a Windows build from the links on the [official FFmpeg download page](https://ffmpeg.org/download.html), then place `ffmpeg.exe` beside `src/server.js` or set the `FFMPEG_PATH` environment variable.

## Run on Windows

1. Start the app with `npm start` from the project folder, or run `Jalankan.bat`.
2. Open the local URL printed in the terminal.
3. To use a phone, open the PC's LAN URL while the phone is on the same Wi-Fi.
4. Enter the startup PIN, camera's local IP, ONVIF username, and camera password.

Allow Node.js on the Windows **Private network** profile if the firewall asks. Do not share the PIN or camera password.

## Android app

The Android app connects directly to the camera over the home network. A PC and the Windows viewer do not need to stay running. The camera must expose a compatible RTSP stream for video; ONVIF is used to discover the stream and control PTZ/presets when supported. Keep the phone and camera on the same Wi-Fi.

1. Install `android/app/build/outputs/apk/debug/app-debug.apk` on the phone.
2. Open Ruang Kamera and enter the camera's private LAN IP, ONVIF username, and password.
3. Tap **Hubungkan**. The password is kept in memory only while the app runs.

The APK accepts private IPv4 camera addresses and is signed with the standard debug key. It is suitable for personal installation; a public Play Store release needs a release signing key and store review. GitHub Actions builds the APK for pushes and pull requests; download it from the run's `RuangKamera-debug-apk` artifact. Some camera firmwares may require a different RTSP path or codec. Camera microSD recording settings are not yet implemented; generic ONVIF does not provide verified access to this model's proprietary recording controls.

## Safety

This MVP is LAN-only and does not encrypt local HTTP traffic. Do not expose its port to the internet. For remote access, use a trusted VPN into the home network. See [SECURITY.md](SECURITY.md).

## Project structure

- `src/server.js`: local HTTP server, ONVIF discovery, and FFmpeg streaming
- `public/index.html`: responsive browser interface

The project is an early MVP. ONVIF PTZ movement and presets are included when the camera exposes a PTZ service. Camera microSD recording settings, multiple cameras, and automated Windows packaging are not included yet. Generic ONVIF does not configure continuous/event recording, camera audio recording, or SD/HD recording quality; those settings need verified support for the camera firmware's own API.
