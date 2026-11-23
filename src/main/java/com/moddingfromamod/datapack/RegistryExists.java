package com.moddingfromamod.datapack;

/**
 * <b>Does this id name a thing the game actually has?</b>
 *
 * <p>The one question about an item reference that a pure class cannot answer, split out so that it
 * can be <b>injected rather than skipped</b>. {@link ItemBase}'s own note has said for a while that
 * existence <i>"needs the live registry… asked in {@code ItemPropertiesGameTest}, where a real
 * registry exists"</i> — but nothing on the {@code make_item} path ever asked it, so
 * {@code minecraft:cobblestne} rendered a {@code /give} that failed with no error anywhere.
 *
 * <p><b>Why a parameter and not a static call into {@code BuiltInRegistries}.</b> The obvious
 * version wraps the registry lookup in a try/catch and treats "no registry here" as a pass, so the
 * check evaporates in every unit test while still reading as present — the exact shape of failure
 * this repo keeps recording (a gate that skip-succeeds is worse than no gate). Making it an
 * argument means a caller with no registry must say {@link #ALWAYS} out loud, and the one caller
 * that has a registry is the one that passes the real thing.
 */
@FunctionalInterface
public interface RegistryExists {

    /** @param id a well-formed {@code namespace:path} */
    boolean has(String id);

    /**
     * "Assume everything exists" — for callers with no registry, and for unit tests.
     *
     * <p>Named rather than defaulted so that a test asserting on a typo has to state that it is
     * <b>not</b> testing existence, instead of quietly passing because nothing was checked.
     */
    RegistryExists ALWAYS = id -> true;
}
