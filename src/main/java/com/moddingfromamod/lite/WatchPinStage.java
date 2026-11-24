package com.moddingfromamod.lite;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.wish.VanillaWatchItem;
import com.moddingfromamod.wish.WatchPin;
import com.moddingfromamod.wish.WatchPinService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>: every row of the watch-pin table, done by a GENUINELY STOCK client's own hands.</b>
 *
 * <p>{@code WatchPinGameTest} drives each mechanism through the server call a packet lands in; this
 * is the other half — a stock 1.21.4 client pressing the keys, dragging with the mouse, clicking
 * Respawn — and a film of what the child SEES. The server stages each row in turn, logs
 * {@code WATCHPIN staged row=… hand=…}, and {@code tools/watch-pin-film.sh} does what {@code hand}
 * says on the client while filming. After the row's window the server judges the table's end state
 * (exactly one watch, in slot 1, none loose, plus the row's own check) and logs
 * {@code WATCHPIN result row=… PASS|FAIL}.
 *
 * <p>A wish's {@code /clear} is not a row: it runs and the server-side pin restores the
 * watch exactly as for a typed one ({@code WatchPinGameTest.aWishsRunCommandCannotTakeHimEither}
 * pins that, and {@code clear_typed} films the typed one). A wish's {@code /item replace} is
 * ({@code item_wish}).
 *
 * <p>Off unless {@code -Dmoddingfromamod.watchpin=all} (or {@code +}-joined row names) is set.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class WatchPinStage {

    private WatchPinStage() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-watch-pin-stage");

    public static final String PROPERTY = ModdingFromAModMod.MODID + ".watchpin";

    /**
     * One row: its name, the game mode, and what the client's hands do (the script's vocabulary:
     * {@code keys:<xdotool keys>}, {@code drag}, {@code cursor}, {@code dragout}, {@code chest},
     * {@code numkey}, {@code respawn}, {@code cdelete}, {@code ccopy}, {@code rclick}, {@code chat:<text>},
     * {@code none}).
     */
    record Row(String name, GameType mode, String hand) {
    }

    static final List<Row> ROWS = List.of(
            new Row("q", GameType.SURVIVAL, "keys:1 q"),
            new Row("ctrl_q", GameType.SURVIVAL, "keys:1 ctrl+q"),
            new Row("drag", GameType.SURVIVAL, "drag"),
            new Row("cursor", GameType.SURVIVAL, "cursor"),
            new Row("drag_out", GameType.SURVIVAL, "dragout"),
            new Row("chest", GameType.SURVIVAL, "chest"),
            new Row("offhand_f", GameType.SURVIVAL, "keys:1 f"),
            new Row("number_key", GameType.SURVIVAL, "numkey"),
            new Row("death", GameType.SURVIVAL, "respawn"),
            new Row("death_keep", GameType.SURVIVAL, "respawn"),
            new Row("creative_delete", GameType.CREATIVE, "cdelete"),
            new Row("creative_copy", GameType.CREATIVE, "ccopy"),
            new Row("clear_typed", GameType.SURVIVAL, "chat:/clear"),
            new Row("item_typed", GameType.SURVIVAL,
                    "chat:/item replace entity @s hotbar.0 with minecraft:diamond"),
            new Row("item_wish", GameType.SURVIVAL, "none"),
            new Row("full", GameType.SURVIVAL, "none"),
            // A report: "when I right click with Moddy now it both throws the wish
            // watch AND wishes". A plain right-click in the air with slot 1 selected must open the
            // panel and move NOTHING: no item entity, nothing undone, the watch in slot 1 on every
            // tick of the window.
            new Row("right_click", GameType.SURVIVAL, "rclick"),
            new Row("right_click_creative", GameType.CREATIVE, "rclick"),
            // The same click aimed at the floor (the stage's stone): the RightClickBlock path.
            new Row("right_click_block", GameType.SURVIVAL, "rclick"),
            // A child whose first click "did nothing" (the panel is a round trip away through a
            // proxy) clicks again — and the panel opens with the mouse centred, which is the
            // COMPOSE cell. And a child who HOLDS the button.
            new Row("right_click_twice", GameType.SURVIVAL, "rclick2"),
            new Row("right_click_hold", GameType.SURVIVAL, "rhold"),
            // Something usable in the OFFHAND: the stock client's own use of the watch returns
            // PASS, so it goes on to use the offhand as well — is that thrown too?
            new Row("right_click_offhand", GameType.SURVIVAL, "rclick"),
            // A reported case: a backpack (a real bundle, the bag) in the offhand, which
            // a right-click empties onto the ground one item per tick.
            new Row("right_click_offhand_bundle", GameType.SURVIVAL, "rclick"),
            // Walking through the click: after the panel closes he must walk at full speed (a client
            // left "using" an item walks at a fifth). Measured here, server-side.
            new Row("right_click_walk", GameType.SURVIVAL, "rwalk"),
            // Open, close at once, click again inside Moddy's 10-tick cooldown, with a backpack in
            // the offhand: on cooldown the client skips the watch's own use and falls through.
            new Row("right_click_reopen", GameType.SURVIVAL, "rreopen"),
            // Aimed at a HORSE: horse is one of CarrierPool's carriers, so a wished creature can
            // be one, and leather horse armour is what a horse wears. Tamed and wild. LAST: a
            // tamed horse leaves the client believing slot 1 is empty (the ghost this row films),
            // and a row after it would be clicking with an empty hand.
            new Row("right_click_horse", GameType.SURVIVAL, "rclickent"),
            new Row("right_click_horse_wild", GameType.SURVIVAL, "rclickent"));

    /**
     * <b>The offhand click matrix </b>: every kind of wished item
     * ({@link com.moddingfromamod.item.OffhandMatrix.Row}) in slot 2 (Moddy's row: slot 1) crossed with
     * every offhand ({@link com.moddingfromamod.item.OffhandMatrix.Col}), one right-click each.
     * {@code m_<letter>_<col>} aims at open air (a PLACED row at the floor, since that is its click);
     * {@code m_<letter>_<col>_blk} aims at the floor. {@code watchpin=matrix} is all of them; a film
     * names the ones it wants. Judged by {@code OffhandMatrix}'s rule, the one the GameTest asserts.
     */
    record MatrixCell(com.moddingfromamod.item.OffhandMatrix.Row row,
            com.moddingfromamod.item.OffhandMatrix.Col col, boolean block) {
    }

    static final java.util.Map<String, MatrixCell> MATRIX = new java.util.LinkedHashMap<>();
    static final List<Row> MATRIX_ROWS = new ArrayList<>();

    static {
        for (var r : com.moddingfromamod.item.OffhandMatrix.Row.values()) {
            for (var c : com.moddingfromamod.item.OffhandMatrix.Col.values()) {
                for (boolean blk : new boolean[] {false, true}) {
                    String name = "m_" + r.letter + "_" + c.key() + (blk ? "_blk" : "");
                    // A placed thing's click IS at the floor: its plain row aims there too.
                    boolean block = blk || r == com.moddingfromamod.item.OffhandMatrix.Row.PLACED;
                    MATRIX.put(name, new MatrixCell(r, c, block));
                    boolean moddy = r == com.moddingfromamod.item.OffhandMatrix.Row.MODDY;
                    MATRIX_ROWS.add(new Row(name, GameType.SURVIVAL,
                            "mclick:" + (moddy ? "1" : "2") + ":" + (moddy ? "esc" : "noesc")));
                }
            }
        }
    }

    /** The right-click rows: judged on "nothing moved", not on "something was undone". */
    static boolean isRightClick(Row r) {
        return r.name().startsWith("right_click");
    }

    private static final int SEAT_X = 8;
    private static final int SEAT_Y = 120;
    private static final int SEAT_Z = 8;
    /** A stock client applies the item pack by RELOADING after the join: let it finish. */
    private static final int SETTLE_TICKS = 20 * 20;
    private static final int WATCH_TICKS = 11 * 20;
    private static final int GAP_TICKS = 2 * 20;

    private static int joinedTicks;
    private static int index = -1;
    private static int phaseTicks;
    private static boolean watching;
    private static Row current;
    private static BlockPos deathAt;
    /** Evidence the attempt reached the server — see {@link #verdict}. */
    private static int undoneAtStage;
    private static int deathsAtStage;
    private static boolean carriedSeen;
    /** The body staged for a death row: a respawn is a NEW ServerPlayer. */
    private static ServerPlayer stagedBody;
    private static final List<String> passes = new ArrayList<>();
    /** Right-click rows: what the window saw, tick by tick. */
    private static int itemJoins;
    private static final List<String> itemJoinWhat = new ArrayList<>();
    private static int offHomeTicks;
    private static String offHomeFirst = "";
    private static boolean panelSeen;
    /** The walk row: the window tick the panel closed, where he stood 5 ticks later, his speed. */
    private static int closedAt = -1;
    private static net.minecraft.world.phys.Vec3 walkFrom;
    private static double walkSpeed = -1;
    /** The horse a horse row put in front of him, discarded when the next row is staged. */
    private static net.minecraft.world.entity.animal.equine.Horse stagedHorse;
    /** Every menu the window saw open, in order (the panel, the wish prompt, ...). */
    private static final java.util.LinkedHashSet<String> menusSeen = new java.util.LinkedHashSet<>();
    private static int selectedOther;
    /** Matrix rows: uses started, and snowballs thrown, during the window. */
    private static final List<String> usedMain = new ArrayList<>();
    private static final List<String> usedOff = new ArrayList<>();
    private static int taggedThrows;
    private static int plainThrows;

    static List<Row> wanted() {
        String p = System.getProperty(PROPERTY, "").trim();
        if (p.isEmpty()) {
            return List.of();
        }
        if (p.equals("all")) {
            return ROWS;
        }
        if (p.equals("matrix")) {
            return MATRIX_ROWS;
        }
        List<Row> out = new ArrayList<>();
        List<Row> every = new ArrayList<>(ROWS);
        every.addAll(MATRIX_ROWS);
        for (Row r : every) {
            if (("+" + p.replace(',', '+') + "+").contains("+" + r.name() + "+")) {
                out.add(r);
            }
        }
        return out;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        List<Row> rows = wanted();
        if (rows.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        List<ServerPlayer> ps = server.getPlayerList().getPlayers();
        if (ps.isEmpty()) {
            joinedTicks = 0;
            return;
        }
        ServerPlayer p = ps.get(0);
        if (++joinedTicks < SETTLE_TICKS || index >= rows.size()) {
            return;
        }
        try {
            if (index < 0 || (!watching && ++phaseTicks >= GAP_TICKS)) {
                index++;
                if (index >= rows.size()) {
                    LOG.info("WATCHPIN done passed={}/{} [{}]", passes.size(), rows.size(),
                            String.join(",", passes));
                    return;
                }
                stage(server, p, rows.get(index));
                return;
            }
            if (watching) {
                carriedSeen |= VanillaWatchItem.isWatch(p.containerMenu.getCarried());
                observe(p);
                if (++phaseTicks >= WATCH_TICKS) {
                    verdict(server, p);
                }
            }
        } catch (RuntimeException e) {
            LOG.error("WATCHPIN result row={} RIG FAULT {}", current == null ? "?" : current.name(),
                    e.toString(), e);
            watching = false;
            phaseTicks = 0;
        }
    }

    /** Every tick of a window: did the watch leave slot 1, did the panel open. */
    private static void observe(ServerPlayer p) {
        panelSeen |= p.containerMenu instanceof com.moddingfromamod.wish.WatchMenu;
        menusSeen.add(p.containerMenu.getClass().getSimpleName());
        // The walk row: from 5 ticks after the panel closed, how far he walks in the next 20.
        if (panelSeen && closedAt < 0 && p.containerMenu == p.inventoryMenu) {
            closedAt = phaseTicks;
        }
        if (closedAt >= 0 && phaseTicks == closedAt + 5) {
            walkFrom = p.position();
        }
        if (closedAt >= 0 && phaseTicks == closedAt + 25 && walkFrom != null) {
            walkSpeed = p.position().subtract(walkFrom).horizontalDistance();   // blocks per second
        }
        if (!VanillaWatchItem.isWatch(p.getInventory().getItem(WatchPin.HOME))) {
            if (offHomeTicks++ == 0) {
                offHomeFirst = "tick " + phaseTicks + " slot1="
                        + p.getInventory().getItem(WatchPin.HOME) + " body="
                        + p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.BODY)
                        + " offhand=" + p.getOffhandItem();
            }
        }
        if (p.getInventory().getSelectedSlot() != 0) {
            selectedOther++;
        }
    }

    /**
     * A use STARTED on the server (a shield raised, bread begun, a bundle emptying), and in which hand.
     * Counted at the event rather than by sampling the flag each tick: a click's use and its release
     * can land in one server tick, and a sampled flag would never see it.
     */
    @SubscribeEvent
    public static void onUseStart(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Start event) {
        if (!watching || stagedBody == null || event.getEntity() != stagedBody) {
            return;
        }
        if (event.getItem() == stagedBody.getOffhandItem()) {
            usedOff.add(event.getItem().getItem().toString());
        } else {
            usedMain.add(event.getItem().getItem().toString());
        }
        LOG.info("WATCHPIN use started during row={}: {} in the {}", current == null ? "?" : current.name(),
                event.getItem(), event.getItem() == stagedBody.getOffhandItem() ? "offhand" : "main hand");
    }

    /** Any item entity trying to join a level while a window is open, refused or not. */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    public static void onItemJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        // NEAR HIM only: the flat world's villagers harvest their farms, and a carrot dropped 40
        // blocks away failed two control rows before this line (measured).
        if (stagedBody == null || event.getEntity().distanceToSqr(stagedBody) > 32 * 32) {
            return;
        }
        if (watching && !event.getLevel().isClientSide() && !event.loadedFromDisk()
                && event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile pr) {
            itemJoins++;
            itemJoinWhat.add("projectile " + pr.getType().toShortString());
            if (pr.getTags().contains(com.moddingfromamod.item.OffhandMatrix.THROW_TAG)) {
                taggedThrows++;
            } else {
                plainThrows++;
            }
            LOG.info("WATCHPIN projectile joined during row={}: {} at {}",
                    current == null ? "?" : current.name(), pr.getType().toShortString(), pr.blockPosition());
        }
        if (watching && !event.getLevel().isClientSide() && !event.loadedFromDisk()
                && event.getEntity() instanceof ItemEntity e) {
            itemJoins++;
            itemJoinWhat.add(e.getItem() + (VanillaWatchItem.isWatch(e.getItem()) ? "(WATCH)" : ""));
            LOG.info("WATCHPIN item entity joined during row={}: {} watch={} at {}",
                    current == null ? "?" : current.name(), e.getItem(),
                    VanillaWatchItem.isWatch(e.getItem()), e.blockPosition(), new Throwable("who"));
        }
    }

    private static BlockPos seat() {
        return new BlockPos(SEAT_X, SEAT_Y, SEAT_Z);
    }

    private static void stage(MinecraftServer server, ServerPlayer p, Row r) {
        current = r;
        carriedSeen = false;
        itemJoins = 0;
        itemJoinWhat.clear();
        offHomeTicks = 0;
        offHomeFirst = "";
        panelSeen = false;
        menusSeen.clear();
        closedAt = -1;
        walkFrom = null;
        walkSpeed = -1;
        if (stagedHorse != null) {
            stagedHorse.discard();
            stagedHorse = null;
        }
        selectedOther = 0;
        usedMain.clear();
        usedOff.clear();
        taggedThrows = 0;
        plainThrows = 0;
        deathsAtStage = deaths(p);
        stagedBody = p;
        ServerLevel level = p.level();
        BlockPos s = seat();
        int far = r.name().equals("right_click_walk") ? 30 : 8;   // room to walk south
        for (BlockPos q : BlockPos.betweenClosed(s.offset(-5, -1, -4), s.offset(5, 6, far))) {
            level.setBlockAndUpdate(q, q.getY() == s.getY() - 1 ? Blocks.STONE.defaultBlockState()
                    : Blocks.AIR.defaultBlockState());
        }
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(s).inflate(16))) {
            e.discard();
        }
        // A matrix row's anchor (the placed shape) and its thrown things, from the row before.
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, new AABB(s).inflate(16),
                e -> e instanceof net.minecraft.world.entity.decoration.ArmorStand
                        || e instanceof net.minecraft.world.entity.projectile.Projectile)) {
            e.discard();
        }
        if (p.containerMenu != p.inventoryMenu) {
            p.closeContainer();
        }
        p.setGameMode(r.mode());
        com.moddingfromamod.compat.Teleports.player(p, level, s.getX() + 0.5, s.getY(),
                s.getZ() + 0.5, 0.0F,
                // The right-click rows aim: UP at open air (nothing within reach, so it is a pure
                // use-in-air), or DOWN at the stage floor (RightClickBlock, then the use).
                MATRIX.containsKey(r.name()) ? (MATRIX.get(r.name()).block() ? 55.0F : -30.0F)
                        : r.name().equals("right_click_block") ? 70.0F
                        : r.name().startsWith("right_click_horse") ? 15.0F
                        : isRightClick(r) ? -30.0F : 30.0F);
        p.setHealth(p.getMaxHealth());
        p.getInventory().clearContent();
        ItemStack watch = VanillaWatchItem.stack();
        if (Boolean.getBoolean(PROPERTY + ".nostamp")) {
            // The control arm: Moddy as he was before the one-click stamp.
            com.moddingfromamod.compat.Consumables.unstamp(watch);
        }
        p.getInventory().setItem(WatchPin.HOME, watch);
        p.getInventory().setItem(3, new ItemStack(Items.BREAD, 5));
        p.getInventory().setItem(4, new ItemStack(Items.TORCH, 16));
        p.getInventory().setSelectedSlot(0);
        p.connection.send(new ClientboundSetHeldSlotPacket(0));
        server.getPlayerList().op(new NameAndId(p.getUUID(), p.getScoreboardName()));
        level.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY).set(false, server);
        MatrixCell cell = MATRIX.get(r.name());
        if (cell != null) {
            p.getFoodData().setFoodLevel(10);   // hungry enough that bread in either hand is eaten
            var main = com.moddingfromamod.item.OffhandMatrix.stack(cell.row(), server.registryAccess());
            if (main != null && cell.row() != com.moddingfromamod.item.OffhandMatrix.Row.MODDY) {
                p.getInventory().setItem(1, main);
            }
            var off = com.moddingfromamod.item.OffhandMatrix.offhand(cell.col());
            p.getInventory().setItem(WatchPin.OFFHAND, off == null ? ItemStack.EMPTY : off);
            if (cell.row() == com.moddingfromamod.item.OffhandMatrix.Row.MODDY
                    && Boolean.getBoolean(PROPERTY + ".nostamp")) {
                com.moddingfromamod.compat.Consumables.unstamp(p.getInventory().getItem(WatchPin.HOME));
            }
        }
        switch (r.name()) {
            case "chest" -> level.setBlockAndUpdate(s.offset(0, 0, 2), Blocks.CHEST.defaultBlockState());
            case "right_click_offhand" -> p.getInventory().setItem(WatchPin.OFFHAND,
                    new ItemStack(Items.SNOWBALL, 8));
            case "right_click_offhand_bundle", "right_click_reopen" -> {
                ItemStack bag = new ItemStack(Items.BUNDLE);
                bag.set(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS,
                        new net.minecraft.world.item.component.BundleContents(List.of(
                                new ItemStack(Items.DIRT, 5), new ItemStack(Items.COBBLESTONE, 3))));
                p.getInventory().setItem(WatchPin.OFFHAND, bag);
            }
            case "right_click_horse", "right_click_horse_wild" -> {
                var horse = EntityType.HORSE.create(level, EntitySpawnReason.COMMAND);
                if (horse != null) {
                    horse.snapTo(s.getX() + 0.5, s.getY(), s.getZ() + 2.5, 180.0F, 0.0F);
                    horse.setNoAi(true);
                    horse.setTamed(r.name().equals("right_click_horse"));
                    level.addFreshEntity(horse);
                    stagedHorse = horse;
                }
            }
            case "death", "death_keep" -> {
                level.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY)
                        .set(r.name().equals("death_keep"), server);
                deathAt = p.blockPosition();
                p.getInventory().setItem(5, new ItemStack(Items.DIRT, 7));
                p.hurt(p.damageSources().generic(), Float.MAX_VALUE);
            }
            case "full" -> {
                for (int i = 0; i < WatchPin.MAIN_END; i++) {
                    p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
                }
                p.getInventory().setItem(WatchPin.HOME, new ItemStack(Items.GOLDEN_APPLE));
            }
            case "item_wish" -> throughRunCommand(server, p,
                    "item replace entity @p hotbar.0 with minecraft:stick");
            default -> {
            }
        }
        p.inventoryMenu.broadcastChanges();
        // From here on. A "none" row's staged move (a full bag, the wish's command) is undone on a
        // later tick, so it counts too.
        undoneAtStage = WatchPinService.undone();
        LOG.info("WATCHPIN staged row={} mode={} hand={}", r.name(),
                r.mode().getName(), r.hand());
        watching = true;
        phaseTicks = 0;
    }

    /**
     * The command a wish's {@code run_command} runs, through the real tool on a wish of his: submit,
     * assign, call on a worker thread (every tool hops onto the server thread and refuses a
     * re-entrant hop), finish.
     */
    private static void throughRunCommand(MinecraftServer server, ServerPlayer p, String command) {
        UUID player = p.getUUID();
        var book = com.moddingfromamod.wish.WishBook.get(server);
        var wish = book.add(com.moddingfromamod.wish.Wish.create("watch pin stage: " + command,
                com.moddingfromamod.wish.WishSource.TYPED, System.currentTimeMillis(), player,
                p.getScoreboardName(), "minecraft:overworld", 0, 64, 0));
        var marshal = com.moddingfromamod.marshal.Marshal.global();
        marshal.submit(wish.id(), player, wish.text());
        String worker = "watchpin-stage";
        marshal.assign(worker, wish.id());
        Thread t = new Thread(() -> {
            try {
                String said = com.moddingfromamod.agent.AgentContext.with(worker, () -> {
                    JsonObject args = new JsonObject();
                    JsonArray cmds = new JsonArray();
                    cmds.add(command);
                    args.add("command", cmds);
                    var r = new com.moddingfromamod.agent.tools.RunCommandTool().call(args);
                    return r == null ? "null" : r.text();
                });
                LOG.info("WATCHPIN run_command said: {}", said.replace('\n', ' '));
            } catch (RuntimeException e) {
                LOG.error("WATCHPIN run_command threw", e);
            } finally {
                server.execute(() -> {
                    marshal.finish(wish.id());
                    com.moddingfromamod.wish.BuildGate.settle(wish.id());
                });
            }
        }, "watchpin-stage-run-command");
        t.setDaemon(true);
        t.start();
    }

    private static void verdict(MinecraftServer server, ServerPlayer p) {
        watching = false;
        phaseTicks = 0;
        // After a death the player object is the RESPAWNED one; ask the server for him again.
        ServerPlayer now = server.getPlayerList().getPlayer(p.getUUID());
        ServerLevel level = now.level();
        var inv = now.getInventory();
        int watches = VanillaWatchItem.isWatch(now.containerMenu.getCarried()) ? 1 : 0;
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < WatchPin.SLOTS; i++) {
            if (VanillaWatchItem.isWatch(inv.getItem(i))) {
                watches++;
                where.append(i).append(' ');
            }
        }
        BlockPos around = current.name().startsWith("death") && deathAt != null ? deathAt
                : now.blockPosition();
        // TALL: the stage floor floats at y=120, and a death's drops scatter and can fall off its
        // edge to the ground far below -- measured, a dirt drop the first box could not find.
        AABB box = new AABB(around).inflate(24, 160, 24);
        int loose = level.getEntitiesOfClass(ItemEntity.class, box,
                e -> VanillaWatchItem.isWatch(e.getItem())).size();
        boolean home = VanillaWatchItem.isWatch(inv.getItem(WatchPin.HOME)) && watches == 1;
        String extra0 = switch (current.name()) {
            case "chest" -> {
                var be = level.getBlockEntity(seat().offset(0, 0, 2));
                boolean stored = false;
                if (be instanceof ChestBlockEntity c) {
                    for (int i = 0; i < c.getContainerSize(); i++) {
                        stored |= VanillaWatchItem.isWatch(c.getItem(i));
                    }
                }
                yield stored ? "a watch is still in the chest" : "";
            }
            case "offhand_f" -> VanillaWatchItem.isWatch(now.getOffhandItem())
                    ? "a watch is in the offhand" : "";
            case "item_typed" -> inv.countItem(Items.DIAMOND) == 1 ? "" : "the diamond is gone";
            case "item_wish" -> inv.countItem(Items.STICK) == 1 ? "" : "the stick is gone";
            case "full" -> level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(now.blockPosition()).inflate(4),
                    e -> e.getItem().is(Items.GOLDEN_APPLE)).isEmpty()
                    && inv.countItem(Items.GOLDEN_APPLE) == 0 ? "the golden apple was destroyed" : "";
            case "death" -> level.getEntitiesOfClass(ItemEntity.class, box,
                    e -> e.getItem().is(Items.DIRT)).isEmpty()
                    ? "keepInventory off dropped nothing (the dirt should drop)" : "";
            default -> "";
        };
        String extra = extra0;
        // THE ATTEMPT HAPPENED. An end state that holds with nothing undone is also what a missed
        // click looks like, so each row names its evidence: the server undid something, or (a
        // watch on the cursor is allowed) the cursor was seen holding it, or (keepInventory keeps
        // it in place) a death was recorded. NOINPUT=1 is the control that this fails.
        int undone = WatchPinService.undone() - undoneAtStage;
        int died = deaths(now) - deathsAtStage;
        String evidence = MATRIX.containsKey(current.name()) ? matrixEvidence(now)
                : isRightClick(current) ? rightClickEvidence(undone, now) : switch (current.name()) {
            case "cursor" -> carriedSeen ? "" : "the watch was never on his cursor";
            // The kill is staged, so the drop removal happens before the baseline; that no watch
            // dropped is the loose == 0 check around where he died.
            case "death", "death_keep" -> died >= 1 && now != stagedBody && now.isAlive() ? ""
                    : "he never died and respawned (died=" + died + ")";
            default -> undone >= 1 ? "" : "nothing was undone, so the attempt never arrived";
        };
        if (!evidence.isEmpty()) {
            extra = (extra.isEmpty() ? "" : extra + "; ") + evidence;
        }
        boolean pass = home && loose == 0 && extra.isEmpty() && now.isAlive();
        if (pass) {
            passes.add(current.name());
        }
        LOG.info("WATCHPIN result row={} {} watches={} at=[{}] loose={} alive={} undone={} "
                        + "died={} carriedSeen={} {}", current.name(), pass ? "PASS" : "FAIL",
                watches, where.toString().trim(), loose, now.isAlive(), undone, died, carriedSeen,
                extra);
    }

    /** A right-click must move nothing, and must open the panel. */
    private static String rightClickEvidence(int undone, ServerPlayer now) {
        List<String> bad = new ArrayList<>();
        if (itemJoins > 0) {
            bad.add(itemJoins + " item entity(ies) appeared " + itemJoinWhat);
        }
        if (undone > 0) {
            bad.add("the pin undid " + undone + " thing(s)");
        }
        if (offHomeTicks > 0) {
            bad.add("the watch left slot 1 for " + offHomeTicks + " tick(s), first " + offHomeFirst);
        }
        if (stagedHorse != null && stagedHorse.isWearingBodyArmor()) {
            bad.add("the horse is wearing " + stagedHorse.getBodyArmorItem());
        }
        if (current.name().equals("right_click_walk") && walkSpeed < 3.0) {
            bad.add("after the panel closed he walked at " + String.format("%.2f", walkSpeed)
                    + " blocks/s (full speed is ~4.3; a client left \"using\" walks ~0.9)");
        }
        // One click, one hand: the offhand must come out of Moddy's click exactly as it went in.
        ItemStack off = now.getOffhandItem();
        if (current.name().equals("right_click_offhand")
                && !(off.is(Items.SNOWBALL) && off.getCount() == 8)) {
            bad.add("the offhand was used: " + off);
        }
        if (current.name().equals("right_click_offhand_bundle") || current.name().equals("right_click_reopen")) {
            var c = off.get(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS);
            int n = 0;
            if (c != null) {
                for (ItemStack s : c.items()) {
                    n += s.getCount();
                }
            }
            if (!off.is(Items.BUNDLE) || n != 8) {
                bad.add("the backpack lost something: " + off + " holding " + n);
            }
        }
        // Aimed at an entity, the click is an INTERACT, not a use: whether the panel opens there is
        // recorded (menus=…), not required.
        if (!panelSeen && !current.name().startsWith("right_click_horse")) {
            bad.add("the panel never opened (did the click arrive?)");
        }
        LOG.info("WATCHPIN rclick row={} panelOpened={} itemJoins={} undone={} offHomeTicks={} "
                        + "selectedOther={} menus={} walkSpeed={}", current.name(), panelSeen, itemJoins, undone,
                offHomeTicks, selectedOther, menusSeen, String.format("%.2f", walkSpeed));
        return String.join("; ", bad);
    }

    /**
     * A matrix cell: what the main hand and the offhand did in the window, against
     * {@code OffhandMatrix}'s rule (the same one {@code OffhandClickMatrixGameTest} asserts). The
     * observed values are logged whatever the verdict, as the row of the film's table.
     */
    private static String matrixEvidence(ServerPlayer now) {
        MatrixCell cell = MATRIX.get(current.name());
        var level = now.level();
        BlockPos s = seat();
        AABB stageBox = new AABB(s).inflate(12, 6, 12);
        List<String> main = new ArrayList<>();
        if (panelSeen) {
            main.add("panel");
        }
        boolean gold = false;
        boolean torch = false;
        for (BlockPos q : BlockPos.betweenClosed(s.offset(-5, -1, -4), s.offset(5, 3, 8))) {
            gold |= level.getBlockState(q).is(Blocks.GOLD_BLOCK);
            torch |= level.getBlockState(q).is(Blocks.TORCH) || level.getBlockState(q).is(Blocks.WALL_TORCH);
        }
        if (gold) {
            main.add("ability");
        }
        if (taggedThrows > 0) {
            main.add("thrown");
        }
        if (!level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class, stageBox,
                a -> a.getTags().contains(com.moddingfromamod.item.OffhandMatrix.PLACE_TAG)).isEmpty()) {
            main.add("placed");
        }
        if (cell.row() == com.moddingfromamod.item.OffhandMatrix.Row.WORN
                && !now.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty()) {
            main.add("equipped");
        }
        if (!usedMain.isEmpty()) {
            main.add(usedMain.get(0).contains("bundle") ? "emptying" : "eating");
        }
        ItemStack off = now.getOffhandItem();
        String offSeen = switch (cell.col()) {
            case EMPTY -> "-";
            case SNOWBALL -> plainThrows > 0 || off.getCount() != 8 ? "thrown" : "-";
            case BUNDLE -> !usedOff.isEmpty() || com.moddingfromamod.item.OffhandMatrix.inside(off) != 8
                    ? "emptying" : "-";
            case SHIELD -> !usedOff.isEmpty() ? "raised" : "-";
            case BREAD -> !usedOff.isEmpty() || off.getCount() != 5 ? "eating" : "-";
            case TORCH -> torch || off.getCount() != 16 ? "placed" : "-";
        };
        String mainSeen = main.isEmpty() ? "-" : String.join("+", main);
        String wantMain = com.moddingfromamod.item.OffhandMatrix.expectMain(cell.row(), cell.block());
        String wantOff = com.moddingfromamod.item.OffhandMatrix.expectOff(cell.row(), cell.col(), cell.block());
        LOG.info("WATCHPIN matrix row={} cell={}:{}/{}/{} main={} off={} want main={} off={} usedMain={} "
                        + "usedOff={} throws={}+{} menus={}", current.name(), cell.row().letter, cell.row().key(),
                cell.col().key(), cell.block() ? "block" : "air", mainSeen, offSeen, wantMain, wantOff,
                usedMain, usedOff, taggedThrows, plainThrows, menusSeen);
        List<String> bad = new ArrayList<>();
        if (!mainSeen.equals(wantMain)) {
            bad.add("main hand did " + mainSeen + ", want " + wantMain);
        }
        if (!offSeen.equals(wantOff)) {
            bad.add("offhand did " + offSeen + ", want " + wantOff);
        }
        return String.join("; ", bad);
    }

    private static int deaths(ServerPlayer p) {
        return p.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(
                net.minecraft.stats.Stats.DEATHS));
    }
}
