package com.moddingfromamod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * an earlier issue's rules, driven through {@link ModdySettings#change} — the action itself, which every path a player can
 * reach calls — never through a menu.
 */
class ModdySettingsTest {

    @TempDir
    Path dir;

    private static final ModdySettings.Who HOST = new ModdySettings.Who("host", true, false);
    private static final ModdySettings.Who GUEST = new ModdySettings.Who("guest", false, false);
    private static final ModdySettings.Who ON_A_SERVER = new ModdySettings.Who("op", true, true);

    /** A server's settings override (a plug-in): the model and Magician set, everything it names locked. */
    private static SettingsOverride overridden() {
        return new SettingsOverride() {
            @Override
            public Set<String> keys() {
                return Set.of(SettingsCatalog.MODEL, SettingsCatalog.MAGICIAN, "spawn.*");
            }

            @Override
            public Optional<String> value(String key) {
                return Optional.ofNullable(Map.of(SettingsCatalog.MAGICIAN, "on").get(key));
            }
        };
    }

    private ModdySettings settings(List<SettingsOverride> overrides, Map<String, String> file, boolean dedicated) {
        return new ModdySettings(() -> overrides, k -> Optional.ofNullable(file.get(k)), () -> dedicated,
                new PlayerSettings(dir.resolve("settings.json")));
    }

    @Test
    void withNoOverrideTheHostChangesASettingAndItSticks() {
        ModdySettings s = settings(List.of(), Map.of(), false);
        assertEquals(Optional.of("off"), s.value(SettingsCatalog.MAGICIAN), "Magician is off by default");
        ModdySettings.Change c = s.change(SettingsCatalog.MODEL, "opus", HOST);
        assertTrue(c.done(), c.line());
        assertEquals(Optional.of("opus"), s.value(SettingsCatalog.MODEL));
        // And it is on disk: a new instance over the same file reads it back.
        assertEquals(Optional.of("opus"), settings(List.of(), Map.of(), false).value(SettingsCatalog.MODEL));
    }

    @Test
    void aLockedSettingIsRefusedByTheActionEvenForTheHost() {
        ModdySettings s = settings(List.of(overridden()), Map.of(), false);
        ModdySettings.Change c = s.change(SettingsCatalog.MODEL, "opus", HOST);
        assertFalse(c.done());
        assertEquals("This server chooses the model.", c.line());
        assertTrue(s.value(SettingsCatalog.MODEL).isEmpty(), "a refused change must not be stored");
        assertFalse(s.changeable(SettingsCatalog.MODEL, HOST), "a menu would offer a locked setting");
        assertTrue(s.locked("spawn.sculpted.world"), "a .* key locks its prefix");
    }

    @Test
    void aLockedSettingIgnoresAValueThePlayerSavedBefore() {
        settings(List.of(), Map.of(), false).change(SettingsCatalog.MODEL, "opus", HOST);
        ModdySettings s = settings(List.of(overridden()), Map.of(), false);
        assertTrue(s.value(SettingsCatalog.MODEL).isEmpty(), "a lock must not let an old saved value through");
        assertEquals(Optional.of("on"), s.value(SettingsCatalog.MAGICIAN), "the override wins");
    }

    @Test
    void aLanGuestWishesOnTheHostsSettingsButNeverChangesThem() {
        ModdySettings s = settings(List.of(), Map.of(), false);
        ModdySettings.Change c = s.change(SettingsCatalog.MAGICIAN, "on", GUEST);
        assertFalse(c.done());
        assertEquals(ModdySettings.HOST_ONLY, c.line());
        assertEquals(Optional.of("off"), s.value(SettingsCatalog.MAGICIAN));
    }

    @Test
    void onADedicatedServerEveryChangeIsRefusedAndTheFileRules() {
        ModdySettings s = settings(List.of(), Map.of(SettingsCatalog.MAGICIAN, "off"), true);
        ModdySettings.Change c = s.change(SettingsCatalog.MAGICIAN, "on", ON_A_SERVER);
        assertFalse(c.done());
        assertEquals(ModdySettings.FROM_FILE, c.line());
        assertEquals(Optional.of("off"), s.value(SettingsCatalog.MAGICIAN));
        // Even with a host flag the request does not carry: the server is dedicated.
        assertFalse(s.change(SettingsCatalog.MODEL, "opus", HOST).done());
    }

    @Test
    void theOverrideBeatsTheServersFile() {
        ModdySettings s = settings(List.of(overridden()), Map.of(SettingsCatalog.MAGICIAN, "off"), true);
        assertEquals(Optional.of("on"), s.value(SettingsCatalog.MAGICIAN));
    }

    @Test
    void safetyAndEffortAreNotAPlayersAndBadValuesAreRefused() {
        ModdySettings s = settings(List.of(), Map.of(), false);
        assertFalse(s.change(SettingsCatalog.SAFETY, "off", HOST).done());
        assertFalse(s.change(SettingsCatalog.SAFETY, "on", HOST).done());
        assertFalse(s.change(SettingsCatalog.EFFORT, "low", HOST).done());
        assertFalse(s.change(SettingsCatalog.MAGICIAN, "maybe", HOST).done());
        assertFalse(s.change("no.such.setting", "on", HOST).done());
        assertTrue(s.value(SettingsCatalog.SAFETY).isEmpty());
    }
}
