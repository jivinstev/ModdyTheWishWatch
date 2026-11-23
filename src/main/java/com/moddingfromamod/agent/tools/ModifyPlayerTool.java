package com.moddingfromamod.agent.tools;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.player.NeverDie;
import com.moddingfromamod.player.PlayerPowers;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * <b>Change the CHILD — make him giant, fast, strong, able to breathe underwater.</b>
 *
 * <p>The sibling of {@code modify_mob}, for the one subject that tool will not accept. Measured
 * over 11 player-directed wishes: the agent used a tool whose subject is the child
 * <b>zero</b> times, because there was not one, and hand-rolled {@code run_command} instead — while
 * the same agent in the same session answered creature wishes with {@code modify_mob} in four calls
 * and zero failures. It reaches the verbs it has.
 *
 * <p><b>Why this cannot get an attribute id wrong.</b> The failure it replaces is specific and was
 * reproduced three times across two weeks: the agent writes
 * {@code attribute … minecraft:scale base set 5}, which does not exist on this build, and burns a
 * round trip finding out it is {@code generic.scale}. Nothing here spells an attribute — size goes
 * through the typed {@link Attributes#SCALE} handle, so the mistake is not expressible.
 *
 * <p>Effects are applied <b>infinite</b> by default. A child asking to be giant does not mean "for
 * thirty seconds", and a power that silently expires reads as the wish having broken.
 */
public final class ModifyPlayerTool implements McpTool {

    /** Vanilla's marker for an effect that does not run out. */
    private static final int INFINITE = PlayerPowers.INFINITE;

    @Override
    public String name() {
        return "modify_player";
    }

    @Override
    public String description() {
        return "CALL inspect_player FIRST if he already has powers — the same way you "
                + "inspect_build before changing a building. \"Make me EVEN stronger\" means "
                + "raise the strength he ALREADY HAS to a higher level, and you cannot do that "
                + "without seeing what level he is on; piling on a different power instead is not "
                + "what he asked for. "
                + "NOT FOR 'make me <character>'. If the wish names ANY person or character — "
                + "however the sentence goes on ('who …', 'that …', 'with …', 'he/she …') — that "
                + "is someone for you to BUILD: use sculpt_creature, not this. 'Make me <character> "
                + "who runs super fast' is a fast CREATURE, not a speed power for him. This tool is "
                + "only for wishes with no character in them at all. "
                + "Change the CHILD HIMSELF — make him giant or tiny, fast, strong, bouncy, able to "
                + "breathe underwater, or unable to die; and the stats: falling never hurts him "
                + "(no_fall_damage), nothing knocks him back (no_knockback), he is never hungry, "
                + "he swims or mines much faster. Give a stat only when he ASKED about it: `jump` "
                + "already lands him softly, so a jump wish is `jump` alone, not `jump` plus "
                + "`no_fall_damage`. THIS is how you answer 'make me giant', "
                + "'let me fly', 'make me super fast', 'let me jump over trees', 'make me "
                + "invincible' — and 'LET ME GET HURT BUT NEVER DIE', which is the `never_die` "
                + "power: he still takes damage and his hearts still go down, and the blow that "
                + "would have killed him simply does not. You do NOT need to write a datapack for "
                + "that one. These are wishes about HIM, not about a mob. (For a creature use "
                + "modify_mob; "
                + "for 'make me A dragon' — with 'a' — he wants one BUILT, so sculpt it. Same "
                + "whenever he NAMES SOMEBODY and then describes them as 'he', 'she' or 'them': "
                + "that pronoun means he is telling you about someone ELSE, so build that character "
                + "rather than turning him into it. When it could honestly be either, BUILD THE "
                + "THING: he can look at it, show it to someone and keep it, so a wrong guess costs "
                + "a conversation — guessing the other way leaves him with nothing to look at.) "
                + "Pick one "
                + "or more named powers: " + String.join(", ", PlayerPowers.names())
                + ". Powers last forever unless you pass seconds — and forever includes after "
                + "he DIES: a power given without seconds comes back with him when he respawns. "
                + "So a power he wants to keep 'forever' or 'from now on' is this one "
                + "call; do NOT write a datapack that re-applies the effect every tick.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.stringOrList(s, "powers",
                "Which power(s) to give him, from: " + String.join(", ", PlayerPowers.names())
                        + ". An unknown name is refused rather than guessed at.", 8);
        Schemas.prop(s, "level", "string",
                "How strong: normal (default), extra, or super. Use it when the child says "
                        + "'SUPER high' or 'really fast' — it raises the power rather than making "
                        + "you write the effect out by hand.");
        Schemas.prop(s, "seconds", "integer",
                "How long, in seconds. Leave it out for FOREVER, which is what a child means "
                        + "unless he said otherwise.");
        Schemas.prop(s, "remove", "boolean",
                "True = take these powers away again instead of giving them.");
        Schemas.required(s, "powers");
        return s;
    }

    /** The powers asked for, accepting either one string or a list. */
    private static List<String> asked(JsonObject args) {
        List<String> out = new ArrayList<>();
        JsonElement e = args.get("powers");
        if (e == null || e.isJsonNull()) {
            return out;
        }
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (x != null && x.isJsonPrimitive()) {
                    out.add(x.getAsString());
                }
            }
        } else if (e.isJsonPrimitive()) {
            // "giant, fast" is how a model writes two things into a string field.
            for (String part : e.getAsString().split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }

    @Override
    public ToolResult call(JsonObject args) {
        List<String> want = asked(args);
        if (want.isEmpty()) {
            return ToolResult.error("Say which power(s) to give. Available: "
                    + String.join(", ", PlayerPowers.names()));
        }

        // RESOLVE EVERYTHING BEFORE APPLYING ANYTHING. Half-granting "giant and teleportation" would
        // leave him giant with an error message, which is a worse answer than a clean refusal.
        List<PlayerPowers.Spec> specs = new ArrayList<>();
        for (String name : want) {
            PlayerPowers.Spec spec = PlayerPowers.find(name);
            if (spec == null) {
                return ToolResult.error(PlayerPowers.unknown(name));
            }
            specs.add(spec);
        }

        PlayerPowers.Level level = PlayerPowers.level(
                args.has("level") && !args.get("level").isJsonNull()
                        ? args.get("level").getAsString() : null);
        boolean remove = args.has("remove") && args.get("remove").getAsBoolean();
        int asked = args.has("seconds") && !args.get("seconds").isJsonNull()
                ? Math.max(1, args.get("seconds").getAsInt()) : 0;
        // on a world whose rules clamp powers, never forever and never past the cap — and said.
        com.moddingfromamod.world.WishClamp.Limits clamp = com.moddingfromamod.world.WishClamp.of(
                com.moddingfromamod.world.WorldRulesService.loadedOrNull());
        final boolean clamped = clamp != null && !remove && (asked == 0 || asked > clamp.powerSeconds());
        final int seconds = clamped ? clamp.powerSeconds() : asked;
        final String clampWords = clamped ? com.moddingfromamod.world.WishClamp.powerWords(clamp) : null;
        int duration = seconds > 0 ? seconds * 20 : INFINITE;

        try {
            return AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                if (server == null) {
                    return ToolResult.error("The world is not running yet.");
                }
                ServerPlayer player = com.moddingfromamod.agent.ActingChild.person(server);
                if (player == null) {
                    return ToolResult.noPlayerOnline(com.moddingfromamod.agent.ActingChild.whyNobody(server, name(),
                            "Nobody is in the world, so there is no one to change."));
                }

                List<String> did = new ArrayList<>();
                for (PlayerPowers.Spec spec : specs) {
                    applyOne(player, spec, remove, duration, level, did);
                    // a timed power ENDS — sizes, stats and never_die included, which have no clock of
                    // their own (PowerTimers); a power given forever, or taken away, drops any old end.
                    if (!remove && seconds > 0) {
                        com.moddingfromamod.player.PowerTimers.schedule(player, spec.id(),
                                level.name().toLowerCase(java.util.Locale.ROOT),
                                System.currentTimeMillis() + seconds * 1000L);
                    } else {
                        com.moddingfromamod.player.PowerTimers.cancel(player, spec.id());
                    }
                }
                if (clampWords != null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("✨ " + clampWords)
                            .withStyle(net.minecraft.ChatFormatting.AQUA));
                }
                return ToolResult.ok((remove ? "Took away: " : "Gave the child: ")
                        + String.join("; ", did)
                        + (remove || seconds > 0 ? "." + (seconds > 0 && !remove ? " They last " + seconds + " seconds." : "")
                           : ". These last until they are taken away — even through dying.")
                        + (clampWords == null ? "" : " CLAMPED (this world's clamp rule): " + clampWords
                           + " He was told; the wish is granted — finish it."));
            });
        } catch (RuntimeException e) {
            return ToolResult.error("Could not change the child: " + e);
        }
    }

    /**
     * Apply one power to one subject.
     *
     * <p>Takes a {@link LivingEntity} rather than a {@code ServerPlayer} because nothing in here
     * needs a player — attributes and effects are `LivingEntity` API, and `never_die` keys off
     * persistent data any entity has. Widening it is what makes this testable at all: a
     * GameTest can then apply real powers to a PLOT-LOCAL subject instead of installing a mock into
     * the server's one shared player list, which is how six concurrent tests came to be changing
     * each other's player and failing ones leaked theirs into later batches.
     *
     * <p>It also happens to be the shape a future "affect any entity" verb would need.
     */
    public static void applyOne(LivingEntity player, PlayerPowers.Spec spec, boolean remove,
            int duration, PlayerPowers.Level level, List<String> did) {
        if (spec.scale() != null) {
            // TYPED HANDLE, never a string id — see the class note.
            AttributeInstance attr = player.getAttribute(Attributes.SCALE);
            if (attr != null) {
                attr.setBaseValue(remove ? 1.0 : level.stretch(spec.scale()));
            }
            // A giant who cannot step onto his own doorstep, and who is hurt stepping off it, does
            // not feel giant. Both ride the size and are undone with it.
            AttributeInstance step = player.getAttribute(Attributes.STEP_HEIGHT);
            if (step != null) {
                step.setBaseValue(remove || spec.scale() <= 1.0 ? 0.6 : 0.6 * spec.scale());
            }
            AttributeInstance fall = player.getAttribute(Attributes.SAFE_FALL_DISTANCE);
            if (fall != null) {
                fall.setBaseValue(remove || spec.scale() <= 1.0 ? 3.0 : 3.0 * spec.scale());
            }
        }

        // attribute powers, each by its typed handle — the class rule: never a string id.
        for (PlayerPowers.Stat s : spec.stats()) {
            var handle = switch (s.kind()) {
                case FALL_DAMAGE -> Attributes.FALL_DAMAGE_MULTIPLIER;
                case KNOCKBACK_RESISTANCE -> Attributes.KNOCKBACK_RESISTANCE;
                case BLOCK_BREAK_SPEED -> Attributes.BLOCK_BREAK_SPEED;
                case WATER_MOVEMENT -> Attributes.WATER_MOVEMENT_EFFICIENCY;
            };
            AttributeInstance a = player.getAttribute(handle);
            if (a != null) {
                a.setBaseValue(remove ? s.kind().vanillaDefault() : s.value());
            }
        }

        for (PlayerPowers.Buff b : spec.buffs()) {
            ResourceLocation id = ResourceLocation.tryParse(b.effectId());
            var holder = id == null ? null
                    : BuiltInRegistries.MOB_EFFECT.getOptional(id)
                            .map(BuiltInRegistries.MOB_EFFECT::wrapAsHolder).orElse(null);
            if (holder == null) {
                continue;   // an effect this build does not have is skipped, never fatal
            }
            if (remove) {
                player.removeEffect(holder);
            } else {
                MobEffect effect = holder.value();
                player.addEffect(new MobEffectInstance(holder, duration,
                        b.level() + level.bonus(), false, false, true));
                if (effect == null) {
                    continue;
                }
            }
        }

        // Only a player can be told not to die — the flag is read by a death listener that looks
        // for one. A non-player subject silently skips it rather than failing: the rest of the
        // power still applies, and no wish can ask for this on a mob today.
        if (spec.neverDie() && player instanceof net.minecraft.world.entity.player.Player p) {
            NeverDie.set(p, !remove);
        }
        did.add(spec.id() + " (" + spec.blurb()
                + (level == PlayerPowers.Level.NORMAL ? "" : ", " + level.name().toLowerCase(java.util.Locale.ROOT))
                + ")");
    }
}
