# Security notes

Ruang Kamera is intended for a trusted home LAN. The current MVP does not provide HTTPS, user accounts, or remote access.

- Do not forward the viewer port from your router to the public internet.
- Use a unique camera password and keep it out of source files, screenshots, and issue reports.
- The pairing PIN is generated at startup. Treat it as a local access code and do not publish it.
- Camera credentials are supplied at runtime. Do not commit them or device IDs.
- FFmpeg is a separate dependency and is deliberately excluded from this repository.
- LAN traffic is not encrypted in this MVP. Use only on a trusted network.
