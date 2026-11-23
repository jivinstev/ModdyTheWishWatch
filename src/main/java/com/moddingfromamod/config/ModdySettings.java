package com.moddingfromamod.config;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * <b>The public mod's settings, and who may change them</b> (D17, D18, D28). One place every setting is read
 * from and every change goes through. Three layers, first answer wins:
 *
 * <pre>   a private override (a plug-in)  &gt;  the dedicated server's file  &gt;  the host's own settings  &gt;  the default</pre>
 *
 * <ul>
 *   <li><b>Overrides</b> ({@link SettingsOverride}) are registered by a plug-in; every key one names is LOCKED.</li>
 *   <li><b>The server's file</b> ({@link ServerSettings}) applies only on a dedicated server, where nobody changes
 *       settings in game (D28).</li>
 *   <li><b>The host's own</b> ({@link PlayerSettings}) apply in single-player and on the LAN host's game; a guest
 *       wishes on them but never changes them (D18).</li>
 * </ul>
 *
 * <p><b>The lock is enforced HERE, in {@link #change}, which every action that changes a setting calls</b> (the
 * {@code /moddy setting} branch, and the Magician setup's menu actions) — never only by hiding a button, so a
 * stale menu, a typed command or a crafted click is refused the same way. A menu asks {@link #changeable} to decide
 * what to show; that is the second layer, not the first.
 *
 * <p>Pure apart from the shared instance's suppliers: no Minecraft, so the rules are unit-tested directly.
 */
public final class ModdySettings {

    /** Who is asking to change a setting. */
    public record Who(String name, boolean host, boolean dedicated) {
    }

    /** The answer to a change: done or refused, and the line the player is told either way. */
    public record Change(boolean done, String line) {
    }

    public static final String FROM_FILE = "This server's settings come from its configuration file.";
    public static final String HOST_ONLY = "Only the host changes Moddy's settings.";
    /** Magician turns on only for picks that passed the check. */
    public static final String NEEDS_CHECK =
            "Magician turns on when its check passes. Open the setup with /moddy magician.";
    /** Said when a pick changes while Magician is on. */
    public static final String RECHECK = " Magician is off until its check passes with the new pick.";

    private static volatile ModdySettings shared = new ModdySettings(
            () -> com.moddingfromamod.plugin.Plugins.shared().all(SettingsOverride.class),
            ServerSettings::value, ServerSettings::dedicated, PlayerSettings.shared());

    private final Supplier<List<SettingsOverride>> overrides;
    private final java.util.function.Function<String, Optional<String>> file;
    private final java.util.function.BooleanSupplier dedicated;
    private final PlayerSettings player;
    /** Did the current picks pass the Magician check? ({@link MagicianCheckRecord}; a unit test hands its own.) */
    private volatile java.util.function.Predicate<ModdySettings> checkPassed =
            s -> MagicianCheckRecord.shared().passedFor(MagicianPicks.read(s));
    /**
     * Run after a pick changed: a pool started for the old provider (Claude Code workers, Muse picks now) is
     * stopped, so no worker of the old program outlives the switch. A unit test hands its own.
     */
    private volatile Runnable afterPickChange = com.moddingfromamod.agent.AgentSession::stopIfProviderChanged;

    public ModdySettings(Supplier<List<SettingsOverride>> overrides,
                         java.util.function.Function<String, Optional<String>> file,
                         java.util.function.BooleanSupplier dedicated, PlayerSettings player) {
        this.overrides = overrides;
        this.file = file;
        this.dedicated = dedicated;
        this.player = player;
    }

    /** For a test: how this instance learns whether the current picks passed the check. Returns this. */
    public ModdySettings checkPassed(java.util.function.Predicate<ModdySettings> passed) {
        this.checkPassed = passed;
        return this;
    }

    /** The game's one instance. */
    public static ModdySettings shared() {
        return shared;
    }

    /** For a unit test: these overrides in place of the plug-ins' (null puts the plug-ins back). */
    public static void overridesForTest(List<SettingsOverride> list) {
        shared = new ModdySettings(list == null
                ? () -> com.moddingfromamod.plugin.Plugins.shared().all(SettingsOverride.class)
                : () -> list, ServerSettings::value, ServerSettings::dedicated, PlayerSettings.shared());
    }

    // ── reading ────────────────────────────────────────────────────────────────────────────────────────

    /** The setting's value from the first layer that sets it, else its catalogue default, else empty. */
    public Optional<String> value(String key) {
        boolean locked = false;
        for (SettingsOverride o : overrides.get()) {
            Optional<String> v = o.value(key);
            if (v.isPresent()) {
                return v;
            }
            locked |= o.locks(key);
        }
        if (dedicated.getAsBoolean()) {
            Optional<String> v = file.apply(key);
            if (v.isPresent()) {
                return v;
            }
        } else if (!locked && SettingsCatalog.find(key).map(Setting::playerChangeable).orElse(false)) {
            Optional<String> v = player.get(key);
            if (v.isPresent()) {
                return v;
            }
        }
        return SettingsCatalog.find(key).map(Setting::dflt);
    }

    public static Optional<String> get(String key) {
        return shared.value(key);
    }

    /** A number setting, or {@code fallback} when nothing sets it (or it is not a number). */
    public static long longOr(String key, long fallback) {
        try {
            return get(key).map(Long::parseLong).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static int intOr(String key, int fallback) {
        try {
            return get(key).map(Integer::parseInt).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Is {@code key} locked by an override? */
    public boolean locked(String key) {
        for (SettingsOverride o : overrides.get()) {
            if (o.locks(key)) {
                return true;
            }
        }
        return false;
    }

    // ── changing ───────────────────────────────────────────────────────────────────────────────────────

    /** The rule, without changing anything: would {@code who} be allowed to set {@code key} to {@code value}? */
    public Change decide(String key, String value, Who who) {
        Optional<Setting> s = SettingsCatalog.find(key);
        if (s.isEmpty()) {
            return new Change(false, "There is no setting called " + key + ".");
        }
        for (SettingsOverride o : overrides.get()) {
            if (o.locks(key)) {
                return new Change(false, o.refusal(key));
            }
        }
        if (who.dedicated() || dedicated.getAsBoolean()) {
            return new Change(false, FROM_FILE);
        }
        if (!who.host()) {
            return new Change(false, HOST_ONLY);
        }
        if (!s.get().playerChangeable()) {
            return new Change(false, "You can't change " + s.get().label() + ".");
        }
        if (!s.get().accepts(value)) {
            return new Change(false, "Pick one of: " + String.join(", ", s.get().choices()) + ".");
        }
        // Magician turns ON only for picks that passed the end-to-end check, whatever path asked (the setup's
        // own button asks here too, after recording the pass). Turning it OFF is always allowed.
        if (SettingsCatalog.MAGICIAN.equals(key) && "on".equals(value) && !checkPassed.test(this)) {
            return new Change(false, NEEDS_CHECK);
        }
        return new Change(true, "Set " + s.get().label() + " to " + value + ".");
    }

    /**
     * <b>THE action.</b> Change {@code key} to {@code value} for {@code who}, or refuse. Every path a player can
     * reach that changes a setting comes through here.
     */
    public Change change(String key, String value, Who who) {
        Change c = decide(key, value, who);
        if (c.done()) {
            boolean wasOn = "on".equals(value(SettingsCatalog.MAGICIAN).orElse("off"));
            boolean changed = !value.equals(value(key).orElse(null));
            player.set(key, value);
            if (changed && SettingsCatalog.PICKS.contains(key)) {
                afterPickChange.run();
            }
            // A new pick has not been checked: Magician goes off until the check passes again.
            if (changed && wasOn && SettingsCatalog.PICKS.contains(key) && !locked(SettingsCatalog.MAGICIAN)) {
                player.set(SettingsCatalog.MAGICIAN, "off");
                return new Change(true, c.line() + RECHECK);
            }
        }
        return c;
    }

    /** For a test: what runs after a pick changed (see {@link #afterPickChange}). Returns this. */
    public ModdySettings afterPickChange(Runnable r) {
        this.afterPickChange = r;
        return this;
    }

    /** May {@code who} change {@code key} at all? What a menu asks to decide whether to show it. */
    public boolean changeable(String key, Who who) {
        Optional<Setting> s = SettingsCatalog.find(key);
        return s.isPresent() && s.get().choices().stream().anyMatch(v -> decide(key, v, who).done());
    }
}
