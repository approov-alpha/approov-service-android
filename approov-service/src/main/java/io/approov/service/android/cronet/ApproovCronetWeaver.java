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

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetProvider;

/**
 * ApproovCronetWeaver is the runtime target of the Cronet weaving done by the Approov Gradle plugin
 * io.approov.gradle. The plugin rewrites Cronet builder creation in your app's classes and in the
 * configured dependency packages (react-native-nitro-fetch by default), so that
 * new CronetEngine.Builder(context) becomes new ApproovCronetEngineBuilder(context, site) and
 * provider.createBuilder() becomes createBuilder(). The code's own builder.build() call is left as it
 * is and returns the Approov engine.
 */
public final class ApproovCronetWeaver {
    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovCronetWeaver() {
    }

    /**
     * Creates a builder woven in place of provider.createBuilder(). Each pinned engine generation asks
     * the same provider for a fresh builder.
     *
     * @param provider is the provider the code chose
     * @param site is the woven call site, as "dotted.Class.method"
     * @return a builder whose engine is protected by Approov
     */
    public static CronetEngine.Builder createBuilder(final CronetProvider provider, String site) {
        return new ApproovCronetEngineBuilder(new RecordingCronetEngineBuilder(provider::createBuilder), site, null);
    }
}
