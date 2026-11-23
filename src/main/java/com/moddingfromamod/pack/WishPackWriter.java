package com.moddingfromamod.pack;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moddingfromamod.ModdingFromAModMod;
import com.moddingfromamod.item.IconSprites;
import com.moddingfromamod.vanilla.AtomLibrary;

/**
 * <b>Writes the pack a client with no mod needs, from anywhere — including a server.</b>
 *
 * <p>This is the generation half of {@code client.spike.VanillaItemPackClient}, moved down so it is
 * reachable from a <b>dedicated server</b>. That is not tidying: the item spike's whole delivery
 * gap is that
 * the only thing which had ever produced the pack was a {@code Dist.CLIENT} class, so on the
 * shipping default a client without the mod got a brown horse armour for every wished item and
 * nothing anywhere could have written it one. A server cannot push what it cannot build.
 *
 * <p>{@code VanillaItemPackClient} now calls this rather than carrying its own copy, so there is
 * <b>one</b> definition of what the pack contains — the failure mode this repo has already paid for
 * twice being two that agree today.
 *
 * <h2>Who calls it</h2>
 *
 * <p>{@code vanilla/ItemPackService} — at server start, published to the pack host and offered to
 * every client with no mod on join. <b>That caller did not exist for the whole life of this
 * class</b>: the writer worked, was reachable only from {@code LiteProbe} and a
 * {@code Dist.CLIENT} spike, and so a stock child was handed a leather horse armour for every
 * wished thing while every gate stayed green. Do not remove that call without reading
 * {@code StockClientIconTest}, which is the gate that would otherwise say nothing.
 *
 * <h2>What it deliberately does NOT do</h2>
 *
 * <p>It does not host, push, or decide when to refresh — {@code ItemPackService} owns all three.
 * The refresh question turns out to be trivial for THIS pack and only this one: the content is
 * static ({@code WishIcons.all()} is a {@code List.of}), so it is a build-time artefact published
 * once, and an earlier issue's ~6.7 s stock reload lands inside a join rather than inside a wish.
 *
 * <p>It also carries the atom rig's constant cube library ({@link #atoms}), on the same carrier
 * definition, because this is the one pack a stock client is offered at join.
 */
public final class WishPackWriter {

    /** The carrier the item route rides on, as a bare path (the pack keys models by path). */
    public static final String BASE_ITEM = "leather_horse_armor";

    private WishPackWriter() {
    }

    /**
     * Write the pack into {@code dir} and return it. Rewritten every call, so a stale pack from an
     * earlier mode cannot be silently reused — the break modes below depend on that.
     *
     * @param mode {@code on} for the real pack; {@code shift} renumbers every binding one higher
     *             (nothing missing, every icon wearing its neighbour's artwork — which renders
     *             beautifully and is completely wrong); {@code drop} emits the carrier's item
     *             definition with no {@code range_dispatch} entries at all, only the plain-carrier
     *             fallback — what a client with no pack draws for a stack carrying a number. The two
     *             break modes are the oracle's verify-to-fail.
     */
    public static Path write(String mode, Path dir) throws Exception {
        List<VanillaItemPack.Icon> icons = icons(mode);
        VanillaItemPack.Spec spec =
                new VanillaItemPack.Spec(BASE_ITEM, icons, atoms(mode));

        deleteTree(dir);
        Files.createDirectories(dir);

        Map<String, String> text = new LinkedHashMap<>(VanillaItemPack.textFiles(spec));
        if ("drop".equals(mode)) {
            // The same definition over NO bindings: the fallback alone, every number unbound.
            text.put(VanillaItemPack.itemDefinitionPath(BASE_ITEM), VanillaItemPack.itemDefinition(
                    new VanillaItemPack.Spec(BASE_ITEM, List.of(), List.of(), spec.dyeTint())));
        }
        for (Map.Entry<String, String> e : text.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
        }
        // the real items a Builder colour scheme tints — definitions, models and textures, the jar's own bytes.
        for (String f : TintedItems.files()) {
            byte[] bytes = classpath("/assets/" + VanillaItemPack.NAMESPACE + "/" + f);
            if (bytes == null) {
                throw new IllegalStateException("tinted item file " + f + " is not on the classpath");
            }
            Path p = dir.resolve("assets/" + VanillaItemPack.NAMESPACE + "/" + f);
            Files.createDirectories(p.getParent());
            Files.write(p, bytes);
        }
        List<String> toCopy = new ArrayList<>(sprites(icons, spec.extras()));
        for (String overlay : com.moddingfromamod.item.IconOverlays.NAMES) {
            toCopy.add(VanillaItemPack.overlaySprite(overlay));   // The overlay library
        }
        for (String fx : VanillaItemPack.BACKING) {
            toCopy.add(VanillaItemPack.FX_DIR + "/" + fx);        // The sparkle backing
        }
        for (String sprite : toCopy) {
            byte[] png = classpath("/assets/" + ModdingFromAModMod.MODID
                    + "/textures/item/" + sprite + ".png");
            if (png == null) {
                throw new IllegalStateException("icon sprite " + sprite + " is not on the classpath");
            }
            Path p = dir.resolve(VanillaItemPack.texturePath(sprite));
            Files.createDirectories(p.getParent());
            Files.write(p, png);
            // An ANIMATED sprite's .mcmeta rides with it, or a stock client draws the whole
            // strip squashed into one square -- a tall smear, not a flicker.
            byte[] meta = classpath("/assets/" + ModdingFromAModMod.MODID
                    + "/textures/item/" + sprite + ".png.mcmeta");
            if (meta != null) {
                Files.write(p.resolveSibling(p.getFileName() + ".mcmeta"), meta);
            }
            // An ANIMATED sprite (an earlier issue's wish wand) is a frame strip plus its .png.mcmeta. Without
            // the sidecar a stock client does not refuse the texture -- it draws the whole 16x128
            // strip squashed into one slot, which is a picture of eight wands and no error.
            byte[] anim = classpath("/assets/" + ModdingFromAModMod.MODID
                    + "/textures/item/" + sprite + ".png.mcmeta");
            if (anim != null) {
                Files.write(dir.resolve(VanillaItemPack.texturePath(sprite) + ".mcmeta"), anim);
            }
        }
        return dir;
    }

    /**
     * Zip the written pack and return its <b>SHA-1</b>, which is what the push needs.
     *
     * <p>The hash is the whole reason a rejoin can be cheap: {@code ClientboundResourcePackPushPacket}
     * carries it, and a client that already has a pack with that hash serves it from its own
     * download cache instead of fetching. Getting it wrong is not a hard failure, which is exactly
     * why it is computed here from the bytes rather than typed anywhere.
     *
     * <p>Deterministic on purpose — entries sorted, timestamps zeroed — so the same pack content
     * produces the same hash on two machines and a warm rejoin stays warm.
     */
    public static String zip(Path dir, Path zipFile) throws Exception {
        List<Path> files;
        try (var walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        Files.createDirectories(zipFile.getParent());
        try (OutputStream out = Files.newOutputStream(zipFile);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Path f : files) {
                ZipEntry entry = new ZipEntry(dir.relativize(f).toString().replace('\\', '/'));
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(Files.readAllBytes(f));
                zip.closeEntry();
            }
        }
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest(Files.readAllBytes(zipFile));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * The icon list, read from the mod's own models so the pack cannot drift from the mod.
     *
     * <p><b>Authored by NAME.</b> It walks {@link IconSprites#bindings()} — the one table of
     * "which sprites does the item pack bind" — and asks it for the number rather than typing one
     * ({@code docs/VERSION_STRATEGY.md} §6.2's rule in code).
     *
     * <p>MODDY HIMSELF comes out of that list too, at a reserved number above every library id and
     * drawn from the mod's OWN {@code models/item/wish_watch.json} — the same file the modded
     * client renders. The library is the set of pictures a WISH may pick and the watch must not be
     * in it: a wished thing that came back looking like the thing you wish with is the same
     * "we substituted this" / "he asked for exactly that" confusion the item spike paid for five
     * times over.
     *
     * <p>⚠ THIS IS WHY THE WRITER HAD TO MOVE OFF THE CLIENT. The vanilla wish watch ships ON by
     * default, and a client with no mod reads Moddy's face out of the PUSHED pack — so until a
     * server could build one, the panel's own button had no artwork on the only route it exists
     * for. See docs/VANILLA_WATCH_DECISIONS.md D4 and its blocker 2.
     */
    public static List<VanillaItemPack.Icon> icons(String mode) {
        List<VanillaItemPack.Icon> out = new ArrayList<>();
        for (IconSprites.Sprite sprite : IconSprites.bindings()) {
            int number = "shift".equals(mode)
                    ? sprite.customModelData() + 1 : sprite.customModelData();
            out.add(new VanillaItemPack.Icon(number, sprite.name(), layersOf(sprite.model())));
        }
        return out;
    }

    /**
     * <b>The atom rig's constant library, riding on the same pack.</b>
     *
     * <p>Why HERE rather than in a pack of its own: this is the one pack a stock client is offered
     * <b>at join</b>, before it has wished anything, and applying a pack costs ~6.7 s of frozen
     * game. The whole point of the atom rig ({@code docs/ATOM_RIG_PLAN.md}) is that a creature
     * costs no reload at all, which is only true if the library is already on the client when the
     * first creature appears. A second pack would be a second reload; a per-creature pack is the
     * thing being removed.
     *
     * <p>The content is static — two plain cubes and one per {@code SculptPattern} name, forever —
     * so it changes nothing about this pack's lifecycle: still a build-time artefact, still
     * published once at server start, still de-duplicated by sha1 on a rejoin. The vocabulary is
     * eight names and does not grow with the creatures, which is the property that makes a constant
     * library possible at all.
     */
    public static List<VanillaItemPack.Extra> atoms(String mode) {
        List<VanillaItemPack.Extra> out = new ArrayList<>();
        for (AtomLibrary.Entry e : AtomLibrary.all()) {
            out.add(new VanillaItemPack.Extra(
                    "shift".equals(mode) ? e.customModelData() + 1 : e.customModelData(),
                    e.modelName(),
                    AtomLibrary.model(VanillaItemPack.textureId(e.spriteName()),
                            e.shade()).toString()));
        }
        return out;
    }

    /** Which sprites the pack has to carry — every layer of every icon, deduplicated. */
    public static List<String> sprites(List<VanillaItemPack.Icon> icons) {
        List<String> out = new ArrayList<>();
        for (VanillaItemPack.Icon icon : icons) {
            for (String layer : icon.layers()) {
                if (!out.contains(layer)) {
                    out.add(layer);
                }
            }
        }
        return out;
    }

    /**
     * …and the sprites the verbatim models need too.
     *
     * <p>Separate overload rather than a widened one, because the atom cube's texture is not a
     * "layer" of anything and the icon list genuinely does not contain it. An earlier shape had
     * {@link #write} copy the icon sprites and silently not the atom's, which is a pack that loads,
     * reports nothing, and draws every creature as the missing texture.
     */
    public static List<String> sprites(List<VanillaItemPack.Icon> icons,
                                       List<VanillaItemPack.Extra> extras) {
        List<String> out = new ArrayList<>(sprites(icons));
        if (extras == null || extras.isEmpty()) {
            return out;
        }
        // DERIVED from the library rather than a literal, because the library now has NINE sprites
        // (one white cube shared by both plain models, plus one per pattern) and a hardwired
        // ATOM_SPRITE would ship eight pattern MODELS pointing at textures the pack does not carry
        // -- eight patterns rendering as the missing texture, on the only client this route exists
        // for, with the pack itself perfectly valid.
        for (AtomLibrary.Entry e : AtomLibrary.all()) {
            if (!out.contains(e.spriteName())) {
                out.add(e.spriteName());
            }
        }
        return out;
    }

    /**
     * A model's layers, in order, out of the mod's shipped model file.
     *
     * @param model the model's path under {@code models/item/} — {@code wish/cone}, or
     *              {@code wish_watch} for Moddy, which does not live in that folder
     */
    public static List<String> layersOf(String model) {
        String path = "/assets/" + ModdingFromAModMod.MODID + "/models/item/" + model + ".json";
        byte[] raw = classpath(path);
        if (raw == null) {
            throw new IllegalStateException("icon " + model + " has no model at " + path);
        }
        JsonObject root = JsonParser.parseString(
                new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject textures = root.getAsJsonObject("textures");
        List<String> layers = new ArrayList<>();
        for (int i = 0; textures.has("layer" + i); i++) {
            String id = textures.get("layer" + i).getAsString();
            // "moddingfromamod:item/wish/axe" -> "wish/axe". The pack re-namespaces it; keeping the
            // mod's namespace would work and would make the control unreadable.
            // ⚠ Everything after "item/", not after the last "/": Moddy's sprite is
            // item/wish_watch and the wish icons' are item/wish/<name>, so cutting at the last
            // slash would flatten both into the same folder and lose one of them.
            int cut = id.indexOf("item/");
            layers.add(cut < 0 ? id.substring(id.lastIndexOf('/') + 1)
                    : id.substring(cut + "item/".length()));
        }
        if (layers.isEmpty()) {
            throw new IllegalStateException("icon " + model + " declares no layers");
        }
        return layers;
    }

    private static byte[] classpath(String path) {
        try (InputStream in = WishPackWriter.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                    // best effort; the write above fails loudly if it mattered
                }
            });
        }
    }
}
