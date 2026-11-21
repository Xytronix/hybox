package io.github.xytronix.hybox.hytale;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.xytronix.hybox.core.incident.DiagnosticSection;

final class HytaleServerLog {

    private HytaleServerLog() {
    }

    static List<DiagnosticSection> appendTo(List<DiagnosticSection> base, boolean includeServerLog,
                                            int tailLines, ServerLogMasker logMasker) {
        if (!includeServerLog || tailLines <= 0) {
            return base;
        }
        return appendTo(base, HytaleBundleExtrasProvider.latestServerLog(), tailLines, logMasker);
    }

    static List<DiagnosticSection> appendTo(List<DiagnosticSection> base, Path log, int tailLines,
                                            ServerLogMasker logMasker) {
        if (log == null) {
            return base;
        }
        String tail;
        try {
            tail = HytaleBundleExtrasProvider.readTail(log, tailLines);
        } catch (IOException e) {
            return base;
        }
        if (tail.isEmpty()) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(base);
        out.add(new DiagnosticSection("Server log", Map.of(), logMasker.mask(tail)));
        return out;
    }
}
