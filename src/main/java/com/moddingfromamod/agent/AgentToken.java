package com.moddingfromamod.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared secret between the game and Claude.
 *
 * <p>Without it, <em>any</em> local process could drive commands inside a child's game through an
 * unauthenticated port. A static token in a 0600 file is proportionate for a family machine: ten
 * lines, and it closes that hole completely.
 *
 * <p>Persisted (not regenerated per boot) so the Claude config stays valid across restarts.
 */
public final class AgentToken {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);
    private static final String PREFIX = "Bearer ";

    private static volatile String token;

    private AgentToken() {
    }

    /**
     * Under the <b>configured</b> home, which {@link com.moddingfromamod.runtime.RuntimeConfig}
     * already documents as "shared with the agent token" — it simply was not, until the home became relocatable. A second
     * Moddy on one machine, given its own {@code -Dmoddingfromamod.home}, shares this file's
     * neighbours and used to share its secret too.
     */
    public static Path tokenFile() {
        return com.moddingfromamod.runtime.RuntimeConfig.home().resolve("token");
    }

    /** Load the token, creating one on first run. */
    public static synchronized String token() {
        if (token != null) {
            return token;
        }
        Path file = tokenFile();
        try {
            if (Files.isRegularFile(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty()) {
                    token = existing;
                    return token;
                }
            }
            String fresh = UUID.randomUUID().toString().replace("-", "");
            Files.createDirectories(file.getParent());
            Files.writeString(file, fresh, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(file,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Non-POSIX filesystem — the token still works, just without 0600.
            }
            LOGGER.info("Created a wish-agent token at {}", file);
            token = fresh;
        } catch (IOException e) {
            // Fall back to an in-memory token rather than running unauthenticated.
            LOGGER.warn("Could not persist the wish-agent token ({}); using a temporary one", e.toString());
            token = UUID.randomUUID().toString().replace("-", "");
        }
        return token;
    }

    /** Forget the loaded token so the next {@link #token()} reads (or creates) the file again — tests only. */
    static synchronized void forgetForTest() {
        token = null;
    }

    /** Constant-time-ish check of an {@code Authorization: Bearer ...} header. */
    public static boolean isAuthorised(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
            return false;
        }
        String presented = authorizationHeader.substring(PREFIX.length()).trim();
        String expected = token();
        if (presented.length() != expected.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= presented.charAt(i) ^ expected.charAt(i);
        }
        return diff == 0;
    }
}
