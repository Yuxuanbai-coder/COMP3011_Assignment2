/*
 * COMP3011 Assignment 2 playback-worker communication.
 *
 * Authors:
 *   1. Yuxuan Bai (a2993969), with OpenAI Codex AI assistance.
 */
package comp3011;

/**
 * Immutable notifications sent from the playback worker to the JavaFX thread.
 */
sealed interface PlaybackEvent {
    enum FrameCleared implements PlaybackEvent {
        INSTANCE
    }

    enum ShutdownComplete implements PlaybackEvent {
        INSTANCE
    }

    record VideoSizeChanged(int width, int height) implements PlaybackEvent {
    }

    record StatusChanged(String status) implements PlaybackEvent {
    }

    record PlaybackStateChanged(boolean playing, boolean paused) implements PlaybackEvent {
    }

    record AudioOutputStateChanged(boolean enabled) implements PlaybackEvent {
    }
}
