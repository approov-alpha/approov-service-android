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

import org.chromium.net.CronetException;
import org.chromium.net.ExperimentalUrlRequest;
import org.chromium.net.UploadDataProviders;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import io.approov.service.android.ApproovService;

/**
 * ApproovCronetRequest is the UrlRequest your app holds for an ApproovCronetEngine. It becomes a real
 * Cronet request only once started, after the ApproovService pipeline has run on an Approov thread.
 * Every callback your app receives carries this request as its request argument, since callers call
 * read() and followRedirect() on it, and response info whose URL and URL chain are the URLs of each hop
 * before Approov was applied, so a secure string substituted into a query is never shown. Note that a
 * secret a server echoes into a redirect Location does reach your app, as that value came from the
 * server.
 * <p>
 * Callbacks follow CronetUrlRequest, so a cancel before start or after completion is ignored,
 * followRedirect() after cancel returns, and exactly one terminal callback reaches your app. Redirects
 * are not followed by Cronet, which would resend every header, Approov's included, to the new URL.
 * Instead followRedirect() cancels the current hop quietly and starts a new one to the new URL, built as
 * Chromium builds a redirect from the headers of the hop as sent. The pipeline then strips what it
 * added, restores secure string placeholders and classifies the new URL afresh, under the mutator the
 * request was first processed with.
 */
final class ApproovCronetRequest extends ExperimentalUrlRequest {
    // Chromium's redirect limit (URLRequest::kMaxRedirects)
    static final int MAX_REDIRECTS = 20;

    // failure message for a URL whose authority cannot be verified, which never names the URL as it may
    // reach JavaScript
    static final String UNVERIFIABLE_AUTHORITY = "Approov: unverifiable URL authority (userinfo, backslash, "
            + "whitespace, percent-encoded host or invalid port)";

    // states of the request, where PREPARING is processing a hop and STARTED has a real request running
    private enum State { NEW, PREPARING, STARTED, FINISHED }

    // Hop is one attempt, the request as it is to be sent to the url before Approov is applied to it
    private static final class Hop {
        // the URL of the attempt
        final String url;

        // the HTTP method of the attempt
        final String method;

        // the headers of the attempt as name and value pairs
        final List<String[]> headers;

        // the body of the attempt, or null if there is none
        final byte[] body;

        Hop(String url, String method, List<String[]> headers, byte[] body) {
            this.url = url;
            this.method = method;
            this.headers = headers;
            this.body = body;
        }
    }

    // source of the real engines the hops are sent on
    private final CronetEngineSource engines;

    // executor running the Approov processing of each hop
    private final Executor approovExecutor;

    // the request as your app built it
    private final CronetRequestBuilder.Recording rec;

    // lock guarding the request state
    private final Object lock = new Object();

    // the current state of the request
    private State state = State.NEW;

    // true once your app has cancelled the request
    private boolean canceled;

    // the real request of the current hop, or null if there is none
    private UrlRequest inner;

    // the callback relaying the current hop's callbacks to your app
    private Relay relay;

    // the headers and body of the hop as sent, from which a redirect hop is built
    private Hop sent;

    // the redirect URL your app may follow, or null if there is none
    private String pendingRedirectUrl;

    // the HTTP status code of the pending redirect
    private int pendingRedirectCode;

    // the number of redirects followed so far
    private int redirectsFollowed;

    // the URL of every hop before Approov was applied to it, as your app is shown it
    private final List<String> urlChain = new ArrayList<>();

    // the request's Approov processing, shared by all its hops and only touched on the Approov thread
    // running the current hop
    private ApproovService.ApproovPreparedRequest prepared;

    ApproovCronetRequest(CronetEngineSource engines, Executor approovExecutor, CronetRequestBuilder.Recording rec) {
        this.engines = engines;
        this.approovExecutor = approovExecutor;
        this.rec = rec;
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (state != State.NEW)
                throw new IllegalStateException("Request is already started.");
            // as CronetUrlRequest, a request started after the engine's shutdown() fails at start()
            if (engines.isShutDown())
                throw new IllegalStateException(PinnedCronetEngines.SHUT_DOWN);
            state = State.PREPARING;
        }
        approovExecutor.execute(guarded(() -> {
            // build the first hop, reading the whole upload body
            Hop first;
            try {
                String method = (rec.method != null) ? rec.method : ((rec.uploadProvider != null) ? "POST" : "GET");
                byte[] body = (rec.uploadProvider == null) ? null : readAndClose(rec.uploadProvider, rec.uploadExecutor);
                first = new Hop(rec.url, method, rec.headers, body);
            } catch (Throwable t) {
                fail(t);
                return;
            }
            prepareAndStart(first);
        }));
    }

    // Cronet closes the provider when the request ends, so it is read once and closed; the body is resent on redirects
    private static byte[] readAndClose(org.chromium.net.UploadDataProvider provider, Executor executor)
            throws java.io.IOException {
        try {
            return CronetBodyReader.readAll(provider, executor);
        } finally {
            try {
                provider.close();
            } catch (Exception e) {
                ApproovLog.e(ApproovCronetEngine.TAG, "closing the app's UploadDataProvider failed: " + e);
            }
        }
    }

    // an exception escaping a pool thread is uncaught and kills the app process
    private Runnable guarded(final Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                // the class only: fail() logs the message, without URLs
                ApproovLog.e(ApproovCronetEngine.TAG, "Approov Cronet request processing failed: " + t.getClass().getName());
                try {
                    fail(t);
                } catch (Throwable ignored) {
                    // fail() already logs; nothing else can be done on this thread
                }
            }
        };
    }

    // a rejected callback is dropped with an error log, as CronetUrlRequest does
    private void postToApp(Runnable callback) {
        try {
            rec.callbackExecutor.execute(callback);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            ApproovLog.e(ApproovCronetEngine.TAG, "the app's callback executor rejected an Approov Cronet callback: " + e);
        }
    }

    // runs the hop through the ApproovService pipeline and starts its real request
    private void prepareAndStart(Hop next) {
        synchronized (lock) {
            if (canceled || (state == State.FINISHED))
                return; // no token fetch for a cancelled hop
            urlChain.add(next.url);
        }
        Relay newRelay = new Relay();
        UrlRequest request;
        CronetRequestContext context;
        // the generation this hop is sent on, held until its real request has started
        CronetEngineSource.Lease lease = null;
        try {
            // an authority Cronet could read as another host than this layer fails closed, before any token fetch
            if (CronetUrls.origin(next.url) == null)
                throw new ApproovCronetException(UNVERIFIABLE_AUTHORITY, null, ApproovCronetException.ERR_INVALID_URL);
            context = new CronetRequestContext(next.url, next.method, next.headers, next.body);
            if (prepared == null) {
                prepared = ApproovService.prepareRequest(context);
                prepared.completeProcessing(context);
            }
            // a redirect hop is reclassified and any held protection is refreshed for the attempt
            prepared.refreshProtectionForAttempt(context);
            CronetUrls.Origin target = CronetUrls.origin(context.getURL());
            if (target == null)
                throw new ApproovCronetException(UNVERIFIABLE_AUTHORITY, null, ApproovCronetException.ERR_INVALID_URL);
            // the engine is chosen after the token fetch, so any pins it delivered apply
            lease = engines.acquire();
            prepared.refreshProtectionIfStale(context);
            // cleartext to a host this generation pins is a pinning failure
            lease.checkConnection(target.scheme, target.host);
            // build the real request from the processed request and your app's settings
            UrlRequest.Builder b = lease.engine().newUrlRequestBuilder(context.getURL(), newRelay,
                    rec.callbackExecutor);
            b.setHttpMethod(context.getMethod());
            for (String[] header : context.headers())
                b.addHeader(header[0], header[1]);
            // a processed request's URL may carry a substituted secure string, which Cronet's disk cache would key on
            if (rec.disableCache || prepared.isProcessed())
                b.disableCache();
            if (rec.priority != null)
                b.setPriority(rec.priority);
            if (context.body() != null) {
                b.setUploadDataProvider(UploadDataProviders.create(context.body()),
                        (rec.uploadExecutor != null) ? rec.uploadExecutor : rec.callbackExecutor);
            }
            if (rec.allowDirectExecutor)
                b.allowDirectExecutor();
            for (Object annotation : rec.annotations)
                b.addRequestAnnotation(annotation);
            if (rec.trafficStatsTag != null)
                b.setTrafficStatsTag(rec.trafficStatsTag);
            if (rec.trafficStatsUid != null)
                b.setTrafficStatsUid(rec.trafficStatsUid);
            if (rec.network != null)
                b.bindToNetwork(rec.network);
            if (b instanceof ExperimentalUrlRequest.Builder) {
                ExperimentalUrlRequest.Builder experimental = (ExperimentalUrlRequest.Builder) b;
                if (rec.idempotency != null)
                    experimental.setIdempotency(rec.idempotency);
                if (rec.disableConnectionMigration)
                    experimental.disableConnectionMigration();
            }
            request = b.build();
        } catch (Throwable t) {
            release(lease);
            fail(t);
            return;
        }
        synchronized (lock) {
            if (canceled || (state == State.FINISHED)) {
                release(lease);
                return; // cancelled while preparing; onCanceled already delivered
            }
            inner = request;
            relay = newRelay;
            sent = new Hop(context.getURL(), context.getMethod(), context.headers(), context.body());
            state = State.STARTED;
        }
        try {
            request.start();
        } catch (Throwable t) {
            fail(t);
            return;
        } finally {
            // started (Cronet now refuses to shut the generation down) or failed
            release(lease);
        }
        // a cancel that arrived between publishing STARTED and start() reached a request Cronet had not
        // started, which Cronet ignores, so we cancel again
        boolean cancelNow;
        synchronized (lock) {
            cancelNow = canceled;
        }
        if (cancelNow)
            request.cancel();
    }

    private static void release(CronetEngineSource.Lease lease) {
        if (lease != null)
            lease.release();
    }

    // delivers onFailed unless the request has already finished
    private void fail(Throwable t) {
        synchronized (lock) {
            if (state == State.FINISHED)
                return;
            state = State.FINISHED;
        }
        // the message may reach JavaScript and the log is readable by others, so any URL in it is cut down
        // to its host
        final CronetException error = (t instanceof CronetException) ? (CronetException) t
                : new ApproovCronetException(CronetUrls.withoutUrls(
                        (t.getMessage() != null) ? t.getMessage() : t.getClass().getName()), t);
        ApproovLog.e(ApproovCronetEngine.TAG, "Approov Cronet request failed: " + CronetUrls.withoutUrls(error.getMessage()));
        postToApp(() -> terminal(() -> rec.callback.onFailed(this, null, error)));
    }

    @Override
    public void followRedirect() {
        UrlRequest old;
        Hop next;
        boolean tooMany;
        synchronized (lock) {
            if (canceled || (state == State.FINISHED))
                return; // as CronetUrlRequest after cancel
            if ((state != State.STARTED) || (pendingRedirectUrl == null) || (sent == null) || (inner == null))
                throw new IllegalStateException("No redirect to follow.");
            tooMany = redirectsFollowed >= MAX_REDIRECTS;
            redirectsFollowed++;
            next = redirectHop(sent, pendingRedirectUrl, pendingRedirectCode);
            pendingRedirectUrl = null;
            if (relay != null)
                relay.superseded = true; // the old hop's cancel must not reach the app
            old = inner;
            inner = null;
            relay = null;
            state = State.PREPARING;
        }
        old.cancel();
        if (tooMany) {
            fail(new ApproovCronetException("net::ERR_TOO_MANY_REDIRECTS", null,
                    ApproovCronetException.ERR_TOO_MANY_REDIRECTS));
            return;
        }
        approovExecutor.execute(guarded(() -> prepareAndStart(next)));
    }

    // as Chromium: 303, or 301/302 after POST, is a GET without body or Content-Type; cross-origin drops Authorization
    private static Hop redirectHop(Hop current, String newUrl, int code) {
        boolean toGet = ((code == 303) && !current.method.equalsIgnoreCase("HEAD"))
                || (((code == 301) || (code == 302)) && current.method.equalsIgnoreCase("POST"));
        boolean sameOrigin = sameOrigin(current.url, newUrl);
        List<String[]> headers = new ArrayList<>();
        for (String[] header : current.headers) {
            if (toGet && header[0].equalsIgnoreCase("Content-Type"))
                continue;
            if (!sameOrigin && header[0].equalsIgnoreCase("Authorization"))
                continue;
            headers.add(header);
        }
        return new Hop(newUrl, toGet ? "GET" : current.method, headers, toGet ? null : current.body);
    }

    // false if the first URL cannot be read
    private static boolean sameOrigin(String a, String b) {
        CronetUrls.Origin x = CronetUrls.origin(a);
        return (x != null) && x.equals(CronetUrls.origin(b));
    }

    @Override
    public void cancel() {
        UrlRequest toCancel;
        synchronized (lock) {
            // as CronetUrlRequest: ignored before start and once done
            if ((state == State.NEW) || (state == State.FINISHED) || canceled)
                return;
            canceled = true;
            if (state == State.PREPARING) {
                state = State.FINISHED;
                toCancel = null;
            } else {
                toCancel = inner; // STARTED: the inner onCanceled reaches the app through Relay
            }
        }
        if (toCancel != null)
            toCancel.cancel();
        else
            postToApp(() -> terminal(() -> rec.callback.onCanceled(this, null)));
    }

    @Override
    public void read(ByteBuffer buffer) {
        UrlRequest current;
        synchronized (lock) {
            current = inner;
        }
        if (current == null)
            throw new IllegalStateException("Request is not started.");
        current.read(buffer);
    }

    /**
     * Determines whether the request is done, which is true as soon as cancel() returns, as with
     * CronetUrlRequest.
     */
    @Override
    public boolean isDone() {
        synchronized (lock) {
            return (state == State.FINISHED) || canceled;
        }
    }

    @Override
    public void getStatus(final StatusListener listener) {
        UrlRequest current;
        final int status;
        synchronized (lock) {
            current = inner;
            status = ((state == State.PREPARING) && !canceled) ? Status.IDLE : Status.INVALID;
        }
        if (current != null)
            current.getStatus(listener);
        else
            postToApp(() -> listener.onStatus(status));
    }

    private void finish() {
        synchronized (lock) {
            state = State.FINISHED;
        }
    }

    // never throws back into an executor thread, as with CronetUrlRequest
    private static void terminal(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            // CronetUrlRequest logs and swallows exceptions from final callbacks
            ApproovLog.e(ApproovCronetEngine.TAG, "exception in the app's final UrlRequest callback: " + e);
        }
    }

    // shows your app the URL it asked for on this hop and the chain of such URLs
    private UrlResponseInfo appFacing(UrlResponseInfo info) {
        List<String> chain;
        synchronized (lock) {
            chain = new ArrayList<>(urlChain);
        }
        return new AppFacingResponseInfo(info, chain.isEmpty() ? rec.url : chain.get(chain.size() - 1), chain);
    }

    // Relay forwards one hop's callbacks to your app, with this request as the request, until superseded
    private final class Relay extends UrlRequest.Callback {
        // true once a redirect hop has replaced this one, so its callbacks no longer reach your app
        volatile boolean superseded;

        @Override
        public void onRedirectReceived(UrlRequest request, UrlResponseInfo info, String newLocationUrl) throws Exception {
            if (superseded)
                return;
            synchronized (lock) {
                pendingRedirectUrl = newLocationUrl;
                pendingRedirectCode = info.getHttpStatusCode();
            }
            rec.callback.onRedirectReceived(ApproovCronetRequest.this, appFacing(info), newLocationUrl);
        }

        @Override
        public void onResponseStarted(UrlRequest request, UrlResponseInfo info) throws Exception {
            if (superseded)
                return;
            rec.callback.onResponseStarted(ApproovCronetRequest.this, appFacing(info));
        }

        @Override
        public void onReadCompleted(UrlRequest request, UrlResponseInfo info, ByteBuffer byteBuffer) throws Exception {
            if (superseded)
                return;
            rec.callback.onReadCompleted(ApproovCronetRequest.this, appFacing(info), byteBuffer);
        }

        @Override
        public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
            if (superseded)
                return;
            finish();
            final UrlResponseInfo shown = appFacing(info);
            terminal(() -> rec.callback.onSucceeded(ApproovCronetRequest.this, shown));
        }

        @Override
        public void onFailed(UrlRequest request, UrlResponseInfo info, final CronetException error) {
            if (superseded)
                return;
            finish();
            final UrlResponseInfo shown = (info == null) ? null : appFacing(info);
            terminal(() -> rec.callback.onFailed(ApproovCronetRequest.this, shown, error));
        }

        @Override
        public void onCanceled(UrlRequest request, UrlResponseInfo info) {
            if (superseded)
                return;
            finish();
            final UrlResponseInfo shown = (info == null) ? null : appFacing(info);
            terminal(() -> rec.callback.onCanceled(ApproovCronetRequest.this, shown));
        }
    }

    // AppFacingResponseInfo is response info with the URL and URL chain replaced by what your app asked for
    static final class AppFacingResponseInfo extends UrlResponseInfo {
        // the response info of the real request
        private final UrlResponseInfo wire;

        // the URL your app asked for on this hop
        private final String appUrl;

        // the chain of URLs your app asked for
        private final List<String> appChain;

        AppFacingResponseInfo(UrlResponseInfo wire, String appUrl, List<String> appChain) {
            this.wire = wire;
            this.appUrl = appUrl;
            this.appChain = appChain;
        }

        @Override public String getUrl() { return appUrl; }
        @Override public List<String> getUrlChain() { return appChain; }
        @Override public int getHttpStatusCode() { return wire.getHttpStatusCode(); }
        @Override public String getHttpStatusText() { return wire.getHttpStatusText(); }
        @Override public List<Map.Entry<String, String>> getAllHeadersAsList() { return wire.getAllHeadersAsList(); }
        @Override public Map<String, List<String>> getAllHeaders() { return wire.getAllHeaders(); }
        @Override public boolean wasCached() { return wire.wasCached(); }
        @Override public String getNegotiatedProtocol() { return wire.getNegotiatedProtocol(); }
        @Override public String getProxyServer() { return wire.getProxyServer(); }
        @Override public long getReceivedByteCount() { return wire.getReceivedByteCount(); }
    }
}
