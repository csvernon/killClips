# Kill Clips

A RuneLite plugin that automatically records your PvP kills and deaths as video clips.

## Features

- Automatically records kills and deaths during PvP combat
- Only captures fights you're involved in (combat tracking)
- Saves clips locally as H.264 MP4 (pure Java, no external dependencies)
- Optional auto-upload to Streamable.com
- Sidebar panel with clickable links to your recent Streamable uploads
- Kills shown in yellow, deaths in red
- Configurable resolution (480p / 720p / 1080p)
- Configurable frame rate (15 / 20 / 25 / 30 FPS)
- Configurable recording duration (10-60 seconds)
- Sensitive content protection (login screens, bank PIN automatically blurred)

## How It Works

1. The plugin continuously buffers video frames in memory while you play
2. When you kill another player or die in PvP, the buffer is saved as a clip
3. Clips include footage from before and after the event
4. If Streamable is enabled, clips are automatically uploaded and a link appears in the sidebar

## File Layout

Clips are saved under `.runelite/videos/<playername>/`:

```
kills/kills_20260414_013049_123.mp4
death/death_20260414_013112_456.mp4
```

All event metadata is stored in a single `clips.json` file under `.runelite/videos/`.

## Settings

### Video Settings
- **Resolution** - 480p, 720p (default), or 1080p
- **Frame Rate** - 15, 20, 25 (default), or 30 FPS
- **Recording Duration** - 10 to 60 seconds (default 10)

### Streamable
- **Enable Streamable Upload** - Toggle auto-upload on/off
- **Email / Username** - Your Streamable account email
- **Password** - Your Streamable account password (stored securely in RuneLite config)

## Requirements

- RuneLite GPU plugin must be enabled (required for video frame capture)

## Installation

Search for **Kill Clips** in the RuneLite Plugin Hub.

## License

BSD 2-Clause License - See [LICENSE](LICENSE).
