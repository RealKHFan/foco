# Foco

A Google-free camera for Pixel phones, made mainly for **GrapheneOS**, laid out like Pixel Camera.

Foco has **no internet permission**, no Google Play Services, no Firebase and no tracking.
It uses Android's open-source CameraX library and the phone's own camera hardware.

<p>
<img src="docs/screenshots/01_photo.png" width="200">
<img src="docs/screenshots/02_photo_pro_controls.png" width="200">
<img src="docs/screenshots/03_photo_settings_general.png" width="200">
<img src="docs/screenshots/06_video.png" width="200">
</p>

## Features

- Pixel Camera-style layout: gallery bubble, shutter, flip button, zoom chips (.5 · 1× · 2 · 5) with smooth zoom, swipeable mode strip (Action Pan · Long Exposure · Photo · Night · Panorama), Photo/Video toggle, settings and Pro buttons
- **Foco processing:** every photo is merged from several frames (aligned, ghost-free) for less noise and recovered highlights, saved as **Ultra HDR** on Android 14+
- **Night:** automatic in the dark (or as a mode), multi-second handheld capture with a "hold still" guide, countdown and Off / Auto / Max slider
- **Sharper zoom:** extra frames and sharpening when zoomed past a lens
- **Long Exposure:** 3 s of frames lined up on the background and averaged, so water, traffic and crowds blur
- **Action Pan:** follow a moving subject; frames are lined up on the subject so the background streaks
- **Panorama:** turn slowly; frames are kept by gyroscope, projected onto a cylinder, lined up and blended, with a live strip and level guide
- **Gallery button opens Google Photos on the exact photo** (same `com.android.camera.action.REVIEW` hand-off with the MediaStore ID as Pixel Camera), otherwise Foco's own swipeable viewer of recent shots
- Photo settings sheet (General / Pro): flash, timer 3/5/10 s, macro focus, 9:16 / 4:3, 12 / 50 MP, RAW + JPEG, lens selection
- Pro controls: brightness, shadows, white balance, focus, shutter speed, ISO, with live readout; Pixel-style sliders where the scale moves under a fixed marker
- Lock-screen camera (shows only photos taken since unlocking), and works as the camera for other apps
- Tap to focus, long-press for AE/AF lock, pinch / drag zoom, double-tap 2×, volume-key shutter, grid, level with degrees and top-down crosshair, quick access controls
- Offline QR code scanning, dirty lens warning, optional location tag, option to strip phone model from photos
- Video: 1080p / 4K, Auto (24–30) / 30 / 60 fps, stabilization, 10-bit HDR, torch, pause, photo while recording; checks the microphone when you switch to Video so a blocked mic is caught before you film

Each feature is only offered when the phone actually allows it for third-party apps; otherwise it shows "Unavailable".

## What it can't do

Pixel Camera's own processing (HDR+, Night Sight, Super Res Zoom, Magic Eraser, Best Take, etc.) is Google's software and
isn't available to other apps. Foco has its own, simpler multi-frame processing instead, so results differ. Audio zoom isn't
possible with the video recorder Foco uses.

## Build

```
./gradlew assembleDebug
```

Release signing reads `keystore.properties` (not in the repo).

## License

GPL-3.0. Written by [RealKHFan](https://github.com/RealKHFan) with help from Claude (Anthropic).
