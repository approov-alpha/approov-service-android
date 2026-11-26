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
 * ApproovOkHttpWeaver is the runtime target of the Approov Gradle plugin. The plugin rewrites every
 * OkHttpClient.Builder.build() call site in the app to call build() here instead, so that plain OkHttp
 * clients are Approov protected without any source changes.
 * <p>
 * Note that this class must not be renamed or moved, as the plugin references it by its fully qualified
 * name and method signature.
 */
public final class ApproovOkHttpWeaver {
    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovOkHttpWeaver() {
    }

    /**
     * Builds an OkHttpClient from the given builder, always adding Approov protection. Protection is added
     * even before ApproovService.initialize(), so a client built early (for example one cached by the app)
     * becomes protected once initialization completes, and requests made before then are sent without
     * Approov processing. Any Approov interceptors already on the builder are replaced, so a builder woven
     * more than once still has a single set. The builder is protected and built as one step with respect to any
     * other protection, so a builder built on several threads at once gives each a client with a single set.
     *
     * @param builder is the OkHttpClient.Builder captured at the woven call site
     * @return the OkHttpClient with Approov protection
     */
    public static OkHttpClient build(OkHttpClient.Builder builder) {
        return ApproovOkHttp.protectAndBuild(builder);
    }
}
