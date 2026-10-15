/*
 * COMP3011 Assignment 2 playback-worker communication.
 *
 * Authors:
 *   1. Yuxuan Bai (a2993969), with OpenAI Codex AI assistance.
 */
package comp3011;

import java.io.File;
import java.util.Objects;

/**
 * Immutable commands sent from the JavaFX thread to the playback worker.
 */
sealed interface PlaybackCommand {
    record Play(File file) implements PlaybackCommand {
        public Play {
            Objects.requireNonNull(file, "file");
        }
    }

    record SeekTo(long timestampUs) implements PlaybackCommand {
    }

    record SeekBy(long offsetUs) implements PlaybackCommand {
    }

    record AudioFailure(long playbackGeneration, String message) implements PlaybackCommand {
        public AudioFailure {
            Objects.requireNonNull(message, "message");
        }
    }

    enum TogglePause implements PlaybackCommand {
        INSTANCE
    }

    enum ToggleAudio implements PlaybackCommand {
        INSTANCE
    }

    enum Stop implements PlaybackCommand {
        INSTANCE
    }

    enum Shutdown implements PlaybackCommand {
        INSTANCE
    }
}
