# Installing Hybox

## Installation

1. Download `Hybox-X.X.X.jar` from the [releases](https://github.com/Xytronix/hybox/releases).
2. Put it in the server's `mods` folder.
3. Start the server. Hybox logs `Hybox started.` and `Config: <path>`.

Hybox runs on Hytale 0.7.7 and later and on the 0.8 pre-releases. The Mixin details in reports need [Hyinit](https://github.com/IroriPowered/hyinit/releases) 0.5.0 or newer.

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
