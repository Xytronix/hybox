# Plugin API

A mod can add metrics, timeline events and settings to Hybox reports by writing JDK Flight Recorder (JFR) events. This is the recommended way: the mod needs no Hybox JAR or dependency, and any JFR tool reads the same events. A plugin can also add report sections, counters, gauges, timeline events, bundle files and config files through `io.github.xytronix.hybox.hytale.HyboxApi`.

## JFR events

Give each event the category `{"Mods", "<Mod name>"}`. Hybox recognises three shapes by their field names:

| Shape | Fields | In the report |
| --- | --- | --- |
| Metric | `name` (string), `kind` (string, `counter` or `gauge`), `value` (number) | A chart under Plugin metrics, drawn like a `HyboxApi` metric (see [Methods](#methods)). For a counter, `value` is the change since the last event with that name. For a gauge, it is the current value. |
| Action | `category` (string), `message` (string) | A marker on every report chart, with one entry per mod in the event legend, and a row in the event list under Key metrics in game. |
| Setting | `name` (string), `value` (string, number or boolean) | A row in the card "`<mod>` settings" under Captured diagnostics. The last value of each name is shown. |

The mod is the second category entry. When a loaded plugin has that name, the report shows the plugin id instead, for example `IroriPowered:Refixes` for `Refixes`. Events whose names start with `jdk.` or `io.github.xytronix.hybox.` are never mod events. Two event types with the same name, for example from two class loaders, count as one.

Hybox cuts mod names to 48 characters, metric and setting names to 64, action categories to 40, and messages and setting values to 200. It reads at most 64 metrics and 128 settings per mod, and the last 2,000 actions. JFR metrics show in reports only, not in Prometheus. Action messages and setting values are masked and redacted like the rest of the report. See [Privacy](privacy.md#masking-and-redaction).

JFR writes events only while a recording runs. Write metrics on a period, such as `@Period("5 s")`, and settings with `@Period("beginChunk")`, so that every part of a recording has them.

```java
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;

@Name("mymod.Metric")
@Label("My Mod Metric")
@Category({"Mods", "My Mod"})
@Period("5 s")
@StackTrace(false)
public final class MetricEvent extends Event {
    @Label("Name")
    public String name;

    @Label("Kind")
    public String kind;

    @Label("Value")
    public double value;
}

@Name("mymod.Action")
@Label("My Mod Action")
@Category({"Mods", "My Mod"})
@StackTrace(false)
public final class ActionEvent extends Event {
    @Label("Category")
    public String category;

    @Label("Message")
    public String message;
}
```

Register the metric hook once, for example in `start()`:

```java
Runnable hook = () -> {
    MetricEvent event = new MetricEvent();
    event.name = "cache entries";
    event.kind = "gauge";
    event.value = cacheSize.get();
    event.commit();
};
FlightRecorder.addPeriodicEvent(MetricEvent.class, hook);
```

In `shutdown()`, call `FlightRecorder.removePeriodicEvent(hook)`.

A setting event has the fields `name` and `value` and `@Period("beginChunk")`, and is written from its own periodic hook the same way.

## HyboxApi setup

To use `HyboxApi`, add Hybox as a dependency. Hybox is not published to a Maven repository. [Build it](installing.md#build-and-install) and put the JAR in your project, for example in `libs/`.

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

The report and the in-game viewer give each metric its own chart, for `HyboxApi` and JFR metrics alike:

- **Gauges** are drawn as recorded.
- **Counters** are drawn as a rate per second. Each delta is spread over the time since the previous delta for that name, so a batched delta every 30 seconds gives a steady line, not spikes. The first delta has no known start, so the line starts at the second delta and stops at the last one. The stats show the total over the recording window, which counts every delta, and the running total since start when the recording has it. JFR counters have no running total.
- **Counters reported once** in the window get no chart and are listed with their total.
- **Metrics that stay at zero** for the whole window are listed by name under the charts.

Up to 64 metrics across all plugins and mods get a chart. Any beyond that are listed with their totals.

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
