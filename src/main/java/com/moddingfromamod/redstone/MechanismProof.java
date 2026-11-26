package com.moddingfromamod.redstone;

import com.moddingfromamod.agent.AgentBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Proves a placed mechanism works: at rest it is in its rest state; each control, used, moves it to its fired state
 * and it stays there; let go, it comes back and stays. Twice over.
 *
 * <p>The proof is a state machine stepped on the server thread at the end of EVERY tick, and it checks the world on
 * every tick. It was first written as an off-thread poll, and a GameTest server, which ticks as fast as it can, ran
 * 20 ticks between two polls: a button's whole pulse came and went unseen, and a working door read as broken. On a
 * normal server the same poll would have passed; stepping per tick means the same on both.
 */
@EventBusSubscriber
public final class MechanismProof {

    /** Ticks for a freshly built circuit to settle before its rest state is checked. */
    public static final int SETTLE = 14;
    /** A button stays pressed 20 to 30 ticks, and a plate's check runs every 20; the most a let-go may take on top. */
    public static final int LET_GO = 40;
    /** The longest a mechanism may take to answer a control (a long run's repeaters, a sequencer). */
    public static final int FIRE_LIMIT = 60;
    /** Once reached, a state must hold on every one of this many further ticks. */
    public static final int HOLD = 10;
    /** How many on-and-off cycles the proof runs per control. */
    public static final int CYCLES = 2;

    /** The proof's result: null {@link #why} when it held, and a line per step either way. */
    public record Result(String why, List<String> steps) {
        public boolean ok() {
            return why == null;
        }
    }

    private static final List<Run> RUNNING = new CopyOnWriteArrayList<>();

    private MechanismProof() {
    }

    /** Off the server thread: starts the proof on the server and waits for its result (up to two minutes). */
    public static Result prove(ServerLevel level, MechanismPlan plan) {
        return prove(level, plan, CYCLES);
    }

    /** As {@link #prove(ServerLevel, MechanismPlan)}, with {@code cycles} on-and-off rounds per control. */
    public static Result prove(ServerLevel level, MechanismPlan plan, int cycles) {
        Run run = new Run(level, plan);
        run.cycles = cycles;
        AgentBridge.onServer(() -> RUNNING.add(run), 10_000L);
        try {
            return run.done.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            run.stop = true;
            return new Result("the proof did not finish: " + e, run.steps);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (Run r : RUNNING) {
            if (r.stop || r.done.isDone()) {
                RUNNING.remove(r);
                continue;
            }
            try {
                r.tick();
            } catch (RuntimeException e) {
                r.finish("the proof threw " + e);
            }
        }
    }

    /** One proof. Phases: settle at rest, then per cycle and control: use, wait for fired, let go, wait for rest. */
    static final class Run {
        final ServerLevel level;
        final MechanismPlan plan;
        final List<String> steps = new ArrayList<>();
        final CompletableFuture<Result> done = new CompletableFuture<>();
        volatile boolean stop;
        int cycles = CYCLES;

        int cycle = 1, control = -1;     // control -1: the first settle at rest
        boolean fired;                   // the state being waited for
        boolean used;                    // has this phase's control been used yet
        int waited, held, limit = SETTLE + FIRE_LIMIT, reachedAt = -1, firedIn;

        Run(ServerLevel level, MechanismPlan plan) {
            this.level = level;
            this.plan = plan;
        }

        void finish(String why) {
            done.complete(new Result(why, List.copyOf(steps)));
        }

        String where() {
            Control c = plan.controls().get(control);
            return "the " + c.kind() + " at " + c.use();
        }

        void tick() {
            if (control >= 0 && !used) {
                Control c = plan.controls().get(control);
                // a TOGGLE is pressed again to switch it back; wait for the button to come up first
                if (c.toggle() && MechanismWorld.pressed(level, c)) {
                    MechanismWorld.use(level, c, false);   // step off a plate; a button comes up by itself
                    return;
                }
                if (!MechanismWorld.use(level, c, c.toggle() || fired)) {
                    finish(where() + " is not there to use");
                    return;
                }
                used = true;
                return;              // the world answers from the next tick on
            }
            waited++;
            String wrong = MechanismWorld.check(level, plan.expects(), fired);
            if (reachedAt < 0) {
                if (wrong == null) {
                    reachedAt = waited;
                    held = 0;
                } else if (waited > limit) {
                    finish(failure(wrong + " (after " + limit + " ticks)"));
                    return;
                }
                return;
            }
            if (wrong != null) {
                finish(failure("it got there in " + reachedAt + " ticks and did not stay: " + wrong));
                return;
            }
            if (++held < HOLD) {
                return;
            }
            advance();
        }

        String failure(String what) {
            String head = control < 0 ? "right after building, before anything was used"
                    : (fired ? "using " : "letting go of ") + where() + " (cycle " + cycle + ")";
            return head + ": " + what + " || circuit: " + MechanismWorld.trace(level, plan);
        }

        void advance() {
            if (control < 0) {
                steps.add("at rest: as planned");
            } else if (fired) {
                firedIn = reachedAt;
            } else {
                steps.add(where() + ": works in " + firedIn + " ticks and comes back in " + reachedAt + " (cycle "
                        + cycle + ")");
            }
            // next phase
            if (control >= 0 && fired) {
                fired = false;                    // let go of the same control
                limit = LET_GO + FIRE_LIMIT;
            } else {
                control++;
                if (control >= plan.controls().size()) {
                    if (cycle >= cycles || plan.controls().isEmpty()) {
                        finish(null);
                        return;
                    }
                    cycle++;
                    control = 0;
                }
                fired = true;
                limit = FIRE_LIMIT;
            }
            used = false;
            waited = 0;
            reachedAt = -1;
        }
    }
}
