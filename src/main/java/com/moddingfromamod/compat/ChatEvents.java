package com.moddingfromamod.compat;

import java.net.URI;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/**
 * <b>Clickable and hoverable chat</b>. 1.21.5 made {@code ClickEvent} and {@code HoverEvent}
 * sealed interfaces with a record per action ({@code ClickEvent.RunCommand(command)},
 * {@code OpenUrl(URI)}, {@code HoverEvent.ShowText(Component)}…). These keep the old
 * {@code new ClickEvent(action, value)} shape at every call site, and read the value back for tests.
 */
public final class ChatEvents {

    private ChatEvents() {
    }

    /** What {@code new ClickEvent(action, value)} built on 1.21.4. */
    public static ClickEvent click(ClickEvent.Action action, String value) {
        return switch (action) {
            case RUN_COMMAND -> new ClickEvent.RunCommand(value);
            case SUGGEST_COMMAND -> new ClickEvent.SuggestCommand(value);
            case OPEN_URL -> new ClickEvent.OpenUrl(URI.create(value));
            case COPY_TO_CLIPBOARD -> new ClickEvent.CopyToClipboard(value);
            case CHANGE_PAGE -> new ClickEvent.ChangePage(Integer.parseInt(value));
            default -> throw new IllegalArgumentException("ChatEvents.click: no string form for " + action);
        };
    }

    /** What {@code new HoverEvent(HoverEvent.Action.SHOW_TEXT, text)} built. */
    public static HoverEvent hoverText(Component text) {
        return new HoverEvent.ShowText(text);
    }

    /** What {@code click.getValue()} returned: the command, the address or the text; "" for none. */
    public static String value(ClickEvent click) {
        return switch (click) {
            case ClickEvent.RunCommand r -> r.command();
            case ClickEvent.SuggestCommand s -> s.command();
            case ClickEvent.OpenUrl u -> u.uri().toString();
            case ClickEvent.CopyToClipboard c -> c.value();
            case ClickEvent.ChangePage p -> String.valueOf(p.page());
            default -> "";
        };
    }
}
