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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.approov.service.android.ApproovException;
import io.approov.service.android.ApproovRequestContext;
import io.approov.service.android.ApproovService;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.Buffer;

/**
 * OkHttpRequestContext adapts the OkHttp Request of a network attempt to the stack-neutral ApproovRequestContext
 * used by the ApproovService pipeline. As OkHttp requests are immutable, each change rebuilds the request. The
 * context keeps the host and port of the attempt, as OkHttp requires of a network interceptor, and only supports
 * query parameter substitution on an attempt that is the app's own, so the query of a redirect target is left
 * exactly as the server sent it.
 */
final class OkHttpRequestContext implements ApproovRequestContext {
    // the request as currently changed by the pipeline
    private Request request;

    // true if the pipeline may substitute query parameters in the URL
    private final boolean urlMutation;

    private OkHttpRequestContext(Request request, boolean urlMutation) {
        this.request = request;
        this.urlMutation = urlMutation;
    }

    /**
     * Creates the context of a network attempt, where URL mutation is supported only if the attempt is the app's
     * own, as any other URL, such as a redirect target, is the server's. The app's URL is the one
     * ApproovCallInterceptor tagged the request with, after the app's own application interceptors, or the URL
     * of the call's original request if the request has no such tag.
     *
     * @param request is the request of the attempt as OkHttp is about to send it
     * @param callURL is the URL of the call's original request
     * @return the context of the attempt
     */
    static OkHttpRequestContext forNetworkAttempt(Request request, HttpUrl callURL) {
        ApproovAppUrl appURL = request.tag(ApproovAppUrl.class);
        HttpUrl url = (appURL != null) ? appURL.getURL() : callURL;
        boolean appAttempt = isAppAttempt(request.url(), url, ApproovService.getSubstitutionQueryParams());
        return new OkHttpRequestContext(request, appAttempt);
    }

    /**
     * Determines whether a network attempt is the app's own. It is when it has the scheme, host and port of the
     * app's URL and, for each substitution query parameter, the same values in the same order. An app network
     * interceptor that changes the path or adds another query parameter leaves the attempt the app's own, while
     * a redirect target, whose substitution query values are the server's, is not.
     *
     * @param attempt is the URL of the attempt
     * @param app is the app's URL
     * @param queryParams are the substitution query parameters, mapped to their patterns
     * @return true if the attempt is the app's own, false otherwise
     */
    static boolean isAppAttempt(HttpUrl attempt, HttpUrl app, Map<String, Pattern> queryParams) {
        if (!attempt.scheme().equals(app.scheme()) || !attempt.host().equals(app.host())
                || (attempt.port() != app.port()))
            return false;
        String attemptURL = attempt.toString();
        String appURL = app.toString();
        for (Pattern pattern : queryParams.values()) {
            if (!queryValues(attemptURL, pattern).equals(queryValues(appURL, pattern)))
                return false;
        }
        return true;
    }

    // every value of the parameter in the query (after the first '?' and before any '#'), in order, matched as
    // the pipeline matches it
    private static List<String> queryValues(String url, Pattern pattern) {
        List<String> values = new ArrayList<>();
        int hash = url.indexOf('#');
        int end = (hash < 0) ? url.length() : hash;
        int question = url.indexOf('?');
        if ((question < 0) || (question >= end))
            return values;
        Matcher matcher = pattern.matcher(url);
        matcher.region(question, end);
        while (matcher.find())
            values.add(matcher.group(1));
        return values;
    }

    Request getRequest() {
        return request;
    }

    @Override
    public String getMethod() {
        return request.method();
    }

    @Override
    public String getURL() {
        return request.url().toString();
    }

    @Override
    public boolean supportsURLMutation() {
        return urlMutation;
    }

    /**
     * Replaces the request URL. The host and port of the attempt cannot change, as OkHttp requires of a network
     * interceptor. Note that the processed request callback may still change the path and query of an
     * attempt whose query parameters are not substituted, such as a redirect target, as that callback is the
     * app's own code and changes only what the app chooses to.
     *
     * @param url is the new URL
     * @throws ApproovException if the URL is invalid or changes the host or port of the attempt
     */
    @Override
    public void setURL(String url) throws ApproovException {
        Request changed;
        try {
            changed = request.newBuilder().url(url).build();
        } catch (IllegalArgumentException e) {
            // the exception's message is not used as it may quote the URL with the substituted secure strings
            throw new ApproovException("Query parameter substitution: invalid URL");
        }

        // OkHttp fails a network interceptor that changes the host or port with an IllegalStateException, so
        // the change is refused here with the reason
        HttpUrl current = request.url();
        HttpUrl next = changed.url();
        if (!next.host().equals(current.host()) || (next.port() != current.port()))
            throw new ApproovException("Request URL change: a network interceptor cannot change the host or port");
        request = changed;
    }

    @Override
    public String getHeader(String name) {
        return request.header(name);
    }

    @Override
    public List<String> getHeaders(String name) {
        return request.headers(name);
    }

    @Override
    public List<String> getHeaderNames() {
        return new ArrayList<>(request.headers().names());
    }

    @Override
    public void setHeader(String name, String value) {
        request = request.newBuilder().header(name, value).build();
    }

    @Override
    public void removeHeader(String name) {
        request = request.newBuilder().removeHeader(name).build();
    }

    @Override
    public void addHeader(String name, String value) {
        request = request.newBuilder().addHeader(name, value).build();
    }

    @Override
    public boolean hasBody() {
        return request.body() != null;
    }

    @Override
    public byte[] getBodyBytes() {
        RequestBody body = request.body();
        // ignore null bodies and one shot bodies as these cannot be replayed
        if ((body == null) || body.isOneShot())
            return null;
        try {
            // ignore bodies of unknown length as these could be unbounded, but buffer an empty body so that
            // it still gets a body digest
            if (body.contentLength() < 0)
                return null;
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            return buffer.readByteArray();
        } catch (IOException e) {
            // ignore bodies that can't be interrogated
            return null;
        }
    }
}
