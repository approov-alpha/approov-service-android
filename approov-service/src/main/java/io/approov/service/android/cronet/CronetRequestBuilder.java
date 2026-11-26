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

import org.chromium.net.ExperimentalUrlRequest;
import org.chromium.net.UploadDataProvider;
import org.chromium.net.UrlRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * CronetRequestBuilder records your app's request builder calls without touching Cronet, so build()
 * stays cheap and non-blocking, as React Native libraries call it on the JS thread.
 */
final class CronetRequestBuilder extends ExperimentalUrlRequest.Builder {
    // Recording holds everything your app set on its builder, replayed onto the real one at start()
    static final class Recording {
        // the URL of the request
        final String url;

        // your app's callback for the request
        final UrlRequest.Callback callback;

        // the executor your app's callbacks run on
        final Executor callbackExecutor;

        // the HTTP method, or null for the default
        String method;

        // the headers as name and value pairs
        final List<String[]> headers = new ArrayList<>();

        // true if the cache is disabled
        boolean disableCache;

        // the request priority, or null if not set
        Integer priority;

        // the upload data provider, or null if there is no body
        UploadDataProvider uploadProvider;

        // the executor the upload data provider runs on
        Executor uploadExecutor;

        // true if direct executors are allowed
        boolean allowDirectExecutor;

        // the request annotations
        final List<Object> annotations = new ArrayList<>();

        // the traffic stats tag, or null if not set
        Integer trafficStatsTag;

        // the traffic stats UID, or null if not set
        Integer trafficStatsUid;

        // the network handle to bind to, or null if not set
        Long network;

        // the idempotency, or null if not set
        Integer idempotency;

        // true if connection migration is disabled
        boolean disableConnectionMigration;

        Recording(String url, UrlRequest.Callback callback, Executor callbackExecutor) {
            this.url = url;
            this.callback = callback;
            this.callbackExecutor = callbackExecutor;
        }

        // a copy, so later builder calls do not change a built request
        Recording snapshot() {
            Recording copy = new Recording(url, callback, callbackExecutor);
            copy.method = method;
            for (String[] header : headers)
                copy.headers.add(new String[] {header[0], header[1]});
            copy.disableCache = disableCache;
            copy.priority = priority;
            copy.uploadProvider = uploadProvider;
            copy.uploadExecutor = uploadExecutor;
            copy.allowDirectExecutor = allowDirectExecutor;
            copy.annotations.addAll(annotations);
            copy.trafficStatsTag = trafficStatsTag;
            copy.trafficStatsUid = trafficStatsUid;
            copy.network = network;
            copy.idempotency = idempotency;
            copy.disableConnectionMigration = disableConnectionMigration;
            return copy;
        }
    }

    // RequestFactory makes the request from a snapshot of the recording
    interface RequestFactory {
        ExperimentalUrlRequest create(Recording recording);
    }

    // the recording of your app's builder calls
    private final Recording recording;

    // the factory making the request at build()
    private final RequestFactory factory;

    CronetRequestBuilder(Recording recording, RequestFactory factory) {
        this.recording = recording;
        this.factory = factory;
    }

    @Override
    public ExperimentalUrlRequest.Builder setHttpMethod(String method) {
        if (method == null)
            throw new NullPointerException("Method is required.");
        recording.method = method;
        return this;
    }

    @Override
    public ExperimentalUrlRequest.Builder addHeader(String header, String value) {
        if (header == null)
            throw new NullPointerException("Invalid header name.");
        if (value == null)
            throw new NullPointerException("Invalid header value.");
        recording.headers.add(new String[] {header, value});
        return this;
    }

    @Override
    public ExperimentalUrlRequest.Builder disableCache() {
        recording.disableCache = true;
        return this;
    }

    @Override
    public ExperimentalUrlRequest.Builder setPriority(int priority) {
        recording.priority = priority;
        return this;
    }

    @Override
    public ExperimentalUrlRequest.Builder setUploadDataProvider(UploadDataProvider provider, Executor executor) {
        if (provider == null)
            throw new NullPointerException("Invalid UploadDataProvider.");
        if (executor == null)
            throw new NullPointerException("Invalid UploadDataProvider Executor.");
        recording.uploadProvider = provider;
        recording.uploadExecutor = executor;
        return this;
    }

    @Override
    public ExperimentalUrlRequest.Builder allowDirectExecutor() {
        recording.allowDirectExecutor = true;
        return this;
    }

    @Override
    public ExperimentalUrlRequest.Builder addRequestAnnotation(Object annotation) {
        recording.annotations.add(annotation);
        return this;
    }

    @Override
    public ExperimentalUrlRequest.Builder setTrafficStatsTag(int tag) {
        recording.trafficStatsTag = tag;
        return this;
    }

    @Override
    public ExperimentalUrlRequest.Builder setTrafficStatsUid(int uid) {
        recording.trafficStatsUid = uid;
        return this;
    }

    @Override
    public ExperimentalUrlRequest.Builder bindToNetwork(long networkHandle) {
        recording.network = networkHandle;
        return this;
    }

    @Override
    public ExperimentalUrlRequest.Builder setIdempotency(int idempotency) {
        recording.idempotency = idempotency;
        return this;
    }

    @Override
    public ExperimentalUrlRequest.Builder disableConnectionMigration() {
        recording.disableConnectionMigration = true;
        return this;
    }

    @Override
    public ExperimentalUrlRequest build() {
        return factory.create(recording.snapshot());
    }
}
