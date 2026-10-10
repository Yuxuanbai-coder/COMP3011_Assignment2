/*
 * COMP3011 Assignment 2 regression tests.
 *
 * Authors:
 *   1. Yuxuan Bai (a2993969), with OpenAI Codex AI assistance.
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandLineControllerTest {
    @TempDir
    Path temporaryDirectory;

    private File videoFile;

    @BeforeEach
    void createPlaceholderVideoFile() throws IOException {
        videoFile = Files.createFile(temporaryDirectory.resolve("sample.mp4")).toFile();
    }

    @Test
    void printsTheSpecifiedHelpText() {
        String output = captureStandardOutput(() -> {
            CommandLineController controller = new CommandLineController(new String[] { "--help" });
            assertFalse(controller.shouldLaunchApplication());
            assertNull(controller.getErrorMessage());
        });

        assertEquals(expectedHelpText(), output);
    }

    @Test
    void parsesEveryShortAndLongEffectOptionAndCreatesItsProcessor() {
        List<EffectOptionCase> cases = List.of(
                new EffectOptionCase("-n", "--number-frames", FrameProcessorOption.NUMBER_FRAMES, FrameNumberer.class),
                new EffectOptionCase("-s", "--scratch-frames", FrameProcessorOption.SCRATCH_FRAMES, FrameScratcher.class),
                new EffectOptionCase("-f", "--flicker-frames", FrameProcessorOption.FLICKER_FRAMES, FrameFlickerer.class),
                new EffectOptionCase("-w", "--black-and-white", FrameProcessorOption.BLACK_AND_WHITE, FrameBlackAndWhiter.class),
                new EffectOptionCase("-y", "--yellow-frames", FrameProcessorOption.YELLOW_FRAMES, FrameYellower.class),
                new EffectOptionCase("-v", "--vignette-frames", FrameProcessorOption.VIGNETTE_FRAMES, FrameVignetter.class),
                new EffectOptionCase("-d", "--dust-frames", FrameProcessorOption.DUST_FRAMES, FrameDuster.class),
                new EffectOptionCase("-j", "--jitter-frames", FrameProcessorOption.JITTER_FRAMES, FrameJitterer.class),
                new EffectOptionCase("-m", "--mottle-frames", FrameProcessorOption.MOTTLE_FRAMES, FrameMottler.class),
                new EffectOptionCase("-b", "--bleed-frames", FrameProcessorOption.BLEED_FRAMES, FrameBleeder.class),
                new EffectOptionCase("-p", "--pepper-frames", FrameProcessorOption.PEPPER_FRAMES, FramePepperer.class));

        for (EffectOptionCase effect : cases) {
            assertParsedAs(effect.shortOption(), effect.option());
            assertParsedAs(effect.longOption(), effect.option());

            FrameProcessor first = effect.option().createProcessor();
            FrameProcessor second = effect.option().createProcessor();
            assertEquals(effect.processorClass(), first.getClass(), effect.shortOption());
            assertNotSame(first, second, effect.shortOption() + " should create a new instance each time");
        }
    }

    @Test
    void preservesOrderAndRepeatedEffectsInStackedShortOptions() {
        CommandLineController controller = parse("-jfddn");

        assertNull(controller.getErrorMessage());
        assertEquals(List.of(
                FrameProcessorOption.JITTER_FRAMES,
                FrameProcessorOption.FLICKER_FRAMES,
                FrameProcessorOption.DUST_FRAMES,
                FrameProcessorOption.DUST_FRAMES,
                FrameProcessorOption.NUMBER_FRAMES), controller.getFrameProcessorOptions());
    }

    @Test
    void preservesOrderWhenLongAndShortOptionsAreMixed() {
        CommandLineController controller = parse(
                "-jf",
                "--dust-frames",
                "-d",
                "--number-frames");

        assertNull(controller.getErrorMessage());
        assertEquals(List.of(
                FrameProcessorOption.JITTER_FRAMES,
                FrameProcessorOption.FLICKER_FRAMES,
                FrameProcessorOption.DUST_FRAMES,
                FrameProcessorOption.DUST_FRAMES,
                FrameProcessorOption.NUMBER_FRAMES), controller.getFrameProcessorOptions());
    }

    @Test
    void keepsExistingShortLaunchOptionsWorking() {
        CommandLineController controller = parse("-ax1");

        assertNull(controller.getErrorMessage());
        assertTrue(controller.isAudioRequested());
        assertTrue(controller.isMaximiseRequested());
        assertEquals(1, controller.getDisplayId());
    }

    @Test
    void rejectsUnknownEffectOptions() {
        String output = captureStandardOutput(() -> {
            CommandLineController controller = new CommandLineController(
                    new String[] { "-q", videoFile.getAbsolutePath() });

            assertEquals("Unknown option: -q", controller.getErrorMessage());
            assertEquals(1, controller.getExitCode());
            assertFalse(controller.shouldLaunchApplication());
        });

        assertEquals("Unknown option: -q" + System.lineSeparator(), output);
    }

    private void assertParsedAs(String option, FrameProcessorOption expected) {
        CommandLineController controller = parse(option);

        assertNull(controller.getErrorMessage(), option);
        assertEquals(List.of(expected), controller.getFrameProcessorOptions(), option);
    }

    private CommandLineController parse(String... options) {
        String[] arguments = Arrays.copyOf(options, options.length + 1);
        arguments[arguments.length - 1] = videoFile.getAbsolutePath();
        return new CommandLineController(arguments);
    }

    private String captureStandardOutput(Runnable action) {
        PrintStream originalOutput = System.out;
        ByteArrayOutputStream capturedBytes = new ByteArrayOutputStream();

        try (PrintStream capturedOutput = new PrintStream(capturedBytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOutput);
            action.run();
        } finally {
            System.setOut(originalOutput);
        }

        return capturedBytes.toString(StandardCharsets.UTF_8);
    }

    private String expectedHelpText() {
        return String.join(System.lineSeparator(),
                "Usage: VideoPlayer [options] [video-file]",
                "",
                "Options:",
                "  -h, --help         Show this help message",
                "  -a, --audio        Play audio",
                "  -x, --maximise     Open the player maximised",
                "  -1, --monitor-1    Open the player on display 1",
                "  -2, --monitor-2    Open the player on display 2",
                "",
                "Frame processors:",
                "  -n, --number-frames   Render the frame number onto each frame",
                "  -s, --scratch-frames  Render vertical film scratches",
                "  -f, --flicker-frames  Randomly dim frames",
                "  -w, --black-and-white Convert frames to black and white",
                "  -y, --yellow-frames   Apply a warmer colour temperature",
                "  -v, --vignette-frames Darken the frame edges",
                "  -d, --dust-frames     Render dust and hair marks",
                "  -j, --jitter-frames   Randomly displace frames by a few pixels",
                "  -m, --mottle-frames   Add cloudy emulsion mottling",
                "  -b, --bleed-frames    Bleed light into frames",
                "  -p, --pepper-frames   Pepper frames with dark spots/blotches",
                "Frame processors are applied in command-line order and may be repeated.",
                "",
                "Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,",
                "converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.")
                + System.lineSeparator();
    }

    private record EffectOptionCase(
            String shortOption,
            String longOption,
            FrameProcessorOption option,
            Class<? extends FrameProcessor> processorClass) {
    }
}
