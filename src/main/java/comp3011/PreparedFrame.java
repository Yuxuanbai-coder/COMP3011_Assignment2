/*
 * COMP3011 Assignment 2 playback-worker communication.
 *
 * Authors:
 *   1. Yuxuan Bai (a2993969), with OpenAI Codex AI assistance.
 */
package comp3011;

import javafx.scene.image.Image;

/**
 * A display-ready frame and its timing metadata, transferred to the JavaFX thread.
 */
record PreparedFrame(
        long playbackGeneration,
        Image image,
        int frameNumber,
        long mediaTimestampUs,
        long logicalTimestampUs,
        long targetTimeNs,
        long preparedAtNs) {

    /** Keeps the current single-threaded playback path source-compatible during the staged refactor. */
    PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
        this(0, image, frameNumber, mediaTimestampUs, logicalTimestampUs, targetTimeNs, preparedAtNs);
    }
}
