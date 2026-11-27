package com.moddingfromamod.vanilla;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.runtime.CreatureFiles;
import com.moddingfromamod.runtime.CreatureSculpt;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.testing.SpeciesForTest;
import com.moddingfromamod.testing.gt.GameTest;
import com.moddingfromamod.testing.gt.GameTestHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * <b>Every stock creature, set as a guard against a husk, a vindicator and a ravager (#99).</b> Measured, and kept:
 * each of the 55 ready-made creatures (the 11 starters, the 44 Builder bodies) is made from its own shipped files
 * (its sculpt and its species record, as shipped), given the guardian instinct, and set beside a monster in a walled
 * plot. Each tick reads whether it lands a hit, how long it lasts and how long the monster lasts. One line per fight
 * goes to the log ({@code creature-fight:}), and a table at the end, so a balance change is read off a run rather than
 * guessed.
 *
 * <p>What it asserts is the rule the stat lines must keep: <b>a creature set as a guard can hurt a husk</b>: none has
 * a melee hit of 0, and nearly all of them land one ({@link #MUST_HURT_HUSK}). Before
 * #99 forty-odd stock creatures had a melee hit of 0, which lands nothing ({@code RigInstincts} checks it is above 0):
 * a rock golem chased monsters and never hurt them. A vehicle is not a fighter (it never fights, whatever its
 * instinct says) and is only listed. The vindicator and the ravager fights are measured and reported, not asserted:
 * a gentle animal is meant to lose to a ravager.
 *
 * <p>One fight at a time per plot, each in a batch of its own, so a guard never goes for the monster in the next plot.
 */
@GameTestHolder(ModdingFromAModMod.MODID)
public final class StockCreatureFightGameTest {

    private static final Logger LOG = LoggerFactory.getLogger("moddingfromamod/creature-fight");

    /** How long one fight may run, in ticks (20 s of game time: a guard that can hit lands one long before). */
    static final int FIGHT_TICKS = 400;
    /** How long a creature may take to stand up on its carrier before the fight is written off. */
    static final int SPAWN_TICKS = 100;
    /**
     * How many of the 44 stock guards (the 55 less the 11 vehicles) must hurt a husk. Measured on #99's stat lines: 38
     * and 41 in two runs (before them, 5 of 44). The few that miss all carry damage, and which ones miss changes from
     * run to run (the sheep, the snail, the snake, the swimmers on dry land, the pet dragon): what keeps them off the
     * husk is how they move, not their numbers. A margin under those, so a slow run is not a red one; far above 5, so a
     * return to unchosen zeros is.
     */
    static final int MUST_HURT_HUSK = 36;

    private static final BlockPos CREATURE_AT = new BlockPos(1, 2, 4);
    private static final BlockPos MONSTER_AT = new BlockPos(6, 2, 4);

    /** One stock creature: where its files are, and what its shipped record says. */
    record Stock(String id, String root, JsonObject species, JsonObject sculpt) {
        boolean vehicle() {
            return species.has("vehicle") && species.get("vehicle").getAsBoolean();
        }
    }

    /** What one fight showed. {@code -1} for a tick that never came. */
    record Fight(String creature, String monster, double health, double damage, boolean vehicle, boolean stood,
                 int hits, double dealt, int firstHit, int monsterDied, int creatureDied) {
        String line() {
            return String.format(Locale.ROOT,
                    "creature-fight: %-20s vs %-10s %s health %5.1f damage %4.1f | hits %2d dealt %6.1f first %4s"
                            + " | monster down %4s | creature down %4s",
                    creature, monster, vehicle ? "vehicle" : (stood ? "guard  " : "NOSTAND"), health, damage, hits,
                    dealt, tick(firstHit), tick(monsterDied), tick(creatureDied));
        }

        private static String tick(int t) {
            return t < 0 ? "-" : Integer.toString(t);
        }
    }

    // ── the three fights ──────────────────────────────────────────────────────────────────────

    @GameTest(template = "empty_test", batch = "stock_fight_husk", timeoutTicks = 40000)
    public static void everyStockGuardHurtsAHusk(GameTestHelper helper) {
        run(helper, EntityType.HUSK, "husk", true);
    }

    @GameTest(template = "empty_test", batch = "stock_fight_vindicator", timeoutTicks = 40000)
    public static void everyStockGuardAgainstAVindicatorIsMeasured(GameTestHelper helper) {
        run(helper, EntityType.VINDICATOR, "vindicator", false);
    }

    @GameTest(template = "empty_test", batch = "stock_fight_ravager", timeoutTicks = 40000)
    public static void everyStockGuardAgainstARavagerIsMeasured(GameTestHelper helper) {
        run(helper, EntityType.RAVAGER, "ravager", false);
    }

    // ── one fight after another ───────────────────────────────────────────────────────────────

    private static void run(GameTestHelper helper, EntityType<? extends Monster> type, String name, boolean mustHit) {
        List<Stock> stock;
        try {
            stock = stock();
        } catch (Exception e) {
            helper.fail(Component.literal("could not read the stock creatures: " + e));
            return;
        }
        List<Fight> done = new ArrayList<>();
        Arena arena = new Arena(helper, type, name, stock, done);
        helper.onEachTick(arena::tick);
        helper.succeedWhen(() -> {
            helper.assertTrue(done.size() == stock.size(), Component.literal(
                    "fights done: " + done.size() + " of " + stock.size()));
            if (!arena.reported) {
                arena.reported = true;
                LOG.info("creature-fight: ===== {} stock creatures against a {} =====", done.size(), name);
                done.forEach(f -> LOG.info(f.line()));
                int guards = 0;
                for (Fight f : done) {
                    if (f.vehicle()) {
                        continue;
                    }
                    guards++;
                    if (f.damage() <= 0) {
                        arena.zero.add(f.creature());
                    }
                    if (!f.stood() || f.dealt() <= 0) {
                        arena.harmless.add(f.creature() + (f.stood() ? "" : " (never stood up)"));
                    }
                }
                arena.guards = guards;
                LOG.info("creature-fight: {} of {} guards hurt the {}; never: {}", guards - arena.harmless.size(), guards,
                        name, arena.harmless);
            }
            if (!mustHit) {
                return;
            }
            // asserted on every call, not only the first: a retried succeedWhen must not read a failure as done
            helper.assertTrue(arena.zero.isEmpty(), Component.literal(
                    "stock creatures (not vehicles) with a melee hit of 0, which lands nothing: " + arena.zero));
            int hurt = arena.guards - arena.harmless.size();
            helper.assertTrue(hurt >= MUST_HURT_HUSK, Component.literal(String.format(Locale.ROOT,
                    "only %d of %d stock guards hurt a husk in %d ticks (at least %d must; before #99 it was 5). Never: %s",
                    hurt, arena.guards, FIGHT_TICKS, MUST_HURT_HUSK, arena.harmless)));
        });
    }

    /** The fights in one plot, as a state machine run from the tick. */
    private static final class Arena {
        final GameTestHelper helper;
        final EntityType<? extends Monster> type;
        final String name;
        final List<Stock> stock;
        final List<Fight> done;
        int index = -1;
        int tick;
        String id;
        Monster monster;
        Mob carrier;
        float lastMonsterHealth;
        int hits;
        double dealt;
        int firstHit = -1;
        int monsterDied = -1;
        int creatureDied = -1;
        boolean stood;
        boolean reported;
        final List<String> harmless = new ArrayList<>();
        final List<String> zero = new ArrayList<>();
        int guards;

        Arena(GameTestHelper helper, EntityType<? extends Monster> type, String name, List<Stock> stock, List<Fight> done) {
            this.helper = helper;
            this.type = type;
            this.name = name;
            this.stock = stock;
            this.done = done;
        }

        void tick() {
            if (done.size() == stock.size()) {
                return;
            }
            if (index < 0 || finished()) {
                if (index >= 0) {
                    record();
                }
                clear();
                index++;
                if (index < stock.size()) {
                    start(stock.get(index));
                }
                return;
            }
            tick++;
            if (carrier == null) {
                carrier = SpeciesOnRigGameTest.carrier(helper, id);
                stood = carrier != null;
                return;
            }
            float h = monster.isAlive() ? monster.getHealth() : 0f;
            if (h < lastMonsterHealth - 1e-3) {
                hits++;
                dealt += lastMonsterHealth - h;
                if (firstHit < 0) {
                    firstHit = tick;
                }
            }
            lastMonsterHealth = h;
            if (!monster.isAlive() && monsterDied < 0) {
                monsterDied = tick;
            }
            if (!carrier.isAlive() && creatureDied < 0) {
                creatureDied = tick;
            }
        }

        boolean finished() {
            if (stock.get(index).vehicle()) {
                return tick >= 20;   // a vehicle never fights, whatever its instinct says: listed, not fought
            }
            if (carrier == null) {
                return tick >= SPAWN_TICKS;
            }
            return monsterDied >= 0 || creatureDied >= 0 || tick >= FIGHT_TICKS;
        }

        void start(Stock s) {
            tick = 0;
            hits = 0;
            dealt = 0;
            firstHit = monsterDied = creatureDied = -1;
            carrier = null;
            stood = false;
            id = "gtfight_" + name.charAt(0) + "_" + s.id();
            Species sp;
            try {
                sp = guard(s, id);
            } catch (Exception e) {
                helper.fail(Component.literal("could not make " + s.id() + ": " + e));
                return;
            }
            SpeciesForTest.put(helper, sp);
            monster = helper.spawn(type, MONSTER_AT);
            if (monster instanceof Zombie z) {
                z.setBaby(false);
            }
            if (type == EntityType.VINDICATOR) {
                monster.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));   // as it spawns in a raid
            }
            monster.setPersistenceRequired();
            lastMonsterHealth = monster.getHealth();
            SpeciesOnRigGameTest.wishAt(helper.getLevel(), helper.absolutePos(CREATURE_AT), id);
        }

        void record() {
            Stock s = stock.get(index);
            double health = s.species().has("health") ? s.species().get("health").getAsDouble() : Double.NaN;
            double damage = s.species().has("damage") ? s.species().get("damage").getAsDouble() : Double.NaN;
            done.add(new Fight(s.id(), name, health, damage, s.vehicle(), stood, hits, dealt, firstHit, monsterDied,
                    creatureDied));
        }

        /** Everything but a player off the plot: the creature's carrier and its parts, the monster, what they dropped. */
        void clear() {
            AABB plot = new AABB(helper.absolutePos(BlockPos.ZERO)).inflate(24);
            for (Entity e : helper.getLevel().getEntities((Entity) null, plot, e -> !(e instanceof Player))) {
                e.discard();
            }
            monster = null;
            carrier = null;
        }
    }

    /**
     * The stock creature as shipped, under a test id, with the guardian instinct: its sculpt written as its art, its own
     * species record (health, damage, armour, knockback, powers, moves, size) with only the instinct changed and the
     * runner's flee taken off, since a guard that runs away measures the running.
     */
    static Species guard(Stock s, String id) throws Exception {
        String text = s.sculpt().toString();
        CreatureFiles.write(id, CreatureSculpt.parseParts(s.sculpt().getAsJsonArray("parts")), text);
        JsonObject o = s.species().deepCopy();
        o.addProperty("id", id);
        o.addProperty("modelKey", id);
        o.addProperty("base", "guardian");
        if (o.has("powers")) {
            var kept = new com.google.gson.JsonArray();
            o.getAsJsonArray("powers").forEach(p -> {
                if (!p.getAsString().equalsIgnoreCase("skittish")) {
                    kept.add(p);
                }
            });
            o.add("powers", kept);
        }
        return Species.fromJson(o);
    }

    /** Every stock creature: the starter library's species, then the Builder's bodies, each in index order. */
    static List<Stock> stock() throws Exception {
        List<Stock> out = new ArrayList<>();
        for (String[] root : new String[][] {{"starter-library", "starter-library/index.txt"},
                {"builder-bodies", "builder-bodies/index.txt"}}) {
            for (String line : text(root[1]).split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                String dir = t.endsWith("/species.json") ? t.substring(0, t.length() - "/species.json".length())
                        : root[0].equals("builder-bodies") && t.startsWith("species/") && t.indexOf('/', 8) < 0 ? t : null;
                if (dir == null || !dir.startsWith("species/")) {
                    continue;
                }
                String id = dir.substring("species/".length());
                out.add(new Stock(id, root[0], json(root[0] + "/" + dir + "/species.json"),
                        json(root[0] + "/" + dir + "/sculpt.json")));
            }
        }
        return out;
    }

    private static JsonObject json(String path) throws Exception {
        return JsonParser.parseString(text(path)).getAsJsonObject();
    }

    private static String text(String path) throws Exception {
        try (InputStream in = StockCreatureFightGameTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(path + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private StockCreatureFightGameTest() {
    }
}
