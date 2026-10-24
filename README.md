# Jovial

A lightweight native Android client for [Ganymede](https://github.com/Zibbp/ganymede), built with Kotlin, Jetpack Compose, and Media3 (ExoPlayer).

## Features

- **Ganymede REST API Integration**: Connects to your local Ganymede instance or over private networks (e.g., ZeroTier / Tailscale) with optional API Key authentication.
- **Adaptive Chat & Video HUD**:
  - **Side-by-Side Mode**: Split screen video and chat with instant left/right swapping.
  - **Overlay Mode**: Fullscreen video with semi-transparent floating chat.
  - **Hidden Mode**: Distraction-free fullscreen video.
  - **Quick Chat Toggle**: Show/hide chat with one tap from the player controls.
  - **Customizable Proportions**: Adjust chat panel width (18% to 45%), font size, and background opacity live while watching.
- **Synchronized Live Chat**:
  - Automatically synchronizes chat comments with playback time, seeks, and scrubbing.
  - Continuous autoscroll with buffer management.
- **7TV / BTTV / FFZ Emote Support**:
  - Renders inline animated WebP & GIF emotes from 7TV, BetterTTV, and FrankerFaceZ, alongside first-party Twitch and embedded emotes.
- **Immersive Fullscreen Playback**:
  - Automatically hides status and navigation bars during video playback.
- **Local Playback Resume**:
  - Saves your watch position locally so you can resume where you left off.
  - Progress indicators on the VOD browser grid.

## Building from Source

```bash
./gradlew assembleDebug
```

The compiled APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Releases

Download the latest pre-built APK (`jovial.apk`) from the [Releases](https://github.com/rdenno/jovial/releases) tab.
