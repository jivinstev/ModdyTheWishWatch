package com.moddingfromamod.vanilla;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.Voice;
import com.moddingfromamod.species.VoiceSounds;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.SoundType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * <b>A creature's voice, on its rig</b> (chunk 4): its idle sound now and then, a cry when
 * it is hurt, one when it dies, and its footfalls — the four moments a vanilla mob is heard.
 *
 * <p>The carrier is muted ({@code DisplayRig}: a horse's whinny out of a dragon), and before this
 * nothing played anything in its place, so every wished creature was silent. The sounds are the
 * species' {@link Voice}, resolved by {@link VoiceSounds}, pitched and pushed further by its size.
 *
 * <p>⚠ <b>Played at a POSITION, never bound to the carrier.</b> A sound bound to an entity is
 * dropped by the client when that entity is silent — and the carrier is — so an entity-bound voice
 * would be sent, logged and never heard.
 *
 * <p>The ambient clock is vanilla's ({@code Mob.baseTick}): a hurt resets it, as it does there.
 * A creature still being wished is quiet, as it is still: the reveal is when it is heard.
 */
@EventBusSubscriber(modid = ModdingFromAModMod.MODID)
public final class RigVoice {

    private static final Map<UUID, int[]> AMBIENT = new ConcurrentHashMap<>();
    /** Per carrier: {blocks walked since the last footfall, last x, last z}. */
    private static final Map<UUID, double[]> WALKED = new ConcurrentHashMap<>();
    private static final Map<Voice, VoiceSounds.Resolved> RESOLVED = new ConcurrentHashMap<>();
    /** What each carrier was last heard to play, newest last — for the GameTests; small and bounded. */
    private static final Map<UUID, List<String>> HEARD = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, List<String>> e) {
                    return size() > 256;
                }
            });
    private static volatile Set<String> known;

    private RigVoice() {
    }

    /** Every sound id this game knows. */
    public static Set<String> knownSounds() {
        Set<String> k = known;
        if (k == null) {
            k = BuiltInRegistries.SOUND_EVENT.keySet().stream().map(ResourceLocation::toString)
                    .collect(Collectors.toUnmodifiableSet());
            known = k;
        }
        return k;
    }

    public static VoiceSounds.Resolved resolve(Voice v) {
        return RESOLVED.computeIfAbsent(v, x -> VoiceSounds.resolve(x, knownSounds()));
    }

    /** One tick of one driven carrier: its idle sound and its footfalls. */
    public static void tick(Mob carrier, Species built) {
        if (!(carrier.level() instanceof ServerLevel level) || !carrier.isAlive() || carrier.deathTime > 0) {
            return;
        }
        Species sp = live(carrier, built);
        if (sp == null || sp.voice().isNone() || sp.voice().silent() || RigPowers.stillBeingWished(carrier, sp)) {
            return;
        }
        VoiceSounds.Resolved r = resolve(sp.voice());
        UUID id = carrier.getUUID();
        int[] clock = AMBIENT.computeIfAbsent(id, k -> new int[]{-VoiceSounds.AMBIENT_INTERVAL});
        if (VoiceSounds.ambientFires(clock[0]++, carrier.getRandom().nextInt(1000))) {
            clock[0] = -VoiceSounds.AMBIENT_INTERVAL;
            play(level, carrier, r.get(Voice.Slot.AMBIENT), VoiceSounds.volume(sp.scale()), pitch(carrier, sp));
        }
        double[] w = WALKED.computeIfAbsent(id, k -> new double[]{0, carrier.getX(), carrier.getZ()});
        double d = Math.hypot(carrier.getX() - w[1], carrier.getZ() - w[2]);
        w[1] = carrier.getX();
        w[2] = carrier.getZ();
        if (d > 1.0) {
            return;   // a teleport, not a stride
        }
        if (carrier.onGround()) {
            w[0] += d;
        }
        if (w[0] >= VoiceSounds.stride(sp.scale())) {
            w[0] = 0;
            step(level, carrier, sp, r);
        }
    }

    private static void step(ServerLevel level, Mob carrier, Species sp, VoiceSounds.Resolved r) {
        String own = r.get(Voice.Slot.STEP);
        if (own != null) {
            play(level, carrier, own, VoiceSounds.stepVolume(sp.scale()), pitch(carrier, sp));
        } else if (r.blockSteps()) {
            // A mob with no step of its own walks on the block's, as vanilla's do.
            SoundType t = level.getBlockState(carrier.getOnPos()).getSoundType(level, carrier.getOnPos(), carrier);
            playEvent(level, carrier, t.getStepSound(), t.getVolume() * VoiceSounds.stepVolume(sp.scale()), t.getPitch());
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof Mob carrier) || !(carrier.level() instanceof ServerLevel level)
                || !VanillaRigAnimator.isDriving(carrier) || carrier.isDeadOrDying()) {
            return;   // the blow that kills it is its death cry, not a hurt one
        }
        Species sp = live(carrier, null);
        if (sp == null || sp.voice().isNone() || sp.voice().silent()) {
            return;
        }
        AMBIENT.put(carrier.getUUID(), new int[]{-VoiceSounds.AMBIENT_INTERVAL});
        play(level, carrier, resolve(sp.voice()).get(Voice.Slot.HURT), VoiceSounds.volume(sp.scale()), pitch(carrier, sp));
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob carrier) || !(carrier.level() instanceof ServerLevel level)
                || !VanillaRigAnimator.isDriving(carrier)) {
            return;
        }
        Species sp = live(carrier, null);
        if (sp != null && !sp.voice().isNone() && !sp.voice().silent()) {
            play(level, carrier, resolve(sp.voice()).get(Voice.Slot.DEATH), VoiceSounds.volume(sp.scale()), pitch(carrier, sp));
        }
        AMBIENT.remove(carrier.getUUID());
        WALKED.remove(carrier.getUUID());
    }

    /** The species as it stands now (a voice given after the rig was built is heard at once). */
    private static Species live(Mob carrier, Species built) {
        Species now = DisplayRig.speciesOn(carrier);
        return now != null ? now : built;
    }

    private static float pitch(Mob carrier, Species sp) {
        return VoiceSounds.pitch(sp.scale(), carrier.getRandom().nextDouble() * 2 - 1);
    }

    private static void play(ServerLevel level, Mob at, String id, float volume, float pitch) {
        if (id == null) {
            return;
        }
        SoundEvent ev = BuiltInRegistries.SOUND_EVENT.getValue(ResourceLocation.parse(id));
        if (ev != null) {
            playEvent(level, at, ev, volume, pitch);
        }
    }

    private static void playEvent(ServerLevel level, Mob at, SoundEvent ev, float volume, float pitch) {
        level.playSound(null, at.getX(), at.getY(), at.getZ(), ev, SoundSource.NEUTRAL, volume, pitch);
        List<String> h = HEARD.computeIfAbsent(at.getUUID(), k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (h) {
            h.add(ev.location().toString());
            if (h.size() > 64) {
                h.remove(0);
            }
        }
    }

    /** What this carrier has played, oldest first (GameTests). */
    public static List<String> heard(UUID carrier) {
        List<String> h = HEARD.get(carrier);
        if (h == null) {
            return List.of();
        }
        synchronized (h) {
            return List.copyOf(h);
        }
    }

    /** Stop everything held for a carrier. */
    public static void forget(UUID carrier) {
        AMBIENT.remove(carrier);
        WALKED.remove(carrier);
    }
}
