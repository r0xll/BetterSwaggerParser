package io.github.r0xll.betterswagger.burp;

import burp.api.montoya.MontoyaApi;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs slow work (parsing, URL fetches, bulk sends) off the Swing Event Dispatch
 * Thread so Burp's UI stays responsive (BApp rule 3). Every submitted task is
 * wrapped so that exceptions on background threads are written to the extension
 * error stream &mdash; Burp does not report background exceptions itself.
 *
 * <p>The executor is shut down by {@link UnloadHandler} on unload (BApp rule 4).
 */
final class BackgroundWorker {

    private final MontoyaApi api;
    private final ExecutorService executor;

    BackgroundWorker(MontoyaApi api) {
        this.api = api;
        this.executor = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                new ThreadFactory() {
                    private final AtomicInteger n = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "BetterSwaggerParser-worker-" + n.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                });
    }

    /** Submit a task; any Throwable is logged to the error stream, never lost. */
    void submit(String taskName, Runnable task) {
        if (executor.isShutdown()) {
            return;
        }
        executor.submit(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                // BApp rule 3: Burp does not catch background exceptions.
                api.logging().logToError("Background task failed: " + taskName, t);
            }
        });
    }

    /** Terminate all background threads (BApp rule 4). */
    void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
