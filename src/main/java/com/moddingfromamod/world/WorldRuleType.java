package com.moddingfromamod.world;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

/**
 * <b>A world-rule type a plug-in adds to the rule library</b>. The built-in types are {@link WorldRule.Type};
 * a plug-in registers one of these with {@code plugins.add(WorldRuleType.class, …)} and a rules file may then carry
 * a rule whose {@code type} is this {@link #key()}. The core checks the entry like any other (id, no unknown keys, no
 * effects, no message), hands its {@code params} to {@link #params} to check, and stores the result as a
 * {@link WorldRule} of type {@link WorldRule.Type#PLUGIN}. The plug-in reads its own rule back with
 * {@link WorldRules#first(String)}; the core never knows what the rule means.
 *
 * <p>A type nobody registered stays a refused file ({@code type must be one of …}), never a skipped rule: a world
 * whose rules cannot all be read falls back to the closed rules.
 *
 * <p><b>A documented extension point</b>: {@code docs/EXTENDING.md} → "WorldRuleType".
 */
public interface WorldRuleType {

    /**
     * The file's spelling ({@code "type": "lighthouse_hours"}): {@code [a-z][a-z0-9_]*}, and not a built-in type's
     * key. A registration with a bad or taken key is ignored and named in every parse's error list, so it cannot
     * pass unnoticed.
     */
    String key();

    /**
     * Check the rule's {@code params} and return them as plain values (numbers, strings, booleans, lists and maps of
     * those); the result is what {@link WorldRule#params()} holds. Say what is wrong by adding lines to {@code err},
     * each starting with {@code where}; any line refuses the whole file. {@code raw} is never null (an entry with no
     * {@code params} gives an empty object). Called wherever a rules file is parsed, from any thread: keep it pure.
     */
    Map<String, Object> params(JsonObject raw, String where, List<String> err);

    /** May a world carry only one rule of this type? True by default, as for the built-in parameter types. */
    default boolean single() {
        return true;
    }
}
