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

package io.approov.service.android.okhttp;

import okhttp3.OkHttpClient;

/**
 * ApproovReactNativeWeaver is the target of the Approov Gradle plugin's React Native weave, which rewrites React
 * Native's OkHttpClientProvider so that the clients and builders it hands out carry Approov protection: React
 * Native's networking (fetch and XMLHttpRequest), Fresco and the Expo modules that use the provider. A WebSocket
 * upgrade goes out without Approov processing, as WebSockets are not supported.
 * It is not meant to be called directly.
 */
public final class ApproovReactNativeWeaver {
    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovReactNativeWeaver() {
    }

    /**
     * Adds Approov protection to the builder that OkHttpClientProvider.createClientBuilder() returns. As with
     * ApproovOkHttpWeaver, protection is added before ApproovService.initialize() too, and requests made before
     * then are sent without Approov processing. Any Approov interceptors already on the builder are replaced.
     *
     * @param builder is the builder React Native created, or null
     * @return the same builder with the Approov interceptors added, or null for null
     */
    public static OkHttpClient.Builder protectBuilder(OkHttpClient.Builder builder) {
        return (builder == null) ? null : ApproovOkHttp.protect(builder);
    }

    /**
     * Adds Approov protection to the client that an app's OkHttpClientFactory returned to OkHttpClientProvider.
     * The protected client is derived with newBuilder(), so it keeps the factory's settings and interceptors and
     * shares its connection pool and dispatcher, and it is protected and built as one step, as
     * ApproovOkHttpWeaver.build() does. A null client stays null, so React Native still falls back to
     * its default client, which createClientBuilder() protects.
     *
     * @param client is the client the app's factory returned, or null
     * @return a client with the Approov interceptors added, or null for null
     */
    public static OkHttpClient protectClient(OkHttpClient client) {
        return (client == null) ? null : ApproovOkHttp.protectAndBuild(client.newBuilder());
    }
}
