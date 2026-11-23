package com.moddingfromamod.agent.tools;

import com.google.gson.JsonObject;
import com.moddingfromamod.agent.McpTool;
import com.moddingfromamod.agent.Schemas;
import com.moddingfromamod.agent.ToolResult;
import com.moddingfromamod.reference.ReferenceResearcher;
import com.moddingfromamod.reference.ReferenceSpec;

/**
 * <b>Look up a real character before sculpting it.</b>
 *
 * <p>The wish agent has no web access, deliberately and permanently: it can write unguarded
 * datapacks into a child's live world, so page text must never reach its context. This tool is the
 * only way a reference gets in, and what comes back has been narrowed to enums and numbers by
 * {@link ReferenceSpec} — the lookup itself happens in a separate process with no wish tools at all.
 *
 * <p>Why it exists: without it every claim the agent makes about a real character is recall, and
 * recall is what produced a Spikor built 65% tan from a lesson that confidently said so. The
 * mechanism was working perfectly; the fact was wrong.
 */
public final class LookUpCharacterTool implements McpTool {

    /**
     * <b>How long to wait for a cold lookup — and 45 s was measured MISSING BY EIGHT SECONDS.</b>
     *
     * <p>The first cold-cache run with the wait in place gave up at 00:21:33 and the reference
     * landed at 00:21:41. The creature that shipped was a man in sunglasses, exactly as before the
     * whole feature existed: the timeout, not the design, is what failed. A standalone lookup
     * measures 48 s, but inside a live wish it competes with the client, the agent and the
     * researcher's own model call, and this one took ~53 s from the tool's point of view.
     *
     * <p>So this is set well above the measured spread rather than just above the median. The
     * EXPECTED cost is the fetch (~50 s), never this number, because the wait returns the moment
     * the future completes — success or failure. The timeout only binds when something is
     * genuinely stuck, and then a wish is 90–200 s anyway.
     *
     * <p>It is also paid <b>once per character, ever</b>: every later wish about the same one is a
     * 1 ms cache hit.
     */
    private static final int WAIT_SECONDS = 90;

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(LookUpCharacterTool.class);

    @Override
    public String name() {
        return "look_up_character";
    }

    @Override
    public String description() {
        return "Find out what a REAL character actually looks like — colours and their proportions, "
                + "notable parts, rough size. Call this BEFORE sculpting OR PAINTING whenever the "
                + "child named something real (a game, cartoon or TV character, a brand's mascot, a "
                + "meme character, a YouTuber) — before sculpt_creature, and before paint_surface "
                + "for 'a picture of' one. Pass what it returns as sculpt_creature's 'palette', or paint with its "
                + "colours. For a REAL PERSON it answers whether they are a public figure and, if "
                + "so, either tells you to paint their Minecraft skin with paint_surface "
                + "'from_skin' (when an account is linked to them) or gives you their Minecraft "
                + "look to paint from — never draw a real person from memory. A Minecraft "
                + "creator on the generated creator list gets only 'paint this creator's own "
                + "skin' — do exactly that. You have no web access yourself; "
                + "this runs the search somewhere else and hands you back only the facts. It may "
                + "answer that there is NO canonical design — for a song or a meme that is the "
                + "correct answer, and you should build from the child's words and say so, rather "
                + "than implying an official version exists. Skip it for creatures the child "
                + "invented: their words are the spec and there is nothing to look up.";
    }

    @Override
    public JsonObject inputSchema() {
        JsonObject s = Schemas.object();
        Schemas.prop(s, "character", "string",
                "The character's name exactly as the child said it, e.g. 'the frog knight' or 'Captain "
                        + "Comet'.");
        return Schemas.required(s, "character");
    }

    @Override
    public ToolResult call(JsonObject args) {
        String character = args != null && args.has("character")
                ? args.get("character").getAsString() : "";
        if (character.isBlank()) {
            return ToolResult.error("Which character? Pass the name the child used.");
        }
        // THE AGENT'S ARGUMENT IS A HINT, NOT THE KEY.
        //
        // The background prefetch started the moment the wish arrived, keyed on whatever
        // CharacterResolver made of the wish text. If the key were taken from the argument instead,
        // the agent could ask for "Spikor, King of the Kroaks" and miss a fetch that is already
        // sitting in the cache under "spikor" -- then wait, or give up. Resolving from the same
        // wish text makes that impossible by construction.
        // it asked itself, so the ride-along reference would only repeat this answer.
        var askedFor = com.moddingfromamod.agent.AgentContext.currentRun();
        if (askedFor != null) {
            com.moddingfromamod.reference.ReferenceRideAlong.agentAsked(askedFor.wishId().toString());
        }
        String intent = com.moddingfromamod.provenance.ProvenanceContext.currentIntent();
        var resolved = com.moddingfromamod.reference.CharacterResolver.resolveSubject(intent);
        // A REAL PERSON WITH NO PUBLIC ARTICLE is not searched for, however the agent asks
        // (D1): a classmate's name must start no web search on the server's sign-in.
        var privatePerson =
                com.moddingfromamod.reference.CharacterResolver.privatePerson(intent);
        if (privatePerson.isPresent()) {
            return ToolResult.ok(privatePersonAnswer(privatePerson.get()));
        }
        // a creature a child wished for in this world is not a character to research, however the
        // agent asks: that search finds somebody else's creature of the same name.
        if (resolved.isEmpty() && com.moddingfromamod.reference.OwnCreatures.wished(character)) {
            LOGGER.info("look_up_character('{}'): a creature a child wished for, not looked up (#2645)", character);
            return ToolResult.ok("\"" + character + "\" is a creature a child already wished for in this world, "
                    + "not a character from a game, show or book, so there is nothing to look up: the web would "
                    + "only find somebody else's creature with the same name. Work from the creature itself "
                    + "(modify_mob changes it; describe it from what it is).");
        }
        String canonical = resolved.map(r -> r.name()).orElse(character);
        // The agent asked, so it is looked up even when the resolver said NONE -- but as a
        // character, since that is what this tool is for; a creature's photos are only measured
        // when the resolver itself said CREATURE.
        var kind = resolved.map(r -> r.kind())
                .orElse(com.moddingfromamod.reference.SubjectKind.CHARACTER);
        if (!canonical.equalsIgnoreCase(character)) {
            LOGGER.info("look_up_character('{}') resolved to '{}' from the wish", character,
                    canonical);
        }
        // WAIT HERE, BECAUSE THIS IS THE MOMENT THAT DECIDES THE CREATURE.
        //
        // This used to return immediately with "build from your own knowledge; I will check the
        // result afterwards", and the timing says why that could never be enough. Over 25 real
        // wishes the agent calls THIS TOOL at a median of 9s and `sculpt_creature` at 33-56s,
        // against a cold lookup of 48s. Answering at 9s means the FIRST sculpt -- the one that
        // decides what the creature IS -- is always built from memory, and the reference is
        // demoted to a grading step. Waiting turns it back into an input.
        //
        // Nothing a CHILD is waiting on blocks here: the wish is already being granted and the
        // progress narration is already running. ReferenceResearcher.MAX_WAIT_SECONDS stays 0 for
        // the submission path, which is a genuinely different situation.
        // A REAL PERSON: the resolver kept the kind only because Wikipedia has an article
        // for the name. No researcher run -- its pictures of a real person are photographs, and a
        // likeness built from a photo of a real face is not built (2026-10-02). What makes
        // a Minecraft YouTuber recognisable is his skin, and paint_surface can paint it.
        //
        // the skin only when an outside source LINKS an account to him. With no link the
        // fallback is the reference-lookup picture: what he LOOKS LIKE IN MINECRAFT, asked
        // as a character (renders of his skin), never a researcher run over photographs.
        if (kind == com.moddingfromamod.reference.SubjectKind.PERSON) {
            // minecraft.wiki, then the generated creator list: either one licenses the skin.
            var link = com.moddingfromamod.reference.SkinLink.resolve(canonical);
            if (link instanceof com.moddingfromamod.reference.SkinLink.Found) {
                return ToolResult.ok(personAnswer(canonical));
            }
            var look = ReferenceResearcher.lookUp(skinSubject(canonical), WAIT_SECONDS,
                    com.moddingfromamod.reference.SubjectKind.CHARACTER);
            return ToolResult.ok(unlinkedPersonAnswer(canonical,
                    look.isEmpty() ? null : look.describe()));
        }
        var spec = ReferenceResearcher.lookUp(canonical, WAIT_SECONDS, kind);
        if (spec.isEmpty()) {
            return ToolResult.ok("No usable reference for \"" + canonical + "\" — nothing was "
                    + "found, or what was found was not about a character anyone could build. "
                    + "Build it from the child's own words, and say in your note that you could "
                    + "not check what it really looks like. Do not invent an official version.");
        }
        return ToolResult.ok(spec.describe());
    }

    /** What the agent is told about a real public person: paint the skin, never a face. */
    static String personAnswer(String name) {
        return "\"" + name + "\" is a REAL PERSON and a public figure, so a picture of them is fine "
                + "— as their MINECRAFT SKIN, never a face drawn from memory or from a photo. Call "
                + "paint_surface with from_skin = \"" + name + "\" (the name they are known by, "
                + "NEVER a username) and leave 'picture' out: the server finds the account "
                + "minecraft.wiki or the generated creator list links to them and paints that "
                + "skin pixel for pixel. skin_view "
                + "'face' (the default) is their head; 'front' is the whole player. If the skin "
                + "cannot be found, tell the child plainly — do not invent one.";
    }

    /**
     * A real person the public-figure gate closed.: a creator
     * the GENERATED LIST links to an account gets his own skin painted -- and only that. He is
     * still not a public figure: this answers before any lookup, so nothing is searched, no
     * researcher runs and no photograph is fetched ({@code PublicFigure} never reads the list).
     */
    static String privatePersonAnswer(String name) {
        if (com.moddingfromamod.reference.CreatorAccounts.lookup(name) != null) {
            return listedCreatorAnswer(name);
        }
        return "\"" + name + "\" sounds like a real person who is "
                + "not a public figure (there is no Wikipedia article for the name), so nothing "
                + "is looked up and no skin is fetched. Build it from the child's own words, "
                + "kindly and in cartoon style, and do not try to make it look like a real "
                + "person.";
    }

    /**
     * A creator only the generated creator list links to an account: his skin, and
     * nothing else. Not "a public figure" — the list licenses the skin only.
     */
    static String listedCreatorAnswer(String name) {
        return "\"" + name + "\" is a Minecraft creator whom the generated creator list links to "
                + "their own Minecraft account, so paint THIS CREATOR'S OWN SKIN: call "
                + "paint_surface with from_skin = \"" + name + "\" (the name they are known by, "
                + "NEVER a username) and leave 'picture' out — the server fetches only the account "
                + "the list links to them and paints that skin pixel for pixel. skin_view 'face' "
                + "(the default) is their head; 'front' is the whole player. That skin is the only "
                + "picture of them: nothing else about them is looked up, so never draw their face "
                + "from memory or from a photo. If the skin cannot be found, tell the child "
                + "plainly — do not invent one.";
    }

    /** The subject asked for when a person has no linked account: his look in Minecraft. */
    static String skinSubject(String name) {
        return name + "'s Minecraft skin";
    }

    /**
     * A public person with no account linked to them: no from_skin — the account with
     * his public name may be a stranger's — so the picture is painted from the lookup.
     */
    static String unlinkedPersonAnswer(String name, String described) {
        String head = "\"" + name + "\" is a REAL PERSON and a public figure, but no outside source "
                + "links them to a Minecraft account, so do NOT call paint_surface with from_skin "
                + "— the account with that name may be somebody else's, and it would be refused. "
                + "Never draw their face from memory or from a photo. ";
        if (described == null) {
            return head + "Nothing usable was found about how they look in Minecraft either: "
                    + "paint a simple picture from the child's words and tell the child it is "
                    + "your picture of " + name + ", not their real skin.";
        }
        return head + "Paint them with paint_surface's 'picture' from what they look like in "
                + "Minecraft, below — its colours and their proportions — and tell the child it "
                + "is your picture of " + name + ", not their real skin.\n" + described;
    }
}
