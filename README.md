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

## Safety

This MVP is LAN-only and does not encrypt local HTTP traffic. Do not expose its port to the internet. For remote access, use a trusted VPN into the home network. See [SECURITY.md](SECURITY.md).

## Project structure

- `src/server.js`: local HTTP server, ONVIF discovery, and FFmpeg streaming
- `public/index.html`: responsive browser interface

The project is an early MVP. PTZ controls, audio, recording, multiple cameras, and automated packaging are not included.
