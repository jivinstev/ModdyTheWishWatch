package com.moddingfromamod.gatec;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.moddingfromamod.ModdingFromAModMod;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * <b>A network between the server and this client, in a test.</b>
 *
 * <p>A singleplayer client talks to its server over an in-memory channel, so every packet arrives the instant it is
 * sent and a schedule that would stutter over a real network looks smooth. This holds each packet the CLIENT receives
 * for {@code delay ± jitter} milliseconds (normal), delivering them IN ORDER as TCP does: a late packet holds back the
 * ones behind it (head-of-line). A lost packet is modelled as TCP sees it, a retransmit: that packet and everything
 * behind it arrive {@code rto} later.
 *
 * <p>{@code -Dmoddingfromamod.lag=DELAY:JITTER[:LOSS_PERCENT[:RTO]]} in milliseconds, e.g. {@code 80:20} or
 * {@code 200:60:1}. A film at {@code filmtickrate=5} runs the game 4x slower, so a real 20 ms is passed as 80 here
 * (the caller scales it). Off when unset; never in production (it lives in the client test package).
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class LagInjector {

    private LagInjector() {
    }

    private static final String SPEC = System.getProperty(ModdingFromAModMod.MODID + ".lag", "");
    private static final double DELAY;
    private static final double JITTER;
    private static final double LOSS;
    private static final double RTO;
    /**
     * {@code lagper=flush} (default): packets that reach this client together (one server tick's flush, within
     * {@link #BURST_NS}, {@code lagburst} ms, default 2) share one delay, as one TCP write mostly arrives together; the jitter is between flushes.
     * {@code lagper=packet}: every packet its own delay, the worst case (one tick's moves scattered over client ticks).
     */
    private static final boolean PER_PACKET =
            "packet".equals(System.getProperty(ModdingFromAModMod.MODID + ".lagper", "flush"));
    private static final long BURST_NS =
            1_000_000L * Long.getLong(ModdingFromAModMod.MODID + ".lagburst", 2L);

    static {
        String[] p = SPEC.isBlank() ? new String[0] : SPEC.split(":");
        DELAY = p.length > 0 ? Double.parseDouble(p[0]) : 0;
        JITTER = p.length > 1 ? Double.parseDouble(p[1]) : 0;
        LOSS = p.length > 2 ? Double.parseDouble(p[2]) / 100.0 : 0;
        RTO = p.length > 3 ? Double.parseDouble(p[3]) : Math.max(200, 4 * DELAY);
    }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (SPEC.isBlank()) {
            return;
        }
        ChannelPipeline pipe = event.getConnection().channel().pipeline();
        if (pipe.get("mfam_lag") != null) {
            return;
        }
        pipe.addBefore("packet_handler", "mfam_lag", new Hold(new Random(2676)));
        System.out.printf("[lag] inbound packets held %.0f +- %.0f ms (normal, per %s), loss %.1f%% (retransmit +%.0f ms), in order%n",
                DELAY, JITTER, PER_PACKET ? "packet" : "flush", LOSS * 100, RTO);
    }

    /**
     * <b>One ordered queue, drained by one task.</b> Delivery times only ever grow, and packets leave strictly in the
     * order they arrived. The first version scheduled each packet as its own task; two packets due at the same moment
     * got deadlines a few nanoseconds apart (each computed from its own {@code nanoTime()}), so a later one could run
     * first. Real TCP never reorders, and a reorder that put a display's data update ahead of its spawn left that part
     * on the client with no {@code teleport_duration} (it snapped every move) for the whole run: the "parts left behind"
     * anomaly of an earlier change's lag arms (measured: 8 of 40 parts at ease 0, the other 32 at 3).
     */
    static final class Hold extends ChannelDuplexHandler {
        private final Random rnd;
        private final java.util.ArrayDeque<Object[]> queue = new java.util.ArrayDeque<>();
        private long lastDue;
        private long lastArrival;
        private boolean draining;

        Hold(Random rnd) {
            this.rnd = rnd;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            long now = System.nanoTime();
            boolean sameFlush = !PER_PACKET && now - lastArrival < BURST_NS;
            lastArrival = now;
            long due;
            if (sameFlush) {
                due = lastDue;
            } else {
                double ms = Math.max(0, DELAY + JITTER * rnd.nextGaussian());
                if (LOSS > 0 && rnd.nextDouble() < LOSS) {
                    ms += RTO;
                }
                due = Math.max(lastDue, now + (long) (ms * 1_000_000L));
            }
            lastDue = due;
            queue.addLast(new Object[] {due, msg});
            if (!draining) {
                draining = true;
                ctx.executor().schedule(() -> drain(ctx), Math.max(0, due - now), TimeUnit.NANOSECONDS);
            }
        }

        /** On the channel's own event loop: deliver every packet now due, in order, then wait for the next. */
        private void drain(ChannelHandlerContext ctx) {
            long now = System.nanoTime();
            while (!queue.isEmpty() && (long) queue.peekFirst()[0] <= now) {
                ctx.fireChannelRead(queue.pollFirst()[1]);
            }
            if (queue.isEmpty()) {
                draining = false;
            } else {
                long next = (long) queue.peekFirst()[0];
                ctx.executor().schedule(() -> drain(ctx), Math.max(0, next - now), TimeUnit.NANOSECONDS);
            }
        }
    }
}
