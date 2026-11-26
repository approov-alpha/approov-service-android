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

import io.approov.service.android.ApproovLog;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.approov.service.android.ApproovService;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

/**
 * ApproovOkHttp is the OkHttp adapter of the Approov service layer. It provides OkHttpClient instances that
 * route every network attempt through the ApproovService request pipeline, which adds the Approov token, performs
 * secure string substitutions and applies any configured message signing, and that pin the connections
 * with the Approov dynamic pins.
 * <p>
 * Obtain clients with getOkHttpClient(), or add the interceptors to your own builder with protect().
 * Retrofit and Ktor (with the OkHttp engine) are protected by passing them such a client.
 */
public class ApproovOkHttp {
    // logging tag
    private static final String TAG = "ApproovOkHttp";

    // name for the default builder
    private static final String DEFAULT_BUILDER_NAME = "_default";

    // the Approov pins shared by all clients, with the network interceptor built on them
    private static OkHttpPins pins = null;
    private static ApproovInterceptor interceptor = null;

    // the application interceptor of every protected client, which holds no state
    private static final ApproovCallInterceptor CALL_INTERCEPTOR = new ApproovCallInterceptor();

    // builders to be used for new OkHttp clients where each can be named
    private static final Map<String, OkHttpClient.Builder> okHttpBuilders = new HashMap<>();

    // cached OkHttpClients to use for each of the named builders
    private static final Map<String, OkHttpClient> okHttpClients = new HashMap<>();

    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovOkHttp() {
    }

    private static synchronized ApproovInterceptor getInterceptor() {
        if (interceptor == null) {
            // no pins are read here, so a pins change notified before the listener is registered is never lost
            pins = new OkHttpPins();
            interceptor = new ApproovInterceptor(pins);
            // rebuild the derived CertificatePinner whenever the dynamic pins change
            ApproovService.registerPinsChangeListener(new Runnable() {
                @Override
                public void run() {
                    OkHttpPins current;
                    synchronized (ApproovOkHttp.class) {
                        current = pins;
                    }
                    if (current != null)
                        current.buildPins();
                }
            });
        }
        return interceptor;
    }

    /**
     * Gets the Approov network interceptor shared by every protected client, for an app that adds it to its own
     * builder rather than using protect(). It must be the last network interceptor, added after any of the app's
     * own, so that it protects each network attempt as it is written and its changes reach the wire unchanged. On
     * its own it substitutes query parameters only in an attempt to the URL of the call's original request, as
     * protect() also adds the application interceptor that gives it the URL the app's application interceptors
     * left, and the app then sees no-store as the Cache-Control of a response to a substituted query parameter,
     * which is kept out of the cache. Prefer protect().
     *
     * @return the Approov network interceptor
     */
    public static Interceptor networkInterceptor() {
        return getInterceptor();
    }

    /**
     * Sets the OkHttpClient.Builder to be used for constructing the Approov OkHttpClient for a named
     * builder. This allows custom configurations to be set, with additional interceptors and properties.
     * This clears the appropriate cached OkHttp client so should only be called when an actual builder
     * change is required.
     *
     * @param builderName is the name of the builder to set
     * @param builder is the OkHttpClient.Builder to be used as a basis for the Approov OkHttpClient
     */
    public static synchronized void setOkHttpClientBuilder(String builderName, OkHttpClient.Builder builder) {
        if (builderName == null)
            throw new IllegalArgumentException("builderName must not be null");
        if (builder == null)
            throw new IllegalArgumentException("builder must not be null");
        ApproovLog.d(TAG, "OkHttp client builder set for " + builderName);
        okHttpBuilders.put(builderName, builder);
        // always force a rebuild of the client as builders are mutable, even if the same one is set again
        okHttpClients.remove(builderName);
    }

    /**
     * Sets the OkHttpClient.Builder to be used for constructing the default Approov OkHttpClient. This
     * allows a default custom configuration to be set, with additional interceptors and properties.
     *
     * @param builder is the OkHttpClient.Builder to be used as a basis for the Approov OkHttpClient
     */
    public static synchronized void setOkHttpClientBuilder(OkHttpClient.Builder builder) {
        setOkHttpClientBuilder(DEFAULT_BUILDER_NAME, builder);
    }

    /**
     * Adds the Approov interceptors to the given builder, removing any previously added Approov interceptors
     * first, so protecting a builder more than once leaves a single set. Use this if you prefer to manage the
     * OkHttpClient lifecycle yourself rather than using getOkHttpClient(). The Approov network interceptor is
     * appended as the last network interceptor, so it protects each network attempt, a redirect, retry or
     * Authenticator follow-up included, just before it is written, and your own network interceptors run before
     * it: they never see the Approov headers or secure strings, and a change they make is covered by the
     * signature. A small Approov application interceptor is appended as the last application interceptor. Add
     * any further interceptors before calling this.
     *
     * @param builder is the OkHttpClient.Builder to protect
     * @return the same builder with the Approov interceptors added
     */
    public static synchronized OkHttpClient.Builder protect(OkHttpClient.Builder builder) {
        if (builder == null)
            throw new IllegalArgumentException("builder must not be null");
        // remove any existing Approov interceptor from either list, wherever it is
        removeApproovInterceptors(builder.interceptors());
        removeApproovInterceptors(builder.networkInterceptors());

        // both go last, so the app's interceptors run before them
        builder.interceptors().add(CALL_INTERCEPTOR);
        builder.networkInterceptors().add(getInterceptor());
        return builder;
    }

    /**
     * Adds the Approov interceptors to the given builder, as protect() does, and builds a client from it while
     * still holding the lock that protect() takes. Protecting a builder removes and then adds the Approov
     * interceptors, so a build of the same builder that another thread runs in between would copy its lists
     * without them. The plugin's weavers use this, as an app may build one builder on several threads.
     *
     * @param builder is the OkHttpClient.Builder to protect and build
     * @return the OkHttpClient with the Approov interceptors
     */
    static synchronized OkHttpClient protectAndBuild(OkHttpClient.Builder builder) {
        return protect(builder).build();
    }

    // removes every Approov interceptor from the list
    private static void removeApproovInterceptors(List<Interceptor> interceptors) {
        Iterator<Interceptor> iter = interceptors.iterator();
        while (iter.hasNext()) {
            Interceptor interceptor = iter.next();
            if ((interceptor instanceof ApproovInterceptor) || (interceptor instanceof ApproovCallInterceptor))
                iter.remove();
        }
    }

    /**
     * Gets the OkHttpClient that enables the Approov service for the named builder. This adds the Approov
     * token in a header to requests, performs any configured secure string substitutions and message
     * signing, and pins the connections. The OkHttpClient is constructed lazily on demand but is cached if
     * there are no changes. Use setOkHttpClientBuilder() to provide any special properties. Note that a
     * client obtained before initialize() becomes protected once initialization completes.
     *
     * @param builderName is the name for the builder
     * @return OkHttpClient to be used with Approov
     */
    public static synchronized OkHttpClient getOkHttpClient(String builderName) {
        if (builderName == null)
            throw new IllegalArgumentException("builderName must not be null");
        OkHttpClient okHttpClient = okHttpClients.get(builderName);
        if (okHttpClient == null) {
            // get the builder and warn if none was available
            OkHttpClient.Builder okHttpBuilder = okHttpBuilders.get(builderName);
            if (okHttpBuilder == null) {
                ApproovLog.d(TAG, "No builder available for " + builderName);
                okHttpBuilder = new OkHttpClient.Builder();
                okHttpBuilders.put(builderName, okHttpBuilder);
            }
            // we always add the Approov interceptors, even before initialization, so that we never cache an
            // unprotected client: requests proceed without Approov processing until protection is enabled
            if (!ApproovService.isApproovServiceEnabled())
                ApproovLog.w(TAG, "Building Approov OkHttpClient for " + builderName + ": not protected until initialized");
            else
                ApproovLog.d(TAG, "Building new Approov OkHttpClient for " + builderName);
            okHttpClient = protectAndBuild(okHttpBuilder);
            // cache the client for future usages
            okHttpClients.put(builderName, okHttpClient);
        }
        return okHttpClient;
    }

    /**
     * Gets the default OkHttpClient that enables the Approov service. This adds the Approov token in a
     * header to requests, performs any configured secure string substitutions and message signing, and pins
     * the connections. The OkHttpClient is constructed lazily on demand but is cached if there are no
     * changes. Use setOkHttpClientBuilder() to provide any special properties.
     *
     * @return OkHttpClient to be used with Approov
     */
    public static synchronized OkHttpClient getOkHttpClient() {
        return getOkHttpClient(DEFAULT_BUILDER_NAME);
    }

    // for tests only
    static synchronized void resetForTesting() {
        pins = null;
        interceptor = null;
        okHttpBuilders.clear();
        okHttpClients.clear();
    }
}
