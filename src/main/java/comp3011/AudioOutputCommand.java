/*
 * COMP3011 Assignment 2 audio-output worker communication.
 *
 * Authors:
 *   1. Yuxuan Bai (a2993969), with OpenAI Codex AI assistance.
 */
package comp3011;

/** Commands that configure or synchronise the dedicated audio-output worker. */
sealed interface AudioOutputCommand {
    record Open(long playbackGeneration, int sampleRate, int channels) implements AudioOutputCommand {
    }

    /** Rebinds the output line to a new seek generation and discards its old buffered sound. */
    record Reset(long playbackGeneration) implements AudioOutputCommand {
    }

    /** Flushes queued sound after pause or mute without changing the playback generation. */
    record Flush(long playbackGeneration) implements AudioOutputCommand {
    }

    record Close(long playbackGeneration) implements AudioOutputCommand {
    }

    /** Drain the final decoded packets, play buffered sound, and then close the output line. */
    record DrainAndClose(long playbackGeneration) implements AudioOutputCommand {
    }

    enum Shutdown implements AudioOutputCommand {
        INSTANCE
    }
}
