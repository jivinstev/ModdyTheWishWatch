package com.moddingfromamod.paint;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /paint} — put a picture on the panel you are looking at.
 *
 * <ul>
 *   <li>{@code /paint demo} — a face, so a person can see the thing work without typing JSON.</li>
 *   <li>{@code /paint <key> <json>} — the real path: a name for the picture and the shapes that
 *       make it. This is the shape the {@code paint} wish tool will call, so the command and the
 *       tool cannot drift into two behaviours.</li>
 *   <li>{@code /paint size <blocks-wide> <blocks-tall>} — how big the picture hangs.</li>
 * </ul>
 *
 * <p><b>It targets what your crosshair targets</b>, and clips on {@code OUTLINE} rather than
 * {@code COLLIDER}. That is a lesson from the sibling repo, paid for once already: a rail has no
 * collision box, so a {@code COLLIDER} clip reported the block <i>underneath</i> a perfectly good
 * track and every train placement was refused. A panel is a full cube today and either would work —
 * which is exactly why it is worth getting right now rather than when it stops being one.
 */
public final class PaintCommand {

    private PaintCommand() {
    }

    /** How far a person can paint from. Far enough to reach a train car's side from the platform. */
    private static final double REACH = 12.0;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("paint")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("demo")
                        .executes(ctx -> demo(ctx, 1, 1))
                        .then(Commands.argument("wide", IntegerArgumentType.integer(1,
                                        PanelGeometry.MAX_SPAN))
                                .then(Commands.argument("tall", IntegerArgumentType.integer(1,
                                                PanelGeometry.MAX_SPAN))
                                        .executes(ctx -> demo(ctx,
                                                IntegerArgumentType.getInteger(ctx, "wide"),
                                                IntegerArgumentType.getInteger(ctx, "tall"))))))
                // `paint <key> <picture json>` as ONE greedy argument split at the first space
                //: the key may be a child's fork, `<id>~<short uuid>`, which brigadier's
                // word() refuses.
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(PaintCommand::paint)));
    }

    private static int demo(CommandContext<CommandSourceStack> ctx, int wide, int tall) {
        return apply(ctx, "demo_face", PaintPanels.demoFace(), wide, tall);
    }

    private static int paint(CommandContext<CommandSourceStack> ctx) {
        var line = com.moddingfromamod.runtime.ForkIdArgs.head(
                StringArgumentType.getString(ctx, "args"));
        String key = line.id();
        String json = line.tail();
        if (json.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "Usage: paint <key> <picture>."));
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayer();
        BlockPos target = lookingAt(player);
        if (target == null) {
            return miss(ctx);
        }
        // 0,0 = keep the size this panel already hangs at. Passing 1,1 here is what shrank a
        // three-block mural to one block on every repaint.
        PaintPanels.Result r = PaintPanels.paint(
                (ServerLevel) player.level(), target, key, json, 0, 0);
        return say(ctx, r);
    }

    private static int apply(CommandContext<CommandSourceStack> ctx, String key,
                             PaintRecipe recipe, int wide, int tall) {
        ServerPlayer player = ctx.getSource().getPlayer();
        BlockPos target = lookingAt(player);
        if (target == null) {
            return miss(ctx);
        }
        return say(ctx, PaintPanels.paint(
                (ServerLevel) player.level(), target, key, recipe, wide, tall));
    }

    private static int miss(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendFailure(Component.literal(
                "Look at a painted panel first — put one down from the creative menu, then aim at "
                        + "it and run this again."));
        return 0;
    }

    private static int say(CommandContext<CommandSourceStack> ctx, PaintPanels.Result r) {
        if (r.ok()) {
            ctx.getSource().sendSuccess(() -> Component.literal("🎨 " + r.message())
                    .withStyle(ChatFormatting.AQUA), false);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal(r.message()));
        return 0;
    }

    /** The block the crosshair is on, or null. */
    private static BlockPos lookingAt(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 to = eye.add(player.getViewVector(1.0f).scale(REACH));
        BlockHitResult hit = player.level().clip(new ClipContext(eye, to,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }
}
