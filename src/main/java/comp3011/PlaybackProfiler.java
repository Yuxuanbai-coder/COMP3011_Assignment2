/*
 * Optional playback timing diagnostics for COMP3011 Assignment 2.
 *
 * Authors:
 *   1. Yuxuan Bai (a2993969), with OpenAI Codex AI assistance.
 */
package comp3011;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Collects low-overhead stage timings while enabled through the
 * {@code VIDEOPLAYER_PROFILE=true} environment variable.
 */
final class PlaybackProfiler {
    private static final long REPORT_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);

    private final boolean enabled = Boolean.parseBoolean(System.getenv("VIDEOPLAYER_PROFILE"));
    private final String[] processorNames;
    private final long[] processorNanos;

    private long decodeNanos;
    private long audioCopyNanos;
    private long conversionNanos;
    private long completedFrames;
    private long windowStartedNs;

    PlaybackProfiler(List<FrameProcessor> processors) {
        processorNames = processors.stream()
                .map(processor -> processor.getClass().getSimpleName())
                .toArray(String[]::new);
        processorNanos = new long[processorNames.length];
    }

    boolean enabled() {
        return enabled;
    }

    void recordDecode(long elapsedNs) {
        if (!enabled) {
            return;
        }
        startWindowIfNeeded(elapsedNs);
        decodeNanos += elapsedNs;
    }

    void recordAudioCopy(long elapsedNs) {
        if (enabled) {
            audioCopyNanos += elapsedNs;
        }
    }

    void recordProcessor(int processorIndex, long elapsedNs) {
        if (enabled) {
            processorNanos[processorIndex] += elapsedNs;
        }
    }

    void recordConversion(long elapsedNs) {
        if (enabled) {
            conversionNanos += elapsedNs;
        }
    }

    void recordCompletedFrame() {
        if (!enabled) {
            return;
        }

        completedFrames++;
        long now = System.nanoTime();
        if (now - windowStartedNs >= REPORT_INTERVAL_NS) {
            reportAndReset(now);
        }
    }

    private void startWindowIfNeeded(long firstDecodeDurationNs) {
        if (windowStartedNs == 0) {
            windowStartedNs = System.nanoTime() - firstDecodeDurationNs;
        }
    }

    private void reportAndReset(long now) {
        if (completedFrames == 0) {
            windowStartedNs = now;
            return;
        }

        // Wall FPS includes scheduling gaps; active-stage FPS counts only measured decode/copy/effect/convert work.
        long effectsNanos = Arrays.stream(processorNanos).sum();
        long measuredNanos = decodeNanos + audioCopyNanos + effectsNanos + conversionNanos;
        long wallNanos = now - windowStartedNs;
        double wallFramesPerSecond = completedFrames * TimeUnit.SECONDS.toNanos(1) / (double) wallNanos;
        double activeFramesPerSecond = measuredNanos > 0
                ? completedFrames * TimeUnit.SECONDS.toNanos(1) / (double) measuredNanos
                : 0.0;

        System.err.printf(
                Locale.ROOT,
                "[profile] %d frames; %.1f wall fps; %.1f active-stage fps; avg ms/frame: "
                        + "grab %.2f, audio-copy %.2f, effects %.2f, convert %.2f%n",
                completedFrames,
                wallFramesPerSecond,
                activeFramesPerSecond,
                averageMillis(decodeNanos),
                averageMillis(audioCopyNanos),
                averageMillis(effectsNanos),
                averageMillis(conversionNanos));

        for (int i = 0; i < processorNanos.length; i++) {
            double share = measuredNanos > 0 ? processorNanos[i] * 100.0 / measuredNanos : 0.0;
            System.err.printf(
                    Locale.ROOT,
                    "[profile]   effect[%d] %s: %.2f ms/frame (%.1f%% of measured stage time)%n",
                    i,
                    processorNames[i],
                    averageMillis(processorNanos[i]),
                    share);
        }

        decodeNanos = 0;
        audioCopyNanos = 0;
        conversionNanos = 0;
        completedFrames = 0;
        Arrays.fill(processorNanos, 0);
        windowStartedNs = now;
    }

    private double averageMillis(long totalNanos) {
        return completedFrames == 0
                ? 0.0
                : totalNanos / (double) completedFrames / 1_000_000.0;
    }
}
