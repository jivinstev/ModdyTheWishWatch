package com.moddingfromamod.wish;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a README's command table: {@code PublicCommandsReadmeTest} and the private build's check. */
public final class CommandsReadme {

    private CommandsReadme() {
    }

    private static final Pattern ROW = Pattern.compile("^\\|\\s*`/([^`]+)`");

    /** The commands the README's "## Commands" section names: a row's first cell, as {@link CommandTree#paths}. */
    public static TreeSet<String> documented(String readme, String section) {
        int at = readme.indexOf("\n## " + section + "\n");
        assertTrue(at >= 0, "the README has no '## " + section + "' section");
        int end = readme.indexOf("\n## ", at + 4);
        TreeSet<String> out = new TreeSet<>();
        for (String line : readme.substring(at, end < 0 ? readme.length() : end).split("\n")) {
            Matcher m = ROW.matcher(line);
            if (!m.find()) {
                continue;
            }
            List<String> words = new ArrayList<>();
            for (String w : m.group(1).trim().split("\\s+")) {
                if (!w.matches("[a-z][a-z0-9_]*")) {
                    break;   // an argument (<id>, [words], …) ends the path
                }
                words.add(w);
            }
            String root = words.get(0);
            out.add((root.equals("moddy") || root.equals("moddyop")) && words.size() > 1 ? root + " " + words.get(1) : root);
        }
        return out;
    }

}
