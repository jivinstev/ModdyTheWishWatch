package com.moddingfromamod.mob;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <b>What replaced what — so a handle names the body that is really there.</b>
 *
 * <p>A wished creature is manifested <b>twice</b>, and neither hop used to be followed:
 *
 * <ol>
 *   <li>it joins the world as a {@code wish_mob}, and {@code VanillaRigBridge} cancels it and
 *       spawns a display rig on a vanilla carrier;</li>
 *   <li>every re-wish rebuilds that rig, discarding the carrier and spawning a new one — a rig's
 *       geometry is baked, so it cannot be re-applied in place.</li>
 * </ol>
 *
 * <p>The agent's own tools were among the losers. {@code DefineSpeciesTool} spawns a creature and
 * hands back a handle with the words <i>"use it as modify_mob's target"</i>; the next edit was
 * answered <i>"… is gone — it died or unloaded"</i>, which is true and useless.
 *
 * <p>⚠ <b>Why this is a separate class rather than a field on the bridge.</b>
 * {@link EntityHandles} states that it must not import Minecraft — <i>"that purity is what lets
 * every naming, eviction and referent rule below be a unit test"</i> — and it is the thing that has
 * to consult this. A register living on the bridge would have made the pure class depend on a
 * Minecraft one to answer a question that is nothing but two UUIDs. So the register is pure, the
 * bridge WRITES to it, and the purity invariant survives the fix.
 *
 * <p>⚠ <b>And why this is needed at all when {@link EntityHandles#repoint} exists.</b> Repointing
 * moves a handle that already names the old entity; this answers whoever asks afterwards. Which one
 * fires depends on whether the caller minted before or after the swap — and MEASURED over a whole
 * Gate B tier, <b>41 conversions moved zero handles</b>, because {@code MinecraftServer.execute}
 * drains before the caller's next line. Every real caller is on the far side. Shipping only the
 * tidier half would have shipped the half that never runs.
 *
 * <p>Bounded and oldest-out: it exists to survive a tool call, not a session. A creature converted
 * two hundred and fifty creatures ago is not one anybody still holds a stale id for.
 */
public final class EntityReplacements {

    private EntityReplacements() {
    }

    /** How many replacements are remembered before the oldest is dropped. */
    public static final int CAPACITY = 256;

    /** How far a chain is followed before it is treated as a cycle and abandoned. */
    public static final int MAX_HOPS = 8;

    private static final Map<UUID, UUID> REPLACED = Collections.synchronizedMap(
            new LinkedHashMap<>(CAPACITY, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, UUID> eldest) {
                    return size() > CAPACITY;
                }
            });

    /** {@code was} has stopped existing and {@code now} stands where it did. */
    public static void record(UUID was, UUID now) {
        if (was != null && now != null && !was.equals(now)) {
            REPLACED.put(was, now);
        }
    }

    /**
     * The entity that stands where this one used to, following every hop.
     *
     * @return {@code id} itself when nothing replaced it — so a caller never has to ask whether it
     *         is talking about a wished creature
     */
    public static UUID settled(UUID id) {
        UUID at = id;
        for (int hops = 0; hops < MAX_HOPS; hops++) {
            UUID next = at == null ? null : REPLACED.get(at);
            if (next == null || next.equals(at)) {
                return at;
            }
            at = next;
        }
        // A cycle, which nothing should be able to produce. Answering the last link is better than
        // looping, and better than throwing on a path that only wants to name a creature.
        return at;
    }

    /** Test-only: forget everything, so one test cannot read another's conversions. */
    public static void clearForTest() {
        REPLACED.clear();
    }

    /** Test-only: how many replacements are remembered right now. */
    public static int sizeForTest() {
        return REPLACED.size();
    }
}
