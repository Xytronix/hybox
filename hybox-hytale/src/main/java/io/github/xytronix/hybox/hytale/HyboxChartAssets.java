package io.github.xytronix.hybox.hytale;

import com.hypixel.hytale.protocol.Asset;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.setup.AssetFinalize;
import com.hypixel.hytale.protocol.packets.setup.AssetInitialize;
import com.hypixel.hytale.protocol.packets.setup.AssetPart;
import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

final class HyboxChartAssets implements HyboxIncidentRenderer.ChartImages {
    static final String DIRECTORY = "UI/Custom/Hybox/Charts/";
    private static final int PART = 2_621_440;

    interface Delivery {
        void work(Runnable task);
        void send(ToClientPacket[] packets);
        void update(Runnable onWorldThread);
        void update(UICommandBuilder commands);
    }

    private final Delivery delivery;
    private final Consumer<Throwable> failed;
    private final Set<String> sent = new HashSet<>();
    private final List<HyboxIncidentRenderer.ChartRequest> pending = new ArrayList<>();
    private volatile long generation;
    private volatile boolean disabled;

    HyboxChartAssets(Delivery delivery, Consumer<Throwable> failed) {
        this.delivery = delivery;
        this.failed = failed;
    }

    void begin() {
        generation++;
        pending.clear();
    }

    @Override
    public void request(HyboxIncidentRenderer.ChartRequest request) {
        if (!disabled) pending.add(request);
    }

    void flush() {
        if (disabled || pending.isEmpty()) return;
        long expected = generation;
        List<HyboxIncidentRenderer.ChartRequest> charts = List.copyOf(pending);
        pending.clear();
        try {
            delivery.work(() -> deliver(expected, charts));
        } catch (RuntimeException failure) {
            fail(failure);
        }
    }

    private void deliver(long expected, List<HyboxIncidentRenderer.ChartRequest> charts) {
        try {
            UICommandBuilder commands = new UICommandBuilder();
            List<ToClientPacket> packets = new ArrayList<>();
            Set<String> fresh = new HashSet<>();
            for (HyboxIncidentRenderer.ChartRequest chart : charts) {
                if (expected != generation || disabled) return;
                byte[] png = chart.draw().get();
                String hash = sha256(png);
                String name = DIRECTORY + hash + ".png";
                if (!sent.contains(hash) && fresh.add(hash)) asset(new Asset(hash, name), png, packets);
                chart.apply().accept(name, commands);
            }
            if (expected != generation || disabled) return;
            if (!packets.isEmpty()) {
                packets.add(new RequestCommonAssetsRebuild());
                delivery.send(packets.toArray(ToClientPacket[]::new));
                sent.addAll(fresh);
            }
            delivery.update(() -> {
                if (expected == generation && !disabled) delivery.update(commands);
            });
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
        }
    }

    private void fail(Throwable failure) {
        if (disabled) return;
        disabled = true;
        failed.accept(failure);
    }

    static void asset(Asset asset, byte[] blob, List<ToClientPacket> packets) {
        packets.add(new AssetInitialize(asset, blob.length));
        for (int offset = 0; offset < blob.length; offset += PART) {
            byte[] part = new byte[Math.min(PART, blob.length - offset)];
            System.arraycopy(blob, offset, part, 0, part.length);
            packets.add(new AssetPart(part));
        }
        packets.add(new AssetFinalize());
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}