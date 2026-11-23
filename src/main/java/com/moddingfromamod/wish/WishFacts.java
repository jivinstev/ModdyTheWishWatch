package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.marshal.ReferentFacts;
import com.moddingfromamod.marshal.WishLadder;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Gathers the referent facts for a wish from the live world</b> — what of his is in his
 * view ({@link ArtifactIndex}'s {@code inView}, a real look test against his look vector) and what
 * he talked about last ({@link WishTopic}). The wording is {@link ReferentFacts}, which is pure.
 *
 * <p>Gathered at submit, because that is when his eyes were where the wish was aimed; the agent
 * reads it later from the wish's note. Nothing here decides, names or announces anything.
 */
public final class WishFacts {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModdingFromAModMod.MODID);

    private WishFacts() {
    }

    /** The facts block for this child now, or "". Server thread only; never throws. */
    public static String forPlayer(ServerPlayer player) {
        if (player == null) {
            return "";
        }
        try {
            List<WishLadder.Candidate> his = ArtifactIndex.forPlayer(player);
            List<ReferentFacts.Thing> inView = new ArrayList<>();
            for (WishLadder.Candidate c : his) {
                if (c.inView() && c.distance() >= 0) {
                    inView.add(thing(c));
                }
            }
            WishTopic.Last topic = WishTopic.last(player.getUUID());
            String lastId = topic == null ? null : topic.artifactId();
            ReferentFacts.Thing last = null;
            if (lastId != null) {
                last = new ReferentFacts.Thing(lastId, topic.kind(), "", -1);
                for (WishLadder.Candidate c : his) {
                    if (c.id().equalsIgnoreCase(lastId)) {
                        last = thing(c);
                        break;
                    }
                }
            }
            WishTopic.Wished prev = WishTopic.previousWish(player.getUUID());
            return ReferentFacts.render(inView, last, WishTopic.ageMs(player.getUUID()),
                    topic == null ? "" : topic.words(),
                    prev == null ? "" : prev.words(),
                    prev == null ? -1 : Math.max(0L, System.currentTimeMillis() - prev.atMs()));
        } catch (RuntimeException e) {
            // Context, not a gate: a wish without its facts is still a wish.
            LOGGER.warn("could not gather the referent facts: {}", e.toString());
            return "";
        }
    }

    private static ReferentFacts.Thing thing(WishLadder.Candidate c) {
        return new ReferentFacts.Thing(c.id(), c.kind(), c.name(), c.distance());
    }
}
