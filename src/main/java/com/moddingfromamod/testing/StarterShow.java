package com.moddingfromamod.testing;

import com.moddingfromamod.film.CameraPath;
import com.moddingfromamod.film.FilmCamera;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.wish.WishCatalog;
import com.moddingfromamod.wish.WishExecutor;
import com.moddingfromamod.wish.WishTemplates;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>A starter wish, SHOWN</b> ({@code -Dmoddingfromamod.startershow=<starter id>}): the film
 * for the site of a starter that is not a creature. A creature shows itself (the behaviour
 * exercise, {@link BehaviourExercise}); a sword, a pair of boots, a machine or a lucky block does
 * nothing until somebody USES it, and a castle or a picture is a thing that appears. So: on a
 * meadow, a stand-in child is served the starter's OWN commands exactly as his button runs them
 * ({@link WishExecutor#runOne}), then does what the card promises — swings the sword at monsters,
 * wears the boots and jumps, loads the slushie machine and pulls its handle, places lucky blocks and
 * breaks them — while a real stock client, the ONLOOKER, watches from where the move reads and
 * records it (tools/film-starters.sh). Nothing is faked: every effect is the starter's own datapack
 * or build, fired by the same action a child would take.
 *
 * <p>Entirely inert without the property.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class StarterShow {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);
    private static final String TAG = "[show]";
    /**
     * Ticks after the onlooker joins before anything happens: his client loads the world and then the
     * item pack the server pushes, and the first sword film opened on the Mojang loading screen
     * after five seconds.
     */
    private static final int SETTLE = 300;

    private StarterShow() {
    }

    static String which() {
        return System.getProperty(ModdingFromAModMod.MODID + ".startershow", "");
    }

    private static int tick = -1;
    private static boolean done;
    private static ServerLevel level;
    private static ServerPlayer kid;
    private static Vec3 centre;
    private static final List<Mob> props = new ArrayList<>();
    private static final Map<String, Object> state = new HashMap<>();
    /** The blocks the wish put down, found by difference with a snapshot taken just before. */
    private static Map<BlockPos, BlockState> before;
    private static AABB made;
    private static java.util.Set<String> picturesBefore = java.util.Set.of();
    private static java.util.Set<UUID> entitiesBefore = java.util.Set.of();

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (which().isEmpty() || done) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerPlayer eye = server.getPlayerList().getPlayerByName(FilmOnlooker.name().isEmpty()
                ? "Steve" : FilmOnlooker.name());
        if (eye == null) {
            return;
        }
        tick++;
        try {
            if (tick == SETTLE) {
                setUp(server, eye);
            } else if (tick > SETTLE) {
                int t = tick - SETTLE;
                if (!run(server, t)) {
                    LOGGER.info("{} done — {}", TAG, which());
                    props.forEach(Entity::discard);
                    MockPlayerNetwork.remove(kid);
                    // The camera is NOT handed back: the recorder stops a moment after "done", and
                    // giving him his own eyes there put a 15-pixel lurch at the end of every take
                    // (tools/camera-shake.py). The server is stopped next anyway.
                    done = true;
                    return;
                }
                camera(eye, t);
            }
        } catch (RuntimeException e) {
            LOGGER.error("{} {} threw at t={}: {}", TAG, which(), tick - SETTLE, e.toString(), e);
            done = true;
        }
    }

    // ── the scene ───────────────────────────────────────────────────────────────────────────────

    private static void setUp(MinecraftServer server, ServerPlayer eye) {
        level = server.overworld();
        BlockPos spawn = level.getRespawnData().pos();
        BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn);
        BehaviourExercise.arena(level, ground);
        BehaviourExercise.meadow(level, ground);
        centre = Vec3.atBottomCenterOf(ground);
        var rules = level.getGameRules();
        rules.set(GameRules.SPAWN_MOBS,false, server);
        rules.set(GameRules.ADVANCE_TIME,false, server);
        rules.set(GameRules.ADVANCE_WEATHER,false, server);
        // The sword's lightning lights the grass it strikes, as it does in any world; spreading, the
        // fire ran across the meadow and the frame was flames by the third hit.
        rules.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0, server);   // doFireTick false (Mojang's own fix)
        com.moddingfromamod.mv.DayTime.set(level, 6000);
        com.moddingfromamod.mv.DayTime.weather(level, 12000, 0, false, false);
        for (Entity e : level.getEntities((Entity) null, new AABB(ground).inflate(64),
                x -> x instanceof net.minecraft.world.entity.LivingEntity && !(x instanceof net.minecraft.world.entity.player.Player))) {
            e.discard();
        }
        eye.setGameMode(GameType.CREATIVE);
        eye.getAbilities().flying = true;
        eye.onUpdateAbilities();
        kid = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inServer(level, "Wisher",
                UUID.nameUUIDFromBytes("moddingfromamod-starter-show".getBytes()), false));
        kid.setPermanentlyInvulnerable(true);
        kid.getInventory().clearContent();
        kid.snapTo(centre.x, centre.y, centre.z, 0f, 0f);   // facing south, +z: where the wish appears
        LOGGER.info("{} showing {} at {}", TAG, which(), ground.toShortString());
    }

    /** The starter's own commands, as his button runs them; the blocks they put down are found after. */
    private static void serve() {
        WishTemplates.Known starter = WishCatalog.templates().known().stream()
                .filter(k -> k.id().equals(which())).findFirst().orElse(null);
        if (starter == null) {
            throw new IllegalStateException("no starter " + which());
        }
        var m = WishCatalog.templates().matchConfident(starter.phrase());
        if (m.isEmpty() || !m.get().templateId().equals(which())) {
            throw new IllegalStateException("its own words were served by " + m.map(x -> x.templateId()).orElse("nothing"));
        }
        before = snapshot();
        picturesBefore = new java.util.HashSet<>(com.moddingfromamod.paint.PaintRegistry.ids());
        entitiesBefore = new java.util.HashSet<>();
        for (Entity e : level.getEntities((Entity) null, new AABB(BlockPos.containing(centre)).inflate(SCAN + 8), x -> true)) {
            entitiesBefore.add(e.getUUID());
        }
        for (String cmd : m.get().plan().commands()) {
            var r = WishExecutor.runOne(level.getServer(), level, kid.position(), kid, cmd);
            LOGGER.info("{} `{}` -> {}{}", TAG, cmd, r.success() ? "" : "FAILED ", r.joined());
        }
    }

    private static final int SCAN = 40;
    /** How high the snapshot reaches: 30 stopped under the castle's roofs, which stayed up when it was taken away. */
    private static final int SCAN_UP = 48;

    private static Map<BlockPos, BlockState> snapshot() {
        Map<BlockPos, BlockState> map = new HashMap<>();
        BlockPos c = BlockPos.containing(centre);
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-SCAN, -3, -SCAN), c.offset(SCAN, SCAN_UP, SCAN))) {
            map.put(p.immutable(), level.getBlockState(p));
        }
        return map;
    }

    /** The box round every block that is not what it was before the wish. */
    private static AABB changed() {
        BlockPos c = BlockPos.containing(centre);
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-SCAN, -3, -SCAN), c.offset(SCAN, SCAN_UP, SCAN))) {
            BlockState now = level.getBlockState(p);
            BlockState was = before.get(p);
            // Only what the wish PUT DOWN: the meadow's own leaves decay to air while the show runs,
            // and counting them stretched the box to the whole scan, so the camera framed the sky
            // from 60 blocks out (the first castle and creeper films).
            // (A block put down OVER a leaf still counts: the castle stands on some of the trees.)
            if (now != was && !now.isAir() && !now.is(net.minecraft.tags.BlockTags.LEAVES)) {
                x0 = Math.min(x0, p.getX()); y0 = Math.min(y0, p.getY()); z0 = Math.min(z0, p.getZ());
                x1 = Math.max(x1, p.getX()); y1 = Math.max(y1, p.getY()); z1 = Math.max(z1, p.getZ());
            }
        }
        return x0 == Integer.MAX_VALUE ? null : new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
    }

    // ── what each starter does ──────────────────────────────────────────────────────────────────

    /** One tick of the show; false once it is over. */
    private static boolean run(MinecraftServer server, int t) {
        String id = which();
        if (t == 1 && !id.startsWith(CLASS)) {
            serve();
        }
        return switch (id) {
            case "class_rule" -> rule(t);
            case "class_datapack" -> datapack(t);
            case "starter_lightning_sword" -> sword(t);
            case "starter_jump_boots" -> boots(t);
            case "starter_slushie_machine" -> slushie(t);
            case "starter_lucky_block" -> lucky(t);
            default -> appears(t);   // a building or a picture: it appears, and is looked at
        };
    }

    // ── the two wish classes no starter covers (a film of every wish class on 1.21.8) ──────

    /** A show id that is a wish CLASS rather than a starter: granted through its real tool, not a button. */
    static final String CLASS = "class_";

    /** What the tool said, once the thread calling it returns. */
    private static final java.util.concurrent.atomic.AtomicReference<String> REPLY =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * The real tool, as the agent calls it: from its OWN thread, because a tool hops to the server
     * thread and waits, and this runs on it.
     */
    private static void callTool(com.moddingfromamod.agent.McpTool tool, com.google.gson.JsonObject args) {
        REPLY.set(null);
        Thread th = new Thread(() -> {
            String said;
            try {
                var r = tool.call(args);
                said = (r.isError() ? "ERROR " : "") + r.text();
            } catch (Throwable e) {
                said = "ERROR threw " + e;
            }
            LOGGER.info("{} {} said: {}", TAG, tool.name(), said.replace('\n', ' '));
            REPLY.set(said);
        }, "startershow-tool");
        th.setDaemon(true);
        th.start();
    }

    /**
     * A RULE (set_rule): "when a pig lands, it bounces back up". Three pigs are dropped in front of him
     * once the rule is set; with it they never stop bouncing, which a one-off command cannot do — the
     * same form WorldRulesGameTest.aLandingPigIsPushedBackUp proves headless.
     */
    private static boolean rule(int t) {
        if (t == 1) {
            callTool(new com.moddingfromamod.agent.tools.SetRuleTool(), com.google.gson.JsonParser.parseString(
                    "{\"when\":\"lands\",\"who\":\"pig\",\"name\":\"bouncy_pigs\",\"push\":{\"up\":0.8},"
                    + "\"do\":[\"particle minecraft:happy_villager ~ ~0.5 ~ 0.4 0.2 0.4 0 6\"]}").getAsJsonObject());
        }
        String said = REPLY.get();
        if (said != null && said.startsWith("ERROR")) {
            throw new IllegalStateException("set_rule refused the rule: " + said);
        }
        if (said != null && (t == 60 || t == 90 || t == 120)) {
            int k = (t - 60) / 30;
            var pig = EntityTypes.PIG.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            pig.snapTo(centre.x - 2 + 2 * k, centre.y + 5, centre.z + 4, 0f, 0f);
            level.addFreshEntity(pig);
            props.add(pig);
        }
        if (t == 299) {
            int up = 0;
            for (Mob m : props) {
                up += m.onGround() ? 0 : 1;
            }
            LOGGER.info("{} pigs in the air at the end: {} of {}", TAG, up, props.size());
        }
        return t < 300;
    }

    /**
     * A DATAPACK (author_datapack): "every husk is twice as big, forever" — a standing rule written in
     * CBScript, compiled and loaded. A husk is walked in BEFORE it (normal size) and two more AFTER it:
     * the new ones grow too, which is what tells a standing rule from a one-off command
     * (MobRuleClientTest's whole point). Husks, not zombies: a zombie burns in the meadow's daylight.
     */
    private static boolean datapack(int t) {
        if (t == 1) {
            husk(centre.x - 2.5);
        }
        // Late enough that the recording (which starts ~3 s in) has the husk at its own size first.
        if (t == 130) {
            com.google.gson.JsonObject pack = new com.google.gson.JsonObject();
            pack.addProperty("feature_id", "show_big_husks");
            pack.addProperty("description", "every husk is twice as big, forever");
            pack.addProperty("cbscript", """
                    clock tick
                        as @e[type=husk, tag=!show_big] at @s
                            /tag @s add show_big
                            /attribute @s minecraft:scale base set 2.0
                            /particle minecraft:happy_villager ~ ~1 ~ 0.5 1 0.5 0 20
                        end
                    end
                    """);
            callTool(new com.moddingfromamod.agent.tools.AuthorDatapackTool(), pack);
        }
        String said = REPLY.get();
        if (said != null && said.startsWith("ERROR")) {
            throw new IllegalStateException("author_datapack refused the pack: " + said);
        }
        if (said != null && state.putIfAbsent("loadedAt", t) == null) {
            LOGGER.info("{} the pack is in at t={}", TAG, t);
        }
        Integer at = (Integer) state.get("loadedAt");
        if (at != null && (t == at + 60 || t == at + 110)) {
            husk(centre.x + (t == at + 60 ? 0 : 2.5));
        }
        if (at != null && t == at + 200) {
            StringBuilder sizes = new StringBuilder();
            for (Mob m : props) {
                sizes.append(String.format(" %.1f", m.getScale()));
            }
            LOGGER.info("{} husk sizes at the end:{}", TAG, sizes);
            return false;
        }
        return at == null ? t < 2400 : true;
    }

    /** A husk standing in front of him, turned to face the camera (a no-AI mob never turns its body). */
    private static void husk(double x) {
        var husk = EntityTypes.HUSK.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        husk.snapTo(x, centre.y, centre.z + 5, 200f, 0f);
        husk.setYBodyRot(200f);
        husk.setYHeadRot(200f);
        husk.setNoAi(true);
        level.addFreshEntity(husk);
        props.add(husk);
    }

    private static ItemStack carried(String name) {
        for (int i = 0; i < kid.getInventory().getContainerSize(); i++) {
            ItemStack s = kid.getInventory().getItem(i);
            if (!s.isEmpty() && s.getHoverName().getString().equals(name)) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Put {@code stack} in his hand the way a child does: by SELECTING a hotbar slot holding it. Setting
     * the hand outright wrote over the slot the watch is pinned to, and the watch came back before
     * the swing — the first sword film was three hits with Moddy (WatchPin keeps the watch in its slot).
     */
    private static void hold(ItemStack stack) {
        int watch = -1;
        for (int i = 0; i < 9; i++) {
            if (kid.getInventory().getItem(i).getHoverName().getString().equals("Moddy")) {
                watch = i;
            }
        }
        int slot = watch == 4 ? 5 : 4;
        kid.getInventory().setItem(slot, stack);
        kid.getInventory().setSelectedSlot(slot);
        shown(EquipmentSlot.MAINHAND, stack);
    }

    /**
     * Tell the onlooker what he now holds or wears. A real player's equipment reaches the others from
     * his own tick; a mock player is never ticked, so the first slushie film poured a slushie he was
     * never seen holding.
     */
    private static void shown(EquipmentSlot slot, ItemStack stack) {
        level.getChunkSource().sendToTrackingPlayers(kid, new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(
                kid.getId(), List.of(com.mojang.datafixers.util.Pair.of(slot, stack.copy()))));
    }

    private static Mob monster(double dx, double dz) {
        Mob m = EntityTypes.HUSK.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        m.snapTo(centre.x + dx, centre.y, centre.z + dz, 180f, 0f);
        m.setNoAi(true);
        m.setPersistenceRequired();
        // The first strike's fire spread to all three and burned them out before the third swing,
        // which then struck empty grass: they stand (burning) until each is hit.
        m.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE,
                -1, 0, false, false));
        level.addFreshEntity(m);
        props.add(m);
        return m;
    }

    private static final int SWING_AT = 110;

    private static boolean sword(int t) {
        if (t == 20) {
            hold(carried("Lightning Sword").copy());
            // Spaced so the line from the camera (behind his right shoulder) to each one clears him: in
            // the first over-the-shoulder take the third husk stood behind his head and its bolt was hidden.
            monster(-2.0, 2.4);
            monster(-0.4, 2.8);
            monster(1.2, 2.4);
        }
        // He turns to each husk, swings and hits: the starter's on_hit lightning comes down on it.
        // The first swing waits for the recording: it starts ~3 s into the show, and the first take
        // opened on a bolt already falling.
        int k = (t - SWING_AT) / 70;
        if (t >= SWING_AT && k < props.size() && (t - SWING_AT) % 70 == 0) {
            Mob m = props.get(k);
            kid.lookAt(EntityAnchorArgument.Anchor.EYES, m.getEyePosition());
            com.moddingfromamod.compat.Swings.swing(kid, InteractionHand.MAIN_HAND, true);
            kid.attack(m);
            String fn = com.moddingfromamod.item.WishItem.onHit(kid.getMainHandItem());
            var rl = net.minecraft.resources.Identifier.tryParse(fn);
            LOGGER.info("{} hit {} holding '{}' on_hit '{}' function known: {}", TAG, m.getType().toShortString(),
                    kid.getMainHandItem().getHoverName().getString(), fn,
                    rl != null && level.getServer().getFunctions().get(rl).isPresent());
        }
        if (t >= SWING_AT && (t - SWING_AT) % 70 == 4) {
            LOGGER.info("{} bolts near the husks: {}", TAG, level.getEntitiesOfClass(net.minecraft.world.entity.LightningBolt.class,
                    new AABB(BlockPos.containing(centre)).inflate(10)).size());
        }
        return t < SWING_AT + 250;
    }

    private static boolean boots(int t) {
        if (t == 80) {
            ItemStack b = carried("Double Jump Boots");
            kid.setItemSlot(EquipmentSlot.FEET, b.copy());
            shown(EquipmentSlot.FEET, b);
            b.setCount(0);
        }
        // A plain jump first, without them, so the boots' jump has something to be higher than; then
        // he puts them on and their datapack gives him its jump boost (and a puff) and he jumps three
        // times. A mock player has no client to move it, so the jump is vanilla's own arc, driven
        // here: v0 = 0.42 + 0.1 * (boost level), then (v - 0.08) * 0.98 a tick.
        if (t == 30 || t == 110 || t == 160 || t == 210) {
            var boost = kid.getEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST);
            double v0 = 0.42 + (boost == null ? 0 : 0.1 * (boost.getAmplifier() + 1));
            state.put("vy", v0);
            LOGGER.info("{} jumps {} boots, boost {}", TAG, kid.getItemBySlot(EquipmentSlot.FEET).isEmpty() ? "without" : "in",
                    boost == null ? "none" : boost.getAmplifier() + 1);
        }
        Double vy = (Double) state.get("vy");
        if (vy != null) {
            double y = kid.getY() + vy;
            if (y <= centre.y) {
                y = centre.y;
                state.remove("vy");
            } else {
                state.put("vy", (vy - 0.08) * 0.98);
            }
            kid.teleportTo(centre.x, y, centre.z);
            state.merge("top", y - centre.y, (a, b) -> Math.max((Double) a, (Double) b));
        }
        if (t == 259) {
            LOGGER.info("{} highest jump {} blocks", TAG, state.get("top"));
        }
        return t < 260;
    }

    private static BlockPos front(double dz) {
        return BlockPos.containing(centre.x, centre.y - 1, centre.z + dz);
    }

    private static void useOn(BlockPos target, Direction face) {
        com.moddingfromamod.compat.Swings.swing(kid, InteractionHand.MAIN_HAND, true);
        kid.gameMode.useItemOn(kid, level, kid.getMainHandItem(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(target).add(0, 0.5, 0), face, target, false));
    }

    private static boolean slushie(int t) {
        if (t == 20) {
            BlockPos floor = front(3);
            kid.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(floor.above()));
            hold(carried("Slushie Machine").copyWithCount(1));
            useOn(floor, Direction.UP);
        }
        if (t == 40) {
            kid.teleportTo(centre.x, centre.y, centre.z + 1.2);   // up to the counter, for a closer shot
        }
        if (t == 85 || t == 135 || t == 185) {
            LOGGER.info("{} after the pull: lever {} slushie in hand-or-bag: {}", TAG, state.get("lever"),
                    !carried("Big Slushie with Sugar").isEmpty());
        }
        if (t == 200 && !carried("Big Slushie with Sugar").isEmpty()) {
            hold(carried("Big Slushie with Sugar").copy());   // he holds it up: it glints
            kid.lookAt(EntityAnchorArgument.Anchor.EYES, centre.add(3, 1.6, 1.2));
        }
        BlockPos lever = (BlockPos) state.get("lever");
        if (lever == null && t > 20) {
            BlockPos at = front(3);
            for (BlockPos q : BlockPos.betweenClosed(at.offset(-2, 0, -2), at.offset(2, 4, 2))) {
                if (level.getBlockState(q).is(Blocks.LEVER)) {
                    state.put("lever", q.immutable());
                    lever = q.immutable();
                    break;
                }
            }
        }
        if (lever != null) {
            // Ice, then sugar, then the pull that pours: the machine's own load-and-pour.
            if (t == 80) {
                hold(new ItemStack(Items.ICE, 3));
                pull(lever);
            } else if (t == 130) {
                hold(new ItemStack(Items.SUGAR, 1));
                pull(lever);
            } else if (t == 180) {
                hold(ItemStack.EMPTY);
                pull(lever);
            }
        }
        return t < 300;
    }

    private static void pull(BlockPos lever) {
        kid.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(lever));
        useOn(lever, Direction.UP);
    }

    private static final double[][] LUCKY = {{-1.6, 2.5}, {0, 3.0}, {1.6, 2.5}};

    private static boolean lucky(int t) {
        if (t == 20) {
            ItemStack blocks = carried("Lucky Block");
            for (double[] o : LUCKY) {
                hold(blocks.copyWithCount(1));
                BlockPos floor = BlockPos.containing(centre.x + o[0], centre.y - 1, centre.z + o[1]);
                useOn(floor, Direction.UP);
            }
        }
        // He breaks them one at a time: each one's outcome table goes off.
        int k = (t - 70) / 70;
        if (t >= 70 && k < LUCKY.length && (t - 70) % 70 == 0) {
            BlockPos body = BlockPos.containing(centre.x + LUCKY[k][0], centre.y, centre.z + LUCKY[k][1]);
            kid.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(body));
            com.moddingfromamod.compat.Swings.swing(kid, InteractionHand.MAIN_HAND, true);
            kid.gameMode.destroyBlock(body);
        }
        return t < 330;
    }

    /**
     * A building or a picture goes up in a tick or two, before any camera can be standing in the right
     * place, so the first castle film was a still. It is made once to learn where it lands and how big
     * it is, taken away again (the snapshot puts every block back), and made a second time at
     * {@code AGAIN} with the camera already looking: the film is the meadow, then the wish appearing.
     */
    private static final int AGAIN = 120;   // the recording starts ~3 s into the show

    private static boolean appears(int t) {
        if (t == 12 && made != null) {
            for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(made.minX, made.minY, made.minZ),
                    BlockPos.containing(made.maxX - 1, made.maxY - 1, made.maxZ - 1))) {
                BlockState was = before.get(p);
                if (was != null && level.getBlockState(p) != was) {
                    level.setBlock(p, was, net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                            | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE);
                }
            }
            // A picture is maps in frames on a board: the frames would drop as items when the board
            // went, and the registry would keep its spot taken, so the second one went up four blocks
            // along, right in front of the camera.
            // ...and a castle stood on as a black silhouette with its blocks all gone: whatever else the
            // wish put there is taken away too — every entity that was not about before it, bar the
            // camera and the people.
            Map<String, Integer> gone = new java.util.TreeMap<>();
            for (Entity e : level.getEntities((Entity) null, made.inflate(4), e -> !entitiesBefore.contains(e.getUUID())
                    && !FilmCamera.isCamera(e) && !(e instanceof net.minecraft.world.entity.player.Player))) {
                gone.merge(e.getType().toShortString(), 1, Integer::sum);
                e.discard();
            }
            LOGGER.info("{} taken away with it: {}", TAG, gone.isEmpty() ? "no entities" : gone);
            for (String id : com.moddingfromamod.paint.PaintRegistry.ids()) {
                if (!picturesBefore.contains(id)) {
                    com.moddingfromamod.paint.PaintRegistry.remove(level.getServer(), id);
                }
            }
            // Send the onlooker the chunks again, so his client holds exactly what the server does and
            // the take opens on the bare meadow. (A castle then goes up DARK and lights over ~4 s: that
            // is the client lighting 20,000 new blocks, which a child sees too — not leftovers. Both
            // were mistaken for a failed take-away until a take showed the empty meadow first.)
            ServerPlayer onlooker = level.getServer().getPlayerList().getPlayerByName(
                    FilmOnlooker.name().isEmpty() ? "Steve" : FilmOnlooker.name());
            if (onlooker != null) {
                for (int cx = ((int) Math.floor(made.minX)) >> 4; cx <= ((int) Math.floor(made.maxX)) >> 4; cx++) {
                    for (int cz = ((int) Math.floor(made.minZ)) >> 4; cz <= ((int) Math.floor(made.maxZ)) >> 4; cz++) {
                        onlooker.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                                level.getChunk(cx, cz), level.getLightEngine(), null, null));
                    }
                }
            }
            AABB left = changed();
            LOGGER.info("{} taken away again, to be made on camera at t={}; still standing: {}", TAG, AGAIN,
                    left == null ? "nothing" : left);
        }
        if (t == AGAIN) {
            for (String cmd : WishCatalog.templates().matchConfident(phraseOf(which())).get().plan().commands()) {
                var r = WishExecutor.runOne(level.getServer(), level, kid.position(), kid, cmd);
                LOGGER.info("{} again: `{}` -> {}{}", TAG, cmd, r.success() ? "" : "FAILED ", r.joined());
            }
        }
        if (t % 20 == 0 || t == 10) {
            AABB c = changed();
            if (c != null) {
                if (made == null || !c.equals(made)) {
                    LOGGER.info("{} it put down {}", TAG, c);
                }
                made = c;
            }
        }
        return t < AGAIN + 200;
    }

    private static String phraseOf(String id) {
        return WishCatalog.templates().known().stream().filter(k -> k.id().equals(id)).findFirst()
                .orElseThrow().phrase();
    }

    // ── the camera ──────────────────────────────────────────────────────────────────────────────

    /** Where the onlooker stands and what he looks at, for this starter at tick {@code t}. */
    private static void camera(ServerPlayer eye, int t) {
        Vec3 spot;
        Vec3 look;
        switch (which()) {
            case "starter_lightning_sword" -> {
                // Behind and beside him on his SWORD side (facing south, his right hand is west),
                // looking past him at the husks: the swing and the strike it calls down are one
                // picture. From in front of the husks (the first take) he was a speck behind them and
                // nobody could see what he held.
                spot = centre.add(-2.0, 0.5, -1.4);
                look = centre.add(0.3, 1.1, 1.8);
            }
            case "starter_jump_boots" -> {
                // Back far enough for the whole 4-block jump plus his height: at 4.4 blocks the top
                // of every boots jump left the frame.
                spot = centre.add(6.6, 1.2, 1.6);
                look = centre.add(0, 3.0, 0);
            }
            case "starter_slushie_machine" -> {
                spot = centre.add(2.8, 0.7, 0.9);
                look = centre.add(0, 1.3, 2.3);
            }
            case "starter_lucky_block" -> {
                spot = centre.add(1.0, 0.9, 6.0);
                look = centre.add(0, 0.7, 2.6);
            }
            case "class_rule" -> {
                // From the side and back, so a pig's whole bounce (up to ~4 blocks) stays in the frame.
                spot = centre.add(7.5, 1.0, 1.0);
                look = centre.add(0, 2.5, 4);
            }
            case "class_datapack" -> {
                // Off to his side and back far enough for three husks at twice their size (four blocks
                // tall) side by side; from behind him he stood in front of the middle one.
                spot = centre.add(3.5, 1.0, -4.5);
                look = centre.add(0, 2.2, 5);
            }
            default -> {
                // A building or a picture: from the child's side of it, far enough to take it all in.
                AABB box = made;
                if (box == null) {
                    spot = centre.add(0, 1.0, -3);
                    look = centre.add(0, 2, 6);
                } else {
                    Vec3 mid = box.getCenter();
                    double size = Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize()));
                    Vec3 toward = new Vec3(centre.x - mid.x, 0, centre.z - mid.z);
                    toward = toward.lengthSqr() < 0.01 ? new Vec3(0, 0, -1) : toward.normalize();
                    // In front of where he stands, not at his head: at 6 blocks a picture three blocks
                    // across was shot past the back of his head.
                    double d = Math.max(4.5, 0.95 * size + 1.5);
                    // to one side, so a face-on picture still has depth and he is out of the way
                    Vec3 side = new Vec3(-toward.z, 0, toward.x).scale(d * 0.4);
                    spot = mid.add(toward.scale(d)).add(side).add(0, size * 0.25 - 1.62, 0);
                    // A slow pan round it while it appears and after (a film with no camera motion at
                    // all reads as static): PAN_DEG across the whole show, still on the empty meadow before it.
                    double[] o = CameraPath.orbit(mid.x, mid.z, spot.x, spot.z, PAN_DEG,
                            t - (AGAIN - 30), 260);
                    spot = new Vec3(o[0], spot.y, o[1]);
                    look = mid;
                }
            }
        }
        // Through FilmCamera, never by teleporting him and turning his head: a look-at every tick
        // reached the client as a shake (the boots, the slushie, the lucky block were all jostly).
        // `spot` is where his FEET would be, as it always was; the camera is at his eyes.
        FilmCamera.aim(eye, spot.add(0, 1.62, 0), look, SETTLE_RATE);
    }

    /** How fast the camera settles on a new spot; a pan moves its spot every tick and it follows. */
    private static final double SETTLE_RATE = 0.2;

    /** How far the camera pans round a building or a picture, in degrees, over the show. */
    static final double PAN_DEG = 30;   // 16 barely read on the castle at fifty blocks
}
