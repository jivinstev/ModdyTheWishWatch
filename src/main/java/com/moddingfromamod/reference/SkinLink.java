package com.moddingfromamod.reference;

import java.util.UUID;

import com.google.gson.JsonObject;

/**
 * <b>An outside source's statement that a person plays as a particular Java account </b>,
 * the only thing {@code paint_surface from_skin} fetches a skin for. Two sources say it, asked in
 * this order by {@link #resolve}:
 * <ol>
 *   <li>minecraft.wiki's {@code {{Infobox person}}}, read live ({@link MinecraftWikiPerson});</li>
 *   <li>the generated creator list ({@link CreatorAccounts}), a local table, so it answers even
 *       when the wiki cannot be reached.</li>
 * </ol>
 * Neither consults Mojang for a name nobody links, and the model never supplies the username.
 */
public interface SkinLink {

    /** The Java username the source names. */
    String username();

    /** The account the source pinned, when it pinned one; the fetched skin must be this account. */
    default UUID pinnedUuid() {
        return null;
    }

    /** "minecraft.wiki" / "the generated creator list (…)", for the sentences the agent reads. */
    String sourceName();

    /** Where the link came from, for provenance ({@code skin.json}'s {@code linkedBy}). */
    JsonObject toJson();

    /** What the sources said: a link, nothing, or (only when nothing local answers) no answer. */
    sealed interface Result permits Found, Unlinked, Unreachable {
    }

    record Found(SkinLink link) implements Result {
    }

    record Unlinked(String publicName) implements Result {
    }

    record Unreachable(String publicName) implements Result {
    }

    /**
     * minecraft.wiki first (the primary source), then the generated list. A wiki that
     * cannot be read is UNREACHABLE only when the list has no row either: the list is local.
     */
    static Result resolve(String publicName) {
        MinecraftWikiPerson.Result wiki = MinecraftWikiPerson.resolve(publicName);
        if (wiki instanceof MinecraftWikiPerson.Linked linked) {
            return new Found(linked);
        }
        CreatorAccounts.Row row = CreatorAccounts.lookup(publicName);
        if (row != null) {
            return new Found(row);
        }
        return wiki instanceof MinecraftWikiPerson.Unreachable
                ? new Unreachable(publicName) : new Unlinked(publicName);
    }
}
