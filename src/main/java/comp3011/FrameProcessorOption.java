/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. <student name and student number insert here upon modification>
 */
package comp3011;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Maps each command-line frame-processor option to the processor it creates.
 */
public enum FrameProcessorOption {
    NUMBER_FRAMES('n', "--number-frames", FrameNumberer::new),
    SCRATCH_FRAMES('s', "--scratch-frames", FrameScratcher::new),
    FLICKER_FRAMES('f', "--flicker-frames", FrameFlickerer::new),
    BLACK_AND_WHITE('w', "--black-and-white", FrameBlackAndWhiter::new),
    YELLOW_FRAMES('y', "--yellow-frames", FrameYellower::new),
    VIGNETTE_FRAMES('v', "--vignette-frames", FrameVignetter::new),
    DUST_FRAMES('d', "--dust-frames", FrameDuster::new),
    JITTER_FRAMES('j', "--jitter-frames", FrameJitterer::new),
    MOTTLE_FRAMES('m', "--mottle-frames", FrameMottler::new),
    BLEED_FRAMES('b', "--bleed-frames", FrameBleeder::new),
    PEPPER_FRAMES('p', "--pepper-frames", FramePepperer::new);

    private final char shortName;
    private final String longName;
    private final Supplier<? extends FrameProcessor> processorFactory;

    FrameProcessorOption(
            char shortName,
            String longName,
            Supplier<? extends FrameProcessor> processorFactory) {
        this.shortName = shortName;
        this.longName = longName;
        this.processorFactory = processorFactory;
    }

    public FrameProcessor createProcessor() {
        return processorFactory.get();
    }

    public static Optional<FrameProcessorOption> fromShortName(char shortName) {
        for (FrameProcessorOption option : values()) {
            if (option.shortName == shortName) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }

    public static Optional<FrameProcessorOption> fromLongName(String longName) {
        for (FrameProcessorOption option : values()) {
            if (option.longName.equals(longName)) {
                return Optional.of(option);
            }
        }
        return Optional.empty();
    }
}
