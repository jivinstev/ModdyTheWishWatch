package com.moddingfromamod.wish;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.Landing;
import com.moddingfromamod.marshal.Marshal;
import com.moddingfromamod.marshal.Spot;
import com.moddingfromamod.marshal.TripHome;
import com.moddingfromamod.marshal.WishRun;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>"Take me to my wish."</b>
 *
 * <p>Every wish already stores the exact spot it was aimed at — the locked anchor, per run — plus
 * its dimension. So this is a lookup, not new machinery, and everything above it (the chat link, the
 * watch's Happening-now rows, a HUD pointer) is a thin skin over one command that can also be typed
 * and tested.
 *
 * <p>Three rules, each of which is a real failure if skipped:
 *
 * <ol>
 *   <li><b>Never land inside the build.</b> The volume is being written at 8,000 blocks a tick;
 *       arriving inside it means suffocating in a wall that did not exist a tick ago. He arrives
 *       {@link Landing#STAND_OFF} blocks back, at eye level, facing it, on something solid — a
 *       temporary glass pad if there is nothing.</li>
 *   <li><b>Brief invulnerability on arrival.</b> He may be 3,000 blocks from anywhere he knows, in
 *       the dark, next to whatever the wish summoned.</li>
 *   <li><b>Always offer the way home.</b> One remembered departure point per player, offered as
 *       {@code [take me back]}. A child teleported far away and unable to find his base again is a
 *       session-ending disaster, and it is entirely preventable.</li>
 * </ol>
 */
public final class WishGo {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    /** Long enough to look around and not die doing it. */
    private static final int ARRIVAL_GRACE_TICKS = 20 * 10;

    /** Where he was before he asked to be taken somewhere. */
    public record Home(String dimension, double x, double y, double z, float yaw, float pitch,
                       long departedMs) {
    }

    private static final Map<UUID, Home> HOMES = new ConcurrentHashMap<>();

    /** Where the last hop put him down, so we can tell "still visiting" from "went off to play". */
    private static final Map<UUID, Home> LANDINGS = new ConcurrentHashMap<>();

    private WishGo() {
    }

    /** What happened, so a command can report it and a gate can assert it. */
    public enum Result { WENT, NOTHING_TO_SEE, NO_SUCH_WISH, WRONG_WORLD }

    /**
     * Take him to a wish of his.
     *
     * @param shortId which one, or blank for "whatever you are making for me"
     */
    public static Result go(ServerPlayer player, String shortId) {
        if (player == null) {
            return Result.NOTHING_TO_SEE;
        }
        Optional<WishRun> target = pick(player, shortId);
        if (target.isEmpty()) {
            tell(player, "🤔 I haven't got anything to show you yet!", ChatFormatting.GRAY);
            return shortId == null || shortId.isBlank() ? Result.NOTHING_TO_SEE : Result.NO_SUCH_WISH;
        }
        WishRun run = target.get();
        Spot spot = run.anchor();
        if (spot == null) {
            tell(player, "🤔 I don't know where that one is going yet — give me a moment!",
                    ChatFormatting.GRAY);
            return Result.NOTHING_TO_SEE;
        }
        ServerLevel level = levelFor(player, spot.dimension());
        if (level == null) {
            tell(player, "🤔 That one is in a different world and I can't take you there.",
                    ChatFormatting.GRAY);
            return Result.WRONG_WORLD;
        }

        land(player, level, spot.x(), spot.y(), spot.z(), run.subject(), "wish " + run.shortId());
        return Result.WENT;
    }

    /**
     * <b>Take him to one of his THINGS</b> ("take me to my castle"): a standing building of his, or a creature of
     * his near enough to find, found the one way the game finds a named thing ({@code place/PlaceFinder}, its front).
     * Says nothing when it is not found, so the caller can let his words go on as typed.
     */
    public static Result goThing(ServerPlayer player, String thingId, String name) {
        if (player == null || thingId == null || thingId.isBlank()) {
            return Result.NOTHING_TO_SEE;
        }
        var place = com.moddingfromamod.place.PlaceFinder.find(player.level().getServer(), player, thingId + ":front", "front");
        if (!place.ok()) {
            return Result.NO_SUCH_WISH;
        }
        BlockPos at = place.block();
        land(player, place.level(), at.getX(), at.getY(), at.getZ(), name == null ? "" : name, "thing " + thingId);
        return Result.WENT;
    }

    /** Put him down beside a spot: the stand-off, a surface under his feet, the arrival grace and the way home. */
    private static void land(ServerPlayer player, ServerLevel level, int x, int y, int z, String subject,
                             String what) {
        rememberHome(player);
        var landing = Landing.standOff(x, y, z, player.getX(), player.getZ());
        BlockPos feet = surfaceNear(level, BlockPos.containing(landing.x(), landing.y(), landing.z()));
        standOnSomething(level, feet);

        com.moddingfromamod.compat.Teleports.player(player, level, landing.x(), feet.getY(), landing.z(),
                landing.yaw(), landing.pitch());
        // Where he was put down, so the NEXT hop can tell "still looking at this" from "went off
        // and played somewhere else".
        LANDINGS.put(player.getUUID(), new Home(level.dimension().identifier().toString(),
                landing.x(), feet.getY(), landing.z(),
                landing.yaw(), landing.pitch(), System.currentTimeMillis()));
        // He may have arrived in the dark, 3,000 blocks from anywhere, beside whatever the wish
        // summoned. The same seam WishCapture already uses for the photograph.
        player.setInvulnerable(true);
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.RESISTANCE, ARRIVAL_GRACE_TICKS, 4,
                false, false));
        arrive(player);

        tell(player, "✨ Here it is! " + (subject.isEmpty() ? "" : "(" + subject + ") "),
                ChatFormatting.LIGHT_PURPLE);
        offerWayHome(player);
        LOGGER.info("took {} to {} at {}", player.getName().getString(), what, feet.toShortString());
    }

    /**
     * Put him back where he was.
     *
     * <p>Exactly where — same position, same view. "Somewhere near where you were" is how a child
     * ends up unable to find his own front door.
     */
    public static boolean back(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        Home home = HOMES.remove(player.getUUID());
        if (home == null) {
            tell(player, "🤔 I don't know where you were before!", ChatFormatting.GRAY);
            return false;
        }
        ServerLevel level = levelFor(player, home.dimension());
        if (level == null) {
            tell(player, "🤔 I can't find the way back to that world, sorry!", ChatFormatting.GRAY);
            return false;
        }
        com.moddingfromamod.compat.Teleports.player(player, level, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
        player.setInvulnerable(false);
        arrive(player);
        tell(player, "🏠 Back where you were!", ChatFormatting.AQUA);
        return true;
    }

    /** Is the way home still on offer? */
    public static boolean canGoBack(UUID playerId, long nowMs) {
        Home home = playerId == null ? null : HOMES.get(playerId);
        return home != null && Landing.wayHomeOpen(home.departedMs(), nowMs);
    }

    /** Forget everything (world unload, logout). */
    public static void clear() {
        HOMES.clear();
        LANDINGS.clear();
    }

    public static void forget(UUID playerId) {
        HOMES.remove(playerId);
        LANDINGS.remove(playerId);
    }

    // ── the affordances ──────────────────────────────────────────────────────────────────────

    /**
     * <b>"✨ Your ice dragon is ready!" — and how to go and see it</b>, in words rather than a
     * "[take me there]" link (2026-11-10, a Bedrock design decision): the watch's own tile does it, on
     * every edition. Only said when the thing landed somewhere he is not.
     */
    public static void offerToTakeHim(ServerPlayer player, WishRun run) {
        if (player == null || run == null || run.anchor() == null) {
            return;
        }
        Spot spot = run.anchor();
        boolean elsewhere = !spot.dimension().isEmpty()
                && !spot.dimension().equals(player.level().dimension().identifier().toString());
        if (!elsewhere) {
            double distSq = spot.distanceSqTo(player.getX(), player.getY(), player.getZ(),
                    player.level().dimension().identifier().toString());
            if (distSq >= 0 && distSq < NEARBY_SQ) {
                return;   // he is already looking at it
            }
        }
        tell(player, TAKE_ME_THERE_HINT, ChatFormatting.AQUA);
    }

    /** Said when the thing is out of sight. */
    public static final String TAKE_ME_THERE_HINT =
            "   🔍 Want to see it? Open your wish watch and press " + WatchHome.WATCH + ".";

    /** Said when he arrives: the way back is the watch's first tile. */
    public static final String WAY_HOME_HINT =
            "   To go back, open your wish watch and press " + WatchHome.BACK + ".";

    /** Inside this, he can already see it, so do not offer a trip. 24 blocks. */
    private static final double NEARBY_SQ = 24 * 24;

    private static void offerWayHome(ServerPlayer player) {
        tell(player, WAY_HOME_HINT, ChatFormatting.YELLOW);
    }

    // ── the details that bite ────────────────────────────────────────────────────────────────

    /**
     * Which wish he means. Blank is "whatever you are making for me right now"; a seven-year-old
     * clicking a link has not read an id and should never have to.
     */
    static Optional<WishRun> pick(ServerPlayer player, String shortId) {
        if (player == null) {
            return Optional.empty();
        }
        Marshal marshal = Marshal.global();
        List<WishRun> mine = marshal.liveFor(player.getUUID());
        if (shortId != null && !shortId.isBlank()) {
            String wanted = shortId.trim();
            for (WishRun r : marshal.all()) {
                if (r.shortId().equalsIgnoreCase(wanted)
                        && player.getUUID().equals(r.playerId())) {
                    return Optional.of(r);   // finished ones too: "take me to the thing you made"
                }
            }
            return Optional.empty();
        }
        if (!mine.isEmpty()) {
            return Optional.of(mine.get(mine.size() - 1));
        }
        WishRun last = marshal.lastCompleted(player.getUUID());
        return Optional.ofNullable(last);
    }

    /**
     * Remember where he is standing NOW, unless he is mid-trip.
     *
     * <p>Hop → hop must still lead back to where he lives rather than to the wish he visited in
     * between, which is why this is not an unconditional overwrite. But it used to be
     * {@code computeIfAbsent} and the entry was only ever cleared by actually pressing "take me
     * back" — so a child who teleported out to look at something and then just carried on playing
     * left that entry behind forever, and his next "take me back" threw him to wherever he had been
     * standing that time. Steve hit exactly that.
     *
     * <p>{@link TripHome} draws the line: he is still on the trip if he is near where the last hop
     * put him down. See there for why that is the right question.
     */
    private static void rememberHome(ServerPlayer player) {
        UUID id = player.getUUID();
        Home landed = LANDINGS.get(id);
        boolean sameWorld = landed != null
                && landed.dimension().equals(player.level().dimension().identifier().toString());
        double away = landed == null || !sameWorld ? -1
                : Math.sqrt(player.distanceToSqr(landed.x(), landed.y(), landed.z()));
        if (TripHome.shouldRecord(HOMES.containsKey(id), sameWorld, away)) {
            HOMES.put(id, new Home(
                    player.level().dimension().identifier().toString(),
                    player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot(), System.currentTimeMillis()));
        }
    }

    /**
     * <b>Somewhere he can actually see from.</b>
     *
     * <p>Found by looking at the Gate-C screenshot, which is the only thing that could have found
     * it: every assertion was green — he travelled, he landed the right distance off, he was not
     * inside a block — and the picture was <b>almost entirely black</b>, because a wish aimed 400
     * blocks away at his own height lands inside a hillside. "✨ Here it is!" over a black screen
     * reads to a child as broken, not as underground.
     *
     * <p>So the landing rises to the first place with air over its head, up to {@link #MAX_LIFT}.
     * Deliberately bounded and deliberately upward-only: dropping him instead would put him under
     * the thing he came to see, and an unbounded search would fire him into the sky over a deep
     * cave. If nothing is open within the limit, the original spot stands — being underground with
     * a glass pad is worse than being underground, but it is not worse than not arriving.
     */
    private static final int MAX_LIFT = 40;

    private static BlockPos surfaceNear(ServerLevel level, BlockPos feet) {
        for (int dy = 0; dy <= MAX_LIFT; dy++) {
            BlockPos at = feet.above(dy);
            if (at.getY() + 1 >= com.moddingfromamod.compat.LevelHeights.topExclusive(level)) {
                break;
            }
            boolean roomForHim = level.getBlockState(at).isAir()
                    && level.getBlockState(at.above()).isAir();
            // Two clear blocks is standing room; a third means he can also SEE, which is the whole
            // point of the lift.
            boolean canLookAround = level.getBlockState(at.above(2)).isAir();
            if (roomForHim && canLookAround) {
                return at;
            }
        }
        return feet;
    }

    /**
     * Give him something to stand on.
     *
     * <p>A wish anchored in mid-air — "make a castle in the sky" — has no floor, and dropping him
     * into it means watching his own wish recede upwards as he falls. Glass, so it is obvious it is
     * ours and it does not block the view of the thing he came to see.
     */
    private static void standOnSomething(ServerLevel level, BlockPos feet) {
        BlockPos under = feet.below();
        if (level.getBlockState(under).isAir()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pad = under.offset(dx, 0, dz);
                    if (level.getBlockState(pad).isAir()) {
                        level.setBlockAndUpdate(pad, Blocks.GLASS.defaultBlockState());
                    }
                }
            }
        }
        // And clear his head, so a stand-off spot inside a hillside is not a suffocation.
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos at = feet.above(dy);
            if (!level.getBlockState(at).isAir()
                    && level.getBlockState(at).getDestroySpeed(level, at) >= 0) {
                level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static ServerLevel levelFor(ServerPlayer player, String dimension) {
        if (dimension == null || dimension.isEmpty()) {
            return player.level();
        }
        Identifier id = Identifier.tryParse(dimension);
        if (id == null) {
            return player.level();
        }
        return player.level().getServer().getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, id));
    }

    private static void arrive(ServerPlayer player) {
        var level = player.level();
        level.sendParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 1, player.getZ(),
                40, 0.6, 1.0, 0.6, 0.4);
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.PLAYERS, 0.8f, 1.3f);
    }

    private static void tell(ServerPlayer player, String text, ChatFormatting colour) {
        player.sendSystemMessage(Component.literal(text).setStyle(Style.EMPTY.withColor(colour)));
    }

    /** For the gate: which level a dimension string resolves to. */
    static ServerLevel resolve(ServerPlayer player, String dimension) {
        return levelFor(player, dimension);
    }

    /** Convenience for tests: the remembered home, if any. */
    static Home homeOf(UUID playerId) {
        return HOMES.get(playerId);
    }

    /** The overworld key, so a caller need not import the registry. */
    public static String overworld() {
        return Level.OVERWORLD.identifier().toString();
    }
}
