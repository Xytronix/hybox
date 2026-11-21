# Privacy

A bundle describes your server in detail. Review it before you share it. See [Sharing a bundle](/mod/hybox/using-hybox#sharing-a-bundle).

## What a bundle contains

"Controlled by" names an entry of `Capture.Artifacts` or another [Capture](/mod/hybox/configuration#capture) key. `Capture.Enabled` set to `false` stops bundles entirely.

| File | Contents | Controlled by |
| --- | --- | --- |
| `incident.json` | Id, time, severity, trigger, scope, headline and trigger details. | Always written |
| `report.html` | Charts from the recording, health at capture time, hot threads and the [report sections](#report-sections). With metrics history, usual values for this time of day. For earlier bundles still on disk, their time, trigger, world, top mod and top method, never their player names. | `report` |
| `recording.jfr` | The JFR recording, up to `Jfr.MaxAge`, cleaned for sharing. See [The recording](#the-recording). | `jfr` |
| `env/jvm.txt`, `env/os.txt` | Java version, VM name and vendor, and `java.home` with paths shortened. OS name, version and architecture. | `env` |
| `extras/threads.txt`, `extras/threads-<n>.txt` | Thread dumps. A frame that a Mixin added or changed ends with the mod and Mixin, such as `[changed by Mixin from Refixes (MixinWorld)]`. | `threads` |
| `extras/server.txt` | Server name, Hytale version and a hosting-plugin presence flag. | `server` |
| `extras/plugins.txt` | Identifier, version and name of every plugin. | `plugins` |
| `extras/mixins.txt` | With a Hyinit that has the diagnostics API: Hyinit and Mixin versions, every early plugin JAR with its full SHA-256, Mixin configs, Mixin problems with their stack traces when Hyinit recorded them, and every method a Mixin added or changed, with the Mixin, config and JAR. File names only, no paths. | `plugins` |
| `extras/worlds.txt` | Default world name and its player count. | `worlds` |
| `extras/heartbeats.txt` | Last heartbeat time of each world. | `heartbeats` |
| `extras/server-log.txt` | Tail of the newest file in `logs/`, up to `Capture.LogTailLines` lines and 256 KiB, with [chat and command arguments hidden](#chat-and-commands). | `serverLog` and `Capture.IncludeServerLog` |
| `extras/metrics/health-<date>.csv` | The two newest metrics files, up to 1 MiB each. Numbers only. | `metrics` |
| Files from other plugins | Whatever those plugins attach. If two attachments share a path, the first is kept. | `Capture.AllowPluginExtras` |

### Report sections

Hybox writes these only when `report` is in `Capture.Artifacts`.

| Section | Contents and condition |
| --- | --- |
| Environment, Plugins, Asset packs | Server name, Hytale version, container runtime, Mixin loaders such as Hyxin or an older Hyinit, loaded plugins and asset packs. |
| Mixins | The same details as `extras/mixins.txt`, plus methods changed by more than one mod. With an older Hyinit: its version and the line "Update Hyinit for Mixin details". With another Mixin loader: its version, each registered Mixin config with its Mixin classes, and classes changed by more than one config. |
| Tick systems, Entities, Heartbeats, Thread progress, Memory pools, Stalled thread, Deadlock cycle | Engine, JVM and thread state. |
| Start command | JVM and program arguments with paths shortened and [private arguments](#paths-and-start-arguments) hidden. It comes from the recording. |
| Heap histogram | Class names, instance counts and sizes. With `Capture.HeapHistogram`. |
| Server log | The same tail as `extras/server-log.txt`. |
| `config.json` | With `Capture.IncludeServerConfig`: the server's `config.json`, up to 96 KiB, with each `"Password"` value and the `AuthCredentialStore` object replaced by `<redacted>`. |
| Mod config files | With `Capture.IncludeModConfigs`: `config.json` and `<name>.json` from each folder directly under `mods/`, where `<name>` is the folder name after its last underscore, in lower case. Files registered by plugins come first. At most 12 files, 96 KiB each. Only the [redact patterns](#redaction) remove secrets from them. |
| Plugin sections | Key and value pairs from other plugins. |
| Mod settings | The last value of each setting a mod writes as a JFR event. See [Plugin API](/mod/hybox/plugin-api#jfr-events). |

## Masking and redaction

Both apply to `incident.json`, `report.html`, `env/` files, report sections, trigger details, headlines, and attachments ending in `.txt`, `.json`, `.html`, `.log`, `.properties`, `.xml`, `.yaml`, `.yml`, `.cfg` or `.csv`. [The recording](#the-recording) is cleaned in a different way. Other binary files are not changed.

In `incident.json` and the report data, Hybox masks text values only. Keys, numbers and `true`, `false` or `null` stay as they are. In report markup, player names are masked in text and in titles and tooltips, never in tags.

The headline of an incident, for example the exception message of a world failure, is masked and redacted once before anything uses it, so the bundle, the in-game alert, the server log line and Discord all get the same text. Headlines and values that are shown or sent also always lose IP addresses, host names with a port and `.local` names, even when `Capture.RedactPatterns` has no address patterns.

### Player names

Hybox replaces player names and their UUIDs with tokens such as `player-1`. A name is found in any case, and also next to `_` or another character that is not a letter or digit, so `Steve_home` and `island_Steve` are masked, while `Steve2` and `Steven` stay. When names overlap, the longest is masked first, so `Bob_Smith` gets its own token even when `Bob` is known. A name that is also a common word, such as `Default`, `Info` or `Spawn`, is masked only in the same case, so the world `default` and the level `INFO` stay. A UUID is found in any case, with or without dashes. Hybox learns a name and UUID when the player connects, and from server log lines of these forms:

- `Disconnecting <name> at `
- `Player '<name>' (<uuid>)`
- `, <name>, <uuid>}` and `, <uuid>, <name>}`

It also learns the names a bundle holds when it cleans an older bundle or opens one in game: the player names of each world, `--owner-name` and `--owner-uuid` in the start command, and the log lines above. Tokens such as `player-3`, numbers and the words `true`, `false`, `null`, `unknown` and `player` are never learned as names.

Hybox also masks names in its own JFR connection and plugin events as they are recorded. Action messages and setting values from mod JFR events get the same masking and redaction in `report.html`, and `recording.jfr` cleans them like any other event. Tokens restart at `player-1` each run. Hybox tracks at most 4096 names and UUIDs per run.

Not masked: world names, the server name, and names not learned in the current run. A bundle rebuilt after a crash only masks names seen since the restart.

### Chat and commands

The server log tail keeps no chat and no command arguments. This applies to `extras/server-log.txt`, the Server log section of `report.html`, the Server log card in game, and every copy made from them.

A chat line keeps its time, level and logger. The rest becomes `[chat line hidden]`, with the player token when Hybox knows the name in the line:

```
[2026/11/19 10:00:05   INFO]                 [Hytale] Steve: the vault code is 4417
[2026/11/19 10:00:05   INFO]                 [Hytale] [chat line hidden, player-1]
```

Hybox finds chat lines in three ways. It keeps the last 256 chat messages in memory, never on disk, and hides lines of the `Hytale` logger that contain one of them, so custom chat formats are covered. The messages are gone after a restart. For lines from before Hybox started, it also hides lines of the `Hytale` logger in the default `<name>: <message>` form, and lines where a [known player name](#player-names) is followed by a colon and a space. Other lines with a colon stay.

A command line keeps the sender and the command name. The arguments become `[arguments hidden]`, so private messages, passwords and permission changes do not appear. The same applies to the `Command not found!` reply for an unknown command:

```
[2026/11/19 10:00:09   INFO]         [CommandManager] Steve executed command: lp user Alex permission set admin.*
[2026/11/19 10:00:09   INFO]         [CommandManager] player-1 executed command: lp [arguments hidden]
```

`/hybox` commands keep their arguments. Hybox also removes color codes from the log tail.

Not hidden: replies that a command prints, for example `Could not look up "Alex"` after `/whitelist add Alex`, and chat lines from other plugins that log chat with their own logger.

### Paths and start arguments

Hybox shortens file paths in the same files. The server folder becomes `.`. Home folders become `~`, yours and any other, in any case and also when the user name has spaces. Home folders are paths such as `/home/<name>`, `/Users/<name>`, `/root` or `C:\Users\<name>`. Other absolute paths keep only their last part, for example `/opt/java/jdk-25` becomes `.../jdk-25`. This includes network shares such as `\\nas\share\x.jar`, and paths with spaces that start with a drive letter or a mount folder such as `/Volumes/` or `/media/`. A path is found after a space, a quote or a separator such as `=`, `:` or `|`, and after `-I` or `-L`.

The Start command also hides the values of `--owner-name`, `--owner-uuid`, `--session-token`, `--identity-token`, `--bind`, `-b`, `--boot-command`, and every argument whose name contains `token`, `secret`, `password`, `passwd`, `credential` or `apikey`, also when the argument is in quotes.

In game, `/hybox status --verbose` shows paths the same way. The console shows full paths.

### Redaction

Text that matches a pattern in `Capture.RedactPatterns` becomes `[REDACTED]`. A port right after a redacted address is removed too. Setting the list replaces these built-in patterns, so copy them in if you want to keep them:

| Pattern | Matches |
| --- | --- |
| IPv4 address | `203.0.113.7`. Four-part version numbers stay when `v`, `version`, `build` or `release` comes before them, or when `+`, `_` or `-` and a letter follow them, as in `v 1.2.3.4` or `25.0.4.1+1-LTS`. |
| IPv6 address, with or without brackets | `2001:db8::1`, `fe80::1%en0`, `[::1]:5520`. Clock times such as `[12:34:56]` stay. |
| Host name with a port | `play.example.net:5520`, `mc.server.pl:5520`, `localhost:61857`, in any case, and any host with a port right after `://`. The name has to be `localhost` or end in a common top-level domain such as `.com`, `.net`, `.gg`, `.ovh` or a two-letter country code. A single name that starts with a capital letter or ends like a source file, such as `Foo.java:123`, `script.pl:12` or `timeout.ms:5000`, stays, as do code locations such as `Thread.run:1583`. |
| `.local` host name | `Alexs-MacBook-Pro.local` |
| Discord webhook URL | `https://discord.com/api/webhooks/<id>/<token>`, also with `/api/v10/` |
| Value of a JSON key that contains `password`, `passwd`, `secret`, `token`, `apikey` or `credential`, any case | `"rconPassword": "secret"`, `"token": "abc"` |
| Value after `=` or `:` following one of those words | `password=hunter2`, `password=123456`, `token: abc`. A count of 1 to 3 digits stays, such as `token: 5`. Closing brackets after the value stay. |

## Bundles from older versions

A bundle saved by an older Hybox may hold player names, paths and addresses that the current rules hide. Every bundle Hybox writes now carries `"cleaned": 2` in the `meta` of `incident.json`, the version of the masking rules.

- In game, the incident viewer, the Incidents list, `/hybox list`, `/hybox incident`, the trend report and error messages mask what they show with the current rules, whatever the bundle holds.
- At startup, Hybox cleans each bundle without that mark once, in the background, with the current masking and redaction, and logs how many it cleaned.
- If a bundle cannot be cleaned, for example because its `incident.json` is damaged, Hybox keeps it as it is and logs one line with the reason. The Incidents list then shows it as "Not cleaned", and the viewer says "Saved by an older Hybox. It can hold names and paths. Do not share the zip."

## The recording

Hybox writes a cleaned copy of the recording into the bundle. Private text is masked in place: each character of a private value becomes `*` and the event stays. Hybox masks the same values as in text files: paths, player names and UUIDs it knows, IP addresses with their port, matches of `Capture.RedactPatterns`, and the values of [private start arguments](#paths-and-start-arguments). In a path, the parts that text files shorten are masked: the server folder, home folders and the folders of other absolute paths. The last part of the path stays. Hybox also masks your OS user name wherever it appears as a whole word. The version of the running Java, such as `25.0.4.1`, stays even though it looks like an IP address.

| Before | After |
| --- | --- |
| `quiche-listener-203.0.113.7/203.0.113.7:5520` | `quiche-listener-****************************` |
| `/Users/alex/Library/Hytale/Server/HytaleServer.jar` | `***********/Library/Hytale/Server/HytaleServer.jar` |
| `/usr/lib/libz.1.dylib` | `********/libz.1.dylib` |
| `--owner-name=Alex` | `--owner-name=****` |

A mask has the same length as the value it hides, so `****` shows that a name had four letters. If your OS user name is a common word such as `hytale`, that word is masked everywhere in the recording's text, thread dumps included.

Hybox leaves out these event types entirely, since they only describe the machine: environment variables, system and security properties, Java and native agents with their options, the list of running processes, started processes, socket reads and writes, and TLS handshakes.

Hybox checks the cleaned copy and leaves out any event that still holds private text.

`report.html` is built from the full recording before cleaning, so its charts, Start command and system facts are complete.

Still in the cleaned recording: world names, the server name, plugin, class and method names, the parts of thread names and paths that are not private, and the length of each masked value. If Hybox cannot clean the recording, the bundle has no `recording.jfr` and the server log says why. Remove `jfr` from `Capture.Artifacts` to always leave it out.

## Prometheus

The endpoint has no authentication. Anyone who can reach `Metrics.Prometheus.Bind` and `Port` can read it. It binds to `127.0.0.1` by default. It exposes server health, world names, disconnect reasons, incident counts by trigger, plugin metric names and values, and the names and values of metrics that mods write as JFR events.

## Discord

When `Discord.WebhookUrl` is set, Hybox posts two short messages for an automatic incident. The first goes out when the trigger is accepted, before the post-incident wait:

```text
**<server name>** Critical incident: World default stopped responding for 2.1 s
World default, incident <id>. Hybox is saving the bundle now.
```

The second goes out when the bundle is saved. It has the final headline, a `Cause:` line from Diagnosis, or a `Notable:` line when there is no cause, and the first next step:

```text
**<server name>** Critical incident saved: World default stopped responding for 4 min and was still frozen when the bundle was saved
World default, incident <id>.
Cause: <cause line>
Next step: <first next step>
```

The server name is `Discord.ServerName`, or the server name from Hytale's `config.json` when that key is empty. Manual captures and profiles get only the second message. If a capture fails after the first message, the second message reads "<Severity> incident not saved" and says that Hybox could not save the bundle and to check the server log. [`Discord.Cooldown`](/mod/hybox/configuration#discord) limits how many messages go out. Messages mention nobody, even when a headline holds `@everyone`. Hybox uploads no files.

The headline, cause and next step are masked and redacted.

[Copy summary](/mod/hybox/reading-a-report#sharing-a-report) in `report.html` copies a short text for you to paste into Discord yourself. It comes from the masked report, so it shows nothing the report does not.
