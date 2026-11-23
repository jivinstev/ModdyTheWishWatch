package com.moddingfromamod.reference;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b> speed: the (b) describer on Ember, one direct call with the pictures in it.</b>
 *
 * <p>The Haiku describer was a {@code claude -p} process that read each picture with a tool and answered on its
 * second turn: 10-17 s cold, most of it the process and the turns, not the pictures (3 pictures at 512 px cost
 * the same as 5 at 768). Muse Ember reads images (measured: an orange square answered "Orange" in 1.2 s), and
 * its API takes them inline, so the whole description is one request. Ember is an approved small model for jobs
 * that are not a grant (CLAUDE.md's wish-models rule); it never grants a wish.
 *
 * <p><b>Responses API at effort minimal</b>. The Messages API takes no effort below low, and at
 * low it spent all 4000 output tokens thinking on 5 calls in 25 and gave no answer (each then waited ~16 s for
 * the Haiku fallback). At minimal through {@code /v1/responses}: 25 of 25 answered, median 2.4 s against 4.7 s, at a
 * quarter of the cost (corpus/describer-bench/2026-11-19).
 *
 * <p>Only the pictures and our own fixed prompt go out: no wish text, no name, no player data (the launch audit's
 * egress row for the describer). Returns null whenever it cannot answer (no key, AI not allowed on this host,
 * an error, a timeout), and the caller falls back to the Haiku describer.
 */
public final class EmberVision {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmberVision.class);

    /** Meta's Responses API: the one that takes {@code reasoning.effort = minimal}. A test points it elsewhere. */
    static volatile String endpoint = "https://api.meta.ai/v1/responses";
    static volatile Duration timeout = Duration.ofSeconds(30);
    /** Room for Ember's own thinking as well as a 100-word answer. */
    static final int MAX_TOKENS = 4000;
    /** The effort it asks for: minimal unless {@link BriefLooks#EFFORT_PROPERTY} says otherwise. */
    static final String EFFORT = "minimal";

    static volatile java.util.function.Supplier<String> keySource = () -> System.getenv("MUSE_API_KEY");

    private EmberVision() {
    }

    /** Whether there is an API key to call with (the describer's direct key step runs at all). */
    static boolean hasKey() {
        String key = keySource.get();
        return key != null && !key.isBlank() && !"subscription".equals(key.strip());
    }

    /** The answer's text, or null. */
    public static String describe(String system, String prompt, List<java.awt.image.BufferedImage> pictures) {
        if (com.moddingfromamod.marshal.LocalOnly.refuse("a Muse vision call")) {   //: a Wish Builder make is local only
            return null;
        }
        String key = keySource.get();
        if (key == null || key.isBlank() || "subscription".equals(key.strip()) || pictures == null
                || pictures.isEmpty() || !com.moddingfromamod.config.Magician.allowsAi()) {
            return null;
        }
        long t0 = System.currentTimeMillis();
        try {
            HttpResponse<String> res = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout)
                            .header("Authorization", "Bearer " + key.strip())
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    body(system, prompt, pictures, BriefLooks.effort(EFFORT)).toString()))
                            .build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                LOGGER.info("Ember describer answered HTTP {} in {} ms", res.statusCode(), System.currentTimeMillis() - t0);
                return null;
            }
            String text = text(res.body());
            LOGGER.info("Ember describer: {} picture(s), {} chars in {} ms", pictures.size(),
                    text == null ? -1 : text.length(), System.currentTimeMillis() - t0);
            return text;
        } catch (Exception e) {
            LOGGER.info("Ember describer failed after {} ms: {}", System.currentTimeMillis() - t0, e.toString());
            return null;
        }
    }

    /** The request: our system prompt as the instructions, each picture as a PNG, then the one-line ask. */
    static JsonObject body(String system, String prompt, List<java.awt.image.BufferedImage> pictures, String effort)
            throws Exception {
        JsonArray content = new JsonArray();
        for (java.awt.image.BufferedImage img : pictures) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", png);
            JsonObject image = new JsonObject();
            image.addProperty("type", "input_image");
            image.addProperty("image_url", "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray()));
            content.add(image);
        }
        JsonObject ask = new JsonObject();
        ask.addProperty("type", "input_text");
        ask.addProperty("text", prompt);
        content.add(ask);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.add("content", content);
        JsonArray input = new JsonArray();
        input.add(user);
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", effort);
        JsonObject body = new JsonObject();
        body.addProperty("model", com.moddingfromamod.config.SupportedModels.EMBER);
        body.addProperty("max_output_tokens", MAX_TOKENS);
        body.addProperty("instructions", system);
        body.add("input", input);
        body.add("reasoning", reasoning);
        return body;
    }

    /** The output text of an answer's message items, joined; null when there is none. */
    static String text(String responseBody) {
        JsonObject r = JsonParser.parseString(responseBody).getAsJsonObject();
        if (!r.has("output") || !r.get("output").isJsonArray()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement item : r.getAsJsonArray("output")) {
            JsonObject o = item.getAsJsonObject();
            if (!"message".equals(o.has("type") ? o.get("type").getAsString() : "") || !o.has("content")
                    || !o.get("content").isJsonArray()) {
                continue;
            }
            for (JsonElement c : o.getAsJsonArray("content")) {
                JsonObject cc = c.getAsJsonObject();
                if ("output_text".equals(cc.has("type") ? cc.get("type").getAsString() : "") && cc.has("text")) {
                    sb.append(cc.get("text").getAsString());
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
