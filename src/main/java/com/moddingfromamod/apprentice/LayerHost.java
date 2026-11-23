package com.moddingfromamod.apprentice;

import com.moddingfromamod.wishbuilder.Apprentice;
import com.moddingfromamod.wishbuilder.ApprenticeLayer;
import com.moddingfromamod.wishbuilder.ClauseReader;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Where the Apprentice's trained layer lives while a server runs</b>: one worker thread that loads the
 * sentence model when the world is up and reads clauses on it afterwards, so the server thread never runs the model.
 *
 * <p><b>Inert without the model.</b> The layer's numbers ship in every jar; the sentence model (about 90 MB) ships
 * only in the store jar ({@link BundledModel}, copied out on first start), and a dedicated server can be given it separately. No model folder, a model that is not the one the layer was trained on, or a
 * machine ONNX Runtime has no library for: ONE log line, and the Apprentice answers from its keywords exactly as it
 * did before this existed.
 *
 * <p>Nothing typed is logged or stored here; see {@link OnnxClauseReader}.
 */
public final class LayerHost {

    private static final Logger LOGGER = LoggerFactory.getLogger("moddingfromamod/apprentice");

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "moddy-apprentice-layer");
        t.setDaemon(true);
        return t;
    });

    private record Ready(ClauseReader reader, double threshold) {
    }

    private static volatile Ready ready;
    private static OnnxClauseReader onnx;   // the worker's own

    private LayerHost() {
    }

    /** The world is up: load the model on the worker. {@code natives}: see {@link OnnxClauseReader#open}. */
    public static void start(Path modelDir, Path natives) {
        WORKER.execute(() -> {
            long t0 = System.nanoTime();
            try {
                ApprenticeLayer layer = ApprenticeLayer.bundled();
                if (layer == null) {
                    LOGGER.info("apprentice layer: off, keywords only: this build carries no trained layer");
                    return;
                }
                // A store jar carries the model (D13): copy it out once; any other jar changes nothing here.
                try {
                    String did = BundledModel.unpack(modelDir, LayerHost.class.getClassLoader(), layer::refusal);
                    if (did != null) {
                        LOGGER.info("apprentice layer: {}", did);
                    }
                } catch (java.io.IOException e) {
                    LOGGER.warn("apprentice layer: could not copy out the model this jar carries: {}", e.toString());
                }
                String why = layer.refusal(modelDir);
                if (why != null) {
                    LOGGER.info("apprentice layer: off, keywords only: {}", why);
                    return;
                }
                close();
                onnx = OnnxClauseReader.open(modelDir, layer, natives);
                onnx.read("warm up");   // the first run pays for the session's own set-up
                ready = new Ready(onnx, layer.threshold());
                LOGGER.info("apprentice layer: on ({}, {} picks, threshold {}), loaded in {} ms from {}", layer.model(),
                        layer.classes().size() - 1, layer.threshold(), (System.nanoTime() - t0) / 1_000_000, modelDir);
            } catch (Throwable e) {   // a missing native library is an Error, and must not take the worker with it
                ready = null;
                LOGGER.warn("apprentice layer: off, keywords only: the model could not be loaded: {}", e.toString());
            }
        });
    }

    /** The world is going down: let the model go. */
    public static void stop() {
        ready = null;
        WORKER.execute(LayerHost::close);
    }

    private static void close() {
        if (onnx != null) {
            onnx.close();
            onnx = null;
        }
    }

    /** Is there a layer to ask right now? */
    public static boolean ready() {
        return ready != null;
    }

    /**
     * The hybrid answer, worked out on the worker. Never completes exceptionally: any trouble is the keyword answer.
     * The words live in this one task and are dropped with it.
     */
    public static CompletableFuture<Apprentice.Answer> match(Apprentice matcher, String words) {
        Ready r = ready;
        if (r == null) {
            return CompletableFuture.completedFuture(matcher.match(words));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return matcher.match(words, r.reader(), r.threshold());
            } catch (RuntimeException e) {
                LOGGER.warn("apprentice layer: a read failed, answering from keywords: {}", e.getClass().getSimpleName());
                return matcher.match(words);
            }
        }, WORKER);
    }

    /** Test seam: a reader in place of the model (null takes it away again). */
    public static void useForTest(ClauseReader reader, double threshold) {
        ready = reader == null ? null : new Ready(reader, threshold);
    }
}
