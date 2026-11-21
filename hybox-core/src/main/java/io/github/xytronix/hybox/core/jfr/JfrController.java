package io.github.xytronix.hybox.core.jfr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jdk.jfr.Configuration;
import jdk.jfr.EventSettings;
import jdk.jfr.Recording;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.RecordingState;

import io.github.xytronix.hybox.core.health.JfrTimeline;

/**
 * Controls a single rolling JFR recording and exposes a minimal API for dumping it.
 */
public final class JfrController implements AutoCloseable {
    private static final String DEFAULT_CONFIGURATION = "default";
    private static final String PROFILE_CONFIGURATION = "profile";
    static final String PROFILE_SAMPLE_PERIOD = "10 ms";
    static final String PROFILE_LOCK_THRESHOLD = "10 ms";
    static final String MEMORY_ALLOCATION_THROTTLE = "1000/s";
    static final String MEMORY_ROOT_SEARCH_CUTOFF = "1 s";
    private static final String DUMP_MARKER_PREFIX = "hybox dump:";
    static final Set<String> LEAN_DISABLED_EVENTS = Stream.concat(
            Stream.of("jdk.ThreadSleep", "jdk.JavaMonitorWait", "jdk.NativeMethodSample"),
            RecordingScrubber.DROPPED_EVENTS.stream().filter(name -> !name.equals("jdk.InitialSystemProperty")))
        .collect(Collectors.toUnmodifiableSet());

    private final Duration maxAge;
    private final long maxSizeBytes;
    private final String recordingName;
    private final List<String> disabledEvents;
    private final String configurationName;
    private final boolean oldObjectSampling;
    private Recording recording;
    private volatile RecordingHealth health;
    private final System.Logger logger = System.getLogger(JfrController.class.getName());

    private static final class RecordingHealth implements FlightRecorderListener {
        private final Recording recording;
        private volatile boolean running;

        private RecordingHealth(Recording recording) {
            this.recording = recording;
        }

        @Override
        public void recordingStateChanged(Recording changed) {
            if (changed == recording) {
                running = changed.getState() == RecordingState.RUNNING;
            }
        }
    }

    public JfrController(Duration maxAge, long maxSizeBytes, String recordingName) {
        this(maxAge, maxSizeBytes, recordingName, List.of());
    }

    public JfrController(Duration maxAge, long maxSizeBytes, String recordingName, List<String> disabledEvents) {
        this(maxAge, maxSizeBytes, recordingName, disabledEvents, DEFAULT_CONFIGURATION);
    }

    public JfrController(Duration maxAge, long maxSizeBytes, String recordingName,
                         List<String> disabledEvents, String configurationName) {
        this(maxAge, maxSizeBytes, recordingName, disabledEvents, configurationName, false);
    }

    public JfrController(Duration maxAge, long maxSizeBytes, String recordingName,
                         List<String> disabledEvents, String configurationName, boolean oldObjectSampling) {
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
        this.maxSizeBytes = maxSizeBytes;
        this.recordingName = Objects.requireNonNull(recordingName, "recordingName");
        this.disabledEvents = List.copyOf(Objects.requireNonNull(disabledEvents, "disabledEvents"));
        this.configurationName = configurationName == null || configurationName.isBlank()
            ? DEFAULT_CONFIGURATION : configurationName;
        this.oldObjectSampling = oldObjectSampling;
    }

    public synchronized void start() {
        if (recording != null) {
            return;
        }
        startWith(null);
    }

    public synchronized void restart(ProfileMode mode) {
        close();
        startWith(mode);
    }

    public synchronized void applyConfiguration(ProfileMode mode) {
        Recording rec = requireRecording();
        try {
            rec.setSettings(settings(mode));
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING,
                "Failed to apply JFR configuration '" + configurationLabel(mode) + "' to the live recording.", e);
            return;
        }
        applyHyboxSettings(rec, mode);
    }

    private void applyHyboxSettings(Recording rec, ProfileMode mode) {
        enableMarkerEvent(rec);
        if (oldObjectSampling && mode != ProfileMode.MEMORY) {
            enableOldObjectSampling(rec);
        }
        disableConfiguredEvents(rec);
    }

    private String configurationLabel(ProfileMode mode) {
        return mode == null ? configurationName : PROFILE_CONFIGURATION + " " + mode.id();
    }

    private void startWith(ProfileMode mode) {
        Recording created = createConfiguredRecording(mode);
        try {
            created.setName(recordingName);
            created.setToDisk(true);
            created.setMaxAge(maxAge.isZero() ? null : maxAge);
            created.setMaxSize(Math.max(0L, maxSizeBytes));
            if (maxAge.isZero() && maxSizeBytes <= 0L) {
                logger.log(System.Logger.Level.WARNING,
                    "JFR recording has no age or size cap (both unlimited). The recording on disk can grow until it "
                    + "fills the disk. Set Jfr.MaxAge or Jfr.MaxSizeBytes to a positive value to bound it.");
            }
            applyHyboxSettings(created, mode);
            RecordingHealth observer = new RecordingHealth(created);
            FlightRecorder.addListener(observer);
            health = observer;
            created.start();
            this.recording = created;
        } catch (RuntimeException | Error failure) {
            RecordingHealth observer = health;
            health = null;
            if (observer != null) {
                FlightRecorder.removeListener(observer);
            }
            created.close();
            throw failure;
        }
    }

    public synchronized EventSettings enableEvent(String eventName) {
        return requireRecording().enable(eventName);
    }

    public synchronized void dump(Path target) throws IOException {
        Objects.requireNonNull(target, "target");
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        HyboxMarkerEvent marker = new HyboxMarkerEvent();
        marker.message = DUMP_MARKER_PREFIX + " " + target.getFileName();
        marker.commit();
        requireRecording().dump(target);
    }

    @Override
    public synchronized void close() {
        Recording current = recording;
        recording = null;
        RecordingHealth observer = health;
        health = null;
        if (observer != null) {
            FlightRecorder.removeListener(observer);
        }
        if (current != null) {
            current.close();
        }
    }

    public boolean isRunning() {
        RecordingHealth current = health;
        return current != null && current.running;
    }

    private Recording requireRecording() {
        if (recording == null) {
            throw new IllegalStateException("Recording has not been started.");
        }
        return recording;
    }

    private Recording createConfiguredRecording(ProfileMode mode) {
        Recording configured = tryLoadConfiguration(mode);
        if (configured != null) {
            return configured;
        }

        if (mode == null && !PROFILE_CONFIGURATION.equals(configurationName)) {
            Recording profile = tryLoadConfiguration(ProfileMode.LAG);
            if (profile != null) {
                return profile;
            }
        }

        logger.log(System.Logger.Level.WARNING, "Failed to load JFR configurations. Falling back to an unconfigured recording.");
        return new Recording();
    }

    private void enableMarkerEvent(Recording recording) {
        try {
            recording.enable(HyboxMarkerEvent.class)
                .withoutStackTrace()
                .withThreshold(Duration.ZERO);
        } catch (IllegalArgumentException e) {
            logger.log(System.Logger.Level.WARNING, "Failed to enable marker event.", e);
        }
    }

    private void enableOldObjectSampling(Recording recording) {
        try {
            recording.enable("jdk.OldObjectSample")
                .withStackTrace()
                .with("cutoff", "0 ns");
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING, "Failed to enable old-object sampling.", e);
        }
    }

    private void disableConfiguredEvents(Recording recording) {
        for (String eventName : disabledEvents) {
            try {
                recording.disable(eventName);
                logger.log(System.Logger.Level.DEBUG, "Disabled JFR event: " + eventName);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING, "Failed to disable JFR event '" + eventName + "'.", e);
            }
        }
    }

    private Recording tryLoadConfiguration(ProfileMode mode) {
        try {
            return new Recording(settings(mode));
        } catch (IOException | ParseException e) {
            logger.log(System.Logger.Level.DEBUG, "Failed to load JFR configuration '" + configurationLabel(mode) + "'.", e);
            return null;
        }
    }

    private Map<String, String> settings(ProfileMode mode) throws IOException, ParseException {
        return mode == null ? settings(configurationName) : profileSettings(mode);
    }

    static Map<String, String> settings(String configurationName) throws IOException, ParseException {
        Map<String, String> settings = new HashMap<>(Configuration.getConfiguration(configurationName).getSettings());
        if (DEFAULT_CONFIGURATION.equals(configurationName)) {
            for (String event : LEAN_DISABLED_EVENTS) {
                settings.put(event + "#enabled", "false");
            }
            String lockWait = JfrTimeline.LOCK_WAIT_MS + " ms";
            settings.put("jdk.ThreadPark#threshold", lockWait);
            settings.put("jdk.JavaMonitorEnter#threshold", lockWait);
        }
        return settings;
    }

    static Map<String, String> profileSettings(ProfileMode mode) throws IOException, ParseException {
        Map<String, String> settings = settings(PROFILE_CONFIGURATION);
        settings.put("jdk.ExecutionSample#enabled", "true");
        settings.put("jdk.ExecutionSample#period", PROFILE_SAMPLE_PERIOD);
        settings.put("jdk.ThreadPark#enabled", "true");
        settings.put("jdk.ThreadPark#threshold", PROFILE_LOCK_THRESHOLD);
        settings.put("jdk.JavaMonitorEnter#enabled", "true");
        settings.put("jdk.JavaMonitorEnter#threshold", PROFILE_LOCK_THRESHOLD);
        if (mode == ProfileMode.MEMORY) {
            settings.put("jdk.ObjectAllocationSample#enabled", "true");
            settings.put("jdk.ObjectAllocationSample#throttle", MEMORY_ALLOCATION_THROTTLE);
            settings.put("jdk.OldObjectSample#enabled", "true");
            settings.put("jdk.OldObjectSample#stackTrace", "true");
            settings.put("jdk.OldObjectSample#cutoff", MEMORY_ROOT_SEARCH_CUTOFF);
        }
        return settings;
    }
}
