package com.moddingfromamod.builder;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /wishbuild <id>} — put up a building the agent invented earlier.
 *
 * <p>This is the piece that makes buildings part of the self-improving loop. A learned wish
 * template is a list of <b>commands</b>, so without a command form the best castle the agent ever
 * designs could only ever be rebuilt by another agent round-trip. With it, {@code add_template} can
 * teach the watch {@code wishbuild floating_castle} and the offline engine puts the whole thing up
 * in a couple of seconds, with no AI and no network.
 *
 * <p>Permission 0 on purpose, exactly like {@code /wish}: it can only run a plan that was already
 * saved, every plan is volume-capped, and the placement is rate-limited by {@link Builder}.
 */
public final class BuildCommand {

    private BuildCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // "Take me to my castle" as a button: finds the build by id, every time.
        com.moddingfromamod.agent.tools.TakeMeToTool.register(event.getDispatcher());
        // run ANY command on a building's named spot — a real dragon summoned onto the
        // castle roof, a chest on the bar's counter — once the building is finished.
        event.getDispatcher().register(Commands.literal("wishspot")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("spot", StringArgumentType.greedyString())
                        .executes(ctx -> SpotCommand.run(ctx.getSource(),
                                StringArgumentType.getString(ctx, "spot")))));
        event.getDispatcher().register(Commands.literal("wishbuild")
                // A REPLAY command, not something a child types: the stored form of every
                // saved button, run by the executor at permission 2. Hidden from a child's tab list.
                .requires(src -> src.hasPermission(2))
                // greedyString, not word(): a child's FORK of a curated building is `<id>~<his short
                // uuid>`, and brigadier's word() refuses `~` — so `wishbuild castle~1a2b3c4d`
                // could never be taught, which the first real grant hit (CommandTrial refused it).
                .then(Commands.argument("id", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> {
                            BuildRegistry.ids().forEach(b::suggest);
                            return b.buildFuture();
                        })
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            String id = StringArgumentType.getString(ctx, "id").trim();
                            BuildPlan plan = BuildRegistry.get(id);
                            if (plan == null) {
                                ctx.getSource().sendFailure(Component.literal(
                                        "No building called '" + id + "'. Known: "
                                                + String.join(", ", BuildRegistry.ids())));
                                return 0;
                            }
                            // A served wish's locked spot when one is running, so the
                            // building rises where its pillar stood.
                            var served = com.moddingfromamod.wish.WishProgress.servedSpot();
                            var anchor = served != null ? served : Builder.anchorInFrontOf(player, 12, 0);
                            // A copy of somebody else's building keeps its hands off the
                            // owner's remembered spot: the library's and his own do not.
                            String owner = plan.ownerId();
                            boolean his = owner == null || owner.isEmpty()
                                    || owner.equals(com.moddingfromamod.marshal.ArtifactOwner
                                            .normalise(player.getStringUUID()));
                            // On the ground where it lands: the library treehouse on a
                            // hillside gets its footing, a starter wished over the sea its island.
                            String result = Builder.submit(player.level(), anchor, plan, his,
                                    BuildGround.Ground.settle(), player);
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("✨ " + result), false);
                            return 1;
                        })));
    }
}
