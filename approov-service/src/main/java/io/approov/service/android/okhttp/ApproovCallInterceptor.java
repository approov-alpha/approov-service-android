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

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * ApproovCallInterceptor is the OkHttp application interceptor that runs once for each call, as the last
 * application interceptor so that it sees the request after the app's own application interceptors. It tags the
 * request with its URL as the app's URL. OkHttp builds each redirect, retry and Authenticator follow-up from the
 * request it is given here, so every attempt of the call carries the tag, and ApproovInterceptor substitutes
 * query parameters only in an attempt to that URL. It also tags the call's cache bypass, so that the app gets the
 * server's own Cache-Control on a response ApproovInterceptor kept out of the cache.
 */
final class ApproovCallInterceptor implements Interceptor {
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Request tagged = request.newBuilder()
                .tag(ApproovAppUrl.class, new ApproovAppUrl(request.url()))
                .tag(OkHttpCacheBypass.class, new OkHttpCacheBypass())
                .build();
        return OkHttpCacheBypass.restore(tagged, chain.proceed(tagged));
    }
}
