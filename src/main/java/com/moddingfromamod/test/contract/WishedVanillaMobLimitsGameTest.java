package com.moddingfromamod.test.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.AgentContext;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.ModifyMobTool;
import com.moddingfromamod.agent.tools.RunCommandTool;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.WatchIcons;
import com.moddingfromamod.marshal.WatchPanel;
import com.moddingfromamod.plugin.Plugins;
import com.moddingfromamod.safety.SafetyGrader;
import com.moddingfromamod.safety.SafetyVerdict;
import com.moddingfromamod.testing.MockPlayerNetwork;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;
import com.moddingfromamod.wish.LearnedTemplates;
import com.moddingfromamod.wish.LiveFit;
import com.moddingfromamod.wish.MakeFactsLookup;
import com.moddingfromamod.wish.WishHotbar;
import com.moddingfromamod.wishbuilder.MakeFacts;
import com.moddingfromamod.world.WorldMakes;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * <b>#174: every route by which a wish makes a creature strong is held to the world's limits, through the real entry
 * points.</b> The playtest's Super Wolf was a learned recipe ({@code wishsummon minecraft:wolf {"damage":100,...}})
 * fired from the hotbar in an arena capped at 5; it stood at attack 100, and the world's refit counted "0 raised, 0
 * lowered, 0 as they were". Under {@link ContractWorld}'s limits (attack {@value ContractWorld#HARDEST}, health
 * {@value ContractWorld#TOUGHEST}):
 * <ul>
 *   <li>the HOTBAR FIRE of a learned recipe with that very {@code wishsummon}: the wolf stands at the limits, its own
 *       numbers underneath, the recipe reads attack 100 (and, following him, not as a monster), a refit counts it and
 *       a later round that lets health out raises it;</li>
 *   <li>the agent's real {@code run_command}: a {@code summon} whose NBT sets attack 100 is held; a WILD wolf it
 *       changes ({@code attribute … base set}, {@code effect give … strength}, a multiplying modifier) is held, but
 *       never below what a wolf is without a wish;</li>
 *   <li>a spawn egg the agent's {@code give} hands over hatches a wolf carrying the mark, and a later change no wish
 *       scope sees is caught by the watch;</li>
 *   <li>the agent's real {@code modify_mob} on a real wolf with damage 100: held, and said so.</li>
 * </ul>
 * Each in a batch of its own: every one replaces the shared world hook.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class WishedVanillaMobLimitsGameTest {

    private WishedVanillaMobLimitsGameTest() {
    }

    private static final double EPS = 1e-6;

    private static String superWolf(String name) {
        return "wishsummon minecraft:wolf {\"name\":\"" + name + "\",\"speed\":0.6,\"damage\":100,\"health\":100,"
                + "\"follow\":true,\"friendly\":true,\"base\":\"guardian\",\"persistent\":true}";
    }

    private static void inContractWorld(GameTestHelper helper, UUID owner, Runnable after) {
        Plugins mine = new Plugins();
        new ContractWorld().register(mine);
        Plugins.shared().replaceForTest(WorldMakes.class, mine.first(WorldMakes.class).orElseThrow());
        MockPlayerNetwork.afterTest(helper, () -> {
            Plugins.shared().replaceForTest(WorldMakes.class, null);
            ContractWorld.round(owner, 1);
            after.run();
        });
    }

    private static void check(GameTestHelper helper, boolean ok, String why) {
        helper.assertTrue(ok, Component.literal(why));
    }

    private static LivingEntity named(ServerPlayer p, String name) {
        for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, new AABB(p.blockPosition()).inflate(24),
                e -> e.getCustomName() != null && e.getCustomName().getString().equals(name))) {
            return e;
        }
        return null;
    }

    private static LivingEntity tagged(ServerLevel level, String tag) {
        for (Entity e : level.getAllEntities()) {
            if (e instanceof LivingEntity l && e.getTags().contains(tag) && e.isAlive()) {
                return l;
            }
        }
        return null;
    }

    private static void heldAtTheLimits(GameTestHelper helper, LivingEntity mob, String what) {
        double attack = mob.getAttributeValue(Attributes.ATTACK_DAMAGE);
        check(helper, attack <= ContractWorld.HARDEST + EPS,
                what + " hits for " + attack + " in a world capped at " + ContractWorld.HARDEST);
        check(helper, mob.getMaxHealth() <= ContractWorld.TOUGHEST + EPS,
                what + " has " + mob.getMaxHealth() + " health in a world capped at " + ContractWorld.TOUGHEST);
        check(helper, mob.getAttributeBaseValue(Attributes.ATTACK_DAMAGE) == 100.0,
                what + "'s own numbers underneath were changed: base attack "
                        + mob.getAttributeBaseValue(Attributes.ATTACK_DAMAGE));
    }

    // ── the hotbar fire of a learned recipe ───────────────────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = "contract_world_vanilla_fit_hotbar", timeoutTicks = 600)
    public static void aHotbarFireOfASuperWolfRecipeStandsAtTheLimitsAndARefitCountsIt(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "gtvanillafit");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        var server = helper.getLevel().getServer();
        UUID owner = p.getUUID();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        String wolfName = "Super Wolf " + tag;
        String recipe = "gt174_super_wolf_" + tag;
        SafetyGrader.forTest(t -> SafetyVerdict.fine(SafetyVerdict.Provenance.HAIKU));
        List<String> had = LearnedTemplates.savedJsonForTest();
        List<String> with = new ArrayList<>(had);
        JsonObject t = new JsonObject();
        t.addProperty("id", recipe);
        JsonArray all = new JsonArray();
        all.add("gt174superwolf" + tag);
        t.add("all", all);
        t.addProperty("ownerId", p.getStringUUID());
        JsonArray commands = new JsonArray();
        commands.add(superWolf(wolfName));
        t.add("commands", commands);
        with.add(t.toString());
        LearnedTemplates.restoreForTest(with, server);
        inContractWorld(helper, owner, () -> {
            SafetyGrader.forTest(null);
            LearnedTemplates.restoreForTest(had, server);
            for (String n : new String[]{wolfName}) {
                LivingEntity w = named(p, n);
                if (w != null) {
                    w.discard();
                }
            }
        });

        // what the replay gate reads now: the recipe's own numbers (#174 B1, B2), not a cautious "other"
        MakeFacts facts = MakeFactsLookup.forRecipe(server, owner, recipe);
        check(helper, facts.known() && facts.number(MakeFacts.ATTACK_DAMAGE_HALF_HEARTS).orElse(0) >= 100,
                "the recipe does not read as attack 100: " + facts);
        check(helper, !facts.has(MakeFacts.HOSTILE), "a wolf summoned to follow him is not rated a monster: " + facts);

        p.getInventory().clearContent();
        var proxy = WishHotbar.proxy(new WatchPanel.Cell(0, WatchPanel.Family.CREATION, WatchPanel.Action.CARD,
                wolfName, wolfName, recipe, wolfName, WatchIcons.UNKNOWN, List.of()), owner);
        p.getInventory().setItem(0, proxy);
        WishHotbar.Use use = WishHotbar.use(p, p.getInventory().getItem(0));
        check(helper, use.used() == WishHotbar.Used.WISHED, "the hotbar fire did not wish: " + use);

        helper.succeedWhen(() -> {
            LivingEntity wolf = named(p, wolfName);
            check(helper, wolf != null, "waiting for the Super Wolf");
            heldAtTheLimits(helper, wolf, "the hotbar's Super Wolf");
            check(helper, owner.equals(LiveFit.fitOwnerOf(wolf)),
                    "the wolf does not remember whose limits hold it: " + wolf.getTags());

            // a round that lets health out further: the refit must SEE both wolves and raise them
            ContractWorld.round(owner, 2);
            LiveFit.Refit r = LiveFit.refit(server, owner);
            check(helper, r.raised() + r.lowered() + r.same() >= 1, "the refit did not count the wished wolf: " + r);
            check(helper, wolf.getMaxHealth() > ContractWorld.TOUGHEST
                            && wolf.getMaxHealth() <= ContractWorld.TOUGHEST_LATER + EPS,
                    "after the limit rose to " + ContractWorld.TOUGHEST_LATER + " the Super Wolf has " + wolf.getMaxHealth());
            check(helper, wolf.getAttributeValue(Attributes.ATTACK_DAMAGE) <= ContractWorld.HARDEST + EPS,
                    "the refit let the Super Wolf's attack out past the limit");
        });
    }

    // ── the agent's run_command on a wild wolf ────────────────────────────────────────────────────────────────────

    /** Run {@code body} as a wish of {@code child}'s, off the server thread (the tools hop onto it). */
    private static void asWish(ServerPlayer child, String tag, Runnable body, AtomicReference<Throwable> err,
                               AtomicReference<Boolean> done) {
        UUID wish = UUID.randomUUID();
        String worker = "gt-vanillafit-" + tag + "-" + wish;
        Marshal.global().submit(wish, child.getUUID(), "gt vanilla fit " + tag);
        Marshal.global().assign(worker, wish);
        Thread th = new Thread(() -> {
            AgentContext.bind(worker);
            try {
                body.run();
            } catch (Throwable e) {
                err.set(e);
            } finally {
                AgentContext.unbind();
                Marshal.global().finish(wish);
                done.set(true);
            }
        }, "gametest-vanillafit-" + tag);
        th.setDaemon(true);
        th.start();
    }

    private static JsonObject commands(String... cs) {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        for (String c : cs) {
            a.add(c);
        }
        o.add("command", a);
        return o;
    }

    private static LivingEntity wildWolf(GameTestHelper helper, Vec3 at, String tag) {
        ServerLevel level = helper.getLevel();
        LivingEntity wolf = EntityType.WOLF.create(level, EntitySpawnReason.COMMAND);
        wolf.snapTo(at.x, at.y, at.z, 0, 0);
        wolf.addTag(tag);
        level.addFreshEntity(wolf);   // no wish scope: the world's own wolf
        return wolf;
    }

    @GameTest(template = "empty_test", batch = "contract_world_vanilla_fit_command", timeoutTicks = 600)
    public static void aWildWolfAWishCommandMakesStrongIsHeldButNeverBelowItsOwnKind(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "gtvanillafitcmd");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        String tag = "gt174wild" + UUID.randomUUID().toString().substring(0, 6);
        String strong = tag + "s";
        LivingEntity wolf = wildWolf(helper, p.position().add(2, 0, 0), tag);
        LivingEntity other = wildWolf(helper, p.position().add(-2, 0, 0), strong);
        double ownAttack = wolf.getAttributeValue(Attributes.ATTACK_DAMAGE);
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        AtomicReference<ToolResult> out = new AtomicReference<>();
        inContractWorld(helper, p.getUUID(), () -> {
            wolf.discard();
            other.discard();
            LivingEntity nbt = tagged(helper.getLevel(), tag + "n");
            if (nbt != null) {
                nbt.discard();
            }
        });
        check(helper, !LiveFit.markedByTags(wolf.getTags()), "the world's own wolf starts unmarked: " + wolf.getTags());

        String nbtTag = tag + "n";
        asWish(p, "cmd", () -> out.set(new RunCommandTool().call(commands(
                "summon minecraft:wolf ~1 ~ ~ {Tags:[\"" + nbtTag + "\"],attributes:[{id:\"minecraft:attack_damage\","
                        + "base:100},{id:\"minecraft:max_health\",base:200}]}",
                "attribute @e[tag=" + tag + ",limit=1] minecraft:attack_damage base set 100",
                "attribute @e[tag=" + tag + ",limit=1] minecraft:max_health base set 200",
                "effect give @e[tag=" + strong + ",limit=1] minecraft:strength 60 9",
                "attribute @e[tag=" + strong + ",limit=1] minecraft:armor modifier add gt174:shell 10 "
                        + "add_multiplied_total"))), err, done);

        helper.succeedWhen(() -> {
            check(helper, err.get() == null, "run_command threw: " + err.get());
            check(helper, Boolean.TRUE.equals(done.get()), "waiting for run_command");
            check(helper, out.get() != null && !out.get().isError(), "run_command failed: "
                    + (out.get() == null ? "nothing" : out.get().text()));
            double attack = wolf.getAttributeValue(Attributes.ATTACK_DAMAGE);
            check(helper, attack <= ContractWorld.HARDEST + EPS,
                    "an attribute command left the wild wolf at attack " + attack);
            check(helper, attack >= Math.min(ownAttack, ContractWorld.HARDEST) - EPS,
                    "the wolf was held below its own kind: " + attack + " < " + ownAttack);
            check(helper, wolf.getMaxHealth() <= ContractWorld.TOUGHEST + EPS,
                    "an attribute command left the wild wolf at " + wolf.getMaxHealth() + " health");
            check(helper, wolf.getAttributeBaseValue(Attributes.ATTACK_DAMAGE) == 100.0,
                    "its base was rewritten instead of held: " + wolf.getAttributeBaseValue(Attributes.ATTACK_DAMAGE));
            check(helper, wolf.getTags().contains(LiveFit.TOUCHED_TAG) && p.getUUID().equals(LiveFit.fitOwnerOf(wolf)),
                    "the changed wolf is not marked as held for its wisher: " + wolf.getTags());
            LivingEntity nbt = tagged(helper.getLevel(), nbtTag);
            check(helper, nbt != null, "the summon with NBT attributes made nothing");
            heldAtTheLimits(helper, nbt, "a summon whose NBT set attack 100");
            check(helper, !nbt.getTags().contains(LiveFit.TOUCHED_TAG) && p.getUUID().equals(LiveFit.fitOwnerOf(nbt)),
                    "the summoned wolf is a wish's own, held for its wisher: " + nbt.getTags());
            double strongAttack = other.getAttributeValue(Attributes.ATTACK_DAMAGE);
            check(helper, strongAttack <= ContractWorld.HARDEST + EPS,
                    "Strength X left the other wolf at attack " + strongAttack);
            double armor = other.getAttributeValue(Attributes.ARMOR);
            check(helper, armor <= ContractWorld.HARDEST_SHELL + EPS,
                    "a multiplying armour modifier left it at armour " + armor);
        });
    }

    // ── a spawn egg a wish hands over, and a later change the watch catches ──────────────────────────────────────

    @GameTest(template = "empty_test", batch = "contract_world_vanilla_fit_egg", timeoutTicks = 600)
    public static void aWishedSpawnEggHatchesAHeldMobAndTheWatchKeepsItHeld(GameTestHelper helper) {
        MockPlayerNetwork.clearStaleMocks(helper);
        ServerPlayer p = MockPlayerNetwork.inLevel(helper, "gtvanillafitegg");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        p.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.getInventory().clearContent();
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        AtomicReference<ToolResult> out = new AtomicReference<>();
        AtomicReference<LivingEntity> hatched = new AtomicReference<>();
        inContractWorld(helper, p.getUUID(), () -> {
            if (hatched.get() != null) {
                hatched.get().discard();
            }
        });
        asWish(p, "egg", () -> out.set(new RunCommandTool().call(commands("give @s minecraft:wolf_spawn_egg 1"))),
                err, done);

        helper.succeedWhen(() -> {
            check(helper, err.get() == null, "run_command threw: " + err.get());
            check(helper, Boolean.TRUE.equals(done.get()) && out.get() != null && !out.get().isError(),
                    "waiting for the give: " + (out.get() == null ? "nothing" : out.get().text()));
            if (hatched.get() == null) {
                net.minecraft.world.item.ItemStack egg = net.minecraft.world.item.ItemStack.EMPTY;
                for (net.minecraft.world.item.ItemStack s : p.getInventory().getNonEquipmentItems()) {
                    if (s.is(net.minecraft.world.item.Items.WOLF_SPAWN_EGG)) {
                        egg = s;
                    }
                }
                check(helper, !egg.isEmpty(), "the egg did not arrive in his inventory");
                var data = egg.get(net.minecraft.core.component.DataComponents.ENTITY_DATA);
                check(helper, data != null && data.copyTagWithoutId().toString().contains(LiveFit.HELD_TAG),
                        "the egg a wish handed over does not say what it hatches is held: " + data);
                // hatched as the game hatches an egg: the egg's entity data put on the mob
                LivingEntity wolf = EntityType.WOLF.spawn(helper.getLevel(), egg, p, p.blockPosition().east(2),
                        EntitySpawnReason.SPAWN_ITEM_USE, true, false);
                check(helper, wolf != null, "the egg hatched nothing");
                hatched.set(wolf);
                check(helper, wolf.getTags().contains(LiveFit.HELD_TAG) && p.getUUID().equals(LiveFit.fitOwnerOf(wolf)),
                        "the hatched wolf does not carry the egg's mark: " + wolf.getTags());
                // a change no wish scope sees (a grown-up's own command, a held weapon): the watch catches it
                wolf.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(100.0);
                wolf.getAttribute(Attributes.MAX_HEALTH).setBaseValue(300.0);
            }
            heldAtTheLimits(helper, hatched.get(), "the egg's wolf");
        });
    }

    // ── what the watch costs: the index, never every entity ──────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = "contract_world_vanilla_fit_cost", timeoutTicks = 600)
    public static void theWatchReadsTwoHundredHeldBodiesAndNotTheOthers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(1, 2, 1));
        String tag = "gt174cost" + UUID.randomUUID().toString().substring(0, 6);
        List<LivingEntity> held = new ArrayList<>();
        List<LivingEntity> plain = new ArrayList<>();
        inContractWorld(helper, new UUID(0L, 0L), () -> {
            held.forEach(Entity::discard);
            plain.forEach(Entity::discard);
        });
        for (int i = 0; i < 400; i++) {
            LivingEntity wolf = EntityType.WOLF.create(level, EntitySpawnReason.COMMAND);
            wolf.snapTo(base.getX() + (i % 20) * 0.5, base.getY(), base.getZ() + (i / 20) * 0.5, 0, 0);
            ((net.minecraft.world.entity.Mob) wolf).setNoAi(true);
            wolf.addTag(tag);
            if (i % 2 == 0) {
                wolf.addTag(LiveFit.HELD_TAG);   // as a wished egg's hatchling carries it
                wolf.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(100.0);
                held.add(wolf);
            } else {
                plain.add(wolf);
            }
            level.addFreshEntity(wolf);
        }
        var server = level.getServer();
        long t0 = System.nanoTime();
        int fitted = com.moddingfromamod.wish.LiveFitWatch.look(server);
        long first = System.nanoTime() - t0;
        t0 = System.nanoTime();
        com.moddingfromamod.wish.LiveFitWatch.look(server);
        long steady = System.nanoTime() - t0;
        int read = com.moddingfromamod.wish.LiveFitWatch.lastRead();
        org.slf4j.LoggerFactory.getLogger(ModdingFromAModMod.MODID).info(
                "live fit watch cost: {} held + {} plain wolves; first look fitted {} in {} µs, a steady look read {} "
                        + "bodies in {} µs", held.size(), plain.size(), fitted, first / 1000, read, steady / 1000);
        for (LivingEntity w : held) {
            check(helper, com.moddingfromamod.wish.LiveFitWatch.indexes(w.getUUID()), "a held wolf is not indexed");
            check(helper, w.getAttributeValue(Attributes.ATTACK_DAMAGE) <= ContractWorld.HARDEST + EPS,
                    "a held wolf was not fitted by the look: " + w.getAttributeValue(Attributes.ATTACK_DAMAGE));
        }
        for (LivingEntity w : plain) {
            check(helper, !com.moddingfromamod.wish.LiveFitWatch.indexes(w.getUUID()), "an unmarked wolf is indexed");
        }
        check(helper, fitted >= held.size(), "the first look fitted " + fitted + " of " + held.size());
        check(helper, read >= held.size() && read == com.moddingfromamod.wish.LiveFitWatch.watched(),
                "a look read " + read + " bodies; the index holds " + com.moddingfromamod.wish.LiveFitWatch.watched());
        check(helper, read < held.size() + plain.size(), "a look read every wolf (" + read + "), not the index");
        helper.succeed();
    }

    // ── the agent's modify_mob on a real wolf ─────────────────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = "contract_world_vanilla_fit_modify", timeoutTicks = 600)
    public static void modifyMobOnARealWolfIsHeldAndSaysSo(GameTestHelper helper) {
        Vec3 child = com.moddingfromamod.testing.PlotChild.standHere(helper);
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        Vec3 at = child.add(3, 0, 0);
        for (LivingEntity stale : overworld.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(64),
                e -> e.getType() == EntityType.WOLF)) {
            stale.discard();
        }
        com.moddingfromamod.mob.StaleHandles.forgetEvery(EntityType.WOLF);
        LivingEntity wolf = EntityType.WOLF.create(overworld, EntitySpawnReason.COMMAND);
        wolf.snapTo(at.x, at.y, at.z, 0, 0);
        overworld.addFreshEntity(wolf);
        inContractWorld(helper, new UUID(0L, 0L), wolf::discard);

        AtomicReference<ToolResult> reply = new AtomicReference<>();
        Thread th = new Thread(() -> reply.set(new ModifyMobTool().call(JsonParser.parseString(
                "{\"scope\":\"one\",\"target\":\"wolf\",\"damage\":100,\"health\":300,\"name\":\"Mighty Wolf\"}")
                .getAsJsonObject())), "gametest-vanillafit-modify");
        th.setDaemon(true);
        th.start();

        helper.succeedWhen(() -> {
            ToolResult r = reply.get();
            check(helper, r != null, "waiting for modify_mob");
            check(helper, !r.isError(), "modify_mob failed: " + r.text());
            heldAtTheLimits(helper, wolf, "modify_mob's wolf");
            check(helper, r.text().contains("held to this world's limits"), "modify_mob did not say so: " + r.text());
        });
    }
}
