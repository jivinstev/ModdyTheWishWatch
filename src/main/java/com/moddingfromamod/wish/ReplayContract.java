package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

/**
 * <b>The rule every "the watch remembers it" feature must obey.</b>
 *
 * <p>A learned wish is a list of <b>commands</b>, not tool calls. So a wish can only ever become a
 * button if some command can carry it out. That gives every feature which saves a design <b>five</b>
 * obligations, and all five or none — each one useless without the rest:
 *
 * <ol>
 *   <li><b>Save the design</b> in a store that survives the session.</li>
 *   <li><b>Prove it's remembered</b>: the wish becomes an entry on the watch — a button the child
 *       can find without having to recall the words he used.</li>
 *   <li><b>A replay command</b> that rebuilds it from that store by id, registered in the game.</li>
 *   <li><b>An agent tool that LISTS what is stored</b>, and is reachable — otherwise "another
 *       McBurger's train" reads to the agent as a blank sheet and it designs a second, different
 *       train.</li>
 *   <li><b>The agent told to call that list first</b>, or the tool exists and is never used.</li>
 * </ol>
 *
 * <p>3 also requires the command to be on {@link CommandGuard}'s allowlist, or {@code add_template}
 * refuses it at teach time.
 *
 * <p><b>The one intentional exception: {@code reuseByAutoLoad} (CBScript datapacks).</b> "All five or
 * none" is deliberately strong, and stays the default for every future wish type. Datapacks earn a
 * documented carve-out because a datapack <b>auto-loads into every world</b>, so its mechanic is
 * reused for free without a button. Obligation 2 (a button) therefore becomes <b>conditional</b> for
 * them: an ACTION datapack (something the child re-triggers on demand — a give, a spawn) still gets a
 * button via {@code /wishdatapack <id> <entry>}, but a RULE datapack (an ambient, always-on behavior
 * — Grumbnar's smash, "always night") legitimately has <b>no</b> button, because one would visibly do
 * nothing. The infra obligations (3 replay command, 4 list tool, 5 told-to-list) stay mandatory even
 * for datapacks — only 2 relaxes, and only for this flagged category. The agent <b>declares</b> which
 * kind it is ({@code reuse_action} present or absent on {@code author_datapack}); it is never inferred
 * from the compiled pack, because the categories overlap inside one pack. See docs/DATAPACKS.md.
 *
 * <p><b>This class exists because obligation 1 was met twice without obligations 2 and 3.</b> Trains
 * saved every plan to {@code trains.json} and machines could be replayed, but there was no
 * {@code /wishtrain} — so {@code add_template} had nothing to put in {@code commands} and every
 * McTrain cost a full agent round-trip, forever, while its design sat saved and unreachable. Runtime
 * creatures had the same hole: {@code /wishmob spawn} existed but was not allowlisted, so teaching
 * one was rejected.
 *
 * <p>Neither was visible to any gate. The train tests built trains directly, the creature tests
 * spawned creatures directly, and every one of them passed. A feature is only <i>finished</i> when
 * the child can get it back with one tap — and nothing was checking that.
 *
 * <p><b>Adding a new kind of thing the watch can make? Add it here.</b> The Gate B test and Gate D
 * both read this list, so a missing replay command fails immediately and by name instead of
 * surfacing months later as "why do I have to ask for my train again every time".
 */
public final class ReplayContract {

    /**
     * One kind of saved design, and how a wish template reinstates it.
     *
     * @param feature  what the child would call it
     * @param sample   a representative command, exactly as a template would contain it
     * @param store    the file under {@code <world>/wishes/} holding the designs
     * @param builtBy  the agent tool that creates one, for the failure message
     * @param listTool the agent tool that enumerates what is already saved
     * @param reuseByAutoLoad the ONE relaxation: when true (datapacks only), obligation 2 (a button)
     *        is conditional because the design auto-loads every world — a rule needs no button. The
     *        infra obligations (3/4/5) are still checked. False for every other type, and the default
     *        for anything added later, so the contract stays strong-by-default.
     */
    public record Replayable(String feature, String sample, String store, String builtBy,
                             String listTool, boolean reuseByAutoLoad) {

        /** The command word Brigadier registers. */
        public String command() {
            int sp = sample.indexOf(' ');
            return sp < 0 ? sample : sample.substring(0, sp);
        }
    }

    public static final List<Replayable> ALL = List.of(
            new Replayable("buildings", "wishbuild demo_id", "buildings.json",
                    "build_structure", "list_builds", false),
            // "machines" (/wishmachine) and "trains" (/wishtrain) went with Create. A taught
            // button that still says one is refused by the ordinary unknown-command path.
            new Replayable("creatures", "wishmob spawn demo_id", "species.json",
                    "sculpt_creature", "list_creatures", false),
            // The one exception — see the class javadoc. A datapack auto-loads every world, so its
            // button is conditional (ACTION → button via /wishdatapack <id> <entry>; RULE → none).
            // The command, list tool and told-to-list obligations are still enforced below.
            new Replayable("datapacks", "wishdatapack demo_id give", "wish-datapacks/ (configured mod repo)",
                    "author_datapack", "list_datapacks", true),
            // ITEMS SHARE THE DATAPACK'S COMMAND, because an item IS a datapack behind the scenes:
            // make_item renders a template, compiles it under the item's feature id, and the way to
            // put it in his hand is `wishdatapack <id> give`. The store is its own, though — the
            // pack is the behaviour, and items.json is the DESIGN, which is what an edit needs and
            // what a second wand would otherwise be made from scratch against.
            new Replayable("items", "wishdatapack demo_id give", "items.json",
                    "make_item", "inspect_item", true),
            // MACHINES YOU FILL (the first new class since the contract): an item on the
            // machine shape with load_and_pour, so it replays exactly as an item does — but it is a
            // class of its own in the register because a child wishes for it as a machine, and it
            // owes C18 (it explains itself) where most items owe nothing.
            new Replayable("appliances", "wishdatapack demo_id give", "items.json",
                    "make_item", "inspect_item", true),
            // PICTURES: /wishpaint hung them from the start and they sat
            // outside this list, so nothing checked that a painted wish could become a button.
            // The design is the painting's recipe on his shelf; where it hangs is separate.
            new Replayable("pictures", "wishpaint demo_id", "pictures.json",
                    "paint_surface", "list_paintings", false),
            // BLOCKS WITH EFFECTS: an item on the magic_block shape. It replays as an item
            // does; it is a class because he wishes for a BLOCK, and it owes the safety rules of a
            // thing that acts on the world by itself (caps, no block entities, the cow rule).
            new Replayable("effect_blocks", "wishdatapack demo_id give", "items.json",
                    "make_item", "inspect_item", true),
            // STAGED EVENTS: a wave, a flood, a meteor. The design is kept per world, and
            // `moddyevent stage <name>` sets it off again where he is — the one subcommand
            // CommandGuard lets a template, a rule or an item run.
            new Replayable("events", "moddyevent stage demo_id",
                    "<world>/data/moddingfromamod_event_designs.dat", "stage_event", "list_events", false));

    private ReplayContract() {
    }

    /**
     * Everything that breaks the contract right now, or empty if it holds.
     *
     * <p>Both halves are checked because either alone is useless: a command that does not exist
     * cannot rebuild anything, and a command the guard refuses is rejected the moment the agent
     * tries to teach it — which looks, from the outside, exactly like an agent that forgot.
     */
    public static List<String> violations(CommandDispatcher<CommandSourceStack> dispatcher) {
        List<String> toolNames = new ArrayList<>();
        String prompt = "";
        try {
            com.moddingfromamod.agent.WishAgent.buildTools().all()
                    .forEach(t -> toolNames.add(t.name()));
            prompt = com.moddingfromamod.agent.AgentSession.prompt();
        } catch (RuntimeException e) {
            // Never let the audit itself be the thing that breaks; report what we could check.
            toolNames.add("(tool list unavailable: " + e + ")");
        }
        List<String> problems = new ArrayList<>();
        for (Replayable r : ALL) {
            if (dispatcher != null && dispatcher.getRoot().getChild(r.command()) == null) {
                problems.add(r.feature() + ": no /" + r.command() + " command, so a "
                        + r.feature().substring(0, r.feature().length() - 1)
                        + " made with " + r.builtBy() + " can NEVER become a button — "
                        + "add_template would have no command to put in its plan");
            }
            if (!CommandGuard.isAllowed(r.sample())) {
                problems.add(r.feature() + ": CommandGuard refuses '" + r.sample()
                        + "', so teaching one would be rejected at teach time and the design in "
                        + r.store() + " stays saved but unreachable");
            }
            if (!toolNames.contains(r.listTool())) {
                problems.add(r.feature() + ": no " + r.listTool() + " tool, so the agent cannot see "
                        + "what is already in " + r.store() + " — \"another one\" becomes a fresh "
                        + "design job and the child gets a DIFFERENT " + one(r.feature())
                        + " from the one he meant");
            } else if (!prompt.contains(r.listTool())) {
                problems.add(r.feature() + ": " + r.listTool() + " exists but the agent is never "
                        + "told to call it, so it will keep designing from scratch — a tool nobody "
                        + "reaches for is the same as no tool");
            }
        }
        return problems;
    }

    /** "trains" → "train", for a readable sentence. */
    private static String one(String plural) {
        return plural.endsWith("s") ? plural.substring(0, plural.length() - 1) : plural;
    }
}
