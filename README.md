# White Noise

A minimalist Android app that generates ambient sounds for relaxing, sleeping, or focusing.

## Sounds

| Type | Description |
|---|---|
| White | Uniform random noise |
| Pink | Frequency-filtered white noise |
| Brown | Deep Brownian motion |
| Ocean | Brown noise shaped with wave envelopes |
| Rain | High-pass filtered noise |
| Fan | Double low-pass filtered mechanical hum |
| Fire | Base noise with random crackling |

White, Pink, and Brown are mutually exclusive. Ocean, Rain, Fan, and Fire can be blended (up to two at once).

## Features

- Real-time audio synthesis at 44.1 kHz via `AudioTrack`
- Volume control with persistent preference
- Foreground service for uninterrupted background playback
- Persistent notification with a quick-stop button
- Saves selected sounds and volume across sessions

## Requirements

- Android 8.0 (API 26) or higher

## Building

```bash
./gradlew assembleDebug
```
