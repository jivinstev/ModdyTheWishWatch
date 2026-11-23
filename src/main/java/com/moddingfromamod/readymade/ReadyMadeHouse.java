package com.moddingfromamod.readymade;

import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.agent.AgentBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>The live index: what ships, plus what this server has made</b> (kind (a)).
 *
 * <p>Read fresh for each question rather than cached — the server's stores change with every wish, and a
 * stale (a) entry would point the worker at a creature that was removed an hour ago. Each store is
 * read in its own guard: one store failing to read costs that store's entries, never the index.
 */
public final class ReadyMadeHouse {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReadyMadeHouse.class);

    private ReadyMadeHouse() {
    }

    /** Everything, (a) included. Never throws. */
    public static ReadyMadeIndex index() {
        List<ReadyMade> out = new ArrayList<>(guard("shipped", ReadyMadeSources::shipped));
        out.addAll(guard("house", ReadyMadeHouse::house));
        out.addAll(guard("starter buttons", ReadyMadeSources::starterButtons));
        return new ReadyMadeIndex(out);
    }

    /** The server's own things as (a) entries, read on the server thread. */
    static List<ReadyMade> house() throws Exception {
        return AgentBridge.onServer(() -> {
            List<ReadyMade> out = new ArrayList<>();
            out.addAll(guard("creatures", ReadyMadeHouse::creatures));
            out.addAll(guard("items", ReadyMadeHouse::items));
            out.addAll(guard("builds", ReadyMadeHouse::builds));
            out.addAll(guard("buttons", ReadyMadeHouse::buttons));
            return out;
        }, 4_000L);
    }

    private static List<ReadyMade> creatures() {
        List<ReadyMade> out = new ArrayList<>();
        for (var s : com.moddingfromamod.species.SpeciesRegistry.all()) {
            String words = s.name() + (s.powers().isEmpty() ? "" : " (" + s.powers() + ")");
            out.add(ReadyMadeSources.priorWish("creature", s.id(), s.name(), words,
                    ReadyMadeSources.priorUse("creature", s.id())));
        }
        return out;
    }

    private static List<ReadyMade> items() {
        List<ReadyMade> out = new ArrayList<>();
        for (var s : com.moddingfromamod.item.ItemSpecStore.all()) {
            String words = s.name() + (s.effects().isEmpty() ? "" : ", does " + s.effects());
            out.add(ReadyMadeSources.priorWish("item", s.id(), s.name(), words,
                    ReadyMadeSources.priorUse("item", s.id())));
        }
        return out;
    }

    private static List<ReadyMade> builds() {
        List<ReadyMade> out = new ArrayList<>();
        for (String id : com.moddingfromamod.builder.BuildRegistry.ids()) {
            var plan = com.moddingfromamod.builder.BuildRegistry.get(id);
            String name = plan == null ? id : plan.name();
            out.add(ReadyMadeSources.priorWish("building", id, name, name,
                    ReadyMadeSources.priorUse("building", id)));
        }
        return out;
    }

    /** Learned buttons, described by the wish that taught them — never by a bare id. */
    private static List<ReadyMade> buttons() {
        List<ReadyMade> out = new ArrayList<>();
        for (var p : com.moddingfromamod.wish.LearnedTemplates.asPresets()) {
            String id = p.templateId() == null || p.templateId().isBlank() ? p.label() : p.templateId();
            out.add(ReadyMadeSources.priorWish("button", id, p.label(), p.wishText(),
                    "the watch already grants \"" + p.wishText() + "\" by itself"));
        }
        return out;
    }

    private interface Source {
        List<ReadyMade> get() throws Exception;
    }

    private static List<ReadyMade> guard(String what, Source s) {
        try {
            List<ReadyMade> r = s.get();
            return r == null ? List.of() : r;
        } catch (Throwable t) {
            LOGGER.debug("ready-made index: could not read {}: {}", what, t.toString());
            return List.of();
        }
    }
}
