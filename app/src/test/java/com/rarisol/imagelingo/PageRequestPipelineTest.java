package com.rarisol.imagelingo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class PageRequestPipelineTest {
    @Test public void runsThreeRequestsAtOnceAndReturnsResultsInPageOrder() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch threeStarted = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        PageRequestPipeline<String> pipeline = new PageRequestPipeline<String>(3, 6,
                index -> true,
                index -> () -> {
                    peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                    threeStarted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    running.decrementAndGet();
                    return "page" + index;
                },
                () -> false);
        try {
            pipeline.topUp(0);
            assertTrue("three requests should start together", threeStarted.await(5, TimeUnit.SECONDS));
            assertEquals(3, pipeline.inFlightCount());
            release.countDown();
            List<String> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                pipeline.topUp(i);
                results.add(pipeline.take(i));
            }
            assertEquals(List.of("page0", "page1", "page2", "page3", "page4", "page5"), results);
            assertEquals(3, peak.get());
        } finally {
            pipeline.shutdown();
        }
    }

    @Test public void skippedPagesAreNeverRequested() throws Exception {
        List<Integer> requested = Collections.synchronizedList(new ArrayList<>());
        PageRequestPipeline<Integer> pipeline = new PageRequestPipeline<Integer>(3, 5,
                index -> index % 2 == 0,
                index -> {
                    requested.add(index);
                    return () -> index;
                },
                () -> false);
        try {
            for (int i = 0; i < 5; i++) {
                pipeline.topUp(i);
                if (i % 2 == 0) assertEquals(Integer.valueOf(i), pipeline.take(i));
                else pipeline.discard(i);
            }
            assertEquals(List.of(0, 2, 4), requested);
        } finally {
            pipeline.shutdown();
        }
    }

    @Test public void oneAtATimeRunsOnCallerThreadWithFreshContext() throws Exception {
        Thread caller = Thread.currentThread();
        List<Thread> ranOn = new ArrayList<>();
        AtomicInteger memoryVersion = new AtomicInteger();
        PageRequestPipeline<Integer> pipeline = new PageRequestPipeline<Integer>(1, 3,
                index -> true,
                index -> {
                    int context = memoryVersion.get();
                    return () -> {
                        ranOn.add(Thread.currentThread());
                        return context;
                    };
                },
                () -> false);
        for (int i = 0; i < 3; i++) {
            pipeline.topUp(i);
            assertEquals(0, pipeline.inFlightCount());
            assertEquals(Integer.valueOf(i), pipeline.take(i));
            memoryVersion.incrementAndGet();
        }
        for (Thread thread : ranOn) assertSame(caller, thread);
        pipeline.shutdown();
    }

    @Test public void requestErrorsReachTheCallerUnwrapped() {
        PageRequestPipeline<String> pipeline = new PageRequestPipeline<String>(3, 2,
                index -> true,
                index -> () -> {
                    throw new TranslationClient.HttpStatusException(403, "denied");
                },
                () -> false);
        try {
            pipeline.topUp(0);
            TranslationClient.HttpStatusException error = assertThrows(
                    TranslationClient.HttpStatusException.class, () -> pipeline.take(0));
            assertEquals(403, error.statusCode);
        } finally {
            pipeline.shutdown();
        }
    }

    @Test public void stopRequestPreventsNewRequests() throws Exception {
        AtomicInteger started = new AtomicInteger();
        PageRequestPipeline<Integer> pipeline = new PageRequestPipeline<Integer>(3, 5,
                index -> true,
                index -> {
                    started.incrementAndGet();
                    return () -> index;
                },
                () -> true);
        pipeline.topUp(0);
        assertEquals(0, started.get());
        pipeline.shutdown();
    }

    /** Mirrors how MainActivity builds the pipeline, so the same lambda shape must compile. */
    @Test public void mainActivityStyleConstructionCompiles() throws Exception {
        PageRequestPipeline<TranslationClient.TranslationResult> prefetcher = null;
        try {
            final int totalPages = 2;
            final String memory = "ctx";
            prefetcher = new PageRequestPipeline<TranslationClient.TranslationResult>(
                    2, totalPages,
                    index -> index >= 0,
                    index -> {
                        String page = "p" + index;
                        String context = memory + index;
                        return () -> fakeTranslate(page, context, index + 1, totalPages);
                    },
                    () -> false);
            prefetcher.topUp(0);
            assertEquals("p1|ctx1|2/2", prefetcher.take(1).text);
        } finally {
            if (prefetcher != null) prefetcher.shutdown();
        }
    }

    private static TranslationClient.TranslationResult fakeTranslate(
            String page, String context, int pageNumber, int total) throws Exception {
        return new TranslationClient.TranslationResult(
                page + "|" + context + "|" + pageNumber + "/" + total, null, null, null);
    }
}
