# Privacy

A bundle describes your server in detail. Review it before you share it. See [Sharing a bundle](using.md#sharing-a-bundle).

## What a bundle contains

"Controlled by" names an entry of `Capture.Artifacts` or another [Capture](configuration.md#capture) key. `Capture.Enabled` set to `false` stops bundles entirely.

| File | Contents | Controlled by |
| --- | --- | --- |
| `incident.json` | Id, time, severity, trigger, scope, headline and trigger details. | Always written |
| `report.html` | Charts from the recording, health at capture time, hot threads and the [report sections](#report-sections). | `report` |
| `recording.jfr` | The JFR recording, up to `Jfr.MaxAge`. | `jfr` |
| `env/jvm.txt`, `env/os.txt` | Java version, VM name and vendor, `java.home`; OS name, version and architecture. | `env` |
| `extras/threads.txt`, `extras/threads-<n>.txt` | Thread dumps. | `threads` |
| `extras/server.txt` | Server name, Hytale version and a hosting-plugin presence flag. | `server` |
| `extras/plugins.txt` | Identifier, version and name of every plugin. | `plugins` |
| `extras/worlds.txt` | Default world name and its player count. | `worlds` |
| `extras/heartbeats.txt` | Last heartbeat time of each world. | `heartbeats` |
| `extras/server-log.txt` | Tail of the newest file in `logs/`, up to `Capture.LogTailLines` lines and 256 KiB. | `serverLog` and `Capture.IncludeServerLog` |
| `extras/metrics/health-<date>.csv` | The two newest metrics files, up to 1 MiB each. Numbers only. | `metrics` |
| Files from other plugins | Whatever those plugins attach. If two attachments share a path, the first is kept. | `Capture.AllowPluginExtras` |

### Report sections

Hybox collects these only when `report` is in `Capture.Artifacts`.

| Section | Contents and condition |
| --- | --- |
| Environment, Plugins, Asset packs | Server name, Hytale version, container runtime, Mixin loaders such as Hyinit, loaded plugins and asset packs. |
| Mixins | With a Mixin loader: its version, each registered Mixin config with its Mixin classes, and classes changed by more than one config. |
| Tick systems, Entities, Heartbeats, Thread progress, Memory pools, Stalled thread, Deadlock cycle | Engine, JVM and thread state. |
| Mod hot-path contribution (JFR), Start command | CPU samples grouped by mod; JVM and program arguments. Both come from the recording. |
| Heap histogram | Class names, instance counts and sizes. With `Capture.HeapHistogram`. |
| Server log | The same tail as `extras/server-log.txt`. |
| `config.json` | With `Capture.IncludeServerConfig`: the server's `config.json`, up to 96 KiB, with each `"Password"` value and the `AuthCredentialStore` object replaced by `<redacted>`. |
| Mod config files | With `Capture.IncludeModConfigs`: `config.json` and `<name>.json` from each folder directly under `mods/`, where `<name>` is the folder name after its last underscore, in lower case. Files registered by plugins come first. At most 12 files, 96 KiB each. Only the [redact patterns](#redaction) remove secrets from them. |
| Plugin sections | Key and value pairs from other plugins. |

## Masking and redaction

Both apply to `incident.json`, `report.html`, `env/` files, report sections, trigger details, headlines, and attachments ending in `.txt`, `.json`, `.html`, `.log`, `.properties`, `.xml`, `.yaml`, `.yml`, `.cfg` or `.csv`. Neither applies to [the recording](#the-recording) or other binary files.

### Player names

Hybox replaces player names and their UUIDs with tokens such as `player-1`. It learns a name and UUID when the player connects, and from server log lines of these forms:

- `Disconnecting <name> at `
- `Player '<name>' (<uuid>)`
- `, <name>, <uuid>}` and `, <uuid>, <name>}`

Hybox also masks names in its own JFR connection and plugin events when they are recorded. Tokens restart at `player-1` each run. Hybox tracks at most 4096 names and UUIDs per run.

Not masked: names not learned in the current run, so a bundle rebuilt after a crash only masks names seen since the restart; world names; the server name.

### Redaction

Text that matches a pattern in `Capture.RedactPatterns` becomes `[REDACTED]`. Setting the list replaces these built-in patterns, so copy them in if you want to keep them:

| Pattern | Matches |
| --- | --- |
| IPv4 address | `203.0.113.7` |
| IPv6 address in brackets, with optional port | `[2001:db8::1]:5520` |
| Discord webhook URL | `https://discord.com/api/webhooks/<id>/<token>` |
| Value of a JSON `"password"` key, any case | `"password": "secret"` |

## The recording

Hybox does not redact or mask `recording.jfr`. It holds whatever the `Jfr.Configuration` preset records, plus Hybox's own events. Remove `jfr` from `Capture.Artifacts`, or delete the file from the zip, to keep it out of a shared bundle. `report.html` keeps its charts either way.

The report's Start command section is built from the recording's `jdk.JVMInformation` event even when `recording.jfr` is left out. If your start command holds secrets, add `jdk.JVMInformation` to `Jfr.DisabledEvents` and restart, or add a pattern to `Capture.RedactPatterns`. Bundles already written keep the old text.

## Prometheus

The endpoint has no authentication. Anyone who can reach `Metrics.Prometheus.Bind` and `Port` can read it. It binds to `127.0.0.1` by default. It exposes server health, world names, disconnect reasons, incident counts by trigger, and plugin metric names and values.

## Discord

When `Discord.WebhookUrl` is set, Hybox posts one message per new bundle, at most once per `Discord.Cooldown`:

```json
{"content": "[<SEVERITY>] <headline> (scope: <scope>)", "username": "<Discord.Username>"}
```

The headline is masked and redacted. Hybox uploads no files.
