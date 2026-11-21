package io.github.xytronix.hybox.hytale;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import io.github.xytronix.hybox.core.incident.DiagnosticSection;

final class HytaleEntities {
    private static final System.Logger LOGGER = System.getLogger(HytaleEntities.class.getName());
    private static final int TYPE_LIMIT = 10;
    private static final long DEADLINE_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    private static final String[] NPC_CLASSES = {
        "com.hypixel.hytale.server.npc.components.NPCComponent",
        "com.hypixel.hytale.server.npc.entities.NPCEntity"
    };

    private static volatile NpcAccess npcAccess;

    private HytaleEntities() {
    }

    private record NpcAccess(ComponentType<EntityStore, Component<EntityStore>> type, MethodHandle roleName) {
    }

    static List<DiagnosticSection> appendTo(List<DiagnosticSection> base) {
        Map<String, String> entries = entries();
        if (entries.isEmpty()) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(base);
        out.add(new DiagnosticSection("Entities", entries));
        return out;
    }

    private static Map<String, String> entries() {
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try {
            long deadline = System.nanoTime() + DEADLINE_NANOS;
            NpcAccess npc = npcAccess();
            Map<String, CompletableFuture<String>> pending = new LinkedHashMap<>();
            for (Map.Entry<String, World> entry : Universe.get().getWorlds().entrySet()) {
                String name = entry.getKey();
                World world = entry.getValue();
                if (name == null || name.isBlank() || world == null) {
                    continue;
                }
                pending.put(name, HytaleWorldThreads.submit(world, () -> describeWorld(world, npc)));
            }
            for (Map.Entry<String, CompletableFuture<String>> entry : pending.entrySet()) {
                String name = entry.getKey();
                try {
                    String value = HytaleWorldThreads.await(entry.getValue(), deadline);
                    if (value != null) {
                        out.put(name, value);
                    } else if (entry.getValue().isCancelled()) {
                        LOGGER.log(System.Logger.Level.DEBUG, "Entity count skipped for " + name
                            + ": the world thread did not answer in time.");
                    }
                } catch (ExecutionException e) {
                    LOGGER.log(System.Logger.Level.WARNING, "Entity count failed for " + name, e.getCause());
                }
            }
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.WARNING, "Entity enumeration failed.", t);
        }
        return out;
    }

    private static NpcAccess npcAccess() {
        NpcAccess access = npcAccess;
        if (access == null) {
            access = resolveNpcAccess();
            npcAccess = access;
        }
        return access;
    }

    @SuppressWarnings("unchecked")
    private static NpcAccess resolveNpcAccess() {
        ClassLoader loader = Universe.class.getClassLoader();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        for (String name : NPC_CLASSES) {
            try {
                Class<?> type = Class.forName(name, true, loader);
                Object componentType = lookup.findStatic(type, "getComponentType",
                    MethodType.methodType(ComponentType.class)).invoke();
                MethodHandle roleName = lookup.findVirtual(type, "getRoleName", MethodType.methodType(String.class))
                    .asType(MethodType.methodType(String.class, Object.class));
                if (componentType != null) {
                    return new NpcAccess((ComponentType<EntityStore, Component<EntityStore>>) componentType, roleName);
                }
            } catch (Throwable t) {
                LOGGER.log(System.Logger.Level.DEBUG, "NPC access via " + name + " unavailable.", t);
            }
        }
        return null;
    }

    private static String describeWorld(World world, NpcAccess access) {
        var store = world.getEntityStore().getStore();
        int total = store.getEntityCount();
        if (total <= 0) {
            return null;
        }
        Map<String, int[]> byRole = new HashMap<>();
        int[] npcTotal = {0};
        store.forEachChunk((chunk, buffer) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                Object npc = access == null ? null : chunk.getComponent(i, access.type());
                if (npc == null) {
                    continue;
                }
                String role;
                try {
                    role = (String) access.roleName().invokeExact(npc);
                } catch (Throwable t) {
                    role = null;
                }
                byRole.computeIfAbsent(role == null || role.isBlank() ? "NPC" : role, k -> new int[1])[0]++;
                npcTotal[0]++;
            }
        });

        List<Map.Entry<String, int[]>> ranked = new ArrayList<>(byRole.entrySet());
        ranked.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder value = new StringBuilder(128);
        int shown = 0;
        int shownCount = 0;
        for (Map.Entry<String, int[]> role : ranked) {
            if (shown++ >= TYPE_LIMIT) {
                break;
            }
            if (value.length() > 0) {
                value.append(", ");
            }
            value.append(role.getKey()).append(" x").append(role.getValue()[0]);
            shownCount += role.getValue()[0];
        }
        int foldedNpcs = npcTotal[0] - shownCount;
        if (foldedNpcs > 0) {
            value.append(value.length() > 0 ? ", " : "").append("Other NPC x").append(foldedNpcs);
        }
        int players = playerCount(world);
        if (players > 0) {
            value.append(value.length() > 0 ? ", " : "").append("Player x").append(players);
        }
        int other = total - npcTotal[0] - Math.max(0, players);
        if (other > 0) {
            value.append(value.length() > 0 ? ", " : "").append("Other x").append(other);
        }
        return value.length() == 0 ? null : value.toString();
    }

    private static int playerCount(World world) {
        try {
            return world.getPlayerCount();
        } catch (Throwable t) {
            return 0;
        }
    }
}
