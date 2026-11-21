package io.github.xytronix.hybox.core.env;

import java.util.regex.Pattern;

public final class PrivateArguments {
    private static final Pattern PRIVATE_ARGUMENT = Pattern.compile(
        "(?i)(?<![^\\s\"'])(--?(?:owner-name|owner-uuid|session-token|identity-token|bind|b|boot-command"
            + "|[\\w.-]*(?:token|secret|password|passwd|credential|api-?key)[\\w.-]*)"
            + "|-D[\\w.-]*(?:token|secret|password|passwd|credential|api-?key)[\\w.-]*)(=|\\s+)"
            + "(\"[^\"]*\"|'[^']*'|(?!-)[^\\s\"']+)");

    private PrivateArguments() {
    }

    public static String mask(String command) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        return PRIVATE_ARGUMENT.matcher(command).replaceAll("$1$2[REDACTED]");
    }
}
