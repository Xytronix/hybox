# Installing Hybox

## Requirements

| Requirement | Detail |
| --- | --- |
| Hytale server | 0.7.7 or later, and 0.8.0 pre-releases from pre-release 5. One jar covers all of them. The range is `hytaleServerRange` in `gradle.properties`. Hytale only accepts a pre-release that the range names, so on a later pre-release such as 0.9.0-pre.1 the server still loads Hybox but warns that it is out of date. |
| Java 25 | Needed to build. Gradle downloads a Java 25 toolchain if none is installed. |

## Build and install

1. Run `./gradlew clean build`.
2. Stop the server.
3. Copy `hybox-hytale/build/libs/Hybox-<version>.jar`, for example `Hybox-1.1.0.jar`, into the server's `mods/` folder.
4. Start the server. Hybox logs `Hybox started.` and `Config: <path>`.

To remove Hybox, stop the server and delete the JAR. The data folder stays until you delete it.

## Data folder

Hybox keeps everything in `mods/Xytronix_Hybox/`.

| Path | Contents |
| --- | --- |
| `hybox.json` | Settings, written with defaults on first start. See [Configuration](configuration.md). |
| `incidents/incident-<id>.zip` | Incident bundles. |
| `temp/` | Recordings of captures in progress. Recordings that could not be bundled move to `temp/failed/` or `temp/pending/`. |
| `live/rolling.jfr` | Rolling snapshot of the recording, used after a crash. |
| `live/recover/`, `live/jfr-session` | Crash recovery staging, and the location of the previous run's JFR repository. |
| `metrics/health-<yyyyMMdd>.csv` | Health metrics, one file per UTC day. |
| `trend-report.html` | Written by `/hybox trend` from the console. |

## Permissions

Hytale builds command permission nodes from the plugin's group and name. Hybox's root node is `xytronix.hybox.command.hybox`. Each subcommand adds its name, for example `xytronix.hybox.command.hybox.dump` and `xytronix.hybox.command.hybox.profile.stop`. A player needs the root node and every node on the path to the subcommand. [Commands](using.md#commands) lists each node.

The dashboard needs `.ui`. Its buttons check the node of the matching command. Skipping the post-incident wait needs `.dump`.
