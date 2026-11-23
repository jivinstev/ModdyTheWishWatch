package com.moddingfromamod.paint;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.ProfileNotFoundException;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Fetch a Minecraft player's skin, by username, on the server.</b>
 *
 * <p>The profile is resolved through <b>authlib</b> — the same {@code GameProfileRepository} and
 * {@code fetchProfile} the game uses for player heads — so the Mojang API's shapes are the game's
 * problem rather than a hand-rolled parser's. The PNG itself is fetched here, and ONLY from
 * {@code textures.minecraft.net}: a profile's texture URL is data from a third party, and a URL
 * this server will GET is the shape of every SSRF there is.
 *
 * <p>⚠ <b>The profile hands out {@code http://} URLs and plain http is refused</b> (measured
 * 2026-10-02 from a cloud container: http 403, the same URL over https 200). So the scheme is
 * UPGRADED, never followed — which it should be anyway.
 *
 * <p>Nothing here decides WHOSE skin may be fetched. That is {@code PublicFigure}'s job, and
 * {@code paint_surface} asks it first: every child with an account has a username, so without the
 * gate a sibling's name would fetch a private child's skin.
 *
 * <p>Costs $0 and about a second, against 48–109 s for a researcher lookup. Each skin is cached by
 * uuid + texture hash, and the RECIPE made from it is stored with the picture, so a replay of the
 * picture never fetches anything.
 */
public final class SkinFetch {

    private static final Logger LOGGER = LoggerFactory.getLogger(SkinFetch.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /** A skin is 64×64 at most; anything near this is not one. */
    static final int MAX_BYTES = 64 * 1024;

    /** The only host a skin PNG is fetched from. */
    static final String TEXTURE_HOST = "textures.minecraft.net";

    /** What a Mojang username can be. Anything else is answered "no such player" without asking. */
    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{2,16}");

    private static final Pattern TEXTURE_PATH = Pattern.compile("/texture/[0-9a-fA-F]{16,128}");

    /** A skin found, and where it came from — everything provenance records. */
    public record Skin(String username, UUID uuid, String textureUrl, String textureHash,
                       boolean slim, byte[] png) {
    }

    /** The three answers, each needing a different sentence for the child. */
    public sealed interface Result permits Found, NoSuchPlayer, Failed {
    }

    public record Found(Skin skin) implements Result {
    }

    public record NoSuchPlayer(String username) implements Result {
    }

    public record Failed(String why) implements Result {
    }

    /** Where skins come from — the real Mojang services, or a test's. */
    @FunctionalInterface
    public interface Source {
        Result fetch(String username);
    }

    private static final Source LIVE = SkinFetch::live;

    private static volatile Source source = LIVE;

    /** Skin bytes by {@code uuid:hash}. A texture hash names its bytes, so this never goes stale. */
    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();

    private static volatile YggdrasilAuthenticationService auth;

    private SkinFetch() {
    }

    /** The skin for a username. Never throws. */
    public static Result fetch(String username) {
        String name = username == null ? "" : username.trim();
        if (!USERNAME.matcher(name).matches()) {
            return new NoSuchPlayer(name);
        }
        try {
            return source.fetch(name);
        } catch (RuntimeException e) {
            LOGGER.info("skin fetch for '{}' failed: {}", name, e.toString());
            return new Failed("the skin service did not answer");
        }
    }

    /** A test's skins instead of Mojang's. {@link #reset()} afterwards. */
    public static void useSource(Source s) {
        source = s == null ? LIVE : s;
    }

    public static void reset() {
        source = LIVE;
    }

    /**
     * The URL a profile gave, made safe to GET: upgraded to https, and refused unless it is a
     * texture on {@link #TEXTURE_HOST}. Null for anything else — a different host, a port, a
     * query, userinfo, a path that is not a texture.
     */
    static URI textureUri(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(raw.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            if (!TEXTURE_HOST.equalsIgnoreCase(u.getHost()) || u.getPort() != -1
                    || u.getRawUserInfo() != null || u.getRawQuery() != null
                    || u.getRawFragment() != null || u.getRawPath() == null
                    || !TEXTURE_PATH.matcher(u.getRawPath()).matches()) {
                return null;
            }
            return URI.create("https://" + TEXTURE_HOST + u.getRawPath());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ───────────────────────────── the real services ─────────────────────────────

    private static Result live(String name) {
        YggdrasilAuthenticationService svc = auth();
        GameProfile[] found = new GameProfile[1];
        Exception[] failed = new Exception[1];
        svc.createProfileRepository().findProfilesByNames(new String[]{name},
                new ProfileLookupCallback() {
                    @Override
                    public void onProfileLookupSucceeded(GameProfile profile) {
                        found[0] = profile;
                    }

                    @Override
                    public void onProfileLookupFailed(String n, Exception e) {
                        failed[0] = e;
                    }
                });
        if (found[0] == null) {
            if (failed[0] == null || failed[0] instanceof ProfileNotFoundException) {
                return new NoSuchPlayer(name);
            }
            LOGGER.info("skin: profile lookup for '{}' failed: {}", name, failed[0].toString());
            return new Failed("the Minecraft profile service did not answer");
        }
        MinecraftSessionService session = svc.createMinecraftSessionService();
        var result = session.fetchProfile(found[0].getId(), false);
        if (result == null) {
            return new Failed("the Minecraft profile service did not answer");
        }
        MinecraftProfileTexture tex = session.getTextures(result.profile()).skin();
        if (tex == null) {
            return new Failed(found[0].getName() + " has never changed the default skin, so there "
                    + "is no skin of their own to paint");
        }
        URI uri = textureUri(tex.getUrl());
        if (uri == null) {
            LOGGER.warn("skin: refusing a texture URL off {}: {}", TEXTURE_HOST, tex.getUrl());
            return new Failed("the skin is not on Minecraft's own texture server");
        }
        String hash = tex.getHash();
        String key = found[0].getId() + ":" + hash;
        byte[] png = CACHE.get(key);
        if (png == null) {
            png = download(uri);
            if (png == null) {
                return new Failed("the skin picture could not be downloaded");
            }
            CACHE.put(key, png);
        }
        // ⚠ THE FIRST LIVE FETCH ON A SERVER IS ITS EGRESS CHECK: this
        // line in its log is what proves sessionserver and textures.minecraft.net are reachable.
        LOGGER.info("skin: fetched {} ({}) from {} — {} bytes, model {}", found[0].getName(),
                found[0].getId(), uri, png.length,
                "slim".equals(tex.getMetadata("model")) ? "slim" : "classic");
        return new Found(new Skin(found[0].getName(), found[0].getId(), uri.toString(), hash,
                "slim".equals(tex.getMetadata("model")), png));
    }

    private static YggdrasilAuthenticationService auth() {
        YggdrasilAuthenticationService a = auth;
        if (a == null) {
            synchronized (SkinFetch.class) {
                if (auth == null) {
                    // THE MACHINE'S PROXY, if it has one. The game's own services are built with
                    // Proxy.NO_PROXY, which on a host whose egress goes through a proxy (a cloud
                    // session) never reaches Mojang at all; a server with none gets DIRECT.
                    Proxy proxy = Proxy.NO_PROXY;
                    try {
                        var ps = ProxySelector.getDefault()
                                .select(URI.create("https://sessionserver.mojang.com/"));
                        if (!ps.isEmpty()) {
                            proxy = ps.get(0);
                        }
                    } catch (RuntimeException ignored) {
                        // no selector: DIRECT
                    }
                    auth = new YggdrasilAuthenticationService(proxy);
                }
                a = auth;
            }
        }
        return a;
    }

    private static byte[] download(URI uri) {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            HttpResponse<byte[]> res = c.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200 || res.body() == null || res.body().length == 0
                    || res.body().length > MAX_BYTES) {
                LOGGER.info("skin: {} answered {} ({} bytes)", uri, res.statusCode(),
                        res.body() == null ? 0 : res.body().length);
                return null;
            }
            return res.body();
        } catch (Exception e) {
            LOGGER.info("skin: {} failed: {}", uri, e.toString());
            return null;
        }
    }
}
