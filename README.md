# 🎶 Vibe Music

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-0095D5?style=for-the-badge&logo=kotlin&logoColor=white)
![Media3](https://img.shields.io/badge/Media3_ExoPlayer-4285F4?style=for-the-badge&logo=google&logoColor=white)
![Open Source](https://img.shields.io/badge/Open_Source-FOSS-brightgreen?style=for-the-badge)

**Vibe Music** is a modern, feature-rich, and open-source Android music player built for audiophiles and power users. Stream in high fidelity, sync playback with friends in real-time, and control your library across your entire ecosystem—all wrapped in a stunning, highly animated UI.

---

## 🚀 Key Features

*   **Vibee AI Assistant:** A built-in, intelligent assistant to help you discover music, manage your queue, and elevate your listening experience.
*   **Listen Together:** Flawless, real-time playback synchronization with friends using a custom low-latency WebSocket and SSE engine.
*   **High-Fidelity Saavn & YouTube:** Stream your favorite tracks ad-free, including Saavn 320 kbps top-tier audio quality.
*   **Parametric Auto EQ:** Tailor your sound perfectly with built-in AutoEQ support featuring thousands of headphone profiles.
*   **Smart Shuffle & Infinite Playback:** Analyze your queue's vibe to inject recommended tracks seamlessly, plus unlimited scrolling for Automix and Daily Discover.
*   **Liquid Glass UI & Visualizer:** A stunning, translucent aesthetic featuring premium micro-animations and a real-time reactive audio visualizer.
*   **Ecosystem Ready:** Full native support for Android Auto and Wear OS.
*   **Native Cast Volume Sync:** Control Google Cast device volume directly via your phone's hardware buttons.
*   **Offline Local Backup:** Export and import your playlists, history, and settings seamlessly via local JSON payloads.
*   **Quality-of-Life:** Swipe-to-queue, customizable home screen widgets, and "Recently Played" integration.

---

## 🛠️ Architecture & Under-the-Hood

Vibe Music is built entirely in **Kotlin** using **Jetpack Compose** and **Media3 (ExoPlayer)**. 

Recent architectural milestones include a complete rewrite of our multiplayer synchronization:
*   **ListenTogetherManager:** Intercepts Media3 player events to orchestrate playback state, timeline discontinuities, and seek events across all clients seamlessly.
*   **Real-Time Networking:** Powered by `ListenTogetherClient` using OkHttp WebSockets and Server-Sent Events (SSE) for robust room administration and low-latency signaling.
*   **Deep Service Integration:** `MusicService` exposes raw player lifecycle hooks directly to the sync orchestrator.
*   **Compose Injection:** The multiplayer manager is wired into the core dependency graph and exposed via `CompositionLocal` in the `MainActivity` for frictionless UI control.

---
## ⚙️ Installation

You can download the latest stable release directly from the GitHub releases page.

1. Go to the releases section.
2. Download the latest `vibe-music-release.apk`.
3. Install the APK on your Android device (ensure "Install from Unknown Sources" is enabled).

---


