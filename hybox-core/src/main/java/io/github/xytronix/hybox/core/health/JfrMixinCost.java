package io.github.xytronix.hybox.core.health;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import io.github.xytronix.hybox.core.health.FrameLabels.Label;
import io.github.xytronix.hybox.core.health.FrameLabels.Origin;
import io.github.xytronix.hybox.core.health.JfrTimeline.MixinCost;
import io.github.xytronix.hybox.core.health.JfrTimeline.MixinHit;

final class JfrMixinCost {
    record Stack(String own, String ownMethod, List<String> ownClasses, String started, String startedMethod,
                 List<String> reach, List<String> changedKeys, List<String> changedMethods) {
        static final Stack NONE = new Stack(null, null, List.of(), null, null, List.of(), List.of(), List.of());
    }

    private record Key(String kind, String key, String method, List<String> ownClasses) {}

    private final Map<Key, long[]> hits = new HashMap<>();
    private long samples;

    static Stack stack(String[] types, String[] names, IntFunction<Label> labelOf, IntFunction<String> loaderOf) {
        String own = null;
        String ownMethod = null;
        String started = null;
        String startedMethod = null;
        boolean ownDone = false;
        boolean startedDone = false;
        List<String> ownClasses = new ArrayList<>(2);
        List<String> reach = new ArrayList<>(2);
        List<String> changedKeys = new ArrayList<>(2);
        List<String> changedMethods = new ArrayList<>(2);
        for (int i = 0; i < types.length; i++) {
            String type = types[i];
            if (type == null) {
                continue;
            }
            String name = names[i];
            Label label = labelOf.apply(i);
            boolean mixinCode = FrameLabels.mixinCode(label, name);
            String merged = mixinCode ? merged(name, label) : null;
            if (!ownDone && mixinCode) {
                ownDone = true;
                if (merged != null) {
                    own = merged;
                    ownMethod = method(type, name);
                }
            } else if (!ownDone && !JfrModAttribution.isLibrary(type)) {
                if (label != null || JfrModAttribution.hytale(type) || FrameLabels.resumedOriginal(name)
                    || loaderOf.apply(i) != null) {
                    ownDone = true;
                } else {
                    addOnce(ownClasses, JfrTimelineParser.cleanType(type));
                }
            }
            if (!startedDone && (mixinCode || FrameLabels.resumedOriginal(name))) {
                startedDone = true;
                if (merged != null) {
                    started = merged;
                    startedMethod = method(type, name);
                }
            }
            if (merged != null) {
                addOnce(reach, merged);
            }
            if (label != null) {
                String changedMethod = label.merged() ? null : method(type, FrameLabels.originalMethod(name));
                for (Origin origin : label.origins()) {
                    if (origin.jar() != null) {
                        String key = MixinCost.FILE_KEY + origin.jar();
                        addOnce(reach, key);
                        addChanged(changedKeys, changedMethods, key, changedMethod);
                    }
                }
            } else if (merged == null && i > 0 && type.equals(types[i - 1]) && !FrameLabels.resumedOriginal(name)) {
                Label calleeLabel = labelOf.apply(i - 1);
                String callee = FrameLabels.mixinCode(calleeLabel, names[i - 1]) ? merged(names[i - 1], calleeLabel) : null;
                if (callee != null) {
                    addOnce(reach, callee);
                    addChanged(changedKeys, changedMethods, callee, method(type, name));
                }
            }
        }
        if (reach.isEmpty()) {
            return Stack.NONE;
        }
        return new Stack(own, ownMethod, own == null ? List.of() : List.copyOf(ownClasses), started, startedMethod,
            List.copyOf(reach), List.copyOf(changedKeys), List.copyOf(changedMethods));
    }

    private static void addOnce(List<String> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    private static void addChanged(List<String> keys, List<String> methods, String key, String method) {
        if (method == null) {
            return;
        }
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).equals(key) && methods.get(i).equals(method)) {
                return;
            }
        }
        keys.add(key);
        methods.add(method);
    }

    private static String merged(String name, Label label) {
        if (label != null && label.merged()) {
            Origin origin = FrameLabels.mergedBy(label, name);
            if (origin == null) {
                return null;
            }
            if (origin.jar() != null) {
                return MixinCost.FILE_KEY + origin.jar();
            }
        }
        String id = FrameLabels.mixinJar(name);
        return id == null ? null : MixinCost.ID_KEY + id;
    }

    private static String method(String type, String name) {
        return JfrTimelineParser.cleanType(type) + "." + name;
    }

    void add(Stack stack) {
        samples++;
        if (stack == Stack.NONE) {
            return;
        }
        if (stack.own() != null) {
            count(MixinCost.OWN, stack.own(), stack.ownMethod(), stack.ownClasses());
        }
        if (stack.started() != null) {
            count(MixinCost.STARTED, stack.started(), stack.startedMethod(), List.of());
        }
        count(MixinCost.REACH, String.join(MixinCost.KEY_SEPARATOR, stack.reach()), null, List.of());
        for (int i = 0; i < stack.changedKeys().size(); i++) {
            count(MixinCost.CHANGED, stack.changedKeys().get(i), stack.changedMethods().get(i), List.of());
        }
    }

    private void count(String kind, String key, String method, List<String> ownClasses) {
        hits.computeIfAbsent(new Key(kind, key, method, ownClasses), k -> new long[1])[0]++;
    }

    MixinCost build() {
        if (hits.isEmpty()) {
            return null;
        }
        List<MixinHit> out = new ArrayList<>(hits.size());
        hits.forEach((key, count) -> out.add(new MixinHit(key.kind(), key.key(), key.method(), key.ownClasses(),
            count[0])));
        out.sort((a, b) -> Long.compare(b.samples(), a.samples()));
        return new MixinCost(samples, List.copyOf(out));
    }
}
