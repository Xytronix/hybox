# Hybox

Hybox helps you investigate lag, freezes and crashes on Hytale servers. It keeps a rolling recording and saves incident bundles containing an HTML report, Java Flight Recorder (JFR) data and thread dumps.

## Installation

Build with Java 25:

```sh
./gradlew clean build --no-daemon
```

Stop your server, copy `hybox-hytale/build/libs/Hybox-1.0.0.jar` into its `mods/` folder, then start it again.

Run `/hybox dump` to save your first bundle. `/hybox status` shows the recorder's health and where its files are stored.

## Commands

| Command | What it does |
| --- | --- |
| `/hybox dump` | Save an incident bundle. |
| `/hybox status` | Show recorder health and file locations. |
| `/hybox list` | List recent bundles. |
| `/hybox triggers` | Show detector settings and thresholds. |
| `/hybox profile` | Record more detail for five minutes by default. |
| `/hybox trend` | Show performance history. Requires metrics to be enabled. |
| `/hybox histogram` | Inspect heap memory. Can pause the server. |
| `/hybox reload` | Reload settings and report whether a restart is needed. |

## Configuration

Edit the generated `hybox.json` in the plugin data folder, then run `/hybox reload`.

| Section or setting | What you can change |
| --- | --- |
| `Jfr` | Recording history. Defaults to **15 minutes**, up to **256 MiB**. |
| `Trigger` | When Hybox should capture incidents automatically. |
| `Retention` | Bundle storage. Defaults to **25 bundles**, **1 GiB** total and **7 days**. |
| `Capture` | What goes into each bundle. Automatic log and configuration collection is **off by default**. |
| `Metrics.Enabled` | Turn on history for `/hybox trend`. **Off by default**. |
| `Metrics.Prometheus` | Export metrics at `127.0.0.1:9099/metrics`. **Off by default**. |
| `Discord.WebhookUrl` | Add a webhook to receive incident notifications. **Disabled until set**. |

In the config file, `PT15M` means 15 minutes and size limits are written in bytes. Recordings needed for recovery may be kept beyond the retention limits.

Review bundles before sharing: recordings can contain sensitive data. Prometheus has no authentication, so keep its endpoint private.

## Plugin API

Use Hybox as a compile-only dependency and declare `Xytronix:Hybox` as a plugin dependency.

```java
import io.github.xytronix.hybox.hytale.HyboxApi;

HyboxApi.recordEvent("my-plugin", "cache", "Cache rebuilt");
```

Keep callbacks quick and thread-safe. Close registration handles when your plugin stops.

[MIT license](LICENSE). Continued fork of [Blackbox](https://github.com/ZECHEESELORD/blackbox).
