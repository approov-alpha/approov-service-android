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

import android.content.Context;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetProvider;

/**
 * ApproovCronet provides Approov protection for Cronet. Use these builders in place of
 * new CronetEngine.Builder(context) and provider.createBuilder(), or apply the Approov Gradle plugin
 * io.approov.gradle, which makes that change at build time in your app's classes and in the
 * configured dependency packages (react-native-nitro-fetch by default).
 * <p>
 * Requests made on the engine go through the ApproovService pipeline like those of every other
 * adapter, and connections are checked against the Approov pins for the host, or the Managed Trust
 * Roots, with an engine rebuilt whenever the pins change. Before ApproovService.initialize(), and in
 * bypass mode, requests pass through unchanged on OS trust. Note that if Cronet falls back to its Java
 * engine (no Google Play services and no bundled native Cronet), which never checks pins, requests to
 * hosts with Approov pins fail as pinning failures and all other requests are sent on OS trust.
 */
public final class ApproovCronet {
    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovCronet() {
    }

    /**
     * Gets a builder that picks its Cronet provider as new CronetEngine.Builder(context) does.
     *
     * @param context is the context to be used
     * @return a builder whose engine is protected by Approov
     */
    public static CronetEngine.Builder newBuilder(Context context) {
        return new ApproovCronetEngineBuilder(context, "ApproovCronet.newBuilder");
    }

    /**
     * Gets a builder that uses the given Cronet provider.
     *
     * @param provider is the Cronet provider to be used
     * @return a builder whose engine is protected by Approov
     */
    public static CronetEngine.Builder newBuilder(CronetProvider provider) {
        return ApproovCronetWeaver.createBuilder(provider, "ApproovCronet.newBuilder");
    }

    /**
     * Determines whether an engine is an Approov engine.
     *
     * @param engine is the engine to be checked
     * @return true if its requests are protected by Approov, false otherwise
     */
    public static boolean isProtected(CronetEngine engine) {
        return engine instanceof ApproovCronetEngine;
    }
}
