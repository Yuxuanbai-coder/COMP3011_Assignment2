/*
 * COMP3011 Assignment 2 playback-worker communication.
 *
 * Authors:
 *   1. Yuxuan Bai (a2993969), with OpenAI Codex AI assistance.
 */
package comp3011;

import java.util.Objects;

/**
 * A timestamped copy of decoded PCM samples transferred to the audio output worker.
 */
final class AudioPacket {
    private final long playbackGeneration;
    private final long timestampUs;
    private final byte[] pcmSamples;

    AudioPacket(long playbackGeneration, long timestampUs, byte[] pcmSamples) {
        this.playbackGeneration = playbackGeneration;
        this.timestampUs = timestampUs;
        this.pcmSamples = Objects.requireNonNull(pcmSamples, "pcmSamples").clone();
    }

    long playbackGeneration() {
        return playbackGeneration;
    }

    long timestampUs() {
        return timestampUs;
    }

    /** Returns a copy so the queued packet's contents cannot be mutated by its receiver. */
    byte[] copyPcmSamples() {
        return pcmSamples.clone();
    }
}
