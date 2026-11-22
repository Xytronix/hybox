# Plugin API

Any plugin can add its own data to Hybox: report sections, counters, gauges, timeline events, bundle files and config files. Everything goes through one class, `io.github.xytronix.hybox.hytale.HyboxApi`.

## Setup

Hybox is not published to a Maven repository. Build it (see [Installing](installing.md#build)) and put the JAR in your project, for example in `libs/`.

`build.gradle.kts`:

```kotlin
dependencies {
    compileOnly(files("libs/Hybox-1.0.0.jar"))
}
```

`manifest.json`:

```json
{
  "Dependencies": {
    "Xytronix:Hybox": "*"
  }
}
```

If Hybox is optional for your plugin, list it under `OptionalDependencies` instead. Then keep every `HyboxApi` call in a class that you load only when Hybox is installed, so your plugin still loads without it.

## Owner token

Every method takes a `plugin` string first. Hybox uses it to name your data, to count your limits and to clear your registrations. Use the same short, stable name everywhere, such as your plugin name. Hybox trims it, replaces `/` with `_`, cuts it to 48 characters, and uses `unknown` if it is blank.

## Methods

| Method | Returns | Limit per owner |
| --- | --- | --- |
| `recordEvent(String plugin, String category, String message)` | `void` | None |
| `recordCount(String plugin, String name, long delta)` | `void` | 64 counter names |
| `recordGauge(String plugin, String name, double value)` | `void` | 64 gauge names |
| `registerGauge(String plugin, String name, DoubleSupplier supplier)` | `Registration` | 64 |
| `registerDiagnostics(String plugin, String title, Supplier<Map<String, String>> supplier)` | `Registration` | 64 |
| `registerExtras(String plugin, BundleExtrasProvider provider)` | `Registration` | 64 |
| `registerConfig(String plugin, Path file)` | `Registration` | 64 |
| `clearPlugin(String plugin)` | `void` | |

All methods are static and safe to call from any thread.

### recordEvent

Adds a point to the timeline of the report. The category becomes `<owner>/<category>`, with the category cut to 40 characters and the whole cut to 64. The message is cut to 200 characters. Player names in both are masked. Hybox writes the event to the recording as `io.github.xytronix.hybox.PluginEvent`. The method never throws.

### recordCount and recordGauge

`recordCount` adds `delta` to a counter. `recordGauge` sets a gauge to `value`. The metric is named `<owner>/<name>`, with the name cut to 64 characters. Hybox writes each call to the recording as `io.github.xytronix.hybox.PluginMetric` and shows plugin metrics in the report. With Prometheus on, they appear as `hybox_plugin_counter` and `hybox_plugin_gauge` with a `name` label.

A blank name or a value that is not finite is ignored. Counters and gauges each allow 64 names per owner. Calls with a new name beyond that are ignored. Neither method throws.

### registerGauge

Registers a supplier that Hybox reads once per `Jfr.SampleInterval`. Registering the same name again replaces the old supplier. Throws `IllegalArgumentException` for a blank name, and `IllegalStateException` when the owner already has 64 gauges.

### registerDiagnostics

Registers a section for the report. Hybox calls the supplier at each capture and shows the map as a section with the given title. Redact patterns apply to the values. Throws `IllegalStateException` when the owner already has 64 sections.

### registerExtras

Registers a provider of bundle files. `BundleExtrasProvider` is in `io.github.xytronix.hybox.core.capture`:

| Method | Called |
| --- | --- |
| `List<BundleAttachment> extras(IncidentReport report, TriggerEvent triggerEvent)` | At each capture. Required. |
| `List<BundleAttachment> historicalExtras()` | When Hybox rebuilds a bundle after a crash. Defaults to none. |
| `List<BundleAttachment> configExtras()` | When Hybox rebuilds a bundle after a crash, only while `Capture.AllowModConfigOptIn` is on. Defaults to none. |

`BundleAttachment(String pathInZip, byte[] data)` is in `io.github.xytronix.hybox.core.bundle`. The path must be relative, use `/`, and contain no empty, `.` or `..` segments. Use a path under `extras/<your plugin>/` so it does not collide with Hybox's own files. Text files are redacted and masked. See [Privacy](privacy.md#player-names).

If Hybox is not running, or `Capture.AllowPluginExtras` is off, `registerExtras` returns a handle that does nothing and your provider is never called. Throws `IllegalStateException` when the owner already has 64 providers.

### registerConfig

Asks Hybox to include a config file in the report. Hybox reads up to 96 KiB of it at each capture. A bundle holds at most 12 config files in total, and registered files come before files that Hybox finds itself.

If Hybox is not running, or `Capture.AllowModConfigOptIn` is off, it returns a handle that does nothing. If the file is already registered, it returns a handle that removes that registration. Throws `IllegalStateException` when the owner already has 64 files.

### clearPlugin

Closes every registration of the owner and deletes its counter and gauge values.

## Registrations

`HyboxApi.Registration` has `unregister()` and `close()`, which does the same, so it works in try-with-resources. Close each handle when your plugin stops, or call `clearPlugin` once.

When Hybox itself stops, it drops every registration and every recorded metric. Register in your plugin's `start()`, so Hybox is running when you do.

## Callbacks

Gauge suppliers, diagnostic suppliers and extras providers are callbacks. Hybox runs them on its own threads, named `hybox-callback-<n>`, never on a world thread. Two run at a time.

| Limit | Value |
| --- | --- |
| One call | 100 ms |
| All callbacks of one kind in one collection | 500 ms |
| Calls in flight per registration | 1 |

- A call that runs past its deadline is cancelled, and that registration is quarantined. Hybox does not call it again until you register again.
- A call that throws is counted as a failure and returns nothing. Hybox calls it again next time.
- A call is skipped if the previous call of the same registration is still running, or if both callback threads are busy.

`/hybox status` shows callback failures, timeouts, quarantined registrations and rejected calls. `--verbose` adds the last failure.

Make callbacks fast and thread-safe. Read state that is already computed. Do not wait on a world thread: during a stall, the call would miss its deadline.

## Example

```java
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import io.github.xytronix.hybox.core.bundle.BundleAttachment;
import io.github.xytronix.hybox.hytale.HyboxApi;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class MyPlugin extends JavaPlugin {
    private static final String OWNER = "my-plugin";
    private final AtomicInteger cacheSize = new AtomicInteger();
    private volatile String lastRebuild = "never";

    public MyPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void start() {
        HyboxApi.registerDiagnostics(OWNER, "My Plugin", () -> Map.of(
            "Cache entries", Integer.toString(cacheSize.get()),
            "Last rebuild", lastRebuild));
        HyboxApi.registerGauge(OWNER, "cache_entries", cacheSize::get);
        HyboxApi.registerExtras(OWNER, (report, event) -> List.of(
            new BundleAttachment("extras/my-plugin/state.txt",
                ("cache=" + cacheSize.get() + "\n").getBytes(StandardCharsets.UTF_8))));
    }

    void rebuildCache() {
        long started = System.nanoTime();
        cacheSize.set(rebuild());
        HyboxApi.recordGauge(OWNER, "rebuild_ms", (System.nanoTime() - started) / 1e6);
        HyboxApi.recordCount(OWNER, "cache_rebuilds", 1);
        HyboxApi.recordEvent(OWNER, "cache", "Cache rebuilt");
        lastRebuild = Instant.now().toString();
    }

    private int rebuild() {
        return 0;
    }

    @Override
    protected void shutdown() {
        HyboxApi.clearPlugin(OWNER);
    }
}
```
