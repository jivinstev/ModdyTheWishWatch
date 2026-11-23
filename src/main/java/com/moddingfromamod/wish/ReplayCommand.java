package com.moddingfromamod.wish;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>Is this command a replay of something already kept?</b> — Minecraft-free.
 *
 * <p>The agent puts back a kept creature, building, machine, train, picture, item or event through
 * {@code run_command} with the very command its button runs ({@link ReplayContract#ALL}), and that
 * makes nothing new — so nothing needs photographing, and the wish's camera is let go
 * ({@code CameraNeed.reused}). Read off the register rather than a second list, so a class added
 * there is a replay here; {@code wishsummon} (a vanilla mob with a kept {@code modify_mob} spec) is
 * the one replay the register does not hold.
 */
public final class ReplayCommand {

    /** The forms, as token lists; {@code demo_id} stands for any one id. */
    private static final List<String[]> FORMS = forms();

    private ReplayCommand() {
    }

    private static List<String[]> forms() {
        List<String[]> out = new ArrayList<>();
        for (ReplayContract.Replayable r : ReplayContract.ALL) {
            out.add(r.sample().split(" "));
        }
        out.add(new String[] {"wishsummon", "demo_id"});
        return out;
    }

    /** True when {@code command} (with or without its slash, any trailing arguments) replays a kept thing. */
    public static boolean isReplay(String command) {
        if (command == null) {
            return false;
        }
        String c = command.strip();
        if (c.startsWith("/")) {
            c = c.substring(1);
        }
        String[] t = c.toLowerCase(Locale.ROOT).split("\\s+");
        for (String[] form : FORMS) {
            if (t.length < form.length) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < form.length && ok; i++) {
                ok = "demo_id".equals(form[i]) ? !t[i].isEmpty() : form[i].equals(t[i]);
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }
}
