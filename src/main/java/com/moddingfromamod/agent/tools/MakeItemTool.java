package com.moddingfromamod.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.AgentBridge;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.datapack.CbScriptCompiler;
import com.moddingfromamod.datapack.CompileResult;
import com.moddingfromamod.datapack.DatapackForge;
import com.moddingfromamod.datapack.ItemBase;
import com.moddingfromamod.datapack.ItemCatalogue;
import com.moddingfromamod.datapack.ItemRef;
import com.moddingfromamod.datapack.ItemParts;
import com.moddingfromamod.datapack.ItemProperties;
import com.moddingfromamod.datapack.ItemRecipient;
import com.moddingfromamod.datapack.ItemYield;

/**
 * <b>Give a child a ready-made THING, without writing any code.</b>
 *
 * <p>Shaped like {@link ApplyEffectTool} on purpose — that is the pattern with measured evidence
 * behind it. Design and the pre-registered A/B: docs/ITEM_CATALOGUE_PLAN.md. The check that nearly
 * stopped it being built, and which should be re-read before this grows: ITEM_CATALOGUE_KILLCHECK.md.
 *
 * <p><b>Every parameter is required and none is defaulted.</b> This is not caution, it is the
 * single measured lesson of the last catalogue: {@code TRIGGER}'s default was deliberate,
 * documented and defended with good reasoning, and it still built the wrong ability for 3 of 8
 * wishes — because a default cannot tell an ambiguous wish from an explicit one. Every refusal here
 * quotes what to look for in the child's own words, while the wish text is still in front of the
 * agent, which is the only moment the question can actually be answered.
 *
 * <p>Three things it deliberately does NOT do:
 *
 * <ul>
 *   <li><b>It does not guess an effect, a shape or an icon.</b> Each unknown value lists the real
 *       ones. A silently-substituted item is a wrong answer the child has to notice.</li>
 *   <li><b>It does not accept an unknown parameter.</b> Passing {@code DURATION} where the template
 *       wants {@code SECONDS} would otherwise be ignored.</li>
 *   <li><b>It does not report success for a pack it only compiled.</b> Compiling is not giving; the
 *       item exists when the pack is LOADED. That distinction cost the effect catalogue weeks of
 *       green gates over an ability nobody had.</li>
 * </ul>
 */
public final class MakeItemTool implements McpTool {

    /** The id the last call derived, for the undo seam. Per-instance; tools are not shared. */
    private volatile String lastMadeId;

    @Override
    public String lastMadeId() {
        return lastMadeId;
    }


    /**
     * <b>"Did you mean X?" — the one line that keeps a wrong guess inside the catalogue.</b>
     *
     * <p>The refusal already listed every effect and what each does, and that was not enough:
     * measured at opus/low x8, two runs in eight answered that list by leaving for
     * {@code author_datapack} rather than reading it. A refusal arrives after the agent has
     * written a plan around a name it invented, so a list reads as "start again" while a single
     * named candidate reads as "change one word".
     *
     * <p>Deliberately cheap and deliberately silent when unsure: a shared prefix or a containment
     * either way, nothing cleverer. A wrong suggestion is worse than none — it would send a wish
     * confidently to the wrong effect — so anything below that bar returns the empty string and
     * the list below still stands on its own.
     */
    static String nearest(ItemCatalogue catalogue, String guess) {
        String best = null;
        for (ItemCatalogue.Entry e : catalogue.all()) {
            String id = e.id();
            boolean close = guess.startsWith(id.substring(0, Math.min(4, id.length())))
                    || id.startsWith(guess.substring(0, Math.min(4, guess.length())))
                    || guess.contains(id) || id.contains(guess);
            if (close && (best == null || id.length() > best.length())) {
                best = id;
            }
        }
        if (best == null) {
            // by MEANING when the spelling shares nothing — spawn → summon, anger → aggro,
            // random_item → outcome_table (Spark's invented names, an earlier issue's (d) arm). Silent unless
            // confident: ReadyMadeSearch.closest.
            List<String> near = com.moddingfromamod.readymade.ReadyMadeSearch.closest(guess,
                    com.moddingfromamod.readymade.ReadyMadeSources.items(catalogue));
            if (near.isEmpty()) {
                return "";
            }
            if (near.size() == 2) {
                return " Closest: '" + near.get(0) + "' — " + catalogue.get(near.get(0)).childWhen()
                        + " or '" + near.get(1) + "' — " + catalogue.get(near.get(1)).childWhen()
                        + " If one of them is what he asked for, call this again with that effect "
                        + "and change nothing else.";
            }
            best = near.get(0);
        }
        return " Did you mean '" + best + "' — " + catalogue.get(best).childWhen()
                + " If that is what he asked for, call this again with effect=\"" + best
                + "\" and change nothing else.";
    }

    /** How often a placed thing does its effect. Two seconds — see shape_placed.cbs. */
    private static final int PLACED_PERIOD_TICKS = 40;
    /** How far a placed thing reaches. */
    static final double PLACED_RADIUS = 6;

    /** Where a magic block's settings are kept inside {@code params}. */
    static final String BLOCK_KEY = "_block";

    /**
     * Where an item's KIT is kept inside {@code params} — the supplies that are handed
     * over WITH it, so they come back whenever the thing is re-wished, and an edit keeps them.
     * An object ({@code {"items": "…"}}) rather than a string, so a one-effect design's flat
     * parameters being dropped on an effect change does not drop it with them.
     */
    static final String KIT_KEY = "_kit";

    /** The fields {@code block} takes. */
    static final List<String> BLOCK_FIELDS =
            List.of("when", "seconds", "who", "radius", "where", "look", "walk_through");

    /**
     * Move the call's {@code block} object into {@code params._block}, merged key by key over any
     * settings already there, so everything downstream — inherit(), the stored design, a later
     * re-wish — treats it like any other parameter group.
     */
    /**
     * Move the call's {@code kit} into {@code params._kit}, so the stored design, a later
     * re-wish and {@link #inherit} all carry it. {@code "none"} takes it off.
     */
    static JsonObject foldKit(JsonObject args) {
        if (!args.has("kit") || args.get("kit").isJsonNull()) {
            return args;
        }
        JsonObject out = args.deepCopy();
        JsonObject params = out.has("params") && out.get("params").isJsonObject()
                ? out.getAsJsonObject("params") : new JsonObject();
        String said = out.get("kit").isJsonPrimitive() ? out.get("kit").getAsString().trim() : "";
        if (said.isEmpty() || "none".equalsIgnoreCase(said)) {
            params.add(KIT_KEY, com.google.gson.JsonNull.INSTANCE);   // "take it off" (see inherit)
        } else {
            JsonObject kit = new JsonObject();
            kit.addProperty("items", said);
            params.add(KIT_KEY, kit);
        }
        out.add("params", params);
        out.remove("kit");
        return out;
    }

    /**
     * <b>A kit as give lines</b>: {@code "minecraft:melon_slice 8, cocoa_beans 4"} — each
     * an item he can be handed (a vanilla id, or a made item's id or name, through the same
     * {@link ItemRef} a fill's ingredients use) and how many, 1 to 64.
     *
     * @return the give lines, one per kit item, or a refusal starting {@code "!"}
     */
    static String kitLines(String kit, com.moddingfromamod.datapack.RegistryExists registry) {
        if (kit == null || kit.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (String one : kit.split(",")) {
            String t = one.trim();
            if (t.isEmpty()) {
                continue;
            }
            int count = 1;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)\\s+(?:x\\s*)?(\\d+)$")
                    .matcher(t);
            if (m.matches()) {
                t = m.group(1).trim();
                count = Integer.parseInt(m.group(2));
            }
            if (count < 1 || count > 64) {
                return "!kit: give 1 to 64 of each thing, not " + count + " of '" + t + "'";
            }
            ItemRef ref = ItemRef.parse(t);
            String bad = ref.whyUnusable("kit", registry);
            if (bad != null) {
                return "!" + bad;
            }
            if (++n > MAX_KIT) {
                return "!kit: at most " + MAX_KIT + " different things — it is a starter kit, not "
                        + "a shop";
            }
            out.append('\n').append("    ").append(ItemRecipient.command(ItemRecipient.ME,
                    giveArg(ref), count));
        }
        return out.toString();
    }

    /** The shapes whose datapack hands a broken one back ({@code refund}): kit kept apart. */
    static final java.util.Set<String> REFUNDS = java.util.Set.of(ItemCatalogue.MACHINE,
            com.moddingfromamod.datapack.MagicBlock.SHAPE);

    /** How many different things one kit may hold. */
    static final int MAX_KIT = 6;

    static JsonObject foldBlockSettings(JsonObject args) {
        if (!args.has("block") || !args.get("block").isJsonObject()) {
            return args;
        }
        JsonObject out = args.deepCopy();
        JsonObject params = out.has("params") && out.get("params").isJsonObject()
                ? out.getAsJsonObject("params") : new JsonObject();
        JsonObject block = params.has(BLOCK_KEY) && params.get(BLOCK_KEY).isJsonObject()
                ? params.getAsJsonObject(BLOCK_KEY) : new JsonObject();
        JsonObject sent = out.getAsJsonObject("block");
        for (String k : sent.keySet()) {
            block.add(k, sent.get(k));
        }
        // A pull on a magic block reaches exactly as far as the block does — both models set REACH
        // to the block's radius every time in the smoke, and one spent a turn first
        // misspelling it. Said once in `block.radius`, it is not asked for again.
        if (sent.has("radius") && !params.has("REACH") && namesEffect(out, "pull")) {
            params.add("REACH", sent.get("radius"));
        }
        params.add(BLOCK_KEY, block);
        out.add("params", params);
        out.remove("block");
        return out;
    }

    private static boolean namesEffect(JsonObject args, String id) {
        com.google.gson.JsonElement e = args.get("effect");
        if (e == null || e.isJsonNull()) {
            return false;
        }
        if (e.isJsonArray()) {
            for (com.google.gson.JsonElement x : e.getAsJsonArray()) {
                if (x.isJsonPrimitive() && id.equals(x.getAsString())) {
                    return true;
                }
            }
            return false;
        }
        return e.isJsonPrimitive() && id.equals(e.getAsString());
    }
    /** How far in front of the child a held item's effect happens. */
    private static final int HELD_REACH = 5;
    /**
     * Where an effect aimed AHEAD lands on a SHIELD: 1.5 blocks in front of his eyes, not a
     * wand's 5. What he blocked stands right in front of him, and the effect reaches 3.5 blocks
     * round its point &mdash; measured, a shield that "makes mobs bounce away" blocked a zombie 1.8
     * blocks off and never reached it from 5 (OnBlockShapeGameTest.aShieldThatKnocksMobsAway…).
     */
    private static final String BLOCK_REACH = "1.5";

    /** Characters that fit on one line of a sign before the game cuts the word in half. */
    private static final int SIGN_WIDTH = 15;

    /**
     * the id a FRESH make (no {@code start_from}) should write under. Null when {@code minted} is free; the
     * minted id itself when it is free to overwrite (his own item, a shared one, or no child bound); the asker's own
     * fork id when it belongs to another child or the library, the same decision {@code ForkPolicy} makes for an edit.
     */
    static String freshClash(String minted) {
        // Only a wish this worker is BOUND to names an asker. AgentContext's single-live-run fallback would make any
        // unbound caller (a starter regeneration, a direct call) "the child" of whichever wish happens to be live,
        // and fork a library starter into a stranger's copy (Gate B, StarterRegenGameTest, 2026-11-15).
        String worker = com.moddingfromamod.agent.AgentContext.workerId();
        var run = worker.isEmpty() ? null : com.moddingfromamod.marshal.Marshal.global().runForWorker(worker);
        String asker = run == null || run.playerId() == null ? "" : run.playerId().toString();
        var existing = com.moddingfromamod.item.ItemSpecStore.get(minted);
        String owner;
        if (existing != null) {
            owner = existing.ownerId();
        } else {
            // free in the STORE, but another child's make may have found it free a moment ago and not stored
            // it yet. The first to find it free holds it (ItemClaims); a second child then forks, as he would had the
            // first make been stored — before, both wrote one id and both got the second one's design.
            owner = com.moddingfromamod.item.ItemClaims.shared().claim(minted, asker, System.currentTimeMillis());
            if (owner.equals(asker)) {
                return null;
            }
        }
        var decision = com.moddingfromamod.marshal.ForkPolicy.decide(asker, minted, owner,
                com.moddingfromamod.library.WishLibrary.artifactDir("items", minted) != null);
        return decision == com.moddingfromamod.marshal.ForkPolicy.Decision.FORK
                ? com.moddingfromamod.item.ItemLineage.forkId(minted, asker) : minted;
    }

    @Override
    public String name() {
        return "make_item";
    }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder(
                "Give the child a READY-MADE THING — something to hold, to throw, to put down, "
                        + "or simply to have. "
                        + "Faster and far more reliable than writing a datapack yourself: the code "
                        + "already exists and is known to compile, so PREFER THIS over "
                        + "author_datapack whenever one of these fits.\n"
                        // Measured at opus/low, an enchanted real item went to a bare
                        // `give` in run_command 14 times in 16 — no wish row, no provenance, and
                        // one enchantment spelling the 1.21.4 parser refuses.
                        + "\nThat includes a REAL item from the game made special — a vanilla "
                        + "tool, weapon, armour piece or rod with an enchantment, more damage or no "
                        + "wear: build it on that item with base_item and put the enchantment in "
                        + "`properties`. Never hand a wished thing over as a bare `give` in "
                        + "run_command; that one has no place in his wishes, and this one does.\n"
                        + "\nIt has a real icon, a real colour and a real name, and it is live in "
                        + "the world when this returns — no verifying needed.\n"
                        // ── THE FIRST QUESTION IS NOT "WHICH TRIGGER", AND THAT WAS THE BUG.
                        //
                        // `plain` shipped inside a list headed "SHAPE — how they set it off", as
                        // its fifth entry, after four shapes that all fire. Measured at
                        // opus/low x8: every wish reached this tool (40/40) and the plain wishes
                        // chose a firing shape anyway -- syrup 0/8, whipped cream 0/8, cups 2/8.
                        // An agent scanning a list framed as HOW DOES THIS GO OFF has no reason to
                        // pick the entry that says it doesn't; the entry is not an answer to the
                        // question being asked.
                        //
                        // So the BEHAVIOUR question is asked before the TRIGGER question, which is
                        // also the order the two axes actually have: whether anything happens is
                        // decidable from his words alone, and only then is there a trigger to pick.
                        + "\nFIRST, BEFORE ANY OTHER FIELD — DOES ANYTHING HAPPEN WHEN HE USES IT? "
                        + "Read it off his own words, not off what would be more fun. The two "
                        + "answers are different calls:"
                        + "\n  • HE NAMED SOMETHING THAT HAPPENS — it shoots, it burns, it heals, "
                        + "it goes off. Pick the EFFECT, and then the SHAPE that sets it off. Both "
                        + "are below."
                        + "\n  • HE NAMED ONLY A THING — what it is, what it is called, what they "
                        + "come in, how many of them. Nothing in his words goes off. Then "
                        + "`shape: \"plain\"` and NO effect field at all. That is a COMPLETE "
                        + "answer and not a half-finished one, and it refuses an effect on "
                        + "purpose: a plain thing has nothing to set it off, so an effect would be "
                        + "built and never run. Handing him a behaviour he never asked for is the "
                        + "failure on this branch — he gets something that surprises him every "
                        + "time he touches it, having asked for a thing that just IS. "
                        + "\"a bag of flour\", \"a spare battery\"."
                        + "\n\nSHAPE — for the ones that DO something, how he sets it off:"
                        + "\n  • held    he holds it and uses it. \"a magic wand that shoots "
                        + "lightning\". NOT for a potion or a food: those are `on_consume`, so he "
                        + "drinks or eats it and then the magic happens (this example used "
                        + "to be a potion, and a held potion is one he can never drink)."
                        + "\n  • thrown  he throws it; it goes off where it LANDS. "
                        + "\"a bomb i can throw that explodes\", \"a snowball that freezes "
                        + "whatever it hits\""
                        + "\n  • placed  he uses it on the ground to put it down; it then works by "
                        + "itself on whatever comes near, every 2 seconds. A trap, a campfire, a "
                        + "healing totem — anything that just sits there and does its thing."
                        + "\n  • magic_block he uses it on the ground and it becomes a BLOCK he can "
                        + "see, that does something to the world around it by itself: a block that "
                        + "sucks things in (pull), a block that zaps monsters (lightning), a cloud "
                        + "he bounces on (fly), a block that gives a surprise when he BREAKS it "
                        + "(outcome_table), one whose empty surroundings fill up (fill_blocks). "
                        + "Its dials are the `block` object — WHEN it goes off, WHO it reaches, "
                        + "how far, and what it LOOKS like. Breaking it gives the item back, "
                        + "unless it goes off when broken. A NEW BLOCK of his own that does "
                        + "nothing else is magic_block with NO effect and a `block.look` — a block "
                        + "he can put down, not a keepsake in his hand (that is plain)."
                        + "\n  • machine he uses it on the ground and it BECOMES SOMETHING: a body "
                        + "he can see, with a HANDLE on the front he pulls to work it, and a sign "
                        + "with its name on. Breaking it gives the item back. Reach for this "
                        + "whenever the thing itself is what he asked for — \"a slushie machine i "
                        + "can put down\", \"i want a coffee machine\", a vending machine, a "
                        + "money printer. `placed` would give him the behaviour and NOTHING TO "
                        + "LOOK AT, which reads as the wish having failed."
                        + "\n            A MACHINE IS ONE OF TWO EFFECTS, and choosing between them "
                        + "is the first thing to decide. ONE thing in and ONE thing out in a single "
                        + "pull — \"turns wheat into cake\" — is `turn_into`. SEVERAL things in "
                        + "before it makes anything, a size, an add-in, or a chest to fill — one "
                        + "that needs two ingredients, or comes out small or big depending on how "
                        + "much went in — is `load_and_pour` (below). A maker "
                        + "answered with `rich` is a vending machine — he works it and something "
                        + "appears, having put nothing in — which is a different toy from the one "
                        + "he asked for. If what goes in or what comes out is not in the game yet, "
                        + "make_item it FIRST and name that id; that is the whole "
                        + "beans → machine → cup chain, and it is three calls."
                        + "\n            When it TAKES SEVERAL THINGS before it makes one — two "
                        + "or three ingredients, a machine he fills up first, sizes that grow with "
                        + "how much he put in, toppings, or \"into a chest, not my pockets\" — it is "
                        + "`load_and_pour`, not turn_into: INGREDIENTS (up to 3, all needed), EXTRAS "
                        + "(optional add-ins that go in the name, or none), MAX (how many of the "
                        + "first ingredient it holds: 1 = no sizes, 3 or more = Small/Medium/Big), "
                        + "WHERE (hand or chest) and OUTPUT. It says what it takes, what is still "
                        + "missing, and how it works when he sneaks and pulls. WHAT IT MAKES is "
                        + "nearly always something he eats or drinks: make_item THAT first, shape "
                        + "`on_consume` with effect `food`, so the slushie or popcorn it pours is "
                        + "real food and not a thing he can only hold."
                        + "\n  • on_hit  he SWINGS it and the wish happens to what he hit. \"a "
                        + "hammer that freezes whatever i smack\", \"gloves that set things on "
                        + "fire when i punch them\". Reach for this whenever he says HIT, whack, "
                        + "smack, punch, bonk or attack — a weapon wish answered with `held` is a "
                        + "wand he has to right-click, which is not what he asked for and works "
                        + "well enough that nobody notices. The shape comes from what the thing "
                        + "IS, not the verb: anything he swings is on_hit even when he says it "
                        + "shoots or blasts something; something he aims (a wand, a staff) is held."
                        + "\n  • arrow   he SHOOTS it — a bow, crossbow or trident whose arrow "
                        + "makes the wish happen WHERE IT LANDS: explosive arrows, arrows that "
                        + "fling what they hit, a trident that calls lightning. Build it on "
                        + "base_item minecraft:bow, minecraft:crossbow or minecraft:trident; made "
                        + "from scratch it is a bow. The arrows are his own; nothing is thrown."
                        + "\n  • on_shot he SHOOTS it and the wish happens THE MOMENT HE DOES: a "
                        + "beam leaves the bow to the first thing in its way and the wish happens "
                        + "there — a laser, a ray, a blast that is instant. His arrow still flies. "
                        + "The same bases as `arrow`; made from scratch it is a bow. Where it LANDS "
                        + "is `arrow`; the instant he fires is `on_shot`."
                        + "\n  • on_consume he EATS or DRINKS it, and when he has finished the wish "
                        + "happens: a magic cookie, a potion. Build it on the real food or drink "
                        + "(base_item minecraft:cookie, minecraft:potion…) so it is eaten or drunk the "
                        + "way it always is; made from scratch it is a potion when its icon is a cup "
                        + "or bottle and a cookie otherwise. A `held` wish on food would take the "
                        + "eating away."
                        + "\n  • on_block he raises a SHIELD and when it BLOCKS a hit the wish "
                        + "happens: a bubble, mobs knocked away, an arrow bounced back (the `reflect` "
                        + "effect). Built on minecraft:shield (from scratch it is one), so it still "
                        + "blocks the way a shield always does; a `held` wish would stop it blocking."
                        + "\n  • on_throw_land he THROWS a real throwable — a snowball, an egg, an ender "
                        + "pearl, a splash potion, a wind charge — and the wish happens WHERE IT LANDS. "
                        + "Vanilla throws it, once, as itself (from scratch it is a snowball); `thrown` "
                        + "is for a made-up thing that is thrown."
                        + "\n  • on_use_done he USES a real thing that takes a while — blows a goat "
                        + "horn, brushes, looks through a spyglass — and the wish happens when he is "
                        + "DONE. Built on the real item (from scratch it is a goat horn)."
                        + "\n  • on_mine he MINES with it and the wish happens at every block he "
                        + "breaks — a pickaxe that lights the cave as he digs. Build it on the "
                        + "real tool (base_item minecraft:diamond_pickaxe) so it mines at its "
                        + "proper speed."
                        + "\n  • worn_head / worn_chest / worn_legs / worn_feet  he PUTS IT ON "
                        + "and it works for as long as he wears it — boots, a helmet, a "
                        + "chestplate, leggings, a cape. \"boots that let me jump REALLY high\" "
                        + "is worn_feet + jump. Made from scratch it is drawn on him as leather "
                        + "in its colour; built on a real piece (base_item minecraft:golden_boots, "
                        + "minecraft:diamond_helmet, minecraft:elytra for wings that glide) it "
                        + "looks like that piece. A wearable wish answered with `held` is a charm "
                        + "he has to right-click, which is the bug this shape exists for.\n"
                        // Wishes vanilla already grants, generated from VanillaNative.
                        + com.moddingfromamod.datapack.VanillaNative.guidance() + "\n"
                        + "\nICON — by NAME, never a number. Pick the one that IS the thing (a "
                        + "panda, a taco, a guitar) before a shape that merely holds it (orb, "
                        + "food_round). A * icon keeps its own colours (a strawberry stays red); "
                        + "every other one is drawn in his colour, and many have a second part "
                        + "(a belly, windows, icing) in `accent`:" + ItemCatalogue.iconMenu() + "\n"
                        // NAMED HERE AND NOT ONLY IN THE SCHEMA. Tier 3.5 caught this: asked to
                        // choose from the description alone, the model reported "there is no NAME
                        // parameter, so the item cannot even be called '10000 Dollars'" — and it
                        // was right about what it had been shown. A model picks its approach from
                        // the summary and may never read a schema field for something it does not
                        // know it has to supply, which is the same defect build_contraption's
                        // `modules` had.
                        + "\nAND YOU ALSO CHOOSE, every time:"
                        + "\n  • NAME    what he called it, in his words — \"Bloop Slayer Trident\","
                        + " \"10000 Dollars\". It is the name on the item in his hand."
                        + "\n  • COLOUR  #RRGGBB, or a word: " + String.join(", ", COLOURS.keySet())
                        + ". The icons are drawn in grey and tinted, so this really is what he sees."
                        // NAMED HERE AND NOT ONLY IN THE SCHEMA, for the reason the NAME note above
                        // records and then measured at n=16: a model picks its approach from
                        // this summary, and a field it does not know it has is a field it never
                        // supplies. `plain` was documented, exampled and refused-on-misuse and was
                        // still chosen 9 times in 48, because of where it sat.
                        + "\n  • WHAT IT IS  `properties` — static facts that ride on the thing "
                        + "itself rather than something it does: how many fit in a slot, how hard "
                        + "it hits when swung. Set them for the whole wish, or inside one member "
                        + "of `variants` for just that one. This is what makes a set of SIZES three "
                        + "different things instead of one thing under three labels — without it, "
                        + "\"small\" and \"large\" are only words on a label."
                        + "\n  • COUNT   how many to give, 1 to 64. One for a wand or a machine; a"
                        + " handful for something thrown, because throwing the only one you have"
                        + " ends the game.\n"
                        + "\nA SET OF THEM, when he asks for one — `variants`:"
                        + "\n  \"20 flavors of ice cream!!\" is ONE wish, not twenty. "
                        + "Send up to " + MAX_VARIANTS + " {name, colour} pairs in `variants` and he "
                        + "gets all of them from one call, each named and coloured its own way, all "
                        + "doing the same thing. A count of 20 is twenty of the SAME one — that is "
                        + "not what he asked for.\n"
                        + "\nEFFECT — what it does. ONE, or SEVERAL joined with '+' (up to 3):"
                        + "\n  A child's wish often has more than one part, and that is normal. "
                        + "\"a spear that leaves a puddle where it lands\" is also expected to HURT what "
                        + "it hits; \"a sword that sets things on fire\" is also expected to be a "
                        + "sword. Build those in ONE call as hurt+place or hurt+ignite — do not "
                        + "pick the nearer half and leave the rest, and do not go and "
                        + "author_datapack the other half separately."
                        + "\n  With several, group the parameters by effect id: "
                        + "{\"hurt\":{\"HEARTS\":\"8\"},\"ignite\":{\"SECONDS\":\"6\"}}.");
        try {
            // with the item search on, the menu is one line an effect and the rest is a
            // describe_ready_made away; off, every effect's full text, as it always was. One
            // definition of that text either way (ReadyMadeSources.itemDetail).
            boolean slim = com.moddingfromamod.readymade.ReadyMadeConfig.searches(
                    com.moddingfromamod.readymade.ReadyMade.Kind.ITEM);
            for (ItemCatalogue.Entry e : ItemCatalogue.load().all()) {
                if (slim) {
                    sb.append("\n  • ").append(e.id()).append(" — ").append(
                            com.moddingfromamod.readymade.ReadyMade.oneLine(e.summary()));
                } else {
                    sb.append("\n  • ").append(com.moddingfromamod.readymade.ReadyMadeSources.itemDetail(e));
                }
            }
            if (slim) {
                sb.append("\n  describe_ready_made <effect> shows an effect's parameters and what to "
                        + "read off his words for each; find_ready_made searches them by what he wants.");
            }
        } catch (RuntimeException ex) {
            // Never remove the tool silently: an agent that sees no effects simply goes back to
            // authoring, and the experiment reads as a null result rather than a broken fixture.
            sb.append("\n  (the catalogue could not be read: ").append(ex).append(')');
        }
        sb.append("\n\nEVERY parameter is REQUIRED. Nothing is filled in for you, on purpose: a "
                + "value guessed after the wish text is gone is how a wish quietly becomes a "
                + "different wish. If the child did not say, choose from what he DID say and tell "
                + "him what you chose. If nothing here fits, write it with author_datapack.");
        return sb.toString();
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        // ⚠ "REQUIRED (unless start_from)" IS SPELLED OUT ON EVERY ONE OF THESE, and it is not
        // decoration. The requirement cannot live in the schema's `required` array: with
        // `start_from` these are INHERITED from the item being changed, and declaring them required
        // would force a refinement to resend every field it is not changing — which is the bug the
        // note at the end of this method exists to prevent. So the schema says nothing, the runtime
        // enforces it, and until now the only place the rule appeared was this class's own javadoc,
        // which is not what a model reads when it is filling in one field.
        //
        // Measured over ten real item wishes seeded into corpus/real-tool-calls.jsonl: 10 of 14
        // make_item calls FAILED, every wish needed two or more calls to make one item, and the
        // single commonest cause was missing fields -- `icon` and `colour`, together, 4 times out
        // of 4. The agent was learning a requirement by being refused, once per wish, at a turn
        // apiece. That is an earlier issue's "remove the round trips" with a name on it, and level 1 on the
        // self-improvement ladder: an input to generation rather than a check after the fact.
        String req = "REQUIRED (unless you pass start_from, which inherits it). ";
        Schemas.prop(s, "name", "string",
                req + "What it is CALLED, in the child's words, e.g. \"Bloop Slayer Trident\".");
        Schemas.prop(s, "icon", "string",
                req + "Which icon, by name — one of:" + ItemCatalogue.iconMenu()
                        + "\nPick by what the thing IS: a panda is panda, a trident is a spear, "
                        + "money is a coin. A * icon keeps its own colours.");
        Schemas.prop(s, "accent", "string",
                "Optional. The SECOND colour, for an icon with a second part (a belly, windows, "
                        + "a ribbon, icing), as #RRGGBB or a colour word — \"a red car with yellow "
                        + "windows\" is colour red, accent yellow. Leave it out and the second part "
                        + "is a lighter shade of `colour`.");
        Schemas.prop(s, "icon_wanted", "string",
                "Optional. When no icon is exactly the thing, the one word you WISH there were "
                        + "(\"laser_blaster\", \"mermaid\") — still choose the closest real icon in "
                        + "`icon`. This is how the next icons get drawn.");
        Schemas.prop(s, "colour", "string",
                req + "Its colour, as #RRGGBB or a plain colour word like blue. The icons are "
                        + "drawn in grey and tinted, so this really is what the child will see.");
        Schemas.prop(s, "shape", "string",
                // DERIVED from the catalogue's own list: this line used to be typed, and the
                // arrow and on_mine shapes shipped absent from it — measured, 0 of 32 routing runs
                // chose either, while the description a few thousand characters away named both.
                req + String.join(", ", ItemCatalogue.SHAPES) + " — how the child sets it off. "
                        + "arrow: he SHOOTS it (a bow, crossbow or trident whose arrows make the wish "
                        + "happen where they land); on_shot: he SHOOTS it and the wish happens the "
                        + "moment he does, where a beam from the bow stops (a laser); on_consume: he "
                        + "EATS or DRINKS it and the wish happens when he finishes; on_block: his "
                        + "SHIELD blocks a hit and the wish happens then; on_throw_land: a real "
                        + "throwable lands and the wish happens there; on_use_done: a horn, brush or "
                        + "spyglass is used and the wish happens when he is done; on_mine: he "
                        + "MINES with it (the wish happens at "
                        + "every block he breaks); worn_*: he puts it on and it works while he wears "
                        + "it; `plain` does nothing. See this tool's description.");
        Schemas.prop(s, "effect", "string",
                "Which ready-made effect — or SEVERAL, separated by '+', up to 3: "
                        + "\"hurt+ignite\" is a sword that burns what it cuts. Use more than one "
                        + "whenever the child's wish has more than one part; that is the normal "
                        + "case, not the exotic one. "
                        // Measured — "bounces arrows back" went on_block + hurt 4 in 8.
                        + "Arrows bouncing BACK off his shield is `reflect` (on a shield, on_block) — "
                        + "not `hurt`, which hurts whatever is near him and sends nothing back. "
                        // measured — "a pearl that … home" went to `teleport` 6 in 8, reaching
                        // for a destination (TO: home) that `teleport` does not have.
                        + "Taking him HOME (his bed, his house, back to spawn) is `teleport_home` — "
                        + "`teleport` only goes where he points and has no destination. "
                        // And a wish the real item already grants is no effect at all.
                        + "Animals following him, a throw that comes back, water freezing under his "
                        + "feet: those are NOT effects — the real item does them (a golden carrot; a "
                        + "loyalty trident; frost walker boots), see ALREADY IN THE GAME. "
                        // Measured, "turns dirt into grass" went to convert or to a guess —
                        // and made the two one effect, so there is nothing left to choose.
                        + "Anything that TURNS one thing INTO another — a block he points at, the "
                        + "blocks where it lands, or a thing in his bag — is turn_into, with FROM "
                        + "and TO; the shape decides which (a machine takes from his inventory, a "
                        + "thrown thing changes blocks, a held one changes the block he points at "
                        + "if it matches and otherwise works on his inventory).");
        // `req` too, and it was the one field an earlier issue's pass missed: measured over an earlier pass
        // (P1), "make_item needs [count]" was refused in 106 of 191 Opus grants and 35 of
        // 62 Muse grants — a retry turn in more than half of every item wish.
        Schemas.prop(s, "count", "integer",
                req + "How many to give, 1 to 64. One for a wand or a machine; a handful for something "
                        + "thrown, because throwing the only one you have ends the game.");
        Schemas.prop(s, "params", "object",
                "That effect's own parameters, e.g. {\"MOB\":\"minecraft:zombie\",\"COUNT\":\"3\"}. "
                        + "ALL of them are required — there are no defaults. A parameter name that "
                        + "does not exist is an ERROR, not ignored.\n"
                        + "For SEVERAL effects, group them by effect id: "
                        + "{\"hurt\":{\"HEARTS\":\"8\"},\"ignite\":{\"SECONDS\":\"6\"}}. "
                        + "Grouping is required there because two effects can both want SECONDS, "
                        + "and a flat list would silently hand one effect's value to the other.");
        Schemas.prop(s, "variants", "array",
                "OPTIONAL — for a wish that asks for SEVERAL of a thing that differ only in what "
                        + "they are called and what colour they are — a set of flavours, a set of "
                        + "sizes, one of each colour. "
                        + "A list of up to " + MAX_VARIANTS + " objects, each {\"name\":..., "
                        + "\"colour\":...} — e.g. [{\"name\":\"Vanilla Ice Cream\","
                        + "\"colour\":\"white\"},{\"name\":\"Chocolate Ice Cream\","
                        + "\"colour\":\"brown\"}].\n"
                        + "They all DO the same thing, so this is ONE wish and one call — not one "
                        + "call each. Use it whenever he asks for a set; making twenty separate "
                        + "items is twenty times the work and fills his wish menu with twenty rows "
                        + "for one wish. If he asked for one thing, leave this out.");
        Schemas.prop(s, "properties", "object",
                "OPTIONAL — what the thing IS, as opposed to what it DOES. Static facts that ride "
                        + "on the item itself, so they hold whatever shape it has and whether or "
                        + "not it has an effect at all:\n  "
                        + String.join("\n  ", ItemProperties.catalogue()) + "\n"
                        + "Send it here for the whole wish, or inside a member of `variants` for "
                        + "just that one — a member's own value wins, key by key. That is how "
                        + "\"small, medium and LARGE\" becomes three different things instead of "
                        + "one thing in three colours.\n"
                        + "If what he asked for is something that HAPPENS, it is an effect and not "
                        + "a property.");
        Schemas.prop(s, "block", "object",
                "ONLY with shape magic_block — its dials. Every field is optional:\n"
                        + "  when: every (on its own, on a beat — the default), near (when one of "
                        + "`who` comes within radius), stepped_on (when something stands or LANDS "
                        + "on it — a bounce pad, a trap you step on), broken (once, when he breaks "
                        + "it: a lucky block).\n"
                        + "  seconds: the beat for every, or the rest between goes for near and "
                        + "stepped_on; 0.25 to 60 (default 2, or 1).\n"
                        + "  who: everyone, players, mobs, hostile (monsters only), animals or items "
                        + "(dropped things). Anything that HURTS reaches hostile unless he said "
                        + "otherwise, so his own animals are safe.\n"
                        + "  radius: how far it reaches, 1 to 16 blocks (default 6).\n"
                        + "  where: here (the effect happens at the block, to everything near — "
                        + "pull, fill, heal) or on_each (it happens AT each one it finds — a "
                        + "lightning bolt on every monster).\n"
                        + "  look: the block it is made of, a real block id like minecraft:gold_block "
                        + "or minecraft:white_wool (default: a coloured block). Never a chest-like "
                        + "block or an unbreakable one.\n"
                        + "  walk_through: true to draw it as a block you can walk straight through "
                        + "(a ghost block). Cannot be combined with when: broken.");
        Schemas.prop(s, "spare_players", "boolean",
                "OPTIONAL — true keeps the power off every PLAYER: it lands only on creatures, and a "
                        + "lightning bolt holds off while a player stands where it would strike. The Wish "
                        + "Builder sets it on a bow, a trident or a snowball; send it when he says the "
                        + "power is only for monsters.");
        Schemas.prop(s, "look", "boolean",
                "OPTIONAL — true draws a REAL item (base_item) as the icon you name, keeping everything the real item "
                        + "does: a cookie he eats that looks like a cupcake, a leather helmet he wears that is a crown "
                        + "in his hand. The Wish Builder sets it when a look is chosen.");
        Schemas.prop(s, "tint", "boolean",
                "OPTIONAL — true draws a REAL item (base_item) in `colour`: a diamond pickaxe with a blue "
                        + "head, a blue bow. The Wish Builder sets it for its colour schemes; it only works "
                        + "on the tools, weapons and other items it offers, and changes nothing elsewhere.");
        Schemas.prop(s, "base_item", "string",
                "OPTIONAL — the item this is BUILT ON. Leave it out and the thing is made from "
                        + "scratch out of the icon library, which is what nearly every wish wants.\n"
                        + "Set it to a full item id — minecraft:golden_axe, minecraft:diamond_sword "
                        + "— when the wish is about a REAL item from the game or from a mod: "
                        // This example used to be a bow that shoots lightning, which pulled
                        // the agent to `held` — the laser bow, whose wish cancelled the draw.
                        + "\"make my sword never wear out\", \"my pickaxe, but it lights the cave\". "
                        + "Everything here still applies to it: the name he gives it, what sets it "
                        + "off, its properties, what it is made of. The icon and colour do not, "
                        + "because a real item has its own artwork.\n"
                        + com.moddingfromamod.datapack.ItemTriggers.guidance(ItemCatalogue.SHAPES));
        Schemas.prop(s, "recipient", "string",
                "OPTIONAL — who ends up holding it. Leave it out and he gets it himself, which is "
                        + "almost always right. Set it to a full entity id — minecraft:piglin_brute "
                        + "— when the wish is that a CREATURE gets the thing: \"the dragon should "
                        + "have my crown\". The nearest one of that kind within 24 blocks takes "
                        + "it, so conjure or find the creature FIRST.");
        Schemas.prop(s, "at", "string", com.moddingfromamod.place.At.describe("the made thing",
                "its front (a building) or in front of him (me)",
                "It is put THERE instead of in his hand: a thing he puts down (placed, machine, "
                        + "magic_block) is put down and works as if he had placed it; anything else "
                        + "goes into a chest or barrel at the spot, or lies there for him to pick up. "
                        + "\"A slushie machine in my kitchen\" → at:\"<his house>:inside\". Leave it "
                        + "out for a thing he holds or wears."));
        Schemas.prop(s, "kit", "string",
                "OPTIONAL — the SUPPLIES that come WITH it, as \"item count, item count\": "
                        + "\"minecraft:melon 4, minecraft:cocoa_beans 8\" for a smoothie bar, a made "
                        + "item's id for one of his own. Use it whenever a wish hands him things to "
                        + "USE this thing with — ingredients for a machine, ammo, seeds, fuel. They "
                        + "become this thing's kit: handed over right after it, and handed over AGAIN "
                        + "whenever he re-wishes it, on its own or as a part of a bigger wish. NEVER a "
                        + "raw `give` in run_command for those: a give beside the thing belongs to "
                        + "nothing, so re-wishing the thing comes back without them. Up to "
                        + MAX_KIT + " things, 1 to 64 of each. A machine broken and handed back "
                        + "comes back WITHOUT its kit. \"none\" takes a kit off. A filled machine "
                        + "(effect fill) already gets its own starter kit; this adds to it.");
        Schemas.prop(s, "parts", "array",
                "OPTIONAL — what the thing is ASSEMBLED FROM, when the parts are the wish: \"a milkshake with "
                        + "banana and honey in it\" is [\"banana\",\"honey\"]. Up to " + ItemParts.MAX + " short things, in his own words. "
                        + "They are written on the item where he reads them, and they come back out "
                        + "of inspect_item so a later wish can change one. Use it instead of "
                        + "stuffing them into the name.");
        Schemas.prop(s, "yield", "object",
                "OPTIONAL — what USING it leaves behind, when the thing turns INTO something. "
                        + "\"a big chocolate bar that snaps into 6 squares\": the bar leaves "
                        + "his bag and 6 squares arrive. {\"name\":\"Pizza Slice\",\"icon\":\"food_round\","
                        + "\"colour\":\"orange\",\"count\":8} — all four are required, because "
                        + "something he cannot recognise arriving as the thing he had disappears is "
                        + "indistinguishable from the wish failing.\n"
                        + "This is the ONE thing here that takes something away from him, so it is "
                        + "only for a wish that actually says so. It needs a moment of use to hang "
                        + "off, so it works with shape " + String.join(" and ", ItemYield.SHAPES)
                        + " only, and not together with `variants`.");
        Schemas.prop(s, "start_from", "string",
                "OPTIONAL — the id or name of an item he ALREADY HAS, whenever this wish builds on "
                        + "that thing. Send ONLY the fields that differ; everything else is kept. "
                        + "Find the id with list_items (it also says which one he was just working "
                        + "on) or inspect_item, or just give the name he uses. WHICH OF THE TWO "
                        + "THINGS THIS DOES IS DECIDED BY `as_new`, so read that too — on its own, "
                        + "start_from CHANGES the thing he already has.");
        Schemas.prop(s, "only_looks_like", "boolean",
                "Pass TRUE only when he wants a thing that LOOKS like a real bow, pickaxe, trident, "
                + "horn… and must NOT work like one. Otherwise a name like that is refused until it "
                + "is built on the real item (base_item), because a made one cannot be drawn, mine, "
                + "be thrown or be blown.");
        Schemas.prop(s, "from_scratch", "boolean",
                "Pass TRUE only when his wish sounds like it points back at something he already "
                + "has ('and one that...', 'another one but...') and you have CHECKED that it does "
                + "not — that he really is asking for something unrelated. Without it a wish like "
                + "that is refused rather than guessed at, because its sentence carries only the "
                + "DIFFERENCE and everything else would be invented here. Never pass it to get "
                + "past the refusal; call list_items and look first.");
        Schemas.prop(s, "as_new", "boolean",
                // ⚠ NOT the fixture's own words. The obvious example here is the wish this was
                // built for, and quoting it would hand the agent the answer to the very thing
                // wants measured — LessonsDoNotCoachTheFixturesTest caught exactly that.
                // Same error as an earlier issue's option 4, from the other side.
                "Pass TRUE with start_from when he asked for ANOTHER one rather than a change: "
                        + "\"and one that freezes them instead\", \"make another one but blue\", "
                        + "\"a second one, but bigger\". You inherit the whole design and "
                        + "he ends up with BOTH — give the new one its own name. Leave it off for a "
                        + "refinement (\"make it green\", \"make it hurt more\", \"can it freeze "
                        + "things as well\"): the thing he already has changes and he still has one. "
                        + "SAY WHICH — it is never guessed from your wording, and the two outcomes "
                        + "are a word apart in this call and a world apart for him: getting it "
                        + "wrong either leaves him an item he did not ask for, or destroys the one "
                        + "he loved. ⚠ A REFUSED EDIT IS NOT A REASON TO MAKE A NEW ONE: when a "
                        + "start_from change is refused (\"a fishing rod cannot be thrown\"), tell "
                        + "him what cannot change — making a fresh item instead leaves him two, and "
                        + "for the rest of that wish a make_item without start_from is refused "
                        + "unless it passes as_new: true, which you send only when he really asked "
                        + "for a separate new item as well.");
        // NOTHING IS REQUIRED *HERE*, and that is not a relaxation — it is the only way to express
        // "required unless start_from" in a schema that has no conditionals.
        //
        // Marking all seven required made the model restate all seven on every edit, and it was
        // measured doing exactly that: of ~40 start_from calls across eight ladder cells, all but a
        // handful also sent name, icon, colour, shape, effect, count and params. On the rung that
        // asks for "a whole bunch of them" it dutifully restated the colour — from memory, as the
        // ORIGINAL purple — silently undoing the green it had applied one rung earlier. Four of six
        // cells reverted that way.
        //
        // The rule itself is untouched: with no start_from, every field is still required, and the
        // check below refuses the call with the same message and the same reasoning (— a
        // default cannot tell an ambiguous wish from an explicit one). It is enforced where the
        // condition can actually be evaluated.
        return s;
    }

    /**
     * <b>: a refused edit never becomes a new item.</b> The grappling hook: an edit
     * ({@code start_from}) was refused twice by the base-item rule, then a call with no
     * {@code start_from} minted a second hook beside the first. So a refused edit says so in words
     * the agent cannot read as "try something else", and for the rest of the wish a make_item from
     * nothing is refused until it says {@code as_new: true} (NoDuplicate).
     */
    @Override
    public ToolResult call(JsonObject args) {
        com.moddingfromamod.marshal.WishRun run = boundRun();
        String startFrom = str(args, "start_from");
        boolean editing = startFrom != null && !startFrom.isBlank();
        boolean asNew = args.has("as_new") && args.get("as_new").isJsonPrimitive()
                && args.get("as_new").getAsBoolean();
        if (run != null) {
            String fresh = com.moddingfromamod.marshal.NoDuplicate.freshAfterRefusedEdit(
                    run.refusedEdit(), editing, asNew);
            if (fresh != null) {
                lastMadeId = null;
                return ToolResult.error(fresh);
            }
        }
        ToolResult result = grant(args);
        if (run != null && editing && !asNew) {
            if (result.isError()) {
                run.editRefused(startFrom.trim());
                return ToolResult.error(result.text()
                        + com.moddingfromamod.marshal.NoDuplicate.refusedEditSuffix(startFrom.trim()));
            }
            run.editLanded(startFrom.trim());
        }
        return result;
    }

    private ToolResult grant(JsonObject args) {
        // A MAGIC BLOCK'S SETTINGS ride in params under `_block`, so a re-wish inherits
        // them key by key exactly as it inherits an effect's parameters, and the stored design
        // needs no new field. Only a call that SENT `block` may be refused for it below.
        boolean sentBlock = args.has("block") && !args.get("block").isJsonNull();
        args = foldBlockSettings(args);
        args = foldKit(args);
        // AN EDIT, IF HE ALREADY HAS ONE. Everything not mentioned is inherited from the thing he
        // is holding — which is a different claim from "defaulted", and a true one. `make_item`
        // requires every parameter precisely because a DEFAULT cannot tell an ambiguous wish from
        // an explicit one; a value taken from the item he already owns can.
        // "AND ONE THAT…" — REFUSE TO INVENT WHAT HE WAS POINTING AT (item 3).
        //
        // Before anything else, because the damage is done by the time a spec is built. A wish
        // whose whole content is a DELTA carries no shape, no icon, no colour; minting from
        // scratch fills all of that in from nowhere and looks like a successful grant at every
        // tier we run. Fires only when the wish refers back AND nothing is being inherited AND
        // there is something he could have meant — see SecondSentence for why an empty store is
        // deliberately left alone.
        String startFrom = str(args, "start_from");
        boolean fromScratch = args.has("from_scratch") && args.get("from_scratch").getAsBoolean();
        // What THIS call said about the count, before inherit() fills it in from the base — the one
        // way to tell "a whole pile of them" from an edit that never mentioned how many.
        Integer askedCount = null;
        try {
            askedCount = args.has("count") ? args.get("count").getAsInt() : null;
        } catch (RuntimeException notANumber) {
            // refused by the ordinary validation below; nothing to decide here
        }
        // THE WISH TEXT MUST COME FROM A RUN THIS WORKER IS BOUND TO, never from the ambient
        // context — and that is a correctness rule, not a test accommodation.
        //
        // The obvious source is ProvenanceContext.currentIntent(), and it is wrong here.
        // AgentContext.currentRun() "falls back to the single live run when nothing is bound and
        // there is exactly one", which is a sensible default for reading a note and a bad one for
        // deciding a REFUSAL: measured on the first Gate B run of this check, 11 unrelated tests
        // went red because the ambient run was a NEIGHBOUR's, so make_item refused perfectly good
        // calls quoting wishes like "make gt_affinity_same taller" that had nothing to do with
        // them. AgentContext's own javadoc names the principle it was falling foul of — "guessing
        // is precisely the silent mis-attribution this class exists to stop" — it simply guarded
        // the two-runs case and not this one.
        //
        // Unbound means no refusal. In production a granting worker is always bound, so nothing is
        // lost; everywhere else the check is inert rather than firing on somebody else's sentence.
        String wishText = boundWishText();
        if (com.moddingfromamod.item.SecondSentence.of(
                com.moddingfromamod.item.SecondSentence.looksLikeASibling(wishText),
                startFrom != null && !startFrom.isBlank(),
                fromScratch,
                com.moddingfromamod.item.ItemSpecStore.count())
                    == com.moddingfromamod.item.SecondSentence.Verdict.ASK) {
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (var spec : com.moddingfromamod.item.ItemSpecStore.all()) {
                ids.add(spec.id());
            }
            return ToolResult.error(
                    com.moddingfromamod.item.SecondSentence.refusal(wishText, ids));
        }
        // A MADE ITEM NAMED LIKE A REAL ONE THAT DOES SOMETHING (Q1): "Egg Bow" with no
        // base_item is a carrier that cannot be drawn — 6 of Muse's 14 shortfalls in an earlier pass.
        // Before anything is stored, so a refused call leaves nothing behind.
        boolean onlyLooksLike = args.has("only_looks_like") && args.get("only_looks_like").getAsBoolean();
        var realItem = com.moddingfromamod.item.RealItemHint.of(str(args, "name"), str(args, "base_item"),
                startFrom != null && !startFrom.isBlank(), onlyLooksLike, str(args, "shape"));
        if (realItem.isPresent()) {
            return ToolResult.error(com.moddingfromamod.item.RealItemHint.refusal(str(args, "name"),
                    realItem.get()));
        }
        com.moddingfromamod.item.ItemSpecStore.Spec base = null;
        String forkId = null;
        String forkNote = "";
        if (startFrom != null && !startFrom.isBlank()) {
            base = com.moddingfromamod.item.ItemSpecStore.find(startFrom);
            if (base == null) {
                // ── AN ITEM THIS ENGINE DID NOT MINT ───────────────────────────────
                //
                // "make my diamond sword unbreakable" names something that was never minted
                // here, so the store misses — and the old refusal sent the agent to base_item,
                // which BUILDS A COPY. The child ends the wish holding two swords, one of them
                // a stranger wearing the name of the other, and a copy of a mod's item keeps
                // what components express and silently drops whatever that mod does in Java.
                //
                // So: if it is a real item and somebody is carrying it, change THAT stack and
                // make nothing. The spike measured both halves on a real create:wrench before
                // this was written — the write survives a save and the item keeps its identity.
                var srv = com.moddingfromamod.agent.AgentBridge.serverOrNull();
                boolean resolves = srv != null
                        && com.moddingfromamod.item.ForeignItemEdits.resolves(srv, startFrom);
                boolean carried = resolves
                        && com.moddingfromamod.item.ForeignItemEdits
                                .findCarried(srv, startFrom) != null;
                switch (com.moddingfromamod.item.ForeignItemEdit.decide(false, resolves, carried)) {
                    case EDIT_IN_PLACE -> {
                        java.util.List<String> changed =
                                com.moddingfromamod.item.ForeignItemEdits.applyOnServer(
                                        srv, startFrom, str(args, "name"),
                                        args.has("properties") && args.get("properties").isJsonObject()
                                                ? args.getAsJsonObject("properties") : null);
                        if (changed.isEmpty()) {
                            return ToolResult.error("I found his '" + startFrom + "' but there was "
                                    + "nothing on this call an edit can change. Send `name`, or "
                                    + "`properties` with " + com.moddingfromamod.datapack
                                            .ItemProperties.keys() + ". "
                                    + com.moddingfromamod.item.ForeignItemEdit.REACH);
                        }
                        return ToolResult.ok("Changed his own '" + startFrom + "' — "
                                + String.join(", ", changed) + ". Nothing new was made, so he still "
                                + "has exactly one. " + com.moddingfromamod.item.ForeignItemEdit.REACH);
                    }
                    case REFORGE -> {
                        // Fall through to the normal minting path, but the agent is told plainly
                        // that a copy is what it is getting — a silent copy is the whole bug.
                        return ToolResult.error(
                                com.moddingfromamod.item.ForeignItemEdit.reforgeNotice(startFrom));
                    }
                    default -> {
                        return ToolResult.error(
                                com.moddingfromamod.item.ForeignItemEdit.refusal(startFrom));
                    }
                }
            }
            // ── ANOTHER CHILD'S DESIGN IS FORKED, NEVER EDITED. ItemSpecStore.put
            // keeps the owner's stamp and replaces the design, so "make it green" aimed at his
            // brother's wand recoloured the brother's wand (ChildForkGameTest, red). ForkPolicy
            // decides, exactly as it does for modify_mob and for buildings; this carries it out.
            String asker = com.moddingfromamod.agent.AgentContext.currentPlayerId();
            var fork = com.moddingfromamod.marshal.ForkPolicy.decide(asker, base.id(),
                    base.ownerId(),
                    com.moddingfromamod.library.WishLibrary.artifactDir("items", base.id()) != null);
            if (fork == com.moddingfromamod.marshal.ForkPolicy.Decision.REFUSE) {
                return ToolResult.error("'" + base.id() + "' is not this child's to change, and "
                        + "with no child bound to this wish there is nobody to make a copy for. "
                        + "Nothing was made.");
            }
            if (fork == com.moddingfromamod.marshal.ForkPolicy.Decision.FORK) {
                // An item's fork id is its datapack's namespace, so it cannot carry '~'.
                String key = com.moddingfromamod.item.ItemLineage.forkId(base.id(), asker);
                var his = com.moddingfromamod.item.ItemSpecStore.get(key);
                if (his != null) {
                    base = his;            // his second refinement edits HIS copy
                } else {
                    forkId = key;
                    forkNote = "'" + base.id() + "' is not this child's — it belongs to another "
                            + "child or to the shared library — so this change made HIS OWN copy, '"
                            + key + "', and the original is untouched. Use '" + key + "' next time.\n";
                }
            }
            args = inherit(args, base);
        }
        args = canonicalParams(args);
        String name = str(args, "name");
        String iconName = str(args, "icon");
        String colour = str(args, "colour");
        String shape = str(args, "shape");
        String effect = str(args, "effect");

        List<String> absent = missingFields(args);
        if (!absent.isEmpty()) {
            // Lessons ride out on the refusal: this is the moment a retry is certain, and for a
            // while it was the ONE path by which an item lesson could never arrive (see
            // LessonsReachTheAgentTest — every kind needs more than one way in).
            // Measured — frost walker boots came back 3 in 8 as worn_feet with no effect.
            // When a real item already does the whole wish, the answer to "which effect" is none,
            // and the shape that says so is plain; say it where the retry is decided.
            String plainHint = absent.contains("effect") && str(args, "base_item") != null
                    ? " If the real item already does the whole wish by itself (an enchantment, a golden "
                    + "carrot, a loyalty trident), there is no effect: use shape plain."
                    : "";
            return ToolResult.error("make_item needs " + absent + "." + plainHint + " Nothing is filled in for you: "
                    + "take each one from what the child actually said, while you can still see it."
                    + " (If this is a CHANGE to something he already has, pass start_from instead "
                    + "and send only the fields that change — then the rest come from the thing "
                    + "itself rather than from your memory of it.)"
                    + com.moddingfromamod.wish.WishLessons.appendTo("item"));
        }

        // A THING NAMED AS A MACHINE, MADE AS A THING HE HOLDS. Measured: "a popcorn
        // machine with a bag or a big tub" came back as a plain item called Popcorn Machine and a
        // stack of plain tubs, "just for show" in the agent's own words, because nothing on the
        // path it took ever mentioned the machine shape. Asked ONCE per name: the same call again
        // goes through, so a real "time machine" wand is never blocked, only questioned.
        String machineWord = machineWord(name);
        if (machineWord != null && startFrom == null && shape != null
                && !ItemCatalogue.MACHINE.equals(shape) && ASKED_MACHINE.add(name.toLowerCase(Locale.ROOT))) {
            return ToolResult.error("'" + name + "' is a " + machineWord + " — is it the MACHINE he "
                    + "asked for, something he puts down and works? Then use shape `machine`, with "
                    + "effect `turn_into` (one thing in, one thing out) or `load_and_pour` (several "
                    + "things in, sizes like a bag and a tub, add-ins, or a chest to fill). If he "
                    + "named nothing to put in, choose the obvious real ingredient and tell him. What "
                    + "it makes is usually food: make that first as on_consume + food. If "
                    + "this really is a thing he HOLDS, send the same call again and it is made."
                    + com.moddingfromamod.wish.WishLessons.appendTo("item"));
        }

        ItemCatalogue catalogue;
        try {
            catalogue = ItemCatalogue.load();
        } catch (RuntimeException e) {
            return ToolResult.error("the item catalogue could not be read: " + e.getMessage());
        }

        // ⚠ `effect` IS NULL FOR THE `plain` SHAPE, WHICH IS THE WHOLE POINT OF THAT SHAPE.
        // missingFields already exempts it, and then this split threw a NullPointerException, so
        // every plain wish an agent actually made died here. It was measured selecting `plain`
        // 16 times out of 16 against a STUB and had never once been through this method: the
        // matrix loops EFFECT_SHAPES by construction, and every other test calls gives() directly.
        // Found by Gate C -- a real client, driving the real tool, for the first time.
        List<String> effectIds = new ArrayList<>();
        for (String part : (effect == null ? "" : effect).split("\\+")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) {
                // CANONICALISE HERE, at the edge, and nowhere else. `anger` and `aggro` are the
                // same effect from this line on -- so the datapack, the id derived from it, the
                // stored spec and inspect_item all say `aggro`, and the next wish can refine it.
                // An alias resolved at USE instead would work perfectly and leave the child with
                // an item whose effect the catalogue has never heard of.
                String canonical = catalogue.canonical(id);
                effectIds.add(canonical == null ? id : canonical);
            }
        }
        // A MACHINE THAT VENDS, WHEN HE ASKED FOR ONE THAT MAKES (smoke, 2026-09-28). Two of
        // five Opus grants reached for `give` on the machine shape — an alias of `rich` — and got a
        // machine that hands over something (cookies) with nothing put in, or gave up on the
        // machine and handed over a pile of the product. `rich` IS right for a prize or a vending
        // machine; for one that makes a food or a drink it is the wrong toy. Asked ONCE per name, so
        // a real vending machine goes through on the second call. Only inside a WISH (a bound run),
        // like SecondSentence's check: a direct call — the item matrix, a replay — is not a child
        // asking for anything, so there is nobody for the question to be about.
        //
        // WIDENED after the rerun: the same wish then reached for `status` (a machine that gives a
        // power when pulled), which no rule named. So the question is asked of ANY machine whose
        // effects do not take something in — everything but load_and_pour and turn_into (
        // convert's successor, which on a machine always takes from his inventory).
        if (ItemCatalogue.MACHINE.equals(shape) && startFrom == null && !effectIds.isEmpty()
                && !effectIds.contains("load_and_pour")
                && !effectIds.contains(com.moddingfromamod.datapack.TurnInto.EFFECT)
                && boundWishText() != null && ASKED_VENDING.add(name.toLowerCase(Locale.ROOT))) {
            return ToolResult.error("`" + (effect == null ? "" : effect.trim()) + "` on a "
                    + "machine happens when he pulls the handle with NOTHING put in — "
                    + (effectIds.contains("rich") ? "a VENDING machine" : "a button, not a machine that makes anything")
                    + ". If he asked for a machine that MAKES something — a food, a "
                    + "drink, a snack — that is `load_and_pour`: he puts the ingredients in, pulls, "
                    + "and it pours. He does not have to name the ingredients: choose the obvious "
                    + "real ones and tell him (it comes with enough to try it). SIZES he asks for — "
                    + "a small one and a big one, a little serving and a large one — are "
                    + "load_and_pour's sizes (more of the first ingredient, a bigger one), not "
                    + "variants. Make what comes OUT first, as on_consume + food, then this machine "
                    + "with OUTPUT set to it. If it really is a prize or vending machine, send the "
                    + "same call again and it is made."
                    + com.moddingfromamod.wish.WishLessons.appendTo("item"));
        }
        for (String id : effectIds) {
            if (catalogue.get(id) == null) {
                // SAY WHAT EACH ONE DOES, not just what it is called. Measured on a real wish:
                // asked for a weapon that would put something in the child's bag, the agent
                // invented `give`, was handed a bare list of seventeen ids, did not recognise
                // `rich` as the one that means exactly that, and shipped HALF the wish — with a
                // closing message describing the half it had dropped. It had the answer in front
                // of it and could not see it, because an id is not a description.
                //
                // The line beside each id is the one the child is told when the item is handed
                // over, so it is already written in terms of what HAPPENS rather than of what the
                // catalogue calls it. Nothing here is specific to any wish.
                return ToolResult.error("there is no ready-made effect called '" + id
                        + "'." + nearest(catalogue, id)
                        + " Here is every one there is, and what each DOES:\n  "
                        + catalogue.all().stream()
                        .map(e -> e.id() + " — " + e.childWhen())
                        .collect(java.util.stream.Collectors.joining("\n  "))
                        + "\nOne of these is very often the thing you just tried to invent a name "
                        + "for. If none of them truly fits, write it yourself with author_datapack "
                        + "rather than picking the nearest one — a thing that ALMOST does what he "
                        + "asked is the answer he will notice. What you must NOT do is drop that "
                        + "half of the wish and grant the rest: half a wish reported as done is "
                        + "worse than a refusal, because he finds out by trying it."
                        // Measured — a carrot that makes animals follow was answered 6 in 8
                        // with an invented effect ("attract", "tempt", "follow"), when a golden
                        // carrot already does it. The retry is where that has to be said.
                        + "\nAND SOME WISHES ARE NO EFFECT AT ALL, because the real item already does "
                        + "them:" + com.moddingfromamod.datapack.VanillaNative.guidance());
            }
        }
        if (effectIds.size() > ItemCatalogue.MAX_EFFECTS) {
            return ToolResult.error("an item may combine at most " + ItemCatalogue.MAX_EFFECTS
                    + " effects, and you asked for " + effectIds.size() + ". Past three nobody can "
                    + "tell what it did, least of all the child.");
        }
        if (new java.util.LinkedHashSet<>(effectIds).size() != effectIds.size()) {
            return ToolResult.error("the same effect twice does nothing twice: " + effectIds);
        }
        ItemCatalogue.Entry entry = effectIds.isEmpty() ? null : catalogue.get(effectIds.get(0));
        if (com.moddingfromamod.datapack.WornShape.isWorn(shape)
                && !com.moddingfromamod.compat.Wearables.SUPPORTED) {
            // LOUD, never degraded into a held charm -- that degradation IS.
            return ToolResult.error("'" + shape + "' is something he WEARS, and this server's "
                    + "Minecraft cannot put a wished thing on the body (worn things need 1.21.4). "
                    + "Tell him plainly that boots and helmets that do things are not possible "
                    + "here yet, rather than handing him something to right-click instead.");
        }
        if (!ItemCatalogue.SHAPES.contains(shape)) {
            return ToolResult.error("there is no shape called '" + shape + "'. Use held "
                    + "(hold it and use it), thrown (it goes off where it lands), on_hit "
                    + "(he swings it at something), placed "
                    + "(put it down and it keeps working by itself) or machine "
                    + "(put it down and it BECOMES a thing with a handle he pulls), magic_block (put it "
                    + "down and it becomes a BLOCK that does something by itself: pulls, zaps, "
                    + "bounces, surprises when broken), arrow (he shoots "
                    + "it and the wish happens where it lands), on_shot (he shoots it and the wish "
                    + "happens the moment he does: a beam), on_consume (he eats or drinks it and the "
                    + "wish happens after), on_block (he blocks with a shield and the wish rides the "
                    + "block), on_throw_land (he throws a real snowball, egg, pearl… and the wish "
                    + "happens where it lands), on_use_done (he blows a horn, brushes, looks through "
                    + "a spyglass, and the wish happens when he is done), on_mine (he mines with it), "
                    + "or worn_head / "
                    + "worn_chest / worn_legs / worn_feet (he puts it on and it works while he "
                    + "wears it).");
        }
        Integer icon = ItemCatalogue.icon(iconName);
        if (icon == null) {
            // logged, so the next icons drawn are the ones the agent actually reached for.
            com.moddingfromamod.agent.ActionLog.iconMiss(ItemCatalogue.normalise(iconName), "",
                    "refused", name);
            List<String> near = ItemCatalogue.nearestIcons(iconName, 6);
            return ToolResult.error("there is no icon called '" + iconName + "'."
                    + (near.isEmpty() ? "" : " Closest: " + String.join(", ", near) + ".")
                    + " Choose one by name from:" + ItemCatalogue.iconMenu()
                    + "\nNever invent one — an icon that is not on this list silently wears the "
                    + "default artwork. If none is quite right, choose the closest and say what "
                    + "you wanted in icon_wanted.");
        }
        String wanted = str(args, "icon_wanted");
        if (wanted != null && !wanted.isBlank()) {
            com.moddingfromamod.agent.ActionLog.iconMiss(ItemCatalogue.normalise(wanted),
                    iconName, "wanted", name);
        }
        Integer accent = null;
        String accentWord = str(args, "accent");
        if (accentWord != null && !accentWord.isBlank()) {
            accent = colourOf(accentWord);
            if (accent == null) {
                return ToolResult.error("I cannot tell what colour '" + accentWord + "' is (accent). "
                        + "Give it as #RRGGBB, or use one of: " + String.join(", ", COLOURS.keySet()) + ".");
            }
        }
        Integer rgb = colourOf(colour);
        if (rgb == null) {
            return ToolResult.error("I cannot tell what colour '" + colour + "' is. Give it as "
                    + "#RRGGBB, or use one of: " + String.join(", ", COLOURS.keySet()) + ".");
        }
        int count;
        try {
            count = args.get("count").getAsInt();
        } catch (RuntimeException e) {
            return ToolResult.error("count must be a whole number of items, 1 to 64.");
        }
        if (count < 1 || count > 64) {
            return ToolResult.error("count must be between 1 and 64, not " + count
                    + ". (For a wish about a LOT of something, the amount belongs in the effect's "
                    + "own parameters — a `rich` item's COUNT, say — not in how many items he gets.)");
        }
        // on a world whose rules clamp gifts, the hand-over is one of this wish's gifts: a stack
        // of it at most, and a kind of its own among the wish's few.
        {
            int[] n = {count};
            String refused = GiveItemsTool.clampGift("made:" + str(args, "name"), n, 64);
            if (refused != null) {
                return ToolResult.error(refused);
            }
            if (n[0] < count) {
                GiveItemsTool.tellChild(com.moddingfromamod.world.WishClamp.giftWords());
                count = n[0];
            }
        }

        // The agent's parameters, verbatim. EVERY declared one must be present: this is the tool's
        // whole reason for existing, so it is checked before anything is rendered.
        // PARAMETERS, PER EFFECT. One effect may keep passing them flat — that is what the agent
        // has been taught and what every learned template already sends — but several MUST group
        // them by effect id, because two effects can both want SECONDS and a flat list would hand
        // one effect's value to the other with nothing to notice it.
        JsonObject rawParams = args.has("params") && args.get("params").isJsonObject()
                ? args.getAsJsonObject("params").deepCopy() : new JsonObject();
        JsonObject blockSettings = rawParams.has(BLOCK_KEY) && rawParams.get(BLOCK_KEY).isJsonObject()
                ? rawParams.remove(BLOCK_KEY).getAsJsonObject() : null;
        // the supplies that travel WITH it. Rendered after the effects, into the give.
        com.google.gson.JsonElement kitParam = rawParams.remove(KIT_KEY);
        String askedKit = kitParam != null && kitParam.isJsonObject()
                && kitParam.getAsJsonObject().has("items")
                ? kitParam.getAsJsonObject().get("items").getAsString() : "";
        Map<String, Map<String, String>> byEffect = new LinkedHashMap<>();
        boolean grouped = effectIds.size() > 1 || effectIds.stream()
                .anyMatch(id -> rawParams.has(id) && rawParams.get(id).isJsonObject());
        for (String id : effectIds) {
            Map<String, String> one = new LinkedHashMap<>();
            if (grouped) {
                if (rawParams.has(id) && rawParams.get(id).isJsonObject()) {
                    JsonObject g = rawParams.getAsJsonObject(id);
                    for (String k : g.keySet()) {
                        one.put(k, g.get(k).getAsString());
                    }
                }
            } else {
                for (String k : rawParams.keySet()) {
                    one.put(k, rawParams.get(k).getAsString());
                }
            }
            byEffect.put(id, one);
        }
        if (effectIds.size() > 1 && byEffect.values().stream().allMatch(Map::isEmpty)
                && rawParams.size() > 0) {
            return ToolResult.error("with several effects the parameters must be GROUPED BY EFFECT, "
                    + "e.g. {\"hurt\":{\"HEARTS\":\"8\"},\"ignite\":{\"SECONDS\":\"6\"}}. "
                    + "A flat list cannot say which effect a value belongs to, and two of these can "
                    + "both want SECONDS.");
        }

        // Validate every effect BEFORE building any of them, so a compound wish reports all of its
        // problems at once rather than one per round trip.
        StringBuilder problems = new StringBuilder();
        for (String id : effectIds) {
            ItemCatalogue.Entry e = catalogue.get(id);
            Map<String, String> one = byEffect.get(id);
            List<String> missing = e.params().stream().filter(k -> !one.containsKey(k))
                    // turn_into's RADIUS is only read when it changes blocks; whether it does
                    // is derived from the shape, so derive() asks for it then, never a machine.
                    .filter(k -> !(com.moddingfromamod.datapack.TurnInto.EFFECT.equals(id) && "RADIUS".equals(k)))
                    .toList();
            List<String> unknown = one.keySet().stream()
                    .filter(k -> !e.params().contains(k)).toList();
            // BOTH, in one message, because the commonest way to get here is ONE mistake producing
            // both halves: passing DURATION where the template wants SECONDS leaves SECONDS missing
            // and DURATION unknown, and reporting only the missing one tells the agent to supply a
            // parameter it believes it already supplied.
            if (missing.isEmpty() && unknown.isEmpty()) {
                continue;
            }
            problems.append("\nthe '").append(id).append("' effect takes ").append(e.params())
                    .append('.');
            if (!unknown.isEmpty()) {
                problems.append("\n  It has no parameter(s) ").append(unknown)
                        .append(" — check the spelling against that list. (Ignoring a name I do "
                                + "not know is how an item builds cleanly and then does something "
                                + "other than what was asked.)");
            }
            if (!missing.isEmpty()) {
                problems.append("\n  Still needed, with no default for any of them: ")
                        .append(missing).append(". Take each from the child's own words:");
                for (String k : missing) {
                    problems.append("\n    ").append(k).append(" — ").append(e.paramAsks().get(k))
                            .append("\n        (").append(e.paramDocs().get(k)).append(')');
                }
                if (com.moddingfromamod.datapack.TurnInto.EFFECT.equals(id)
                        && ItemCatalogue.MACHINE.equals(shape)) {
                    // Measured: a slushie machine "ice + sugar, more ice = bigger" went to
                    // convert, which can only ever take the ice, and the agent then had to tell him
                    // the sugar and the sizes did not work. This is where it is choosing.
                    problems.append("\n  Does it need MORE THAN ONE thing in, a size, an add-in or a "
                            + "chest? Then it is not turn_into: use effect `load_and_pour` "
                            + "(INGREDIENTS, EXTRAS, MAX, WHERE, OUTPUT).");
                }
            }
        }
        if (problems.length() > 0) {
            return ToolResult.error(problems.toString().strip());
        }

        // THE MAGIC BLOCK'S DIALS: when, who, how far, where, and what it looks like.
        com.moddingfromamod.datapack.MagicBlock.Spec magic = null;
        String magicLook = null;
        boolean magicWalk = false;
        if (com.moddingfromamod.datapack.MagicBlock.SHAPE.equals(shape)) {
            Map<String, String> raw = new LinkedHashMap<>();
            if (blockSettings != null) {
                for (String k : blockSettings.keySet()) {
                    String key = k.toLowerCase(Locale.ROOT);
                    if (!BLOCK_FIELDS.contains(key)) {
                        return ToolResult.error("`block` has no field '" + k + "'. It takes "
                                + BLOCK_FIELDS + ".");
                    }
                    raw.put(key, blockSettings.get(k).isJsonNull() ? ""
                            : blockSettings.get(k).getAsString());
                }
            }
            com.moddingfromamod.datapack.MagicBlock.Parsed parsed =
                    com.moddingfromamod.datapack.MagicBlock.parse(raw, effectIds);
            if (parsed.refusal() != null) {
                return ToolResult.error(parsed.refusal());
            }
            magic = parsed.spec();
            magicWalk = List.of("true", "yes").contains(
                    raw.getOrDefault("walk_through", "").trim().toLowerCase(Locale.ROOT));
            String lookRaw = raw.getOrDefault("look", "").trim().toLowerCase(Locale.ROOT);
            magicLook = lookRaw.isEmpty() ? machineBody(rgb)[0] : lookRaw;
            String badLook = com.moddingfromamod.datapack.BlockRef.refusal("block.look", magicLook,
                    LIVE_REGISTRY);
            if (badLook == null) {
                badLook = com.moddingfromamod.datapack.BlockLook.refusal("block.look", magicLook,
                        com.moddingfromamod.datapack.BlockLook.live(magicLook), true, magicWalk);
            }
            if (badLook == null && magicWalk) {
                badLook = com.moddingfromamod.datapack.MagicBlock.walkThroughRefusal(magic.when());
            }
            if (badLook != null) {
                return ToolResult.error(badLook);
            }
        } else if (sentBlock) {
            return ToolResult.error("`block` (when / who / radius / look) is only for shape "
                    + com.moddingfromamod.datapack.MagicBlock.SHAPE + " — a block he puts down "
                    + "that does something. Either use that shape or leave `block` out.");
        }

        // THE ID IS THE ITEM'S, FOR LIFE — not a slug of what it currently happens to be.
        //
        // It used to be featureId(name, effects), i.e. derived from the very fields a refinement
        // changes, so renaming it MEGA or adding a second effect produced a different id, a
        // different datapack and therefore a different item. Measured over eight ladder cells,
        // `refinements that LANDED` equalled `EXTRA items` exactly: every refinement that worked,
        // worked by creating. You cannot edit a thing whose id is a function of its contents.
        //
        // ⚠ AND THAT IS RIGHT FOR A REFINEMENT AND WRONG FOR "and one that…". This line read
        // `base != null ? base.id() : …`, so `start_from` overwrote A unconditionally and there was
        // no argument, in any combination, meaning "inherit this design and mint a SECOND". The
        // wish that needs it — "and one that shoves the noob in LAVA" — could only ever come out as
        // a change to the pearl he already had. `as_new` is the agent SAYING which it means; it is
        // never inferred from the wording, because a guess cannot tell an ambiguous wish from an
        // explicit one (and the catalogue's own _no_defaults rule).
        String freshId = featureId(name, effectIds.isEmpty() ? shape : String.join("_", effectIds));
        com.moddingfromamod.item.ItemLineage.Route lineage =
                com.moddingfromamod.item.ItemLineage.of(base != null,
                        args.has("as_new") && args.get("as_new").getAsBoolean());
        String lineageRefusal = com.moddingfromamod.item.ItemLineage.refusal(
                lineage, base == null ? null : base.id(), freshId);
        if (lineageRefusal != null && forkId == null) {
            return ToolResult.error(lineageRefusal);
        }
        // A fork is a copy by construction: his own id, whatever the call said about as_new.
        String mintedId = forkId != null ? forkId : com.moddingfromamod.item.ItemLineage.idFor(
                lineage, base == null ? null : base.id(), freshId);
        // A NEW ITEM WHOSE NAME MINTS ANOTHER CHILD'S ID IS HIS OWN, NEVER A WRITE INTO THEIRS. The id
        // comes from the name and effects alone, so two children making "Turbo Pickaxe" (the Builder names the
        // same picks the same way) minted one id, and the second make replaced the first child's design under
        // the first child's stamp. The fork rule only ran for an edit (start_from); a fresh make now asks it
        // too, and a FORK gets the same per-child id an edit's fork gets.
        if (base == null && forkId == null) {
            String clash = freshClash(mintedId);
            if (clash != null && !clash.equals(mintedId)) {
                forkNote = "Another child already has an item called '" + mintedId + "', so this one is HIS OWN, '"
                        + clash + "', and theirs is untouched. Use '" + clash + "' next time.\n";
                forkId = clash;
                mintedId = clash;
            }
        }
        final String featureId = mintedId;
        // REPORTED, because it cannot be read from the arguments — see McpTool.lastMadeId and.
        // Without this the wish records nothing as made and the [undo] button leaves the item behind.
        lastMadeId = featureId;
        String baseItem = str(args, "base_item");
        List<String> badBase = ItemBase.refusals(baseItem, LIVE_REGISTRY);
        if (!badBase.isEmpty()) {
            return ToolResult.error(String.join(" ", badBase));
        }
        // THE WISH RIDES WITH THE REAL ITEM'S OWN ACTION, NEVER INSTEAD OF IT. A
        // `held` wish on a bow cancelled the draw, on a shield the block, on a cookie the eating:
        // the laser bow shot nothing and a magic cookie could not be eaten. ItemTriggers is the one
        // table (make_item, E1 and the tests read it). A conflict whose ride ships is REDIRECTED
        // and said so; one whose ride is not built yet is refused with the path that works today.
        String rode = null;
        com.moddingfromamod.datapack.ItemTriggers.Verdict ride =
                com.moddingfromamod.datapack.ItemTriggers.check(baseItem, shape);
        if (!ride.ok()) {
            String to = ride.redirect();
            if (to != null && ItemCatalogue.SHAPES.contains(to)
                    && com.moddingfromamod.datapack.ItemTriggers.check(baseItem, to).ok()) {
                rode = "\n  Built as `" + to + "`, not `" + shape + "`: " + ride.base() + " "
                        + com.moddingfromamod.datapack.ItemTriggers.describe(ride.action())
                        + " exactly as it always does, and the wish rides on that. Tell him so.";
                shape = to;
                args.addProperty("shape", to);
            } else {
                return ToolResult.error(ride.reason() + (to != null && !ItemCatalogue.SHAPES.contains(to)
                        ? " `" + to + "` is not built yet, so for now leave base_item out: made from "
                        + "scratch it is a thing he right-clicks, with no eating, blocking or throwing "
                        + "of its own to take away. "
                        : " ")
                        + com.moddingfromamod.datapack.ItemTriggers.guidance(ItemCatalogue.SHAPES));
            }
        }
        // AN ARROW NEEDS SOMETHING TO SHOOT IT. From scratch it is a bow — the shape names
        // the thing, the way worn_feet names boots — and a base that cannot fire an arrow would
        // fire nothing, so it is refused rather than accepted and silent.
        // FOOD AND DRINK NEED A REAL ONE. Made from scratch there is nothing to eat, so —
        // as `arrow` from scratch is a bow — `on_consume` from scratch is a real drink or food,
        // chosen by what its icon looks like (ItemTriggers.defaultBase).
        if (("on_consume".equals(shape) || "on_block".equals(shape) || "on_throw_land".equals(shape)
                || "on_use_done".equals(shape))
                && (baseItem == null || baseItem.isBlank() || ItemBase.isWishItem(baseItem))) {
            baseItem = com.moddingfromamod.datapack.ItemTriggers.defaultBase(shape, iconName);
        }
        if ("arrow".equals(shape) || "on_shot".equals(shape)) {
            if (baseItem == null || baseItem.isBlank() || ItemBase.isWishItem(baseItem)) {
                baseItem = "minecraft:bow";
            } else if (!com.moddingfromamod.datapack.ItemTriggers.check(baseItem, shape).ok()) {
                // The three bases are ItemTriggers': the one table make_item, E1 and the
                // tests read, so this list cannot drift from what E1 judges.
                return ToolResult.error("shape `" + shape + "` fires from a bow, a crossbow or a trident — "
                        + "base_item '" + baseItem + "' cannot shoot anything, so its wish would "
                        + "never happen. Use minecraft:bow, minecraft:crossbow or minecraft:trident, "
                        + "or leave base_item out for a bow.");
            }
        }
        String recipient = str(args, "recipient");
        // WHERE it goes, when his words name a place. Grammar checked now, before anything
        // is stored; the place itself is found after the thing is live (a building may still be
        // going up, and wishspot waits for it).
        String putAt = str(args, "at") == null ? "" : str(args, "at").trim();
        if (!putAt.isEmpty() && com.moddingfromamod.place.At.parse(putAt) == null) {
            return ToolResult.error("at must read \"<thing>:<spot>\" — a building's id, a creature or "
                    + "me, then a spot (top, inside, front, beside, …), not '" + putAt + "'.");
        }
        List<String> badTo = ItemRecipient.refusals(recipient);
        if (!badTo.isEmpty()) {
            return ToolResult.error(String.join(" ", badTo));
        }
        com.google.gson.JsonArray parts = args.has("parts") && args.get("parts").isJsonArray()
                ? args.getAsJsonArray("parts") : null;
        List<String> badParts = ItemParts.refusals(parts);
        if (!badParts.isEmpty()) {
            return ToolResult.error(String.join(" ", badParts));
        }
        com.moddingfromamod.item.ItemSpecStore.Spec resolved =
                new com.moddingfromamod.item.ItemSpecStore.Spec(
                        featureId, name, iconName, colour, shape, count, effectIds,
                        args.has("params") && args.get("params").isJsonObject()
                                ? args.getAsJsonObject("params") : new JsonObject(),
                        // Written down, so the NEXT wish can change one flavour without being told
                        // all of them again. Taken from `args` AFTER inherit(), so an edit that
                        // mentioned no family stores the one it started with rather than erasing it.
                        args.has("variants") && args.get("variants").isJsonArray()
                                ? args.getAsJsonArray("variants") : new com.google.gson.JsonArray(),
                        // Written down for the same reason the family is: so the NEXT wish can
                        // change the colour without being told the sizes again.
                        args.has("properties") && args.get("properties").isJsonObject()
                                ? args.getAsJsonObject("properties") : new JsonObject(),
                        parts == null ? new com.google.gson.JsonArray() : parts,
                        baseItem);
        // THE CRITIQUE IS BUILT AGAINST `base`, BEFORE THE STORE IS OVERWRITTEN. Reading it back
        // afterwards would compare the item to itself and report nothing, for ever, which is what
        // an unwired gate also does.
        String critique = forkNote + critiqueFor(base, resolved);
        critique += com.moddingfromamod.datapack.MagicBlock.sameEveryTimeNote(magic, effectIds);
        // WHICH OF THE TWO HAPPENED, said out loud. A copy and an edit differ by one word in
        // the call, and the whole failure is that the wrong one reads as success at every tier we
        // run — the number that separates them is items in his inventory afterwards, which no gate
        // here can see. So the transcript carries it instead of leaving it to be inferred from an id.
        // A fork asked for as an EDIT is his copy standing in for the original — the stacks he
        // holds were changed in place and forkNote says so. "Made a SECOND item, he now has BOTH"
        // contradicted that in the same reply (smoke, re-wishing the canned lucky block).
        String lineageNote = forkId != null
                && lineage != com.moddingfromamod.item.ItemLineage.Route.COPY_THEN_DIVERGE ? ""
                : com.moddingfromamod.item.ItemLineage.note(lineage, base == null ? null : base.id(),
                        featureId);
        if (!lineageNote.isBlank()) {
            critique = critique + lineageNote + "\n";
        }
        // EVERY REFUSAL ABOUT WHAT THE ITEM IS COMES BEFORE THE STORE IS WRITTEN. A design
        // put first and refused after stayed in the store with the bad property, and the next
        // "make my sword unbreakable" (start_from) inherited it and was refused for ever — found
        // by Gate B the day a property could first be refused for its BASE.
        List<String> badProps = ItemProperties.refusals(
                args.has("properties") && args.get("properties").isJsonObject()
                        ? args.getAsJsonObject("properties") : null);
        if (!badProps.isEmpty()) {
            return ToolResult.error(String.join(" ", badProps));
        }
        // A property that parses and means nothing on THIS base (a projectile on a sword, a
        // never-empty diamond) would be a silent no-op: refused, with the base that works.
        List<String> badOnBase = ItemProperties.baseRefusals(
                args.has("properties") && args.get("properties").isJsonObject()
                        ? args.getAsJsonObject("properties") : null, baseItem);
        if (!badOnBase.isEmpty()) {
            return ToolResult.error(String.join(" ", badOnBase));
        }
        // Every id a rod may catch must be a REAL item on this Minecraft: the shape was checked
        // by ItemProperties; only the registry can say it exists.
        if (args.has("properties") && args.get("properties").isJsonObject()) {
            String[] caught = ItemProperties.catchOf(args.getAsJsonObject("properties").get(ItemProperties.CATCHES_KEY));
            if (caught != null) {
                for (String id : caught[0].split(",")) {
                    if (!ItemProperties.CATCH_SPECIALS.contains(id)
                            && net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(
                                    net.minecraft.resources.ResourceLocation.tryParse(id)).isEmpty()) {
                        return ToolResult.error("catches names '" + id + "', which is not an item in this "
                                + "Minecraft. Use a real item id, or treasure_chest / enchanted_book.");
                    }
                }
            }
        }
        // eat_speed means nothing on a thing he does not eat or drink: asked of the REAL
        // item, because "is it eaten" is a component on 1.21.4 and a class on 1.21.1.
        if (args.has("properties") && args.get("properties").isJsonObject()
                && args.getAsJsonObject("properties").has(ItemProperties.EAT_SPEED_KEY)) {
            var eaten = baseItem == null || baseItem.isBlank() ? null
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(
                            net.minecraft.resources.ResourceLocation.tryParse(
                                    baseItem.contains(":") ? baseItem : "minecraft:" + baseItem));
            if (eaten == null || eaten.isEmpty() || !com.moddingfromamod.compat.Consumables.isConsumed(
                    new net.minecraft.world.item.ItemStack(eaten.get()))) {
                return ToolResult.error("eat_speed only means something on a real food or drink — set "
                        + "base_item to one (minecraft:bread, minecraft:mushroom_stew, minecraft:potion…; it is "
                        + (baseItem == null || baseItem.isBlank() ? "nothing" : baseItem) + "). A cake is a "
                        + "block he eats from, not a thing he holds, so it is not this.");
            }
        }
        JsonObject yield = args.has("yield") && args.get("yield").isJsonObject()
                ? args.getAsJsonObject("yield") : null;
        List<String> badYield = ItemYield.refusals(yield, shape,
                args.has("variants") && args.get("variants").isJsonArray()
                        && !args.getAsJsonArray("variants").isEmpty());
        if (!badYield.isEmpty()) {
            return ToolResult.error(String.join(" ", badYield));
        }
        // What this store held before, so a build that fails below can put it back: the
        // design is written first because the steps below read it, and a failed build must not
        // leave a design no pack carries for the next re-wish to inherit.
        final com.moddingfromamod.item.ItemSpecStore.Spec before =
                com.moddingfromamod.item.ItemSpecStore.own(resolved.id());
        com.moddingfromamod.item.ItemSpecStore.put(resolved);
        final String storedId = resolved.id();
        Runnable unstore = () -> com.moddingfromamod.item.ItemSpecStore.restore(storedId, before);
        // record WHICH PATH made this item. A wished item is a tinted vanilla
        // carrier on the substituted path and a `wish_item` on the mod path, and those are
        // different things on disk -- so a world whose items were minted one way and is
        // later opened the other way degrades, silently, into a leather horse armour.
        com.moddingfromamod.lite.MintStore.stamp(
                com.moddingfromamod.agent.AgentBridge.serverOrNull(), "item",
                com.moddingfromamod.datapack.VanillaCarrier.enabled());
        Map<String, Map<String, String>> argsByEffect = new LinkedHashMap<>();
        Map<String, String> derivedAim = new LinkedHashMap<>();
        String[] signLines = {"", ""};
        String recipeLine = null;
        String machineDoes = null;
        String machineKit = null;       // the starter kit's give lines, if it is a filled machine
        String machineKitSaid = null;
        for (String id : effectIds) {
            ItemCatalogue.Entry e = catalogue.get(id);
            Map<String, String> effectArgs = new LinkedHashMap<>(byEffect.get(id));
            // Each effect gets a TARGET from ITS OWN applies_to: a hurt+heal item damages what it
            // hits and heals the child, and one shared selector could not express that.
            effectArgs.put("TARGET", magic != null ? magic.target()
                    : ItemCatalogue.targetFor(shape, e.appliesTo(), PLACED_RADIUS));
            if ("fill_blocks".equals(id)) {
                // Tags its markers with the item's own id, so undo takes back exactly its fills.
                effectArgs.put("FEATURE", featureId);
            }
            if ("load_and_pour".equals(id)) {
                // THE LOAD LIVES ON THIS MACHINE'S OWN ANCHOR, which the machine shape tags with
                // the feature id -- so two machines never share one load.
                effectArgs.put("MACHINE", featureId);
                effectArgs.put(FILL_NAME, jsonSafe(name));
            }
            String derivedError = derive(e, shape, effectArgs, LIVE_REGISTRY);
            if (derivedError != null) {
                return failed(unstore, derivedError);
            }
            if (args.has("spare_players") && args.get("spare_players").isJsonPrimitive()
                    && args.get("spare_players").getAsBoolean()) {
                SparePlayers.apply(effectArgs);   //: what lands where it falls never touches a player
            }
            // THE SIGN SAYS WHAT IT DOES (contract C18): lines 2-3 are the recipe, generated
            // from the same arguments the machine runs on, so the sign cannot tell a lie after a
            // re-wish changes the recipe.
            String aimed = effectArgs.remove(TurnIntoRecipe.AIM_KEY);
            effectArgs.remove(TurnIntoRecipe.MODE_KEY);
            if (aimed != null) {
                derivedAim.put(id, aimed);
            }
            String l2 = effectArgs.remove(SIGN_2);
            String l3 = effectArgs.remove(SIGN_3);
            String said = effectArgs.remove(RECIPE);
            String how = effectArgs.remove(HOW);
            if (how != null) {
                machineDoes = how;
            }
            String kitLines = effectArgs.remove(KIT);
            String kitTold = effectArgs.remove(KIT_SAID);
            if (kitLines != null && machineKit == null) {
                machineKit = kitLines;
                machineKitSaid = kitTold;
            }
            effectArgs.remove(FILL_NAME);
            if (l2 != null && signLines[0].isEmpty()) {
                signLines[0] = l2;
                signLines[1] = l3;
                recipeLine = said;
            }
            argsByEffect.put(id, effectArgs);
        }

        Map<String, String> shapeArgs = new LinkedHashMap<>();
        shapeArgs.put("FEATURE", featureId);
        shapeArgs.put("ICON", String.valueOf(icon));
        shapeArgs.put("COLOUR", String.valueOf(rgb));
        shapeArgs.put("HEX", String.format("#%06X", rgb));
        shapeArgs.put("NAME", jsonSafe(name));
        shapeArgs.put("COUNT", String.valueOf(count));
        shapeArgs.put("HINT", jsonSafe(hintFor(shape)));
        shapeArgs.put("GIVE_MESSAGE", jsonSafe("You got " + name + "! " + hintFor(shape)));
        // THE FAMILY, or a family of one. parseVariants returns the single-member list when the
        // wish did not ask for a set, so every existing wish takes exactly the path it always did.
        List<Variant> family;
        try {
            family = parseVariants(args, name, rgb);
        } catch (IllegalArgumentException bad) {
            return failed(unstore, bad.getMessage());
        }
        if (accent != null) {
            // one second colour for the whole family; each member keeps its own main one.
            List<Variant> withAccent = new ArrayList<>();
            for (Variant v : family) {
                withAccent.add(new Variant(v.name(), v.rgb(), v.props(), accent));
            }
            family = withAccent;
        }
        if (args.has("look") && args.get("look").isJsonPrimitive() && args.get("look").getAsBoolean()
                && baseItem != null && !baseItem.isBlank() && !ItemBase.wearsOurIcons(baseItem)) {
            // the Builder's chosen LOOK, drawn on the real item it is built on: eaten, worn or mined as the real
            // one, seen as the look. Without `look` a real item keeps its own artwork, as it always did.
            family = family.stream().map(Variant::drawnAsIcon).toList();
        }
        if (args.has("tint") && args.get("tint").isJsonPrimitive() && args.get("tint").getAsBoolean()
                && com.moddingfromamod.pack.TintedItems.modelFor(baseItem) != null) {
            // the Wish Builder's colour scheme, DRAWN on the real item (its tinted model + the colour as
            // dyed_color). Only on a real item that has a tinted model; everywhere else `tint` changes nothing.
            family = family.stream().map(Variant::painted).toList();
        }
        // ⚠ THE **RAW** BASE. Resolving here and passing the answer on is the one mistake this
        // whole seam invites, and it shipped for an afternoon on the line below: everything
        // downstream keys on the raw base to tell "our own item, possibly substituted" from "an
        // item the child named", and a resolved carrier id is indistinguishable from the second.
        // MEASURED, on this exact line: every wished thing came out as a plain brown leather
        // horse armour reading "+3 Armor", with no icon, no colour and no carrier mark for the
        // creative-menu shim to find. See GiveLineIsWhatCallRendersTest, which drives the give
        // line the way call() does rather than the way itemArg's own tests do.
        // WHAT IT DOES, DRAWN ON IT: derived from the effects, never asked for.
        String overlay = com.moddingfromamod.item.IconOverlays.forEffects(effectIds);
        shapeArgs.put("GIVES", gives(shape, featureId, icon, count, hintFor(shape), family,
                recipient, ItemParts.loreLine(parts), baseItem, overlay));
        String toldOther = ItemRecipient.message(recipient, name);
        if (toldOther != null) {
            // HE MUST BE TOLD, or the wish looks like it did nothing. The give lands on a creature
            // several blocks away and nothing arrives in his hands; without this line the only
            // evidence the wish worked is noticing what a piglin is carrying.
            shapeArgs.put("GIVE_MESSAGE", jsonSafe(toldOther));
        }
        if (family.size() > 1) {
            // He asked for a SET, so tell him he got one. "You got Vanilla Ice Cream!" after asking
            // for twenty flavours reads as nineteen of them having gone missing.
            shapeArgs.put("GIVE_MESSAGE", jsonSafe("You got all " + family.size() + " of them! "
                    + hintFor(shape)));
        }
        // AIM comes from `aim`, NOT from applies_to. They are different questions and merging them
        // shipped a teleport that teleported you to where you already were.
        // turn_into's aim is DERIVED with its mode (TurnInto): a machine's works on his bag, a
        // held one raycasts for itself, a thrown one acts at the block where it lands.
        java.util.function.Function<String, String> aimOf = id -> derivedAim.getOrDefault(id,
                catalogue.get(id).aim());
        boolean anyAhead = effectIds.stream().anyMatch(id -> "ahead".equals(aimOf.apply(id)));
        // ANY, not all. Every self-aimed body addresses @s or @p[distance=..] explicitly, so it
        // works wherever it is run from — while an ahead-aimed one is wrong anywhere but the aim
        // point. So the stricter requirement wins, and a hurt+heal wand damages what he points at
        // and still heals him.
        // `block` is stricter still: the exact block he points at, found by walking his
        // line of sight, because a fixed reach lands inside a near wall or past a far one. Only a
        // HELD thing has a line of sight to walk; the machine keeps the fixed reach.
        boolean anyBlock = effectIds.stream().anyMatch(id -> "block".equals(aimOf.apply(id)));
        shapeArgs.put("AIM", "on_shot".equals(shape)
                // THE LASER: a beam to the first thing in the way, the wish there — or on
                // him, for a wish about him. ShotBeam; `block`-aimed effects land at the hit too.
                ? String.join("\n    ", com.moddingfromamod.datapack.ShotBeam.lines("$namespace:effect",
                        anyAhead || anyBlock, com.moddingfromamod.datapack.ShotBeam.particle(rgb)))
                : anyBlock && ("held".equals(shape) || "on_use_done".equals(shape))
                ? String.join("\n    ", com.moddingfromamod.datapack.ChangeBlock.aimLines("$namespace:effect"))
                : !anyAhead && !anyBlock
                ? "/function $namespace:effect"
                : "/execute at @s anchored eyes positioned ^ ^ ^"
                        + ("on_block".equals(shape) ? BLOCK_REACH : String.valueOf(HELD_REACH))
                        + " run function $namespace:effect");
        // WHAT USING IT LEAVES BEHIND. Empty for every wish that did not ask, which is nearly all
        // of them -- the placeholder exists on the two shapes that have a moment of use, so it must
        // always be supplied even when there is nothing to put in it.
        shapeArgs.put("YIELD", yield == null ? ""
                : yieldBlock(featureId, shape, icon, yield, baseItem));
        shapeArgs.put("PERIOD_TICKS", String.valueOf(PLACED_PERIOD_TICKS));
        // WHAT THE MACHINE IS MADE OF, and what its sign says. Always supplied, because a template
        // rendered with a placeholder still in it compiles perfectly and does nothing (see
        // ItemCatalogue's class note) -- so the machine shape would build out of the literal string
        // "${BODY_BASE}" and the failure would be a machine-shaped hole.
        String[] body = machineBody(rgb);
        shapeArgs.put("BODY_BASE", body[0]);
        shapeArgs.put("BODY_MID", body[1]);
        shapeArgs.put("BODY_TOP", body[2]);
        // What a magic block looks like, named for its precompiled skeleton: the same pick in another look is
        // the same pack. No template spells ${LOOK}; ItemCatalogue.skeleton takes it out of the look's command lines.
        shapeArgs.put("LOOK", magicLook == null ? body[0] : magicLook);
        // THE MAGIC BLOCK'S PLACEHOLDERS, always supplied for the same reason the machine's are.
        shapeArgs.putAll(com.moddingfromamod.datapack.MagicBlock.shapeArgs(
                magic == null ? com.moddingfromamod.datapack.MagicBlock.defaults() : magic, featureId,
                magicLook == null ? body[0] : magicLook, magicWalk, !effectIds.isEmpty()));
        // FOUR LINES OF SIGN, and the name has to fit on one of them. A sign line holds about 15
        // characters before it is cut off mid-word, and "Blue Raspberry Slushie Machine" is not a
        // label at that width -- it is a smear. Trimmed rather than wrapped, because the second
        // line already says what to do with it.
        shapeArgs.put("SIGN_NAME", jsonSafe(name.length() > SIGN_WIDTH
                ? name.substring(0, SIGN_WIDTH - 1).trim() + "…" : name));
        shapeArgs.put("SIGN_COLOUR", String.format("#%06X", rgb));
        shapeArgs.put("SIGN_IN", jsonSafe(signLines[0]));
        shapeArgs.put("SIGN_OUT", jsonSafe(signLines[1]));
        if (recipeLine != null && ItemCatalogue.MACHINE.equals(shape) && recipient == null) {
            // BEFORE HE PLACES IT (C18's first moment): the give message says what it takes.
            shapeArgs.put("GIVE_MESSAGE", jsonSafe("You got " + name + "! " + hintFor(shape)
                    + " It makes " + recipeLine + ".")
                    + (machineKitSaid == null ? "" : " " + machineKitSaid));
        }
        // THE KIT: what a filled machine needs to pour, plus whatever supplies the wish
        // handed over with this thing (`kit`). They are the thing's, not the wish's: re-wishing
        // the thing — alone, as a part of a bigger wish — brings them again, where a raw `give`
        // beside it belonged to nothing and never came back.
        String kit = machineKit != null && ItemCatalogue.MACHINE.equals(shape) ? machineKit : "";
        String asked = kitLines(askedKit, LIVE_REGISTRY);
        if (asked.startsWith("!")) {
            return failed(unstore, asked.substring(1));
        }
        kit += asked;
        if (recipient != null) {
            kit = "";   // a thing handed to a creature does not drag his supplies along
        }
        if (REFUNDS.contains(shape)) {
            // ITS OWN PLACEHOLDER in a shape that hands a broken one back, so the refund gives
            // the thing ALONE: the kit is for the first pour, not for every re-placing.
            shapeArgs.put("KIT", kit);
        } else if (!kit.isEmpty()) {
            // AFTER the thing, so a full bag still gets him the thing first.
            shapeArgs.put("GIVES", shapeArgs.get("GIVES") + kit);
        }
        // A plain thing has no effect, so it has no trail either -- and no shape that renders a
        // trail is a shape a plain item can have.
        shapeArgs.put("TRAIL", effectIds.isEmpty() ? "" : trailFor(effectIds.get(0)));
        shapeArgs.putAll(wornArgs(shape, featureId, argsByEffect));

        long t0 = System.nanoTime();
        String source;
        com.moddingfromamod.datapack.ReadyMadeSkeleton.Skeleton skeleton;
        try {
            source = catalogue.render(effectIds, shape, argsByEffect, shapeArgs);
            // Its precompiled pack's key: every Builder and Apprentice item is built with no compiler.
            skeleton = catalogue.skeleton(effectIds, shape, argsByEffect, shapeArgs);
        } catch (IllegalArgumentException e) {
            return failed(unstore, e.getMessage());
        }
        long renderedMs = (System.nanoTime() - t0) / 1_000_000;

        if (!CbScriptCompiler.available() && !com.moddingfromamod.datapack.PrecompiledPacks.covers(DatapackForge.dialect(skeleton))) {
            return failed(unstore, "the CBScript compiler is turned off here (a test), and this ready-made "
                    + "item is not one of the precompiled ones, so it cannot be built here.");
        }
        try {
            long t1 = System.nanoTime();
            CompileResult r = DatapackForge.compile(featureId,
                    "ready-made item: " + name + " (" + shape + " / "
                            + String.join("+", effectIds) + ")", source, skeleton);
            long compiledMs = (System.nanoTime() - t1) / 1_000_000;
            if (!r.ok()) {
                // A catalogue template that stopped compiling is a REGRESSION in the catalogue, not
                // something for the agent to debug — so say which one, and do not hand it the source.
                return failed(unstore, "the ready-made '" + String.join("+", effectIds)
                        + "' + '" + shape + "' template failed to compile, which is a bug in the catalogue rather "
                        + "than in your wish: " + r.failureSummary());
            }

            long t2 = System.nanoTime();
            // A/B seam, matching apply_effect's: reproduces "compiled but never loaded" exactly, so
            // the client gate can be PROVEN to detect it rather than assumed to.
            if ("true".equals(System.getProperty(
                    com.moddingfromamod.ModdingFromAModMod.MODID + ".skipItemActivate"))) {
                return ToolResult.ok(critique + "Made '" + name + "'.\n  [A/B SEAM: activation deliberately "
                        + "skipped]");
            }
            net.minecraft.server.MinecraftServer srv = AgentBridge.serverOrNull();
            if (srv == null) {
                // "Compiled" and "the child HAS it" are different claims. Conflating them is the
                // entire bug this code path exists to avoid.
                return ToolResult.ok("Built '" + name + "', but there is no running world to load "
                        + "it into, so it is NOT active."
                        + "\n  rendered in " + renderedMs + "ms, compiled in " + compiledMs + "ms"
                        + "\n  feature id: " + featureId);
            }
            String packId;
            if (srv.isSameThread()) {
                packId = DatapackForge.activate(srv, featureId);
            } else {
                packId = AgentBridge.onServer(() -> {
                    try {
                        return DatapackForge.activate(srv, featureId);
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            // DID THE SERVER TAKE IT? The template compiles, but it carries the agent's own
            // commands, and one the server cannot parse drops its function — a dropped TICK function
            // stops every datapack clock in the world, not just this item's. Taken back off.
            java.util.function.Supplier<com.moddingfromamod.datapack.DatapackVerifier.Report> check =
                    () -> com.moddingfromamod.datapack.DatapackReloader.takeBackIfRefused(srv, featureId);
            var refused = srv.isSameThread() ? check.get() : AgentBridge.onServer(check::get);
            if (refused != null) {
                return failed(unstore, "'" + name + "' is NOT live: the server refused part of it, so "
                        + "it has been switched off again (one refused tick function stops EVERY "
                        + "datapack in the world).\n" + refused.describe()
                        + "\nThe template is fine; a command you passed in is not. Fix it and call "
                        + "make_item again.");
            }
            long loadedMs = (System.nanoTime() - t2) / 1_000_000;
            // A MEMBER HE ASKED TO BE RID OF IS STILL IN HIS HANDS UNTIL SOMEBODY TAKES IT —.
            //
            // "actually make the grape one of them BUTT CHEEK WATER flavor" is a SWAP: the design
            // and the pack both do it, and the Grape stack he was already holding is untouched, so
            // from where he is standing there are now six flavours and one of them is the thing he
            // just replaced. Measured across two runs of the same rung, the agent removed it
            // unprompted in one and not the other — probabilistic, where the invariant is binary.
            //
            // Done HERE and not in the give function, which is the obvious place and is wrong:
            // "give me loads of them" is granted by running give repeatedly, so a give that cleared
            // its own family first would leave him exactly one batch however many times it ran.
            // Measured — the slushies ladder's last rung is that exact command, run five times.
            //
            // AFTER the pack is live, so an edit that fails to compile never costs him a stack.
            List<String> retired = retiredMembers(base, family);
            int cleared = retired.isEmpty() ? 0 : clearRetired(srv, retired);
            // A REFINEMENT CHANGES WHAT HE IS HOLDING, see HeldRestamp. Without this the
            // design and the pack changed and the stack in his hand did not, and the give below
            // then handed him a second one beside it: one design in the store, two in his bag.
            // A POLISH AFTER THE FINISH (AfterFinish): it changes what he has, never hands him more.
            // Every stack an edit rewrites in his hand glints for a moment so he sees which: a
            // follow-up wish ("make my wand blue") as much as a polish after the finish (
            // 2026-10-22).
            var thisRun = com.moddingfromamod.agent.AgentContext.currentRun();
            boolean polishing = thisRun != null && com.moddingfromamod.wish.BuildGate
                    .forWish(thisRun.wishId()).wasFinishBuildingCalled();
            int restamped = com.moddingfromamod.item.HeldRestamp.applies(lineage,
                    ItemRecipient.isMe(recipient))
                    ? restampHeld(srv, base, featureId, shape, family, icon,
                            ItemParts.loreLine(parts), baseItem, overlay, true)
                    : 0;
            boolean handOver = com.moddingfromamod.item.HeldRestamp.stillHandOver(
                    restamped, askedCount, base == null ? count : base.count());
            if (polishing) {
                handOver = false;
            }
            // RECORDED NOW, AT THE GRANT — its tile, "Give me one" and creative entry no
            // longer wait for it to turn up in his bag (a full bag, a give that missed, a thing
            // handed to a creature never did). Owned by the wisher this call is bound to.
            if (!family.isEmpty()) {
                recordGranted(srv, family.get(0), icon, shape, ItemParts.loreLine(parts), baseItem,
                        featureId, overlay,
                        com.moddingfromamod.agent.AgentContext.currentPlayerId());
            }
            // PUT THERE, not in his hand — the wishspot the agent would otherwise type.
            String placedLine = putAt.isEmpty() ? null : putThere(srv, putAt, featureId, count);
            String placedAt = placedLine != null && placedLine.startsWith("PUT THERE") ? placedLine : null;
            if (placedAt != null) {
                handOver = false;
            }
            return ToolResult.ok("Made '" + name + "' and it is now LIVE in the world."
                    + (placedLine == null ? "" : "\n  " + placedLine)
                    + (machineDoes == null ? "" : "\n  WHAT THIS MACHINE DOES — true now, so tell "
                            + "him this and do not promise it later: " + machineDoes + ".")
                    + (rode == null ? "" : rode)
                    + (cleared > 0 ? "\n  Took " + cleared + " of the old " + retired
                            + " out of his inventory, since he asked for " + (retired.size() == 1
                            ? "it" : "them") + " to be replaced." : "")
                    + (restamped > 0 ? "\n  Changed the " + restamped + " stack"
                            + (restamped == 1 ? "" : "s") + " he was already holding, in place — "
                            + "the thing in his hand IS the new version." : "")
                    + "\n  " + hintFor(shape) + "  " + effectIds.stream()
                            .map(id -> com.moddingfromamod.marshal.ItemVitals.childWhen(id,
                                    catalogue.get(id).childWhen(),
                                    com.moddingfromamod.wish.WatchSnapshot.paramsOf(resolved.params())))
                            .collect(java.util.stream.Collectors.joining("  "))
                    + "\n  rendered in " + renderedMs + "ms, compiled in " + compiledMs
                    + "ms, loaded in " + loadedMs + "ms"
                    + "\n  feature id: " + featureId + "  (pack " + packId + ")"
                    + (placedAt != null ? "\nIt is THERE, not in his hand — do not hand him one as well "
                            + "unless he asked for one to hold too (then: run_command: wishdatapack "
                            + featureId + " give). A taught wish puts it there again with: wishspot "
                            + putAt + " run wishput " + featureId + (count > 1 ? " " + count : "")
                            : handOver ? handOverLine(featureId)
                            : "\nDO NOT HAND HIM ANOTHER ONE. He asked to change the one he has, "
                            + "and it is changed where he is holding it; a give now leaves him "
                            + "with two of it — the thing a refinement must never do. Only if he "
                            + "asks for MORE of it: run_command: wishdatapack " + featureId
                            + " give")
                    + "\nNo code was written: these templates were already known to compile."
                    + critique);
        } catch (Exception e) {
            return failed(unstore, "could not build the ready-made item: " + e);
        }
    }

    /**
     * The catalogue row for a thing just made: the stack its give line hands over, parsed
     * by the game's own {@code ItemParser} from the same {@link #itemArg} the datapack renders, so
     * the row is the thing he gets and not a second definition of it. Never fails the wish.
     */
    private static void recordGranted(net.minecraft.server.MinecraftServer srv, Variant v, int icon,
            String shape, String lore, String baseItem, String featureId, String overlay,
            String wisher) {
        Runnable record = () -> {
            try {
                var parsed = new net.minecraft.commands.arguments.item.ItemParser(
                        srv.registryAccess()).parse(new com.mojang.brigadier.StringReader(
                                itemArg(v, icon, hintFor(shape), lore, baseItem,
                                        customData(shape, featureId), shape, overlay)));
                net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(
                        parsed.item(), 1, parsed.components());
                if (com.moddingfromamod.item.WishItemCatalogue.rememberGranted(srv, stack, wisher)) {
                    com.moddingfromamod.net.ModdedClient.broadcastWishItems(srv);
                }
            } catch (Exception | LinkageError e) {
                org.slf4j.LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID)
                        .debug("could not record '{}' at its grant: {}", featureId, e.toString());
            }
        };
        try {
            if (srv.isSameThread()) {
                record.run();
            } else {
                AgentBridge.onServer(() -> {
                    record.run();
                    return null;
                });
            }
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(com.moddingfromamod.ModdingFromAModMod.MODID)
                    .debug("could not record '{}' at its grant: {}", featureId, e.toString());
        }
    }

    /**
     * <b>Put the made thing at {@code at}</b> through the same {@code wishspot … run wishput}
     * a taught wish replays, run as the child at permission 2 — so it waits for a building still
     * going up, resolves the place with the shared finder, and can do nothing a wish could not.
     * Returns the line to tell the agent: {@code PUT THERE: …}, or why it was not.
     */
    private static String putThere(net.minecraft.server.MinecraftServer srv, String at, String featureId, int count) {
        String cmd = "wishspot " + at + " run wishput " + featureId + (count > 1 ? " "
                + Math.min(count, com.moddingfromamod.item.WishPut.MAX_COUNT) : "");
        java.util.function.Supplier<String> body = () -> {
            var player = com.moddingfromamod.agent.ActingChild.of(srv);
            var level = player != null ? player.level() : srv.overworld();
            var where = player != null ? player.position()
                    : net.minecraft.world.phys.Vec3.atBottomCenterOf(level.getLevelData().getRespawnData().pos());
            var r = com.moddingfromamod.wish.WishExecutor.runOne(srv, level, where, player, cmd);
            return (r.success() ? "PUT THERE: " : "⚠ NOT PUT THERE (it is made, nobody has it yet): ")
                    + (r.output().isEmpty() ? cmd : r.joined());
        };
        return srv.isSameThread() ? body.get() : AgentBridge.onServer(body::get);
    }

    /** A failure after the design was stored: put the store back first. */
    private static ToolResult failed(Runnable unstore, String why) {
        unstore.run();
        return ToolResult.error(why);
    }

    /** The hand-over instruction for a thing he is not yet holding. */
    private static String handOverLine(String featureId) {
        return "\nNOW PUT IT IN HIS HAND, with exactly this and nothing else:"
                    + "\n    run_command: wishdatapack " + featureId + " give"
                    + "\nThe item exists but nobody is holding it until you do. Use THAT command, "
                    + "not `function " + featureId + ":give` — a bare /function on a pack that has "
                    + "just loaded fails with 'This function should not run', and retrying it does "
                    + "not help. Measured in real play: nine attempts and two minutes lost before "
                    + "the agent found the working form, and the child sat watching an empty bag."
                    + "\nIt lands in his HOTBAR, or in his WISH BAG (hotbar slot 2) when the hotbar "
                    + "is full — never his main inventory. The command's result says exactly where, "
                    + "and Moddy tells him by name; repeat THAT if you mention it, never 'check "
                    + "your inventory'.";
    }


    /**
     * The members this edit RETIRED — in the design before and not in it now.
     *
     * <p>Pure, and deliberately the narrow question. Clearing "the family" would be simpler and
     * would take away members that still exist, which is destructive for no reason; clearing only
     * the difference can never touch a flavour he still has. A rename of every member retires every
     * old name, which is correct — those stacks are stale under any reading.
     *
     * <p>Empty whenever the item never had a family, so a plain one-of-a-kind wand takes exactly
     * the path it always did, and so does a design written before families were persisted,
     * whose {@code variants} loads empty rather than wrong.
     *
     * @param before the design this edit started from, or null for a brand-new wish
     * @param after  the family being rendered now
     */
    static List<String> retiredMembers(com.moddingfromamod.item.ItemSpecStore.Spec before,
                                       List<Variant> after) {
        if (before == null || before.variants() == null || before.variants().isEmpty()) {
            return List.of();
        }
        java.util.Set<String> kept = new java.util.LinkedHashSet<>();
        for (Variant v : after) {
            kept.add(v.name().trim().toLowerCase(Locale.ROOT));
        }
        List<String> gone = new ArrayList<>();
        for (var el : before.variants()) {
            if (!el.isJsonObject()) {
                continue;                 // a damaged member is not evidence that anything retired
            }
            var o = el.getAsJsonObject();
            String was = o.has("name") ? o.get("name").getAsString().trim() : "";
            if (!was.isEmpty() && !kept.contains(was.toLowerCase(Locale.ROOT))) {
                gone.add(was);
            }
        }
        return gone;
    }

    /**
     * Take the retired members out of every player's inventory. Server thread only.
     *
     * <p>Matched on the EXACT name and only among stacks the catalogue recognises as wish items —
     * the same two conditions {@code RemoveItemTool} already uses, rather than a second opinion
     * about what belongs to a wish. Exact rather than {@code contains}, because "Grape Fizzlepop
     * Slushie" is a substring of nothing here but a looser rule would be one rename away from
     * taking a thing he still wants.
     *
     * <p><b>Bounded, and the bound is inherited rather than new:</b> this sees the player's
     * inventory and not a chest, so a retired flavour he put away survives. Widening it to the
     * world would mean deleting from storage he arranged on purpose, which is a much worse failure
     * than a spare slushie in a box.
     *
     * @return how many items were actually taken
     */
    private static int clearRetired(net.minecraft.server.MinecraftServer srv, List<String> retired) {
        java.util.Set<String> want = new java.util.LinkedHashSet<>();
        for (String r : retired) {
            want.add(r.trim().toLowerCase(Locale.ROOT));
        }
        java.util.concurrent.Callable<Integer> sweep = () -> {
            int taken = 0;
            for (net.minecraft.server.level.ServerPlayer p : srv.getPlayerList().getPlayers()) {
                for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                    net.minecraft.world.item.ItemStack st = p.getInventory().getItem(i);
                    if (st.isEmpty()
                            || !com.moddingfromamod.item.WishItemCatalogue.knows(st)) {
                        continue;
                    }
                    String shown = st.getHoverName().getString().trim().toLowerCase(Locale.ROOT);
                    if (want.contains(shown)) {
                        taken += st.getCount();
                        p.getInventory().setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                    }
                }
            }
            return taken;
        };
        try {
            // Same shape as the activation above: already on the server thread, just do it;
            // otherwise hand it over. Calling AgentBridge.onServer FROM the server thread is
            // refused (correctly — it would deadlock), and that reads as the feature being broken.
            return srv.isSameThread() ? sweep.call() : AgentBridge.onServer(() -> {
                try {
                    return sweep.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            // A stale stack left behind is a blemish; a failed wish is a broken promise. The item
            // is already live at this point, so this must never be the thing that fails the call.
            return 0;
        }
    }

    /**
     * <b>Rewrite the stacks he already holds to the edited design, in their own slots.</b>
     *
     * <p>The decisions are {@link com.moddingfromamod.item.HeldRestamp}'s; this is the world half.
     * The new stack is parsed out of {@link #itemArg} — the same line the give renders — so there
     * is one definition of what a made item is, not a second one written in Java.
     *
     * <p>Matched on the OLD design's whole {@code custom_data}, identity and behaviour, for the
     * reason {@link #matchArg} gives: a {@code yield}'s leftovers share the feature id, and a
     * slice restamped into a pizza is a pizza he did not have. What the stack has gathered in play
     * — its wear and any enchantment — is carried over; the design never had an opinion on either.
     * Same bound as {@link #clearRetired}: his inventory, never a chest he arranged.
     *
     * @return how many stacks were rewritten; 0 on any failure, because a stale stack is a
     *         blemish and a failed wish is a broken promise
     */
    private static int restampHeld(net.minecraft.server.MinecraftServer srv,
            com.moddingfromamod.item.ItemSpecStore.Spec before, String featureId, String shape,
            List<Variant> family, int icon, String lore, String baseItem, String overlay,
            boolean glint) {
        if (before == null) {
            return 0;
        }
        List<String> oldNames = new ArrayList<>();
        if (before.variants() == null || before.variants().isEmpty()) {
            oldNames.add(before.name());
        } else {
            for (var el : before.variants()) {
                oldNames.add(el.isJsonObject() && el.getAsJsonObject().has("name")
                        ? el.getAsJsonObject().get("name").getAsString() : "");
            }
        }
        List<String> newNames = new ArrayList<>();
        for (Variant v : family) {
            newNames.add(v.name());
        }
        java.util.concurrent.Callable<Integer> sweep = () -> {
            net.minecraft.nbt.CompoundTag was = net.minecraft.nbt.TagParser.parseCompoundFully(
                    "{" + customData(before.shape(), before.id()) + "}");
            String data = customData(shape, featureId);
            int done = 0;
            for (net.minecraft.server.level.ServerPlayer p : srv.getPlayerList().getPlayers()) {
                if (!com.moddingfromamod.item.HeldRestamp.mayRestamp(before.ownerId(),
                        p.getStringUUID())) {
                    continue;
                }
                var inv = p.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    net.minecraft.world.item.ItemStack st = inv.getItem(i);
                    var cd = st.isEmpty() ? null
                            : st.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                    if (cd == null || !carries(cd.copyTag(), was)) {
                        continue;
                    }
                    int m = com.moddingfromamod.item.HeldRestamp.memberFor(
                            st.getHoverName().getString(), oldNames, newNames);
                    if (m < 0) {
                        continue;
                    }
                    var parsed = new net.minecraft.commands.arguments.item.ItemParser(
                            srv.registryAccess()).parse(new com.mojang.brigadier.StringReader(
                                    itemArg(family.get(m), icon, hintFor(shape), lore, baseItem,
                                            data, shape, overlay)));
                    net.minecraft.world.item.ItemStack now = new net.minecraft.world.item.ItemStack(
                            parsed.item(), st.getCount(), parsed.components());
                    copyIfPresent(st, now, net.minecraft.core.component.DataComponents.DAMAGE);
                    copyIfPresent(st, now,
                            net.minecraft.core.component.DataComponents.ENCHANTMENTS);
                    if (glint) {
                        com.moddingfromamod.item.PolishGlintService.apply(now,
                                srv.overworld().getGameTime());
                    }
                    inv.setItem(i, now);
                    done++;
                }
            }
            return done;
        };
        try {
            return srv.isSameThread() ? sweep.call() : AgentBridge.onServer(() -> {
                try {
                    return sweep.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            return 0;
        }
    }

    /** Every key the design wrote is on the stack with the same value — extra keys are fine. */
    private static boolean carries(net.minecraft.nbt.CompoundTag held,
                                   net.minecraft.nbt.CompoundTag design) {
        for (String k : design.keySet()) {
            if (!java.util.Objects.equals(held.get(k), design.get(k))) {
                return false;
            }
        }
        return !design.isEmpty();
    }

    private static <T> void copyIfPresent(net.minecraft.world.item.ItemStack from,
            net.minecraft.world.item.ItemStack to,
            net.minecraft.core.component.DataComponentType<T> type) {
        T v = from.get(type);
        if (v != null && from.getComponentsPatch().get(type) != null) {
            to.set(type, v);
        }
    }

    /**
     * Which required fields this call did not supply — pure, so a gate can ask without a world.
     *
     * <p>Extracted rather than left inline because "can the surface express this wish" is a
     * question about the ARGUMENTS, and answering it used to mean booting a server: `call` compiles
     * and activates, so the only place the rule could be observed was a GameTest. The
     * expressibility corpus asks it of twenty wishes on every commit.
     *
     * <p>Note what it does NOT do: it takes no view on `start_from`. An edit's missing fields are
     * filled by {@link #inherit} before this runs, which is why the caller resolves the base first.
     * Duplicating that knowledge here is how the two would drift.
     */
    static List<String> missingFields(JsonObject args) {
        List<String> absent = new ArrayList<>();
        if (str(args, "name") == null) {
            absent.add("name (what the child called it)");
        }
        if (str(args, "icon") == null) {
            absent.add("icon");
        }
        if (str(args, "colour") == null) {
            absent.add("colour");
        }
        if (str(args, "shape") == null) {
            absent.add("shape (held, thrown, placed, machine, on_hit, worn_head/chest/legs/feet "
                    + "for something he wears, or plain for one that does nothing)");
        }
        // EXCEPT for the shape whose whole meaning is that it has none. Deliberately keyed on the
        // shape rather than made optional in general: "he did not say what it does" and "it does
        // nothing on purpose" are different answers, and a blanket optional could not tell them
        // apart -- which is the argument every other required field here rests on.
        // A magic block with no effect is the same kind of answer: a new BLOCK he puts down and
        // breaks, and nothing more (a made-up block of his own came back as a keepsake he
        // could not place, because this list demanded an effect).
        if (str(args, "effect") == null
                && !ItemCatalogue.PLAIN.equals(str(args, "shape"))
                && !com.moddingfromamod.datapack.MagicBlock.SHAPE.equals(str(args, "shape"))) {
            absent.add("effect");
        }
        if (!args.has("count")) {
            absent.add("count (how many to give)");
        }
        return absent;
    }

    /**
     * Fill in everything the template needs that the child would never say.
     *
     * <p>Same rule as the effect catalogue's seconds-to-ticks: <b>if a value is a quantity a child
     * would never utter, do not ask for it</b> — ask for the one they would, and compute the rest.
     * A child says "8 seconds" and "as hard as you can", never 160 ticks or 1.30 blocks per tick.
     */
    /**
     * The {@code custom_data} each shape hangs its behaviour on — the ONLY thing the three shapes'
     * give-lines ever differed by.
     *
     * <p>It moved here from the three {@code shape_*.cbs} files when a wish became able to ask for a
     * FAMILY. A template cannot loop, so a family of twenty flavours cannot be twenty lines written
     * by hand in a file that knows nothing about how many there are. This is the same move
     * {@code summon.cbs} already made and documents: <i>"the summon line below is repeated COUNT
     * times, built by the tool rather than looped here"</i>.
     */
    /**
     * How many members a family may have.
     *
     * <p>Twenty-four because the driving wish is <i>"20 flavors of ice cream!!"</i> and a cap that
     * cannot answer the wish it was built for is not a cap, it is a refusal. It is bounded at all
     * because each member is a {@code /give} line in one function and a child who asks for "all of
     * them" should get a family, not a datapack the size of a phone book.
     */
    static final int MAX_VARIANTS = 24;

    /**
     * <b>A readable label for an item id</b> — {@code minecraft:cocoa_beans} → "Cocoa Beans".
     *
     * <p>Derived in Java rather than handed to the datapack as a translation key, because the key
     * is {@code item.minecraft.x} for an item and {@code block.minecraft.x} for a block and
     * guessing wrong renders the raw key at the child. He is told what he needs; a line reading
     * "You need item.minecraft.cocoa_beans!" is worse than no line at all.
     */
    static String readableItemName(String id) {
        if (id == null || id.isBlank()) {
            return "it";
        }
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        StringBuilder b = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return b.length() == 0 ? id : b.toString();
    }

    /**
     * <b>What the machine is MADE of, derived from the colour he already chose.</b>
     *
     * <p>Three blocks: a solid body, a tank you can see the colour through, and a lit header that
     * says the thing is on. The slushie spike hard-coded quartz, blue ice and a sea lantern; this
     * is the same silhouette with the child's own colour in it, so a red machine and a green one
     * are visibly different objects and neither costs the agent a decision.
     *
     * <p>The header stays a sea lantern on purpose. It is the part that reads as POWERED rather
     * than as decoration, and a lit block is the only way to say that without a redstone circuit.
     */
    public static String[] machineBody(int rgb) {
        String dye = nearestDye(rgb);
        return new String[]{
            "minecraft:" + dye + "_concrete",
            "minecraft:" + dye + "_stained_glass",
            "minecraft:sea_lantern",
        };
    }

    /**
     * The closest of Minecraft's sixteen dyes to an arbitrary colour, by squared distance in RGB.
     *
     * <p>A lookup rather than a judgement: the agent picks {@code colour} once, in the child's
     * words, and everything the machine is built from follows from it. Squared distance is crude
     * and is the right amount of crude here — the sixteen are far apart, and what matters is that
     * "red" lands on red rather than that two near-identical purples resolve perfectly.
     */
    public static String nearestDye(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        String best = "white";
        long bestD = Long.MAX_VALUE;
        for (Map.Entry<String, Integer> e : DYES.entrySet()) {
            int v = e.getValue();
            long dr = r - ((v >> 16) & 0xFF);
            long dg = g - ((v >> 8) & 0xFF);
            long db = b - (v & 0xFF);
            long d = dr * dr + dg * dg + db * db;
            if (d < bestD) {
                bestD = d;
                best = e.getKey();
            }
        }
        return best;
    }

    /**
     * <b>The second colour when the agent names none </b>: the main colour 55% of the way to
     * white, so a red octopus has a pink belly and a blue car pale-blue windows. A lighter shade
     * reads as the same thing's lighter part; a fixed cream would make every icon's second part the
     * same, and the main colour again would erase the part entirely.
     */
    public static int derivedAccent(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r += Math.round((255 - r) * 0.55f);
        g += Math.round((255 - g) * 0.55f);
        b += Math.round((255 - b) * 0.55f);
        return (r << 16) | (g << 8) | b;
    }

    /** Minecraft's sixteen dye colours, as the game renders them. */
    private static final Map<String, Integer> DYES = new LinkedHashMap<>();

    static {
        DYES.put("white", 0xF9FFFE);
        DYES.put("light_gray", 0x9D9D97);
        DYES.put("gray", 0x474F52);
        DYES.put("black", 0x1D1D21);
        DYES.put("brown", 0x835432);
        DYES.put("red", 0xB02E26);
        DYES.put("orange", 0xF9801D);
        DYES.put("yellow", 0xFED83D);
        DYES.put("lime", 0x80C71F);
        DYES.put("green", 0x5E7C16);
        DYES.put("cyan", 0x169C9C);
        DYES.put("light_blue", 0x3AB3DA);
        DYES.put("blue", 0x3C44AA);
        DYES.put("purple", 0x8932B8);
        DYES.put("magenta", 0xC74EBD);
        DYES.put("pink", 0xF38BAA);
    }

    // ofEntries, not of(): Map.of stops at ten pairs, and the shield made eleven.
    private static final Map<String, String> SHAPE_DATA = Map.ofEntries(
            Map.entry("held", "WishOnUse:\"%1$s:act\""),
            Map.entry("thrown", "WishThrowTag:\"%1$s_fly\",WishHitFunction:\"%1$s:act\""),
            Map.entry("placed", "WishPlaceTag:\"%1$s_here\""),
            // The SAME vanilla anchor `placed` uses -- tagged `_new` because the machine shape's
            // tick swaps that for `_built` the moment it has drawn the body, so a machine is built
            // exactly once however many ticks it survives.
            Map.entry(ItemCatalogue.MACHINE, "WishPlaceTag:\"%1$s_new\""),
            // the same anchor, swapped to `_built` once its body is drawn.
            Map.entry(com.moddingfromamod.datapack.MagicBlock.SHAPE, "WishPlaceTag:\"%1$s_new\""),
            // The melee trigger. Its own key rather than WishHitFunction's, because a wish can be
            // both thrown and swung and one key would make the two indistinguishable to
            // WishItemCatalogue.keyOf, which identifies an item BY its behaviour tag.
            Map.entry("on_hit", "WishOnHit:\"%1$s:act\""),
            // Their own keys, for the same reason on_hit has one.
            Map.entry("arrow", "WishArrowFunction:\"%1$s:act\""),
            // The moment he shoots, as him — the laser. Its own key, like arrow's.
            Map.entry("on_shot", "WishShotFunction:\"%1$s:act\""),
            // When he has FINISHED eating or drinking it, as him.
            Map.entry("on_consume", "WishConsumeFunction:\"%1$s:act\""),
            // When his shield actually BLOCKS a hit, as him.
            Map.entry("on_block", "WishBlockFunction:\"%1$s:act\""),
            // Where the real throwable LANDS, and when a long use is DONE.
            Map.entry("on_throw_land", "WishLandFunction:\"%1$s:act\""),
            Map.entry("on_use_done", "WishUseDoneFunction:\"%1$s:act\""),
            Map.entry("on_mine", "WishOnMine:\"%1$s:act\""),
            // NO TAG AT ALL. It renders `custom_data={}`, which is valid, and which
            // WishItemCatalogue.keyOf already anticipates as "a plain named keepsake" -- an item
            // with no behaviour to key on falls back to name + icon, which is exactly right for a
            // thing whose identity IS its name.
            Map.entry("plain", ""));

    /**
     * One {@code /give} line per member of the family, all sharing one behaviour.
     *
     * <p><b>A single item is a family of one</b>, and that is deliberate rather than a special case:
     * it makes the whole feature behaviour-preserving by construction, and
     * {@code GivesTest#oneVariantRendersExactlyWhatTheOldTemplateDid} pins the rendered bytes
     * against the line these templates used to carry.
     *
     * <p>What differs per member is what a child would name — <i>"chocolate, strawberry,
     * bubblegum"</i> — so it is the NAME and the COLOUR that vary and nothing else. Sharing the
     * behaviour is what keeps this one datapack, one compile and one row in his wish menu.
     */
    public static String gives(String shape, String feature, int icon, int count,
                        String hint, List<Variant> variants) {
        return gives(shape, feature, icon, count, hint, variants, ItemRecipient.ME, null);
    }

    /**
     * The same, but it may hand the thing to somebody else and say what it is made of.
     *
     * @param to    the RECIPIENT as the agent named it -- `me`, or an entity id already checked
     *              by {@link ItemRecipient}. The command in front of the item is derived from it.
     * @param parts one extra lore line naming what it is assembled from, or null
     */
    public static String gives(String shape, String feature, int icon, int count,
                        String hint, List<Variant> variants, String to, String parts) {
        return gives(shape, feature, icon, count, hint, variants, to, parts, ItemBase.WISH_ITEM);
    }

    /**
     * The {@code custom_data} an item carries: WHICH one it is, then WHAT it does.
     *
     * <p>The identity comes first and is unconditional. Before it, a {@code plain} item
     * rendered {@code custom_data={}} — nothing at all — so two named keepsakes were
     * indistinguishable to anything that matches by item, and every made item was indistinguishable
     * to anything that matches by id. That is one bug in four hats: {@code convert} could not take
     * a made item as its INPUT, {@code rich} and {@code give_items} could only hand over a blank,
     * and {@code WishItemCatalogue.keyOf} had nothing stable to key a plain item on, so changing
     * its icon made a second row in his menu.
     *
     * <p><b>Both parts, never just the identity</b>, and {@code ItemYield.clearPredicate} is the
     * proof: a {@code yield} renders the leftovers with the SAME feature id, so an item and what it
     * turns into share a {@code WishItem} tag. Only the behaviour tag tells the pizza from its
     * slices. Any predicate matching a made item must therefore match on what this returns whole,
     * not on the identity alone.
     */
    static String customData(String shape, String feature) {
        // WORN: which slot it lives in. Not a function to run -- the datapack's own clock
        // watches the armour slot -- but WishItemCatalogue.keyOf and a grown-up reading a stack
        // both need to see that this is something he wears, and where.
        String behaviour = com.moddingfromamod.datapack.WornShape.isWorn(shape)
                ? com.moddingfromamod.item.WishItem.WORN + ":\""
                        + com.moddingfromamod.datapack.WornShape.slot(shape) + "\""
                : String.format(SHAPE_DATA.getOrDefault(shape, ""), feature);
        String id = com.moddingfromamod.item.WishItem.WISH_FEATURE + ":\"" + feature + "\"";
        return behaviour.isEmpty() ? id : id + "," + behaviour;
    }

    /**
     * ONE item, as a command argument: the id and its whole component list, no verb.
     *
     * <p>Extracted from {@link #gives} because a {@code convert} recipe has to hand over an item the
     * child already had made — and an id alone cannot do that. Every item made from scratch is
     * {@code moddingfromamod:wish_item}; the name, the icon and the colour are components on it. So
     * <i>"a machine that turns beans into my Hot Coffee"</i> is expressible only if the give line
     * carries the same components the item's own give line carries, which is this string.
     */
    // PUBLIC so a gate can drive the real give line rather than reimplement it. A fixture that
    // reimplements the path under test tests its reimplementation (docs/CAMERA.md §9), and the
    // carrier route's whole risk lives in the JOIN between ItemBase.id and wearsOurIcons — which
    // only this method performs. Visibility only: the schema, the prompt and AgentContractDumpTest
    // are untouched.
    /**
     * What {@code wish_item} stacks to, and therefore what a carrier must be told to stack to.
     *
     * <p>Vanilla's default for {@code new Item.Properties()} — which is exactly how
     * {@code ModItems.WISH_ITEM} is registered. Named rather than inlined because it is a claim
     * about the OTHER route, and {@code VanillaCarrierAbilityGameTest} is where it is checked.
     */
    // PUBLIC so the gate can assert PARITY against the same number the renderer writes, rather
    // than a literal 64 of its own — two copies of "what our item stacks to" is the shape that
    // lets one drift while the other keeps passing.
    public static final int WISH_ITEM_STACKS_TO = 64;

    public static String itemArg(Variant v, int icon, String hint, String parts, String baseItem,
                          String data) {
        return itemArg(v, icon, hint, parts, baseItem, data, null);
    }

    /**
     * The same, knowing the SHAPE — which only a worn thing needs: it has to say where it
     * goes ({@code equippable}), what it is drawn as, and what armour it gives, and each of those
     * is a component the other shapes never write.
     */
    public static String itemArg(Variant v, int icon, String hint, String parts, String baseItem,
                          String data, String shape) {
        return itemArg(v, icon, hint, parts, baseItem, data, shape, null);
    }

    /**
     * The same, with the OVERLAY its icon wears — {@code IconOverlays.forEffects} of what
     * it does, or null for the plain icon. Only our own icon library composes; a real golden axe
     * and a worn thing drawn as leather have no icon of ours to draw it over.
     */
    public static String itemArg(Variant v, int icon, String hint, String parts, String baseItem,
                          String data, String shape, String overlay) {
        // "does this base wear our icon library", NOT "is this our registered item" — a vanilla
        // carrier is the second answer and needs the first. See ItemBase.wearsOurIcons (A1).
        boolean ours = ItemBase.wearsOurIcons(baseItem);
        boolean onCarrier = ItemBase.isWishItem(baseItem)
                && com.moddingfromamod.datapack.VanillaCarrier.isCarrierId(ItemBase.id(baseItem));
        String wornSlot = com.moddingfromamod.datapack.WornShape.isWorn(shape)
                ? com.moddingfromamod.datapack.WornShape.slot(shape) : null;
        // A WORN THING MADE FROM SCRATCH: the carrier, told where it goes and drawn on him as
        // leather in his colour. Its hand icon is the matching vanilla leather piece rather than
        // the icon library's sprite, because a give line may name item_model only ONCE and the
        // piece is what it looks like on the body too. The number stays, for the catalogue and for
        // a client translated down to 1.21.1 (which drops item_model and draws the number).
        boolean wornScratch = wornSlot != null && onCarrier;
        StringBuilder arg = new StringBuilder(ItemBase.id(baseItem)).append('[');
        // ICON AND TINT ARE OURS AND NOBODY ELSE'S. custom_model_data selects out of the wish
        // icon library and dyed_color tints a greyscale sprite; on a real golden axe both are
        // silently meaningless, and writing them would have the tool report a colour he cannot
        // see. The NAME, the lore, the behaviour tag and the properties all still apply.
        if (ours) {
            // The number and, on 1.21.4, the stack's own item_model -- one stamp, see
            // ModelData.components.
            // a worn thing whose icon is a DRAWN picture (a wizard hat, a crown) is that picture in his hand
            // and leather on his body; one of the 24 plain shapes (a helmet) stays the leather piece in both.
            boolean drawnIcon = com.moddingfromamod.item.IconSprites.drawn().stream().anyMatch(d -> d.number() == icon);
            arg.append(wornScratch && drawnIcon
                            ? com.moddingfromamod.compat.ModelData.components(icon, overlay, v.accentRgb())
                                    + "," + com.moddingfromamod.datapack.WornShape.scratchEquippable(wornSlot)
                            : wornScratch
                            ? "minecraft:custom_model_data=" + com.moddingfromamod.compat.ModelData.snbt(icon)
                                    + "," + com.moddingfromamod.datapack.WornShape.scratchComponents(wornSlot)
                            : com.moddingfromamod.compat.ModelData.components(icon, overlay,
                                    v.accentRgb()))
               .append(",").append(com.moddingfromamod.compat.ItemSyntax.dyedColor(v.rgb())).append(",");
        } else if (v.drawn()) {
            // a REAL item drawn as the icon he chose: the number and the item_model (ModelData.components), in his
            // colour. A worn real piece keeps its own equippable and armour (the body still wears leather).
            arg.append(com.moddingfromamod.compat.ModelData.components(icon, overlay, v.accentRgb()))
               .append(",").append(com.moddingfromamod.compat.ItemSyntax.dyedColor(v.rgb())).append(",");
        } else if (v.paint() && com.moddingfromamod.pack.TintedItems.modelFor(baseItem) != null) {
            // A REAL ITEM IN HIS COLOUR SCHEME. Still the real item (it mines, shoots, is eaten as vanilla's),
            // drawn by its tinted model: the part a scheme paints is a greyscale layer the dye tints, the rest
            // (a tool's stick) is vanilla's. The colour is dyed_color, the ONE channel both editions read: Java's
            // definition tints by `minecraft:dye`, Geyser writes it as the Bedrock item's customColor.
            arg.append("minecraft:item_model='").append(com.moddingfromamod.pack.TintedItems.modelFor(baseItem))
               .append("',").append(com.moddingfromamod.pack.TintedItems.dyedColor(v.rgb())).append(",");
        } else if (wornSlot != null) {
            // BUILT ON A REAL ITEM: its own look and armour already are the answer. Only a base
            // that does not already go in that slot is told to (a pumpkin on the head, a diamond
            // helmet asked for as boots would be refused by nobody and worn by nobody).
            String equips = com.moddingfromamod.compat.Wearables.slotOf(baseItem);
            if (!wornSlot.equals(equips)) {
                arg.append("minecraft:equippable={slot:\"").append(wornSlot).append("\"},");
            }
        }
        // WHAT THIS ONE IS, as opposed to what it does -- rendered EARLY because the carrier
        // neutralisation below has to know whether this already writes an attribute_modifiers
        // component. Appended in its original place further down; only the computation moves.
        // A worn thing from scratch keeps LEATHER's armour for its slot, merged into the one
        // attribute_modifiers component the same way keeps a real item's own (below).
        String keep = wornScratch
                ? com.moddingfromamod.datapack.WornShape.scratchArmourModifier(wornSlot,
                        com.moddingfromamod.compat.Attrs.generic("armor"))
                : ownModifiers(baseItem);
        String properties = ItemProperties.render(v.props(), keep);
        // NEUTRALISE THE CARRIER'S OWN ATTRIBUTES. A vanilla carrier brings whatever its item
        // means: leather horse armour contributes +3 armor on the BODY slot with
        // showInTooltip=true, so an un-neutralised wished thing reads "When on Body: +3 Armor"
        // under a name like Hot Coffee. Measured, not assumed -- the first check read the raw
        // component and passed, because ArmorItem supplies its modifiers through
        // getDefaultAttributeModifiers instead; an explicitly PRESENT component is what wins.
        //
        // Written only for a carrier, never for the mod item: wish_item has no attributes to
        // neutralise, and adding a component to its give line would change the shipped path.
        // TRANSITIONAL — carrier spike. Not production. See docs/VANILLA_CARRIER_TRANSITIONAL.md
        // SEAM: delete with the carrier — a wish_item has no attributes to neutralise.
        // GATED ON THE BASE THE CALLER PASSED, not on the id that comes out — and that distinction
        // is a bug that shipped for an afternoon. `base_item` takes ANY item id, so a child can
        // wish for something built on leather horse armour ("magic horse armour"), and asking
        // "is the resolved id the carrier" is TRUE for that as well as for our substitution. The
        // two are indistinguishable from the answer, so with the route OFF -- the shipped
        // configuration -- a deliberate horse-armour wish silently lost its armour and its
        // tooltip. It was named right, rendered right, and did not protect the horse.
        //
        // `isWishItem(baseItem)` asks the question that actually matters: is this OUR item, which
        // the carrier may be standing in for. False for anything the child chose himself.
        //
        // ⚠ AND ONLY WHEN NOTHING ELSE WRITES ONE. A component may appear at most once in a give
        // line -- "Item component 'minecraft:attribute_modifiers' was repeated, but only one value
        // can be specified" -- and the game refuses the WHOLE command, so the child gets nothing
        // at all. `attack_damage` writes that same component, which means every wish that asked
        // for something to hit harder ("a sword that hits really hard") failed outright on this
        // route. Caught by ItemPropertiesGameTest.everyGiveLineThisToolCanRenderIsAcceptedByTheGame
        // (named GiveLineMatrixGameTest here once; no class by that name ever existed), which
        // drives every renderable give line past the real parser; no unit test could see it,
        // because the refusal is Brigadier's.
        //
        // Skipping it there is CORRECT rather than a compromise: the neutralisation works by
        // making the component non-empty (see the NeoForge note below), and a real attack_damage
        // entry is already non-empty. One is as good as two, and two is a broken command.
        if (properties.contains("minecraft:attribute_modifiers")) {
            // nothing to do: the properties already suppress the carrier's own modifiers
        } else if (wornScratch) {
            // Non-empty (see the NeoForge note below), so the carrier's +3 on the BODY is gone and
            // leather's own armour on HIS slot is what he gets.
            arg.append(com.moddingfromamod.compat.ItemSyntax.attributeModifiers(keep)).append(",");
        } else if (ItemBase.isWishItem(baseItem)
                && com.moddingfromamod.datapack.VanillaCarrier.isCarrierId(ItemBase.id(baseItem))) {
            // NON-EMPTY, and that is the whole trick. Read out of NeoForge rather than assumed --
            // IItemStackExtension.getAttributeModifiers does
            //     if (component.modifiers().isEmpty()) component = item.getDefaultAttributeModifiers()
            // so an EMPTY component falls straight through to the carrier's +3 armor. Writing one
            // inert entry (amount 0, on a slot a player cannot use) is what makes the component win,
            // and show_in_tooltip hides the line it would otherwise draw.
            arg.append(com.moddingfromamod.compat.ItemSyntax.attributeModifiers("{"
                            + "type:\"" + com.moddingfromamod.compat.Attrs.generic("armor") + "\","
                            + "id:\"" + com.moddingfromamod.ModdingFromAModMod.MODID + ":carrier_inert\","
                            + "amount:0,operation:\"add_value\",slot:\"body\"}"))
               .append(",").append(com.moddingfromamod.compat.ItemSyntax.hideLines("minecraft:attribute_modifiers"))
               .append(",");
        }
        // AND IT MUST STACK LIKE OUR OWN ITEM DID. wish_item is `new Item.Properties()`, so it
        // stacks to 64; leather horse armour stacks to 1. Nothing wrote max_stack_size unless the
        // agent asked for it, so on the carrier route a `thrown` wish for a handful -- which is the
        // schema's own advice, "a handful for something thrown, because throwing the only one you
        // have ends the game" -- arrived as four separate hotbar slots instead of one stack of four.
        //
        // Not caught by the flip's own gates, and the near-miss is the instructive part:
        // VanillaCarrierAbilityGameTest.aCarrierCanBeMadeToStackLikeTheModItem already asserts a
        // carrier CAN be told to stack, and asserts horse armour's native 1 as its premise. It
        // proved the MECHANISM and nobody asked whether the tool WIRES it -- the same shape as the
        // resolved-base bug this route shipped for an afternoon.
        //
        // Written only when the properties did not, so an item deliberately asked to be
        // one-of-a-kind stays one-of-a-kind. Safe on this carrier specifically because horse
        // armour has no durability: ItemStack refuses MAX_DAMAGE together with MAX_STACK_SIZE > 1
        // and the give then fails SILENTLY, which is why the sibling test asserts that too.
        if (!properties.contains("minecraft:max_stack_size")
                && ItemBase.isWishItem(baseItem)
                && com.moddingfromamod.datapack.VanillaCarrier.isCarrierId(ItemBase.id(baseItem))) {
            // A WORN thing stacks to ONE, like any armour: a stack of 64 boots equips all 64 as
            // one pair, and hands none of the others back.
            arg.append("minecraft:max_stack_size=").append(wornScratch ? 1 : WISH_ITEM_STACKS_TO)
               .append(',');
        }
        // A WISHED FOOD CAN ALWAYS BE EATEN (#173). An on_consume wish rides a real food, and that food's own
        // minecraft:food cannot be eaten on a full hunger bar, so a heal he wished for did nothing when he was hurt
        // and not hungry. The same food, its nutrition and saturation, with can_always_eat; a drink has no food and
        // is always drunk. Written only when nothing else writes the component (it may appear once).
        if (!properties.contains("minecraft:food")) {
            String food = alwaysEdibleFood(baseItem, data);
            if (!food.isEmpty()) {
                arg.append(food).append(',');
            }
        }
        // 1.21.5+: text in a component is SNBT, so the JSON object goes in UNQUOTED (ItemSyntax)
        arg.append("minecraft:custom_name={\"text\":\"").append(jsonSafe(v.name()))
         .append("\",\"color\":\"").append(String.format("#%06X", v.rgb()))
         .append("\",\"bold\":true,\"italic\":false}")
         .append(",minecraft:lore=[{\"text\":\"").append(jsonSafe(hint))
         .append("\",\"color\":\"gray\",\"italic\":false}")
         // WHAT IT IS MADE OF, on its own line under the hint. The lore is where he reads it;
         // an item assembled from things he chose that says nothing about them is the same item
         // he would have got without asking.
         .append(parts == null ? "" : ",{\"text\":\"" + jsonSafe(parts)
                 + "\",\"color\":\"gray\",\"italic\":false}")
         .append("]")
         // THE GLINT, except on a COMPOSED icon: there the sparkle backing says "this
         // is magic", and the glint is additive over every layer — photographed on a stock
         // 1.21.4 client it lit the whole backing disc into a pink ball that hid the item. A real
         // item he named, a worn piece drawn as leather, and 1.21.1 (no backing) keep it.
         // a real item drawn in his colour scheme takes no forced glint: photographed on Java, the glint
         // washed every scheme toward the same purple (an Ocean pickaxe and a Lava sword read alike). The colour is
         // what marks it as his; an enchanted one still glints by itself.
         .append(v.drawn() || v.paint() && com.moddingfromamod.pack.TintedItems.modelFor(baseItem) != null ? ""
                 : ours && !wornScratch && com.moddingfromamod.compat.ModelData.COMPOSED_ICONS
                 // An ENCHANTED composed icon would glint by itself, so it is told not to.
                 ? (properties.contains("minecraft:enchantments")
                         ? ",minecraft:enchantment_glint_override=false" : "")
                 : ",minecraft:enchantment_glint_override=true")
         // WHAT THIS ONE IS, as opposed to what it does. Per MEMBER, because the wish that
         // needed the axis is "small medium and LARGE cups" -- one size for the whole family
         // would make it one cup in three colours, which is the thing it was already.
         .append(properties)
         .append(",minecraft:custom_data={")
         // TRANSITIONAL — carrier spike. Not production. See docs/VANILLA_CARRIER_TRANSITIONAL.md  SEAM: delete with the carrier.
         // THE CARRIER MARK, written only when we are substituting -- see WishItem.CARRIER. It is
         // the one moment the intent is knowable: here the raw base says "made from scratch" and
         // the resolved id says "a vanilla item", and nothing downstream can reconstruct that pair.
         .append(ItemBase.isWishItem(baseItem)
                 && com.moddingfromamod.datapack.VanillaCarrier.isCarrierId(ItemBase.id(baseItem))
                 ? com.moddingfromamod.item.WishItem.CARRIER + ":true," : "")
         .append(data)
         // Properties that are data rather than a component (a bow's projectile, a bucket that
         // stays full): read by WishShapeEvents off the stack, like every behaviour key here.
         .append(ItemProperties.dataEntries(v.props())).append("}]");
        return arg.toString();
    }

    /**
     * <b>What the BASE ITEM already does, as SNBT, so a property does not delete it.</b>
     *
     * <p>NeoForge's {@code IItemStackExtension.getAttributeModifiers()} uses the component when it
     * is non-empty and skips the item's own defaults entirely, so writing one modifier onto a
     * diamond sword leaves a sword with only that modifier — no {@code attack_speed}, swinging at
     * the base 4.0/sec instead of 1.6. On an item whose modifiers are not just damage and speed it
     * deletes them outright, which is exactly the "recovers what components express and silently
     * drops the rest" failure is trying to avoid.
     *
     * <p>Read off a fresh stack rather than from {@code Item.getDefaultAttributeModifiers} directly,
     * because the stack accessor is the one the GAME uses and it runs NeoForge's own
     * {@code computeModifiedAttributes} hook — so a mod that adds modifiers through the event is
     * included, and a mod that adds them any other way is at least not made worse.
     *
     * @return the entries without their brackets, or empty for an item made from scratch (which
     *         has nothing to lose) or one that carries no modifiers
     */
    /**
     * The {@code minecraft:food} an eaten wish carries (#173): its base's own food with {@code can_always_eat:true},
     * or empty when it is not eaten ({@code data} has no {@link com.moddingfromamod.item.WishItem#CONSUME_FN}), its
     * base has no food (a drink) or can always be eaten already, or there is no game to ask (a unit test, as
     * {@link #ownModifiers}).
     */
    static String alwaysEdibleFood(String baseItem, String data) {
        if (baseItem == null || data == null || !data.contains(com.moddingfromamod.item.WishItem.CONSUME_FN)) {
            return "";
        }
        try {
            net.minecraft.resources.ResourceLocation key = net.minecraft.resources.ResourceLocation.tryParse(
                    ItemBase.id(baseItem).trim().toLowerCase(java.util.Locale.ROOT));
            if (key == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key)) {
                return "";
            }
            var food = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(key).components()
                    .get(net.minecraft.core.component.DataComponents.FOOD);
            return food == null || food.canAlwaysEat() ? "" : alwaysEdibleFood(food.nutrition(), food.saturation());
        } catch (Throwable outsideTheGame) {
            return "";   // no registry (a unit test): the line as before; the game's own path is the GameTest's
        }
    }

    /** The component itself, as a give line writes it. Pure. */
    static String alwaysEdibleFood(int nutrition, float saturation) {
        return "minecraft:food={nutrition:" + nutrition + ",saturation:" + saturation + "f,can_always_eat:true}";
    }

    static String ownModifiers(String baseItem) {
        if (baseItem == null || ItemBase.isWishItem(baseItem)) {
            return "";
        }
        try {
            return modifiersOf(baseItem);
        } catch (Throwable outsideTheGame) {
            // ⚠ CAUGHT, AND THE REASON IS NOT DEFENSIVENESS. Reaching BuiltInRegistries triggers
            // Minecraft's registry bootstrap, which throws ExceptionInInitializerError -- an
            // ERROR, not an Exception -- in a plain JUnit test. `gives` is called directly by the
            // unit suite (ConvertRecipeTest went red on exactly this), so the renderer has to
            // survive having no game around it.
            //
            // Returning empty means the UNIT tests render the pre-#385 line and can never see the
            // merge. That is stated rather than hidden: the merge is a question about what the
            // GAME resolves, so its gate is ItemPropertiesGameTest.makingARealItemStrongerKeeps-
            // WhatElseItDid, which reads the modifiers back through forEachModifier. A unit test
            // could not have asked it in the first place.
            return "";
        }
    }

    private static String modifiersOf(String baseItem) {
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.resources.ResourceLocation.tryParse(
                        baseItem.trim().toLowerCase(java.util.Locale.ROOT));
        if (key == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key)) {
            return "";
        }
        var mods = new net.minecraft.world.item.ItemStack(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(key))
                .getAttributeModifiers();
        StringBuilder out = new StringBuilder();
        for (var entry : mods.modifiers()) {
            var attrId = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE
                    .getKey(entry.attribute().value());
            if (attrId == null) {
                // A modifier we cannot name is dropped rather than rendered as a broken id, which
                // would fail the whole give line and hand the child nothing at all.
                continue;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append("{type:\"").append(attrId)
               .append("\",id:\"").append(entry.modifier().id())
               .append("\",amount:").append(entry.modifier().amount())
               .append(",operation:\"").append(entry.modifier().operation().getSerializedName())
               .append("\",slot:\"").append(entry.slot().getSerializedName()).append("\"}");
        }
        return out.toString();
    }

    /**
     * The same, built on any item in the game rather than only on ours.
     *
     * @param baseItem a full item id, already checked by {@link ItemBase}; the wish item by default
     */
    public static String gives(String shape, String feature, int icon, int count,
                        String hint, List<Variant> variants, String to, String parts,
                        String baseItem) {
        return gives(shape, feature, icon, count, hint, variants, to, parts, baseItem, null);
    }

    /** The same, every member's icon wearing {@code overlay} (null for none). */
    public static String gives(String shape, String feature, int icon, int count,
                        String hint, List<Variant> variants, String to, String parts,
                        String baseItem, String overlay) {
        String data = customData(shape, feature);
        boolean ours = ItemBase.isWishItem(baseItem);
        StringBuilder b = new StringBuilder();
        for (Variant v : variants) {
            if (b.length() > 0) {
                b.append('\n');
            }
            // THE VERB IS THE RECIPIENT'S, not always /give -- see ItemRecipient.command. Built by
            // assembling the item argument first and handing it over, because which command can
            // carry it is exactly the thing that differs.
            b.append("    ").append(ItemRecipient.command(
                    to, itemArg(v, icon, hint, parts, baseItem, data, shape, overlay), count));
        }
        return b.toString();
    }

    /**
     * Take the thing away, then hand over what it became, then say so.
     *
     * <p><b>⚠ GIVEN FIRST, THEN CLEARED, AND THE OPPOSITE ORDER LOSES EVERYTHING.</b> The obvious
     * order is to clear first "so the slot the pizza was in is free for the slices". Measured in a
     * real client: he ends up with <b>no pizza and no slices</b>, while the chat cheerfully says
     * <i>"You got 8x Pizza Slice!"</i>.
     *
     * <p>The reason is the item-use path itself. {@code WishItem.use} returns
     * {@code InteractionResultHolder.sidedSuccess(stack, …)} and vanilla writes that returned stack
     * back into the hand slot afterwards. Clearing first empties the hand slot, the give lands the
     * slices straight into it because it is now the first free slot, and then the write-back puts
     * the emptied stack over the top of them. The slices existed for the rest of that tick.
     *
     * <p>Giving first is safe on the other side too: the pizza still occupies the hand, so the
     * slices go elsewhere, and {@code /give} drops what will not fit at his feet rather than
     * silently dropping it from existence. A full bag costs him a walk, not the wish.
     *
     * <p>The yielded thing is rendered by {@link #gives} as a {@code plain} item, so it gets the
     * same icon, colour, name and lore every other wish item has, and none of that had to be
     * written twice.
     */
    private static String yieldBlock(String feature, String shape, int icon, JsonObject y,
                                     String base) {
        Integer rgb = colourOf(y.get("colour").getAsString());
        String name = y.get("name").getAsString().trim();
        int count = ItemYield.count(y);
        int yieldIcon = ItemCatalogue.ICONS.get(y.get("icon").getAsString());
        String data = customData(shape, feature);
        StringBuilder b = new StringBuilder();
        b.append(gives("plain", feature, yieldIcon, count, "It's yours to keep.",
                List.of(new Variant(name, rgb == null ? 0xFFFFFF : rgb)))).append('\n');
        b.append("    /clear @s ")
         .append(ItemYield.clearPredicate(shape, feature, data, base)).append(" 1\n");
        b.append("    /tellraw @s {\"text\":\"")
         .append(jsonSafe("You got " + count + "x " + name + "!"))
         .append("\",\"color\":\"").append(String.format("#%06X", rgb == null ? 0xFFFFFF : rgb))
         .append("\"}");
        return b.toString();
    }

    /**
     * The family this wish asks for, or a family of one.
     *
     * <p>Refuses rather than trims, on the same argument as every other parameter here: a wish for
     * thirty flavours quietly answered with twenty-four is a wrong answer the child discovers by
     * counting, and nothing in the transcript would say so.
     *
     * @throws IllegalArgumentException with a message written to the agent, naming what to do next
     */
    static List<Variant> parseVariants(JsonObject args, String name, int rgb) {
        JsonObject shared = args.has("properties") && args.get("properties").isJsonObject()
                ? args.getAsJsonObject("properties") : null;
        if (!args.has("variants") || args.get("variants").isJsonNull()) {
            return List.of(new Variant(name, rgb, shared));
        }
        if (!args.get("variants").isJsonArray()) {
            throw new IllegalArgumentException("variants must be a LIST of {name, colour} objects, "
                    + "e.g. [{\"name\":\"Vanilla Ice Cream\",\"colour\":\"white\"}].");
        }
        var arr = args.getAsJsonArray("variants");
        if (arr.isEmpty()) {
            return List.of(new Variant(name, rgb, shared));
        }
        if (arr.size() > MAX_VARIANTS) {
            throw new IllegalArgumentException("variants has " + arr.size() + " members and the most "
                    + "that can be made in one wish is " + MAX_VARIANTS + ". Ask him which "
                    + MAX_VARIANTS + " he wants most rather than picking for him.");
        }
        List<Variant> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < arr.size(); i++) {
            if (!arr.get(i).isJsonObject()) {
                throw new IllegalArgumentException("variants[" + i + "] must be an object with a "
                        + "name and a colour, not " + arr.get(i) + ".");
            }
            JsonObject v = arr.get(i).getAsJsonObject();
            String vn = v.has("name") ? v.get("name").getAsString().trim() : "";
            if (vn.isEmpty()) {
                throw new IllegalArgumentException("variants[" + i + "] has no name. Every one of "
                        + "them is a thing he will hold and read, so each needs its own.");
            }
            if (!seen.add(vn.toLowerCase(Locale.ROOT))) {
                // Two identically-named members are indistinguishable in his hand and in his menu,
                // which is the failure "20 flavors" already had in a different form.
                throw new IllegalArgumentException("variants has two members called '" + vn + "'. "
                        + "He cannot tell them apart, so give each its own name.");
            }
            Integer vc = v.has("colour") ? colourOf(v.get("colour").getAsString()) : null;
            if (vc == null) {
                throw new IllegalArgumentException("variants[" + i + "] ('" + vn + "') needs a "
                        + "colour, as #RRGGBB or one of: " + String.join(", ", COLOURS.keySet())
                        + ". The icons are drawn in grey and tinted, so without it every member of "
                        + "the set looks the same.");
            }
            // A MEMBER'S PROPERTIES OVERRIDE THE WISH'S, ONE KEY AT A TIME. Not a whole-object
            // replace: "make them all stack to one, and the large one hits hard" would otherwise
            // drop the shared stack size from the member that named a second property, and the
            // family would come back inconsistent with nothing saying so. Same argument, and the
            // same failure, as Spec.mergeParams.
            JsonObject vp = shared == null ? null : shared.deepCopy();
            if (v.has("properties") && v.get("properties").isJsonObject()) {
                JsonObject own = v.getAsJsonObject("properties");
                if (vp == null) {
                    vp = own.deepCopy();
                } else {
                    for (String k : own.keySet()) {
                        vp.add(k, own.get(k));
                    }
                }
            }
            List<String> bad = ItemProperties.refusals(vp);
            if (!bad.isEmpty()) {
                throw new IllegalArgumentException("variants[" + i + "] ('" + vn + "'): "
                        + String.join(" ", bad));
            }
            out.add(new Variant(vn, vc, vp));
        }
        return out;
    }

    /**
     * One member of a family, and the things that may differ between them.
     *
     * @param props the PROPERTIES this member carries — already merged with the wish's top-level
     *              ones by {@link #parseVariants}, so the give builder never has to know there was
     *              a default. Null for a member that carries none.
     */
    public record Variant(String name, int rgb, JsonObject props, Integer accent, boolean paint, boolean drawn) {

        /** an earlier issue's members; {@code drawn} (a real item drawn as its icon) off. */
        public Variant(String name, int rgb, JsonObject props, Integer accent, boolean paint) {
            this(name, rgb, props, accent, paint, false);
        }

        /**
         * A member as every call site made it: a real item ({@code base_item}) is drawn as vanilla draws it.
         * {@code paint} (the Wish Builder's {@code tint}) draws a real item that has a tinted model
         * ({@code pack.TintedItems}) in {@code rgb} instead.
         */
        public Variant(String name, int rgb, JsonObject props, Integer accent) {
            this(name, rgb, props, accent, false);
        }

        /** This member, drawn in its colour on a real item that has a tinted model. */
        public Variant painted() {
            return new Variant(name, rgb, props, accent, true, drawn);
        }

        /**
         * This member, a REAL item (base_item) drawn as its ICON (`look`): a cookie he eats as a cookie that looks
         * like a cupcake, a leather helmet he wears that is a crown in his hand. What the real item DOES is untouched.
         */
        public Variant drawnAsIcon() {
            return new Variant(name, rgb, props, accent, paint, true);
        }

        /** A member with no second colour of its own — it is derived from {@code rgb}. */
        public Variant(String name, int rgb, JsonObject props) {
            this(name, rgb, props, null);
        }

        /** The second colour this member's icon wears: the one asked for, or a lighter {@code rgb}. */
        public int accentRgb() {
            return accent != null ? accent : derivedAccent(rgb);
        }

        /**
         * A member with no properties of its own — every call site that predates the axis.
         *
         * <p>Kept as a real constructor rather than made forty callers pass {@code null}: the
         * overwhelming majority of items carry no properties, and reading {@code null} at each of
         * those sites teaches nothing.
         */
        public Variant(String name, int rgb) {
            this(name, rgb, null);
        }
    }

    /**
     * What {@code summon}'s {@code AS} means, as literal NBT. A LOOKUP, never interpolation.
     *
     * <p>Ordered so the refusal message lists them in the order a child would reach for them, and
     * {@code plain} is first because it is the answer for almost every spawn egg.
     */
    private static final Map<String, String> SUMMON_AS = new LinkedHashMap<>(Map.of());

    static {
        SUMMON_AS.put("plain", "");
        SUMMON_AS.put("baby", " {IsBaby:1b}");
        SUMMON_AS.put("charged", " {powered:1b}");
    }

    /**
     * What {@code aggro}'s {@code FIGHTS} means, as a literal selector filter. A LOOKUP, never
     * interpolation — the same rule {@link #SUMMON_AS} follows, and it matters more here because
     * the result is spliced INSIDE a selector's bracket, where one comma would end the filter and
     * start an argument the child never asked for.
     *
     * <p>The two answers build opposite toys — a mob that hunts HIM versus two mobs brawling with
     * each other — which is why {@code FIGHTS} has no default. A default here is a coin flip
     * dressed as a decision.
     */
    private static final Map<String, String> AGGRO_FIGHTS = new LinkedHashMap<>(Map.of());

    static {
        AGGRO_FIGHTS.put("me", "type=minecraft:player");
        AGGRO_FIGHTS.put("mob", "type=!minecraft:item,type=!minecraft:snowball,"
                + "type=!minecraft:armor_stand,type=!minecraft:player");
    }

    /**
     * The game's own registries, asked whether an id names a real thing.
     *
     * <p>Both, because the same method serves {@code rich}'s ITEM and {@code place}'s BLOCK, and a
     * block is not an item — {@code minecraft:water} is a block and not an item, so asking only the
     * item registry would refuse a perfectly good {@code place}.
     */
    /** A namespaced id, and nothing that could carry a second command. */
    private static final java.util.regex.Pattern ENTITY_OR_ITEM_ID =
            java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** A comma-separated list, trimmed, blanks dropped. */
    private static List<String> listOf(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String x : raw.split(",")) {
                if (!x.isBlank()) {
                    out.add(x.trim());
                }
            }
        }
        return out;
    }

    /**
     * The live answer to "is this a block, an item, a tag" — what turn_into needs to know
     * which forms a name has. Air is a block and never an item, whatever the item registry's default says.
     */
    static final com.moddingfromamod.datapack.TurnInto.Kinds LIVE_KINDS =
            new com.moddingfromamod.datapack.TurnInto.Kinds() {
                @Override
                public boolean isBlock(String id) {
                    var key = net.minecraft.resources.ResourceLocation.tryParse(id);
                    return key != null && net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(key);
                }

                @Override
                public boolean isItem(String id) {
                    var key = net.minecraft.resources.ResourceLocation.tryParse(id);
                    return key != null && !id.endsWith(":air") && !id.endsWith("_air")
                            && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key);
                }

                @Override
                public boolean isBlockTag(String tag) {
                    var key = net.minecraft.resources.ResourceLocation.tryParse(tag.substring(1));
                    return key != null && net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, key))
                            .isPresent();
                }

                @Override
                public boolean isItemTag(String tag) {
                    var key = net.minecraft.resources.ResourceLocation.tryParse(tag.substring(1));
                    return key != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, key))
                            .isPresent();
                }
            };

    public static final com.moddingfromamod.datapack.RegistryExists LIVE_REGISTRY = id -> {
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.resources.ResourceLocation.tryParse(id);
        return key != null
                && (net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key)
                    || net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(key));
    };

    /**
     * <b>An item reference, as the thing a {@code /give} can carry.</b>
     *
     * <p>A real item is its own id. An item the child had MADE is
     * {@code moddingfromamod:wish_item} plus the whole component list its own give line carries —
     * its name, its icon, its colour and its identity — because that is the only way to hand over
     * <i>his</i> Hot Coffee rather than a nameless blank. Call {@code whyUnusable} first.
     */
    public static String giveArg(ItemRef ref) {
        com.moddingfromamod.item.ItemSpecStore.Spec made = ref.spec();
        if (made == null) {
            return ref.text();
        }
        Integer named = colourOf(made.colour());
        int rgb = named == null ? 0xFFFFFF : named;
        Integer icon = ItemCatalogue.icon(made.icon());
        // THE FIRST MEMBER OF A FAMILY, not a blend of them: a machine hands over one thing, and
        // first is the same rule make_item uses when it names the feature after one of them.
        List<Variant> family = parseVariants(
                made.variants() == null || made.variants().isEmpty() ? new JsonObject()
                        : variantsWrapper(made.variants()), made.name(), rgb);
        Variant v = family.isEmpty() ? new Variant(made.name(), rgb) : family.get(0);
        if (made.properties() != null && !made.properties().isEmpty() && v.props() == null) {
            v = new Variant(v.name(), v.rgb(), made.properties());
        }
        // ITS OWN BASE, not always the wish item. A "Roasted Coffee Beans" built on
        // minecraft:dried_kelp IS dried kelp wearing a name, and rendering it as a wish_item hands
        // the child something no other machine in the chain can take. Measured at Gate C½: a real
        // roaster→grinder chain came out broken for exactly this, and every unit and GameTest was
        // green because all of them used items made from scratch.
        // ⚠ THE **RAW** BASE, NOT ItemBase.id(...) — itemArg resolves the id itself one line in,
        // and every other thing it decides is keyed on the raw base: whether our icon library
        // applies, whether the carrier's own attributes need neutralising, whether to stamp the
        // carrier mark. Handing it a RESOLVED id answers all three "no" for our own items,
        // because a resolved carrier is indistinguishable from a base the child named himself.
        // Harmless while the mod item was the default (resolved == raw); the moment the carrier
        // became the default it meant a machine handed over an untinted, un-iconned, unmarked
        // brown horse armour called "Hot Coffee". Fourth instance of one root bug — see A1 in
        // docs/VANILLA_ITEM_SPIKE_ISSUES.md.
        return itemArg(v, icon == null ? 0 : icon, "Its yours to keep.", null,
                made.baseItem(), customData(made.shape(), made.id()), null,
                com.moddingfromamod.item.IconOverlays.forEffects(made.effects()));
    }

    /**
     * <b>The same reference, as a predicate that matches THAT item and no other.</b>
     *
     * <p>For {@code /clear} and {@code execute if items}. A real item matches by id; a made item
     * matches on its whole {@code custom_data}, identity <b>and</b> behaviour — not the identity
     * alone, because {@code ItemYield} renders an item's leftovers under the same feature id, so
     * only the behaviour tag tells a pizza from its slices. Same rule
     * {@code ItemYield.clearPredicate} already follows.
     */
    public static String matchArg(ItemRef ref) {
        com.moddingfromamod.item.ItemSpecStore.Spec made = ref.spec();
        // Matching on the base plus the identity is STRICTER than either alone: his Roasted
        // Coffee Beans rather than any dried kelp, and his rather than any wish item.
        return made == null ? ref.text()
                : ItemBase.id(made.baseItem()) + "[minecraft:custom_data~{"
                        + customData(made.shape(), made.id()) + "}]";
    }

    /**
     * <b>The same reference as a real {@link net.minecraft.world.item.ItemStack}</b>, for the paths
     * that hand one to Java rather than to a command.
     *
     * <p>Parsed out of {@link #giveArg} with the game's own {@code ItemParser} rather than rebuilt
     * from the design in Java. That is the whole point: the give line IS the definition of what a
     * made item is, so a second construction of it in Java is a second definition, and the two
     * would drift the first time an axis was added to one of them. {@code feed_contraption}
     * dropping something subtly different from what {@code give_items} hands over is precisely the
     * kind of difference nobody would notice until a machine refused it.
     *
     * @return the stack, or null when the argument cannot be parsed — which the caller must report
     *         rather than swallow, since an unparseable give is the silent failure of this issue
     */
    public static net.minecraft.world.item.ItemStack stackFor(ItemRef ref, int count,
            net.minecraft.core.HolderLookup.Provider lookup) {
        try {
            var parsed = new net.minecraft.commands.arguments.item.ItemParser(lookup)
                    .parse(new com.mojang.brigadier.StringReader(giveArg(ref)));
            return new net.minecraft.world.item.ItemStack(
                    parsed.item(), count, parsed.components());
        } catch (Exception e) {
            return null;
        }
    }

    /** What to call it out loud: the child's own name for a made item, else the id said plainly. */
    public static String readableName(ItemRef ref) {
        com.moddingfromamod.item.ItemSpecStore.Spec made = ref.spec();
        return made == null ? readableItemName(ref.text()) : made.name();
    }

    private static JsonObject variantsWrapper(com.google.gson.JsonArray variants) {
        JsonObject o = new JsonObject();
        o.add("variants", variants);
        return o;
    }

    // PACKAGE-PRIVATE SO IT CAN BE TESTED. Every semantic rule this tool has lives in here --
    // hearts to damage, seconds to ticks, AS to NBT -- and until 2026-08-26 not one of them had a
    // unit test, because the method was private and the only route to it was a real agent run.
    static String derive(ItemCatalogue.Entry entry, String shape, Map<String, String> p) {
        return derive(entry, shape, p, com.moddingfromamod.datapack.RegistryExists.ALWAYS);
    }

    /**
     * What this tool fills into one effect's arguments before it renders them, off-server: a TARGET from the effect's
     * own applies_to (no magic), then {@link #derive}. Throws with the derive error. For a check that must ask the
     * tool's real composition rules — the Builder's limits (BuilderLimitsTest) — without a server.
     */
    public static Map<String, String> filledOffServer(ItemCatalogue.Entry entry, String shape, Map<String, String> given) {
        Map<String, String> p = new LinkedHashMap<>(given);
        p.put("TARGET", ItemCatalogue.targetFor(shape, entry.appliesTo(), PLACED_RADIUS));
        String err = derive(entry, shape, p);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
        return p;
    }

    /**
     * @param registry asks whether an id names a real thing. {@code ALWAYS} off-server; the live
     *                 one from {@link #call}, which is the only caller that has it.
     */
    static String derive(ItemCatalogue.Entry entry, String shape, Map<String, String> p,
                         com.moddingfromamod.datapack.RegistryExists registry) {
        switch (entry.id()) {
            case "turn_into" -> {
                // convert + change_block as ONE effect. The agent names FROM and TO; the MODE
                // (his bag, the blocks where it lands, or whichever he points at) is derived from the
                // shape in TurnInto, and both ends go through ItemRef exactly as convert's did, so a
                // CHAIN of machines ("coffee beans you roast then grind") still joins.
                String toRaw = p.get("TO");
                ItemRef out = toRaw == null ? null : ItemRef.parse(toRaw.trim());
                if (out != null && out.spec() != null && ItemCatalogue.PLAIN.equals(out.spec().shape())
                        && com.moddingfromamod.datapack.TurnInto.forShape(shape)
                                != com.moddingfromamod.datapack.TurnInto.Mode.BLOCKS
                        && boundWishText() != null && ASKED_KEEPSAKE.add(out.spec().id())) {
                    // The same question load_and_pour asks, asked here too after the smoke
                    // pass: a convert machine poured plain popcorn bags "you can't eat yet". Once
                    // per output, so a made-up block or keepsake goes through on the second call.
                    return "turn_into's TO '" + out.spec().name() + "' is a PLAIN item: he can "
                            + "only hold it. If it is something to eat or drink, change it first — "
                            + "make_item with start_from '" + out.spec().id() + "', shape "
                            + "on_consume, effect food — and keep this TO (the id stays the "
                            + "same). If it really is a thing to keep or build with, send this call "
                            + "again and it is made.";
                }
                String bad = TurnIntoRecipe.derive(shape, p, registry,
                        registry == LIVE_REGISTRY ? LIVE_KINDS : com.moddingfromamod.datapack.TurnInto.ASSUME);
                if (bad != null) {
                    return bad;
                }
            }
            case "load_and_pour" -> {
                String bad = deriveFill(p, registry);
                if (bad != null) {
                    return bad;
                }
            }
            case "place" -> {
                //. Nothing checked this at all: it fell through the default branch, so a typo
                // and a made item alike rendered a /setblock that fails while the wand still played
                // its sound. See BlockRef for why a block gets its own refusal rather than ItemRef's.
                // BLOCK may be a LIST, picked from each use (PICK random or cycle).
                String bad = blockList("place", p, registry);
                if (bad != null) {
                    return bad;
                }
                List<String> each = new ArrayList<>();
                for (String b : com.moddingfromamod.datapack.PickList.items(p.get("BLOCK"))) {
                    each.add("execute if block ~ ~ ~ #minecraft:replaceable run setblock ~ ~ ~ " + b);
                }
                p.put("PLACES", com.moddingfromamod.datapack.PickList.render(each, p.get("PICK")));
                p.remove("PICK");
            }
            case "status" -> {
                // One of the kind effects, at a level it has, for a bounded time — and a
                // worn one outlasts its renewal (StatusEffect's note: night vision flashes).
                String which = com.moddingfromamod.datapack.StatusEffect.effect(p.get("EFFECT"));
                if (which == null) {
                    return "status's EFFECT must be one of "
                            + String.join(", ", com.moddingfromamod.datapack.StatusEffect.EFFECTS.keySet())
                            + " — not '" + p.get("EFFECT") + "'. Nothing that hurts, blinds or lifts "
                            + "him: those are things done TO a child.";
                }
                int maxLevel = com.moddingfromamod.datapack.StatusEffect.EFFECTS.get(which);
                Integer level = posInt0(p.get("LEVEL"));
                if (level == null || level > maxLevel) {
                    return "status's LEVEL for " + which + " must be 0" + (maxLevel > 0
                            ? " to " + maxLevel : " (it has no stronger version)") + ", not '"
                            + p.get("LEVEL") + "'.";
                }
                Integer secs = posInt(p.get("SECONDS"));
                if (secs == null || secs < 1 || secs > com.moddingfromamod.datapack.StatusEffect.MAX_SECONDS) {
                    return "status's SECONDS must be a whole number 1 to "
                            + com.moddingfromamod.datapack.StatusEffect.MAX_SECONDS + ", not '"
                            + p.get("SECONDS") + "'.";
                }
                p.put("EFFECT", "minecraft:" + which);
                p.put("LEVEL", String.valueOf(level));
                p.put("SECONDS", String.valueOf(secs));
                p.put("DURATION", String.valueOf(
                        com.moddingfromamod.datapack.StatusEffect.duration(shape, secs)));
            }
            case "pull" -> {
                //. Words, never a number, exactly as launch does it.
                Double step = com.moddingfromamod.datapack.MagicBlock.pullStep(p.get("STRENGTH"));
                if (step == null) {
                    return "pull's STRENGTH must be gentle, strong or huge, not '"
                            + p.get("STRENGTH") + "'.";
                }
                Integer reach = posInt(p.get("REACH"));
                if (reach == null || reach < 2 || reach > 16) {
                    return "pull's REACH must be a whole number of blocks from 2 to 16, not '"
                            + p.get("REACH") + "'. Past 16 it pulls things he cannot see.";
                }
                List<String> lines = new ArrayList<>();
                for (var band : com.moddingfromamod.datapack.MagicBlock.pullBands(reach, step)) {
                    // From the centre, FACE the thing; stand where it is; step BACK along that
                    // line, which is toward the centre. Never into a solid block.
                    lines.add("    /execute as " + p.get("TARGET") + " if entity @s[distance="
                            + com.moddingfromamod.datapack.MagicBlock.trim(band.from()) + ".."
                            + com.moddingfromamod.datapack.MagicBlock.trim(band.to())
                            + "] facing entity @s feet positioned as @s positioned ^ ^ ^-"
                            + com.moddingfromamod.datapack.MagicBlock.trim(band.step())
                            + " if block ~ ~ ~ #minecraft:replaceable run tp @s ~ ~ ~");
                }
                p.put("PULLS", String.join("\n", lines));
                p.remove("STRENGTH");
                p.remove("REACH");
            }
            case "event" -> {
                //. The item only NAMES a kept design; it goes into a command line, so the name
                // must be exactly the safe key stage_event keeps it under, and nothing else.
                String raw = p.get("DESIGN");
                String design = com.moddingfromamod.event.design.EventDesign.clean(raw);
                if (raw == null || design.isBlank() || !design.equals(raw.trim())) {
                    return "event's DESIGN must be the name of a design kept with stage_event (lowercase "
                            + "letters, digits and _), not '" + raw + "'. Stage it with now: false first.";
                }
                p.put("DESIGN", design);
            }
            case "fill_blocks" -> {
                //. Only into air (the template's `replace #minecraft:air`), never a block that
                // holds things, never more than 3 each way — the rules that let it be undone.
                String block = low(p.get("BLOCK"));
                String bad = com.moddingfromamod.datapack.BlockRef.refusal("fill_blocks's BLOCK",
                        block, registry);
                if (bad == null) {
                    bad = com.moddingfromamod.datapack.BlockLook.refusal("fill_blocks's BLOCK", block,
                            com.moddingfromamod.datapack.BlockLook.live(block), false, false);
                }
                if (bad != null) {
                    return bad;
                }
                Integer r = posInt(p.get("RADIUS"));
                if (r == null || r < 1 || r > com.moddingfromamod.datapack.MagicBlock.MAX_FILL_RADIUS) {
                    return "fill_blocks's RADIUS must be 1 to "
                            + com.moddingfromamod.datapack.MagicBlock.MAX_FILL_RADIUS + ", not '"
                            + p.get("RADIUS") + "'. 3 is already a 7x7x7 pond; more is a flood.";
                }
                p.put("BLOCK", block);
                p.put("FILL_RADIUS", String.valueOf(r));
                p.remove("RADIUS");
                if (!p.containsKey("FEATURE")) {
                    p.put("FEATURE", "wish_fill");
                }
            }
            case "outcome_table" -> {
                //. The ODDS come from MagicBlock.table (a mod repo's feel rules); what comes out is his.
                List<String> presents = listOf(p.get("PRESENTS"));
                List<String> surprises = listOf(p.get("SURPRISES"));
                surprises.removeIf(x -> x.equalsIgnoreCase("none"));
                String boomWord = low(p.get("BOOM"));
                if (!List.of("yes", "no", "true", "false").contains(boomWord == null ? "" : boomWord)) {
                    return "outcome_table's BOOM must be yes or no, not '" + p.get("BOOM") + "'.";
                }
                Object table = com.moddingfromamod.datapack.MagicBlock.table(presents, surprises,
                        boomWord.equals("yes") || boomWord.equals("true"));
                if (table instanceof String refusal) {
                    return refusal;
                }
                List<String> lines = new ArrayList<>();
                int from = 1;
                for (var o : (List<com.moddingfromamod.datapack.MagicBlock.Outcome>) table) {
                    int to = from + o.weight() - 1;
                    String when = "    /execute if score @s mfam_luck matches " + from + ".." + to + " run ";
                    switch (o.kind()) {
                        case "present" -> {
                            String id = o.what().toLowerCase(Locale.ROOT);
                            if (!ENTITY_OR_ITEM_ID.matcher(id).matches() || !registry.has(id)) {
                                return "outcome_table's present '" + o.what() + "' is not a real "
                                        + "item. Use ids like minecraft:diamond, minecraft:cake.";
                            }
                            lines.add(when + "summon minecraft:item ~ ~0.5 ~ {Item:{id:\"" + id
                                    + "\",count:" + o.count() + "},Motion:[0.0,0.3,0.0]}");
                        }
                        case "surprise" -> {
                            String id = o.what().toLowerCase(Locale.ROOT);
                            if (!ENTITY_OR_ITEM_ID.matcher(id).matches()
                                    || !com.moddingfromamod.datapack.BlockLook.entityExists(id)) {
                                return "outcome_table's surprise '" + o.what() + "' is not a real "
                                        + "creature. Use ids like minecraft:chicken, minecraft:zombie.";
                            }
                            lines.add(when + "summon " + id + " ~ ~ ~");
                        }
                        default -> lines.add(when + "summon minecraft:tnt ~ ~ ~ {fuse:40}");
                    }
                    from = to + 1;
                }
                p.put("OUTCOMES", String.join("\n", lines));
                p.remove("PRESENTS");
                p.remove("SURPRISES");
                p.remove("BOOM");
            }
            case "summon" -> {
                Integer n = posInt(p.get("COUNT"));
                if (n == null || n < 1 || n > 20) {
                    return "summon's COUNT must be a whole number of creatures from 1 to 20, not '"
                            + p.get("COUNT") + "'. (More than that placed every couple of seconds "
                            + "is how a world becomes unplayable in about a minute.)";
                }
                String mob = p.get("MOB");
                if (mob == null || !mob.contains(":")) {
                    return "summon's MOB must be a full entity id like minecraft:zombie or "
                            + "othermod:wither_storm, not '" + mob + "'. A bare name does not "
                            + "resolve, and the summon then silently does nothing.";
                }
                // WHAT THE CREATURES ARE LIKE WHEN THEY APPEAR.
                //
                // Vanilla /summon has taken an NBT tag all along, so "baby mobs" and "eggs that pop
                // out already-electric mobs" were never missing capability -- they were a missing
                // parameter, and both wishes were declined for a year because nothing carried them.
                //
                // THE NBT IS NEVER THE AGENT'S TEXT. It is looked up from the table below by an
                // exact match on a word the child would use, so nothing the agent writes can reach
                // a command. That is the same rule docs/PATTERNS.md states for the sandboxed agent
                // -- the boundary is a lookup, not a filter -- and it matters more here because the
                // result is compiled into a datapack and loaded.
                //
                // Unknown keys are inert rather than fatal in vanilla, so `baby` on a creeper does
                // nothing instead of failing. Say so in the `ask`, or the agent will check the mob
                // first and burn a turn on it.
                String look = p.get("AS");
                String nbt = SUMMON_AS.get(look == null ? "" : look.trim().toLowerCase(Locale.ROOT));
                if (nbt == null) {
                    return "summon's AS must be one of " + String.join(", ", SUMMON_AS.keySet())
                            + " — not '" + look + "'. Use his own word: 'baby mobs' is baby, "
                            + "'already-electric' is charged, and plain if he did not say.";
                }
                p.put("SUMMONS", repeat("    /summon " + mob + " ~ ~ ~" + nbt, n));
                p.remove("COUNT");
                p.remove("MOB");
                p.remove("AS");
            }
            case "hurt" -> {
                Double hearts = num(p.get("HEARTS"));
                if (hearts == null || hearts <= 0 || hearts > 100) {
                    return "hurt's HEARTS must be a number of hearts from 1 to 100, not '"
                            + p.get("HEARTS") + "'.";
                }
                p.put("DAMAGE", trim(hearts * 2));   // a heart is two damage
                p.remove("HEARTS");
            }
            case "ignite" -> {
                Integer s = posInt(p.get("SECONDS"));
                if (s == null || s > 300) {
                    return "ignite's SECONDS must be a whole number of seconds up to 300, not '"
                            + p.get("SECONDS") + "'.";
                }
                p.put("FIRE_TICKS", String.valueOf(s * 20));
                p.remove("SECONDS");
            }
            case "freeze" -> {
                Integer s = posInt(p.get("SECONDS"));
                if (s == null || s > 300) {
                    return "freeze's SECONDS must be a whole number of seconds up to 300, not '"
                            + p.get("SECONDS") + "'.";
                }
                // TicksFrozen, not seconds*20: vanilla shivers from 140 and this is the raw counter.
                p.put("FREEZE_TICKS", String.valueOf(Math.max(140, s * 20)));
                // SECONDS stays — the slowness line uses it directly.
            }
            case "aggro" -> {
                // WHO IT TURNS ON. Looked up, never interpolated: this value lands inside a
                // selector's brackets, so a stray comma would not fail — it would quietly become
                // another selector argument, which is the worst kind of wrong.
                String who = p.get("FIGHTS");
                String filter = AGGRO_FIGHTS.get(who == null ? "" : who.trim().toLowerCase(Locale.ROOT));
                if (filter == null) {
                    return "aggro's FIGHTS must be one of " + String.join(", ", AGGRO_FIGHTS.keySet())
                            + " — not '" + who + "'. Use his own word: 'fight ME' and 'chase me' "
                            + "are me; 'fight a mob', 'fight the zombie' and 'fight each other' "
                            + "are mob. There is no default because the two build opposite toys.";
                }
                p.put("AGGRESSOR", filter);
                p.remove("FIGHTS");
            }
            case "grow" -> {
                Double scale = num(p.get("SCALE"));
                if (scale == null || scale < 0.1 || scale > 10) {
                    return "grow's SCALE must be between 0.1 and 10, where 1 is normal size, not '"
                            + p.get("SCALE") + "'. (Above 10 the child cannot fit anywhere and "
                            + "below 0.1 they cannot be seen.)";
                }
                Integer s = posInt(p.get("SECONDS"));
                if (s == null || s > 3600) {
                    return "grow's SECONDS must be a whole number of seconds up to 3600, not '"
                            + p.get("SECONDS") + "'.";
                }
                p.put("SCALE", trim(scale));
                p.put("GROW_TICKS", String.valueOf(s * 20));
                p.remove("SECONDS");
            }
            case "lightning" -> {
                Integer n = posInt(p.get("STRIKES"));
                if (n == null || n > 10) {
                    return "lightning's STRIKES must be a whole number from 1 to 10, not '"
                            + p.get("STRIKES") + "'.";
                }
                p.put("BOLTS", repeat("    /summon minecraft:lightning_bolt ~ ~ ~", n));
                p.remove("STRIKES");
            }
            case "launch" -> {
                // WORDS, deliberately, exactly as impulse does it: a child asks for a little hop or
                // to be sent flying, never for 1.30. An unrecognised word is REFUSED rather than
                // quietly middling — "as hard as you possibly can" building a shove is a wrong
                // answer that only shows up by playing it.
                String power = low(p.get("POWER"));
                Double motion = switch (power == null ? "" : power) {
                    case "nudge" -> 0.6;
                    case "shove" -> 1.2;
                    case "launch" -> 2.0;
                    case "huge" -> 3.2;
                    default -> null;
                };
                if (motion == null) {
                    return "I do not know a launch called '" + p.get("POWER") + "'. Use nudge, "
                            + "shove, launch or huge. Guessing here would build a throw that is "
                            + "not the one asked for, and nothing would say so.";
                }
                p.put("MOTION", trim(motion));
                // A PLAYER is lifted by levitation, not Motion (launch.cbs says why). One second at
                // amplifier A rises about 15·0.05(A+1) blocks and coasts on, so these land a player
                // near the height MOTION throws a mob: roughly 2, 6, 15 and 33 blocks.
                p.put("LIFT", switch (power) {
                    case "nudge" -> "1";
                    case "shove" -> "6";
                    case "launch" -> "15";
                    default -> "29";
                });
                p.remove("POWER");
            }
            case "fly" -> {
                Integer s = posInt(p.get("SECONDS"));
                if (s == null || s > 30) {
                    return "fly's SECONDS must be a whole number of seconds from 1 to 30, not '"
                            + p.get("SECONDS") + "'. (Past 30 he is a dot in the sky with nothing "
                            + "to look at, and levitation above the build height does nothing.)";
                }
                Integer rate = posInt0(p.get("RATE"));
                if (rate == null || rate > 9) {
                    return "fly's RATE must be 0 to 9, not '" + p.get("RATE") + "'. It is how FAST "
                            + "he climbs — about 0.9 blocks a second per step — so how high he gets "
                            + "is RATE x SECONDS.";
                }
                // THE RIDE DOWN, always longer than the ride up. Levitation ends and gravity takes
                // over from whatever height it reached; "a roket" is a wish for the flight, not for
                // the crater. launch learned this the same way, and it is why this is derived
                // rather than offered — a child would never think to ask for it, and the one time
                // he forgot would be the time it killed him.
                p.put("FALL_SECONDS", String.valueOf(Math.min(120, s * 2 + 15)));
            }
            case "jump" -> {
                // Bounded where it still LANDS SOFT: jump boost takes (level + 1) blocks
                // off every fall, and at 0..4 the jump it adds peaks below that (items/jump.cbs).
                Integer s = posInt(p.get("SECONDS"));
                if (s == null || s > 600) {
                    return "jump's SECONDS must be a whole number of seconds from 1 to 600, not '"
                            + p.get("SECONDS") + "'. On something he wears it renews itself, so "
                            + "this is only how often.";
                }
                Integer level = posInt0(p.get("LEVEL"));
                if (level == null || level > 4) {
                    return "jump's LEVEL must be 0 to 4, not '" + p.get("LEVEL") + "'. 0 jumps "
                            + "about 1.8 blocks, 4 about 5 — and every level lands soft. Higher "
                            + "would start to hurt him when he lands, so it is not offered.";
                }
            }
            case "locate" -> {
                // ANOTHER PLAYER: "a compass that points to Bob". Allowed where the world's
                // player_reach is open — asked again at every click (Locate), not only here.
                String toPlayer = com.moddingfromamod.item.LocateTargets.playerPlace(p.get("PLACE"));
                if (toPlayer != null) {
                    if (com.moddingfromamod.world.PlayerVisits.mode()
                            != com.moddingfromamod.agent.PlayerReach.Mode.OPEN) {
                        return "On this world a compass can't point at another player — every place"
                                + " belongs to the player who lives there. Tell them kindly that it works"
                                + " in " + com.moddingfromamod.world.WorldRulesService.current().workshop()
                                + ", and don't make something else that does it.";
                    }
                    p.put("PLACE", toPlayer);
                } else {
                    // A closed word, never text: it becomes part of a tag name in the function.
                    var place = com.moddingfromamod.item.LocateTargets.of(p.get("PLACE"));
                    if (place == null) {
                        return "locate's PLACE must be one of " + com.moddingfromamod.item.LocateTargets.words()
                                + " (or player:<name>), not '" + p.get("PLACE") + "'. If the place he named"
                                + " is not one of those, tell him plainly rather than pointing him somewhere else.";
                    }
                    p.put("PLACE", place.word());
                }
            }
            case "call_pets" -> {
                // A closed word, never text: it becomes part of a tag name in the function.
                String kind = low(p.get("KIND"));
                if (kind == null || kind.isBlank() || "all".equals(kind) || "pets".equals(kind)) {
                    kind = "any";
                } else if ("dog".equals(kind) || "dogs".equals(kind) || "wolves".equals(kind)) {
                    kind = "wolf";
                } else if (kind.endsWith("s") && com.moddingfromamod.item.CallPets.KINDS.contains(
                        kind.substring(0, kind.length() - 1))) {
                    kind = kind.substring(0, kind.length() - 1);
                }
                if (kind.startsWith("creature_")) {
                    // one of his WISHED creatures, by its id — refused when there is none.
                    String id = kind.substring("creature_".length());
                    if (com.moddingfromamod.species.SpeciesRegistry.get(id) == null) {
                        return "call_pets's KIND creature_" + id + " names no creature: give the id of one he "
                                + "wished (list_creatures has them), e.g. creature_fierce_red_dragon.";
                    }
                    kind = com.moddingfromamod.item.CallPets.creatureKind(id);
                }
                if (!com.moddingfromamod.item.CallPets.isKindWord(kind)) {
                    return "call_pets's KIND must be one of " + com.moddingfromamod.item.CallPets.KINDS
                            + ", creature_<id of a creature he wished>, or event_<id stage_event gave a crowd>"
                            + ", not '" + p.get("KIND") + "'.";
                }
                p.put("KIND", kind);
            }
            case "teleport" -> {
                String safe = low(p.get("SAFE"));
                if (!"yes".equals(safe) && !"no".equals(safe)) {
                    return "teleport's SAFE must be yes or no, not '" + p.get("SAFE") + "'.";
                }
                p.put("SAFE_GUARD", "yes".equals(safe)
                        ? "if block ~ ~ ~ #minecraft:replaceable " : "");
                p.remove("SAFE");
            }
            case "light" -> {
                Integer s = posInt0(p.get("GLOW_SECONDS"));
                if (s == null || s > 600) {
                    return "light's GLOW_SECONDS must be a whole number of seconds from 0 to 600, "
                            + "not '" + p.get("GLOW_SECONDS") + "'. Use 0 for no glow.";
                }
                p.put("GLOW", s == 0
                        // A no-op that is still a command, because the template has a line there.
                        ? "/scoreboard players reset @s mfam_noop"
                        : "/execute as @e[distance=..24,type=!minecraft:item] run effect give @s "
                                + "minecraft:glowing " + s + " 0");
                p.remove("GLOW_SECONDS");
            }
            case "explode" -> {
                Integer size = posInt(p.get("SIZE"));
                if (size == null || size > 8) {
                    return "explode's SIZE must be a whole number from 1 to 8, not '"
                            + p.get("SIZE") + "'.";
                }
                String breaks = low(p.get("BREAKS_BLOCKS"));
                if (!"yes".equals(breaks) && !"no".equals(breaks)) {
                    return "explode's BREAKS_BLOCKS must be yes or no, not '"
                            + p.get("BREAKS_BLOCKS") + "'. Ask which he meant: yes leaves a crater, "
                            + "no makes the same bang and hurts the same but leaves his base "
                            + "standing. Getting this wrong costs him the base.";
                }
                if ("yes".equals(breaks)) {
                    p.put("BLAST", repeat("    /summon minecraft:tnt ~ ~ ~ {fuse:0s}", size));
                } else {
                    double radius = 2 + size;
                    p.put("BLAST", String.join("\n",
                            "    /particle minecraft:explosion_emitter ~ ~ ~ 0 0 0 0 " + size
                                    + " force",
                            "    /playsound minecraft:entity.generic.explode master @a ~ ~ ~ 4 1",
                            "    /execute as @e[distance=.." + trim(radius)
                                    + ",type=!minecraft:item] run damage @s " + (size * 4)
                                    + " minecraft:explosion"));
                }
                p.remove("SIZE");
                p.remove("BREAKS_BLOCKS");
            }
            case "rich" -> {
                Integer n = posInt(p.get("COUNT"));
                if (n == null || n > 2304) {
                    return "rich's COUNT must be a whole number from 1 to 2304 (a full inventory), "
                            + "not '" + p.get("COUNT") + "'. If he asked for more than that, give "
                            + "him the 2304 and TELL him what you did — a number quietly reduced is "
                            + "the one thing he will check.";
                }
                //. This accepted ANY string with a colon, so `moddingfromamod:hot_coffee`
                // rendered a /give that failed and `moddingfromamod:wish_item` handed over N
                // nameless blanks — while the particles and the level-up fanfare, which are
                // separate lines, played either way. "A bag that gives me 10 Hot Coffees" gave him
                // the fanfare and an empty bag.
                ItemRef gift = ItemRef.parse(p.get("ITEM"));
                String badGift = gift.whyUnusable("rich's ITEM", registry);
                if (badGift != null) {
                    return badGift;
                }
                p.put("ITEM", giveArg(gift));
            }
            case "protect" -> {
                Integer level = posInt0(p.get("LEVEL"));
                if (level == null || level > 3) {
                    return "protect's LEVEL must be 0 to 3, not '" + p.get("LEVEL") + "'. "
                            + "Resistance 4 is COMPLETE immunity — an item that makes the game "
                            + "unloseable is a worse gift than one that makes it easy.";
                }
            }
            default -> { /* the rest take their parameters straight through */ }
        }
        // Whatever is left and is a seconds-ish or level-ish number still has to BE a number, or the
        // command it lands in fails to parse and the item silently does nothing.
        for (String key : List.of("SECONDS", "LEVEL", "COUNT")) {
            if (p.containsKey(key) && posInt0(p.get(key)) == null) {
                return entry.id() + "'s " + key + " must be a whole number, not '" + p.get(key)
                        + "'.";
            }
        }
        return null;
    }

    /** n copies of a line, joined — how a COUNT that arrives as text becomes repeated commands. */
    private static String repeat(String line, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(line);
            if (i < n - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** What the child is told to DO with it, derived from the shape rather than from the wish. */
    /** A worn thing's clock never renews faster than this: 2 seconds. */
    static final int WORN_MIN_RENEW_TICKS = 40;

    /** ...and, for an effect that has no duration of its own, this: 10 seconds. */
    static final int WORN_DEFAULT_RENEW_TICKS = 200;

    /**
     * <b>The worn wrapper's three placeholders</b>: which armour slot the clock watches,
     * the scoreboard it counts in, and how often it RENEWS the effect.
     *
     * <p>The renewal is the effect's own duration, half a second short, so a status effect (jump,
     * speed, protection) stays continuously on him with one sound per renewal rather than twenty a
     * second. With several effects the shortest wins, so none of them lapses. Supplied for every
     * shape because the renderer refuses an unfilled placeholder and supplying three unused ones is
     * cheaper than a second map; only {@code shape_worn.cbs} names them.
     */
    public static Map<String, String> wornArgs(String shape, String featureId,
                                               Map<String, Map<String, String>> argsByEffect) {
        Map<String, String> out = new LinkedHashMap<>();
        boolean worn = com.moddingfromamod.datapack.WornShape.isWorn(shape);
        out.put("SLOT", worn ? com.moddingfromamod.datapack.WornShape.slot(shape) : "feet");
        out.put("WORN_OBJECTIVE", "mfam_w_" + featureId);
        int renew = Integer.MAX_VALUE;
        for (Map<String, String> args : argsByEffect.values()) {
            String secs = args.get("SECONDS");
            if (secs == null) {
                continue;
            }
            try {
                renew = Math.min(renew, Integer.parseInt(secs.trim()) * 20 - 10);
            } catch (NumberFormatException notANumber) {
                // derive() already refused it; nothing to renew on
            }
        }
        if (renew == Integer.MAX_VALUE) {
            renew = WORN_DEFAULT_RENEW_TICKS;
        }
        out.put("RENEW_TICKS", String.valueOf(Math.max(WORN_MIN_RENEW_TICKS, renew)));
        return out;
    }

    private static String hintFor(String shape) {
        return switch (shape) {
            case "thrown" -> "Throw it!";
            case "placed" -> "Use it on the ground to put it down.";
            // TWO ACTIONS, and he needs both. Putting it down is only half of it — a machine he
            // has placed and does not know how to work is a machine that does nothing, and the
            // sign on its front says "pull the handle" to whoever has already walked up to it.
            case ItemCatalogue.MACHINE ->
                    "Put it down, then pull the handle on the front!";
            case com.moddingfromamod.datapack.MagicBlock.SHAPE ->
                    "Use it on the ground to put it down, and see what it does!";
            // NOT "right-click" — the whole point of this shape is that it is the other verb, and
            // the lore line is where he finds out which. A hint that says the wrong thing is worse
            // than none: he tries the wrong action, nothing happens, and it reads as broken.
            case "on_hit" -> "Hit something with it!";
            case "arrow" -> "Shoot it!";
            case "on_shot" -> "Shoot it!";
            // One hint for food and drink alike: the verb that matters is FINISHING it.
            case "on_consume" -> "Finish it up, and the magic happens!";
            case "on_block" -> "Hold it up and block, and the magic happens!";
            case "on_throw_land" -> "Throw it!";
            case "on_use_done" -> "Use it, and when you're done the magic happens!";
            case "on_mine" -> "Mine with it!";
            // NOT an instruction, because there is nothing to do. Every other hint tells him which
            // action sets the item off; this one has to tell him there isn't one, or he tries a
            // click, gets nothing, and concludes the wish failed.
            case "plain" -> "It's yours to keep.";
            // WORN: the verb is PUT IT ON, and "right-click" would be wrong twice over -- a
            // right-click does equip it, but nothing then happens until it is on, so a child
            // following "right-click to use it" twice takes it off again.
            case "worn_head", "worn_chest", "worn_legs", "worn_feet" ->
                    "Put it on - it works while you wear it!";
            default -> "Hold it and use it!";
        };
    }

    /** A trail that matches the effect, so a throw is visible in flight. */
    private static String trailFor(String effect) {
        return switch (effect) {
            case "ignite", "explode" -> "minecraft:flame";
            case "freeze" -> "minecraft:snowflake";
            case "aggro" -> "minecraft:angry_villager";
            case "heal", "food" -> "minecraft:heart";
            case "lightning" -> "minecraft:electric_spark";
            case "light" -> "minecraft:end_rod";
            case "rich" -> "minecraft:happy_villager";
            case "teleport" -> "minecraft:portal";
            default -> "minecraft:crit";
        };
    }

    /**
     * A stable, legal feature id from the child's name for it.
     *
     * <p>The effect is in the id on purpose: two wishes for "a bomb" that differ only in what the
     * bomb DOES would otherwise collide, and the second would overwrite the first without a word.
     */
    /**
     * What this edit took away, or "" — the seam the R4 gate reaches the agent through.
     *
     * <p>Its own named method rather than an inline call, for the reason
     * {@code BuildCritiqueWiringTest} exists: a rule's own tests say nothing about whether the rule
     * reaches the string the agent reads, and unwiring the creature shape gate left its rule tests
     * 6/6 green.
     *
     * @param before the design before this call; null for a new item
     * @param after  the RESOLVED design — inherited fields merged in, never the fields sent
     */
    static String critiqueFor(com.moddingfromamod.item.ItemSpecStore.Spec before,
                              com.moddingfromamod.item.ItemSpecStore.Spec after) {
        String words = childWordsFor();
        // BOTH HALVES OF THE ITEM. ItemDrift grades what it still DOES — the effect list, and a
        // parameter block emptied out. EffectDrift grades the numbers inside those effects, which
        // ItemDrift deliberately cannot see because a changed value is what a refinement IS. An
        // item is the first artifact whose identity is half behaviour, and the behaviour is half
        // list and half number; a gate on only one of them leaves the other invisible.
        // ── A CREATION GETS A DESCRIPTION, NOT SILENCE ──────────────────────────────
        //
        // Both drift gates below open with `before == null -> ""` -- correctly, because a new item
        // has nothing to LOSE. The consequence was that a newly made item got no report at all,
        // and most wishes create rather than refine, so the item linter was silent on the path
        // most items take. `build_structure` avoids this because its geometry note runs on a
        // creation too; this had no equivalent. See docs/WISH_LINTERS.md P3.
        //
        // It matters more here than it looks: this tool RESOLVES what the agent sent -- filling in
        // every field it did not mention, and deriving parameters from a scale rather than from a
        // number it asked for. So on a creation the agent has never been told what it actually
        // got, and a later `start_from` edit reasons about what it SENT rather than what exists.
        if (before == null && after != null) {
            return describeFor(after);
        }
        List<String> notes = new ArrayList<>();
        String lost = com.moddingfromamod.runtime.gate.ItemDrift.between(before, after, words);
        if (!lost.isEmpty()) {
            notes.add(lost);
        }
        String rewritten = com.moddingfromamod.runtime.gate.EffectDrift
                .between(before, after, words);
        if (!rewritten.isEmpty()) {
            notes.add(rewritten);
        }
        return notes.isEmpty() ? "" : "\n\n" + String.join("\n\n", notes);
    }

    /**
     * <b>What the item actually came out as</b> — the behaviour that exists, not the words that
     * asked for it.
     *
     * <p>Deliberately narrow, per docs/WISH_LINTERS.md P6: an item gets a great many fields filled
     * in by default, and listing all of them makes a note the agent learns to skip -- taking the
     * true findings with it. So this reports the two things it cannot know any other way: the
     * effects that resolved, and the parameters those effects were given.
     */
    static String describeFor(com.moddingfromamod.item.ItemSpecStore.Spec spec) {
        StringBuilder sb = new StringBuilder("\n\nWHAT YOU ACTUALLY MADE (the child sees this "
                + "behaviour, not the words you asked for):\n");
        if ((spec.effects() == null || spec.effects().isEmpty())
                && com.moddingfromamod.datapack.MagicBlock.SHAPE.equals(spec.shape())) {
            sb.append("  a BLOCK he puts down and breaks, which does nothing else — right for \"a new "
                    + "block\"; if he named a power, give it an effect.\n");
            return sb.toString();
        }
        if (spec.effects() == null || spec.effects().isEmpty()) {
            // Not a style note. An item with no effect looks right in the hand and does nothing,
            // which is indistinguishable to a child from a wish that was never granted.
            sb.append("  ⚠ THIS ITEM DOES NOTHING — no effect resolved, so holding or using it "
                    + "has no result. Give it an effect, or tell the child plainly that it is a "
                    + "keepsake rather than letting them find out.\n");
            return sb.toString();
        }
        sb.append("  effects: ").append(String.join(", ", spec.effects())).append('\n');
        JsonObject params = spec.params();
        if (params != null && !params.entrySet().isEmpty()) {
            List<String> kv = new ArrayList<>();
            for (var e : params.entrySet()) {
                kv.add(e.getKey() + "=" + (e.getValue().isJsonPrimitive()
                        ? e.getValue().getAsString() : e.getValue().toString()));
            }
            // The NUMBERS are the half a child feels and the agent never chose: "really far away"
            // became a specific knockback, derived from a scale. Closing that loop is the point.
            sb.append("  numbers: ").append(String.join(", ", kv)).append('\n');
            sb.append("  Check those against what the child asked for. A number that does not "
                    + "match the words is the difference between the wish and the item.\n");
        }
        return sb.toString();
    }

    /** The wish in the child's own words, or "" outside a run (a direct call, a gate). */
    static String childWordsFor() {
        var run = com.moddingfromamod.agent.AgentContext.currentRun();
        return run == null ? "" : run.text();
    }

    /**
     * <b>Every effect under its OWN name, and its parameters under theirs.</b>
     *
     * <p>The effect string is canonicalised where it is split; this does the same for {@code params},
     * which that split never saw: a group keyed by an alias ({@code {"convert":{…}}}) was looked up
     * under the canonical id and found EMPTY, so every parameter was "still needed". And
     * {@code turn_into}'s old shapes — convert's INPUT/OUTPUT, change_block's BLOCK — are renamed to
     * FROM/TO, flat or grouped, so an old-shaped call (a learned template, a replay, an agent that
     * remembers the old names) makes an item that works. Idempotent; the args are copied.
     */
    static JsonObject canonicalParams(JsonObject args) {
        String effect = str(args, "effect");
        if (effect == null) {
            return args;
        }
        ItemCatalogue catalogue;
        try {
            catalogue = ItemCatalogue.load();
        } catch (RuntimeException e) {
            return args;   // the caller reports an unreadable catalogue in its own words
        }
        JsonObject out = args.deepCopy();
        List<String> canon = new ArrayList<>();
        for (String part : effect.split("\\+")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) {
                String c = catalogue.canonical(id);
                canon.add(c == null ? id : c);
            }
        }
        if (canon.contains(com.moddingfromamod.datapack.TurnInto.EFFECT)) {
            // the effect string itself, so the stored design and inherit() both say turn_into
            out.addProperty("effect", String.join("+", canon));
        }
        if (out.has("params") && out.get("params").isJsonObject()) {
            JsonObject params = out.getAsJsonObject("params");
            for (String k : new ArrayList<>(params.keySet())) {
                if (!params.get(k).isJsonObject() || k.startsWith("_")) {
                    continue;
                }
                String c = catalogue.canonical(k.toLowerCase(Locale.ROOT));
                if (c != null && !c.equals(k) && canon.contains(c)
                        && !(com.moddingfromamod.datapack.TurnInto.OLD_IDS.contains(k))) {
                    JsonObject g = params.remove(k).getAsJsonObject();
                    if (!params.has(c)) {
                        params.add(c, g);
                    }
                }
            }
            com.moddingfromamod.datapack.TurnInto.normaliseParams(params, canon, str(out, "shape"));
        }
        return out;
    }

    /**
     * The agent's call, with every field it did NOT mention filled in from the item he already has.
     *
     * <p>Not-mentioned means <b>keep</b>, which is the rule the buildings lesson already teaches
     * for shapes. The alternative — making the agent restate name, icon, colour, shape, count and
     * every effect parameter in order to change one of them — is not merely tedious: it is six more
     * chances per refinement to get one of them wrong, and one wrong field is a wand that quietly
     * stops doing what it did last week.
     *
     * <p>{@code params} is merged per effect rather than replaced, because <i>"make it hurt way
     * more"</i> arrives as {@code {"hurt":{"HEARTS":"12"}}} on an item that also ignites, and a
     * whole-object replace would drop the burn's SECONDS while the item still claimed both effects.
     */
    static JsonObject inherit(JsonObject args, com.moddingfromamod.item.ItemSpecStore.Spec base) {
        JsonObject out = args.deepCopy();
        if (str(out, "name") == null) {
            out.addProperty("name", base.name());
        }
        if (str(out, "icon") == null) {
            out.addProperty("icon", base.icon());
        }
        if (str(out, "colour") == null) {
            out.addProperty("colour", base.colour());
        }
        if (str(out, "shape") == null) {
            out.addProperty("shape", base.shape());
        }
        if (!out.has("count")) {
            out.addProperty("count", base.count());
        }
        if (str(out, "effect") == null) {
            out.addProperty("effect", String.join("+", base.effects()));
        }
        JsonObject over = out.has("params") && out.get("params").isJsonObject()
                ? out.getAsJsonObject("params") : null;
        // A CHANGED EFFECT DOES NOT INHERIT THE OLD ONE'S PARAMETERS (smoke, 2026-09-28).
        // "make it pour into a chest" turned a convert machine into load_and_pour, and convert's
        // saved INPUT rode along into the new effect and was refused — three calls in a row, the
        // agent re-sending a correct call each time. Only the effects that SURVIVE keep theirs.
        JsonObject kept = base.params() == null ? null : base.params().deepCopy();
        if (kept != null && args.has("effect") && str(args, "effect") != null) {
            java.util.Set<String> now = new java.util.HashSet<>();
            for (String e : str(args, "effect").split("\\+")) {
                now.add(e.trim().toLowerCase(Locale.ROOT));
            }
            // "convert" in an edit is the turn_into the design already has, not a new effect.
            now = new java.util.HashSet<>(com.moddingfromamod.datapack.TurnInto.canonicalEffects(
                    new ArrayList<>(now)));
            boolean anyDropped = false;
            for (String old : base.effects()) {
                if (!now.contains(old.toLowerCase(Locale.ROOT))) {
                    kept.remove(old);
                    anyDropped = true;
                }
            }
            // A ONE-EFFECT design stores its parameters FLAT ({"INPUT": …}), not under the effect's
            // name — which is the shape the first cut of this fix missed, measured live on the very
            // wish it was for. When that one effect is replaced, every flat parameter was its.
            if (anyDropped && base.effects().size() == 1) {
                for (String k : new java.util.ArrayList<>(kept.keySet())) {
                    if (!kept.get(k).isJsonObject()) {
                        kept.remove(k);
                    }
                }
            }
        }
        out.add("params", com.moddingfromamod.item.ItemSpecStore.Spec.mergeParams(kept, over));
        // A PARAMETER SENT AS null MEANS "TAKE IT OFF" — and it used to crash the tool
        // ("JsonNull"), which is how an agent trying to clear a stale one found out.
        JsonObject merged = out.getAsJsonObject("params");
        for (String k : new java.util.ArrayList<>(merged.keySet())) {
            if (merged.get(k).isJsonNull()) {
                merged.remove(k);
            }
        }
        // THE FAMILY, on the same rule as every field above: not mentioned means KEEP..
        //
        // Variants were the one field where absent meant "throw them away", because parseVariants'
        // family-of-one fallback is right for a NEW wish and catastrophic for an edit. Measured
        // live: "make them all give you way more speed" — a wish about the effect, sending no
        // variants — deleted three of four flavours, and the pack shrank 847 bytes while gaining a
        // whole second effect file. Nothing failed; the ladder called it PASS, because the count it
        // was grading came from the child's inventory, where the old stacks were still sitting.
        //
        // A list that IS sent replaces rather than merges: "make the grape one BUTT CHEEK WATER" is
        // a swap, and merging would leave him a Grape he asked to be rid of.
        if ((!out.has("variants") || out.get("variants").isJsonNull())
                && base.variants() != null && !base.variants().isEmpty()) {
            out.add("variants", base.variants());
        }
        // PROPERTIES, on the same rule, and it is the shape exactly. An edit about the colour
        // sends no `properties`, and without this line the LARGE cup would quietly go back to
        // stacking to 64 -- a family that survived its first wish and died on its second, with the
        // give line still rendering and every gate still green.
        //
        // Merged per key rather than replaced wholesale, so "make the big one hit harder" keeps the
        // stack size it already had.
        JsonObject overProps = out.has("properties") && out.get("properties").isJsonObject()
                ? out.getAsJsonObject("properties") : null;
        JsonObject keptProps = base.properties() == null
                ? new JsonObject() : base.properties().deepCopy();
        if (overProps != null) {
            for (String k : overProps.keySet()) {
                keptProps.add(k, overProps.get(k));
            }
        }
        if (!keptProps.isEmpty()) {
            out.add("properties", keptProps);
        }
        // PARTS, on the same rule. "make it blue" must not take the sprinkles off his cone. A list
        // that IS sent replaces rather than merges, exactly as `variants` does: "no sauce, just
        // sprinkles" is a swap, and merging would leave the sauce he asked to be rid of.
        if ((!out.has("parts") || out.get("parts").isJsonNull())
                && base.parts() != null && !base.parts().isEmpty()) {
            out.add("parts", base.parts());
        }
        // WHAT IT IS BUILT ON, on the same rule as every field above — and it was the one axis R7's
        // sweep could not see, because the Spec did not carry it, so "not mentioned" had nowhere to
        // be kept FROM. An axis that is not persisted does not look like an axis at all: the sweep
        // reported every field surviving while "make my Coffee Beans green" quietly turned them
        // from cocoa beans into a blank wish item. Found by Gate C½, not by any gate.
        if (str(out, "base_item") == null && base.baseItem() != null
                && !base.baseItem().isBlank()) {
            out.addProperty("base_item", base.baseItem());
        }
        return out;
    }

    public static String featureId(String name, String effect) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isEmpty()) {
            base = "wish";
        }
        String id = base + "_" + effect;
        if (!Character.isLetter(id.charAt(0))) {
            id = "i" + id;
        }
        return id.length() > 47 ? id.substring(0, 47).replaceAll("_+$", "") : id;
    }

    /** The 16 colour words a child actually uses, plus #RRGGBB. */
    /**
     * The nearest colour WORD to a packed RGB — the reverse of the table below.
     *
     * <p>An item's colour survives in its stack as a 24-bit integer, and a fixture that wants to
     * ask "is it still purple" needs a name. Resolving against this same table rather than against
     * some general colour space is the point: it is the vocabulary the agent was offered, so an
     * agent that asked for "purple" reads back as purple exactly, and only a hand-written #RRGGBB
     * has to be approximated at all.
     */
    public static String colourName(int rgb) {
        String best = null;
        long bestD = Long.MAX_VALUE;
        for (Map.Entry<String, Integer> e : COLOURS.entrySet()) {
            if ("gray".equals(e.getKey())) {
                continue;                     // same colour as "grey"; one spelling is enough
            }
            int v = e.getValue();
            long dr = ((rgb >> 16) & 0xFF) - ((v >> 16) & 0xFF);
            long dg = ((rgb >> 8) & 0xFF) - ((v >> 8) & 0xFF);
            long db = (rgb & 0xFF) - (v & 0xFF);
            long d = dr * dr + dg * dg + db * db;
            if (d < bestD) {
                bestD = d;
                best = e.getKey();
            }
        }
        return best;
    }

    private static final Map<String, Integer> COLOURS = colours();

    private static Map<String, Integer> colours() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("red", 0xE33B3B);      m.put("orange", 0xF08010);
        m.put("yellow", 0xFFD83B);   m.put("green", 0x4CC93B);
        m.put("lime", 0x8FF03B);     m.put("blue", 0x3B7BE3);
        m.put("light_blue", 0x5BC8F0); m.put("cyan", 0x2FC7C7);
        m.put("purple", 0x9B3BE3);   m.put("magenta", 0xE33BC8);
        m.put("pink", 0xFF9BC8);     m.put("brown", 0x8B5A2B);
        m.put("black", 0x1D1D21);    m.put("grey", 0x8E8E8E);
        m.put("gray", 0x8E8E8E);     m.put("white", 0xF0F0F0);
        m.put("gold", 0xFFC02B);     m.put("silver", 0xC8C8D0);
        return Map.copyOf(m);
    }

    /** #RRGGBB or a colour word, to a decimal RGB. Null if it is neither. */
    static Integer colourOf(String colour) {
        String c = colour.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        Integer named = COLOURS.get(c);
        if (named != null) {
            return named;
        }
        String hex = c.startsWith("#") ? c.substring(1) : c;
        if (hex.matches("[0-9a-f]{6}")) {
            return Integer.parseInt(hex, 16);
        }
        return null;
    }

    /**
     * Make a child's words safe to sit inside the give command's JSON.
     *
     * <p>Not decoration: an apostrophe is the single commonest character in the names he chooses
     * ("Bloop's Doom"), the whole component argument is wrapped in single quotes, and one of them
     * turns a working give into a parse error — an item that never appears, from a pack that
     * compiled perfectly.
     */
    /** Scratch keys derive hands back to call(); never rendered, removed before the template. */
    static final String SIGN_2 = "_SIGN2";
    static final String SIGN_3 = "_SIGN3";
    static final String RECIPE = "_RECIPE";

    /** Machine outputs already questioned as plain keepsakes this server run. */
    private static final java.util.Set<String> ASKED_KEEPSAKE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Names already questioned as machines this server run: the second ask goes through. */
    /** Names already asked "is this a vending machine?" — asked once, then it goes through. */
    private static final java.util.Set<String> ASKED_VENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> ASKED_MACHINE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The word that names an item as a machine, or null. A MECHANISM word, never a product. */
    static String machineWord(String name) {
        if (name == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                // Spelled the way he types them too ("facktery", "mashine"): a child's wish is the
                // input, and a check that only knows the dictionary spelling misses the wish.
                .compile("(?i)\\b(machine|mashine|machien|machiene|maker|factory|facktery|factery|"
                        + "fac?k?tory|dispenser)\\b").matcher(name);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }
    /** What a filled machine really does, reported back to the agent. */
    static final String HOW = "_HOW";
    /** A filled machine's starter kit: give lines, and what he is told about them. */
    static final String KIT = "_KIT";
    static final String KIT_SAID = "_KIT_SAID";
    static final String FILL_NAME = "_MACHINE_NAME";

    /**
     * a machine he FILLS. Every argument goes through ItemRef exactly as convert's do, so a
     * made ingredient is named by the id make_item handed back and a typo is refused by name rather
     * than rendered into a machine that takes nothing.
     */
    /** {@link #deriveFill} off-server, for the catalogue's own tests. */
    public static String deriveFillForTest(Map<String, String> p) {
        String bad = deriveFill(p, com.moddingfromamod.datapack.RegistryExists.ALWAYS);
        p.keySet().removeIf(k -> k.startsWith("_"));
        return bad;
    }

    static String deriveFill(Map<String, String> p,
                             com.moddingfromamod.datapack.RegistryExists registry) {
        List<com.moddingfromamod.item.MachineFill.Ingredient> required = new ArrayList<>();
        List<com.moddingfromamod.item.MachineFill.Ingredient> extras = new ArrayList<>();
        List<String> requiredGives = new ArrayList<>();
        List<String> extraGives = new ArrayList<>();
        String ins = p.getOrDefault("INGREDIENTS", "");
        String xs = p.getOrDefault("EXTRAS", "none");
        for (String[] which : new String[][]{{"INGREDIENTS", ins}, {"EXTRAS", xs}}) {
            String raw = which[1] == null ? "" : which[1].trim();
            if ("EXTRAS".equals(which[0]) && (raw.isEmpty() || "none".equalsIgnoreCase(raw))) {
                continue;
            }
            for (String one : raw.split(",")) {
                if (one.isBlank()) {
                    continue;
                }
                ItemRef ref = ItemRef.parse(one.trim());
                String bad = ref.whyUnusable("fill's " + which[0], registry);
                if (bad != null) {
                    return bad;
                }
                var ing = new com.moddingfromamod.item.MachineFill.Ingredient(matchArg(ref),
                        jsonSafe(readableName(ref)));
                ("INGREDIENTS".equals(which[0]) ? required : extras).add(ing);
                ("INGREDIENTS".equals(which[0]) ? requiredGives : extraGives).add(giveArg(ref));
            }
        }
        ItemRef out = ItemRef.parse(p.getOrDefault("OUTPUT", ""));
        String badOut = out.whyUnusable("fill's OUTPUT", registry);
        if (badOut != null) {
            return badOut;
        }
        for (var i : required) {
            if (i.match().equals(matchArg(out))) {
                return "fill's OUTPUT '" + out.text() + "' is also one of its INGREDIENTS, so the "
                        + "machine would pour back what he put in. Name the different thing that "
                        + "comes OUT.";
            }
        }
        int max;
        try {
            max = Integer.parseInt(p.getOrDefault("MAX", "").trim());
        } catch (NumberFormatException e) {
            return "fill's MAX is a whole number, 1 to " + com.moddingfromamod.item.MachineFill.MAX_MAIN
                    + ", not '" + p.get("MAX") + "'";
        }
        String where = p.getOrDefault("WHERE", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (!"hand".equals(where) && !"chest".equals(where)) {
            return "fill's WHERE is 'hand' or 'chest', not '" + p.get("WHERE") + "'";
        }
        if (out.spec() != null && ItemCatalogue.PLAIN.equals(out.spec().shape())
                && ASKED_KEEPSAKE.add(out.spec().id())) {
            // Measured, three runs running: the slushie it pours was made `plain` before
            // anything mentioned food, and the agent then told him "you can't drink it yet".
            // Asked ONCE per output: the same call again goes through for a real keepsake.
            return "fill's OUTPUT '" + out.spec().name() + "' is a PLAIN item: he can only hold it. "
                    + "If it is something to eat or drink, change it first — make_item with "
                    + "start_from '" + out.spec().id() + "', shape on_consume, effect food — and keep "
                    + "this OUTPUT (the id stays the same). If it really is a keepsake, send this "
                    + "call again and it is made.";
        }
        String outName = jsonSafe(readableName(out));
        var spec = new com.moddingfromamod.item.MachineFill.Spec(required, extras, giveArg(out),
                outName, out.spec() == null ? null : outName, max, "chest".equals(where));
        String problem = spec.problem();
        if (problem != null) {
            return problem;
        }
        String machineName = p.getOrDefault(FILL_NAME, "This machine");
        p.putAll(com.moddingfromamod.item.MachineFill.render(spec, machineName));
        List<String> sign = com.moddingfromamod.item.MachineFill.signLines(spec);
        p.put(SIGN_2, sign.get(0));
        p.put(SIGN_3, sign.get(1));
        p.put(RECIPE, com.moddingfromamod.item.MachineFill.recipe(spec));
        p.put(HOW, com.moddingfromamod.item.MachineFill.describe(spec));
        // THE STARTER KIT (follow-up): enough for its biggest pour, handed over WITH the
        // machine so the first pull pours. MachineFill decides what and how many; nothing here asks
        // the agent, so every machine gets the same rule.
        List<String> gives = new ArrayList<>(requiredGives);
        gives.addAll(extraGives);
        StringBuilder kit = new StringBuilder();
        for (var part : com.moddingfromamod.item.MachineFill.kit(spec, gives)) {
            kit.append('\n').append("    ").append(ItemRecipient.command(ItemRecipient.ME,
                    part.give(), part.count()));
        }
        p.put(KIT, kit.toString());
        String kitSaid = com.moddingfromamod.item.MachineFill.kitMessage(spec, gives);
        if (kitSaid != null) {
            p.put(KIT_SAID, kitSaid);
            p.put(HOW, p.get(HOW) + ". It comes WITH a starter kit, handed over by itself: \""
                    + kitSaid + "\" (do not tell him to go and find those)");
        }
        if (!p.containsKey("MACHINE")) {
            // Off-server callers (the unit tests) have no feature id; any tag works for a render.
            p.put("MACHINE", "fill_machine");
        }
        return null;
    }

    static String jsonSafe(String s) {
        return s.replace("\\", "").replace("\"", "").replace("'", "")
                .replace("\n", " ").trim();
    }

    private static String str(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) {
            return null;
        }
        String v = o.get(k).getAsString();
        return v.isBlank() ? null : v;
    }

    private static Integer posInt(String s) {
        Integer v = posInt0(s);
        return v == null || v < 1 ? null : v;
    }

    private static Integer posInt0(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return v < 0 ? null : v;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Double num(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * {@code BLOCK} is one block, or a mode word and a comma list of up to {@link
     * com.moddingfromamod.datapack.PickList#MAX} real blocks — {@code "cycle: a, b"} (the next one each
     * use) or {@code "random: a, b"}. A list with no mode is refused, never guessed. On success
     * {@code p} holds BLOCK as the plain list and PICK as the mode; otherwise says what is wrong.
     */
    private static String blockList(String effect, Map<String, String> p,
                                    com.moddingfromamod.datapack.RegistryExists registry) {
        String raw = p.get("BLOCK");
        String mode = com.moddingfromamod.datapack.PickList.modeOf(raw);
        List<String> blocks = com.moddingfromamod.datapack.PickList.items(
                com.moddingfromamod.datapack.PickList.withoutMode(raw));
        if (blocks.isEmpty()) {
            return com.moddingfromamod.datapack.BlockRef.refusal(effect + "'s BLOCK", raw, registry);
        }
        if (blocks.size() > 1 && mode == null) {
            return effect + "'s BLOCK lists " + blocks.size() + " blocks but not how to choose: put "
                    + "cycle: in front for the next one each time, in the order listed (a rainbow), or "
                    + "random: for a different one each time — e.g. \"cycle: minecraft:red_wool, "
                    + "minecraft:orange_wool\".";
        }
        if (blocks.size() > com.moddingfromamod.datapack.PickList.MAX) {
            return effect + "'s BLOCK lists " + blocks.size() + " blocks; at most "
                    + com.moddingfromamod.datapack.PickList.MAX + " (a rainbow is seven).";
        }
        for (String b : blocks) {
            String bad = com.moddingfromamod.datapack.BlockRef.refusal(effect + "'s BLOCK", b, registry);
            if (bad != null) {
                return bad;
            }
        }
        p.put("BLOCK", String.join(",", blocks));
        p.put("PICK", mode == null ? "random" : mode);
        return null;
    }

    private static String low(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /**
     * The wish this worker is actually granting, or {@code null} when it is not bound to one.
     *
     * <p>Deliberately not {@code AgentContext.currentRun()}: that resolves through a single-live-run
     * fallback, which is right for reading a note and wrong for deciding a refusal. See the note at
     * the call site for what that cost.
     */
    private static String boundWishText() {
        var run = boundRun();
        return run == null ? null : run.text();
    }

    /** The run this worker is bound to, or null — see boundWishText for why never the ambient one. */
    private static com.moddingfromamod.marshal.WishRun boundRun() {
        String worker = com.moddingfromamod.agent.AgentContext.workerId();
        if (worker == null || worker.isEmpty()) {
            return null;
        }
        return com.moddingfromamod.marshal.Marshal.global().runForWorker(worker);
    }
}
