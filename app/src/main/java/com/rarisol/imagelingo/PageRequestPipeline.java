package com.rarisol.imagelingo;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * Keeps up to {@code limit} page requests in flight ahead of the page the batch is working on.
 * Only the requests run in parallel; the caller still consumes results one page at a time, in
 * order, so saving and context updates stay sequential. With a limit of 1 no threads are created
 * and every request runs directly on the caller's thread, exactly like a plain loop.
 */
final class PageRequestPipeline<T> {
    private final int limit;
    private final int size;
    private final IntPredicate needsRequest;
    private final IntFunction<Callable<T>> requestFactory;
    private final BooleanSupplier stopRequested;
    private final ExecutorService pool;
    private final Map<Integer, Future<T>> inFlight = new HashMap<>();
    private int scanCursor = 0;

    PageRequestPipeline(int limit, int size, IntPredicate needsRequest,
                        IntFunction<Callable<T>> requestFactory, BooleanSupplier stopRequested) {
        this.limit = Math.max(1, limit);
        this.size = size;
        this.needsRequest = needsRequest;
        this.requestFactory = requestFactory;
        this.stopRequested = stopRequested;
        this.pool = this.limit > 1 ? Executors.newFixedThreadPool(this.limit) : null;
    }

    /** Starts requests for the next pages (from {@code fromIndex}) that need one, up to the limit. */
    void topUp(int fromIndex) {
        if (pool == null) return;
        int index = Math.max(fromIndex, scanCursor);
        for (; index < size && inFlight.size() < limit && !stopRequested.getAsBoolean(); index++) {
            if (inFlight.containsKey(index) || !needsRequest.test(index)) continue;
            // The factory runs here, on the caller's thread, so any context it captures is a
            // consistent snapshot taken when the request starts.
            inFlight.put(index, pool.submit(requestFactory.apply(index)));
        }
        scanCursor = index;
    }

    /** The page's result: waits for its prefetched request, or runs the request directly. */
    T take(int index) throws Exception {
        Future<T> future = inFlight.remove(index);
        if (future == null) return requestFactory.apply(index).call();
        try {
            return future.get();
        } catch (ExecutionException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } catch (CancellationException cancelled) {
            throw new TranslationClient.TranslationCancelledException();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TranslationClient.TranslationCancelledException();
        }
    }

    /** Drops a request the batch no longer needs (the page was skipped or served from cache). */
    void discard(int index) {
        Future<T> future = inFlight.remove(index);
        if (future != null) future.cancel(true);
    }

    int inFlightCount() {
        return inFlight.size();
    }

    /** Cancels requests for pages the batch will not reach and releases the threads. */
    void shutdown() {
        for (Future<T> future : inFlight.values()) future.cancel(true);
        inFlight.clear();
        if (pool != null) pool.shutdownNow();
    }
}
