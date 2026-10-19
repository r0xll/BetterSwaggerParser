package io.github.r0xll.betterswagger.burp;

import burp.api.montoya.extension.ExtensionUnloadingHandler;

/**
 * Releases the extension's resources on unload (BApp rule 4). The most important
 * resource is the background thread pool, which must be terminated so no worker
 * threads linger after the extension is removed.
 */
final class UnloadHandler implements ExtensionUnloadingHandler {

    private final BackgroundWorker worker;

    UnloadHandler(BackgroundWorker worker) {
        this.worker = worker;
    }

    @Override
    public void extensionUnloaded() {
        worker.shutdown();
    }
}
