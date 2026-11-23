package com.moddingfromamod.wishbuilder;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BERT's uncased WordPiece, the part a clause needs: lower-case, accents dropped, punctuation split off,
 * then the longest vocabulary piece first, {@code ##} for the rest of a word. Pure; the same code, line for line, as
 * {@code WordPiece} in {@code tools/apprentice-layer.py}, and {@code ./tools/apprentice-gate.sh} holds the two to
 * the same ids on every clause it has when the model is there.
 */
public final class WordPiece {

    /** The sentence model reads at most this many pieces; a clause is a handful. */
    public static final int MOST = 64;

    private final Map<String, Integer> vocab = new HashMap<>();
    private final int unk;
    private final int cls;
    private final int sep;

    /** {@code lines}: vocab.txt, one piece a line; a piece's id is its line number. */
    public WordPiece(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            vocab.put(lines.get(i), i);
        }
        unk = need("[UNK]");
        cls = need("[CLS]");
        sep = need("[SEP]");
    }

    private int need(String piece) {
        Integer id = vocab.get(piece);
        if (id == null) {
            throw new IllegalArgumentException("not a WordPiece vocabulary: no " + piece);
        }
        return id;
    }

    public long[] ids(String text) {
        String flat = Normalizer.normalize(text.toLowerCase(java.util.Locale.ROOT), Normalizer.Form.NFD);
        List<String> words = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < flat.length(); ) {
            int ch = flat.codePointAt(i);
            i += Character.charCount(ch);
            if (Character.getType(ch) == Character.NON_SPACING_MARK) {
                continue;
            }
            if (Character.isWhitespace(ch)) {
                flush(words, cur);
            } else if (!Character.isLetterOrDigit(ch)) {
                flush(words, cur);
                words.add(new String(Character.toChars(ch)));
            } else {
                cur.appendCodePoint(ch);
            }
        }
        flush(words, cur);
        List<Integer> out = new ArrayList<>();
        out.add(cls);
        for (String w : words) {
            if (w.length() > 100) {
                out.add(unk);
                continue;
            }
            List<Integer> pieces = new ArrayList<>();
            int start = 0;
            while (start < w.length()) {
                int end = w.length();
                Integer piece = null;
                while (end > start) {
                    piece = vocab.get((start > 0 ? "##" : "") + w.substring(start, end));
                    if (piece != null) {
                        break;
                    }
                    end--;
                }
                if (piece == null) {
                    pieces = List.of(unk);
                    break;
                }
                pieces.add(piece);
                start = end;
            }
            out.addAll(pieces);
        }
        int n = Math.min(out.size(), MOST - 1);
        long[] ids = new long[n + 1];
        for (int i = 0; i < n; i++) {
            ids[i] = out.get(i);
        }
        ids[n] = sep;
        return ids;
    }

    private static void flush(List<String> words, StringBuilder cur) {
        if (cur.length() > 0) {
            words.add(cur.toString());
            cur.setLength(0);
        }
    }
}
