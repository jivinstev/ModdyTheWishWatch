package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.runtime.CreatureAnatomy;
import com.moddingfromamod.runtime.CreatureSculpt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import com.moddingfromamod.testing.gt.GameTestHolder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Everything the spike's four questions do not cover: does a creature on this path SURVIVE
 * ordinary play?</b>
 *
 * <p>{@link VanillaRigGameTest} answers Q1–Q4 — it looks right, it moves, effects come out of the
 * right spot, and you can hit it. Every one of those is a question about a creature standing
 * still in a test plot. This class asks the questions a child's afternoon asks: give it a potion,
 * drink some milk, set it on fire, kill it, and — the one that matters most — <b>build it an
 * ability the way the agent really does</b> and see whether the ability can find it.
 *
 * <p>Each test here is written so that a PASS is a real statement. Where the vanilla path is
 * genuinely worse than {@code wish_mob}, the test <b>measures the gap and logs it</b> rather than
 * asserting the gap away; where it is outright broken, the test fails. The spike's job is a
 * go/no-go with numbers, and "we never checked" is the answer that costs the most later.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class VanillaRigSurvivalGameTest {

    private static final Logger LOG = LoggerFactory.getLogger("mfam-vanilla-rig");

    private static final BlockPos SPOT = new BlockPos(2, 2, 2);

    private static DisplayRig spikor(GameTestHelper helper) {
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(SpikeFixture.SPIKOR);
        double scale = SpikeFixture.scale(SpikeFixture.SPIKOR);
        var extents = CreatureAnatomy.extents(RigGeometry.of(parts));
        return DisplayRig.spawn(helper.getLevel(),
                Vec3.atBottomCenterOf(helper.absolutePos(SPOT)), 0f, parts, scale,
                CreatureAnatomy.widthBlocks(extents) * scale,
                CreatureAnatomy.heightBlocks(extents) * scale, "spikor");
    }

    // ── the invisibility the whole illusion hangs on ─────────────────────────────────────────

    /**
     * <b>🔴 The carrier is hidden by a POTION EFFECT, and a potion effect is something the world
     * can take away.</b>
     *
     * <p>{@code LivingEntity.updateInvisibilityStatus()} runs every tick and sets the invisible
     * flag from {@code hasEffect(INVISIBILITY)} — so {@code setInvisible(true)} is undone within
     * one tick and the only way to hide a vanilla base is to give it the effect. That was found in
     * Gate C the expensive way, with a photograph of a horse standing inside Spikor.
     *
     * <p>The consequence is this test. Anything that clears effects — a splash of milk, an
     * {@code /effect clear}, a bucket of milk thrown by another mod, the agent's own
     * {@code apply_effect} if it ever cleared first — <b>uncovers the carrier</b>, and the child
     * sees a cow inside their dragon. This is not a hypothetical: milk is a vanilla item a
     * seven-year-old has in their inventory.
     *
     * <p>It is asserted rather than merely logged because it is a hard property of the design, and
     * because the fix (whatever it turns out to be — a re-apply tick, a different carrier, a
     * client-side hide) has to have something that fails when it stops working.
     */
    @GameTest(template = "empty_test")
    public static void clearingEffectsUncoversTheCarrier(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        Mob base = rig.base();

        helper.assertTrue(base.isInvisible(), Component.literal("the carrier should start hidden"));

        base.removeAllEffects();
        base.tick();

        boolean nowVisible = !base.isInvisible();
        LOG.info("VANILLA-RIG survival: clearing effects on the carrier -> visible={} (carrier is {})",
                nowVisible, BuiltInRegistries.ENTITY_TYPE.getKey(base.getType()));

        // (Still true on the carrier's OWN tick, which is all this measures: a DRIVEN rig puts it
        // back on the next server tick -- aDrivenRigReHidesItsCarrierAfterEffectClear,.)
        helper.assertTrue(nowVisible,
                Component.literal("expected clearing effects to UNCOVER the carrier — if this now fails the design "
                        + "has gained a second way to hide it and this test should describe that "
                        + "instead of the potion"));
        helper.succeed();
    }

    /**
     * <b>Is there a way to hide the carrier that milk CANNOT undo?</b> Three candidates, measured
     * rather than argued, because "no fix" is a claim that has to be checked before it is made.
     *
     * <ul>
     *   <li><b>A resource pack cannot do it.</b> An entity texture is chosen by type and variant,
     *       never per entity, so a transparent skin would make <i>every</i> horse in the world
     *       invisible. Not testable here and not a candidate.</li>
     *   <li><b>Re-applying the effect works, at the cost of one tick.</b> Measured below: the
     *       carrier is visible for exactly one tick after the effect is cleared and hidden again on
     *       the next. 50 ms of a horse, once, when a child drinks milk.</li>
     *   <li><b>An {@code Interaction} is invisible with no effect at all</b> — nothing to clear.
     *       It has no AI and no health, which is why it is half of a composite rather than a
     *       replacement, but it settles that "invisible without a potion" exists in vanilla.</li>
     * </ul>
     */
    @GameTest(template = "empty_test")
    public static void theCarrierCanBeReHiddenAfterMilk(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        Mob base = rig.base();

        base.removeAllEffects();
        base.tick();
        boolean visibleAfterMilk = !base.isInvisible();

        // What a re-apply tick would do.
        DisplayRig.hide(base);
        base.tick();
        boolean hiddenAgain = base.isInvisible();

        Entity interaction = EntityType.INTERACTION.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        boolean interactionNeedsNoPotion = interaction != null
                && !(interaction instanceof LivingEntity);
        if (interaction != null) {
            interaction.discard();
        }

        LOG.info("VANILLA-RIG survival: hiding the carrier — cleared->visible={} reapplied->hidden={} "
                        + "(so the exposure is ONE tick, 50 ms); an interaction needs no potion at "
                        + "all: {}", visibleAfterMilk, hiddenAgain, interactionNeedsNoPotion);

        helper.assertTrue(visibleAfterMilk, Component.literal("clearing effects should uncover the carrier"));
        helper.assertTrue(hiddenAgain, Component.literal("re-applying the effect should hide it again"));
        helper.succeed();
    }

    /**
     * <b>: a DRIVEN rig puts its carrier's invisibility back by itself.</b> Live: three wishes
     * that touched a T-Rex ({@code run_command}, {@code apply_effect}, {@code remove_datapack}) left
     * its carrier cow standing visibly inside it, because {@link DisplayRig#hide} — the re-apply the
     * two tests above measured — had no caller. The clear here is the REAL {@code /effect clear}, as
     * the agent's {@code run_command} or a datapack sends it, and the rig is driven by the real
     * {@link VanillaRigAnimator} tick; nothing here calls {@code hide}. Its hit proxies, hidden by
     * the same effect, are checked too.
     */
    @GameTest(template = "empty_test", timeoutTicks = 100)
    public static void aDrivenRigReHidesItsCarrierAfterEffectClear(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(SpikeFixture.SPIKOR);
        VanillaRigAnimator.drive(rig, RigFit.intoRigSpace(parts), Map.of());
        Mob base = rig.base();
        var server = helper.getLevel().getServer();
        helper.startSequence()
                .thenExecuteAfter(2, () -> {
                    helper.assertTrue(base.isInvisible(), Component.literal("the carrier should start hidden"));
                    server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack().withSuppressedOutput(),
                            "effect clear " + base.getStringUUID());
                    for (Mob m : rig.proxies()) {
                        m.removeAllEffects();
                    }
                    helper.assertFalse(base.hasEffect(MobEffects.INVISIBILITY),
                            Component.literal("/effect clear did not clear the carrier -- this test measures nothing"));
                })
                .thenExecuteAfter(3, () -> {
                    helper.assertTrue(base.hasEffect(MobEffects.INVISIBILITY) && base.isInvisible(),
                            Component.literal("the carrier stayed UNCOVERED after /effect clear: a cow inside the creature (#1547)"));
                    for (Mob m : rig.proxies()) {
                        helper.assertTrue(m.isRemoved() || m.isInvisible(),
                                Component.literal("a hit proxy stayed uncovered after its effects were cleared (#1547)"));
                    }
                })
                .thenSucceed();
    }

    /**
     * <b>Killing the creature must not leave its rig standing.</b>
     *
     * <p>The counterpart to {@link #killingTheCreatureLeavesTheRigStanding}, which measures what
     * vanilla does on its own. {@code DisplayRig.reap} works from TAGS in the world rather than
     * from the in-memory rig, because a creature can die during a chunk unload or to a
     * {@code /kill} long after the {@code DisplayRig} object is gone — and a display entity has no
     * despawn rule, so anything it misses is permanent.
     */
    @GameTest(template = "empty_test")
    public static void reapingClearsAnOrphanedRigEvenWithoutTheRigObject(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        int before = rig.displays().size();
        java.util.UUID carrier = rig.base().getUUID();

        rig.base().kill(helper.getLevel());
        rig.base().tick();
        int reaped = DisplayRig.reap(helper.getLevel(), carrier);

        long left = rig.displays().stream().filter(Entity::isAlive).count();
        LOG.info("VANILLA-RIG survival: reap removed {} of {} displays; {} left standing",
                reaped, before, left);

        helper.assertTrue(reaped == before,
                Component.literal("reap should remove every display of the dead carrier; removed " + reaped
                        + " of " + before));
        helper.assertTrue(left == 0, Component.literal(left + " displays still standing after the reap"));
        helper.succeed();
    }

    /**
     * <b>And the mirror of it: a creature cannot be given Invisibility as an ABILITY.</b>
     *
     * <p>"Make my dragon go invisible" is an ordinary wish. On the mod path it hides the model. On
     * this path the model is display entities, which have no invisibility flag of their own, and
     * the carrier is <i>already</i> invisible — so the wish is a visual no-op in one direction and
     * a reveal in the other. Measured rather than argued.
     */
    @GameTest(template = "empty_test")
    public static void invisibilityAsAnAbilityDoesNothingVisible(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        List<String> before = visibleDisplays(rig);

        rig.base().addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 200, 0, true, false));
        rig.base().tick();

        List<String> after = visibleDisplays(rig);
        LOG.info("VANILLA-RIG survival: INVISIBILITY as an ability — displays still drawn {} -> {}",
                before.size(), after.size());

        helper.assertTrue(after.size() == before.size(),
                Component.literal("an item_display has no invisibility flag, so the rig should be unchanged — if this "
                        + "fails, vanilla has grown one and the finding is out of date"));
        helper.succeed();
    }

    private static List<String> visibleDisplays(DisplayRig rig) {
        List<String> out = new ArrayList<>();
        for (Entity e : rig.displays()) {
            if (!e.isInvisible() && e.isAlive()) {
                out.add(e.getStringUUID());
            }
        }
        return out;
    }

    // ── ordinary potion effects ──────────────────────────────────────────────────────────────

    /**
     * <b>Do the other effects work?</b> Every registered {@code MobEffect} applied to the carrier,
     * then read back. This is the cheap half of "can we apply all the other mob effects to them",
     * and it should pass trivially — the carrier is a real vanilla {@code Mob}, so it has vanilla's
     * whole effect machinery for free. Worth asserting anyway, because the interesting result would
     * be a FAILURE, and "we assumed it worked" is how the invisibility trap above got in.
     *
     * <p>What this test can and cannot say is the point. It proves the effect LANDS. It cannot
     * prove the effect is VISIBLE: an effect whose only expression is a tint or a particle drawn on
     * the mob's own model (glowing, invisibility) renders on the carrier, which nobody can see.
     * That half is counted separately below.
     */
    @GameTest(template = "empty_test")
    public static void theCarrierAcceptsEveryVanillaMobEffect(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        Mob base = rig.base();

        List<String> refused = new ArrayList<>();
        int applied = 0;
        for (var holder : BuiltInRegistries.MOB_EFFECT.asHolderIdMap()) {
            MobEffect effect = holder.value();
            if (effect.isInstantenous()) {
                continue;   // instant health/damage has nothing to read back
            }
            base.addEffect(new MobEffectInstance(holder, 100, 0, true, false));
            if (base.hasEffect(holder)) {
                applied++;
            } else {
                refused.add(BuiltInRegistries.MOB_EFFECT.getKey(effect).toString());
            }
        }

        LOG.info("VANILLA-RIG survival: {} of {} non-instant mob effects applied to the carrier; "
                        + "refused={}", applied, applied + refused.size(), refused);
        helper.assertTrue(refused.isEmpty(),
                Component.literal("the carrier is a real vanilla Mob and should accept every effect; refused "
                        + refused));
        helper.succeed();
    }

    /**
     * <b>The half that did NOT work, and the glow half that now does</b>.
     *
     * <p>An effect drawn on the entity's own model — the glowing outline, the invisibility fade,
     * the shaking of Darkness — is rendered by the carrier's renderer, and the carrier is invisible.
     * The rig is a separate set of display entities that vanilla has no reason to tint.
     *
     * <p>Glowing was listed here as "RECOVERABLE by mirroring the flag onto the displays" and then
     * nothing mirrored it, while the invisible carrier's own outline — which vanilla DOES draw, as
     * {@code RenderType.outline} — showed the child a horse inside a sea serpent. It is mirrored
     * now ({@code DisplayRig.mirrorGlow}, every tick from {@code VanillaRigAnimator}), so this
     * asserts the mirror: the rig glows, and the carrier sends no outline. {@link RigGlowMirrorGameTest} covers each glow source.
     */
    @GameTest(template = "empty_test")
    public static void effectsDrawnOnTheMobItselfAreLostAndAreListed(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        VanillaRigAnimator.drive(rig,
                RigFit.intoRigSpace(SpikeFixture.parts(SpikeFixture.SPIKOR)), Map.of());
        Mob base = rig.base();

        // Glowing is the clearest case: vanilla draws an outline around the ENTITY's model.
        base.setGlowingTag(true);
        helper.runAfterDelay(2, () -> {
            boolean baseGlows = base.isCurrentlyGlowing();
            boolean carrierOutlined = RigGlowMirrorGameTest.carrierOutlineSent(base);
            long displaysGlowing = rig.displays().stream()
                    .filter(Entity::isCurrentlyGlowing).count();

            LOG.info("VANILLA-RIG survival: glowing — carrier={} (outline sent={}), rig mirrors "
                            + "it={}/{}", baseGlows, carrierOutlined, displaysGlowing,
                    rig.displays().size());

            helper.assertTrue(baseGlows,
                    Component.literal("the carrier is still glowing as far as the server knows -- it is the truth"));
            helper.assertFalse(carrierOutlined,
                    Component.literal("the invisible carrier must not draw its own outline (#775)"));
            helper.assertTrue(displaysGlowing == rig.displays().size(),
                    Component.literal("every display should mirror the carrier's glow; " + displaysGlowing + " of "
                            + rig.displays().size() + " did"));
            helper.succeed();
        });
    }

    // ── 🔴 the ability path, end to end ──────────────────────────────────────────────────────

    /**
     * <b>🔴 THE FINDING THIS CLASS EXISTS FOR: an ability built by the real agent cannot find a
     * creature on this path.</b>
     *
     * <p>{@code apply_effect} does not target an entity by id or by handle. It writes a datapack
     * whose selectors are {@code @e[tag=wishmob_<species>]} — {@link WishMobTags} exists precisely
     * so the agent never has to invent a selector, and {@code WishMobEntity.applySpeciesTag} puts
     * those two tags on every creature the mod spawns.
     *
     * <p><b>{@code DisplayRig} puts neither on the carrier.</b> So every ability the agent has ever
     * built — fire breath, the spider's slowness, a proximity trigger — installs correctly,
     * compiles correctly, reports {@code DONE}, and then selects <b>nothing at all</b>, forever.
     * There is no error anywhere: a selector that matches no entities is not a failure in
     * Minecraft, it is an ordinary tick that does nothing.
     *
     * <p>That is the same shape as the fixed-hitbox bug this project already fixed once — a real
     * capability silently absent, with every cheap instrument green — and it is invisible to Q1
     * (a photograph), Q2 (packet counting), Q3 (an emission point measured from the base directly)
     * and Q4 (raycasts). Only asking "would the agent's own selector find this" surfaces it.
     */
    @GameTest(template = "empty_test")
    public static void anAgentBuiltAbilityCanFindTheCreature(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        Mob base = rig.base();

        String species = WishMobTags.forSpecies("spikor");
        boolean carriesAll = base.getTags().contains(WishMobTags.ALL);
        boolean carriesSpecies = base.getTags().contains(species);

        LOG.info("VANILLA-RIG survival: agent selector check — carrier tags={} "
                        + "wants '{}' and '{}' -> ALL={} SPECIES={}",
                base.getTags(), WishMobTags.ALL, species, carriesAll, carriesSpecies);

        helper.assertTrue(carriesAll && carriesSpecies,
                Component.literal("an ability's selectors are @e[tag=" + WishMobTags.ALL + "] and @e[tag=" + species
                        + "]; the carrier has " + base.getTags() + ", so every ability the agent "
                        + "builds would select NOTHING and report DONE"));
        helper.succeed();
    }

    /**
     * <b>And the twin failure on the other side: an ability must fire ONCE, not once per display.</b>
     *
     * <p>Tagging the carrier is the obvious fix for the test above, and the obvious way to do it —
     * tag everything the rig spawns — turns one fire breath into nineteen. A selector counts
     * entities, and the rig is 6 to 19 of them standing in the same place.
     *
     * <p>So this pins the shape of the fix in advance: exactly one entity in the rig may wear the
     * wishmob tags. It passes today for the wrong reason (nothing is tagged, so the count is 0 not
     * 19) and would fail the moment somebody tags the displays, which is the point of writing it
     * now.
     */
    @GameTest(template = "empty_test")
    public static void exactlyOneEntityInTheRigMayWearTheAbilityTag(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);

        int wearing = 0;
        for (Entity e : rig.all()) {
            if (e.getTags().contains(WishMobTags.ALL)) {
                wearing++;
            }
        }
        LOG.info("VANILLA-RIG survival: {} of {} rig entities wear '{}' (must be exactly 1 once the "
                + "tag is applied, or an ability fires once per display)",
                wearing, rig.all().size(), WishMobTags.ALL);

        helper.assertTrue(wearing <= 1,
                Component.literal(wearing + " entities in the rig wear " + WishMobTags.ALL + " — an ability would "
                        + "fire " + wearing + " times from the same spot"));
        helper.succeed();
    }

    // ── death, damage and cleanup ────────────────────────────────────────────────────────────

    /**
     * <b>🔴 What happens to 19 display entities when the creature dies?</b>
     *
     * <p>Nothing removes them. A vanilla base dies through vanilla's own death path, which knows
     * nothing about the rig hanging off it, so the displays are left standing in the world as a
     * creature-shaped ghost that cannot be hit, targeted or killed — and, being display entities
     * with no despawn rule, they persist across save and reload forever.
     *
     * <p>This is worse than it sounds for a child's world: every creature ever killed leaves its
     * corpse standing, permanently, at 6–19 entities each. Measured, not assumed.
     */
    @GameTest(template = "empty_test")
    public static void killingTheCreatureLeavesTheRigStanding(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        int before = rig.displays().size();

        rig.base().kill(helper.getLevel());
        rig.base().tick();

        long stillAlive = rig.displays().stream().filter(Entity::isAlive).count();
        LOG.info("VANILLA-RIG survival: carrier killed — {} of {} display entities still alive "
                        + "(orphaned rig; they have no despawn rule and survive save/reload)",
                stillAlive, before);

        helper.assertTrue(stillAlive == before,
                Component.literal("expected the rig to be orphaned today (" + before + " displays); if this fails, "
                        + "cleanup has been added and this finding is stale"));
        helper.succeed();
    }

    /**
     * <b>Does the creature take damage and lose health like a creature?</b>
     *
     * <p>Yes, and for free — the carrier is a real {@code Mob}. Asserted so that a future change to
     * the carrier (an {@code ArmorStand}, an {@code Interaction}, a marker) cannot quietly take
     * health with it. An {@code Interaction} entity, which is the obvious answer to Q4's hitbox
     * problem, has <b>no health at all</b>, and this test is what would notice.
     */
    @GameTest(template = "empty_test")
    public static void theCreatureHasHealthAndTakesDamage(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        LivingEntity base = rig.base();

        float full = base.getHealth();
        base.hurt(helper.getLevel().damageSources().generic(), 3.0f);

        LOG.info("VANILLA-RIG survival: health {} -> {} after 3 damage (max {})",
                full, base.getHealth(), base.getMaxHealth());
        helper.assertTrue(base.getHealth() < full,
                Component.literal("the carrier should lose health; it is a real Mob"));
        helper.succeed();
    }

    /**
     * <b>Fire: the carrier burns, and nobody can see it.</b>
     *
     * <p>"Set it on fire" and "make it breathe fire" are different wishes and only the second one
     * works here. Burning is drawn as flames over the ENTITY, and the entity is invisible — so a
     * creature standing in lava looks perfectly comfortable while its health drains.
     *
     * <p>The emission side is already answered: Q3 measured an ability's emission point at
     * <b>0.000000 blocks</b> from the mod path's, so a fire breath comes out of the right place.
     * It is the AMBIENT visual state — burning, glowing, freezing, the damage-red flash — that is
     * attached to a model nobody renders.
     */
    @GameTest(template = "empty_test")
    public static void theCarrierBurnsButTheFlamesAreDrawnOnSomethingInvisible(
            GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        Mob base = rig.base();

        base.igniteForSeconds(5);
        boolean burning = base.isOnFire();
        boolean anyDisplayBurning = rig.displays().stream().anyMatch(Entity::isOnFire);

        // Is THIS one recoverable the way glow is? Set the flag on the displays and ask.
        for (Entity d : rig.displays()) {
            d.igniteForSeconds(5);
        }
        long displaysLit = rig.displays().stream().filter(Entity::isOnFire).count();

        LOG.info("VANILLA-RIG survival: on fire — carrier={} (invisible), rig inherits={}, rig "
                        + "burns when told={}/{} -> {}", burning, anyDisplayBurning, displaysLit,
                rig.displays().size(),
                displaysLit == rig.displays().size()
                        ? "server-side flag takes; whether vanilla DRAWS flames on a Display is a "
                          + "Gate-C question this cannot answer"
                        : "not settable, so a burning creature shows no flames");
        helper.assertTrue(burning, Component.literal("the carrier should catch fire"));
        helper.succeed();
    }

    /**
     * <b>Head-follow: the rig turns the head group, and only the head group.</b>
     *
     * <p>"Head-follow does not work" sounded like the rig could not tell which way the creature was
     * looking. It always could — {@code getYHeadRot()} and {@code getXRot()} are live and synced,
     * and {@code WishMobRenderer} builds its own angles from those same two fields. What was
     * missing was the write.
     *
     * <p>Two things are asserted because getting either wrong is silent: only the HEAD SUBTREE is
     * rewritten (turning the whole rig would swing the body too), and a creature that has not moved
     * its head costs NOTHING, which is the rule Q2's whole cost model rests on.
     *
     * <p>⚠ <b>No literal count, on purpose.</b> This test used to pin the pack rig, where all 26 of
     * Spikor's head boxes were baked into ONE display, so "turning rewrote 1" was its answer. On
     * the atom rig — the only rig — every box is a display and head-follow writes the
     * whole head SUBTREE, so the right number is however many atoms that subtree holds, which is a
     * fact about the fixture rather than about the driver. What the driver owes is structural:
     * turning writes SOMETHING, never the whole creature, and holding or looking ahead writes
     * nothing.
     */
    @GameTest(template = "empty_test")
    public static void theHeadFollowsAndNothingElseDoes(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);

        int heads = 0;
        for (RigPlan.Group g : rig.groups()) {
            if (RigPlan.isHead(g)) {
                heads++;
            }
        }

        int still = RigDriver.lookAt(rig, 0f, 0f, 2);
        int turned = RigDriver.lookAt(rig, 35f, -12f, 2);
        int held = RigDriver.lookAt(rig, 35f, -12f, 2);

        LOG.info("VANILLA-RIG survival: head-follow — {} head atom(s) of {}; straight ahead "
                        + "rewrote {}, turning rewrote {}, HOLDING the same turn rewrote {}",
                heads, rig.groups().size(), still, turned, held);
        helper.assertTrue(heads > 0, Component.literal("Spikor has no head atoms, so this measured nothing"));
        helper.assertTrue(turned > 0, Component.literal("turning the head rewrote nothing -- head-follow is not wired"));
        helper.assertTrue(turned < rig.groups().size(),
                Component.literal("turning the head rewrote all " + turned + " displays -- the whole body swung "
                        + "with it, which is the failure this test exists for"));
        helper.assertTrue(held == 0,
                Component.literal("holding a head turn must cost nothing after the first write; rewrote " + held));
        helper.assertTrue(still == 0,
                Component.literal("a creature looking straight ahead must cost nothing; rewrote " + still));
        helper.succeed();
    }

    /**
     * <b>The damage-flash channel: can the rig be recoloured at runtime on a vanilla client?</b>
     *
     * <p>Vanilla's hurt flash is an overlay on the ENTITY's model and the rig's entity is invisible,
     * so it cannot be reached. What CAN be reached is a colour multiply, if two things are true at
     * once: every model face declares a {@code tintindex}, and the carrier item is one vanilla has
     * a colour handler for. {@code paper} has none — a tintindex on it renders plain white — so the
     * tintable pack switches the carrier to a leather item, which is in {@code #minecraft:dyeable}.
     *
     * <p>Verified in Gate C rather than inferred: with {@code rigtint=on tintrgb=FF0000} the
     * silhouette is unchanged and 8,429 px change colour, with the mean subject green and blue both
     * pushed down. This test guards the SERVER half — that the component actually lands on every
     * display — because the visual half costs a client boot.
     */
    @GameTest(template = "empty_test")
    public static void theRigCanBeRecolouredAtRuntime(GameTestHelper helper) {
        DisplayRig rig = spikor(helper);
        int painted = rig.tint(0xFF0000);

        int carrying = 0;
        for (Entity d : rig.displays()) {
            CompoundTag tag = com.moddingfromamod.compat.EntityNbt.save(d);
            CompoundTag item = tag.getCompoundOrEmpty("item");
            CompoundTag comps = item.getCompoundOrEmpty("components");
            if (comps.contains("minecraft:dyed_color")) {
                carrying++;
            }
        }

        LOG.info("VANILLA-RIG survival: tint — painted {} displays, {} of {} carry dyed_color "
                        + "(carrier={}, tintindex={}) -> {}",
                painted, carrying, rig.displays().size(), RigCarrier.CARRIER, true, "live");

        helper.assertTrue(painted == rig.displays().size(),
                Component.literal("every display should be repainted; " + painted + " of " + rig.displays().size()));
        helper.assertTrue(carrying == rig.displays().size(),
                Component.literal("the dyed_color component should land on every display; " + carrying + " of "
                        + rig.displays().size()));
        helper.succeed();
    }

    // ── the base-shape question, over the WHOLE registry ─────────────────────────────────────

    /**
     * <b>"Aren't there differently shaped mobs we could use?" — asked of every mob vanilla has.</b>
     *
     * <p>{@link DisplayRig#candidates} offers a hand-picked 21. That is a fine starting set and a
     * terrible answer to this question, because the whole point is whether the SHAPE SPACE is big
     * enough — so this sweeps the entire entity registry, builds a candidate from every type that
     * really instantiates as a {@code Mob}, and reports the best achievable width error for each
     * corpus creature.
     *
     * <p>Two things bound the answer and neither is about how many mobs there are:
     *
     * <ul>
     *   <li>{@code minecraft:scale} is <b>uniform</b>. It multiplies width and height together, so
     *       a base's width/height RATIO is fixed and the only thing that matters about a candidate
     *       is that one number. 100 mobs with 12 distinct ratios offer 12 choices.</li>
     *   <li>A {@code Monster} is discarded on Peaceful ({@link RigBase#PEACEFUL_RULE}), which
     *       removes a large slice of the shape space — and the widest and tallest vanilla mobs are
     *       disproportionately hostile.</li>
     * </ul>
     *
     * <p>The output is the table that answers the question, so it is logged in full rather than
     * summarised. The assertion is only that the sweep actually ran and found real candidates: a
     * probe that silently instantiated nothing would report perfect coverage.
     */
    @GameTest(template = "empty_test")
    public static void theWholeRegistryIsSweptForABetterShape(GameTestHelper helper) {
        List<RigBase.Candidate> all = new ArrayList<>();
        Map<String, Float> ratios = new LinkedHashMap<>();

        for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
            Entity probe;
            try {
                probe = t.create(helper.getLevel(), EntitySpawnReason.COMMAND);
            } catch (Exception e) {
                continue;   // some types refuse to instantiate outside their own context
            }
            if (!(probe instanceof Mob mob)) {
                if (probe != null) {
                    probe.discard();
                }
                continue;
            }
            var d = t.getDimensions();
            boolean safe = !(mob instanceof Enemy);
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath();
            if (d.width() > 0 && d.height() > 0) {
                all.add(new RigBase.Candidate(id, d.width(), d.height(), safe));
                if (safe) {
                    ratios.put(id, d.width() / d.height());
                }
            }
            probe.discard();
        }

        long safeCount = all.stream().filter(RigBase.Candidate::peacefulSafe).count();
        List<Float> distinct = ratios.values().stream().map(r -> Math.round(r * 100) / 100f)
                .distinct().sorted().toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "VANILLA-RIG shape space: %d mob types in the registry, %d peaceful-safe, "
                        + "%d DISTINCT width/height ratios among them (scale is uniform, so the "
                        + "ratio is the only thing a base can offer)%n", all.size(), safeCount,
                distinct.size()));
        sb.append("  ratios available: ").append(distinct).append('\n');

        sb.append(String.format("  %-18s %7s %7s %6s | %-13s %6s %-10s | %-13s %6s %6s %s%n",
                "creature", "wantW", "wantH", "ratio",
                "HEIGHT fit", "wErr", "narrow?",
                "COVER fit", "wErr", "hErr", "narrow?"));
        int worseThanFivePercent = 0;
        int stillTooNarrow = 0;
        int coverTooNarrow = 0;
        double coverWorstHeightErr = 0;
        int cases = 0;
        for (String name : SpikeFixture.CASES) {
            if (!SpikeFixture.available(name)) {
                continue;
            }
            List<CreatureSculpt.Part> parts = SpikeFixture.parts(name);
            double s = SpikeFixture.scale(name);
            var ex = CreatureAnatomy.extents(RigGeometry.of(parts));
            double w = CreatureAnatomy.widthBlocks(ex) * s;
            double h = CreatureAnatomy.heightBlocks(ex) * s;

            RigBase.Choice byHeight = RigBase.choose(all, w, h, RigBase.Fit.HEIGHT);
            RigBase.Choice byCover = RigBase.choose(all, w, h, RigBase.Fit.COVER);

            cases++;
            if (byHeight != null && byHeight.widthErrorFraction() > 0.05) {
                worseThanFivePercent++;
            }
            if (byHeight != null && byHeight.tooNarrow()) {
                stillTooNarrow++;
            }
            double coverHeightErr = RigBase.heightErrorFraction(byCover, h);
            if (byCover != null && byCover.tooNarrow()) {
                coverTooNarrow++;
            }
            coverWorstHeightErr = Math.max(coverWorstHeightErr, coverHeightErr);

            sb.append(String.format(
                    "  %-18s %7.2f %7.2f %6.2f | %-13s %5.1f%% %-10s | %-13s %5.1f%% %5.1f%% %s%n",
                    name, w, h, w / h,
                    byHeight == null ? "NONE" : byHeight.id(),
                    byHeight == null ? 0 : byHeight.widthErrorFraction() * 100,
                    byHeight != null && byHeight.tooNarrow() ? "TOO NARROW" : "ok",
                    byCover == null ? "NONE" : byCover.id(),
                    byCover == null ? 0 : byCover.widthErrorFraction() * 100,
                    coverHeightErr * 100,
                    byCover != null && byCover.tooNarrow() ? "TOO NARROW" : "ok"));
        }
        sb.append(String.format(
                "  -> HEIGHT fit: %d of %d creatures worse than 5%% width error, %d TOO NARROW%n",
                worseThanFivePercent, cases, stillTooNarrow));
        sb.append(String.format(
                "  -> COVER  fit: %d of %d TOO NARROW, worst height overshoot %.1f%% "
                        + "(too wide is a bump you can see; too narrow is a hit that misses)%n",
                coverTooNarrow, cases, coverWorstHeightErr * 100));

        helper.assertTrue(coverTooNarrow == 0,
                Component.literal("COVER exists precisely so no box is ever narrower than the model, and "
                        + coverTooNarrow + " still are"));
        LOG.info("{}", sb);

        helper.assertTrue(safeCount > 20,
                Component.literal("the registry sweep found only " + safeCount + " peaceful-safe mobs — the probe is "
                        + "broken, and a broken probe reports perfect coverage"));
        helper.assertTrue(cases > 0, Component.literal("no corpus creature was available to measure against"));
        helper.succeed();
    }

    /**
     * <b>The composite that WOULD fix Q4, priced.</b>
     *
     * <p>An {@code Interaction} entity carries {@code width} and {@code height} as independent NBT
     * fields — the one thing no {@code Mob} offers, because {@code minecraft:scale} is uniform. So
     * the shape that gets both a correct aim surface and real AI/health is a pair: a {@code Mob}
     * for the brain and an {@code Interaction} sized to the model for the hitbox.
     *
     * <p>This test proves the half that was in doubt — that an {@code Interaction}'s box really can
     * be set to a sculpt's exact extents through public NBT, at a ratio no vanilla mob has. It does
     * not claim the composite is built; hit forwarding, targeting and death are all still open, and
     * the spike says so.
     */
    @GameTest(template = "empty_test")
    public static void anInteractionCanTakeTheExactShapeNoMobCanOffer(GameTestHelper helper) {
        List<CreatureSculpt.Part> parts = SpikeFixture.parts(SpikeFixture.SPIKOR);
        double s = SpikeFixture.scale(SpikeFixture.SPIKOR);
        var ex = CreatureAnatomy.extents(RigGeometry.of(parts));
        double wantW = CreatureAnatomy.widthBlocks(ex) * s;
        double wantH = CreatureAnatomy.heightBlocks(ex) * s;

        Entity hit = EntityType.INTERACTION.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        helper.assertTrue(hit != null, Component.literal("vanilla should offer an interaction entity"));
        hit.snapTo(Vec3.atBottomCenterOf(helper.absolutePos(SPOT)));

        var nbt = com.moddingfromamod.compat.EntityNbt.save(hit);
        nbt.putFloat("width", (float) wantW);
        nbt.putFloat("height", (float) wantH);
        com.moddingfromamod.compat.EntityNbt.load(hit, nbt);
        helper.getLevel().addFreshEntity(hit);

        var box = hit.getBoundingBox();
        double gotW = box.getXsize();
        double gotH = box.getYsize();
        LOG.info("VANILLA-RIG Q4 composite: interaction asked for {}x{} (ratio {}), got {}x{} "
                        + "-> width error {}%",
                String.format("%.3f", wantW), String.format("%.3f", wantH),
                String.format("%.2f", wantW / wantH),
                String.format("%.3f", gotW), String.format("%.3f", gotH),
                String.format("%.2f", Math.abs(gotW - wantW) / wantW * 100));

        helper.assertTrue(Math.abs(gotW - wantW) < 0.02 && Math.abs(gotH - wantH) < 0.02,
                Component.literal("an interaction should take the exact box; asked " + wantW + "x" + wantH + " got "
                        + gotW + "x" + gotH));
        hit.discard();
        helper.succeed();
    }
}
