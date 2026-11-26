//
// MIT License
//
// Copyright (c) 2016-present, Approov Ltd.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
// (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
// publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
// subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
// ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
// THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package io.approov.service.android.cronet;

import io.approov.service.android.ApproovLog;

import org.chromium.net.BidirectionalStream;
import org.chromium.net.CronetEngine;
import org.chromium.net.ExperimentalBidirectionalStream;
import org.chromium.net.ExperimentalCronetEngine;
import org.chromium.net.ExperimentalUrlRequest;
import org.chromium.net.UrlRequest;

import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandlerFactory;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.approov.service.android.ApproovService;

/**
 * ApproovCronetEngine is the Cronet engine your app gets from ApproovCronetEngineBuilder. A request
 * built on it is only recorded until it is started. It then runs through the ApproovService pipeline
 * on an Approov thread and is sent on a real engine from the pinned generations, chosen after the token
 * fetch so that any pins delivered by that fetch apply. Other calls act on the current generation,
 * building it if needed. It is an ExperimentalCronetEngine, as every engine real Cronet builds is, so
 * code that casts the engine, its request builders or its requests to the Experimental types keeps
 * working.
 * <p>
 * Note that openConnection() and createURLStreamHandlerFactory() get the pinned engine but no Approov
 * headers and are reported with one error per engine, openConnection(URL, Proxy) refuses a proxy as
 * Cronet does, and bidirectional streams are not supported. Every other inherited method keeps Cronet's
 * default, which sends nothing.
 */
@SuppressWarnings("deprecation") // ExperimentalCronetEngine is the type real Cronet engines have
public final class ApproovCronetEngine extends ExperimentalCronetEngine {
    // logging tag
    static final String TAG = "ApproovCronet";

    // source of the real engines that requests are sent on
    private final CronetEngineSource engines;

    // executor running the Approov processing of each request
    private final Executor approovExecutor;

    // purpose of the engine, used to identify it in the logs
    private final String purpose;

    // true once the use of an unprotected method has been reported
    private final AtomicBoolean unprotectedReported = new AtomicBoolean();

    ApproovCronetEngine(CronetEngineSource engines, Executor approovExecutor, String purpose) {
        this.engines = engines;
        this.approovExecutor = approovExecutor;
        this.purpose = purpose;
    }

    // creates an engine whose pinned generations are built from the recorded settings
    static ApproovCronetEngine create(final String purpose, RecordingCronetEngineBuilder recording, File storageRoot) {
        PinnedCronetEngines engines = new PinnedCronetEngines(recording::newRealBuilder,
                ApproovCronetEngine::approovPins, storageRoot, new PinnedCronetEngines.Diagnostics() {
                    @Override
                    public void info(String message) {
                        ApproovLog.i(TAG, "[" + purpose + "] " + message);
                    }

                    @Override
                    public void warning(String message) {
                        ApproovLog.w(TAG, "[" + purpose + "] " + message);
                    }

                    @Override
                    public void error(String message) {
                        ApproovLog.e(TAG, "[" + purpose + "] " + message);
                    }
                });
        ApproovLog.i(TAG, "Approov Cronet engine created for " + purpose);
        return new ApproovCronetEngine(engines, Threads.EXECUTOR, purpose);
    }

    // no pins before protection is enabled, in bypass mode or without a configuration, so OS trust is used
    static Map<String, List<String>> approovPins() throws IOException {
        if (!ApproovService.isApproovProtectionEnabled())
            return Collections.emptyMap();
        Map<String, List<String>> pins = ApproovService.getPins("public-key-sha256");
        return (pins == null) ? Collections.<String, List<String>>emptyMap() : pins;
    }

    /**
     * Gets a new request builder. As with Cronet's own, a null url, callback or executor fails here, at
     * your app's call site.
     */
    @Override
    public ExperimentalUrlRequest.Builder newUrlRequestBuilder(String url, UrlRequest.Callback callback,
            Executor executor) {
        if (url == null)
            throw new NullPointerException("URL is required.");
        if (callback == null)
            throw new NullPointerException("Callback is required.");
        if (executor == null)
            throw new NullPointerException("Executor is required.");
        return new CronetRequestBuilder(new CronetRequestBuilder.Recording(url, callback, executor), recording -> {
            // as CronetUrlRequestContext, a request built after shutdown() fails at build()
            if (engines.isShutDown())
                throw new IllegalStateException(PinnedCronetEngines.SHUT_DOWN);
            return new ApproovCronetRequest(engines, approovExecutor, recording);
        });
    }

    // accessors run on the app's thread and must not throw, so a failure is only logged and null returned
    private CronetEngine accessorEngine(String accessor) {
        try {
            return engines.current();
        } catch (IOException | RuntimeException e) {
            ApproovLog.e(TAG, "[" + purpose + "] " + accessor + ": no Cronet engine is available: " + e);
            return null;
        }
    }

    /**
     * Bidirectional streams are not supported as they would carry no Approov protection.
     */
    @Override
    public ExperimentalBidirectionalStream.Builder newBidirectionalStreamBuilder(String url,
            BidirectionalStream.Callback callback, Executor executor) {
        throw new UnsupportedOperationException("Approov Cronet does not support bidirectional streams");
    }

    // reports, once per engine, a method that sends requests without Approov headers
    private void reportUnprotected(String what) {
        if (unprotectedReported.compareAndSet(false, true))
            ApproovLog.e(TAG, "[" + purpose + "] " + what + ": not protected by Approov, requests have no "
                    + "Approov token, status or signatures, use newUrlRequestBuilder");
    }

    /**
     * Gets the real engine's version, or "ApproovCronet/unavailable" if no engine can be made.
     */
    @Override
    public String getVersionString() {
        CronetEngine engine = accessorEngine("getVersionString");
        return (engine == null) ? "ApproovCronet/unavailable" : engine.getVersionString();
    }

    /**
     * Shuts the engine down. Unlike Cronet's own, which throws if requests are running, a generation with
     * requests in flight is shut down once they have finished, at the next request, pin change or
     * shutdown() call, so an app may call this from onDestroy() at any time. A request built or started
     * afterwards throws IllegalStateException("Engine is shut down."), as on Cronet, and a further call
     * only shuts down the generations whose requests have finished since.
     */
    @Override
    public void shutdown() {
        engines.shutdown();
    }

    /**
     * Binds the requests of the engine to a network, as Cronet's own does. The current generation is
     * bound and so is every generation built later for new pins.
     */
    @Override
    public void bindToNetwork(long networkHandle) {
        engines.bindToNetwork(networkHandle);
    }

    @Override
    public void startNetLogToDisk(String dirPath, boolean logAll, int maxSize) {
        CronetEngine engine = accessorEngine("startNetLogToDisk");
        if (engine != null)
            engine.startNetLogToDisk(dirPath, logAll, maxSize);
    }

    @Override
    public void startNetLogToFile(String fileName, boolean logAll) {
        CronetEngine engine = accessorEngine("startNetLogToFile");
        if (engine != null)
            engine.startNetLogToFile(fileName, logAll);
    }

    @Override
    public void stopNetLog() {
        CronetEngine engine = accessorEngine("stopNetLog");
        if (engine != null)
            engine.stopNetLog();
    }

    @Override
    public byte[] getGlobalMetricsDeltas() {
        CronetEngine engine = accessorEngine("getGlobalMetricsDeltas");
        return (engine == null) ? new byte[0] : engine.getGlobalMetricsDeltas();
    }

    @Override
    public URLConnection openConnection(URL url) throws IOException {
        reportUnprotected("CronetEngine.openConnection");
        return engines.current().openConnection(url);
    }

    /**
     * Opens a connection as Cronet's own engine does. A DIRECT proxy, such as Proxy.NO_PROXY, uses
     * openConnection(URL) on the pinned engine and any other proxy is refused, since Cronet cannot send
     * through one.
     *
     * @throws UnsupportedOperationException if the proxy is not DIRECT
     */
    @Override
    public URLConnection openConnection(URL url, Proxy proxy) throws IOException {
        // the inherited default would use the platform's own connection, with no Approov pins or headers
        if (proxy.type() != Proxy.Type.DIRECT)
            throw new UnsupportedOperationException("openConnection: Cronet cannot open a connection through a proxy");
        return openConnection(url);
    }

    /**
     * Creates Cronet's URL stream handler factory on the pinned engine. Note that its requests have no
     * Approov headers.
     *
     * @throws IllegalStateException if the Approov pins cannot be read or applied, as the factory is never
     * handed out unpinned
     */
    @Override
    public URLStreamHandlerFactory createURLStreamHandlerFactory() {
        reportUnprotected("CronetEngine.createURLStreamHandlerFactory");
        try {
            return engines.current().createURLStreamHandlerFactory();
        } catch (IOException e) {
            throw new IllegalStateException("createURLStreamHandlerFactory: " + e.getMessage(), e);
        }
    }

    // Threads provides the Approov threads, as request processing may block on a token fetch
    static final class Threads {
        // count of the threads created, used in their names
        private static final AtomicInteger COUNT = new AtomicInteger();

        // executor running the Approov processing of requests
        static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "ApproovCronet-" + COUNT.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        /**
         * Construction is disallowed as this is a static only class.
         */
        private Threads() {
        }
    }
}
