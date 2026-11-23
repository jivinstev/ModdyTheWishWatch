package com.moddingfromamod.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.moddingfromamod.entity.WishMobEntity;
import com.moddingfromamod.species.Species;
import com.moddingfromamod.species.SpeciesRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * {@code /wishmob} — bring a Blockbench-sculpted creature into the world, list what's available, and
 * (see {@code persist}) turn one into a real mod mob.
 *
 * <ul>
 *   <li>{@code /wishmob spawn <key> [count] [at <build>:<spot>]} — spawn the custom-model creature named by a folder in
 *       the model repository. Reuses the one generic {@link WishMobEntity}; the geometry + skin load
 *       off disk, so a creature exported seconds ago spawns with no restart.</li>
 *   <li>{@code /wishmob list} — the model keys the repository currently holds.</li>
 *   <li>{@code /wishmob reload <key>} — drop the cached parse so the next spawn re-reads disk (mtime
 *       normally handles this; this is the belt-and-braces button).</li>
 * </ul>
 *
 * <p>Registered on {@code NeoForge.EVENT_BUS} like every other gameplay command.
 */
public final class WishMobCommands {

    private WishMobCommands() {
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("mfam-wishmob");

    /** Tab-complete the keys that actually have a geometry file on disk right now. */
    private static final SuggestionProvider<CommandSourceStack> KEYS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(repositoryKeys(), builder);

    /** Every {@code <build>:<spot>} there is. */
    private static List<String> spots() {
        List<String> options = new ArrayList<>();
        for (String id : com.moddingfromamod.builder.BuildRegistry.ids()) {
            var plan = com.moddingfromamod.builder.BuildRegistry.get(id);
            if (plan != null) {
                for (String n : com.moddingfromamod.builder.BuildSpots.names(plan).split(", ")) {
                    options.add(id + ":" + n);
                }
            }
        }
        return options;
    }

    /**
     * Tab-complete {@code spawn}'s line word by word (it is one greedy argument): the
     * creature, then {@code at}, then a {@code <build>:<spot>}.
     */
    private static final SuggestionProvider<CommandSourceStack> SPAWN_ARGS = (ctx, builder) -> {
        ForkIdArgs.Cursor c = ForkIdArgs.cursor(builder.getRemaining());
        var here = builder.createOffset(builder.getStart() + c.start());
        if (c.index() == 0) {
            return SharedSuggestionProvider.suggest(repositoryKeys(), here);
        }
        String last = c.before().get(c.before().size() - 1);
        if (last.equals("at")) {
            return SharedSuggestionProvider.suggest(spots(), here);
        }
        if (c.before().size() <= 2 && !c.before().contains("at")) {
            return SharedSuggestionProvider.suggest(List.of("at"), here);
        }
        return here.buildFuture();
    };

    /** Tab-complete {@code paint}'s line: the creature, then its parts, then a colour. */
    private static final SuggestionProvider<CommandSourceStack> PAINT_ARGS = (ctx, builder) -> {
        ForkIdArgs.Cursor c = ForkIdArgs.cursor(builder.getRemaining());
        var here = builder.createOffset(builder.getStart() + c.start());
        List<String> options = new ArrayList<>();
        switch (c.index()) {
            case 0 -> options.addAll(repositoryKeys());
            case 1 -> {
                try {
                    options.addAll(PartPaint.names(ModelRepository.sculptedParts(
                            c.before().get(0).toLowerCase(Locale.ROOT))));
                } catch (RuntimeException ignored) {
                    // not a creature (yet)
                }
            }
            case 2 -> options.addAll(Species.colourNames());
            default -> {
            }
        }
        return SharedSuggestionProvider.suggest(options, here);
    };

    /** Every creature that exists, custom-model or not — an egg works for all of them. */
    private static final SuggestionProvider<CommandSourceStack> SPECIES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(
                    SpeciesRegistry.all().stream().map(Species::id).sorted().toList(), builder);

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("wishmob")
                // Op-only as a whole: `spawn` is the stored form of every creature button,
                // replayed by the executor at permission 2; the rest are a grown-up's tools. A child's
                // tab list is /wish and /moddy.
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(WishMobCommands::list))
                // every key below is the REST OF THE LINE, split by ForkIdArgs, not a
                // brigadier word(): a child's fork of a creature is `<id>~<short uuid>` and word()
                // refuses `~`, so the button his wish taught (`wishmob spawn dragon~1a2b3c4d`)
                // never parsed. did the same for `wishbuild`.
                .then(Commands.literal("spawn")
                        .requires(src -> src.hasPermission(2))
                        // `wishmob spawn <key> [count] [at <build>:<spot>]` (on a building's
                        // named spot, once the building is finished).
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(SPAWN_ARGS)
                                .executes(WishMobCommands::spawn)))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("key", StringArgumentType.greedyString())
                                .suggests(KEYS)
                                .executes(WishMobCommands::reload)))
                .then(Commands.literal("egg")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("key", StringArgumentType.greedyString())
                                .suggests(SPECIES)
                                .executes(WishMobCommands::egg)))
                .then(Commands.literal("parts")
                        .then(Commands.argument("key", StringArgumentType.greedyString())
                                .suggests(KEYS)
                                .executes(WishMobCommands::parts)))
                .then(Commands.literal("paint")
                        .requires(src -> src.hasPermission(2))
                        // `<key> <part> <colour> [tint|fill]` — the trailing word is kept so a
                        // taught button that ends in one still runs: the rig paints a part flat
                        // either way.
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(PAINT_ARGS)
                                .executes(WishMobCommands::paint)))
                // (Moddy Media Stage 2): the media timeline's two rig verbs.
                // `talk <on|off> <targets>` sets the server-side talking flag (RigTalk) and says the
                // game tick it switched on, for the timeline runner's log.
                .then(Commands.literal("talk")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("on")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> talk(ctx, true))))
                        .then(Commands.literal("off")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> talk(ctx, false)))))
                // `face <targets> <x y z | entity | free>` is rig.look_at (RigFacing): he turns to
                // it and stands, an entity followed as it moves, until `free`.
                .then(Commands.literal("face")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.literal("free")
                                        .executes(WishMobCommands::faceFree))
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(ctx -> face(ctx,
                                                Vec3Argument.getVec3(ctx, "pos"), null)))
                                .then(Commands.argument("entity", EntityArgument.entity())
                                        .executes(ctx -> face(ctx, null,
                                                EntityArgument.getEntity(ctx, "entity"))))))
                // `gesture <targets> <clip|off> [hold]` plays a named clip over his idle/talk
                // (RigGesture): a hat tip on a word. Says the game tick it started, for the take's log.
                .then(Commands.literal("gesture")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("clip", StringArgumentType.word())
                                        .executes(ctx -> gesture(ctx, false))
                                        .then(Commands.literal("hold").executes(ctx -> gesture(ctx, true))))))
                .then(Commands.literal("photo")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> photo(ctx, "photo"))
                        .then(Commands.argument("label", StringArgumentType.greedyString())
                                .suggests(KEYS)
                                .executes(ctx -> {
                                    ForkIdArgs.One label = ForkIdArgs.one(
                                            StringArgumentType.getString(ctx, "label"), "photo label");
                                    if (label.error() != null) {
                                        ctx.getSource().sendFailure(Component.literal(label.error()));
                                        return 0;
                                    }
                                    return photo(ctx, label.id());
                                })));
        event.getDispatcher().register(root);
    }

    /**
     * The rig carriers among the selected entities: a selector may also catch a rig's display parts
     * or anything else, and only a driven carrier can talk or turn.
     */
    private static List<Mob> carriers(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        List<Mob> out = new ArrayList<>();
        for (Entity e : EntityArgument.getEntities(ctx, "targets")) {
            if (e instanceof Mob m && com.moddingfromamod.vanilla.VanillaRigAnimator.isDriving(m)) {
                out.add(m);
            }
        }
        if (out.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("None of those is a creature's rig. Target one"
                    + " with its tag, e.g. " + com.moddingfromamod.entity.WishMobTags.selector("moddy") + "."));
        }
        return out;
    }

    private static String names(List<Mob> mobs) {
        List<String> out = new ArrayList<>();
        for (Mob m : mobs) {
            var sp = com.moddingfromamod.vanilla.VanillaRigAnimator.builtFrom(m.getUUID());
            out.add((sp == null ? "?" : sp.id()) + " " + m.getUUID());
        }
        return String.join(", ", out);
    }

    /** {@code /wishmob talk <on|off> <targets>}. */
    private static int talk(CommandContext<CommandSourceStack> ctx, boolean on)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        List<Mob> mobs = carriers(ctx);
        if (mobs.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (Mob m : mobs) {
            if (com.moddingfromamod.vanilla.RigTalk.set(m, on)) {
                changed++;
            }
        }
        // The tick the flag flipped. The animator draws after the tick's commands, so it sees the
        // flag on this same tick and writes the new clip at once (VanillaRigAnimator.driveOne).
        long tick = ctx.getSource().getLevel().getGameTime();
        String line = "talk " + (on ? "on" : "off") + " at game tick " + tick + ": " + changed + " of "
                + mobs.size() + " switched (" + names(mobs) + ")";
        LOG.info("wishmob {}", line);
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return mobs.size();
    }

    /** {@code /wishmob gesture <targets> <clip|off> [hold]}. */
    private static int gesture(CommandContext<CommandSourceStack> ctx, boolean hold)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        List<Mob> mobs = carriers(ctx);
        if (mobs.isEmpty()) {
            return 0;
        }
        String clip = StringArgumentType.getString(ctx, "clip");
        long tick = ctx.getSource().getLevel().getGameTime();
        for (Mob m : mobs) {
            if ("off".equalsIgnoreCase(clip)) {
                com.moddingfromamod.vanilla.RigGesture.stop(m.getUUID());
            } else {
                com.moddingfromamod.vanilla.RigGesture.start(m.getUUID(), clip, tick, hold);
            }
        }
        String line = "gesture " + clip + (hold ? " (hold)" : "") + " at game tick " + tick + " for "
                + mobs.size() + " (" + names(mobs) + ")";
        LOG.info("wishmob {}", line);
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return mobs.size();
    }

    /** {@code /wishmob face <targets> <x y z | entity>} (rig.look_at). */
    private static int face(CommandContext<CommandSourceStack> ctx, net.minecraft.world.phys.Vec3 point,
                            Entity target) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        List<Mob> mobs = carriers(ctx);
        if (mobs.isEmpty()) {
            return 0;
        }
        for (Mob m : mobs) {
            if (target != null) {
                com.moddingfromamod.vanilla.RigFacing.faceEntity(m, target);
            } else {
                com.moddingfromamod.vanilla.RigFacing.facePoint(m, point);
            }
        }
        long tick = ctx.getSource().getLevel().getGameTime();
        String what = target != null ? target.getName().getString() + " " + target.getUUID()
                : String.format(Locale.ROOT, "%.2f %.2f %.2f", point.x, point.y, point.z);
        String line = "face " + what + " at game tick " + tick + ": " + names(mobs)
                + " turning to it and standing (free with /wishmob face <target> free)";
        LOG.info("wishmob {}", line);
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return mobs.size();
    }

    private static int faceFree(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        List<Mob> mobs = carriers(ctx);
        int freed = 0;
        for (Mob m : mobs) {
            if (com.moddingfromamod.vanilla.RigFacing.release(m.getUUID())) {
                freed++;
            }
        }
        int n = freed;
        long tick = ctx.getSource().getLevel().getGameTime();
        String line = "face free at game tick " + tick + ": " + n + " of " + mobs.size() + " released";
        LOG.info("wishmob {}", line);
        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        return mobs.size();
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<String> keys = repositoryKeys();
        CommandSourceStack src = ctx.getSource();
        if (keys.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No custom creatures yet. Sculpt one in "
                    + "Blockbench and export it into " + RuntimeConfig.modelDir())
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("🐉 Custom creatures (" + keys.size() + "): "
                + String.join(", ", keys)).withStyle(ChatFormatting.AQUA), false);
        return keys.size();
    }

    /** The one id a {@code key} argument holds, or null after telling the sender why not. */
    private static String oneKey(CommandContext<CommandSourceStack> ctx) {
        ForkIdArgs.One one = ForkIdArgs.one(StringArgumentType.getString(ctx, "key"), "creature");
        if (one.error() != null) {
            ctx.getSource().sendFailure(Component.literal(one.error()));
            return null;
        }
        return one.id().toLowerCase(Locale.ROOT);
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        String key = oneKey(ctx);
        if (key == null) {
            return 0;
        }
        ModelRepository.clearCache();
        ctx.getSource().sendSuccess(() -> Component.literal("Reloaded '" + key
                + "' — spawn it again to see the latest sculpt.").withStyle(ChatFormatting.GREEN),
                false);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx) throws
            com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ForkIdArgs.Spawn line = ForkIdArgs.spawn(StringArgumentType.getString(ctx, "args"),
                com.moddingfromamod.mob.SpawnBudget.SCULPTED_MAX_COUNT);
        if (line.error() != null) {
            src.sendFailure(Component.literal(line.error()));
            return 0;
        }
        ServerPlayer player = src.getPlayerOrException();
        String key = line.key().toLowerCase(Locale.ROOT);
        int count = line.count();
        String at = line.at();
        // A SEAT WAITS FOR ITS BUILDING. A replayed wish runs `wishbuild castle` and then
        // this in the same tick, while the castle is still a queue of blocks: seated now, the boss
        // would stand on air where the throne will be, or the walls would land on him. So the
        // spawn is put off until the building is finished, with the wish's worker bound again.
        String build = com.moddingfromamod.vanilla.SpotSeat.buildOf(at);
        if (build != null && com.moddingfromamod.builder.Builder.isBuilding(build)
                && ModelRepository.has(key)) {
            var run = com.moddingfromamod.agent.AgentContext.currentRun();
            // The building this replay is putting up: the link is the one he is watching rise.
            // Captured NOW, with the caller's session and slot name (AgentContext.carried).
            java.util.function.Supplier<Void> seat = com.moddingfromamod.agent.AgentContext.carried(() -> {
                spawnNow(src, player, key, count, at, true);
                return null;
            });
            com.moddingfromamod.builder.Builder.whenBuilt(build, seat::get);
            if (run != null) {
                Species sp = SpeciesRegistry.get(key);
                var named = com.moddingfromamod.place.At.parse(at);
                run.seated(sp == null ? key : sp.id(), build + ":"
                        + (named == null || named.where().isEmpty() ? com.moddingfromamod.builder.BuildSpots.FRONT
                                : named.where()));
            }
            src.sendSuccess(() -> Component.literal("✨ " + key + " will take its place at "
                    + (at == null || at.isBlank() ? build + ":front" : at.trim())
                    + " the moment the building is finished (it is still going up).")
                    .withStyle(ChatFormatting.LIGHT_PURPLE), false);
            return 1;
        }
        return spawnNow(src, player, key, count, at, false);
    }

    private static int spawnNow(CommandSourceStack src, ServerPlayer player, String key, int count,
                                String at, boolean trustLink) {

        if (!ModelRepository.has(key)) {
            src.sendFailure(Component.literal("No model named '" + key + "'. Try /wishmob list."));
            return 0;
        }
        MinecraftServer server = player.getServer();
        Species species = resolveSpecies(server, key);
        if (species == null) {
            src.sendFailure(Component.literal("Couldn't prepare '" + key + "' (too many creatures?)."));
            return 0;
        }
        // Tell every client how to draw it, then spawn the generic entity carrying this species.
        com.moddingfromamod.net.ModdedClient.broadcastSpecies();

        ServerLevel level = player.level();
        // COUNT WHAT IS STANDING THERE. This counted `addFreshEntity(wishMob)`,
        // which the rig bridge cancels every time — so every creature in production
        // read "Spawned 0×", and the agent spawned him again. CreatureSpawn builds the rig now and
        // hands back its carrier.
        int spawned = 0;
        String handle = "";
        String settledNote = "";
        String seatNote = "";
        // PRICED BY DISPLAYS. How many displays one of these is, we learn from the first
        // one we make; then SpawnBudget says how many the wave and the world have room for. The
        // first one always comes, however big.
        var dim = level.dimension();
        int displaysBefore = com.moddingfromamod.mob.WishMobLimit.displays(dim);
        int allowed = count;
        for (int i = 0; i < allowed; i++) {
            // WHERE HE CAN SEE IT. This was two blocks in front of him whatever the size,
            // and an earlier issue's scale-4 sea creature stood with his eyes inside it — invisible.
            // or on a building's named spot (`at castle:throne`), in that building's world.
            var placed = com.moddingfromamod.vanilla.SpotSeat.spawnOne(server, player, species.id(), at, i,
                    count, spawned == 0, trustLink);
            if (placed == null) {
                break;
            }
            var seat = placed.seat();
            if (i == 0) {
                seatNote = seat.onSpot() ? " on " + seat.link() + "." + seat.note() : seat.note();
            }
            net.minecraft.world.entity.LivingEntity carrier = placed.carrier();
            if (carrier != null) {
                spawned++;
                // One MORE of it, never a replacement: the end of the wish must not reap it, or the copy it joined,
                // as the wish's scaffolding (a herd of three made two by the reaper).
                com.moddingfromamod.vanilla.WishShimmer.joinsHerd(carrier.getUUID());
                // a creature made for him is HIS — the horn that calls it finds it by this.
                if (carrier instanceof net.minecraft.world.entity.Mob m) {
                    com.moddingfromamod.mob.PetBehaviours.claim(m, player.getUUID());
                }
                // Safe where it stands: a footing over water or lava, out of rock.
                var settled = placed.settled();
                if (spawned == 1) {
                    settledNote = settled.forTheAgent();
                }
                if (handle.isEmpty()) {
                    handle = com.moddingfromamod.vanilla.CreatureSpawn.remember(carrier);
                }
                if (spawned == 1) {
                    allowed = com.moddingfromamod.mob.WishMobLimit.sculptedAllowed(dim,
                            displaysBefore, count);
                }
            }
        }
        int total = spawned;
        // Fewer than asked because the WAND said so, said in its words — not "only N fitted".
        String wave = spawned > 0 && allowed < count
                ? " " + com.moddingfromamod.mob.SpawnBudget.forTheAgent(
                        com.moddingfromamod.mob.SpawnBudget.waveLine(
                                com.moddingfromamod.mob.SpawnBudget.Kind.SCULPTED, species.name(), spawned))
                : "";
        String selector = com.moddingfromamod.entity.WishMobTags.selector(species.id());
        if (total == 0) {
            // LOUD. A number in an ok result is what the agent read past; a failure is
            // `ok:false` in run_command and cannot be.
            src.sendFailure(Component.literal("Spawned nothing: no " + species.name()
                    + " is standing there. Try again somewhere with room for it (not inside a "
                    + "block or deep water), or check " + selector + " for where they are."));
            return 0;
        }
        // Hand back the SELECTOR and the HANDLE, not just a name. The agent reads this result, and
        // without it the only handle it has is the display name — which is how a live wish once
        // lost four minutes guessing at entity types, and broke a datapack compile. See WishMobTags.
        // a MOUNT comes with the horn that calls it back, named for it — once, never again on a
        // re-wish or a button press. Only a rideable creature: see CreatureHorn.
        String horn = "";
        if (species.has(Species.Power.RIDEABLE)) {
            String landed = com.moddingfromamod.item.CreatureHorn.giveIfMissing(player, species);
            if (landed != null) {
                horn = " He was handed a horn, \"Call " + species.name() + "\" (" + landed.trim()
                        + "): blowing it brings it to him from anywhere nearby. Tell him.";
            }
        }
        String shortBy = total < allowed ? " (only " + total + " of " + allowed + " fitted)" : "";
        String named = handle.isEmpty() ? "" : " (the first is " + handle + ")";
        String safe = settledNote + seatNote + horn;
        src.sendSuccess(() -> Component.literal("✨ Spawned " + total + "× " + species.name()
                + shortBy + " from your Blockbench model! Target it with " + selector + named + wave
                + safe)
                .withStyle(ChatFormatting.LIGHT_PURPLE), false);
        return spawned;
    }

    /**
     * Put a creature's spawn egg in the player's hand.
     *
     * <p>The reliable counterpart to the creative-menu egg: the menu's contents are cached, so this
     * always works even if a refresh hasn't landed yet.
     */
    private static int egg(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String key = oneKey(ctx);
        if (key == null) {
            return 0;
        }
        try {
            ServerPlayer player = src.getPlayerOrException();
            Species species = SpeciesRegistry.get(key);
            if (species == null && ModelRepository.has(key)) {
                // A sculpted-but-never-spawned creature: register it so the egg means something.
                species = resolveSpecies(player.getServer(), key);
                com.moddingfromamod.net.ModdedClient.broadcastSpecies();
            }
            if (species == null) {
                src.sendFailure(Component.literal("No creature called '" + key + "'."));
                return 0;
            }
            var stack = com.moddingfromamod.item.WishEggItem.of(
                    com.moddingfromamod.item.ModItems.WISH_EGG.get(), species.id());
            // THROUGH HandOver, not through add()'s boolean. The line this replaces looked
            // right and, in Creative, ate the egg whenever his bag was full while still saying
            // "🥚 Here's your egg!" — Inventory.add discards the stack and reports success for a
            // player with infinite materials. Found in give_items and swept for here, which
            // is what "a defect found is a defect CLASS found" means in practice. HandOver also
            // does the broadcastChanges this used to do by hand, for the same reason: an egg added
            // server-side only left him staring at an empty hotbar.
            // And through the ONE delivery path: the hotbar, then his Wish Bag,
            // and he is told which.
            var landed = com.moddingfromamod.item.delivery.WishDelivery.give(player, stack,
                    com.moddingfromamod.item.delivery.WishDelivery.currentWishKey(), com.moddingfromamod.item.delivery.DeliveryPlan.Kind.HANDOVER, false);
            final Species named = species;
            final String where = com.moddingfromamod.item.delivery.DeliveryWords.where(landed);
            src.sendSuccess(() -> Component.literal("🥚 Here's your " + named.name() + " egg! " + where)
                    .withStyle(ChatFormatting.LIGHT_PURPLE), false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            src.sendFailure(Component.literal("Only a player can be given an egg."));
            return 0;
        }
    }

    /** What can I paint on this creature? Answers the "I couldn't find that part" question up front. */
    private static int parts(CommandContext<CommandSourceStack> ctx) {
        String key = oneKey(ctx);
        if (key == null) {
            return 0;
        }
        List<String> names = PartPaint.names(ModelRepository.sculptedParts(key));
        CommandSourceStack src = ctx.getSource();
        if (names.isEmpty()) {
            src.sendFailure(Component.literal("No sculpted creature called '" + key + "'."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("🎨 " + key + " has: " + String.join(", ", names))
                .withStyle(ChatFormatting.AQUA), false);
        return names.size();
    }

    /**
     * "Make his wings purple." Recolours whole parts, which is what the rig draws;
     * it used to paint a texture the rig never reads. An operator's command has no child, so a
     * creature that is not everyone's to change is refused rather than forked.
     */
    private static int paint(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ForkIdArgs.Paint line = ForkIdArgs.paint(StringArgumentType.getString(ctx, "args"));
        if (line.error() != null) {
            src.sendFailure(Component.literal(line.error()));
            return 0;
        }
        String key = line.key().toLowerCase(Locale.ROOT);
        String part = line.part();
        String colour = line.colour();

        int rgb = Species.parseColour(colour);
        CreaturePaint.Outcome r = CreaturePaint.paint(src.getServer(), key, part, rgb, "", null);
        if (!r.ok()) {
            src.sendFailure(Component.literal(r.message()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("🎨 " + r.message())
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /**
     * Take a picture of what the player is looking at, so the change can be <em>seen</em> rather
     * than just reported. Server-side command → client-side shutter (see {@code PhotoPayload}).
     */
    private static int photo(CommandContext<CommandSourceStack> ctx, String label) {
        CommandSourceStack src = ctx.getSource();
        try {
            ServerPlayer player = src.getPlayerOrException();
            com.moddingfromamod.net.Send.to(player, com.moddingfromamod.net.ModdedClient.photo(label));
            src.sendSuccess(() -> Component.literal("📸 Taking a photo…")
                    .withStyle(ChatFormatting.AQUA), false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            src.sendFailure(Component.literal("Only a player can take a photo."));
            return 0;
        }
    }


    /**
     * The species to spawn for a model key: an existing registered one, else built from a
     * {@code species.json} authored alongside the art, else sensible defaults — always carrying the
     * model key so the renderer draws the custom geometry.
     */
    private static Species resolveSpecies(MinecraftServer server, String key) {
        Species existing = SpeciesRegistry.get(key);
        if (existing != null && existing.hasCustomModel()) {
            return existing;
        }
        JsonObject o = resolveSpeciesOverrides(key);
        Species species = Species.fromJson(o);
        if (species == null) {
            return null;
        }
        // the library's creature is everybody's — registered SHARED, never stamped with the
        // child whose cached wish happened to spawn it (that put it on his shelf as his own).
        return com.moddingfromamod.species.CreatureFork.inLibrary(key)
                ? SpeciesRegistry.putShared(server, species)
                : SpeciesRegistry.put(server, species);
    }

    /** Build the species JSON for a key from its optional species.json + the mandatory id/modelKey. */
    /**
     * The species {@code wishmob spawn <key>} WOULD register, without registering it — or null when
     * the model repository knows no such creature. an earlier issue's Gate B run found the watch drawing no
     * creature icon for a starter dragon nobody had spawned yet ({@code PresetIcons}), because the
     * registry only holds a library creature after its first spawn.
     */
    public static Species peekSpecies(String key) {
        Species existing = SpeciesRegistry.get(key);
        if (existing != null) {
            return existing;
        }
        if (key == null || key.isBlank() || ModelRepository.speciesOverrides(key) == null) {
            return null;
        }
        return Species.fromJson(resolveSpeciesOverrides(key));
    }

    private static JsonObject resolveSpeciesOverrides(String key) {
        JsonObject o = ModelRepository.speciesOverrides(key);
        if (o == null) {
            o = new JsonObject();
        }
        o.addProperty("id", key);
        o.addProperty("modelKey", key);
        if (!o.has("name")) {
            o.addProperty("name", prettify(key));
        }
        return o;
    }

    private static String prettify(String key) {
        String[] parts = key.split("[_-]");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? key : sb.toString();
    }

    /** Every model-repository folder that holds a geometry file. */
    public static List<String> repositoryKeys() {
        Path dir = RuntimeConfig.modelDir();
        List<String> keys = new ArrayList<>();
        // THE LIBRARY COUNTS AS PRESENT. `/wishmob spawn` already resolves a library
        // creature, and a suggestion list that left them out would make a working command look like
        // a typo — which is how "the library did not load" and "I spelled it wrong" become the same
        // observation. Added first, de-duplicated below by the world's own listing.
        keys.addAll(com.moddingfromamod.library.WishLibrary.keys("species"));
        // And every child's own creatures, which live on their shelves.
        for (String k : ModelRepository.shelvedKeys()) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }
        if (!Files.isDirectory(dir)) {
            return keys;
        }
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("geometry.json")))
                    .map(p -> p.getFileName().toString())
                    .filter(ModelRepository::isValidKey)
                    .filter(k -> !keys.contains(k))
                    .sorted()
                    .forEach(keys::add);
        } catch (IOException ignored) {
            // a listing failure just yields no suggestions, never a crash
        }
        return keys;
    }
}
