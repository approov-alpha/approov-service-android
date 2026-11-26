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

import okhttp3.HttpUrl;

/**
 * ApproovAppUrl is the request tag that holds the URL of a call as the app's own application interceptors left
 * it. Only an attempt to this URL has its query parameters substituted, as any other URL, such as a redirect
 * target, is the server's.
 */
final class ApproovAppUrl {
    // the URL of the call as the app made it
    private final HttpUrl url;

    ApproovAppUrl(HttpUrl url) {
        this.url = url;
    }

    /**
     * Gets the URL of the call as the app made it.
     *
     * @return the app's URL
     */
    HttpUrl getURL() {
        return url;
    }
}
