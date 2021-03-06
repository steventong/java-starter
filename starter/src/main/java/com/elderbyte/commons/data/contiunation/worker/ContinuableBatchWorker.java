package com.elderbyte.commons.data.contiunation.worker;

import com.elderbyte.commons.cancelation.CancellationToken;
import com.elderbyte.commons.data.contiunation.ContinuableListing;
import com.elderbyte.commons.exceptions.ArgumentNullException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Provides a simple way to work over a continuable stream of batches.
 *
 * 1. Loads the next batch
 * 2. Processes the batch
 * 3. Go to 1, until no more batches
 *
 * This worker will collect some basic metrics and report them back.
 *
 * @param <T>
 */
public class ContinuableBatchWorker<T> {

    /***************************************************************************
     *                                                                         *
     * Static builders                                                         *
     *                                                                         *
     **************************************************************************/

    public static <G> ContinuableBatchWorker<G> worker(
            Function<String, ContinuableListing<G>> batchLoader,
            Consumer<List<G>> batchProcessor){
        return new ContinuableBatchWorker<>(batchLoader, batchProcessor);
    }

    /***************************************************************************
     *                                                                         *
     * Fields                                                                  *
     *                                                                         *
     **************************************************************************/

    private final Function<String, ContinuableListing<T>> batchLoader;
    private final Consumer<List<T>> batchProcessor;

    /***************************************************************************
     *                                                                         *
     * Public API                                                              *
     *                                                                         *
     **************************************************************************/

    public ContinuableBatchWorker(
            Function<String, ContinuableListing<T>> batchLoader,
            Consumer<List<T>> batchProcessor
    ){
        if(batchLoader == null) throw new ArgumentNullException("batchLoader");
        if(batchProcessor == null) throw new ArgumentNullException("batchProcessor");

        this.batchLoader = batchLoader;
        this.batchProcessor = batchProcessor;
    }

    /***************************************************************************
     *                                                                         *
     * Public API                                                              *
     *                                                                         *
     **************************************************************************/

    /**
     * Process all items from the continuable source.
     * @throws BatchWorkerException Thrown when there was an issue processing a batch.
     */
    public Metrics processAll() throws BatchWorkerException {
        return processAll(metrics -> {});
    }

    /**
     * Process all items from the continuable source.
     * @param progressCallback Callback for metrics report while the worker is processing
     * @throws BatchWorkerException Thrown when there was an issue processing a batch.
     */
    public Metrics processAll(Consumer<Metrics> progressCallback) throws BatchWorkerException {
        return processAll(progressCallback, CancellationToken.Never);
    }

    /**
     * Process all items from the continuable source.
     * @param progressCallback Callback for metrics report while the worker is processing
     * @throws BatchWorkerException Thrown when there was an issue processing a batch.
     */
    public Metrics processAll(Consumer<Metrics> progressCallback, CancellationToken cancellationToken) throws BatchWorkerException {
        return processAllFrom(null, progressCallback, cancellationToken);
    }

    /**
     * Process all items from the continuable source.
     * @param startToken The initial token to use - useful to manually resume work.
     * @throws BatchWorkerException
     */
    public Metrics processAllFrom(String startToken) throws BatchWorkerException {
        return processAllFrom(startToken, metrics -> {}, CancellationToken.Never);
    }

    /**
     * Process all items from the continuable source starting at the given token.
     * @param startToken The initial token to use - useful to manually resume work. If null starts from the beginning.
     * @param progressCallback Callback for metrics report while the worker is processing
     * @param cancellationToken Token to cancel processing - has to be provided.
     * @throws BatchWorkerException Thrown when there was an issue processing a batch.
     */
    public Metrics processAllFrom(String startToken, Consumer<Metrics> progressCallback, CancellationToken cancellationToken) throws BatchWorkerException, CancellationException {

        if(progressCallback == null) throw new ArgumentNullException("progressCallback");
        if(cancellationToken == null) throw new ArgumentNullException("cancellationToken");


        String nextToken = startToken;
        var reporter = new MetricsReporter();

        do {

            // Check if processing should be aborted
            cancellationToken.throwIfCancellationRequested();

            long start = System.nanoTime();

            var chunk = loadNext(nextToken);

            var items = chunk.getContent();

            processAll(items);

            var currentToken = nextToken;
            nextToken = chunk.getNextContinuationToken();

            reporter.reportProcessedBatch(items.size(), System.nanoTime()-start, chunk.getTotal(), currentToken, nextToken);

            progressCallback.accept(reporter.getSnapshot());

        } while (nextToken != null);

        return reporter.getSnapshot();
    }

    /***************************************************************************
     *                                                                         *
     * Private API                                                             *
     *                                                                         *
     **************************************************************************/

    private ContinuableListing<T> loadNext(String nextToken){
        try {
            return batchLoader.apply(nextToken);
        }catch (Exception e){
            throw new BatchWorkerException("Failed to load next batch with nextToken: " + nextToken, e);
        }
    }

    private void processAll(List<T> items){
        try {
            batchProcessor.accept(items);
        }catch (Exception e){
            throw new BatchWorkerException("Failed to process item batch!", e);
        }
    }

    /***************************************************************************
     *                                                                         *
     * Inner class                                                             *
     *                                                                         *
     **************************************************************************/

    private static class MetricsReporter {

        private Long totalItems = null;
        private String completedToken = null;
        private String nextToken = null;

        private int processedItems;
        private int processedBatches;
        private long batchMaxTimeMs = 0;
        private long batchMinTimeMs = Long.MAX_VALUE;
        private long totalTimeNano = 0;

        public void reportProcessedBatch(int items, long nanoTime, Long total, String completedToken, String nextToken){

            this.totalItems = total;
            this.completedToken = completedToken;
            this.nextToken = nextToken;

            this.totalTimeNano += nanoTime;
            this.processedItems += items;
            this.processedBatches++;

            var msTime = nanoToMillis(nanoTime);
            this.batchMaxTimeMs = Math.max(batchMaxTimeMs, msTime);
            this.batchMinTimeMs = Math.min(batchMinTimeMs, msTime);
        }

        public Metrics getSnapshot(){
            return new Metrics(
                    totalItems,
                    completedToken,
                    nextToken,
                    processedItems,
                    processedBatches,
                    batchMaxTimeMs,
                    batchMinTimeMs,
                    nanoToMillis(totalTimeNano)
            );
        }

        private long nanoToMillis(long nano){
            return nano / (1000*1000);
        }
    }

    public static class Metrics {

        private final Long totalItems;
        private final String completedToken;
        private final String nextToken;
        private final int processedItems;
        private final int processedBatches;
        private final long batchMaxTimeMs;
        private final long batchMinTimeMs;
        private final long totalTimeMs;

        private Metrics(
                Long totalItems,
                String completedToken,
                String nextToken,
                int processedItems,
                int processedBatches,
                long batchMaxTimeMs,
                long batchMinTimeMs,
                long totalTimeMs) {

            this.completedToken = completedToken;
            this.nextToken = nextToken;
            this.totalItems = totalItems;
            this.processedItems = processedItems;
            this.processedBatches = processedBatches;
            this.batchMaxTimeMs = batchMaxTimeMs;
            this.batchMinTimeMs = batchMinTimeMs;
            this.totalTimeMs = totalTimeMs;
        }

        public int getProcessedItems() {
            return processedItems;
        }

        public int getProcessedBatches() {
            return processedBatches;
        }

        public long getBatchMaxTimeMs() {
            return batchMaxTimeMs;
        }

        public long getBatchMinTimeMs() {
            return batchMinTimeMs;
        }

        public long getTotalTimeMs() {
            return totalTimeMs;
        }

        public Optional<Long> getTotalItems() {
            return Optional.ofNullable(totalItems);
        }

        /**
         * The continuation token of the last completed batch.
         * Might be null for the first batch.
         */
        public String getCompletedToken() {
            return completedToken;
        }

        /**
         * The next continuation token of the last completed batch.
         * Might be null for the last batch.
         */
        public String getNextToken() {
            return nextToken;
        }
    }
}
