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

import java.util.List;

import io.approov.service.android.ApproovService;
import okhttp3.Request;
import okhttp3.Response;

/**
 * OkHttpCacheBypass keeps a response to a request whose query carries a substituted secure string out of the
 * app's OkHttp Cache, as Cronet's disableCache() does. The cache sees only the request that ApproovInterceptor was
 * given, with the placeholder, because ApproovInterceptor gives that request back on the response. So this is a
 * second defence, and it keeps the cache from storing a response to a request the query secret reached.
 * ApproovInterceptor marks the response to such an attempt no-store, which OkHttp's CacheInterceptor honours,
 * and ApproovCallInterceptor gives the app back the server's own Cache-Control. ApproovCallInterceptor tags one
 * on each call and it is shared by the call's attempts, which OkHttp makes one after another. An attempt of a
 * call without one, where the app added only the network interceptor, is still marked no-store, but the app then
 * sees no-store.
 */
final class OkHttpCacheBypass {
    // the value that keeps a response out of the cache
    private static final String NO_STORE = "no-store";

    // the server's Cache-Control values of the last network attempt marked no-store, or null if it was not
    private volatile List<String> serverCacheControl;

    /**
     * Marks the response to a network attempt as not to be stored if the attempt carried a substituted query
     * secure string, keeping the server's Cache-Control in the call's bypass if it has one.
     *
     * @param sent is the request as the attempt was sent
     * @param prepared is the pipeline state of the attempt, holding what the attempt carried
     * @param response is the response to the attempt
     * @return the response, marked no-store if needed
     */
    static Response markAttempt(Request sent, ApproovService.ApproovPreparedRequest prepared, Response response) {
        OkHttpCacheBypass bypass = sent.tag(OkHttpCacheBypass.class);
        if (prepared.getChanges().getSubstitutionQueryParamKeys().isEmpty()) {
            if (bypass != null)
                bypass.serverCacheControl = null;
            return response;
        }
        if (bypass != null)
            bypass.serverCacheControl = response.headers("Cache-Control");
        return response.newBuilder().header("Cache-Control", NO_STORE).build();
    }

    /**
     * Gives the app the server's own Cache-Control on a response whose last network attempt was marked
     * no-store. A response served from the cache was never marked.
     *
     * @param request is the request of the call, as ApproovCallInterceptor tagged it
     * @param response is the response of the call
     * @return the response with the server's Cache-Control
     */
    static Response restore(Request request, Response response) {
        OkHttpCacheBypass bypass = request.tag(OkHttpCacheBypass.class);
        List<String> server = (bypass == null) ? null : bypass.serverCacheControl;
        if ((server == null) || (response.networkResponse() == null))
            return response;
        Response.Builder builder = response.newBuilder().removeHeader("Cache-Control");
        for (String value : server)
            builder.addHeader("Cache-Control", value);
        return builder.build();
    }
}
