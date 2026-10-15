/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Yuxuan Bai (a2993969), with OpenAI Codex AI assistance.
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.nio.Buffer;
import java.nio.ShortBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import org.bytedeco.javacv.Frame;

/**
 * Streams decoded audio from JavaCV frames to the Java Sound output device, in support of the video player being able
 * to play the sound track as well. This is a good test of a player because sound continuity (or lack thereof) is easy
 * for the ear to pick up on if/when playback gets laggy.
 *
 * <p>
 * Converts supported packed or planar 16-bit sample buffers to the
 * interleaved, little-endian PCM format required by {@link SourceDataLine}.
 * The playback worker converts samples into independent PCM packets, while a
 * dedicated audio-output worker owns this class and writes those packets.
 * </p>
 */
public class AudioPlayer implements AutoCloseable {
    private static final long WRITE_TRACE_THRESHOLD_NS = 2_000_000L;
    private static final int AUDIO_WARMUP_BYTES = 4096;

    private SourceDataLine line; // Java Sound's audio-output interface - for us a destination, not a source.

    public void open(int sampleRate, int channels) throws LineUnavailableException {
        if (sampleRate <= 0 || channels <= 0) {
            throw new IllegalArgumentException("The media has invalid audio format metadata.");
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        line = AudioSystem.getSourceDataLine(format);
        line.open(format);
        line.start();
        warmUpLine();
    }

    /** Converts supported decoded samples to independent interleaved little-endian PCM bytes. */
    public static byte[] copySamples(Frame frame) {
        if (frame.samples == null || frame.samples.length == 0) {
            return new byte[0];
        }

        Buffer[] samples = frame.samples;
        int channels = frame.audioChannels;

        if (channels > 1 && samples.length == channels && samples[0] instanceof ShortBuffer) {
            ShortBuffer[] planarSamples = new ShortBuffer[channels];
            int sampleCount = Integer.MAX_VALUE;

            for (int channel = 0; channel < channels; channel++) {
                if (!(samples[channel] instanceof ShortBuffer channelSamples)) {
                    return new byte[0];
                }
                planarSamples[channel] = channelSamples.duplicate();
                sampleCount = Math.min(sampleCount, planarSamples[channel].remaining());
            }

            if (sampleCount <= 0 || sampleCount == Integer.MAX_VALUE) {
                return new byte[0];
            }
            byte[] pcm = new byte[sampleCount * channels * Short.BYTES];
            int outputIndex = 0;
            for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
                for (ShortBuffer channelSamples : planarSamples) {
                    outputIndex = writeLittleEndian(pcm, outputIndex, channelSamples.get());
                }
            }
            return pcm;
        }

        if (samples[0] instanceof ShortBuffer packedSamples) {
            ShortBuffer source = packedSamples.duplicate();
            byte[] pcm = new byte[source.remaining() * Short.BYTES];
            int outputIndex = 0;
            while (source.hasRemaining()) {
                outputIndex = writeLittleEndian(pcm, outputIndex, source.get());
            }
            return pcm;
        }

        return new byte[0];
    }

    public int write(byte[] samples, int offset, int length) {
        if (line == null || samples.length == 0 || length <= 0) {
            return 0;
        }

        // Try and avoid blocking writes to the audio stream by only adding equal or less than the number of bytes
        // available. We return only the bytes we were able to write without overflowing the buffer, so the remaining
        // bytes can be tracked and written later. This avoids audio buffer blocking in a cooperative multitasking
        // architecture - i.e. we are being cooperative and not stalling the thread on line.write below!
        int availableBeforeWrite = line.available();
        int byteCount = Math.min(length, availableBeforeWrite);
        if (byteCount <= 0) {
            return 0;
        }

        long startNs = System.nanoTime();
        int written = line.write(samples, offset, byteCount); // Will block if we get it wrong.
        long elapsedNs = System.nanoTime() - startNs;
        if (elapsedNs >= WRITE_TRACE_THRESHOLD_NS) {
            // Hopefully you'll never see this.
            System.out.printf(
                    "WARNING: Audio write blocked for %dus writing %d bytes, line available before write: %d bytes%n",
                    elapsedNs / 1000,
                    written,
                    availableBeforeWrite);
        }
        return written;
    }

    public void flush() {
        if (line != null) {
            line.flush();
        }
    }

    /** Waits until the sound already written to the device has played. */
    public void drain() {
        if (line != null) {
            line.drain();
        }
    }

    @Override
    public void close() {
        SourceDataLine closingLine = line;
        line = null;
        if (closingLine != null) {
            try {
                closingLine.stop();
            } catch (RuntimeException e) {
                // The device may not have finished opening when an error occurred.
            }
            try {
                closingLine.flush();
            } catch (RuntimeException e) {
                // Continue to close the device even if it never started.
            }
            try {
                closingLine.close();
            } catch (RuntimeException e) {
                // The line is already closed.
            }
        }
    }

    // Why do we have this? Some (many) audio output systems have a long 'wake up' delay before they output the first
    // audio. This is not great for real time playback of audio that is synched with video frames. It can lead to a
    // noticeable lag between the vision and the audio. This method writes a bunch of silence into the audio system
    // then flushes it out as soon as it is written to get any wake up lag out of the way, so that it is hopefully ready
    // for real time output when the real audio bytes start showing up.
    private void warmUpLine() {
        byte[] silence = new byte[Math.min(AUDIO_WARMUP_BYTES, line.getBufferSize())]; // All zeros.
        line.write(silence, 0, silence.length); // May play, but harmless.
        line.flush(); // Get rid of the rest straight away, hopefully warm up is done.
    }

    private static int writeLittleEndian(byte[] destination, int offset, short sample) {
        destination[offset++] = (byte) (sample & 0xff);
        destination[offset++] = (byte) ((sample >> 8) & 0xff);
        return offset;
    }

}
