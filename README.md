# Ruang Kamera

A local-first web viewer for IP cameras that expose ONVIF and RTSP. The interface is designed for desktop and mobile browsers on a trusted home network.

## Project status

This repository contains the application source. Camera addresses and credentials are supplied at runtime and must not be committed. FFmpeg is a separate runtime dependency and is intentionally excluded from this repository.

## Safety

Use this viewer only on a trusted local network. Do not expose its port directly to the public internet. Never commit camera passwords, device IDs, pairing codes, or private network details.
