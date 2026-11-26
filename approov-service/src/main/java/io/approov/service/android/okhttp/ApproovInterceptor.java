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

import io.approov.service.android.ApproovException;
import io.approov.service.android.ApproovLog;
import io.approov.service.android.ApproovService;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * ApproovInterceptor is the OkHttp network interceptor that applies Approov to every network attempt of a call.
 * It is the last network interceptor, so it protects each attempt as OkHttp is about to write it, after any app
 * network interceptor has made its changes and with the connection the attempt uses already established. OkHttp
 * builds each redirect, retry and Authenticator follow-up from the app's request before the network
 * interceptors run, so every attempt reaches here clean, with nothing Approov added to an earlier attempt, and is
 * protected afresh for its own URL. For each attempt the Approov token is fetched and the secure strings
 * substituted, the processed request callback runs and the request is signed, then the connection is checked
 * against the Approov pins, as the fetch may have delivered new ones, and only then is the request sent. A
 * response to an attempt whose query carries a substituted secure string is kept out of the app's cache.
 * <p>
 * Use ApproovOkHttp.protect() to add it, or add ApproovOkHttp.networkInterceptor() as the last network
 * interceptor. Note that a WebSocket upgrade never reaches a network interceptor, so it is sent without Approov
 * processing.
 */
public final class ApproovInterceptor implements Interceptor {
    // logging tag of the pinning decision
    private static final String PINNING_TAG = "ApproovPinning";

    // the Approov pins the connection of each attempt is checked against
    private final OkHttpPins pins;

    // only ApproovOkHttp creates one, sharing the pins of every client
    ApproovInterceptor(OkHttpPins pins) {
        this.pins = pins;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        // before initialization and in bypass mode the attempt is sent as it is, with only OS trust applied
        if (!ApproovService.isApproovProtectionEnabled())
            return chain.proceed(chain.request());

        // run the pipeline on the attempt as OkHttp is about to write it, where only an attempt to the app's URL
        // has its query parameters substituted
        OkHttpRequestContext context = OkHttpRequestContext.forNetworkAttempt(chain.request(),
                chain.call().request().url());
        ApproovService.ApproovPreparedRequest prepared = ApproovService.prepareRequest(context);

        // a call cancelled before this check, during the fetch included, is neither signed nor sent, while a cancel
        // that arrives after it is handled by OkHttp as for any call, which on HTTP/2 may follow the signed headers
        // being written
        if (chain.call().isCanceled())
            throw new IOException("Canceled");
        if (prepared.isProcessed())
            prepared.completeProcessing(context);

        // the pins are checked after the fetch, which may have delivered new ones, and before anything is sent,
        // for every attempt the mutator pins, an excluded or unprocessed one included, where the mutator is the
        // one the pipeline used for this attempt; the hook is given the URL as this interceptor was given it,
        // with its placeholders and never a substituted secure string, which has the host and port of the URL
        // sent as the pipeline cannot change them
        Request sent = context.getRequest();
        HttpUrl url = chain.request().url();
        if (shouldPin(prepared, url.toString()))
            pins.check(chain, url.host());

        // OkHttp gives the response the request it wrote, so the response is given back the request this
        // interceptor was given: the sent request, with the token, the signature and the substituted secure
        // strings, stays below here, and an app network interceptor that runs before this one, the app's
        // networkResponse() and priorResponse(), and OkHttp's cache, which records the request headers a
        // response varies on, all see the request with its placeholders
        Response response = chain.proceed(sent).newBuilder().request(chain.request()).build();

        // a response to a substituted query secure string is also kept out of OkHttp's cache, as on Cronet
        return OkHttpCacheBypass.markAttempt(sent, prepared, response);
    }

    /**
     * Determines whether an attempt is pinned, asking the mutator the pipeline used for the attempt. A runtime or
     * Approov exception from the hook is logged naming the hook and thrown as an ApproovException, while any
     * other IOException the hook throws to abort the attempt passes through unchanged.
     *
     * @param prepared is the prepared request of the attempt
     * @param url is the URL of the attempt, with its placeholders
     * @return true if the attempt is pinned, false otherwise
     * @throws IOException if the hook failed or aborted the attempt
     */
    private static boolean shouldPin(ApproovService.ApproovPreparedRequest prepared, String url) throws IOException {
        try {
            return prepared.handlePinningShouldProcessRequest(url);
        } catch (RuntimeException | ApproovException e) {
            // any exception from the app's hook, an Approov one included, is the hook's failure
            String message = "ApproovServiceMutator.handlePinningShouldProcessRequest failed: " + e;
            ApproovLog.e(PINNING_TAG, message);
            throw new ApproovException(message, e);
        }
    }
}
