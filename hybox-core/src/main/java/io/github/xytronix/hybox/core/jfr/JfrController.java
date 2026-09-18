package io.github.xytronix.hybox.core.jfr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import jdk.jfr.Configuration;
import jdk.jfr.EventSettings;
import jdk.jfr.Recording;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.RecordingState;

/**
 * Controls a single rolling JFR recording and exposes a minimal API for dumping it.
 */
public final class JfrController implements AutoCloseable {
    private static final String DEFAULT_CONFIGURATION = "default";
    private static final String DUMP_MARKER_PREFIX = "hybox dump:";

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
        startWith(configurationName);
    }

    public synchronized void restart(String configurationOverride) {
        close();
        startWith(configurationOverride == null || configurationOverride.isBlank()
            ? configurationName : configurationOverride);
    }

    public synchronized void applyConfiguration(String configurationOverride) {
        Recording rec = requireRecording();
        String name = configurationOverride == null || configurationOverride.isBlank()
            ? configurationName : configurationOverride;
        try {
            rec.setSettings(Configuration.getConfiguration(name).getSettings());
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING,
                "Failed to apply JFR configuration '" + name + "' to the live recording.", e);
            return;
        }
        enableMarkerEvent(rec);
        if (oldObjectSampling) {
            enableOldObjectSampling(rec);
        }
        disableConfiguredEvents(rec);
    }

    private void startWith(String configuration) {
        Recording created = createConfiguredRecording(configuration);
        try {
            created.setName(recordingName);
            created.setToDisk(true);
            created.setMaxAge(maxAge.isZero() ? null : maxAge);
            created.setMaxSize(Math.max(0L, maxSizeBytes));
            if (maxAge.isZero() && maxSizeBytes <= 0L) {
                logger.log(System.Logger.Level.WARNING,
                    "JFR recording has no age or size cap (both unlimited); the on-disk recording can grow until it "
                    + "fills the disk. Set Jfr.MaxAge or Jfr.MaxSizeBytes to a positive value to bound it.");
            }
            enableMarkerEvent(created);
            if (oldObjectSampling) {
                enableOldObjectSampling(created);
            }
            disableConfiguredEvents(created);
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

    private Recording createConfiguredRecording(String configurationName) {
        Recording configured = tryLoadConfiguration(configurationName);
        if (configured != null) {
            return configured;
        }

        if (!"profile".equals(configurationName)) {
            Recording profile = tryLoadConfiguration("profile");
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

    private Recording tryLoadConfiguration(String configurationName) {
        try {
            return new Recording(Configuration.getConfiguration(configurationName));
        } catch (IOException | ParseException e) {
            logger.log(System.Logger.Level.DEBUG, "Failed to load JFR configuration '" + configurationName + "'.", e);
            return null;
        }
    }
}
