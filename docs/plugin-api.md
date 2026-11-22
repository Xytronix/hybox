# Plugin API

Any plugin can add report sections, counters, gauges, timeline events, bundle files and config files through `io.github.xytronix.hybox.hytale.HyboxApi`.

## Setup

Hybox is not published to a Maven repository. [Build it](installing.md#build-and-install) and put the JAR in your project, for example in `libs/`.

```kotlin
dependencies {
    compileOnly(files("libs/Hybox-1.1.0.jar"))
}
```

In `manifest.json`:

```json
{
  "Dependencies": {
    "Xytronix:Hybox": "*"
  }
}
```

If Hybox is optional, use `OptionalDependencies` and keep `HyboxApi` calls in a class you load only when Hybox is installed. Register in your plugin's `start()`. When Hybox stops, it drops every registration and recorded metric.

## Methods

Each method takes an owner string first: use your plugin name. Hybox trims it, replaces `/` with `_`, cuts it to 48 characters, and uses `unknown` if it is blank. All methods are static and thread-safe. Limits are per owner. Past a limit, `register*` throws `IllegalStateException` and `record*` ignores new names.

| Method | Limit | Behavior |
| --- | --- | --- |
| `void recordEvent(String plugin, String category, String message)` | None | Adds a point to the report timeline, as JFR event `io.github.xytronix.hybox.PluginEvent`. Category becomes `<owner>/<category>`, cut to 40 then 64 characters. Message is cut to 200. Player names are masked. Never throws. |
| `void recordCount(String plugin, String name, long delta)` | 64 names | Adds `delta` to counter `<owner>/<name>`. Never throws. |
| `void recordGauge(String plugin, String name, double value)` | 64 names | Sets gauge `<owner>/<name>`. Never throws. |
| `Registration registerGauge(String plugin, String name, DoubleSupplier supplier)` | 64 | Hybox reads the supplier once per `Jfr.SampleInterval`. The same name replaces the old supplier. A blank name throws `IllegalArgumentException`. |
| `Registration registerDiagnostics(String plugin, String title, Supplier<Map<String, String>> supplier)` | 64 | Hybox calls the supplier at each capture and shows the map as a report section with that title. |
| `Registration registerExtras(String plugin, BundleExtrasProvider provider)` | 64 | Adds files to bundles. See [Bundle files](#bundle-files). |
| `Registration registerConfig(String plugin, Path file)` | 64 | Adds a config file to the report, read at each capture. Limits are in [Privacy](privacy.md#report-sections). If the file is already registered, returns a handle that removes that registration. |
| `void clearPlugin(String plugin)` | | Closes every registration of the owner and deletes its counter and gauge values. |

Metric names are cut to 64 characters. Blank names and values that are not finite are ignored. Each `record` call is written to the recording as `io.github.xytronix.hybox.PluginMetric` and shown in the report. With Prometheus on, metrics also appear there. See [Metrics](configuration.md#metrics).

The report and the in-game viewer give each metric its own chart:

- **Gauges** are drawn as recorded.
- **Counters** are drawn as a rate per second. Each delta is spread over the time since the previous `recordCount` call for that name, so a batched delta every 30 seconds gives a steady line, not spikes. The first delta has no known start, so the line starts at the second delta and stops at the last one. The stats show the total over the recording window, which counts every delta, and the running total since start when the recording has it.
- **Counters reported once** in the window get no chart and are listed with their total.
- **Metrics that stay at zero** for the whole window are listed by name under the charts.

Up to 64 metrics across all plugins get a chart. Any beyond that are listed with their totals.

`registerExtras` returns a handle that does nothing if Hybox is not running or `Capture.AllowPluginExtras` is off. `registerConfig` does the same with `Capture.AllowModConfigOptIn`. Turning either setting on later does not revive such a registration.

`HyboxApi.Registration` has `unregister()` and `close()`, which does the same. Close each handle when your plugin stops, or call `clearPlugin` once.

## Bundle files

`BundleExtrasProvider` is in `io.github.xytronix.hybox.core.capture`:

| Method | Called |
| --- | --- |
| `List<BundleAttachment> extras(IncidentReport report, TriggerEvent triggerEvent)` | At each capture. Required. |
| `List<BundleAttachment> historicalExtras()` | When Hybox rebuilds a bundle after a crash. Defaults to none. |
| `List<BundleAttachment> configExtras()` | When Hybox rebuilds a bundle after a crash, while `Capture.AllowModConfigOptIn` is on. Defaults to none. |

`BundleAttachment(String pathInZip, byte[] data)` is in `io.github.xytronix.hybox.core.bundle`. The path must be relative, use `/`, and have no empty, `.` or `..` segments. Use `extras/<your plugin>/`. If a path is already taken, Hybox keeps the first entry and logs a warning. Text files are masked and redacted. See [Privacy](privacy.md#masking-and-redaction).

## Callbacks

Gauge suppliers, diagnostic suppliers and extras providers run on Hybox's threads `hybox-callback-<n>`, two at a time, never on a world thread.

| Rule | Value |
| --- | --- |
| Deadline per call | 100 ms |
| Deadline for all callbacks of one kind in one collection | 500 ms |
| Calls in flight per registration | 1. A new call is skipped while the last one runs, or while both threads are busy. |
| Call past its deadline | Canceled. The registration is quarantined and not called again until you register again. |
| Call that throws | Counted as a failure. Called again next time. |

`/hybox status` shows callback failures, timeouts, quarantined registrations and skipped calls.

Make callbacks fast and thread-safe. Read state that is already computed. Do not wait on a world thread, because during a stall the call misses its deadline.

## Example

```java
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import io.github.xytronix.hybox.core.bundle.BundleAttachment;
import io.github.xytronix.hybox.hytale.HyboxApi;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class MyPlugin extends JavaPlugin {
    private static final String OWNER = "my-plugin";
    private final AtomicInteger cacheSize = new AtomicInteger();

    public MyPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void start() {
        HyboxApi.registerDiagnostics(OWNER, "My Plugin", () -> Map.of(
            "Cache entries", Integer.toString(cacheSize.get())));
        HyboxApi.registerGauge(OWNER, "cache_entries", cacheSize::get);
        HyboxApi.registerExtras(OWNER, (report, event) -> List.of(
            new BundleAttachment("extras/my-plugin/state.txt",
                ("cache=" + cacheSize.get() + "\n").getBytes(StandardCharsets.UTF_8))));
    }

    void rebuildCache(int entries, long startedNanos) {
        cacheSize.set(entries);
        HyboxApi.recordGauge(OWNER, "rebuild_ms", (System.nanoTime() - startedNanos) / 1e6);
        HyboxApi.recordCount(OWNER, "cache_rebuilds", 1);
        HyboxApi.recordEvent(OWNER, "cache", "Cache rebuilt");
    }

    @Override
    protected void shutdown() {
        HyboxApi.clearPlugin(OWNER);
    }
}
```
