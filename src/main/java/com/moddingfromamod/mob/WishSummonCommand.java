package com.moddingfromamod.mob;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.SummonCommand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /wishsummon <mob> <modify_mob spec>} — summon a real mob and apply the SAME typed change
 * {@code modify_mob} applied, so a wish granted with that tool can be taught as a button.
 *
 * <p>A template is a list of commands and {@code modify_mob} is a tool, so until this existed the
 * agent had to TRANSLATE what worked into commands it had never run — and Steve's Great Big Shark
 * came back from that translation as a {@code summon} whose {@code attributes} Minecraft ignores,
 * an ordinary shark announced as the big one on every re-wish. This is the {@code wishbuild} /
 * {@code wishmob spawn} answer applied to real mobs: replay the typed thing
 * that worked. The spec is {@link MobTweak}'s own JSON, parsed and clamped by the same code the
 * tool uses, and applied by the tool's own {@code apply} — so there is no NBT, no attribute id and
 * no second implementation to drift.
 *
 * <p><b>And it checks what it claims.</b> After applying, it reads the scale and max health back
 * off the mob; if either did not take, the command FAILS naming it, so the replay reads PARTIAL
 * rather than announcing a size the child cannot see.
 *
 * <p>Permission 2, like {@code summon}; only ever run by the wish executor, and allowlisted in
 * {@link com.moddingfromamod.wish.CommandGuard}. A sculpted {@code wish_mob} is refused — it has a
 * saved design, and {@code wishmob spawn} is its replay.
 */
public final class WishSummonCommand {

    private static final SimpleCommandExceptionType NOT_LIVING = new SimpleCommandExceptionType(
            Component.literal("wishsummon only works on living mobs — use summon for anything else"));
    private static final SimpleCommandExceptionType SCULPTED = new SimpleCommandExceptionType(
            Component.literal("A sculpted creature replays with `wishmob spawn <species>`, not wishsummon"));

    private WishSummonCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandBuildContext registries = event.getBuildContext();
        event.getDispatcher().register(Commands.literal("wishsummon")
                .requires(net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("entity", ResourceArgument.resource(registries, Registries.ENTITY_TYPE))
                        .suggests(SuggestionProviders.cast(SuggestionProviders.SUMMONABLE_ENTITIES))
                        .executes(ctx -> run(ctx, "{}"))
                        .then(Commands.argument("spec", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, StringArgumentType.getString(ctx, "spec"))))));
    }

    /** The spec as the tool would parse it, or null with a reason when it is not a JSON object. */
    public static MobTweak parseSpec(String json) {
        try {
            JsonObject o = JsonParser.parseString(json == null || json.isBlank() ? "{}" : json)
                    .getAsJsonObject();
            return MobTweak.fromJson(o);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int run(CommandContext<CommandSourceStack> ctx, String specJson)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        Holder.Reference<EntityType<?>> type = ResourceArgument.getSummonableEntityType(ctx, "entity");
        if (type.key().identifier().getPath().equals("wish_mob")) {
            throw SCULPTED.create();
        }
        MobTweak tweak = parseSpec(specJson);
        if (tweak == null) {
            throw new SimpleCommandExceptionType(Component.literal(
                    "The spec must be modify_mob's JSON, e.g. {\"scale\":4,\"health\":300}")).create();
        }
        String boss = com.moddingfromamod.agent.tools.ModifyMobTool.bossCannotBeAPet(tweak,
                type.key().identifier().getPath());
        if (boss != null) {
            throw new SimpleCommandExceptionType(Component.literal(boss)).create();
        }
        Entity made = SummonCommand.createEntity(src, type, src.getPosition(), new CompoundTag(), true);
        if (!(made instanceof LivingEntity living)) {
            made.discard();
            throw NOT_LIVING.create();
        }
        List<String> notes = com.moddingfromamod.agent.tools.ModifyMobTool.applyTo(
                src.getServer().getScoreboard(), living, tweak);
        List<String> missed = new ArrayList<>();
        if (tweak.scale() != null && !near(living.getAttributeValue(Attributes.SCALE), tweak.scale())) {
            missed.add("size ×" + tweak.scale());
        }
        if (tweak.health() != null
                && !near(living.getAttributeValue(Attributes.MAX_HEALTH), tweak.health())) {
            missed.add(tweak.health().intValue() + " health");
        }
        if (!missed.isEmpty()) {
            throw new SimpleCommandExceptionType(Component.literal(
                    "Summoned it, but " + String.join(" and ", missed) + " did not take "
                            + notes)).create();
        }
        src.sendSuccess(() -> Component.literal("Summoned " + type.key().identifier() + ": "
                + tweak.summary()), false);
        return 1;
    }

    private static boolean near(double actual, double wanted) {
        return Math.abs(actual - wanted) < 1e-3;
    }
}
