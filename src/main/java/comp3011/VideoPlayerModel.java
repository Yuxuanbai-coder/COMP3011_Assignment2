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

import java.io.File;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Coordinates a background playback thread and publishes frames and playback
 * state to the JavaFX thread through queues. The background thread owns the
 * decoder, playback clock, audio scheduling, and frame processing; the JavaFX
 * thread only displays due frames and updates the view through callbacks.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    private static final int FRAME_QUEUE_CAPACITY = 3;
    private static final int AUDIO_QUEUE_CAPACITY = 64;

    private final List<FrameProcessor> frameProcessors;
    private final Queue<PendingAudio> pendingAudio = new ArrayDeque<>();
    // The playback worker consumes commands and produces frames and UI events.
    private final BlockingQueue<PlaybackCommand> playbackCommands = new LinkedBlockingQueue<>();
    private final BlockingQueue<PreparedFrame> preparedFrames = new ArrayBlockingQueue<>(FRAME_QUEUE_CAPACITY);
    // Reserved for separating audio sample transfer from audio output in a later stage.
    private final BlockingQueue<AudioPacket> audioPackets = new ArrayBlockingQueue<>(AUDIO_QUEUE_CAPACITY);
    private final BlockingQueue<PlaybackEvent> playbackEvents = new LinkedBlockingQueue<>();
    private final AtomicLong displayedTimestampUs = new AtomicLong();
    private final AtomicLong relativeSeekBaseUs = new AtomicLong(NO_SEEK_REQUEST);
    private final AtomicLong activePlaybackGeneration = new AtomicLong();
    private final AtomicBoolean shutdownCommandQueued = new AtomicBoolean();
    private volatile boolean audioOutputEnabledSnapshot;
    private boolean pausedSnapshot;
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private final Thread playbackWorker;
    private volatile boolean shutdownRequested;

    // The fields below are owned by playbackWorker and are not read by the UI thread.
    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    private boolean audioOutputEnabled;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long playbackGeneration;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessorOption> frameProcessorOptions,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        audioOutputEnabledSnapshot = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        // The model owns the processor instances; mapping the ordered selections here preserves order and repetitions.
        this.frameProcessors = frameProcessorOptions.stream()
                .map(FrameProcessorOption::createProcessor)
                .toList();

        playbackWorker = new Thread(this::runPlaybackWorker, "video-playback-worker");
        playbackWorker.setDaemon(true);
        playbackWorker.start();
        playbackTimer.start();
    }

    public void play(File file) {
        enqueueCommand(new PlaybackCommand.Play(file));
    }

    public void startOver() {
        enqueueCommand(new PlaybackCommand.SeekTo(0));
    }

    public void backFiveSeconds() {
        enqueueCommand(new PlaybackCommand.SeekBy(-FIVE_SECONDS_US));
    }

    public void forwardFiveSeconds() {
        enqueueCommand(new PlaybackCommand.SeekBy(FIVE_SECONDS_US));
    }

    public void togglePause() {
        enqueueCommand(PlaybackCommand.TogglePause.INSTANCE);
    }

    public void toggleAudioOutput() {
        enqueueCommand(PlaybackCommand.ToggleAudio.INSTANCE);
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabledSnapshot;
    }

    public void stopPlayback() {
        enqueueCommand(PlaybackCommand.Stop.INSTANCE);
    }

    public void shutdown() {
        if (shutdownCommandQueued.compareAndSet(false, true)) {
            enqueueCommand(PlaybackCommand.Shutdown.INSTANCE);
        }
    }

    private void enqueueCommand(PlaybackCommand command) {
        if (!shutdownCommandQueued.get() || command == PlaybackCommand.Shutdown.INSTANCE) {
            playbackCommands.offer(command);
        }
    }

    private void runPlaybackWorker() {
        try {
            while (!shutdownRequested) {
                PlaybackCommand command = playbackCommands.poll();
                if (command != null) {
                    handlePlaybackCommand(command);
                }
                if (shutdownRequested) {
                    break;
                }

                if (playbackOpen) {
                    writeDueAudio(System.nanoTime());
                    if (!pauseRequested && preparedFrames.remainingCapacity() > 0) {
                        prepareNextFrame();
                    } else if (command == null) {
                        command = playbackCommands.poll(5, TimeUnit.MILLISECONDS);
                        if (command != null) {
                            handlePlaybackCommand(command);
                        }
                    }
                } else if (command == null) {
                    command = playbackCommands.poll(100, TimeUnit.MILLISECONDS);
                    if (command != null) {
                        handlePlaybackCommand(command);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownRequested = true;
        } finally {
            closePlaybackResources();
            playbackEvents.offer(PlaybackEvent.ShutdownComplete.INSTANCE);
        }
    }

    private void handlePlaybackCommand(PlaybackCommand command) {
        if (command instanceof PlaybackCommand.Play playCommand) {
            videoFile = playCommand.file();
            videoDurationUs = NO_SEEK_REQUEST;
            videoFrameDurationUs = 0;
            currentTimestampUs = 0;
            startPlayback(0, false, NO_SEEK_REQUEST);
        } else if (command instanceof PlaybackCommand.SeekTo seekCommand) {
            if (videoFile != null) {
                seekTo(seekCommand.timestampUs());
            }
        } else if (command instanceof PlaybackCommand.SeekBy seekCommand) {
            seekRelative(seekCommand.offsetUs());
        } else if (command == PlaybackCommand.TogglePause.INSTANCE) {
            togglePauseOnPlaybackWorker();
        } else if (command == PlaybackCommand.ToggleAudio.INSTANCE) {
            audioOutputEnabled = !audioOutputEnabled;
            audioOutputEnabledSnapshot = audioOutputEnabled;
            pendingAudio.clear();
            flushAudioOutput();
            notifyAudioOutputStateChanged();
        } else if (command == PlaybackCommand.Stop.INSTANCE) {
            closePlaybackResources();
            currentTimestampUs = 0;
            displayedTimestampUs.set(0);
            relativeSeekBaseUs.set(NO_SEEK_REQUEST);
            pauseRequested = false;
            notifyStatusChanged("Stopped");
            notifyFrameCleared();
            notifyPlaybackStateChanged();
        } else if (command == PlaybackCommand.Shutdown.INSTANCE) {
            shutdownRequested = true;
        }
    }

    private void togglePauseOnPlaybackWorker() {
        if (!playbackOpen) {
            if (videoFile != null) {
                long timestampUs = displayedTimestampUs.get();
                startPlayback(displayableSeekTimestamp(timestampUs), false, timestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs.set(NO_SEEK_REQUEST);

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long seekBaseUs = relativeSeekBaseUs.get();
        long baseTimestampUs = seekBaseUs != NO_SEEK_REQUEST
                ? seekBaseUs
                : displayedTimestampUs.get();
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            playbackGeneration++;
            activePlaybackGeneration.set(playbackGeneration);
            preparedFrames.clear();
            audioPackets.clear();
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        playbackGeneration++;
        activePlaybackGeneration.set(playbackGeneration);
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        displayedTimestampUs.set(currentTimestampUs);
        relativeSeekBaseUs.set(initialRelativeSeekBaseUs);

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        preparedFrames.clear();
        audioPackets.clear();
        currentTimestampUs = logicalTimestampUs;
        displayedTimestampUs.set(logicalTimestampUs);
        relativeSeekBaseUs.set(logicalTimestampUs);
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            long pauseDurationNs = now - pauseStartedNs;
            playbackStartNs += pauseDurationNs;
            retimeQueuedFrames(pauseDurationNs);
        }
        pauseStartedNs = 0;
    }

    private void retimeQueuedFrames(long pauseDurationNs) {
        if (pauseDurationNs <= 0 || preparedFrames.isEmpty()) {
            return;
        }

        PreparedFrame frame;
        while ((frame = preparedFrames.poll()) != null) {
            preparedFrames.offer(new PreparedFrame(
                    frame.playbackGeneration(),
                    frame.image(),
                    frame.frameNumber(),
                    frame.mediaTimestampUs(),
                    frame.logicalTimestampUs(),
                    frame.targetTimeNs() + pauseDurationNs,
                    frame.preparedAtNs() + pauseDurationNs));
        }
    }

    private void prepareNextFrame() {
        if (!playbackOpen || pauseRequested || preparedFrames.remainingCapacity() == 0) {
            return;
        }

        try {
            PreparedFrame frame = readNextVideoFrame();
            if (frame != null && frame.playbackGeneration() == playbackGeneration) {
                preparedFrames.offer(frame);
            }
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private PreparedFrame readNextVideoFrame() throws Exception {
        while (playbackOpen) {
            Frame frame = grabFrame();
            if (frame == null) {
                finishPlayback();
                return null;
            }

            long timestampUs = grabber.getTimestamp();
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            if (!frameProcessorsInitialised) {
                initialiseFrameProcessors(new InfoVideo(
                        mediaName(videoFile),
                        totalVideoFrames,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        frameRate,
                        intFrameRate,
                        videoFrameDurationUs,
                        grabber.getPixelFormat()));
                frameProcessorsInitialised = true;
            }

            if (firstTimestampUs == NO_SEEK_REQUEST) {
                firstTimestampUs = timestampUs;
                playbackStartNs = System.nanoTime();
                if (pauseRequested) {
                    pauseStartedNs = playbackStartNs;
                }
            }

            int frameNumber = grabber.getFrameNumber();
            InfoFrame info = new InfoFrame(frameNumber, timestampUs);
            processFrame(frame, info);

            Image image = converter.convert(frame);
            long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);
            long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
            long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

            return new PreparedFrame(
                    playbackGeneration,
                    image,
                    frameNumber,
                    timestampUs,
                    logicalTimestampUs,
                    targetTimeNs,
                    System.nanoTime());
        }

        return null;
    }

    // This is called on the JavaFX thread. It never waits for decoding: it only drains
    // completed state events and displays a frame already prepared by playbackWorker.
    private void pumpPlayback(long now) {
        drainPlaybackEvents();
        if (pausedSnapshot) {
            return;
        }

        PreparedFrame latestDueFrame = null;
        while (true) {
            PreparedFrame nextFrame = preparedFrames.peek();
            if (nextFrame == null) {
                break;
            }
            if (nextFrame.playbackGeneration() != activePlaybackGeneration.get()) {
                preparedFrames.poll();
                continue;
            }
            if (nextFrame.targetTimeNs() > now) {
                break;
            }
            latestDueFrame = preparedFrames.poll();
        }

        if (latestDueFrame != null) {
            displayPreparedFrame(now, latestDueFrame);
        }
    }

    private void drainPlaybackEvents() {
        PlaybackEvent event;
        while ((event = playbackEvents.poll()) != null) {
            if (event instanceof PlaybackEvent.VideoSizeChanged sizeChanged) {
                videoSizeChangedHandler.accept(sizeChanged.width(), sizeChanged.height());
            } else if (event instanceof PlaybackEvent.StatusChanged statusChanged) {
                statusChangedHandler.accept(statusChanged.status());
            } else if (event instanceof PlaybackEvent.PlaybackStateChanged stateChanged) {
                pausedSnapshot = stateChanged.paused();
                playbackStateChangedHandler.accept(stateChanged.playing(), stateChanged.paused());
            } else if (event instanceof PlaybackEvent.AudioOutputStateChanged audioStateChanged) {
                audioOutputStateChangedHandler.accept(audioStateChanged.enabled());
            } else if (event == PlaybackEvent.FrameCleared.INSTANCE) {
                frameReadyHandler.accept(null);
            } else if (event == PlaybackEvent.ShutdownComplete.INSTANCE) {
                playbackTimer.stop();
            }
        }
    }

    private void displayPreparedFrame(long now, PreparedFrame frame) {
        displayedTimestampUs.set(frame.logicalTimestampUs());
        relativeSeekBaseUs.set(NO_SEEK_REQUEST);

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (frame.targetTimeNs() - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        frameReadyHandler.accept(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        displayedTimestampUs.set(endTimestampUs);
        relativeSeekBaseUs.set(endTimestampUs);
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackOpen = false;
        preparedFrames.clear();
        audioPackets.clear();
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void processFrame(Frame frame, InfoFrame info) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.process(frame, info);
        }
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        playbackEvents.offer(new PlaybackEvent.VideoSizeChanged(width, height));
    }

    private void notifyFrameCleared() {
        playbackEvents.offer(PlaybackEvent.FrameCleared.INSTANCE);
    }

    private void notifyStatusChanged(String status) {
        playbackEvents.offer(new PlaybackEvent.StatusChanged(status));
    }

    private void notifyPlaybackStateChanged() {
        playbackEvents.offer(new PlaybackEvent.PlaybackStateChanged(playbackOpen, pauseRequested));
    }

    private void notifyAudioOutputStateChanged() {
        playbackEvents.offer(new PlaybackEvent.AudioOutputStateChanged(audioOutputEnabled));
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}
