package com.moddingfromamod.marshal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Which photographs belong to which of his things.
 *
 * <p><b>The one gap {@code docs/WISH_CARD.md} names.</b> The agent has always named its shots
 * freely — {@code baby_dragon.png}, {@code above_factory.png} — so nothing on disk was keyed to an
 * artifact and the card had no way to ask for a picture of Spikor.
 *
 * <p>Two things make that cheap to fix rather than expensive. Sculpts <b>already</b> photograph as
 * four-angle turnarounds named {@code <id>__0_face}, {@code __1_left}, {@code __2_back},
 * {@code __3_right}; and an agent naming a shot after the thing it photographed lands on the id by
 * accident most of the time. So the backfill is a <b>match</b>, not a migration: of 68 artifacts on
 * this machine, 22 already have an exact photo and 6 have a whole turnaround — including Spikor.
 * Nothing is renamed, nothing is moved, and no file can be lost by getting this wrong.
 *
 * <p>Pure apart from the directory listing, so the ordering rules are unit-tested.
 */
public final class PhotoIndex {

    /** How many the carousel will show. Newest four, so a summer of shots is not a hundred dots. */
    public static final int MAX_PHOTOS = 6;

    /** The turnaround's own order: face first, because that is the one that reads as a portrait. */
    private static final List<String> ANGLE_ORDER =
            List.of("__0_", "__1_", "__2_", "__3_");

    private PhotoIndex() {
    }

    private static final java.util.concurrent.atomic.AtomicLong LISTINGS =
            new java.util.concurrent.atomic.AtomicLong();

    /** How many times the photos directory has been listed — a test's instrument. */
    static long listingsForTest() {
        return LISTINGS.get();
    }

    /** Drop any remembered listing — for a test that writes files and looks again. */
    static synchronized void forgetListingForTest() {
        cachedDir = null;
        cachedStems = List.of();
    }

    /**
     * <b>One listing, reused until the directory changes.</b> The watch asks for every
     * creation, for every player, twice a second; listing the directory per ask put the server
     * thread in {@code Files.list} for 727 of 769 s in a thread dump of a hung Gate B, and costs a
     * live server the same shape at a smaller size. A photo landing or leaving moves the
     * directory's modification time, which invalidates the listing at once; {@link #FRESH_MS} is
     * the backstop for a filesystem whose mtime is coarser than the writes.
     */
    private static final long FRESH_MS = 2_000L;
    private static Path cachedDir;
    private static long cachedMtime;
    private static long cachedAtMs;
    private static List<String> cachedStems = List.of();

    /** Every {@code .png} stem in the directory, lower-cased, from the listing above. */
    private static synchronized List<String> stems(Path photosDir) {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(photosDir).toMillis();
        } catch (IOException e) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        if (photosDir.equals(cachedDir) && mtime == cachedMtime && now - cachedAtMs < FRESH_MS) {
            return cachedStems;
        }
        LISTINGS.incrementAndGet();
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(photosDir)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith(".png")) {
                    out.add(name.substring(0, name.length() - 4).toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        cachedDir = photosDir;
        cachedMtime = mtime;
        cachedAtMs = now;
        cachedStems = List.copyOf(out);
        return cachedStems;
    }

    /**
     * Every photograph of this thing, best first.
     *
     * @param photosDir where the client writes its screenshots
     * @param id        the artifact id
     * @return file names WITHOUT the extension, or empty — never null
     */
    public static List<String> forArtifact(Path photosDir, String id) {
        if (photosDir == null || id == null || id.isBlank() || !Files.isDirectory(photosDir)) {
            return List.of();
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (String stem : stems(photosDir)) {
            // EXACT, or one of ITS turnaround frames. Deliberately not a prefix match:
            // "spikor" must not collect "spikor_castle_view", which is a picture of a
            // different thing that merely mentions him.
            if (stem.equals(key) || stem.startsWith(key + "__")) {
                found.add(stem);
            }
        }
        found.sort(bestFirst(key));
        return found.size() > MAX_PHOTOS ? found.subList(0, MAX_PHOTOS) : found;
    }

    /**
     * The plain shot first, then the turnaround in its own order.
     *
     * <p>Order is not cosmetic: the first frame is what the card shows before he touches anything,
     * so it should be the portrait rather than whichever angle happens to sort first
     * alphabetically — which would be the BACK of the creature's head about a quarter of the time.
     */
    static Comparator<String> bestFirst(String key) {
        return Comparator.comparingInt((String stem) -> {
            if (stem.equals(key)) {
                return -1;                       // the plain shot, if there is one
            }
            for (int i = 0; i < ANGLE_ORDER.size(); i++) {
                if (stem.contains(ANGLE_ORDER.get(i))) {
                    return i;                    // face, left, back, right
                }
            }
            return ANGLE_ORDER.size();
        }).thenComparing(Comparator.naturalOrder());
    }
}
