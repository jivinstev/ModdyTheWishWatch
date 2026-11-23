package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.builder.Builder;
import com.moddingfromamod.wish.WishProgress;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

/**
 * Say where the magic is going to happen, and start it happening there.
 *
 * <p>Born from a real failure. A child asked for a giant airplane; everything worked, and he still
 * thought it was broken — because for 88 seconds while one was being composed for him, nothing on
 * screen changed. The mod now sparkles around him automatically while it waits, but the agent knows
 * something the mod cannot: <b>where the thing is going to appear</b>, usually long before it has
 * finished designing it.
 *
 * <p>Call this the moment you know. Sparkles around a child say "something is happening"; sparkles
 * gathering in a patch of sky say "something is happening THERE", and he turns and watches instead
 * of staring at chat waiting for a message. The effect thickens the longer it takes, so a slow wish
 * looks like a big one being made rather than a stuck one.
 */
public final class ConjureAtTool implements McpTool {

    @Override
    public String name() {
        return "conjure_at";
    }

    @Override
    public String description() {
        return "Tell the child WHERE his wish is about to appear, and start magic sparkles there "
                + "while you work. Call this EARLY. The spot was already decided when he wished — "
                + "where he was looking — and this CONFIRMS it; it does not re-aim from where he is "
                + "now. A big build can take a minute, and to a 7-year-old a minute of nothing looks "
                + "broken, so say something exciting in 'message' too ('a GIANT airplane? watch the "
                + "sky!'). Only pass `up` when the thing really belongs in the air (a sky castle, a "
                + "cloud): then the spot glides up to there. Use it for anything slow, not just "
                + "builds.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "message", "string",
                "What to tell the child right now, in simple excited words.");
        Schemas.prop(s, "up", "integer",
                "ONLY for a thing that belongs in the sky: how far above where he stood when he "
                        + "wished, e.g. 40. Leave it out and the wish stays on the ground where he "
                        + "was looking.");
        Schemas.prop(s, "forward", "integer",
                "ONLY together with `up`: how far out along where he was looking when he wished. "
                        + "On its own it changes nothing — the spot is already where he looked.");
        Schemas.prop(s, "at", "string", com.moddingfromamod.place.At.describe("the wish",
                "its front",
                "The sparkles — and the spot every later build"
                        + " of this wish anchors to — move THERE (\"a fountain next to my house\": conjure_at "
                        + "at:\"<house>:beside\", then build it as usual). The tools that take their own `at` "
                        + "(creatures, things, events, buildings, pictures) do not need this."));
        return s;
    }

    @Override
    public ToolResult call(JsonObject args) {
        String message = args != null && args.has("message") && args.get("message").isJsonPrimitive()
                ? args.get("message").getAsString() : "";
        // the spot was decided when he WISHED (WishAimer.lockAtWishTime) and this call
        // confirms it. It used to treat any `forward` as explicit and re-aim from the LIVE player —
        // the schema advertised "forward (default 12)", so the agent always sent it, and 20–25 s into
        // every wish the pillar jumped to wherever he had since flown to (five wishes measured).
        // Now only `up` moves it, and relative to the wish-time snapshot, never to where he is now.
        boolean lift = args != null && args.has("up");
        boolean far = args != null && args.has("forward");
        int forward = intOr(args, "forward", 0);
        int up = intOr(args, "up", 0);
        // a place he NAMED, rather than where he looked.
        String at = args != null && args.has("at") && args.get("at").isJsonPrimitive()
                ? args.get("at").getAsString().trim() : "";
        if (!at.isEmpty() && com.moddingfromamod.place.At.parse(at) == null) {
            return ToolResult.error("at must read \"<thing>:<spot>\" — a building's id, a creature or me, then a "
                    + "spot (beside, front, top, …), not '" + at + "'.");
        }
        try {
            return AgentBridge.onServer(() -> {
                var server = AgentBridge.serverOrNull();
                ServerPlayer player = com.moddingfromamod.agent.ActingChild.of(server);
                if (player == null) {
                    return ToolResult.noPlayerOnline("Nobody is in the world to show it to.");
                }
                var run = com.moddingfromamod.agent.AgentContext.currentRun();
                var from = run == null ? null : run.aimedFrom();
                BlockPos aimed = WishProgress.manifestAnchor(player);
                BlockPos spot = aimed;
                String moved = null;
                if (from == null && aimed == null) {
                    // No wish-time decision to confirm (a test, a headless probe): decide now, with
                    // the same rule a wish uses.
                    spot = com.moddingfromamod.wish.WishAimer.aim(player) instanceof
                            com.moddingfromamod.wish.WishAim.Spot a ? new BlockPos(a.x(), a.y(), a.z()) : null;
                    moved = "no wish-time spot";
                }
                if (!at.isEmpty()) {
                    var place = com.moddingfromamod.place.PlaceFinder.find(server, player, at,
                            com.moddingfromamod.builder.BuildSpots.FRONT);
                    if (!place.ok()) {
                        return ToolResult.error("Could not move the wish to " + at + ": " + place.why()
                                + ". The sparkles stay where he looked.");
                    }
                    var r = place.r();
                    // A ring is not a point: on its edge on his side, never the building's middle.
                    double start = Math.toDegrees(Math.atan2(-(player.getX() - r.x()), player.getZ() - r.z()));
                    double[] p = com.moddingfromamod.place.PlaceResolver.onRing(r, 0, 1, start, 1.0);
                    spot = new BlockPos((int) Math.floor(p[0]), (int) Math.floor(place.feetAt(p[0], p[1])),
                            (int) Math.floor(p[1]));
                    moved = "at " + r.link();
                }
                if (lift) {
                    // Up from where he STOOD when he wished; out along where he LOOKED then.
                    double bx, bz;
                    double baseY;
                    if (from != null) {
                        double rad = Math.toRadians(from.yaw());
                        bx = far ? from.x() - Math.sin(rad) * forward : (spot != null ? spot.getX() + 0.5 : from.x());
                        bz = far ? from.z() + Math.cos(rad) * forward : (spot != null ? spot.getZ() + 0.5 : from.z());
                        baseY = from.y();
                    } else {
                        BlockPos b = far ? Builder.anchorInFrontOf(player, forward, 0)
                                : spot != null ? spot : player.blockPosition();
                        bx = b.getX() + 0.5;
                        bz = b.getZ() + 0.5;
                        baseY = player.getY();
                    }
                    spot = new BlockPos((int) Math.floor(bx), (int) Math.floor(baseY) + up, (int) Math.floor(bz));
                    if (run != null) {
                        run.raise();
                    }
                    moved = "lifted " + up + " with up";
                }
                if (spot == null) {
                    spot = player.blockPosition();
                }
                if (moved != null && run != null) {
                    com.moddingfromamod.wish.WishAimer.logMove(run.shortId(), aimed, spot, moved);
                }
                // There is no settle here any more: the wish-time spot is already on the ground
                // (WishAim settles it once, when he wishes), and an explicit `up` is never settled.
                WishProgress.focusOn(player, spot);
                // Start a placeholder bar NOW. plan_wish is the first thing that can show a real
                // one, and it lands only after the model has decided the whole build — 69 seconds
                // on one measured wish, all of it with nothing on screen but a generic ticker.
                // Replaced by the real plan the moment plan_wish arrives.
                var thinkingRun = com.moddingfromamod.agent.AgentContext.currentRun();
                // ⚠ THIS USED TO BE TWO PLAYERS, and D9 is why it is one again.
                //
                // `player` was a findFirst() — join order — so it decided WHERE THIS LANDS while a
                // separately-resolved owner decided who got TOLD. The split was deliberate and the
                // comment said so: anchoring A's build at B's position needed a decision rather
                // than a rewrite, so only the telling half (D1/D2) was fixed.
                //
                // ActingChild answers both with the run's owner, so the two variables now resolve
                // to the same child in every case where a run is bound, and to the same fallback
                // when none is. Keeping them apart would be a distinction that no longer exists.
                ServerPlayer tell = player;
                if (thinkingRun != null) {
                    WishProgress.startThinking(tell, thinkingRun.wishId(),
                            thinkingRun.subject());
                }
                // "See the sparkles?": once per wish, for a thing that appears in the world.
                // A kind not declared yet waits for plan_wish, which says it then.
                if (run != null) {
                    run.conjured();
                    if (run.saySparklesOnce()) {
                        com.moddingfromamod.wish.WishFeedback.sparklesHere(tell);
                    }
                }
                if (!message.isBlank()) {
                    tell.sendSystemMessage(Component.literal("✨ " + message)
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)));
                }
                return ToolResult.ok("The wish is now LOCKED to manifest at " + spot.getX() + ","
                        + spot.getY() + "," + spot.getZ() + " — that's where the sparkles are and "
                        + "where the child is looking. Just build normally (no offsets needed): "
                        + "build_structure and the other building tools anchor to this locked spot "
                        + "automatically, so it appears exactly where you promised even if the child "
                        + "walks away."
                        // Said out loud rather than done quietly: the agent has just told the child
                        // where to look, and it should know the spot moved. Told, not asked --
                        // a wish left hanging in the air is the worse outcome by far.
                        + (far && !lift
                                ? "\n(`forward` on its own changes nothing: the spot is where he was "
                                  + "looking when he wished. Pass `up` too only if it belongs in the sky.)"
                                : ""));
            });
        } catch (AgentBridge.Unavailable e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        try {
            return o != null && o.has(key) && o.get(key).isJsonPrimitive()
                    ? o.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
