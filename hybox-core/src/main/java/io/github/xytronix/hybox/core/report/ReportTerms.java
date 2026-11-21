package io.github.xytronix.hybox.core.report;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReportTerms {
    public record Term(String term, String meaning, String doc) {}

    private record Entry(Term term, Pattern pattern) {}

    private static final List<Entry> ENTRIES = List.of(
        entry("Freeze", "A time when a world stopped ticking, so players saw nothing move.", "freeze", "\\bfreeze\\b"),
        entry("Deadlock", "Threads that each wait for a lock another one holds, so none of them can ever go on.",
            "deadlock", "\\b[Dd]eadlock(ed)?\\b"),
        entry("World thread", "The thread that runs all game logic of one world, one tick after another.",
            "world-thread", "\\bworld thread\\b"),
        entry("Worst tick", "The slowest single tick in the recording.", "worst-tick", "\\bworst tick\\b"),
        entry("Tick time", "How long one tick took, in milliseconds.", "tick-time", "\\b[Tt]ick time\\b"),
        entry("Tick budget", "The time one tick may take to keep the TPS target, 33.3 ms at 30 TPS.",
            "tick-budget", "\\btick budget\\b"),
        entry("Target TPS", "The TPS the server aims for, 30 by default.", "target-tps",
            "\\bTPS target\\b|\\btarget TPS\\b"),
        entry("TPS", "Ticks per second, how many game updates a world finishes each second.", "tps", "\\bTPS\\b"),
        entry("Tick", "One game update, in which a world moves entities, runs its systems and sends changes to players.",
            "tick", "\\b[Tt]icks?\\b"),
        entry("p95", "The value 95% of the samples stayed under.", "p95", "\\bp95\\b"),
        entry("Not tied to one world", "Threads Hybox cannot tie to one world from their name, such as chunk generator "
            + "workers, network threads and plugin threads.",
            "not-tied-to-one-world", "\\bnot tied to one world\\b"),
        entry("GC pause", "A stop of every server thread while Java's garbage collector frees memory.",
            "gc-pause", "\\bGC pauses?\\b"),
        entry("Post-GC floor", "The heap still in use right after a garbage collection, the memory the server really holds.",
            "post-gc-floor", "\\bpost-GC floor\\b"),
        entry("Leak", "Memory the server keeps holding after it no longer needs it, so the heap only grows.",
            "leak", "\\bleak\\b"),
        entry("Xmx", "The Java start option -Xmx, which sets the largest heap the server may use.", "xmx", "-Xmx\\b"),
        entry("Heap", "The memory Java keeps the server's objects in, capped by -Xmx.", "heap", "\\b[Hh]eap\\b"),
        entry("RSS", "All memory the server process holds in RAM, the heap and everything else.", "rss", "\\bRSS\\b"),
        entry("Allocation churn", "Many short-lived objects created and thrown away, which makes GC run often.",
            "allocation-churn", "\\bchurn\\b"),
        entry("Allocation rate", "How much new memory the server asks for to create objects, which GC frees later.",
            "allocation-rate", "\\ballocat(ion|ions|ed|es)\\b"),
        entry("Swap", "Disk space the system uses as slow extra memory when RAM runs out.", "swap",
            "\\b[Ss]wap(ped)?\\b"),
        entry("CPU steal", "Time the host of a virtual machine gave this machine's CPU to other guests.",
            "cpu-steal", "\\bsteal\\b"),
        entry("Load average", "How many programs wanted a CPU core at the same time, averaged over one minute.",
            "load-average", "\\bload average\\b|\\bload\\b(?= \\d)"),
        entry("Own share", "The CPU or memory spent in a mod's own code, not in the engine code it calls.",
            "own-share", "\\bown code\\b"),
        entry("Call-chain share", "The samples where a mod's code was anywhere in the call chain, including engine work it "
            + "only passed through.", "call-chain-share", "\\bcall chain\\b"),
        entry("Lock wait", "Time a thread waited for a lock, a guard that lets one thread at a time use some data.",
            "lock-wait", "\\block waits?\\b|\\blocks?\\b"),
        entry("CPU sample", "A snapshot of what a running thread was doing, taken every few milliseconds. "
            + "A share of the samples is a share of CPU time.", "cpu-sample", "\\bCPU samples?\\b"),
        entry("Mixin", "A patch a mod applies to Hytale's own code when the server starts.", "mixin", "\\bMixins?\\b"));

    private ReportTerms() {
    }

    public static List<Term> all() {
        return ENTRIES.stream().map(Entry::term).toList();
    }

    static List<Term> tag(List<List<ReportFindings.Span>> lines) {
        List<Term> used = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (tagFirst(entry, lines)) {
                used.add(entry.term());
            }
        }
        return List.copyOf(used);
    }

    private static boolean tagFirst(Entry entry, List<List<ReportFindings.Span>> lines) {
        for (List<ReportFindings.Span> spans : lines) {
            for (int i = 0; i < spans.size(); i++) {
                ReportFindings.Span span = spans.get(i);
                if (span.term() != null || "code".equals(span.style())) {
                    continue;
                }
                Matcher match = entry.pattern().matcher(span.text());
                if (!match.find()) {
                    continue;
                }
                List<ReportFindings.Span> split = new ArrayList<>(3);
                if (match.start() > 0) {
                    split.add(new ReportFindings.Span(span.text().substring(0, match.start()), span.style()));
                }
                split.add(new ReportFindings.Span(match.group(), span.style(), entry.term().term()));
                if (match.end() < span.text().length()) {
                    split.add(new ReportFindings.Span(span.text().substring(match.end()), span.style()));
                }
                spans.remove(i);
                spans.addAll(i, split);
                return true;
            }
        }
        return false;
    }

    private static Entry entry(String term, String meaning, String anchor, String regex) {
        return new Entry(new Term(term, meaning, "reading-a-report#" + anchor), Pattern.compile(regex));
    }
}
