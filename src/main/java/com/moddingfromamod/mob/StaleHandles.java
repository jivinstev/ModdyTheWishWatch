package com.moddingfromamod.mob;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;

/**
 * Forget every {@link EntityHandles} handle for one species, so a Gate B test does not inherit a
 * referent that belongs to somebody else's animal.
 *
 * <h2>The failure this exists for </h2>
 *
 * <p>{@code EntityHandles.global()} is a <b>process-wide singleton</b>, and 465 GameTests share one
 * process — {@code defaultBatch} alone runs 50 at a time. Two of its properties are deliberate and
 * correct in the game, and lethal in combination across concurrent tests:
 *
 * <ul>
 *   <li>a handle name is <b>sticky for the whole run</b> — {@code anExistingHandleIsNeverSilentlyRepointed}
 *       pins that, because quietly re-pointing {@code @cow} at a different cow is the exact bug the
 *       whole referent mechanism exists to prevent;</li>
 *   <li>nothing forgets a handle when its entity dies. {@link EntityHandles#forget(java.util.UUID)}
 *       exists but no death or discard listener calls it.</li>
 * </ul>
 *
 * <p>So once any test has <i>targeted</i> a cow through {@code modify_mob}, that cow's handle stays
 * in {@link EntityHandles#targetedRecently(int)} forever. When a later test spawns its own cow and
 * asks {@code modify_mob} to change "cow", {@code useTheOneHeIsTalkingAbout} finds the established
 * handle first, looks for that <i>other</i> animal, cannot find it, and correctly answers:
 *
 * <pre>The cow (@cow) he has been talking about is GONE — it died, or it is somewhere nobody is.</pre>
 *
 * <p>That is the product behaving <b>exactly as designed</b> — refusing to grow a different cow and
 * report success. The defect is entirely in the tests: they shared a referent they never meant to
 * share. It is intermittent because it depends on whether some other test targeted that species
 * first, which depends on batch interleaving — measured at 1 failing run in 4.
 *
 * <h2>Why species-scoped rather than {@link EntityHandles#clear()}</h2>
 *
 * <p>Clearing the whole registry on entry would commit the same crime in the other direction: it
 * would wipe the handles of every test running concurrently. Forgetting only the species this test
 * is about to use mirrors the discipline {@code MobIdentityGameTest} already keeps for the mobs
 * themselves — "each test uses a different species" — and extends it to the handle namespace, which
 * is the half that was never covered.
 */
public final class StaleHandles {

    private StaleHandles() {
    }

    /** Forget every handle pointing at a mob of this species, alive or dead. */
    public static void forgetEvery(EntityType<?> type) {
        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
        EntityHandles handles = EntityHandles.global();
        for (EntityHandles.Handle h : handles.recent(EntityHandles.MAX_HANDLES)) {
            if (typeId.equals(h.typeId())) {
                handles.forget(h.id());
            }
        }
    }
}
