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

import java.nio.ByteBuffer;
import java.util.Random;

import org.bytedeco.javacv.Frame;

/**
 * Adds uneven brightness and colour staining to simulate aged film emulsion.
 * Having played around with the tuning of this as much as I'd like, but the
 * effect still looks okay.
 *
 * <p>
 * Initialisation builds multi-scale noise fields and colour lookup tables.
 * {@link #process(Frame, InfoFrame)} samples those fields in image blocks and
 * applies the selected channel scales through {@link #applyMottleBlock(ByteBuffer, int, int, int, int, int, int, int)}.
 * Work arrays are reused between frames, so one instance must finish a frame
 * before it begins another; its image blocks may still run concurrently.
 * </p>
 */
public class FrameMottler extends FrameProcessor {
    private static final int NOISE_GRID_WIDTH = 64;
    private static final int NOISE_GRID_HEIGHT = 36;
    private static final int FINE_SMOOTHING_PASSES = 1;
    private static final int MEDIUM_SMOOTHING_PASSES = 4;
    private static final int LARGE_SMOOTHING_PASSES = 11;
    private static final double FINE_MOTTLE_WEIGHT = 0.25;
    private static final double MEDIUM_MOTTLE_WEIGHT = 0.45;
    private static final double LARGE_MOTTLE_WEIGHT = 0.30;
    private static final int DAMAGE_MASK_BLEND_FRAMES = 72;
    private static final int DAMAGE_MASK_SMOOTHING_PASSES = 13;
    private static final double DAMAGE_MASK_THRESHOLD = 0.55;
    private static final int EFFECT_BLOCK_SIZE = 4;
    private static final int SCALE_LOOKUP_SIZE = 512;
    private static final int SCALE_INDEX_BITS = 9;
    private static final int SCALE_INDEX_MASK = SCALE_LOOKUP_SIZE - 1;
    private static final double MAX_DARKEN_PERCENTAGE = 18.0;
    private static final double MAX_LIGHTEN_PERCENTAGE = 5.0;
    private static final double STAIN_THRESHOLD = 0.35;
    private static final double RED_STAIN_BOOST_PERCENTAGE = 6.0;
    private static final double GREEN_STAIN_BOOST_PERCENTAGE = 2.0;
    private static final double BLUE_STAIN_LOSS_PERCENTAGE = 12.0;
    private static final int SEED_SALT = 0x4d4f5454;
    private static final int BRIGHTNESS_FIELD_SALT = 0x42524947;
    private static final int STAIN_FIELD_SALT = 0x53544149;
    private static final int DAMAGE_MASK_SALT = 0x44414d47;
    private static final int FINE_FIELD_SALT = 0x46494e45;
    private static final int MEDIUM_FIELD_SALT = 0x4d454449;
    private static final int LARGE_FIELD_SALT = 0x4c415247;

    private static int nextInstanceIndex;

    private final int instanceIndex;
    private int mediaSeed;
    private int cachedDamageMaskSegment = Integer.MIN_VALUE;
    private double[] damageMaskA;
    private double[] damageMaskB;
    private int[] xGridLeft;
    private double[] xGridBlend;
    private int[] yGridTop;
    private double[] yGridBlend;
    private int[] smoothingNeighbourIndices;
    // These scratch arrays are reused to avoid allocating noise grids for every frame.
    private double[] noiseWorkspaceA;
    private double[] noiseWorkspaceB;
    private double[] fineNoiseField;
    private double[] mediumNoiseField;
    private double[] largeNoiseField;
    private double[] brightnessField;
    private double[] stainField;
    private byte[][] scaleLookups;
    private double minimumScale;
    private double maximumScale;
    // Noise generation is serial per instance; row-parallel image work starts after the fields are built.
    private final Random noiseRandom = new Random();

    public FrameMottler() {
        super();
        instanceIndex = nextInstanceIndex++;
    }

    @Override
    public void initialise(InfoVideo videoInfo) throws Exception {
        super.initialise(videoInfo);
        mediaSeed = saltedMediaSeed(videoInfo.mediaName(), instanceIndex + SEED_SALT);
        cachedDamageMaskSegment = Integer.MIN_VALUE;
        buildSamplingLookups();
        // Allocate reusable noise and damage-mask buffers once for this video's fixed grid dimensions.
        int noiseFieldSize = NOISE_GRID_WIDTH * NOISE_GRID_HEIGHT;
        // Smoothing neighborhoods depend only on grid size, so cache their clamped indices during setup.
        smoothingNeighbourIndices = buildSmoothingNeighbourIndices();
        noiseWorkspaceA = new double[noiseFieldSize];
        noiseWorkspaceB = new double[noiseFieldSize];
        fineNoiseField = new double[noiseFieldSize];
        mediumNoiseField = new double[noiseFieldSize];
        largeNoiseField = new double[noiseFieldSize];
        brightnessField = new double[noiseFieldSize];
        stainField = new double[noiseFieldSize];
        damageMaskA = new double[noiseFieldSize];
        damageMaskB = new double[noiseFieldSize];
        buildScaleLookups();
    }

    @Override
    public void process(Frame frame, InfoFrame info) throws Exception {
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }

        if (!(frame.image[0] instanceof ByteBuffer pixels)) {
            throw new Exception("FrameMottler requires byte-backed image frames");
        }

        if (videoInfo.imageDepth() != Frame.DEPTH_UBYTE && videoInfo.imageDepth() != Frame.DEPTH_BYTE) {
            throw new Exception("FrameMottler requires 8-bit image frames");
        }

        if (videoInfo.imageChannels() < 3) {
            throw new Exception("FrameMottler requires at least three image channels");
        }

        buildMultiScaleNoiseField(info.frameNumber(), BRIGHTNESS_FIELD_SALT, brightnessField);
        buildMultiScaleNoiseField(info.frameNumber(), STAIN_FIELD_SALT, stainField);
        int damageMaskSegment = info.frameNumber() / DAMAGE_MASK_BLEND_FRAMES;
        double damageMaskBlend = (info.frameNumber() % DAMAGE_MASK_BLEND_FRAMES) / (double) DAMAGE_MASK_BLEND_FRAMES;
        ensureDamageMaskFields(damageMaskSegment);

        mottleFrame(pixels, brightnessField, stainField, damageMaskBlend);
    }

    private void buildSamplingLookups() {
        xGridLeft = new int[videoInfo.imageWidth()];
        xGridBlend = new double[videoInfo.imageWidth()];
        yGridTop = new int[videoInfo.imageHeight()];
        yGridBlend = new double[videoInfo.imageHeight()];

        buildAxisSamplingLookups(videoInfo.imageWidth(), NOISE_GRID_WIDTH, xGridLeft, xGridBlend);
        buildAxisSamplingLookups(videoInfo.imageHeight(), NOISE_GRID_HEIGHT, yGridTop, yGridBlend);
    }

    private void buildAxisSamplingLookups(int imageSize, int gridSize, int[] gridStarts, double[] gridBlends) {
        if (imageSize <= 1 || gridSize <= 1) {
            for (int position = 0; position < imageSize; position++) {
                gridStarts[position] = 0;
                gridBlends[position] = 0.0;
            }
            return;
        }

        double scale = (gridSize - 1) / (double) (imageSize - 1);
        for (int position = 0; position < imageSize; position++) {
            double gridPosition = position * scale;
            int gridStart = (int) Math.floor(gridPosition);
            if (gridStart >= gridSize - 1) {
                gridStart = gridSize - 2;
                gridPosition = gridSize - 1;
            }

            gridStarts[position] = gridStart;
            gridBlends[position] = gridPosition - gridStart;
        }
    }

    private void buildScaleLookups() {
        double darkestBrightnessScale = 1.0 - MAX_DARKEN_PERCENTAGE / 100.0;
        double brightestScale = 1.0 + MAX_LIGHTEN_PERCENTAGE / 100.0;
        double bluestScale = 1.0 - BLUE_STAIN_LOSS_PERCENTAGE / 100.0;
        double reddestScale = 1.0 + RED_STAIN_BOOST_PERCENTAGE / 100.0;
        double greenestScale = 1.0 + GREEN_STAIN_BOOST_PERCENTAGE / 100.0;

        minimumScale = Math.min(darkestBrightnessScale, darkestBrightnessScale * bluestScale);
        maximumScale = Math.max(brightestScale, brightestScale * Math.max(reddestScale, greenestScale));

        double padding = (maximumScale - minimumScale) * 0.05;
        minimumScale -= padding;
        maximumScale += padding;

        scaleLookups = new byte[SCALE_LOOKUP_SIZE][256];
        for (int scaleIndex = 0; scaleIndex < SCALE_LOOKUP_SIZE; scaleIndex++) {
            double blend = scaleIndex / (double) (SCALE_LOOKUP_SIZE - 1);
            double scale = lerp(minimumScale, maximumScale, blend);
            byte[] lookup = scaleLookups[scaleIndex];

            for (int value = 0; value < lookup.length; value++) {
                lookup[value] = (byte) clamp((int) Math.round(value * scale), 0, 255);
            }
        }
    }

    private void ensureDamageMaskFields(int segment) {
        if (segment == cachedDamageMaskSegment) {
            return;
        }

        cachedDamageMaskSegment = segment;
        buildNoiseField(segment, DAMAGE_MASK_SALT, DAMAGE_MASK_SMOOTHING_PASSES, damageMaskA);
        buildNoiseField(segment + 1, DAMAGE_MASK_SALT, DAMAGE_MASK_SMOOTHING_PASSES, damageMaskB);
    }

    private void buildMultiScaleNoiseField(int frameNumber, int fieldSalt, double[] field) {
        buildNoiseField(frameNumber, fieldSalt ^ FINE_FIELD_SALT, FINE_SMOOTHING_PASSES, fineNoiseField);
        buildNoiseField(frameNumber, fieldSalt ^ MEDIUM_FIELD_SALT, MEDIUM_SMOOTHING_PASSES, mediumNoiseField);
        buildNoiseField(frameNumber, fieldSalt ^ LARGE_FIELD_SALT, LARGE_SMOOTHING_PASSES, largeNoiseField);

        for (int i = 0; i < field.length; i++) {
            field[i] = fineNoiseField[i] * FINE_MOTTLE_WEIGHT
                    + mediumNoiseField[i] * MEDIUM_MOTTLE_WEIGHT
                    + largeNoiseField[i] * LARGE_MOTTLE_WEIGHT;
        }

        normaliseField(field);
    }

    private void buildNoiseField(int frameNumber, int fieldSalt, int smoothingPasses, double[] destination) {
        // Reuse two workspaces for smoothing, then copy only the final field into its retained destination.
        double[] field = noiseWorkspaceA;
        double[] scratch = noiseWorkspaceB;
        noiseRandom.setSeed(seedForField(frameNumber, fieldSalt));

        for (int i = 0; i < field.length; i++) {
            field[i] = noiseRandom.nextDouble();
        }

        for (int pass = 0; pass < smoothingPasses; pass++) {
            smoothField(field, scratch);
            double[] swap = field;
            field = scratch;
            scratch = swap;
        }

        normaliseField(field);
        System.arraycopy(field, 0, destination, 0, field.length);
    }

    private long seedForField(int frameNumber, int fieldSalt) {
        long seed = Integer.toUnsignedLong(mediaSeed);
        seed ^= (long) frameNumber * 0x9e3779b97f4a7c15L;
        seed ^= Integer.toUnsignedLong(fieldSalt);

        seed ^= seed >>> 30;
        seed *= 0xbf58476d1ce4e5b9L;
        seed ^= seed >>> 27;
        seed *= 0x94d049bb133111ebL;
        seed ^= seed >>> 31;
        return seed;
    }

    private void smoothField(double[] source, double[] destination) {
        // Each cell has nine clamp-to-edge samples; their positions were precomputed during initialise().
        int samplesPerCell = 9;
        for (int cell = 0; cell < source.length; cell++) {
            int neighbourStart = cell * samplesPerCell;
            double total = 0.0;
            for (int neighbour = 0; neighbour < samplesPerCell; neighbour++) {
                total += source[smoothingNeighbourIndices[neighbourStart + neighbour]];
            }
            destination[cell] = total / samplesPerCell;
        }
    }

    private int[] buildSmoothingNeighbourIndices() {
        int[] indices = new int[NOISE_GRID_WIDTH * NOISE_GRID_HEIGHT * 9];
        for (int y = 0; y < NOISE_GRID_HEIGHT; y++) {
            for (int x = 0; x < NOISE_GRID_WIDTH; x++) {
                int index = (y * NOISE_GRID_WIDTH + x) * 9;
                for (int dy = -1; dy <= 1; dy++) {
                    int sampleY = clamp(y + dy, 0, NOISE_GRID_HEIGHT - 1);
                    for (int dx = -1; dx <= 1; dx++) {
                        int sampleX = clamp(x + dx, 0, NOISE_GRID_WIDTH - 1);
                        indices[index++] = sampleY * NOISE_GRID_WIDTH + sampleX;
                    }
                }
            }
        }
        return indices;
    }

    private void normaliseField(double[] field) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        for (double value : field) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        double range = max - min;
        if (range <= 0.0) {
            return;
        }

        for (int i = 0; i < field.length; i++) {
            field[i] = (field[i] - min) / range;
        }
    }

    private void mottleFrame(
            ByteBuffer pixels,
            double[] brightnessField,
            double[] stainField,
            double damageMaskBlend) {
        int height = videoInfo.imageHeight();
        int width = videoInfo.imageWidth();
        int channels = videoInfo.imageChannels();
        int stride = videoInfo.imageStride();

        // A task owns a band of four image rows, so neighboring tasks cannot write the same pixels.
        int blockRowCount = (height + EFFECT_BLOCK_SIZE - 1) / EFFECT_BLOCK_SIZE;
        forEachRow(blockRowCount, blockRow -> {
            int blockY = blockRow * EFFECT_BLOCK_SIZE;
            int blockYEnd = Math.min(blockY + EFFECT_BLOCK_SIZE, height);
            int sampleY = (blockY + blockYEnd - 1) / 2;

            for (int blockX = 0; blockX < width; blockX += EFFECT_BLOCK_SIZE) {
                int blockXEnd = Math.min(blockX + EFFECT_BLOCK_SIZE, width);
                int sampleX = (blockX + blockXEnd - 1) / 2;

                int scaleIndices = prepareScaleIndicesForBlock(
                        sampleX,
                        sampleY,
                        brightnessField,
                        stainField,
                        damageMaskBlend);
                if (scaleIndices < 0) {
                    continue;
                }

                applyMottleBlock(
                        pixels,
                        blockX,
                        blockXEnd,
                        blockY,
                        blockYEnd,
                        channels,
                        stride,
                        scaleIndices);
            }
        });
    }

    private int prepareScaleIndicesForBlock(
            int x,
            int y,
            double[] brightnessField,
            double[] stainField,
            double damageMaskBlend) {
        double damageStrength = sampleDamageMask(x, y, damageMaskBlend);
        if (damageStrength <= 0.0) {
            return -1;
        }

        double brightness = smoothstep(sampleField(brightnessField, x, y));
        double stain = smoothstep(sampleField(stainField, x, y));

        return setBlockScaleIndices(brightness, stain, damageStrength);
    }

    private double sampleDamageMask(int x, int y, double damageMaskBlend) {
        double maskA = sampleField(damageMaskA, x, y);
        double maskB = sampleField(damageMaskB, x, y);
        double mask = smoothstep(lerp(maskA, maskB, damageMaskBlend));
        return smoothstep((mask - DAMAGE_MASK_THRESHOLD) / (1.0 - DAMAGE_MASK_THRESHOLD));
    }

    private double sampleField(double[] field, int x, int y) {
        int x0 = xGridLeft[x];
        int x1 = Math.min(x0 + 1, NOISE_GRID_WIDTH - 1);
        double xBlend = xGridBlend[x];

        int y0 = yGridTop[y];
        int y1 = Math.min(y0 + 1, NOISE_GRID_HEIGHT - 1);
        double yBlend = yGridBlend[y];

        double top = lerp(field[y0 * NOISE_GRID_WIDTH + x0], field[y0 * NOISE_GRID_WIDTH + x1], xBlend);
        double bottom = lerp(field[y1 * NOISE_GRID_WIDTH + x0], field[y1 * NOISE_GRID_WIDTH + x1], xBlend);
        return lerp(top, bottom, yBlend);
    }

    private int setBlockScaleIndices(double brightness, double stain, double damageStrength) {
        double brightnessScale;
        if (brightness < 0.5) {
            brightnessScale = 1.0 - (0.5 - brightness) * 2.0 * MAX_DARKEN_PERCENTAGE / 100.0;
        } else {
            brightnessScale = 1.0 + (brightness - 0.5) * 2.0 * MAX_LIGHTEN_PERCENTAGE / 100.0;
        }
        brightnessScale = 1.0 + (brightnessScale - 1.0) * damageStrength;

        double stainStrength = Math.max(0.0, (stain - STAIN_THRESHOLD) / (1.0 - STAIN_THRESHOLD));
        stainStrength = smoothstep(stainStrength) * damageStrength;

        double blueScale = brightnessScale * (1.0 - stainStrength * BLUE_STAIN_LOSS_PERCENTAGE / 100.0);
        double greenScale = brightnessScale * (1.0 + stainStrength * GREEN_STAIN_BOOST_PERCENTAGE / 100.0);
        double redScale = brightnessScale * (1.0 + stainStrength * RED_STAIN_BOOST_PERCENTAGE / 100.0);

        // Pack block-local indices instead of shared fields, preventing parallel block tasks from overwriting them.
        return (scaleIndex(blueScale) << (SCALE_INDEX_BITS * 2))
                | (scaleIndex(greenScale) << SCALE_INDEX_BITS)
                | scaleIndex(redScale);
    }

    private void applyMottleBlock(
            ByteBuffer pixels,
            int blockX,
            int blockXEnd,
            int blockY,
            int blockYEnd,
            int channels,
            int stride,
            int scaleIndices) {
        byte[] blueLookup = scaleLookups[(scaleIndices >>> (SCALE_INDEX_BITS * 2)) & SCALE_INDEX_MASK];
        byte[] greenLookup = scaleLookups[(scaleIndices >>> SCALE_INDEX_BITS) & SCALE_INDEX_MASK];
        byte[] redLookup = scaleLookups[scaleIndices & SCALE_INDEX_MASK];

        for (int y = blockY; y < blockYEnd; y++) {
            int rowStart = y * stride;
            for (int x = blockX; x < blockXEnd; x++) {
                int base = rowStart + x * channels;

                // JavaCV supplies these decoded frames in BGR byte order.
                pixels.put(base, blueLookup[Byte.toUnsignedInt(pixels.get(base))]);
                pixels.put(base + 1, greenLookup[Byte.toUnsignedInt(pixels.get(base + 1))]);
                pixels.put(base + 2, redLookup[Byte.toUnsignedInt(pixels.get(base + 2))]);
            }
        }
    }

    private int scaleIndex(double scale) {
        double blend = (scale - minimumScale) / (maximumScale - minimumScale);
        return clamp((int) Math.round(blend * (SCALE_LOOKUP_SIZE - 1)), 0, SCALE_LOOKUP_SIZE - 1);
    }

    private double lerp(double a, double b, double blend) {
        return a + (b - a) * blend;
    }

    private double smoothstep(double value) {
        value = clamp(value, 0.0, 1.0);
        return value * value * (3.0 - 2.0 * value);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }
}
