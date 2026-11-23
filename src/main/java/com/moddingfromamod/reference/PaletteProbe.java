package com.moddingfromamod.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Measure one character's palette from a wiki page, and report how long it took.
 *
 * <pre>./gradlew classpathFile
 * java -cp "$(cat build/runtime-classpath.txt)" \
 *      com.moddingfromamod.reference.PaletteProbe "Spikor" https://www.plumowiki.com/Spikor</pre>
 *
 * <p>Two jobs, deliberately in one place. It is <b>Gate 4's stopwatch</b> — the whole point of
 * moving the palette out of a model is that the arithmetic path can be inline, and that claim is
 * only worth what the measured number says. And it is <b>Gate 5's measured arm</b>: comparing a
 * counted palette against a remembered one needs the counted one to come from the real
 * {@link WikiArtwork} → {@link ReferenceImages} → {@link ReferencePalette} chain, not from a script
 * that reimplements it and drifts.
 *
 * <p>Prints per-image rows as well as the aggregate, because the aggregate is a median and a median
 * hides exactly the thing worth seeing — which render disagreed, and how far.
 */
public final class PaletteProbe {

    private PaletteProbe() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: PaletteProbe <character> <wiki page url> [more urls...]");
            System.exit(2);
        }
        String character = args[0];
        List<String> pages = new ArrayList<>(List.of(args).subList(1, args.length));

        long t0 = System.nanoTime();
        List<String> urls = WikiArtwork.urlsFrom(pages, ReferenceImages.MAX_IMAGES);
        long tResolve = System.nanoTime();
        var fetched = ReferenceImages.fetch(urls);
        long tFetch = System.nanoTime();

        List<Map<String, Double>> per = new ArrayList<>();
        for (var f : fetched) {
            per.add(ReferencePalette.of(f.image()));
        }
        ReferencePalette.Reading r = ReferencePalette.across(per);
        long tDone = System.nanoTime();

        System.out.println("character: " + character);
        System.out.println("urls resolved: " + urls.size() + "  downloaded: " + fetched.size());
        for (int i = 0; i < fetched.size(); i++) {
            var f = fetched.get(i);
            Map<String, Double> p = per.get(i);
            System.out.printf("  %-58s %dx%d opaque=%.2f%s%n",
                    f.url().substring(Math.max(0, f.url().length() - 58)),
                    f.image().getWidth(), f.image().getHeight(),
                    p.getOrDefault(ReferencePalette.OPAQUE_FRACTION, 0.0),
                    ReferencePalette.usable(p) ? "" : "   <-- REJECTED (no subject mask)");
            System.out.println("      " + top(p, 4));
        }
        System.out.println();
        if (r.isEmpty()) {
            System.out.println("RESULT: no palette — " + r.images() + " usable image(s), needs "
                    + ReferencePalette.MIN_IMAGES);
        } else {
            System.out.println("RESULT: " + r.colours());
            System.out.printf("        from %d render(s); leave-one-out swing %.0fpp; "
                    + "top colour stable=%s; confidence ceiling %s%n",
                    r.images(), r.topSwingPct(), r.topStable(), r.confidenceCeiling());
        }
        System.out.printf("TIMING: resolve %.2fs  download %.2fs  count %.2fs  TOTAL %.2fs%n",
                ms(t0, tResolve) / 1000.0, ms(tResolve, tFetch) / 1000.0,
                ms(tFetch, tDone) / 1000.0, ms(t0, tDone) / 1000.0);
        // Machine-readable, so a sweep does not have to scrape the human rows above.
        System.out.println("JSON " + json(character, r, ms(t0, tDone)));
    }

    private static String top(Map<String, Double> p, int n) {
        return p.entrySet().stream()
                .filter(e -> !e.getKey().equals(ReferencePalette.OPAQUE_FRACTION))
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(n)
                .map(e -> String.format("%s %.0f%%", e.getKey(), e.getValue()))
                .reduce((a, b) -> a + ", " + b).orElse("(nothing)");
    }

    private static long ms(long a, long b) {
        return (b - a) / 1_000_000;
    }

    private static String json(String c, ReferencePalette.Reading r, long totalMs) {
        StringBuilder sb = new StringBuilder("{\"character\":\"").append(c)
                .append("\",\"images\":").append(r.images())
                .append(",\"stable\":").append(r.topStable())
                .append(",\"swing\":").append(Math.round(r.topSwingPct()))
                .append(",\"ms\":").append(totalMs)
                .append(",\"colours\":[");
        for (int i = 0; i < r.colours().size(); i++) {
            var x = r.colours().get(i);
            sb.append(i > 0 ? "," : "").append("{\"colour\":\"").append(x.colour())
              .append("\",\"percent\":").append(x.percent()).append('}');
        }
        return sb.append("]}").toString();
    }
}
