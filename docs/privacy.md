# Privacy

A bundle describes your server in detail. Review it before you share it. See [Sharing a bundle](using.md#sharing-a-bundle).

## What a bundle contains

"Controlled by" names an entry of `Capture.Artifacts` or another [Capture](configuration.md#capture) key. `Capture.Enabled` set to `false` stops bundles entirely.

| File | Contents | Controlled by |
| --- | --- | --- |
| `incident.json` | Id, time, severity, trigger, scope, headline and trigger details. | Always written |
| `report.html` | Charts from the recording, health at capture time, hot threads and the [report sections](#report-sections). | `report` |
| `recording.jfr` | The JFR recording, up to `Jfr.MaxAge`, cleaned for sharing. See [The recording](#the-recording). | `jfr` |
| `env/jvm.txt`, `env/os.txt` | Java version, VM name and vendor, and `java.home` with paths shortened. OS name, version and architecture. | `env` |
| `extras/threads.txt`, `extras/threads-<n>.txt` | Thread dumps. A frame that a Mixin added or changed ends with the mod and Mixin, such as `[changed by Mixin from Refixes (MixinWorld)]`. | `threads` |
| `extras/server.txt` | Server name, Hytale version and a hosting-plugin presence flag. | `server` |
| `extras/plugins.txt` | Identifier, version and name of every plugin. | `plugins` |
| `extras/mixins.txt` | With a Hyinit that has the diagnostics API: Hyinit and Mixin versions, every early plugin JAR with its full SHA-256, Mixin configs, Mixin problems, and every method a Mixin added or changed, with the Mixin, config and JAR. File names only, no paths. | `plugins` |
| `extras/worlds.txt` | Default world name and its player count. | `worlds` |
| `extras/heartbeats.txt` | Last heartbeat time of each world. | `heartbeats` |
| `extras/server-log.txt` | Tail of the newest file in `logs/`, up to `Capture.LogTailLines` lines and 256 KiB. | `serverLog` and `Capture.IncludeServerLog` |
| `extras/metrics/health-<date>.csv` | The two newest metrics files, up to 1 MiB each. Numbers only. | `metrics` |
| Files from other plugins | Whatever those plugins attach. If two attachments share a path, the first is kept. | `Capture.AllowPluginExtras` |

### Report sections

Hybox collects these only when `report` is in `Capture.Artifacts`.

| Section | Contents and condition |
| --- | --- |
| Environment, Plugins, Asset packs | Server name, Hytale version, container runtime, Mixin loaders such as Hyxin or an older Hyinit, loaded plugins and asset packs. |
| Mixins | With a Hyinit that has the diagnostics API: Hyinit and Mixin versions, each early plugin JAR with its version and SHA-256, each Mixin config with its Mixin classes, Mixin problems since startup, and methods changed by more than one mod. A bundle rebuilt after a crash uses the copy that Hybox saved in `live/hyinit.json` when that run started. With an older Hyinit: its version and the line "Update Hyinit for Mixin details". With another Mixin loader: its version, each registered Mixin config with its Mixin classes, and classes changed by more than one config. |
| Tick systems, Entities, Heartbeats, Thread progress, Memory pools, Stalled thread, Deadlock cycle | Engine, JVM and thread state. |
| Start command | JVM and program arguments with paths shortened and [private arguments](#paths-and-start-arguments) hidden. It comes from the recording. |
| Heap histogram | Class names, instance counts and sizes. With `Capture.HeapHistogram`. |
| Server log | The same tail as `extras/server-log.txt`. |
| `config.json` | With `Capture.IncludeServerConfig`: the server's `config.json`, up to 96 KiB, with each `"Password"` value and the `AuthCredentialStore` object replaced by `<redacted>`. |
| Mod config files | With `Capture.IncludeModConfigs`: `config.json` and `<name>.json` from each folder directly under `mods/`, where `<name>` is the folder name after its last underscore, in lower case. Files registered by plugins come first. At most 12 files, 96 KiB each. Only the [redact patterns](#redaction) remove secrets from them. |
| Plugin sections | Key and value pairs from other plugins. |

## Masking and redaction

Both apply to `incident.json`, `report.html`, `env/` files, report sections, trigger details, headlines, and attachments ending in `.txt`, `.json`, `.html`, `.log`, `.properties`, `.xml`, `.yaml`, `.yml`, `.cfg` or `.csv`. [The recording](#the-recording) is cleaned in a different way. Other binary files are not changed.

### Player names

Hybox replaces player names and their UUIDs with tokens such as `player-1`. It learns a name and UUID when the player connects, and from server log lines of these forms:

- `Disconnecting <name> at `
- `Player '<name>' (<uuid>)`
- `, <name>, <uuid>}` and `, <uuid>, <name>}`

Hybox also masks names in its own JFR connection and plugin events when they are recorded. Tokens restart at `player-1` each run. Hybox tracks at most 4096 names and UUIDs per run.

Not masked: world names, the server name, and names not learned in the current run. A bundle rebuilt after a crash only masks names seen since the restart.

### Paths and start arguments

Hybox shortens file paths in the same files. The server folder becomes `.`. Your home folder and any other home folder (`/home/<name>`, `/Users/<name>`, `/root`, `C:\Users\<name>`) become `~`. Other absolute paths keep only their last part, for example `/opt/java/jdk-25` becomes `.../jdk-25`.

The Start command also hides the values of `--owner-name`, `--owner-uuid`, `--session-token`, `--identity-token`, `--bind`, `-b`, `--boot-command`, and every argument whose name contains `token`, `secret`, `password`, `passwd`, `credential` or `apikey`.

In game, `/hybox status --verbose` shows paths the same way. The console shows full paths.

### Redaction

Text that matches a pattern in `Capture.RedactPatterns` becomes `[REDACTED]`. A port right after a redacted address is removed too. Setting the list replaces these built-in patterns, so copy them in if you want to keep them:

| Pattern | Matches |
| --- | --- |
| IPv4 address | `203.0.113.7` |
| IPv6 address in brackets, with optional port | `[2001:db8::1]:5520` |
| Discord webhook URL | `https://discord.com/api/webhooks/<id>/<token>` |
| Value of a JSON `"password"` key, any case | `"password": "secret"` |

## The recording

Hybox does not copy the recording as it is. It writes a cleaned copy into the bundle.

Private text in the recording is masked in place. Each character of a private value becomes `*` and the event stays. Hybox masks the same values as in text files: paths, player names and UUIDs it knows, IP addresses with their port, matches of `Capture.RedactPatterns`, and the values of [private start arguments](#paths-and-start-arguments). In a path, the parts that text files shorten are masked: the server folder, home folders and the folders of other absolute paths. The last part of the path stays. Hybox also masks your OS user name wherever it appears as a whole word. The version of the running Java, such as `25.0.4.1`, stays even though it looks like an IP address.

| Before | After |
| --- | --- |
| `quiche-listener-203.0.113.7/203.0.113.7:5520` | `quiche-listener-****************************` |
| `/Users/alex/Library/Hytale/Server/HytaleServer.jar` | `***********/Library/Hytale/Server/HytaleServer.jar` |
| `/usr/lib/libz.1.dylib` | `********/libz.1.dylib` |
| `--owner-name=Alex` | `--owner-name=****` |

So the CPU and native samples of Hytale's network listener threads stay, thread dumps stay complete, and the JVM start command (`jdk.JVMInformation`) and native library events stay with their paths masked.

A mask has the same length as the value it hides, so `****` shows that a name had four letters. If your OS user name is a common word such as `hytale`, that word is masked everywhere in the recording's text, thread dumps included.

Hybox leaves out these event types entirely, because they only describe the machine and do not help with profiling: environment variables, system and security properties, Java and native agents with their options, the list of running processes, started processes, socket reads and writes, and TLS handshakes.

After masking, Hybox reads the cleaned copy back and checks every text again. An event that still holds private text is left out. If the masked copy cannot be read or has a different number of events, Hybox leaves out every event that holds private text instead and logs a warning with the reason.

`report.html` is built from the full recording before cleaning, so its charts, Start command and system facts are complete.

Still in the cleaned recording: world names, the server name, plugin, class and method names, the parts of thread names and paths that are not private, and the length of each masked value. If Hybox cannot clean the recording, the bundle has no `recording.jfr` and the server log says why. Remove `jfr` from `Capture.Artifacts` to always leave it out.

## Prometheus

The endpoint has no authentication. Anyone who can reach `Metrics.Prometheus.Bind` and `Port` can read it. It binds to `127.0.0.1` by default. It exposes server health, world names, disconnect reasons, incident counts by trigger, and plugin metric names and values.

## Discord

When `Discord.WebhookUrl` is set, Hybox posts one message per new bundle, at most once per `Discord.Cooldown`:

```json
{"content": "<SEVERITY>: <headline>", "username": "<Discord.Username>"}
```

The headline is masked and redacted. Hybox uploads no files.
