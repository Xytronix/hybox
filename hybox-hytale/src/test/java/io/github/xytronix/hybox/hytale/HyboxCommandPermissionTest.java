package io.github.xytronix.hybox.hytale;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class HyboxCommandPermissionTest {
    private static final String ROOT_PERMISSION = "hytale.system.command.hybox";

    @Test
    void generatedPermissionsCoverTheEntireCommandTreeAndIsolateReloadAccess() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (Unsafe) field.get(null);
        var runtime = (HyboxRuntime) unsafe.allocateInstance(HyboxRuntime.class);
        var command = new HyboxCommand(runtime);
        command.setOwner((CommandManager) unsafe.allocateInstance(CommandManager.class));

        assertEquals(ROOT_PERMISSION, command.getPermission());
        Set<String> permissions = new HashSet<>();
        assertPermissionsRecursively(command, ROOT_PERMISSION, permissions);
        assertEquals(Set.of(
            ROOT_PERMISSION,
            ROOT_PERMISSION + ".dump",
            ROOT_PERMISSION + ".status",
            ROOT_PERMISSION + ".list",
            ROOT_PERMISSION + ".triggers",
            ROOT_PERMISSION + ".histogram",
            ROOT_PERMISSION + ".profile",
            ROOT_PERMISSION + ".profile.stop",
            ROOT_PERMISSION + ".profile.cancel",
            ROOT_PERMISSION + ".trend",
            ROOT_PERMISSION + ".reload",
            ROOT_PERMISSION + ".settings",
            ROOT_PERMISSION + ".ui",
            ROOT_PERMISSION + ".incident"
        ), permissions);
        assertEquals(1, command.getSubCommand("incident").getVariantCommands().size());
        assertEquals(ROOT_PERMISSION + ".profile.stop",
            command.getSubCommand("profile").getSubCommand("stop").getPermission());
        assertEquals(ROOT_PERMISSION + ".profile.cancel",
            command.getSubCommand("profile").getSubCommand("cancel").getPermission());

        CommandSender reloadOnly = new Sender(Set.of(ROOT_PERMISSION, ROOT_PERMISSION + ".reload"));
        assertTrue(command.hasPermission(reloadOnly));
        assertTrue(command.getSubCommand("reload").hasPermission(reloadOnly));
        for (String name : Set.of("settings", "dump", "ui", "incident", "trend")) {
            assertFalse(command.getSubCommand(name).hasPermission(reloadOnly), name);
        }
        assertFalse(command.getSubCommand("incident").getVariantCommands().iterator().next().hasPermission(reloadOnly));
        assertFalse(command.getSubCommand("reload").hasPermission(new Sender(Set.of(ROOT_PERMISSION + ".reload"))));
        CommandSender profileOnly = new Sender(Set.of(ROOT_PERMISSION, ROOT_PERMISSION + ".profile"));
        assertTrue(command.getSubCommand("profile").hasPermission(profileOnly));
        assertFalse(command.getSubCommand("profile").getSubCommand("stop").hasPermission(profileOnly));
        assertFalse(command.getSubCommand("profile").getSubCommand("cancel").hasPermission(profileOnly));
        for (String action : Set.of("stop", "cancel")) {
            CommandSender permitted = new Sender(Set.of(ROOT_PERMISSION, ROOT_PERMISSION + ".profile",
                ROOT_PERMISSION + ".profile." + action));
            assertTrue(command.getSubCommand("profile").getSubCommand(action).hasPermission(permitted));
            assertFalse(command.getSubCommand("profile").getSubCommand(action.equals("stop") ? "cancel" : "stop")
                .hasPermission(permitted));
        }
    }

    @Test
    void trendsRequireTheirOwnPermissionWithoutGrantingOtherPages() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (Unsafe) field.get(null);
        var command = new HyboxCommand((HyboxRuntime) unsafe.allocateInstance(HyboxRuntime.class));
        command.setOwner((CommandManager) unsafe.allocateInstance(CommandManager.class));

        var trend = command.getSubCommand("trend");
        assertFalse(trend.hasPermission(new Sender(Set.of(ROOT_PERMISSION))));
        assertFalse(trend.hasPermission(new Sender(Set.of(ROOT_PERMISSION + ".trend"))));
        assertFalse(trend.hasPermission(new Sender(Set.of(ROOT_PERMISSION, ROOT_PERMISSION + ".ui"))));
        CommandSender trendOnly = new Sender(Set.of(ROOT_PERMISSION, ROOT_PERMISSION + ".trend"));
        assertTrue(trend.hasPermission(trendOnly));
        for (String page : Set.of("ui", "settings", "incident")) {
            assertFalse(command.getSubCommand(page).hasPermission(trendOnly), page);
        }
        CommandSender dashboardAndTrend = new Sender(Set.of(ROOT_PERMISSION, ROOT_PERMISSION + ".ui", ROOT_PERMISSION + ".trend"));
        assertTrue(command.getSubCommand("ui").hasPermission(dashboardAndTrend));
        assertTrue(trend.hasPermission(dashboardAndTrend));
        assertFalse(command.getSubCommand("settings").hasPermission(dashboardAndTrend));
    }

    @Test
    void pageEntryCommandsRetainTheRootSoEveryPageResolvesTheSamePermissionTree() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (Unsafe) field.get(null);
        var command = new HyboxCommand((HyboxRuntime) unsafe.allocateInstance(HyboxRuntime.class));
        for (String name : List.of("trend", "settings", "ui")) {
            var pageCommand = command.getSubCommand(name);
            var root = pageCommand.getClass().getDeclaredField("rootCommand");
            root.setAccessible(true);
            assertSame(command, root.get(pageCommand), name);
        }
        for (String name : List.of("dump", "profile", "settings", "incident", "trend", "ui", "histogram")) {
            assertNotNull(command.getSubCommand(name), name);
        }
    }

    @Test
    void commandTextsNeverMentionAReport() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (Unsafe) field.get(null);
        var command = new HyboxCommand((HyboxRuntime) unsafe.allocateInstance(HyboxRuntime.class));
        List<String> texts = new java.util.ArrayList<>(HyboxCommand.usage());
        collectDescriptions(command, texts);
        assertTrue(texts.size() > 12, texts.toString());
        for (String text : texts) assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("report"), text);
    }

    private static void collectDescriptions(AbstractCommand command, List<String> texts) {
        if (command.getDescription() != null) texts.add(command.getDescription());
        for (AbstractCommand child : command.getSubCommands().values()) collectDescriptions(child, texts);
        for (AbstractCommand variant : command.getVariantCommands()) collectDescriptions(variant, texts);
    }

    private static void assertPermissionsRecursively(AbstractCommand command, String expected, Set<String> permissions) {
        assertNotNull(command.getPermission(), command.getFullyQualifiedName());
        assertEquals(expected, command.getPermission(), command.getFullyQualifiedName());
        permissions.add(command.getPermission());
        for (AbstractCommand child : command.getSubCommands().values()) {
            assertPermissionsRecursively(child, expected + "." + child.getName(), permissions);
        }
        for (AbstractCommand variant : command.getVariantCommands()) {
            assertPermissionsRecursively(variant, expected, permissions);
        }
    }

    private record Sender(Set<String> permissions) implements CommandSender {
        @Override
        public String getUsername() {
            return "permission-test";
        }

        @Override
        public UUID getUuid() {
            return new UUID(0, 1);
        }

        @Override
        public boolean hasPermission(String permission) {
            return permissions.contains(permission);
        }

        @Override
        public boolean hasPermission(String permission, boolean defaultValue) {
            return hasPermission(permission);
        }

        @Override
        public void sendMessage(Message message) {
            fail("Unexpected chat message: " + message);
        }
    }
}
