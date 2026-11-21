# Installing Hybox

## Requirements

| Requirement | Detail |
| --- | --- |
| Hytale server | 0.7.7 or later, and 0.8.0 pre-releases from pre-release 5. One jar covers all of them. On a later pre-release line the server still loads Hybox but warns that it is out of date. |
| Java 25 | Needed to build. Gradle downloads a Java 25 toolchain if none is installed. |

## Build and install

1. Run `./gradlew clean build`.
2. Stop the server.
3. Copy `hybox-hytale/build/libs/Hybox-<version>.jar` into the server's `mods/` folder.
4. Start the server. Hybox logs `Hybox started.` and `Config: <path>`.

To remove Hybox, stop the server and delete the JAR. The data folder stays until you delete it.

## Data folder

Hybox keeps everything in `mods/Xytronix_Hybox/`.

| Path | Contents |
| --- | --- |
| `hybox.json` | Settings, written with defaults on first start. See [Configuration](/mod/hybox/configuration). |
| `incidents/incident-<id>.zip` | Incident bundles. |
| `temp/` | Recordings of captures in progress. Recordings that could not be bundled move to `temp/failed/` or `temp/pending/`. |
| `live/rolling.jfr` | Rolling snapshot of the recording, used after a crash. |
| `live/recover/`, `live/jfr-session` | Crash recovery staging, and the location of the previous run's JFR repository. |
| `live/hyinit.json` | Hyinit's Mixin details from the last start, so a bundle rebuilt after a crash still has its Mixins section. |
| `metrics/health-<yyyyMMdd>.csv` | Health metrics, one file per UTC day. |
| `trend-report.html` | Written by `/hybox trend` from the console. |

## Permissions

Hybox's root node is `xytronix.hybox.command.hybox`. Each subcommand adds its name, for example `xytronix.hybox.command.hybox.dump` and `xytronix.hybox.command.hybox.profile.stop`. A player needs the root node and every node on the path to the subcommand. [Commands](/mod/hybox/using-hybox#commands) lists each node.

The dashboard buttons check the node of the matching command.

With [LuckPerms](https://luckperms.net), give a staff group every Hybox command. The `.incident` node also gives the staff alerts.

```
/lp group admin permission set xytronix.hybox.command.hybox true
/lp group admin permission set xytronix.hybox.command.hybox.* true
```

## Upgrading

A `hybox.json` from 1.1.0 works as it is. Hybox never rewrites the file, so new defaults only reach new installs. The one new key is `Discord.ServerName`.

| Change | Notes |
| --- | --- |
| `/hybox profile` gained the modes `lag` and `memory`. | Without a mode it records `lag`, which has everything the old profile recorded. See [Profiling](/mod/hybox/using-hybox#profiling). |
| `Capture.IncludeServerLog` is `true` on new installs, so bundles include the server log tail. | Set it to `true` to add the masked log tail to new bundles. Chat and command arguments are hidden. See [Privacy](/mod/hybox/privacy#chat-and-commands). |
| `Metrics.Enabled` is `true` on new installs, so Hybox writes the metrics history. | Set it to `true` for `/hybox trend` and the usual values in incident reports. It writes about 0.7 MiB per day. |
| `Jfr.OldObjectSampling` is `true` on new installs, so Hybox records old-object samples. | Set it to `true` and restart to see where long-lived objects were allocated. |
| The `default` preset of `Jfr.Configuration` records fewer events. | Thread sleep, monitor wait and native method samples are off, and thread parks and monitor enters are kept only from 500 ms. Set `Jfr.Configuration` to `"profile"` to record them all. |
| The built-in `Capture.RedactPatterns` grew from four to seven patterns. See [Redaction](/mod/hybox/privacy#redaction). | A `hybox.json` that still lists the four earlier built-in patterns gets the new list. A list you changed yourself is kept, so add the new patterns to it if you want them. |
| Slow-tick limits now follow the [tick budget](/mod/hybox/reading-a-report#tick-budget) of each world. `Trigger.TickAvgDegradedMs` and `Trigger.TickAvgCriticalMs` default to `0`, which means Auto: degraded at 3 times the budget and critical at 7.5 times. At 30 TPS that is the old 100 ms and 250 ms. | The old defaults `100` and `250` in an existing `hybox.json` count as Auto, so a world at 60 TPS now fires at 50 ms. Each key is checked on its own. To keep a fixed limit, set any other value, such as `110` or `260`. See [Trigger](/mod/hybox/configuration#trigger). |
| Bundles saved by an older Hybox are cleaned once at startup. | See [Bundles from older versions](/mod/hybox/privacy#bundles-from-older-versions). |
| Alerts go out when a trigger is accepted, and again when the bundle is saved. | Staff in game and Discord hear about an incident before the post-incident wait. Set `Discord.ServerName` if the server name from Hytale's `config.json` is not the name you want in Discord. |
