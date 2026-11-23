package com.moddingfromamod.config;

import java.util.Optional;
import java.util.Set;

/**
 * <b>The private side's override</b> (D17): values our own servers set on top of the public mod, registered
 * by a plug-in and asked first. Every key an override names is LOCKED: no player may change
 * it, and the action that would change it refuses ({@link ModdySettings#change}).
 *
 * <p>{@code docs/public-repo/SERVER_CONFIG.md} names every key an override registers ({@code ServerConfigDocTest}).
 *
 * <p><b>A documented extension point</b>: {@code docs/EXTENDING.md} → "SettingsOverride". A server that runs
 * this mod locks its own settings this way.
 */
public interface SettingsOverride {

    /** Every key this override sets or locks. A key ending {@code .*} names every key with that prefix. */
    Set<String> keys();

    /** The value this override sets for {@code key}, or empty to leave the key to the layers below. */
    Optional<String> value(String key);

    /** Does this override lock {@code key}? Every key it names. */
    default boolean locks(String key) {
        if (key == null) {
            return false;
        }
        for (String k : keys()) {
            if (k.equals(key) || (k.endsWith(".*") && key.startsWith(k.substring(0, k.length() - 1)))) {
                return true;
            }
        }
        return false;
    }

    /** What a player who asks to change a locked {@code key} is told. */
    default String refusal(String key) {
        return "This server chooses " + SettingsCatalog.label(key) + ".";
    }
}
