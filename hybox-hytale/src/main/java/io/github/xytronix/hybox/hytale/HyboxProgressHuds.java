package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

final class HyboxProgressHuds implements AutoCloseable {
    enum Kind { PROFILE, CAPTURE }

    static final class Handle {
        private final HyboxProgressHuds owner;
        private final PlayerHud player;
        private final Kind kind;
        private boolean stopped;

        private Handle(HyboxProgressHuds owner, PlayerHud player, Kind kind) {
            this.owner = owner;
            this.player = player;
            this.kind = kind;
        }
    }

    private static final class PlayerHud {
        private final UUID id;
        private PlayerRef requester;
        private Ref<EntityStore> ref;
        private Store<EntityStore> store;
        private World world;
        private Handle current;
        private Handle suspendedProfile;
        private Handle shown;
        private HyboxProgressHud hud;
        private HudManager manager;
        private ScheduledFuture<?> refresh;
        private boolean queued;
        private boolean removing;

        private PlayerHud(UUID id, PlayerRef requester, Ref<EntityStore> ref,
                          Store<EntityStore> store, World world) {
            this.id = id;
            this.requester = requester;
            this.ref = ref;
            this.store = store;
            this.world = world;
        }
    }

    private final HyboxRuntime runtime;
    private final System.Logger logger;
    private final Map<UUID, PlayerHud> players = new HashMap<>();
    private boolean closed;

    HyboxProgressHuds(HyboxRuntime runtime, System.Logger logger) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    Handle start(PlayerRef requester, Kind kind) {
        if (requester == null) return null;
        synchronized (this) {
            if (closed) return null;
        }
        UUID id = null;
        try {
            id = requester.getUuid();
            Ref<EntityStore> ref = requester.getReference();
            if (ref == null || !ref.isValid()) {
                invalidate(id, requester);
                return null;
            }
            Store<EntityStore> store = ref.getStore();
            World world = store.getExternalData().getWorld();
            PlayerHud player;
            Handle handle;
            synchronized (this) {
                if (closed) return null;
                player = players.get(id);
                if (player == null) {
                    player = new PlayerHud(id, requester, ref, store, world);
                    players.put(id, player);
                } else if (player.ref != ref || player.requester != requester) {
                    rebind(player, requester, ref, store, world);
                }
                cancelRefresh(player);
                if (kind == Kind.CAPTURE && player.current != null && player.current.kind == Kind.PROFILE) {
                    player.suspendedProfile = player.current;
                } else {
                    if (player.current != null) player.current.stopped = true;
                    if (kind == Kind.PROFILE && player.suspendedProfile != null) {
                        player.suspendedProfile.stopped = true;
                        player.suspendedProfile = null;
                    }
                }
                handle = new Handle(this, player, kind);
                player.current = handle;
            }
            queue(player);
            return handle;
        } catch (RuntimeException | LinkageError failure) {
            invalidate(id, requester);
            log(failure);
            return null;
        }
    }

    void playerReady(Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) return;
        PlayerHud player;
        try {
            Store<EntityStore> store = ref.getStore();
            PlayerRef requester = store.getComponent(ref, PlayerRef.getComponentType());
            if (requester == null) return;
            synchronized (this) {
                if (closed) return;
                player = players.get(requester.getUuid());
                if (player == null || player.current == null || player.current.stopped) return;
                rebind(player, requester, ref, store, store.getExternalData().getWorld());
            }
        } catch (RuntimeException | LinkageError failure) {
            log(failure);
            return;
        }
        queue(player);
    }

    private void rebind(PlayerHud player, PlayerRef requester, Ref<EntityStore> ref, Store<EntityStore> store, World world) {
        cancelRefresh(player);
        player.requester = requester;
        player.ref = ref;
        player.store = store;
        player.world = world;
        player.hud = null;
        player.shown = null;
        player.manager = null;
    }

    void stop(Handle handle) {
        if (handle == null || handle.owner != this) return;
        PlayerHud player = handle.player;
        synchronized (this) {
            if (handle.stopped) return;
            handle.stopped = true;
            if (player.current == handle) {
                cancelRefresh(player);
                player.current = player.suspendedProfile;
                player.suspendedProfile = null;
            } else if (player.suspendedProfile == handle) {
                player.suspendedProfile = null;
            } else {
                return;
            }
        }
        queue(player);
    }

    @Override
    public void close() {
        PlayerHud[] active;
        synchronized (this) {
            if (closed) return;
            closed = true;
            active = players.values().toArray(PlayerHud[]::new);
            for (PlayerHud player : active) discard(player);
        }
        for (PlayerHud player : active) queue(player);
    }

    private void queue(PlayerHud player) {
        synchronized (this) {
            if (player.queued) return;
            if (player.current == null || player.current.stopped) cancelRefresh(player);
            if (player.current == null && player.hud == null) {
                players.remove(player.id, player);
                return;
            }
            player.queued = true;
        }
        try {
            player.world.execute(() -> refresh(player));
        } catch (RuntimeException | LinkageError failure) {
            synchronized (this) {
                player.queued = false;
                discard(player);
            }
            log(failure);
        }
    }

    private synchronized void refresh(PlayerHud player) {
        try {
            if (!valid(player)) {
                cancelRefresh(player);
                if (!player.requester.isValid() && !online(player.id)) {
                    discard(player);
                    removeInstalled(player, player.manager);
                }
                return;
            }
            Player entity = player.store.getComponent(player.ref, Player.getComponentType());
            if (entity == null) {
                discard(player);
                removeInstalled(player, player.manager);
                return;
            }
            HudManager huds = entity.getHudManager();
            player.manager = huds;
            Handle current = player.current;
            if (current == null || current.stopped || players.get(player.id) != player) {
                removeInstalled(player, huds);
                discard(player);
                return;
            }
            if (player.shown != current) {
                removeInstalled(player, huds);
                if (huds.getCustomHud(HyboxProgressHud.KEY) != null) {
                    huds.removeCustomHud(player.requester, HyboxProgressHud.KEY);
                }
                player.hud = new HyboxProgressHud(player.requester, runtime, current.kind,
                    removed -> removed(player, removed));
                player.shown = current;
                huds.addCustomHud(player.requester, player.hud);
            } else {
                if (huds.getCustomHud(HyboxProgressHud.KEY) != player.hud) {
                    discard(player);
                    player.hud = null;
                    player.shown = null;
                    return;
                }
                player.hud.refresh();
            }
            if (player.current == current && !current.stopped && players.get(player.id) == player
                && player.refresh == null) {
                player.refresh = runtime.scheduleDashboardRefresh(() -> queue(player));
            }
        } catch (RuntimeException | LinkageError failure) {
            discard(player);
            try {
                removeInstalled(player, player.manager);
            } catch (RuntimeException | LinkageError removeFailure) {
                failure.addSuppressed(removeFailure);
            }
            player.hud = null;
            player.shown = null;
            log(failure);
        } finally {
            player.queued = false;
        }
    }

    private static boolean valid(PlayerHud player) {
        return player.ref.isValid() && player.ref.getStore() == player.store
            && player.requester.getReference() == player.ref;
    }

    private static boolean online(UUID id) {
        try {
            PlayerRef current = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(id);
            return current != null && current.isValid();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static void removeInstalled(PlayerHud player, HudManager huds) {
        if (player.hud == null) return;
        player.removing = true;
        try {
            if (huds != null && huds.getCustomHud(HyboxProgressHud.KEY) == player.hud) {
                huds.removeCustomHud(player.requester, HyboxProgressHud.KEY);
            }
        } finally {
            player.hud = null;
            player.shown = null;
            player.removing = false;
        }
    }

    private synchronized void removed(PlayerHud player, HyboxProgressHud hud) {
        if (player.removing || player.hud != hud) return;
        player.hud = null;
        player.shown = null;
        discard(player);
    }

    private void invalidate(UUID id, PlayerRef requester) {
        PlayerHud player;
        synchronized (this) {
            player = players.get(id);
            if (player == null || player.requester != requester) return;
            discard(player);
        }
        queue(player);
    }

    private void discard(PlayerHud player) {
        cancelRefresh(player);
        if (player.current != null) player.current.stopped = true;
        if (player.suspendedProfile != null) player.suspendedProfile.stopped = true;
        player.current = null;
        player.suspendedProfile = null;
        players.remove(player.id, player);
    }

    private static void cancelRefresh(PlayerHud player) {
        if (player.refresh == null) return;
        player.refresh.cancel(false);
        player.refresh = null;
    }

    private void log(Throwable failure) {
        logger.log(System.Logger.Level.WARNING, "Progress HUD unavailable. The capture continues.", failure);
    }
}
