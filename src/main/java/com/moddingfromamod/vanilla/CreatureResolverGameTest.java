package com.moddingfromamod.vanilla;

import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.Surroundings;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.ModifyMobTool;
import com.moddingfromamod.builder.BuildPlan;
import com.moddingfromamod.builder.BuildRegistry;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.marshal.WishLadder;
import com.moddingfromamod.mob.EntityCapture;
import com.moddingfromamod.mob.EntityHandles;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.core.BlockPos;
import com.moddingfromamod.testing.gt.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.moddingfromamod.testing.gt.GameTestHolder;

/**
 * <b>"Which creature do you mean" has one answer, on the rig that ships.</b>
 *
 * <p>Every test stands the same three things side by side, because that is a child's world since
 * and none of the tests before this one did: a wished creature on its vanilla carrier (a long
 * one, so it has hit proxies), and a REAL mob of the carrier's own type. The production failures
 * this pins, all from 2026-09-25's logs:
 * <ul>
 *   <li> — {@code modify_mob {target:"spikor"}} → <i>"No mob matching spikor … Nearby:
 *       minecraft:llama"</i>, and the same for {@code wishmob_spikor};</li>
 *   <li> — the workaround {@code {target:"llama", scope:"group"}} reaches his real llamas,
 *       {@code look_around} lists the creature as its carrier and lists the proxies, and a
 *       {@code run_command} diff mints handles for proxies;</li>
 *   <li> — {@code ArtifactIndex} measured distance on the deleted entity, so a creature was
 *       always "in another world" and could never be the nearest thing, and one creature was two
 *       referents (its species, and its carrier's handle).</li>
 * </ul>
 *
 * <p>{@code ModifyMobReachesTheRigGameTest} stayed green through all of it because it targeted
 * the carrier's TYPE — the workaround itself — with no real mob of that type anywhere near.
 *
 * <p>⚠ At WORLD SPAWN, because {@code modify_mob} and {@code look_around} measure from the child,
 * and with nobody online that is where the child is. One NAMED batch per test: batches run one
 * after another, so nothing else is standing at world spawn while these run.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public class CreatureResolverGameTest {

    /** Tags every entity these tests stand up, so the next run can clear exactly its own. */
    private static final String MINE = "gt_t6g1";

    /** The creature's species — a whale, long enough that the rig needs hit proxies. */
    private static final String ID = "gt_t6g1_whale";
    private static final String NAME = "Wally Whale";

    // ===================== +: modify_mob =====================

    @GameTest(template = "empty_test", batch = "t6g1_modify", timeoutTicks = 800)
    public static void modifyMobFindsTheCreatureByItsOwnNamesAndNeverARealMob(
            GameTestHelper helper) {
        Scene s = stage(helper);
        String carrierWord = EntityType.getKey(s.carrier.getType()).getPath();

        AtomicReference<List<ToolResult>> replies = new AtomicReference<>();
        offThread(() -> {
            List<ToolResult> out = new ArrayList<>();
            out.add(call("{\"scope\":\"one\",\"target\":\"wally\",\"health\":61}"));
            out.add(call("{\"scope\":\"one\",\"target\":\"" + WishMobTags.forSpecies(ID)
                    + "\",\"health\":62}"));
            out.add(call("{\"scope\":\"one\",\"target\":\"" + WishMobTags.selector(ID)
                    + "\",\"health\":63}"));
            // an earlier issue's production workaround, with his real one standing right there.
            out.add(call("{\"scope\":\"group\",\"target\":\"" + carrierWord
                    + "\",\"count\":20,\"glow\":\"red\"}"));
            replies.set(out);
        });

        helper.succeedWhen(() -> {
            List<ToolResult> r = replies.get();
            helper.assertTrue(r != null, Component.literal("waiting for modify_mob"));
            helper.assertFalse(r.get(0).isError(), Component.literal("#940: target:\"wally\" (the creature's "
                    + "own name) must reach it: " + r.get(0).text()));
            helper.assertFalse(r.get(1).isError(), Component.literal("#940: target:\"" + WishMobTags.forSpecies(ID)
                    + "\" (its tag) must reach it: " + r.get(1).text()));
            helper.assertFalse(r.get(2).isError(), Component.literal("the selector the creature tools hand the "
                    + "agent must reach it: " + r.get(2).text()));
            Species after = SpeciesRegistry.get(ID);
            helper.assertTrue(after != null && after.health() == 63.0,
                    Component.literal("the three edits should have landed on the creature's design (health 63), "
                            + "got " + (after == null ? "no species" : after.health())));

            helper.assertFalse(r.get(3).isError(), Component.literal("the real " + carrierWord
                    + " is right there: " + r.get(3).text()));
            helper.assertTrue(s.real.hasGlowingTag(), Component.literal("the REAL " + carrierWord
                    + " should glow red: " + r.get(3).text()));
            LivingEntity carrierNow = carrierOf(helper);
            helper.assertTrue(carrierNow == null || !carrierNow.hasGlowingTag(),
                    Component.literal("#950: target:\"" + carrierWord + "\" reached the CREATURE through its "
                            + "carrier's type — a group edit of his real " + carrierWord
                            + "s must never touch a wished creature: " + r.get(3).text()));
            helper.assertFalse(after.has(Species.Power.GLOWING),
                    Component.literal("#950: the creature's DESIGN was changed by an edit of real "
                            + carrierWord + "s: " + r.get(3).text()));
            for (Mob p : s.proxies) {
                helper.assertFalse(p.hasGlowingTag(), Component.literal("#950: a hit proxy was edited as if "
                        + "it were a mob: " + r.get(3).text()));
            }
            tidy(helper);
        });
    }

    // =====================: look_around =====================

    @GameTest(template = "empty_test", batch = "t6g1_look", timeoutTicks = 400)
    public static void lookAroundListsTheCreatureByItsNameAndNoneOfItsMachinery(
            GameTestHelper helper) {
        Scene s = stage(helper);
        ServerLevel level = helper.getLevel().getServer().overworld();
        String carrierType = EntityType.getKey(s.carrier.getType()).toString();
        String said = Surroundings.describe(level, s.spawn);
        String mobs = section(said);

        helper.assertTrue(mobs.contains(NAME) || mobs.contains(ID),
                Component.literal("look_around must name the creature by its species, not its carrier:\n" + mobs));
        int carrierLines = 0;
        int lines = 0;
        for (String line : mobs.split("\n")) {
            if (!line.contains(" blocks away")) {
                continue;
            }
            lines++;
            if (line.contains(carrierType)) {
                carrierLines++;
                helper.assertFalse(line.contains(NAME) || line.contains(ID),
                        Component.literal("#950: the creature is listed under its carrier's type, which teaches the "
                                + "agent that '" + carrierType + "' is its name:\n" + line));
            }
        }
        helper.assertTrue(carrierLines == 1, Component.literal("exactly ONE line should say " + carrierType
                + " — the real one — but " + carrierLines + " do:\n" + mobs));
        int expected = Math.min(12, countMeaningful(level, s.spawn, 32));
        helper.assertTrue(lines == expected, Component.literal("#950: look_around lists " + lines + " mobs where "
                + expected + " are things a child could mean — the " + s.proxies.size()
                + " hit proxies are invisible machinery, not mobs:\n" + mobs));
        tidy(helper);
        helper.succeed();
    }

    // =====================: handles minted by a command diff =====================

    @GameTest(template = "empty_test", batch = "t6g1_capture", timeoutTicks = 400)
    public static void aCommandDiffMintsOneHandleForTheCreatureAndNoneForItsProxies(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().overworld();
        Vec3 spawn = clearWorldSpawn(helper, level);
        // EXACTLY what run_command does around one command (RunCommandTool): snapshot, act, diff.
        Set<java.util.UUID> before = EntityCapture.snapshot(level, spawn);
        Scene s = standUp(helper, level, spawn);
        List<String> minted = EntityCapture.rememberNew(level, spawn, before);

        helper.assertTrue(!s.proxies.isEmpty(), Component.literal("the whale was given no hit proxies, so this "
                + "proves nothing about them: " + s.rig.hitPlan()));
        for (Mob p : s.proxies) {
            helper.assertTrue(EntityHandles.global().handleFor(p.getUUID()) == null,
                    Component.literal("#950: a hit proxy was minted a handle (" + EntityHandles.global()
                            .handleFor(p.getUUID()) + ") — the agent can now 'make it bigger' "
                            + "on an invisible box. Minted: " + minted));
        }
        String creature = EntityHandles.global().handleFor(s.carrier.getUUID());
        helper.assertTrue(creature != null, Component.literal("the creature itself got no handle: " + minted));
        var h = EntityHandles.global().resolve(creature).orElseThrow();
        helper.assertFalse(h.typeId().equals(EntityType.getKey(s.carrier.getType()).toString()),
                Component.literal("#940: the creature's handle " + creature + " records its CARRIER's type ("
                        + h.typeId() + "), so a later \"" + EntityType.getKey(s.carrier.getType())
                        .getPath() + "\" means him"));
        helper.assertTrue(minted.size() == 2, Component.literal("one handle for the creature and one for the real "
                + "mob, got " + minted));
        tidy(helper);
        helper.succeed();
    }

    // =====================: the world index =====================

    @GameTest(template = "empty_test", batch = "t6g1_ladder", timeoutTicks = 400)
    public static void theNearestThingCanBeACreatureAndACreatureIsOneReferent(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().overworld();
        // what an earlier batch leaves at spawn, planted on purpose so this is not 1 run in N.
        // Buildings never leave BuildRegistry when their test ends (WishVisitGameTest's castle, the
        // builders'), and spawn is shared by every test in this reused world: in two full ci,default
        // runs one stood nearer him than the whale and was "in his view" first, while the test
        // passed alone. A named batch runs alone, so the stage must forget them as it does stray
        // creatures (clearOtherBuildings).
        String leftover = "gt_t6g1_leftover_from_another_batch";
        BuildRegistry.putInMemoryForProbe(BuildPlan.fromJson(JsonParser.parseString("""
            {"id":"%s","name":"Leftover hut","shapes":[
              {"kind":"box","width":1,"height":1,"depth":1,"block":"minecraft:stone"}]}
            """.formatted(leftover)).getAsJsonObject()));
        BuildRegistry.rememberAnchor(leftover,
                BlockPos.containing(Vec3.atBottomCenterOf(level.getSharedSpawnPos()).add(2, 0, 0)).asLong(),
                level.dimension());
        Scene s = stage(helper);
        // A building in view, three times as far away as the creature.
        String castle = "gt_t6g1_castle";
        BuildRegistry.put(level.getServer(), BuildPlan.fromJson(JsonParser.parseString("""
            {"id":"%s","name":"Ladder castle","shapes":[
              {"kind":"box","width":2,"height":2,"depth":2,"block":"minecraft:stone"}]}
            """.formatted(castle)).getAsJsonObject()));
        BuildRegistry.rememberAnchor(castle, BlockPos.containing(s.spawn.add(15, 0, 0)).asLong(),
                level.dimension());
        // What production's run_command → modify_mob left on this creature on 2026-09-25: a
        // handle named after its CARRIER. The index must still see ONE creature.
        EntityHandles.global().targeted(s.carrier.getUUID(),
                EntityType.getKey(s.carrier.getType()).toString(), NAME);

        ServerPlayer looker = unplacedPlayer(level, s.spawn);   // looking along +x, at both
        var all = com.moddingfromamod.wish.ArtifactIndex.forPlayer(looker);
        WishLadder.Candidate whale = find(all, ID);
        WishLadder.Candidate building = find(all, castle);
        helper.assertTrue(whale != null, Component.literal("the creature is not a candidate at all: " + ids(all)));
        helper.assertTrue(building != null, Component.literal("the building is not a candidate: " + ids(all)));
        helper.assertTrue(whale.distance() >= 0 && whale.distance() < 10,
                Component.literal("#948: the creature 5 blocks in front of him reads as distance "
                        + whale.distance() + " — i.e. 'in another world' — so it can never be "
                        + "the nearest thing"));
        helper.assertTrue(whale.inView(), Component.literal("#948: the creature right in front of him is not "
                + "'in view'"));
        for (WishLadder.Candidate c : all) {
            helper.assertFalse("mob".equals(c.kind())
                            && (c.aliases().contains(NAME) || c.name().equals(NAME)),
                    Component.literal("#948: the creature is TWO referents — species '" + ID + "' and mob '"
                            + c.id() + "' — so the ladder asks him to choose between a creature "
                            + "and itself: " + ids(all)));
        }

        // nothing resolves "make it bigger" locally any more. What survives of the index is
        // the FACT of what is in his view, nearest first, and the creature is in it as itself.
        String facts = com.moddingfromamod.wish.WishFacts.forPlayer(looker);
        int creatureAt = facts.indexOf("'" + ID + "'");
        int castleAt = facts.indexOf("'" + castle + "'");
        // The castle may be cut by the in-view cap: a world shared by every batch holds other tests'
        // buildings near this plot (1.21.8 reuses plot positions), so "ahead of the building" is
        // read as: the creature is listed, FIRST, and the castle is either after it or past the cap.
        int firstThing = facts.indexOf("In his view");
        int firstQuote = firstThing < 0 ? -1 : facts.indexOf("('", firstThing);
        boolean creatureFirst = creatureAt >= 0 && firstQuote >= 0 && facts.startsWith("('" + ID + "'", firstQuote);
        boolean castleBehind = castleAt > creatureAt || (castleAt < 0 && facts.contains("more further off"));
        helper.assertTrue(creatureFirst && castleBehind,
                Component.literal("#948/#1340: the creature 5 blocks in front of him must be in his view, ahead of "
                        + "the building 15 blocks off, in the facts the agent reads: " + facts));
        tidy(helper);
        helper.succeed();
    }

    // ===================== the scene =====================

    private record Scene(Vec3 spawn, DisplayRig rig, Mob carrier, List<Mob> proxies,
                         LivingEntity real) {
    }

    /** Clear world spawn and stand up the creature and a real mob of its carrier's type. */
    private static Scene stage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().overworld();
        Vec3 spawn = clearWorldSpawn(helper, level);
        return standUp(helper, level, spawn);
    }

    private static Scene standUp(GameTestHelper helper, ServerLevel level, Vec3 spawn) {
        com.moddingfromamod.testing.SpeciesForTest.putSaved(helper, Species.fromJson(JsonParser.parseString(
                "{\"id\":\"" + ID + "\",\"name\":\"" + NAME + "\",\"modelKey\":\"" + ID + "\","
                        + "\"health\":40,\"speed\":0.0,\"scale\":1.0}").getAsJsonObject()));
        DisplayRig rig = DisplayRig.spawn(level, spawn.add(5, 0, 0), 0f,
                RigFitGameTest.whale(), 1.0, ID, CarrierPool.Move.WALK);
        rig.base().setNoAi(true);
        rig.base().addTag(MINE);
        helper.assertTrue(DisplayRig.speciesOn(rig.base()) != null,
                Component.literal("the carrier does not wear its species, so nothing could find it by name"));
        Entity made = rig.base().getType().create(level, EntitySpawnReason.COMMAND);
        helper.assertTrue(made instanceof LivingEntity, Component.literal("cannot make a real "
                + rig.base().getType()));
        LivingEntity real = (LivingEntity) made;
        real.snapTo(spawn.x - 3, spawn.y, spawn.z, 0f, 0f);
        if (real instanceof Mob m) {
            m.setNoAi(true);
        }
        real.addTag(MINE);
        level.addFreshEntity(real);
        return new Scene(spawn, rig, rig.base(), new ArrayList<>(rig.proxies()), real);
    }

    private static Vec3 clearWorldSpawn(GameTestHelper helper, ServerLevel level) {
        com.moddingfromamod.testing.MockPlayerNetwork.requireNobodyIsOnline(helper);
        // Tidied however the test ENDS, not only on its last line: a red or timed-out test left its creature at the
        // world spawn every test here shares, and the next one listed two of the same mob (2026-11-15 release smoke).
        com.moddingfromamod.testing.MockPlayerNetwork.afterTest(helper, () -> tidy(helper));
        // No conversation left over from an earlier test: a named batch runs alone.
        EntityHandles.global().clear();
        tidy(helper);
        Vec3 spawn = Vec3.atBottomCenterOf(level.getSharedSpawnPos());
        clearOtherCreatures(level, spawn);
        clearOtherBuildings(spawn);
        return spawn;
    }

    /**
     * <b>Every OTHER test's building near spawn goes too</b> — the same leftover as
     * {@link #clearOtherCreatures}, from the other store "in his view" reads. A building's record
     * outlives its test (WishVisitGameTest's castle never leaves the registry), so in a full
     * ci,default run one stood nearer the looker than the whale and was listed first, while the
     * test passed alone. Forgotten in memory only: nothing here may write a shelf.
     */
    private static void clearOtherBuildings(Vec3 spawn) {
        for (String id : BuildRegistry.ids()) {
            Long packed = BuildRegistry.anchorOf(id);
            if (packed != null && Vec3.atBottomCenterOf(BlockPos.of(packed)).distanceTo(spawn) < 64) {
                BuildRegistry.forgetInMemoryForProbe(id);
            }
        }
    }

    /**
     * <b>Every OTHER test's creature near spawn goes too.</b> These tests ask what is NEAREST the
     * child at spawn, and spawn is shared by every test in this reused world: in a full ci,default
     * run on 1.21.8 an earlier batch's 'Pet Dragon' ({@code gt_preview_edit_…}, left by
     * WishPreviewGameTest) stood 3 blocks from the looker, nearer than this test's own whale, and
     * the ladder test failed while passing alone. A named batch runs alone, so any creature here
     * now is a leftover, never a neighbour's live subject.
     */
    private static void clearOtherCreatures(ServerLevel level, Vec3 spawn) {
        List<Entity> stray = new ArrayList<>();
        for (Entity e : level.getEntities().getAll()) {
            if (e != null && e.position().distanceTo(spawn) < 64 && DisplayRig.speciesOn(e) != null) {
                stray.add(e);
            }
        }
        for (Entity e : stray) {
            DisplayRig.reap(level, e.getUUID());
            e.discard();
        }
    }

    /** Remove everything a run of this class stood up — by its own tag, nothing else. */
    private static void tidy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().overworld();
        List<Entity> doomed = new ArrayList<>();
        for (Entity e : level.getEntities().getAll()) {
            if (e.getTags().contains(MINE)
                    || e.getTags().contains(WishMobTags.forSpecies(ID))) {
                doomed.add(e);
            }
        }
        for (Entity e : doomed) {
            DisplayRig.reap(level, e.getUUID());
            e.discard();
        }
    }

    private static LivingEntity carrierOf(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().overworld();
        for (Entity e : level.getEntities().getAll()) {
            if (e instanceof LivingEntity l && e.isAlive()
                    && e.getTags().contains(WishMobTags.forSpecies(ID))
                    && e.getTags().contains(DisplayRig.RIG_TAG)) {
                return l;
            }
        }
        return null;
    }

    /** The oracle, from the tags alone: what a child could mean is every mob but a hit proxy. */
    private static int countMeaningful(ServerLevel level, Vec3 at, double r) {
        return level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(r),
                e -> e.isAlive() && !(e instanceof Player)
                        && !e.getTags().contains(HitProxies.PROXY_TAG)).size();
    }

    private static String section(String said) {
        int from = said.indexOf("MOBS");
        int to = said.indexOf("(These are the CURRENT", Math.max(from, 0));
        return from < 0 ? said : said.substring(from, to < 0 ? said.length() : to);
    }

    private static WishLadder.Candidate find(List<WishLadder.Candidate> all, String id) {
        return all.stream().filter(c -> id.equals(c.id())).findFirst().orElse(null);
    }

    private static String ids(List<WishLadder.Candidate> all) {
        return all.stream().map(c -> c.kind() + ":" + c.id() + "@" + Math.round(c.distance()))
                .toList().toString();
    }

    /** A player that is NOT in the player list (see MobIdentityGameTest), looking along +x. */
    private static ServerPlayer unplacedPlayer(ServerLevel level, Vec3 at) {
        var profile = new com.mojang.authlib.GameProfile(
                java.util.UUID.nameUUIDFromBytes("t6g1-looker".getBytes()), "t6g1-looker");
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        ServerPlayer p = new ServerPlayer(level.getServer(), level, profile,
                cookie.clientInformation());
        p.snapTo(at.x, at.y, at.z, -90f, 0f);
        return p;
    }

    private static ToolResult call(String json) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return new ModifyMobTool().call(args);
    }

    private static void offThread(Runnable work) {
        Thread t = new Thread(work, "t6g1-resolver-gametest");
        t.setDaemon(true);
        t.start();
    }
}
