package com.moddingfromamod.lite;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.agent.tools.MakeItemTool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>T6F.7.2: every trigger family, used by a GENUINELY STOCK client's own hands.</b>
 *
 * <p>The GameTests (F.7.1's matrix) drive a mock player through the server's own use paths, which
 * proves the wiring and cannot prove the one thing a child on a stock client does: press and hold the
 * right mouse button, with the item's use arriving as a packet a translator (ViaProxy) may have
 * rewritten. So the server stages each family in turn &mdash; builds the item through the REAL
 * {@code make_item} and the real give (no agent, fixed words), puts it in his hand, stages what the
 * use needs &mdash; logs {@code STOCKRIDE staged family=…}, and {@code tools/stock-ride-film.sh}
 * presses the mouse on the stock client and films it. Both halves are judged HERE, where they
 * happen: the item's own action (an arrow left the bow, the cookie was eaten, the shield blocked,
 * the snowball flew, the horn was blown) and the wish (glowstone, the {@code place} effect).
 *
 * <p>Off unless {@code -Dmoddingfromamod.stockride=all} (or one family name) is set.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class StockRideStage {

    private StockRideStage() {
    }

    private static final Logger LOG = LoggerFactory.getLogger("mfam-stock-ride");

    public static final String PROPERTY = ModdingFromAModMod.MODID + ".stockride";

    /** One family: the shape make_item builds, on the real base, and what the stock client does. */
    record Family(String name, String shape, String base, int count, String hand) {
    }

    static final List<Family> FAMILIES = List.of(
            new Family("draw", "on_shot", "minecraft:bow", 1, "hold 1600"),
            new Family("eat", "on_consume", "minecraft:cookie", 4, "hold 2600"),
            new Family("block", "on_block", "minecraft:shield", 1, "hold 5000"),
            new Family("throw", "on_throw_land", "minecraft:snowball", 4, "click"),
            new Family("horn", "on_use_done", "minecraft:goat_horn", 1, "hold 1000"),
            new Family("click", "held", "minecraft:feather", 1, "click"));

    private static final int SEAT_X = 8;
    private static final int SEAT_Y = 120;
    private static final int SEAT_Z = 8;
    /** Seconds after the child joins: a stock client applies the item pack by RELOADING. */
    private static final int SETTLE_TICKS = 20 * 20;
    private static final int WATCH_TICKS = 14 * 20;
    private static final int GAP_TICKS = 3 * 20;

    private static int joinedTicks;
    private static int index = -1;
    private static int phaseTicks;
    private static boolean watching;
    private static Family current;
    private static int foodBefore;
    private static int countBefore;
    private static float healthBefore;
    private static boolean usedSeen;
    private static int blocked;
    private static int arrowsFired;
    private static final TreeSet<String> projectiles = new TreeSet<>();
    private static final List<String> passes = new ArrayList<>();

    static List<Family> wanted() {
        String p = System.getProperty(PROPERTY, "").trim();
        if (p.isEmpty()) {
            return List.of();
        }
        if (p.equals("all")) {
            return FAMILIES;
        }
        List<Family> out = new ArrayList<>();
        for (Family f : FAMILIES) {
            if (("," + p.replace('+', ',') + ",").contains("," + f.name() + ",")) {
                out.add(f);
            }
        }
        return out;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onShieldBlock(LivingShieldBlockEvent event) {
        if (watching && current != null && current.name().equals("block") && event.getBlocked()
                && event.getEntity() instanceof ServerPlayer) {
            blocked++;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        List<Family> fams = wanted();
        if (fams.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        List<ServerPlayer> ps = server.getPlayerList().getPlayers();
        if (ps.isEmpty()) {
            joinedTicks = 0;
            return;
        }
        ServerPlayer p = ps.get(0);
        if (++joinedTicks < SETTLE_TICKS || index >= fams.size()) {
            return;
        }
        try {
            if (index < 0 || (!watching && ++phaseTicks >= GAP_TICKS)) {
                index++;
                if (index >= fams.size()) {
                    LOG.info("STOCKRIDE done passed={}/{} [{}]", passes.size(), fams.size(), String.join(",", passes));
                    return;
                }
                stage(p, fams.get(index));
                return;
            }
            if (watching) {
                watch(p);
                if (++phaseTicks >= WATCH_TICKS) {
                    verdict(p);
                }
            }
        } catch (RuntimeException e) {
            LOG.error("STOCKRIDE result family={} RIG FAULT {}", current == null ? "?" : current.name(), e.toString(), e);
            watching = false;
            phaseTicks = 0;
        }
    }

    private static BlockPos seat() {
        return new BlockPos(SEAT_X, SEAT_Y, SEAT_Z);
    }

    private static void stage(ServerPlayer p, Family f) {
        current = f;
        ServerLevel level = p.level();
        BlockPos s = seat();
        // A floor to stand on, clear air ahead, and a stone wall 7 blocks south (the bow's target).
        for (BlockPos q : BlockPos.betweenClosed(s.offset(-5, -1, -4), s.offset(5, 6, 12))) {
            level.setBlockAndUpdate(q, q.getY() == s.getY() - 1 ? Blocks.STONE.defaultBlockState()
                    : Blocks.AIR.defaultBlockState());
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy <= 3; dy++) {
                level.setBlockAndUpdate(s.offset(dx, dy, 7), Blocks.STONE.defaultBlockState());
            }
        }
        p.setGameMode(GameType.SURVIVAL);
        com.moddingfromamod.compat.Teleports.player(p, level, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 0.0F, 0.0F);
        p.setYHeadRot(0.0F);
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(4);
        p.getInventory().clearContent();

        String name = "Stock " + f.name();
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("icon", "wand");
        o.addProperty("colour", "#FFAA00");
        o.addProperty("shape", f.shape());
        o.addProperty("effect", "place");
        o.addProperty("count", f.count());
        o.addProperty("base_item", f.base());
        JsonObject params = new JsonObject();
        params.addProperty("BLOCK", "minecraft:glowstone");
        o.add("params", params);
        MakeItemTool tool = new MakeItemTool();
        ToolResult r = tool.call(o);
        if (r.isError()) {
            throw new IllegalStateException("make_item refused " + f + ": " + r.text());
        }
        p.getServer().getCommands().performPrefixedCommand(
                p.createCommandSourceStack().withPermission(2).withSuppressedOutput(),
                "wishdatapack " + tool.lastMadeId() + " give");
        ItemStack held = ItemStack.EMPTY;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.getHoverName().getString().equals(name)) {
                held = st.copy();
                p.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
        if (held.isEmpty()) {
            throw new IllegalStateException("the give handed over nothing for " + f);
        }
        // Into whichever hotbar slot he has selected: no held-slot packet needed, on any client.
        p.getInventory().setItem(p.getInventory().getSelectedSlot(), held);
        if (f.name().equals("draw")) {
            p.getInventory().setItem(p.getInventory().getSelectedSlot() == 8 ? 7 : 8, new ItemStack(Items.ARROW, 16));
        }
        p.inventoryMenu.broadcastChanges();
        foodBefore = p.getFoodData().getFoodLevel();
        countBefore = held.getCount();
        healthBefore = p.getHealth();
        usedSeen = false;
        blocked = 0;
        arrowsFired = 0;
        projectiles.clear();
        watching = true;
        phaseTicks = 0;
        LOG.info("STOCKRIDE staged family={} shape={} item={}({}) hand={}", f.name(), f.shape(), name.replace(' ', '_'),
                BuiltInRegistries.ITEM.getKey(held.getItem()), f.hand());
    }

    private static void watch(ServerPlayer p) {
        ServerLevel level = p.level();
        if (p.isUsingItem()) {
            usedSeen = true;
        }
        for (Projectile e : level.getEntitiesOfClass(Projectile.class, new AABB(seat()).inflate(24),
                e -> e.getOwner() == p)) {
            projectiles.add(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
        }
        // The shield needs something to block: while he holds it up, the wall shoots at him.
        if (current.name().equals("block") && p.isBlocking() && arrowsFired < 3 && phaseTicks % 20 == 0) {
            Arrow a = new Arrow(EntityType.ARROW, level);
            {
                Vec3 from = p.position().add(0, 1.4, 5);
                a.snapTo(from.x, from.y, from.z);
                a.shoot(0, 0, -1, 2.0F, 0.0F);
                level.addFreshEntity(a);
                arrowsFired++;
            }
        }
    }

    private static int glowstone(ServerPlayer p) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(seat().offset(-6, -2, -5), seat().offset(6, 8, 14))) {
            if (p.level().getBlockState(q).is(Blocks.GLOWSTONE)) {
                n++;
            }
        }
        return n;
    }

    private static void verdict(ServerPlayer p) {
        watching = false;
        phaseTicks = 0;
        String vanilla;
        boolean ok;
        switch (current.name()) {
            case "draw" -> {
                ok = projectiles.contains("arrow");
                vanilla = "drawn=" + usedSeen + " shot=" + projectiles;
            }
            case "eat" -> {
                int count = countIn(p);
                // Not the food bar: the lite server is peaceful, which refills it with nothing eaten
                // (measured by the no-hands control: food 4->20, count 4->4).
                ok = usedSeen && count < countBefore;
                vanilla = "food=" + foodBefore + "->" + p.getFoodData().getFoodLevel() + " count=" + countBefore + "->" + count;
            }
            case "block" -> {
                ok = blocked > 0 && p.getHealth() >= healthBefore;
                vanilla = "raised=" + usedSeen + " arrows=" + arrowsFired + " blocked=" + blocked + " health="
                        + healthBefore + "->" + p.getHealth();
            }
            case "throw" -> {
                ok = projectiles.contains("snowball");
                vanilla = "thrown=" + projectiles + " count=" + countBefore + "->" + countIn(p);
            }
            case "horn" -> {
                ok = usedSeen;
                vanilla = "blown=" + usedSeen;
            }
            default -> {
                ok = true;
                vanilla = "n/a (a feather has no use of its own)";
            }
        }
        int g = glowstone(p);
        boolean pass = ok && g > 0;
        if (pass) {
            passes.add(current.name());
        }
        LOG.info("STOCKRIDE result family={} {} vanilla=[{}] wish=[glowstone={}]", current.name(), pass ? "PASS" : "FAIL",
                vanilla, g);
    }

    private static int countIn(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.getHoverName().getString().equals("Stock " + current.name())) {
                n += st.getCount();
            }
        }
        return n;
    }
}
