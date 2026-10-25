package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class HyboxTrendPageTest {
    private static final String ROOT = "hytale.system.command.hybox";

    @Test
    void rangeAndExportRequireTrendPermissionEvenWhenThePageWasAlreadyOpened() throws Exception {
        AbstractCommand command = command();
        CommandSender denied = new Sender(Set.of(ROOT, ROOT + ".ui", ROOT + ".settings"));
        CommandSender allowed = new Sender(Set.of(ROOT, ROOT + ".trend"));
        for (String action : Set.of("range", "save")) {
            assertEquals("Trends require permission to use /hybox trend.", denial(command, denied, action));
            assertNull(denial(command, allowed, action));
        }
        assertEquals("Trends require permission to use /hybox trend.", denial(command, new Sender(Set.of(ROOT + ".trend")), "save"));
    }

    @Test
    void settingsAndDashboardBackCheckTheirOwnPermissionsRatherThanTrendAccess() throws Exception {
        AbstractCommand command = command();
        CommandSender trendOnly = new Sender(Set.of(ROOT, ROOT + ".trend"));
        assertEquals("Settings require permission to use /hybox settings.", denial(command, trendOnly, "settings"));
        assertEquals("The dashboard requires permission to use /hybox ui.", denial(command, trendOnly, "back"));
        assertNull(denial(command, new Sender(Set.of(ROOT, ROOT + ".settings")), "settings"));
        assertNull(denial(command, new Sender(Set.of(ROOT, ROOT + ".ui")), "back"));
        assertNull(denial(command, new Sender(Set.of()), "close"));
    }

    private static String denial(AbstractCommand root, CommandSender sender, String action) {
        return HyboxTrendPage.denial(action, sender, root.getSubCommand("trend"), root.getSubCommand("settings"), root.getSubCommand("ui"));
    }

    private static AbstractCommand command() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (Unsafe) field.get(null);
        var command = new HyboxCommand((HyboxRuntime) unsafe.allocateInstance(HyboxRuntime.class));
        command.setOwner((CommandManager) unsafe.allocateInstance(CommandManager.class));
        return command;
    }

    private record Sender(Set<String> permissions) implements CommandSender {
        @Override public String getUsername() { return "trend-permission-test"; }
        @Override public UUID getUuid() { return new UUID(0, 3); }
        @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
        @Override public boolean hasPermission(String permission, boolean defaultValue) { return hasPermission(permission); }
        @Override public void sendMessage(Message message) { fail("Unexpected chat message: " + message); }
    }
}
