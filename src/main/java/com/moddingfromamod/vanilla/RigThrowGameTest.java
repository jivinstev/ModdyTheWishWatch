package com.moddingfromamod.vanilla;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * <b>A thrown ball is SEEN leaving the creature, at the shortest range it throws at</b>.
 *
 * <p>Reported: the fire buddy "throws fireballs", but with the monster close you never
 * see one thrown. Measured on the old code: the ball appeared at the invisible carrier's eye, inside
 * the drawn body and far from the hand holding the fireball, and reached a monster 6 blocks off in
 * a handful of ticks, much of them inside the body. These hold the CLASS, not the fire buddy:
 * <ul>
 *   <li>the fire buddy's own sculpt: the ball leaves from the fireball in its hand, and spends at
 *       least {@value #MIN_SEEN} ticks in the air outside its body before it lands — at 6, 7 and
 *       8.5 blocks (the ranged band starts at 6);</li>
 *   <li>a body with no hand at all (the corpus Spikor): the same, from wherever it throws;</li>
 *   <li>a fireball BURNS in flight ({@link ThrowLook}), or every client draws the fire charge's
 *       dark sprite: a black ball.</li>
 * </ul>
 * The oracle is the ball's own path, tick by tick, against the boxes the rig draws.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class RigThrowGameTest {

    /** Ticks in the air, outside the thrower's drawn body, that a throw must last to be seen. */
    static final int MIN_SEEN = 6;
    /** How far the ball may start from the held fireball's middle, in blocks. */
    static final double HAND_SLACK = 0.35;

    private static final Map<UUID, Vec3> SPAWNED = new ConcurrentHashMap<>();
    /**
     * Where the held fireball was drawn at that SAME moment. Read a tick later it had moved: the
     * creature turns as it throws and is held back on its spot, so the hand read late was up to
     * 0.57 blocks from where the ball had left it (a flake in Gate B, 2026-11-21).
     */
    private static final Map<UUID, Vec3> HAND_AT_SPAWN = new ConcurrentHashMap<>();
    private static volatile boolean listening;

    /** Where every rig throw entered the world — the spawn point, before it ever moved. */
    private static void listen() {
        if (listening) {
            return;
        }
        listening = true;
        NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent e) -> {
            if (!e.getLevel().isClientSide() && e.getEntity().getTags().contains(RigAbilities.THROW_TAG)) {
                SPAWNED.put(e.getEntity().getUUID(), e.getEntity().position());
                if (e.getEntity() instanceof Snowball b && b.getOwner() instanceof Mob owner) {
                    Vec3 hand = drawnCentre(owner, "fireball");
                    if (hand != null) {
                        HAND_AT_SPAWN.put(b.getUUID(), hand);
                    }
                }
            }
        });
    }

    @GameTest(template = "empty_test", batch = "rig_throw_6", timeoutTicks = 400)
    public static void theFireBuddysThrowIsSeenAtSixBlocks(GameTestHelper helper) {
        run(helper, "throw_fire_6", true, SpeciesOnRigGameTest.SPOT.offset(6, 0, 0));
    }

    @GameTest(template = "empty_test", batch = "rig_throw_7", timeoutTicks = 400)
    public static void theFireBuddysThrowIsSeenAtSevenBlocks(GameTestHelper helper) {
        run(helper, "throw_fire_7", true, SpeciesOnRigGameTest.SPOT.offset(6, 0, 3));
    }

    @GameTest(template = "empty_test", batch = "rig_throw_8", timeoutTicks = 400)
    public static void theFireBuddysThrowIsSeenAtEightAndAHalfBlocks(GameTestHelper helper) {
        run(helper, "throw_fire_8", true, SpeciesOnRigGameTest.SPOT.offset(6, 0, 6));
    }

    @GameTest(template = "empty_test", batch = "rig_throw_nohand", timeoutTicks = 400)
    public static void aThrowFromABodyWithNoHandIsSeenToo(GameTestHelper helper) {
        run(helper, "throw_nohand_6", false, SpeciesOnRigGameTest.SPOT.offset(6, 0, 0));
    }

    private static void run(GameTestHelper helper, String id, boolean fireBuddy, BlockPos huskAt) {
        listen();
        String extra = "{\"abilities\":[\"ranged\"],\"damage\":6,\"powers\":[\"friendly\",\"persistent\"],"
                + "\"throws\":\"minecraft:fire_charge\"" + (fireBuddy ? ",\"sculpt\":\"starter:fire_imp\"" : "") + "}";
        String made = SpeciesOnRigGameTest.plant(helper, id, extra);
        SpeciesOnRigGameTest.wish(helper, made);
        Husk husk = helper.spawn(EntityType.HUSK, huskAt);
        husk.setPersistenceRequired();
        husk.setNoAi(true);
        BlockPos at = helper.absolutePos(SpeciesOnRigGameTest.SPOT);
        UUID[] ball = {null};
        Vec3[] hand = {null};
        Vec3[] feet = {null};
        double[] distance = {0};
        int[] seen = {0};
        int[] hidden = {0};
        boolean[] done = {false};
        boolean[] burning = {false};
        helper.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            Mob c = SpeciesOnRigGameTest.carrier(helper, made);
            if (c == null) {
                return;
            }
            if (ball[0] == null) {
                // Keep it on its spot until it throws: the throw is the subject, not a stroll.
                if (c.onGround() && Math.hypot(c.getX() - at.getX() - 0.5, c.getZ() - at.getZ() - 0.5) > 0.5) {
                    c.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                }
                for (Snowball b : helper.getLevel().getEntitiesOfClass(Snowball.class, c.getBoundingBox().inflate(6),
                        b -> b.getOwner() == c && b.getTags().contains(RigAbilities.THROW_TAG))) {
                    ball[0] = b.getUUID();
                    distance[0] = c.distanceTo(husk);
                    feet[0] = c.position();
                    if (fireBuddy) {
                        hand[0] = HAND_AT_SPAWN.get(b.getUUID());
                    }
                }
                if (ball[0] == null) {
                    return;
                }
            }
            var e = helper.getLevel().getEntity(ball[0]);
            if (e == null || e.isRemoved()) {
                done[0] = true;
                Vec3 spawn = SPAWNED.get(ball[0]);
                com.mojang.logging.LogUtils.getLogger().info("[rig-throw] {}: {} blocks to the husk, left {} from the "
                        + "held fireball, in sight {} ticks, inside its body {} ticks", made,
                        String.format("%.1f", distance[0]),
                        spawn == null || hand[0] == null ? "-" : String.format("%.2f", spawn.distanceTo(hand[0])),
                        seen[0], hidden[0]);
                StringBuilder why = new StringBuilder();
                if (spawn == null) {
                    why.append("the ball's spawn was never seen; ");
                } else if (fireBuddy && hand[0] == null) {
                    why.append("the held fireball's drawn position at the throw was never read; ");
                } else if (hand[0] != null && spawn.distanceTo(hand[0]) > HAND_SLACK) {
                    why.append(String.format("it left %.2f blocks from the fireball in its hand (%.2f blocks over its "
                                    + "feet; the hand is %.2f over them); ", spawn.distanceTo(hand[0]),
                            spawn.y - feet[0].y, hand[0].y - feet[0].y));
                }
                if (seen[0] < MIN_SEEN) {
                    why.append(String.format("it was in sight for %d ticks (inside its own body for %d) before it "
                            + "landed on a husk %.1f blocks away; ", seen[0], hidden[0], distance[0]));
                }
                if (fireBuddy && !burning[0]) {
                    why.append("its fireball never burned in flight, so it is drawn as the fire charge's dark sprite; ");
                }
                if (why.length() > 0) {
                    helper.fail(Component.literal("a throw nobody would see: " + why));
                } else {
                    helper.succeed();
                }
                return;
            }
            burning[0] |= e.getRemainingFireTicks() > 0;   // drawn wrapped in flames by every client
            if (insideBody(c, e.position(), fireBuddy ? "fireball" : null)) {
                hidden[0]++;
            } else {
                seen[0]++;
            }
        });
    }

    /** Is this point inside a box the rig draws (other than {@code except})? */
    static boolean insideBody(Mob carrier, Vec3 p, String except) {
        double[] r = RigAbilities.intoRig(p, carrier.position(), carrier.yBodyRot);
        for (RigLaunch.Part b : RigAbilities.drawnParts(carrier)) {
            if (b.name().equals(except)) {
                continue;
            }
            if (r[0] >= b.minX() && r[0] <= b.maxX() && r[1] >= b.minY() && r[1] <= b.maxY()
                    && r[2] >= b.minZ() && r[2] <= b.maxZ()) {
                return true;
            }
        }
        return false;
    }

    /** The middle of a named drawn box, in the world. */
    static Vec3 drawnCentre(Mob carrier, String name) {
        List<RigLaunch.Part> parts = RigAbilities.drawnParts(carrier);
        for (RigLaunch.Part b : parts) {
            if (b.name().equals(name)) {
                return HitProxies.toWorld(b.cx(), b.cy(), b.cz(), carrier.position(), carrier.yBodyRot);
            }
        }
        return null;
    }
}
