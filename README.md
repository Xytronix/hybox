# Hybox

Investigate lag, freezes and crashes on Hytale servers. Hybox keeps a rolling Java Flight Recorder (JFR) recording and saves incident bundles with an HTML report, JFR data and thread dumps.

## Installation

Build with Java 25 (`./gradlew clean build`), then copy `hybox-hytale/build/libs/Hybox-1.0.0.jar` into the server's `mods/` folder and restart.

## Commands

| Command | What it does |
| --- | --- |
| `/hybox` | Open the in-game dashboard: health, incidents, triggers, profiling and heap. |
| `/hybox dump` | Save an incident bundle now. |
| `/hybox incident [id]` | View the latest or given incident in-game. |
| `/hybox profile [minutes] [keep-buffer] [stop\|cancel]` | Record extra detail for 1 to 30 minutes (default 5), then save a bundle. |
| `/hybox status [--verbose]` | Show recorder health and file locations. |
| `/hybox list`, `/hybox triggers` | List recent bundles or enabled detectors. |
| `/hybox trend` | Show metrics history. Requires `Metrics.Enabled`. |
| `/hybox histogram` | List the largest heap classes. Pauses the server. |
| `/hybox settings`, `/hybox reload` | Edit or reload `hybox.json`. |

Dashboard buttons require the permission of the matching subcommand.

## Configuration

Edit `hybox.json` in the plugin data folder, or use `/hybox settings`. Recording changes need a restart; everything else applies on `/hybox reload`.

| Setting | Default |
| --- | --- |
| `Jfr` | 15 minutes, up to 256 MiB |
| `Retention` | 25 bundles, 1 GiB, 7 days |
| `Capture` | Log and config collection off |
| `Metrics.Enabled` | Off |
| `Metrics.Prometheus` | Off, `127.0.0.1:9099/metrics` when on |
| `Discord.WebhookUrl` | Unset |

Recordings can contain sensitive data, so review bundles before sharing. The Prometheus endpoint has no authentication.

## Plugin API

Add Hybox as a compile-only dependency and declare `Xytronix:Hybox` as a plugin dependency.

```java
HyboxApi.recordEvent("my-plugin", "cache", "Cache rebuilt");
```

Keep callbacks fast and thread-safe, and close registration handles when your plugin stops.

[MIT license](LICENSE). Fork of [Blackbox](https://github.com/ZECHEESELORD/blackbox).
