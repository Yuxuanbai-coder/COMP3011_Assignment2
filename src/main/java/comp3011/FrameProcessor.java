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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.function.IntConsumer;
import java.util.zip.CRC32;

import org.bytedeco.javacv.Frame;

/**
 * Base class for all the video player's frame-based compositing effects.
 *
 * <p>
 * {@link VideoPlayerModel} initialises each processor once with video metadata,
 * then invokes {@link #process(Frame, InfoFrame)} for every image frame in a set order
 * before it is converted for display. Note that all FrameProcessors should be written
 * to be deterministic - that is they apply the same effect to the same frame number of
 * the same media name, but otherwise can look 'random'. This allows for the reliable
 * determination of accurately applied effects in the correct order free of any race
 * conditions, frame overwrites or frame tearing etc. Some helper methods are provided
 * for this.
 * </p>
 */
public abstract class FrameProcessor {
    private static final int ROWS_PER_PARALLEL_TASK = 16;
    private static final int MIN_PARALLEL_ROW_COUNT = ROWS_PER_PARALLEL_TASK * 3;

    protected InfoVideo videoInfo;

    public FrameProcessor() {
    }

    public void initialise(InfoVideo videoInfo) throws Exception {
        this.videoInfo = videoInfo;
    }

    // Obtain a unique seed for this media name and effect instance index. That is, for a given media name, say
    // 'movie.mp4' and a given position in the list of effects being applied, this will return a unique and different
    // seed for each effect. This is useful for creating the illusion of randomness whilst keeping everything
    // deterministic.
    protected int saltedMediaSeed(String mediaName, int instanceIndex) {
        return hashMediaName(mediaName) + instanceIndex;
    }

    private int hashMediaName(String mediaName) {
        CRC32 crc = new CRC32();
        byte[] bytes = mediaName.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    public abstract void process(Frame frame, InfoFrame info) throws Exception;

    /**
     * Applies a row-independent image operation using the shared fork-join pool.
     * Small images stay on the calling thread to avoid task overhead. Callers must
     * ensure each row writes to a disjoint part of the frame or workspace.
     */
    protected final void forEachRow(int rowCount, IntConsumer rowOperation) {
        if (rowCount < MIN_PARALLEL_ROW_COUNT || ForkJoinPool.getCommonPoolParallelism() <= 1) {
            for (int row = 0; row < rowCount; row++) {
                rowOperation.accept(row);
            }
            return;
        }

        ForkJoinPool.commonPool().invoke(new RowRangeAction(0, rowCount, rowOperation));
    }

    private static final class RowRangeAction extends RecursiveAction {
        private final int startRow;
        private final int endRow;
        private final IntConsumer rowOperation;

        private RowRangeAction(int startRow, int endRow, IntConsumer rowOperation) {
            this.startRow = startRow;
            this.endRow = endRow;
            this.rowOperation = rowOperation;
        }

        @Override
        protected void compute() {
            if (endRow - startRow <= ROWS_PER_PARALLEL_TASK) {
                for (int row = startRow; row < endRow; row++) {
                    rowOperation.accept(row);
                }
                return;
            }

            int middleRow = startRow + (endRow - startRow) / 2;
            invokeAll(
                    new RowRangeAction(startRow, middleRow, rowOperation),
                    new RowRangeAction(middleRow, endRow, rowOperation));
        }
    }
}
