package com.moddingfromamod.testing;

import com.moddingfromamod.seam.SoundLedger;
import com.moddingfromamod.film.FilmCamera;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.compat.RiderInput;
import com.moddingfromamod.entity.WishMobTags;
import com.moddingfromamod.mob.RideSeat;
import com.moddingfromamod.testing.ExercisePlan.Drive;
import com.moddingfromamod.testing.ExercisePlan.Phase;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Put a freshly wished creature through {@link ExercisePlan} and write down what it DID</b>
 * (chunk 0). A behaviour wish reported DONE says the agent stopped; it says nothing about
 * whether the creature stomps, carries its rider, leaps when he jumps or roars. Three of four of the
 * owner's own behaviour wishes were DONE and did not happen.
 *
 * <p>Runs in the headless grant's own server once the wish settles ({@code
 * -Dmoddingfromamod.behaviourexercise=on} beside {@code headlesswish}), so it costs no second boot.
 * It RECORDS and never judges: {@code behaviour.json} in the signal directory holds, per phase, what
 * happened to the monsters, the sheep, the child and the creature, every entity that appeared and
 * every sound the server sent the child; {@code tools/behaviour-judge.py} reads it against the
 * corpus row's expectations. Recording here and judging there means a judge change re-scores
 * every stored run for nothing.
 *
 * <h2>The stand-in child</h2>
 *
 * <p>The grant's wisher is a {@code FakePlayer}, which is in no level and no player list — so an
 * effect could never have hit it, which is exactly the half (effects hitting the rider
 * and the child) a FakePlayer would have hidden. The exercise therefore puts a mock player IN the
 * player list ({@link MockPlayerNetwork#inServer}), with the wisher's own name and id, in survival:
 * a selector's {@code @a}/{@code @p}/{@code @e}, a rider and a possible victim, as a child is.
 *
 * <h2>What it cannot see</h2>
 *
 * <p>Particles, and how anything LOOKS or SOUNDS. A sound here is "the server sent it"; that the
 * client played it and that it is the right sound is {@code tools/audio-check.py}'s question.
 */
public final class BehaviourExercise {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);
    private static final String TAG = "[behaviour]";
    public static final String ENEMY_TAG = "bx_enemy";
    public static final String FRIEND_TAG = "bx_friend";
    private static final List<EntityType<? extends Mob>> ENEMY_KINDS =
            List.of(EntityType.HUSK, EntityType.VINDICATOR, EntityType.SPIDER);

    private BehaviourExercise() {
    }

    /**
     * <b>Filming</b> ({@code -Dmoddingfromamod.bxfilm=on}): the {@link ExercisePlan#FILM} script,
     * and the child's camera kept on the creature every tick, so a recording of his screen is a
     * recording of what it did (tools/film-abilities.sh).
     */
    static boolean filming() {
        String f = System.getProperty(ModdingFromAModMod.MODID + ".bxfilm", "");
        return "on".equalsIgnoreCase(f) || "walk".equalsIgnoreCase(f) || "ride".equalsIgnoreCase(f)
                || "voice".equalsIgnoreCase(f) || "life".equalsIgnoreCase(f) || "throw".equalsIgnoreCase(f);
    }

    /** The throw film ({@code bxfilm=throw}): one monster, held still, watched side on. */
    static boolean throwFilm() {
        return "throw".equalsIgnoreCase(System.getProperty(ModdingFromAModMod.MODID + ".bxfilm", ""));
    }

    /** The film's script: {@code on} the fight, {@code walk} still-then-walking, {@code ride} his ride and jump. */
    static java.util.List<ExercisePlan.Phase> filmScript() {
        return switch (System.getProperty(ModdingFromAModMod.MODID + ".bxfilm", "").toLowerCase(java.util.Locale.ROOT)) {
            case "walk" -> ExercisePlan.FILM_WALK;
            case "ride" -> ExercisePlan.FILM_RIDE;
            case "voice" -> ExercisePlan.FILM_VOICE;
            case "life" -> ExercisePlan.FILM_LIFE;
            case "throw" -> ExercisePlan.FILM_THROW;
            default -> ExercisePlan.FILM;
        };
    }

    /**
     * A film of the creature ALONE ({@code -Dmoddingfromamod.bxalone=on}): no monsters and no sheep.
     * For a voice film, where four monsters and a sheep grunting beside it left no sound of its own
     * clear enough to judge (chunk 4). Never for a panel: the monsters are half the checks.
     */
    static boolean alone() {
        return filming() && "on".equalsIgnoreCase(System.getProperty(ModdingFromAModMod.MODID + ".bxalone", ""));
    }

    /**
     * The film's camera ({@code -Dmoddingfromamod.bxcam}): {@code follow} (the default) keeps him
     * at a filming distance from the creature, turning to it; {@code overhead} parks him high over
     * the arena, still, looking down on all of it — for a move whose point is WHERE it goes (a
     * blink, a shot, a rush), which a camera that follows the creature hides (a teleport film was
     * impossible to follow that way).
     */
    static boolean overhead() {
        return "overhead".equalsIgnoreCase(System.getProperty(ModdingFromAModMod.MODID + ".bxcam", ""));
    }

    /**
     * Who lands the film's blow ({@code -Dmoddingfromamod.bxhurtby}): the child (default) or
     * {@code monster}, for a move that answers what hurt it — a summoner's helpers going for the
     * monster that hit it. A friendly creature's helpers never go for the child, so a punch from him
     * shows nothing.
     */
    static boolean monsterHits() {
        return "monster".equalsIgnoreCase(System.getProperty(ModdingFromAModMod.MODID + ".bxhurtby", ""));
    }

    /**
     * The film's setting ({@code -Dmoddingfromamod.bxscene=meadow}): a grass floor and a
     * ring of trees and flowers OUTSIDE the arena, for a film the public sees (the site carousel),
     * where a grey stone plain made for measuring reads as a test. Everything inside the arena is
     * as it is for a measurement — flat, clear, walled with barrier, no natural spawns — so the
     * scene changes the picture and nothing the judge reads.
     */
    static boolean scenic() {
        return "meadow".equalsIgnoreCase(System.getProperty(ModdingFromAModMod.MODID + ".bxscene", ""));
    }

    /** Where the overhead camera floats: up and to one side, the whole arena in frame. */
    private static Vec3 overheadSpot() {
        return centre.add(6, 8, -1.5);
    }

    /**
     * A REAL joined player to use as the child ({@code -Dmoddingfromamod.bxchild=<name>}), for a
     * film: a stock client whose screen is recorded. Null when unset or not online.
     */
    static ServerPlayer realChild(MinecraftServer server) {
        String name = System.getProperty(ModdingFromAModMod.MODID + ".bxchild", "");
        return name.isEmpty() || server == null ? null : server.getPlayerList().getPlayerByName(name);
    }

    /** Opt-in, beside a headless wish: {@code -Dmoddingfromamod.behaviourexercise=on}. */
    public static boolean enabled() {
        return "on".equalsIgnoreCase(System.getProperty(ModdingFromAModMod.MODID + ".behaviourexercise", ""));
    }

    private static boolean running;
    private static int tick;
    private static Path out;
    private static Runnable onDone;
    private static ServerLevel level;
    private static Mob creature;
    private static Vec3 centre;
    private static ServerPlayer child;
    private static final List<Mob> enemies = new ArrayList<>();
    private static Mob friend;
    private static final Set<UUID> known = new HashSet<>();
    private static final Map<UUID, Vec3> enemyStart = new HashMap<>();
    private static final Map<UUID, Float> healthStart = new HashMap<>();
    private static final Map<UUID, Vec3> enemyLast = new HashMap<>();
    private static final JsonObject report = new JsonObject();
    private static final JsonArray phases = new JsonArray();
    private static JsonObject cur;
    private static Phase curPhase;
    private static Vec3 lastPos;
    private static Map<BlockPos, BlockState> blocksAtStart;
    private static float childHealthStart;
    private static Vec3 childStart;
    private static UUID creatureId;

    public static boolean running() {
        return running;
    }

    /**
     * Start on the creature this wish made. {@code done} runs once {@code behaviour.json} is
     * written (the probe halts the server there).
     */
    public static void begin(MinecraftServer server, Path sigDir, String wish, UUID wisherId, String wisherName,
                             Runnable done) {
        out = sigDir;
        onDone = done;
        level = server.overworld();
        report.addProperty("wish", wish);
        ExercisePlan.use(filming() ? filmScript() : ExercisePlan.PHASES);
        report.addProperty("plan", ExercisePlan.active().toString());
        wisher = new Object[] {wisherId, wisherName};
        waiting = 0;
        running = true;
        LOGGER.info("{} waiting for the wished creature", TAG);
    }

    private static Object[] wisher;
    private static ServerPlayer standIn;

    /**
     * <b>The child who WISHED, in the world from the start</b> — a stock mock in the player list,
     * survival, standing where the probe would have stood its FakePlayer. With the exercise on, the
     * wish is made AS him: the creature is spawned at a child who is there (a FakePlayer is not, and
     * "Nobody is in the world, so I didn't spawn one" was the first staged run's whole result), and
     * the same child is then the rider and the bystander. Channel-less, as a stock child is.
     */
    public static ServerPlayer standInChild(ServerLevel level, UUID id, String name) {
        if (standIn == null) {
            standIn = MockPlayerNetwork.asSurvival(MockPlayerNetwork.inServer(level, name, id, false));
        }
        return standIn;
    }
    /** Ticks spent looking for the creature; -1 once the exercise proper has begun. */
    private static int waiting = -1;
    /** A creature is revealed a few seconds after the tool returns (the reveal), so look for a while. */
    private static final int WAIT_TICKS = 400;

    private static void arrive() {
        creature = findCreature(level);
        if (creature == null) {
            if (++waiting < WAIT_TICKS) {
                return;
            }
            running = false;
            report.addProperty("error", "no wished creature in the world after " + WAIT_TICKS
                    + " ticks (no entity tagged " + WishMobTags.ALL + ")");
            write();
            onDone.run();
            return;
        }
        int waited = waiting;
        waiting = -1;
        UUID wisherId = (UUID) wisher[0];
        String wisherName = (String) wisher[1];
        report.addProperty("waitedTicks", waited);
        creatureId = creature.getUUID();
        BlockPos at = creature.blockPosition();
        arena(level, at);
        if (scenic()) {
            meadow(level, at);
        }
        if (pit()) {
            pit(level, at);
        }
        centre = Vec3.atBottomCenterOf(at);
        quietWorld(server(level), at);
        creature.teleportTo(centre.x, centre.y, centre.z);
        report.addProperty("creature", BuiltInRegistries.ENTITY_TYPE.getKey(creature.getType()).toString());
        report.addProperty("creatureTags", String.join(",", creature.getTags()));
        report.addProperty("creatureMaxHealth", creature.getMaxHealth());
        report.addProperty("creatureWidth", creature.getBbWidth());
        report.addProperty("creatureHeight", creature.getBbHeight());
        report.addProperty("creaturesTagged", level.getEntities(EntityTypeTestAll.INSTANCE,
                e -> e.getTags().contains(WishMobTags.ALL)).size());
        ServerPlayer real = realChild(level.getServer());
        child = real != null ? real : standIn != null ? standIn : MockPlayerNetwork.asSurvival(
                MockPlayerNetwork.inServer(level, wisherName, wisherId, false));
        child.getInventory().clearContent();
        place(child, ExercisePlan.CHILD);
        for (Entity e : level.getEntities((Entity) null, around(centre, 64), x -> true)) {
            known.add(e.getUUID());
        }
        SoundLedger.start();
        SoundLedger.follow(() -> creature == null ? null
                : new double[]{creature.getX(), creature.getY(), creature.getZ()});
        tick = -1;
        if (slowMotion() > 1) {
            // A film in slow motion (bxslowmo=N): the world runs N times slower while it is filmed, so
            // a software-rendered client draws N times the frames of every movement, and the video is
            // sped back up N times. Measured 2026-11-02: 6-8 distinct frames a second at full speed.
            level.getServer().tickRateManager().setTickRate(20f / slowMotion());
        }
        LOGGER.info("{} exercising {} ({}) at {} for {} ticks", TAG, report.get("creature").getAsString(),
                creature.getTags(), at.toShortString(), ExercisePlan.totalTicks());
    }

    public static void tick(MinecraftServer server) {
        if (!running) {
            return;
        }
        if (waiting >= 0) {
            arrive();
            return;
        }
        tick++;
        SoundLedger.tick(tick);
        if (tick == CLAP_TICK && filming()) {
            // A CLAPPERBOARD for the film's recording (chunk 4): a note-block pling at the child,
            // which tools/voice-check.py finds in the sound track and places every sound the server
            // sent against — a recording's first seconds are lost, so nothing is located by wall
            // clock (docs/AUDIO.md). Two seconds in, never on the first tick: a client that has just
            // joined is still loading, and a pling it played late put every sound after it ~2 s
            // early (the first voice film found the END of a howl where its start should be).
            level.playSound(null, child.getX(), child.getY(), child.getZ(),
                    net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(),
                    net.minecraft.sounds.SoundSource.MASTER, 1.0f, 1.0f);
            report.addProperty("clapWallMs", System.currentTimeMillis());
        }
        Phase p = ExercisePlan.at(tick);
        int into = ExercisePlan.into(tick);
        if (into == 0 || p == null) {
            endPhase();
            if (p == null) {
                finish();
                return;
            }
            startPhase(p);
        }
        try {
            drive(p, into);
        } catch (RuntimeException e) {
            note("drive threw: " + e);
        }
        observe(p, into);
        FilmOnlooker.tick(level.getServer(), creature);   //: the camera, when it is not the child
        if (filming() && throwFilm() && child.getVehicle() == null) {
            // Side on, still, looking at the middle of the throw: the ball crosses the frame.
            Vec3 o = centre.add(ExercisePlan.THROW_CAMERA[0], 0, ExercisePlan.THROW_CAMERA[1]);
            if (horizontal(child.position(), o) > 0.5) {
                child.teleportTo(o.x, groundBelow(o.add(0, 2, 0)), o.z);
            }
            child.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    centre.add(0, 1.2, ExercisePlan.THROW_CAMERA[1]));
        } else if (filming() && overhead() && child.getVehicle() == null) {
            // Still, high, looking at the middle of the arena: everything that moves is the scene.
            Vec3 o = overheadSpot();
            if (child.position().distanceTo(o) > 0.5) {
                child.getAbilities().flying = true;
                child.onUpdateAbilities();
                child.teleportTo(o.x, o.y, o.z);
            }
            if (into % 20 == 0) {
                child.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, centre.add(0, 0, -1.5));
            }
        } else if (filming() && creature != null && creature.isAlive() && child.getVehicle() == null) {
            // Keep it in frame: while it roams among the monsters he follows at a filming distance
            // (it walked off to a speck on the first film), every half second so the picture holds.
            if ((p.drive() == Drive.NONE || p.drive() == Drive.STILL) && into % 10 == 0) {
                // By its SIZE, both ways: a tiny voice-film creature was a speck at seven blocks.
                double want = Math.max(2.5, creature.getBbWidth() * 2 + creature.getBbHeight() * 2 + 1.5);
                Vec3 c = creature.position();
                double d = horizontal(child.position(), c);
                double slack = Math.max(1.0, want * 0.4);   // ±3 left a 3.7-block want parked at 6.7
                if (d > want + slack || d < want - slack) {
                    double dx = d < 0.1 ? 1 : (child.getX() - c.x) / d, dz = d < 0.1 ? 0 : (child.getZ() - c.z) / d;
                    Vec3 to = new Vec3(c.x + dx * want, c.y + 2, c.z + dz * want);
                    child.teleportTo(to.x, groundBelow(to), to.z);
                }
            }
            // Keep his eyes on it: the server turns a client's head with a look-at packet.
            child.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    creature.position().add(0, creature.getBbHeight() * 0.5, 0));
        }
    }

    // ── phases ──────────────────────────────────────────────────────────────────────────────────

    private static void startPhase(Phase p) {
        curPhase = p;
        cur = new JsonObject();
        cur.addProperty("name", p.name());
        cur.addProperty("startTick", tick);
        cur.addProperty("startWallMs", System.currentTimeMillis());
        cur.add("notes", new JsonArray());
        cur.add("appeared", new JsonArray());
        enemies.forEach(Entity::discard);
        enemies.clear();
        enemyStart.clear();
        healthStart.clear();
        if (friend != null) {
            friend.discard();
        }
        boolean alive = creature != null && creature.isAlive();
        Vec3 c = alive ? creature.position() : centre;
        // Three KINDS of monster, because an effect is not the same to all of them: an undead husk
        // shrugs off poison and is healed by harming (the second staged run's poison aura caught
        // no monster at all, against three husks), an illager is an ordinary body, a spider an
        // arthropod.
        friend = null;
        if (throwFilm()) {
            Mob m = spawn(EntityType.HUSK, centre.add(ExercisePlan.THROW_ENEMY[0], 0, ExercisePlan.THROW_ENEMY[1]), ENEMY_TAG);
            m.setNoAi(true);   // it stands where the camera expects it, facing nothing
            enemies.add(m);
            enemyStart.put(m.getUUID(), m.position());
            healthStart.put(m.getUUID(), m.getHealth());
        } else if (!alone()) {
            for (int i = 0; i < ExercisePlan.ENEMIES.length; i++) {
                double[] o = ExercisePlan.ENEMIES[i];
                enemies.add(spawn(ENEMY_KINDS.get(i % ENEMY_KINDS.size()), centre.add(o[0], 0, o[1]), ENEMY_TAG));
            }
            enemies.add(spawn(EntityType.HUSK, centre.add(ExercisePlan.FAR_ENEMY[0], 0, ExercisePlan.FAR_ENEMY[1]), ENEMY_TAG));
            friend = spawn(EntityType.SHEEP, centre.add(ExercisePlan.FRIEND[0], 0, ExercisePlan.FRIEND[1]), FRIEND_TAG);
            for (Mob m : enemies) {
                enemyStart.put(m.getUUID(), m.position());
                healthStart.put(m.getUUID(), m.getHealth());
            }
            enemyStart.put(friend.getUUID(), friend.position());
            healthStart.put(friend.getUUID(), friend.getHealth());
        }
        // The child: whole again, no effects, unburnt, where this phase wants him.
        child.setHealth(child.getMaxHealth());
        child.removeAllEffects();
        child.clearFire();
        child.getFoodData().setFoodLevel(20);
        boolean mounted = RideSeat.mountOf(child) == creature;
        // WALK and RIDE run through the monster line from the arena centre (the first baseline
        // started the ride wherever the creature had wandered and rode it into the wall); every
        // other phase brings the monsters to where it is.
        if (alive && (p.drive() == Drive.WALK || p.drive() == Drive.RIDE)) {
            if (mounted) {
                child.stopRiding();
                mounted = false;
            }
            creature.teleportTo(centre.x, centre.y, centre.z - 3);
            c = creature.position();
        } else if (alive && p.drive() != Drive.NONE || alive && p.name().equals("idle")) {
            gatherAround(c);
        }
        switch (p.drive()) {
            case RIDE, RIDER_JUMP, FLY -> {
                southward = true;
                if (!mounted && alive) {
                    child.teleportTo(c.x + creature.getBbWidth() / 2 + 0.8, c.y, c.z);
                    child.setYRot(0f);
                    var r = child.interactOn(creature, InteractionHand.MAIN_HAND);
                    note("climb on: " + r + ", mounted=" + (RideSeat.mountOf(child) == creature));
                }
            }
            case CHILD_JUMP -> {
                child.stopRiding();
                child.teleportTo(c.x + creature.getBbWidth() / 2 + 3, c.y, c.z);
            }
            default -> {
                if (mounted) {
                    child.stopRiding();
                }
                // Beside the creature WHERE IT IS, not where the arena centre is (harness fault 18,
                // chunk 4): a timid little creature had run 25 blocks off by its death, so he
                // stood 30 blocks away and its death cry, played, was out of his hearing — it
                // scored as no voice. The same offset, from it.
                place(child, ExercisePlan.CHILD, alive ? creature.position() : centre);
            }
        }
        child.setXRot(p.drive() == Drive.FLY ? -35f : 0f);
        child.setYRot(0f);
        childHealthStart = child.getHealth();
        childStart = child.position();
        if (alive) {
            cur.addProperty("creatureStartHealth", creature.getHealth());
            cur.add("creatureStart", vec(creature.position()));
            lastPos = creature.position();
        }
        cur.addProperty("pathLength", 0.0);
        cur.addProperty("maxRise", 0.0);
        cur.addProperty("airborneTicks", 0);
        cur.addProperty("maxSpeed", 0.0);
        cur.addProperty("teleports", 0);
        cur.addProperty("maxHealthRegain", 0.0);
        cur.addProperty("mountedTicks", 0);
        cur.addProperty("dismounts", 0);
        cur.addProperty("childMaxRise", 0.0);
        cur.addProperty("childHealthLostMax", 0.0);
        cur.addProperty("childFireTicks", 0);
        cur.addProperty("enemyFireTicks", 0);
        cur.addProperty("enemyMaxDisplaced", 0.0);
        cur.addProperty("enemyMaxRise", 0.0);
        cur.addProperty("enemyMaxSpeed", 0.0);
        enemyLast.clear();
        for (String k : List.of("childEffectsSeen", "enemyEffectsSeen", "friendEffectsSeen")) {
            cur.add(k, new JsonArray());
        }
        blocksAtStart = snapshot();
    }

    private static void endPhase() {
        if (cur == null) {
            return;
        }
        cur.addProperty("endTick", tick);
        cur.addProperty("endWallMs", System.currentTimeMillis());
        JsonArray es = new JsonArray();
        for (Mob m : enemies) {
            es.add(victim(m));
        }
        cur.add("enemies", es);
        cur.add("friend", victim(friend));
        JsonObject c = new JsonObject();
        c.addProperty("healthLost", Math.max(0, childHealthStart - child.getHealth()));
        c.addProperty("dead", !child.isAlive() || child.isDeadOrDying());
        c.addProperty("displaced", childStart == null ? 0 : horizontal(child.position(), childStart));
        c.add("effects", effects(child));
        c.addProperty("onFire", child.getRemainingFireTicks() > 0);
        cur.add("child", c);
        cur.addProperty("creatureAlive", creature != null && creature.isAlive());
        if (creature != null) {
            cur.addProperty("creatureEndHealth", creature.getHealth());
            cur.add("creatureEnd", vec(creature.position()));
        }
        cur.addProperty("blocksChanged", changed(blocksAtStart));
        JsonArray sounds = new JsonArray();
        for (SoundLedger.Sent s : SoundLedger.drain()) {
            JsonObject o = new JsonObject();
            o.addProperty("tick", s.tick());
            o.addProperty("sound", s.sound());
            o.addProperty("source", s.source());
            o.addProperty("volume", s.volume());
            o.addProperty("pitch", s.pitch());
            // Where it stood when the sound went out, not where it has got to now (harness fault 19,
            //): a death cry read 5.1 blocks off because the body had moved on since.
            Vec3 c0 = !Double.isNaN(s.subjectX()) ? new Vec3(s.subjectX(), s.subjectY(), s.subjectZ())
                    : creature != null ? creature.position() : centre;
            if (s.entityId() >= 0) {
                Entity e = level.getEntity(s.entityId());
                o.addProperty("entity", e == null ? "?" : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
                o.addProperty("fromCreature", e != null && (e == creature || ownedBy(e)));
                if (e != null) {
                    o.addProperty("distance", e.position().distanceTo(c0));
                }
            } else {
                o.addProperty("distance", new Vec3(s.x(), s.y(), s.z()).distanceTo(c0));
            }
            sounds.add(o);
        }
        cur.add("sounds", sounds);
        phases.add(cur);
        cur = null;
    }

    private static void drive(Phase p, int into) {
        boolean alive = creature != null && creature.isAlive();
        boolean mounted = alive && RideSeat.mountOf(child) == creature;
        boolean jumpNow = ExercisePlan.jumpNow(p, into);
        switch (p.drive()) {
            case WALK -> {
                if (alive && into % 10 == 0) {
                    creature.getNavigation().moveTo(centre.x, centre.y,
                            centre.z + ExercisePlan.walkZ(into, p.ticks()), 1.0);
                }
            }
            case RIDE -> {
                // Back and forth through the monsters, as a child rides at them: south to past the
                // last one, then north again, never into the arena wall.
                if (alive) {
                    if (creature.getZ() > centre.z + ExercisePlan.WALK_TO + 1) {
                        southward = false;
                    } else if (creature.getZ() < centre.z - 3) {
                        southward = true;
                    }
                    // His HEAD, turned the way a real client obeys: a look-at packet. setYRot alone
                    // steers a mock rider, but a real one (a film's stock client) sends its own
                    // rotation every tick and rode 30 blocks west, away from every monster.
                    double z = southward ? centre.z + ExercisePlan.WALK_TO + 4 : centre.z - 6;
                    child.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                            new Vec3(centre.x, child.getEyeY(), z));
                    child.setYRot(southward ? 0f : 180f);
                }
                hold(true, false);
            }
            case RIDER_JUMP -> hold(false, jumpNow);
            case FLY -> hold(true, true);
            case CHILD_JUMP -> {
                hold(false, false);
                if (jumpNow && child.onGround()) {
                    child.jumpFromGround();
                }
            }
            case HURT -> {
                // HE hits it, through the real attack, because the wishes say "when you hit it";
                // never a monster (a llama carrier spat at the husk that hit it, and that scored as
                // the creature's own move). His punch is a point or two, so a top-up from nobody
                // takes it to a quarter of its health once the hurt cooldown has passed.
                if (alive && into == ExercisePlan.HIT_AT) {
                    Mob nearest = filming() && monsterHits() ? enemies.stream().filter(Mob::isAlive)
                            .min(java.util.Comparator.comparingDouble(m -> m.distanceTo(creature))).orElse(null) : null;
                    if (nearest != null) {
                        // A film's blow from a monster (bxhurtby=monster), through a real mob attack.
                        com.moddingfromamod.compat.Damage.hurt(creature,
                                level.damageSources().mobAttack(nearest), 2.0F);
                    } else {
                        if (!(filming() && overhead())) {   // the overhead camera never leaves its spot
                            child.teleportTo(creature.getX() + creature.getBbWidth() / 2 + 1.0, creature.getY(), creature.getZ());
                            child.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, creature.position());
                        }
                        child.attack(creature);
                    }
                    // ...and down to three quarters IN THE SAME TICK, the low point written here.
                    // A top-up fifteen ticks later (the first version) let a heal answer the punch
                    // first and then sit out its cooldown past the window: the healer read "regained
                    // 0" while it had healed (chunk 1).
                    creature.setHealth(Math.min(creature.getHealth(), creature.getMaxHealth() * 0.75f));
                    cur.addProperty("lowestHealth", creature.getHealth());
                    note("he punched it; down to " + creature.getHealth());
                }
            }
            case DEATH -> {
                if (alive && into == ExercisePlan.KILL_AT) {
                    creature.kill(level);
                    note("killed");
                }
            }
            case STILL -> {
                hold(false, false);
                if (throwFilm()) {
                    // The one monster lives the whole film, so every throw has somewhere to go.
                    enemies.forEach(e -> e.setHealth(e.getMaxHealth()));
                    if (alive && horizontal(creature.position(), centre) > 0.3) {
                        creature.teleportTo(centre.x, creature.getY(), centre.z);
                    }
                }
                if (alive) {
                    // Held where it stands: no path, no sideways motion (it may still turn, swing,
                    // shoot, fall). What happens to the monsters now happens while it stands still.
                    creature.getNavigation().stop();
                    var m = creature.getDeltaMovement();
                    creature.setDeltaMovement(0, m.y, 0);
                }
            }
            case NONE -> {
                hold(false, false);
                // While it stands about, the monsters stand about IT: a pet trots off after the
                // child, and the staged poison aura was judged "quiet" in idle only because it
                // had wandered away from them. Re-gathered every two seconds; a re-gather is a
                // teleport, so it is not counted as a knock.
                if (p.name().equals("idle") && into % 40 == 0 && alive) {
                    if (filming()) {
                        // On film a re-gather is monsters jumping about. It walks back to them instead.
                        if (horizontal(creature.position(), centre) > 6
                                && creature.getDeltaMovement().horizontalDistance() < 0.2) {
                            creature.getNavigation().moveTo(centre.x, centre.y, centre.z, 1.0);
                        }
                    } else {
                        gatherAround(creature.position());
                    }
                }
            }
            default -> hold(false, false);
        }
        if (mounted && jumpNow && p.drive() == Drive.RIDER_JUMP
                && child.getControlledVehicle() instanceof net.minecraft.world.entity.PlayerRideableJumping j
                && j.canJump()) {
            j.handleStartJump(90);   // what START_RIDING_JUMP does, for a mount he controls
        }
    }

    /** The keys the child holds this tick, as the input packet would carry them. */
    /** When the film's clapperboard sounds: settled, before anything it would cover. */
    static final int CLAP_TICK = 30;

    /** Which way the ride is heading along the line of monsters. */
    private static boolean southward = true;

    private static void hold(boolean forward, boolean jump) {
        child.setLastClientInput(new Input(forward, false, false, false, jump, false, false));
    }

    private static void observe(Phase p, int into) {
        if (cur == null) {
            return;
        }
        if (creature != null && creature.isAlive()) {
            Vec3 now = creature.position();
            double step = now.distanceTo(lastPos);
            if (step >= 4.0) {
                inc("teleports", 1);
            } else {
                inc("pathLength", step);
                // Not on a blink: a teleport is 4+ blocks in a tick and read as a 15-block "charge".
                max("maxSpeed", horizontal(now, lastPos));
            }
            double ground = groundBelow(now);
            double rise = now.y - ground;
            max("maxRise", rise);
            if (rise >= 3.0) {
                inc("airborneTicks", 1);
            }
            Number h0 = cur.has("creatureStartHealth") ? cur.get("creatureStartHealth").getAsNumber() : null;
            if (p.drive() == Drive.HURT && into > ExercisePlan.HIT_AT) {
                cur.addProperty("lowestHealth", Math.min(creature.getHealth(),
                        cur.has("lowestHealth") ? cur.get("lowestHealth").getAsFloat() : Float.MAX_VALUE));
                max("maxHealthRegain", creature.getHealth() - cur.get("lowestHealth").getAsFloat());
            }
            lastPos = now;
            if (RideSeat.mountOf(child) == creature) {
                inc("mountedTicks", 1);
                cur.addProperty("wasMounted", true);
            } else if (cur.has("wasMounted") && cur.get("wasMounted").getAsBoolean()) {
                inc("dismounts", 1);
                cur.addProperty("wasMounted", false);
            }
            if (h0 == null) {
                cur.addProperty("creatureStartHealth", creature.getHealth());
            }
        }
        if (childStart != null) {
            max("childMaxRise", child.getY() - childStart.y);
            max("childHealthLostMax", childHealthStart - child.getHealth());
            seen("childEffectsSeen", child);
            if (child.isOnFire()) {
                inc("childFireTicks", 1);
            }
        }
        // Per tick, not only at the end: an effect can wear off and a fire go out before a phase
        // ends, and a knocked-up monster lands again.
        boolean anyFire = false;
        for (Mob m : enemies) {
            seen("enemyEffectsSeen", m);
            anyFire |= m.isOnFire();
            Vec3 s0 = enemyStart.get(m.getUUID());
            if (s0 != null && m.isAlive()) {
                max("enemyMaxDisplaced", horizontal(m.position(), s0));
                max("enemyMaxRise", m.getY() - s0.y);
                // SPEED, not distance, is a knock: a creature walking into a monster shoves it a
                // block or two at a crawl (the staged plain creature did, 1.7 blocks), while a
                // blow sends it off at a third of a block a tick or more.
                Vec3 was = enemyLast.put(m.getUUID(), m.position());
                if (was != null) {
                    max("enemyMaxSpeed", horizontal(m.position(), was));
                }
            }
        }
        if (anyFire) {
            inc("enemyFireTicks", 1);
        }
        if (friend != null && friend.isAlive()) {
            seen("friendEffectsSeen", friend);
        }
        // Anything new near it: a projectile it shot, a helper it called, an item it dropped.
        if (tick % 2 == 0) {
            Vec3 c = creature != null ? creature.position() : centre;
            for (Entity e : level.getEntities((Entity) null, around(c, 40), x -> true)) {
                if (known.add(e.getUUID()) && !e.getTags().contains(ENEMY_TAG) && !e.getTags().contains(FRIEND_TAG)
                        && e != child && !isRigPart(e) && !FilmCamera.isCamera(e)) {   // the film's camera is not its doing
                    JsonObject o = new JsonObject();
                    o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
                    o.addProperty("tick", tick);
                    o.addProperty("projectile", e instanceof Projectile);
                    o.addProperty("living", e instanceof LivingEntity);
                    o.addProperty("wishmob", e.getTags().contains(WishMobTags.ALL));
                    // How far from the creature it appeared: a bee leaving a hive 30 blocks off is
                    // not a helper it called (the staged runs counted three).
                    o.addProperty("distance", e.position().distanceTo(c));
                    cur.getAsJsonArray("appeared").add(o);
                }
            }
        }
    }

    /** The film's slow-motion factor ({@code -Dmoddingfromamod.bxslowmo=N}, 1 = real time). */
    static int slowMotion() {
        try {
            return Math.max(1, Math.min(8, Integer.parseInt(System.getProperty(ModdingFromAModMod.MODID + ".bxslowmo", "1"))));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static void finish() {
        running = false;
        if (slowMotion() > 1) {
            level.getServer().tickRateManager().setTickRate(20f);
        }
        SoundLedger.stop();
        enemies.forEach(Entity::discard);
        if (friend != null) {
            friend.discard();
        }
        if (realChild(level.getServer()) == null) {
            MockPlayerNetwork.remove(child);
        }
        report.add("phases", phases);
        write();
        LOGGER.info("{} done — {}", TAG, out.resolve("behaviour.json"));
        onDone.run();
    }

    private static void write() {
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve("behaviour.json"),
                    new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(report),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("{} could not write behaviour.json: {}", TAG, e.toString());
        }
    }

    // ── the world ───────────────────────────────────────────────────────────────────────────────

    /** The wished creature: the biggest carrier wearing the wish tag (its helpers are smaller). */
    static Mob findCreature(ServerLevel level) {
        Mob best = null;
        for (Entity e : level.getEntities(EntityTypeTestAll.INSTANCE, e -> e.getTags().contains(WishMobTags.ALL)
                && e instanceof Mob && e.isAlive())) {
            Mob m = (Mob) e;
            if (best == null || m.getBbWidth() * m.getBbHeight() > best.getBbWidth() * best.getBbHeight()) {
                best = m;
            }
        }
        return best;
    }

    private static MinecraftServer server(ServerLevel l) {
        return l.getServer();
    }

    /**
     * Nothing turns up that the creature did not bring: no natural spawns (the first staged run
     * counted cod and a night's zombies as "it called helpers"), the ones already about removed,
     * and a clear day. Measured on that run: a plain creature "summoned" 17 things.
     */
    private static void quietWorld(MinecraftServer server, BlockPos at) {
        var rules = level.getGameRules();
        rules.getRule(net.minecraft.world.level.gamerules.GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(net.minecraft.world.level.gamerules.GameRules.RULE_DAYLIGHT).set(false, server);
        rules.getRule(net.minecraft.world.level.gamerules.GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setDayTime(6000);
        level.setWeatherParameters(12000, 0, false, false);
        // Every other creature about, not only monsters: the second staged run's poison aura
        // poisoned a wild PIG, and its hurt squeals near the creature read as the creature's voice.
        for (Entity e : level.getEntities((Entity) null, around(Vec3.atCenterOf(at), 128),
                x -> (x instanceof LivingEntity || x instanceof net.minecraft.world.entity.item.ItemEntity)
                        && !(x instanceof net.minecraft.world.entity.player.Player))) {
            if (!e.getTags().contains(WishMobTags.ALL) && !isRigPart(e)) {
                e.discard();
            }
        }
    }

    /**
     * A PIT ({@code -Dmoddingfromamod.bxpit=on}): a 3-high stone ring 4 blocks round the
     * creature, so a climber's film shows it go up a wall and out, and a walker's shows it cannot.
     */
    static boolean pit() {
        return "on".equalsIgnoreCase(System.getProperty(ModdingFromAModMod.MODID + ".bxpit", ""));
    }

    static void pit(ServerLevel level, BlockPos at) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 4) {
                    continue;
                }
                for (int dy = 0; dy < 3; dy++) {
                    m.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    level.setBlock(m, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** A flat stone floor with air above, so terrain, water and trees are never the finding. */
    static void arena(ServerLevel level, BlockPos at) {
        int r = ExercisePlan.ARENA_RADIUS;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= ExercisePlan.ARENA_HEIGHT; dy++) {
                    m.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    // A WALL of barrier round the edge: a frightened creature walked off the first
                    // arena's edge and its fall onto the terrain below read as a 3-block jump.
                    boolean edge = Math.max(Math.abs(dx), Math.abs(dz)) == r;
                    BlockState st = dy == -1 ? (scenic() ? Blocks.GRASS_BLOCK : Blocks.STONE).defaultBlockState()
                            : edge && dy <= 3 ? Blocks.BARRIER.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    level.setBlock(m, st, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /**
     * Trees and flowers in a ring beyond the arena's wall (radius {@code r + 3} to {@code r + 16}),
     * on whatever ground is there: oaks and birches grown from the game's own tree features, and
     * flowers between them. Fixed seed, so every film of every creature has the same meadow.
     */
    static void meadow(ServerLevel level, BlockPos at) {
        int r = ExercisePlan.ARENA_RADIUS;
        var random = net.minecraft.util.RandomSource.create(1696L);
        var features = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.CONFIGURED_FEATURE);
        var trees = List.of(net.minecraft.data.worldgen.features.TreeFeatures.OAK,
                net.minecraft.data.worldgen.features.TreeFeatures.BIRCH,
                net.minecraft.data.worldgen.features.TreeFeatures.FANCY_OAK);
        List<BlockState> flowers = List.of(Blocks.POPPY.defaultBlockState(), Blocks.DANDELION.defaultBlockState(),
                Blocks.CORNFLOWER.defaultBlockState(), Blocks.OXEYE_DAISY.defaultBlockState(),
                Blocks.SHORT_GRASS.defaultBlockState());
        var generator = level.getChunkSource().getGenerator();
        for (int i = 0; i < 260; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double d = r + 3 + random.nextDouble() * 13;
            int x = at.getX() + (int) Math.round(Math.cos(a) * d);
            int z = at.getZ() + (int) Math.round(Math.sin(a) * d);
            BlockPos top = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
            if (!level.getBlockState(top.below()).is(Blocks.GRASS_BLOCK)) {
                continue;
            }
            if (i % 7 == 0) {
                features.get(trees.get(random.nextInt(trees.size())))
                        .ifPresent(f -> f.value().place(level, generator, random, top));
            } else {
                level.setBlock(top, flowers.get(random.nextInt(flowers.size())), Block.UPDATE_CLIENTS);
            }
        }
    }

    private static Map<BlockPos, BlockState> snapshot() {
        Map<BlockPos, BlockState> map = new HashMap<>();
        BlockPos at = BlockPos.containing(centre);
        int r = ExercisePlan.ARENA_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= ExercisePlan.ARENA_HEIGHT; dy++) {
                    BlockPos p = at.offset(dx, dy, dz);
                    BlockState s = level.getBlockState(p);
                    if (!s.isAir()) {
                        map.put(p, s);
                    }
                }
            }
        }
        return map;
    }

    private static int changed(Map<BlockPos, BlockState> before) {
        if (before == null) {
            return 0;
        }
        Map<BlockPos, BlockState> now = snapshot();
        Set<BlockPos> all = new HashSet<>(before.keySet());
        all.addAll(now.keySet());
        int n = 0;
        for (BlockPos p : all) {
            if (!java.util.Objects.equals(before.get(p), now.get(p))) {
                n++;
            }
        }
        return n;
    }

    private static Mob spawn(EntityType<? extends Mob> type, Vec3 at, String tag) {
        Mob m = type.create(level, EntitySpawnReason.COMMAND);
        m.snapTo(at.x, at.y, at.z, 180f, 0f);
        m.addTag(tag);
        m.setPersistenceRequired();
        var speed = m.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(0.0);   // they stay put, so anything that moves them was the creature
        }
        // ...and they never pick a fight: a husk next to the child bit him (hunger, -10 health on
        // the first staged run) and that read as the creature hurting him.
        var range = m.getAttribute(Attributes.FOLLOW_RANGE);
        if (range != null) {
            range.setBaseValue(0.0);
        }
        var dmg = m.getAttribute(Attributes.ATTACK_DAMAGE);
        if (dmg != null) {
            dmg.setBaseValue(0.0);
        }
        level.addFreshEntity(m);
        return m;
    }

    private static JsonObject victim(Mob m) {
        JsonObject o = new JsonObject();
        if (m == null) {
            return o;
        }
        Float h0 = healthStart.get(m.getUUID());
        o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString());
        o.addProperty("healthLost", h0 == null ? 0 : Math.max(0, h0 - (m.isAlive() ? m.getHealth() : 0)));
        o.addProperty("dead", !m.isAlive());
        Vec3 s = enemyStart.get(m.getUUID());
        o.addProperty("displaced", s == null ? 0 : horizontal(m.position(), s));
        o.addProperty("rose", s == null ? 0 : m.getY() - s.y);
        o.add("effects", effects(m));
        o.addProperty("onFire", m.getRemainingFireTicks() > 0 || m.isOnFire());
        return o;
    }

    private static JsonArray effects(LivingEntity e) {
        JsonArray a = new JsonArray();
        for (MobEffectInstance i : e.getActiveEffects()) {
            a.add(i.getEffect().unwrapKey().map(k -> k.location().toString()).orElse("?"));
        }
        return a;
    }

    private static boolean ownedBy(Entity e) {
        return creatureId != null && e.getTags().contains(
                com.moddingfromamod.vanilla.DisplayRig.RIG_OWNER_PREFIX + creatureId);
    }

    private static boolean isRigPart(Entity e) {
        return ownedBy(e) || e instanceof net.minecraft.world.entity.Display || RideSeat.isSeat(e);
    }

    private static void place(ServerPlayer p, double[] o) {
        place(p, o, centre);
    }

    private static void place(ServerPlayer p, double[] o, Vec3 from) {
        // Far enough to see the whole creature and the monsters round it on a film.
        double k = filming() && creature != null ? Math.max(1.0, (creature.getBbWidth() * 2 + 6) / 7.0) : 1.0;
        Vec3 to = new Vec3(from.x + o[0] * k, from.y + 2, from.z + o[1] * k);
        p.teleportTo(to.x, groundBelow(to), to.z);
    }

    private static double groundBelow(Vec3 v) {
        BlockPos.MutableBlockPos m = BlockPos.containing(v).mutable();
        for (int i = 0; i < 40 && m.getY() > level.getMinY(); i++) {
            m.move(0, -1, 0);
            if (!level.getBlockState(m).isAir()) {
                return m.getY() + 1;
            }
        }
        return v.y;
    }

    private static AABB around(Vec3 c, double r) {
        return new AABB(c.x - r, c.y - r, c.z - r, c.x + r, c.y + r, c.z + r);
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static JsonArray vec(Vec3 v) {
        JsonArray a = new JsonArray();
        a.add(v.x);
        a.add(v.y);
        a.add(v.z);
        return a;
    }

    private static void inc(String k, double by) {
        cur.addProperty(k, cur.get(k).getAsDouble() + by);
    }

    private static void max(String k, double v) {
        if (v > cur.get(k).getAsDouble()) {
            cur.addProperty(k, v);
        }
    }

    /**
     * The monsters round the creature where it stands, a step outside its body. Every phase that
     * does not move it starts this way: the first Opus baseline left them at the arena centre while
     * the creature stood 20 blocks off by the wall after its ride, so no landing, hurt or death could
     * reach them. A gather is a teleport, so it is never counted as a knock.
     */
    private static void gatherAround(Vec3 c) {
        for (int i = 0; i < ExercisePlan.ENEMIES.length && i < enemies.size(); i++) {
            double[] o = ExercisePlan.ENEMIES[i];
            Mob m = enemies.get(i);
            double r = creature.getBbWidth() / 2 + 1.5;
            double len = Math.hypot(o[0], o[1] - 4);
            Vec3 to = c.add(o[0] / Math.max(len, 1e-3) * r, 0, (o[1] - 4) / Math.max(len, 1e-3) * r);
            m.teleportTo(to.x, c.y, to.z);
            enemyLast.remove(m.getUUID());
            enemyStart.put(m.getUUID(), m.position());
        }
    }

    private static void seen(String key, LivingEntity e) {
        JsonArray a = cur.getAsJsonArray(key);
        for (var el : effects(e)) {
            if (!a.contains(el)) {
                a.add(el);
            }
        }
    }

    private static void note(String s) {
        if (cur != null) {
            cur.getAsJsonArray("notes").add(tick + ": " + s);
        }
        LOGGER.info("{} {}", TAG, s);
    }

    /** {@code level.getEntities(test, predicate)} wants a type test; this one admits everything. */
    private enum EntityTypeTestAll implements net.minecraft.world.level.entity.EntityTypeTest<Entity, Entity> {
        INSTANCE;

        @Override
        public Entity tryCast(Entity e) {
            return e;
        }

        @Override
        public Class<? extends Entity> getBaseClass() {
            return Entity.class;
        }
    }
}
