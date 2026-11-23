package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * <b>Which of the things a wish's commands spawned were SCAFFOLDING —.</b>
 *
 * <p>The agent builds props to check its own work: an armour stand to aim a fire breath at, a marker
 * to measure from, a chicken to burn. Building them is right — a wish that claims an ability works
 * without checking is worse — and it never removes them, so they stand in the child's world, in his
 * line of sight, after the wish is over. Found by photographing the child's own screen, not by a
 * gate: none of them is wrong enough to trip an assertion.
 *
 * <p><b>The rule is "what the wish made and did not keep", not "it looks like an armour stand".</b>
 * A type list is unfixable — the next prop is a block, a fire, a chicken — and it is also the thing
 * explicitly refuses. So the wish is BRACKETED instead: everything a {@code run_command}
 * spawned while it was in flight is recorded on the run, and what is still standing at
 * {@code finish_wish} minus what the wish registered is scaffolding by definition.
 *
 * <p>This class is the decision and nothing else — no Minecraft, so the cases that are frightening
 * to get wrong can be written down as tests rather than discovered in a child's world.
 *
 * <h2>⚠ The direction that must never be wrong</h2>
 *
 * <p> says it plainly: <i>getting this wrong deletes the present</i>. So every keep rule here is
 * unconditional and the sweep rule is the fallthrough, and the reason the fallthrough is acceptable
 * at all is a measurement rather than an argument — across <b>147 real {@code run_command} calls</b>
 * in the action log there are <b>17 summons and every one is a test prop</b>: eleven armour stands,
 * three markers, an area-effect cloud, and a pig and a chicken both summoned at a fire target's
 * coordinates with {@code NoAI:1b}. Not one legitimate summon-and-keep. That is not proof it cannot
 * happen — a child may wish for a herd tomorrow — which is why the keep rules below exist and why
 * every removal is reported rather than silent.
 */
public final class PropSweep {

    private PropSweep() {
    }

    /**
     * One thing a command spawned, described in terms this class can reason about.
     *
     * @param id      the entity, so the caller can find it again
     * @param handle  the name {@code EntityCapture} gave it, or "" — this is what a wish REGISTERS
     * @param typeId  {@code minecraft:armor_stand} and friends, for the report only, never a rule
     * @param player  a player is never scaffolding, whatever else is true
     * @param carrying whether something is riding it, or it is riding something
     * @param named   whether it carries a CustomName — see {@link Kept#NAMED}
     * @param living  a mob with its AI on, alive or dying — see {@link Kept#LIVING}
     * @param marked  the command that spawned it carried {@code keep: true} — see {@link Kept#MARKED}
     * @param markable whether the agent's word CAN keep it — false for a dropped item, a thing in
     *                flight and a mob frozen with {@code NoAI}; see {@link Kept#MARKED}
     */
    public record Spawned(UUID id, String handle, String typeId, boolean player, boolean carrying,
                          boolean named, boolean living, boolean marked, boolean markable) {

        /** Marked or not, and a thing the mark can keep. */
        public Spawned(UUID id, String handle, String typeId, boolean player, boolean carrying,
                       boolean named, boolean living, boolean marked) {
            this(id, handle, typeId, player, carrying, named, living, marked, true);
        }

        /** Not marked: every verdict reads exactly as it did. */
        public Spawned(UUID id, String handle, String typeId, boolean player, boolean carrying,
                       boolean named, boolean living) {
            this(id, handle, typeId, player, carrying, named, living, false);
        }

        /** Anything that is not a living mob with its AI on — every prop on record. */
        public Spawned(UUID id, String handle, String typeId, boolean player, boolean carrying,
                       boolean named) {
            this(id, handle, typeId, player, carrying, named, false, false);
        }
    }

    /** Why one spawned thing survived, so a report can say it rather than a count. */
    public enum Kept {
        /** It is scaffolding — remove it. */
        NOT_KEPT,
        /** A player. Cannot be spawned by a summon, and must never be reachable by a sweep. */
        PLAYER,
        /** The wish registered it: it is part of what the child asked for. */
        REGISTERED,
        /** Somebody is riding it, or it is riding somebody. Deleting it dismounts a child. */
        CARRYING,
        /**
         * <b>Somebody gave it a name, so somebody meant to keep it.</b>
         *
         * <p>Not a heuristic dressed up as a rule — it is read off the corpus. All seventeen real
         * props carry {@code Tags:["firetest"]} and <b>not one carries a {@code CustomName}</b>,
         * while the things a wish means to keep are named as a matter of course. So this costs
         * nothing measurable and covers the class of thing the fallthrough would otherwise reach:
         * a named item frame hung as part of a build, a named villager, an armour stand that IS
         * the statue somebody wished for.
         */
        NAMED,
        /**
         * <b>A living mob with its AI on is never a test prop,.</b>
         *
         * <p>A child wished for 100 creepers; they were summoned by {@code run_command}, unnamed and
         * unregistered, so every rule above missed and the fallthrough removed all 100 3.5 s after
         * the reveal. The props on record are armour stands, markers, an area-effect cloud, and a
         * pig and a chicken summoned with {@code NoAI:1b} — none of them a mob with its AI on. The
         * accepted trade-off: a test mob the agent summons with AI on now
         * stays. A test mob with AI on is a problem mid-wish in its own right, so keeping it is the
         * safe direction; if it shows up, it is a lesson ("test mobs are {@code NoAI:1b}"), not a
         * reason to sweep live mobs.
         */
        LIVING,
        /**
         * <b>The agent said so: the command that spawned it carried {@code keep: true} —.</b>
         *
         * <p>Wish {@code 0bc44a2f} parked three minecarts in a garage "forever" and the sweep took
         * all three: unnamed, unregistered, unridden and not alive, so every rule above missed. Every
         * earlier fix was another keep-by-APPEARANCE rule (an earlier issue's living mobs); this one is the
         * agent's own word, because only the agent knows whether a minecart is a car or a probe.
         *
         * <p>The two ways the agent can get it wrong are both bounded. Forgetting it is exactly the
         * behaviour before this rule, and {@link #warning} tells it before the reveal. Passing it
         * on a probe keeps only what THAT one command spawned — the mark is stamped per command
         * and is never a switch on the wish.
         *
         * <p><b>And the mark cannot keep what only a test makes</b> ({@link Spawned#markable}). The
         * agent DOES pass keep on probes it thinks of as gifts — the magic carrot's test animals,
         * 7 calls in 7 — so its word alone cannot separate them. What can is the state the
         * thing was summoned in, read off the entity rather than a type list or a word:
         * <ul>
         *   <li>a <b>mob frozen with {@code NoAI}</b> — every probe mob on record (the trident's
         *       {@code pulltest} pigs, an earlier issue's fire-target pig and chicken) and no delivery on
         *       record. A statue he asked for is kept by its NAME, which the warning tells the agent;
         *   <li>a <b>dropped item, an experience orb or a projectile</b> — the smelting pickaxe's
         *       test ore, the cake machine's test eggs, the trident's test trident, the pearl's test
         *       pearl. None is a lasting thing in his world: an item he should have goes in his hand
         *       with {@code give}, and a thing in flight is a moment.
         * </ul>
         * <p>Everything the mark CAN keep is what a delivery is made of — vehicles, armour stands,
         * displays, frames, markers — and a probe of that kind is left to the agent's word, which
         * an earlier issue's panel measures.
         */
        MARKED
    }

    /**
     * The whole decision for one spawned thing.
     *
     * @param keptHandles what the run recorded in its {@code made()} ledger, by id
     */
    public static Kept verdict(Spawned s, Collection<String> keptHandles) {
        if (s == null) {
            return Kept.NOT_KEPT;
        }
        if (s.player()) {
            return Kept.PLAYER;
        }
        if (s.carrying()) {
            return Kept.CARRYING;
        }
        if (s.named()) {
            return Kept.NAMED;
        }
        if (s.handle() != null && !s.handle().isBlank() && keptHandles != null) {
            String mine = bare(s.handle());
            for (String kept : keptHandles) {
                if (kept != null && bare(kept).equalsIgnoreCase(mine)) {
                    return Kept.REGISTERED;
                }
            }
        }
        // Last of the keep rules, so every verdict above reads exactly as it did.
        if (s.living()) {
            return Kept.LIVING;
        }
        //, after LIVING so the verdicts above it read exactly as before; never for a thing
        // only a test makes (a frozen mob, a drop, a projectile) — see Kept#MARKED.
        if (s.marked() && s.markable()) {
            return Kept.MARKED;
        }
        return Kept.NOT_KEPT;
    }

    /**
     * A handle without its sigil.
     *
     * <p>The two sides of that comparison are minted by different code and spelled differently:
     * {@code EntityHandles} makes {@code @bee}, and what a run records in {@code made()} is
     * whatever argument the tool was called with — {@code modify_mob}'s target arrives with the
     * {@code @}, an artifact id like {@code spikor} without one. A keep rule that missed on a
     * leading character would be a keep rule that silently never fires, which is indistinguishable
     * from not having written it.
     */
    private static String bare(String handle) {
        String h = handle.trim();
        return h.startsWith("@") ? h.substring(1) : h;
    }

    /** Everything to remove, in the order it was spawned. Never contains a duplicate. */
    public static List<UUID> toRemove(List<Spawned> spawned, Collection<String> keptHandles) {
        Set<UUID> out = new LinkedHashSet<>();
        if (spawned == null) {
            return new ArrayList<>();
        }
        for (Spawned s : spawned) {
            if (s != null && s.id() != null && verdict(s, keptHandles) == Kept.NOT_KEPT) {
                out.add(s.id());
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * <b>What to tell the agent — and it is told, not asked.</b>
     *
     * <p>A silent tidy-up is the shape this repo keeps writing up: the world changes, the run says
     * nothing, and the next person to wonder why a prop vanished has no thread to pull. It is also
     * a level-2 check on the repo's own ladder — reported back INSIDE the authoring tool, before
     * the reveal — so an agent that wanted a prop to survive learns immediately that it did not.
     *
     * @param removed how many props were taken down
     * @param gone    how many were already gone (the agent had tidied up after itself)
     * @param kept    how many survived a keep rule
     */
    public static String report(int removed, int gone, int kept) {
        if (removed == 0 && gone == 0 && kept == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder("\n(Tidied up after the wish: ");
        if (removed == 0) {
            b.append("nothing to remove");
        } else {
            b.append("took down ").append(removed).append(" test prop")
                    .append(removed == 1 ? "" : "s").append(" your commands built");
        }
        if (gone > 0) {
            b.append(", ").append(gone).append(" you had already cleared");
        }
        if (kept > 0) {
            b.append(", and left ").append(kept).append(" alone because ")
                    .append(kept == 1 ? "it is" : "they are").append(" part of the wish");
        }
        return b.append(".)").toString();
    }

    /**
     * <b>What one {@code run_command} is told about the things it spawned that the sweep will take —
     *.</b>
     *
     * <p>The tidy runs AFTER the reveal, so an agent that meant a minecart as a car used to learn
     * that it had been deleted only from the child. This moves that to level 2 on the repo's own
     * ladder: the command that spawned it says so, in one sentence, while the agent can still act.
     *
     * <p>Only what {@link #verdict} would remove RIGHT NOW is counted, so it never fires for
     * anything a keep rule already covers (a live mob, a named thing, a ridden one, a marked one),
     * and the caller passes only what is still standing, so a probe the same batch already killed
     * is not mentioned. Empty when there is nothing to say, which is most calls.
     */
    public static String warning(List<Spawned> spawned, Collection<String> keptHandles) {
        if (spawned == null || spawned.isEmpty()) {
            return "";
        }
        java.util.Map<String, Integer> unmarked = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> refused = new java.util.LinkedHashMap<>();
        for (Spawned s : spawned) {
            if (s != null && verdict(s, keptHandles) == Kept.NOT_KEPT) {
                // Marked and still NOT_KEPT can only mean the mark was refused (Kept#MARKED).
                (s.marked() ? refused : unmarked).merge(noun(s.typeId()), 1, Integer::sum);
            }
        }
        String out = "";
        if (!unmarked.isEmpty()) {
            int n = unmarked.values().stream().mapToInt(Integer::intValue).sum();
            out += warningText(things(unmarked), n != 1);
        }
        if (!refused.isEmpty()) {
            int n = refused.values().stream().mapToInt(Integer::intValue).sum();
            out += refusedText(things(refused), n != 1);
        }
        return out;
    }

    /** {@code "3 minecarts, 1 oak boat"}; past three kinds, "N other kinds". */
    private static String things(java.util.Map<String, Integer> byType) {
        List<String> parts = new ArrayList<>();
        int shown = 0;
        for (var e : byType.entrySet()) {
            if (shown == 3) {
                parts.add((byType.size() - 3) + " other kind" + (byType.size() - 3 == 1 ? "" : "s"));
                break;
            }
            int n = e.getValue();
            parts.add(n + " " + e.getKey() + (n == 1 || e.getKey().endsWith("s") ? "" : "s"));
            shown++;
        }
        return String.join(", ", parts);
    }

    /**
     * <b>The entity types this call's commands SUMMON by name</b> — {@code minecraft:minecart} for
     * {@code summon minecart ~ ~ ~}, {@code execute … run summon …} included.
     *
     * <p>The warning counts only these, because its sentence is "summon them with keep:true". A
     * command spawns other things as a side effect — the porkchop and the experience orb a
     * {@code kill} of a test pig drops — and naming those would tell the agent to keep loot it
     * never summoned. They are still recorded and still tidied, exactly as before.
     */
    public static Set<String> summonedTypes(List<String> commands) {
        Set<String> out = new LinkedHashSet<>();
        if (commands == null) {
            return out;
        }
        for (String c : commands) {
            if (c == null) {
                continue;
            }
            String[] words = c.trim().split("\\s+");
            for (int i = 0; i + 1 < words.length; i++) {
                String w = words[i].startsWith("/") ? words[i].substring(1) : words[i];
                if (w.equals("summon")) {
                    String t = words[i + 1].toLowerCase(java.util.Locale.ROOT);
                    out.add(t.contains(":") ? t : "minecraft:" + t);
                }
            }
        }
        return out;
    }

    /**
     * The sentence itself, with the counted things already spelled ({@code "3 minecarts"}). Public
     * so the agent contract can dump it and the routing stub answers in the SAME words — a stub
     * that paraphrased the warning would measure the paraphrase.
     */
    public static String warningText(String things, boolean plural) {
        return "\n(" + things + " from this command will be tidied away when the wish ends. If "
                + (plural ? "they are" : "it is") + " part of what he asked for, summon "
                + (plural ? "them" : "it") + " with keep:true.)";
    }

    /**
     * What a call that passed {@code keep: true} is told about the things the mark could NOT keep
     * (a frozen mob, a drop, a projectile — {@link Kept#MARKED}). Said, never silent: an agent that
     * meant a frozen pig as a statue learns before the reveal how to keep it — by its name.
     */
    public static String refusedText(String things, boolean plural) {
        return "\n(keep:true does not keep " + things + ": a mob frozen with NoAI, a dropped item or a "
                + "thing in flight is a test prop, so " + (plural ? "they" : "it")
                + " will be tidied away when the wish ends. If " + (plural ? "they are" : "it is")
                + " really what he asked for, a frozen mob stays when it has a CustomName, and an "
                + "item belongs in his hand with give.)";
    }

    /** {@code minecraft:armor_stand} → {@code armor stand}; a modded id keeps only its path. */
    private static String noun(String typeId) {
        String t = typeId == null || typeId.isBlank() ? "thing" : typeId.trim();
        int colon = t.indexOf(':');
        return (colon >= 0 ? t.substring(colon + 1) : t).replace('_', ' ');
    }
}
