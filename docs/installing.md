# Installing Hybox

## Requirements

- A Hytale dedicated server of the version Hybox was built for. The build reads it from `hytaleServerVersion` in `gradle.properties` (currently `0.7.7`), and the plugin manifest requires exactly that server version.
- Java 25 to build. Gradle uses a Java 25 toolchain and can download one if none is installed.

## Build

```sh
./gradlew clean build
```

The plugin JAR is `hybox-hytale/build/libs/Hybox-<version>.jar`, for example `Hybox-1.0.0.jar`. It already contains everything Hybox needs.

## Install

1. Stop the server.
2. Copy the JAR into the server's `mods/` folder.
3. Start the server.

On first start Hybox writes a default `hybox.json` into its plugin data folder and logs `Hybox started.` followed by `Config: <path>`. Run `/hybox status --verbose` at any time to see the config path and the incidents folder.

## Files Hybox writes

All paths are inside Hybox's plugin data folder.

| Path | Contents |
| --- | --- |
| `hybox.json` | Settings. See [Configuration](configuration.md). |
| `incidents/incident-<id>.zip` | Incident bundles. |
| `temp/` | Recordings of captures in progress. Recordings that could not be bundled move to `temp/failed/` or `temp/pending/`. |
| `live/rolling.jfr` | Rolling snapshot of the recording, used to rebuild a bundle after a crash. |
| `live/recover/`, `live/jfr-session` | Recovery staging and the location of the previous run's JFR repository. |
| `metrics/health-<yyyyMMdd>.csv` | Health metrics, one file per UTC day, when `Metrics.Enabled` is on. |
| `trend-report.html` | Written by `/hybox trend` from the console. |

## Permissions

Hytale generates a permission node for each command from the plugin's group and name. Hybox's root node is `xytronix.hybox.command.hybox`. Every subcommand needs the root node and its own node. Grant players only what they need.

| Command | Permission nodes | What it does |
| --- | --- | --- |
| `/hybox` | root, `.ui` | Opens the dashboard for a player. The console gets a usage line. |
| `/hybox ui` | root, `.ui` | Opens the dashboard. |
| `/hybox dump` | root, `.dump` | Saves an incident bundle now. |
| `/hybox incident [id]` | root, `.incident` | Opens the latest incident, or the one with that id. The id can be the full id or its numeric suffix. |
| `/hybox profile [--minutes=5] [--keep-buffer=true]` | root, `.profile` | Records extra detail for 1 to 30 minutes, then saves a bundle. See [Profiling](using.md#profiling). |
| `/hybox profile stop` | root, `.profile`, `.profile.stop` | Ends the profile now and saves the bundle. |
| `/hybox profile cancel` | root, `.profile`, `.profile.cancel` | Ends the profile without saving. |
| `/hybox status [--verbose]` | root, `.status` | Shows recorder health. `--verbose` adds paths, retention and trigger timing. |
| `/hybox list [--count=5] [--details]` | root, `.list` | Players: opens the Incidents tab, which also needs `.ui`. Console: lists 1 to 50 recent incidents; `--details` adds bundle paths. |
| `/hybox triggers [--details]` | root, `.triggers` | Players: opens the Triggers tab, which also needs `.ui`. Console: lists armed and disabled detectors; `--details` adds thresholds. |
| `/hybox histogram` | root, `.histogram` | Players: opens the Heap tab, which also needs `.ui`. Console: prints the 10 largest heap classes. Walking the heap can pause the server for seconds. |
| `/hybox trend [--days=7]` | root, `.trend` | Players: opens the trend page. Console: writes `trend-report.html`. 1 to 30 days. Needs `Metrics.Enabled`. |
| `/hybox settings` | root, `.settings` | Opens the settings page. Players only. |
| `/hybox reload` | root, `.reload` | Reloads `hybox.json`. |

"root" is `xytronix.hybox.command.hybox`, and `.dump` means `xytronix.hybox.command.hybox.dump`.

Dashboard buttons check the node of the matching command:

| Dashboard action | Node |
| --- | --- |
| Capture, skip the post-incident wait | `.dump` |
| Start a profile | `.profile` |
| Stop or cancel a profile | `.profile.stop`, `.profile.cancel` |
| Open an incident | `.incident` |
| Walk the heap | `.histogram` |
| Trends | `.trend` |
| Settings | `.settings` |

## Remove Hybox

Stop the server and delete the JAR from `mods/`. The plugin data folder stays until you delete it.
