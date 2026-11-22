# Privacy

A bundle describes your server in detail. This page lists what it contains, what Hybox hides, and what it does not hide. Review a bundle before you share it. See [Sharing a bundle](using.md#sharing-a-bundle).

## What a bundle contains

| File | Contents | Controlled by |
| --- | --- | --- |
| `incident.json` | Incident id, time, severity, trigger, scope, headline and trigger details. | Always written |
| `report.html` | The readable report: charts from the recording, health at capture time, hot threads and the report sections below. | `report` |
| `recording.jfr` | The JFR recording, covering up to `Jfr.MaxAge`. | `jfr` |
| `env/jvm.txt` | Java version, VM name and vendor, `java.home`. | `env` |
| `env/os.txt` | OS name, version and architecture. | `env` |
| `extras/threads.txt` | One thread dump. | `threads` |
| `extras/threads-<n>.txt` | Spaced thread dumps from the post-incident wait. | Always written when taken |
| `extras/server.txt` | Server name, Hytale version, `nitrado.present`. | `server` |
| `extras/plugins.txt` | Identifier, version and name of every plugin. | `plugins` |
| `extras/worlds.txt` | Default world name and its player count. | `worlds` |
| `extras/heartbeats.txt` | Last heartbeat time of each world. | `heartbeats` |
| `extras/server-log.txt` | Tail of the newest file in `logs/`, up to `Capture.LogTailLines` lines and 256 KiB. | `serverLog` and `Capture.IncludeServerLog` |
| `extras/metrics/health-<date>.csv` | The two newest metrics files, up to 1 MiB each. They hold numbers only. | Present when the `metrics/` folder has files |
| Files from other plugins | Whatever those plugins attach. | `Capture.AllowPluginExtras` |

"Controlled by" names an entry of `Capture.Artifacts` or another key in [Capture](configuration.md#capture). `Capture.Enabled` set to `false` stops bundles entirely.

### Report sections

`report.html` can contain these sections. Hybox collects them only when `report` is in `Capture.Artifacts`.

| Section | Contents | Condition |
| --- | --- | --- |
| Environment | Server name, Hytale version, container runtime, Mixin loaders such as Hyinit, and the plugin list. | Always |
| Plugins, Asset packs | Loaded plugins and asset packs. | Always |
| Mixins | The Mixin loader and its version, every registered Mixin config with its Mixin classes, and classes changed by more than one config. | When a Mixin loader is present |
| Tick systems, Entities, Heartbeats, Thread progress, Memory pools | Engine and JVM state. | When available |
| Stalled thread, Deadlock cycle | The thread that stalled, or the deadlocked threads. | For those triggers |
| Mod hot-path contribution (JFR) | CPU samples grouped by mod. | When the recording has samples |
| Start command | JVM and program arguments of the server, read from the recording. | When the recording has them |
| Heap histogram | Class names, instance counts and sizes. | `Capture.HeapHistogram` |
| Server log | The same log tail as `extras/server-log.txt`. | `Capture.IncludeServerLog` |
| `config.json` | The server's `config.json`, up to 96 KiB, with every `"Password"` value and the `AuthCredentialStore` object replaced by `<redacted>`. | `Capture.IncludeServerConfig` |
| Mod config files | See [Mod config files](#mod-config-files). | `Capture.IncludeModConfigs` or plugin opt-in |
| Plugin sections | Key and value pairs from other plugins. | When plugins register them |

### Mod config files

With `Capture.IncludeModConfigs`, Hybox looks in each folder directly under `mods/`. It takes `config.json`, and `<name>.json` where `<name>` is the folder name after its last underscore, in lower case. Plugins can also add their own files with `HyboxApi.registerConfig` while `Capture.AllowModConfigOptIn` is on. A bundle holds at most 12 such files, up to 96 KiB each. Plugin files come first.

These files can hold secrets such as tokens or passwords. Only the [redact patterns](#redaction) remove anything from them.

## Player names

Hybox replaces player names with tokens such as `player-1`. It learns a name, and the player's UUID, when the player connects. It also learns names from server log lines in these forms:

- `Disconnecting <name> at `
- `Player '<name>' (<uuid>)`
- `, <name>, <uuid>}` and `, <uuid>, <name>}`

A learned name and its UUID map to the same token. Hybox masks:

- every text file it writes into the bundle, including `incident.json`, `report.html` and text files from other plugins
- trigger details and headlines
- player names in Hybox's own JFR connection events and plugin events, when they are recorded

Tokens start again at `player-1` after each restart. Hybox tracks at most 4096 names per run.

`Capture.SanitizeLog` masks the server log section when it is collected. The bundle writer masks every text file again, so turning `SanitizeLog` off does not unmask the bundle.

### What is not masked

- Names Hybox has not learned in the current run. A bundle rebuilt after a crash only masks names learned since the restart.
- Names inside `recording.jfr`, except in Hybox's own connection and plugin events.
- Files from other plugins whose names do not end in `.txt`, `.json`, `.html`, `.log`, `.properties`, `.xml`, `.yaml`, `.yml`, `.cfg` or `.csv`.
- World names and the server name.

## Redaction

Text that matches a pattern in `Capture.RedactPatterns` becomes `[REDACTED]`. The built-in patterns match:

| Pattern | Matches |
| --- | --- |
| IPv4 address | `203.0.113.7` |
| IPv6 address in brackets, with optional port | `[2001:db8::1]:5520` |
| Discord webhook URL | `https://discord.com/api/webhooks/<id>/<token>` |
| Value of a JSON `"password"` key, any case | `"password": "secret"` |

Redaction applies to `incident.json`, `report.html`, `env/` files, report sections, trigger details and headlines, and attachments ending in `.txt`, `.json`, `.html`, `.log`, `.properties`, `.xml`, `.yaml`, `.yml` or `.cfg`. It does not apply to `recording.jfr`, to metrics `.csv` files or to other binary files.

Setting `Capture.RedactPatterns` replaces the built-in list. Copy the built-in patterns into your list if you want to keep them.

## The recording

Hybox does not redact or mask `recording.jfr`. It holds whatever the JFR preset in `Jfr.Configuration` records, plus Hybox's own events. The report shows the server's start command from the recording's `jdk.JVMInformation` event. If your start command holds secrets:

- add `jdk.JVMInformation` to `Jfr.DisabledEvents`, or
- remove `jfr` from `Capture.Artifacts`, or
- delete `recording.jfr` from the zip before sharing.

## Prometheus

The Prometheus endpoint has no authentication. Anyone who can reach `Metrics.Prometheus.Bind` and `Metrics.Prometheus.Port` can read it. It binds to `127.0.0.1` by default. It exposes server health, world names, disconnect reasons, incident counts by trigger, and plugin metric names and values.

## Discord

When `Discord.WebhookUrl` is set, Hybox posts one message per new bundle, at most once per `Discord.Cooldown`. The message is JSON with two fields:

```json
{"content": "[<SEVERITY>] <headline> (scope: <scope>)", "username": "<Discord.Username>"}
```

The headline is redacted and masked like the bundle. Hybox does not upload the bundle or any file.
