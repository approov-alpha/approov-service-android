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

package io.approov.service.android.volley;

import com.android.volley.AuthFailureError;
import com.android.volley.Header;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.toolbox.HttpResponse;
import com.android.volley.toolbox.HurlStack;

import io.approov.service.android.ApproovLog;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

import io.approov.service.android.ApproovException;
import io.approov.service.android.ApproovPinningVerifier;
import io.approov.service.android.ApproovRequestContext;
import io.approov.service.android.ApproovService;

/**
 * ApproovHurlStack is an alternative HurlStack for Volley that routes all requests through the ApproovService
 * request pipeline and pins the connections with the Approov dynamic pins. The pinning reads the live pins, so
 * it reacts immediately to pinning changes.
 */
public class ApproovHurlStack extends HurlStack {
    // logging tag
    private static final String TAG = "ApproovHurlStack";

    // the shared pinning verifier, created lazily and replaced whenever Approov protection is enabled or the
    // pins change, as Android pools connections by an address that includes the HostnameVerifier, so a new
    // instance makes the next request open, and pin, a new connection
    private static ApproovPinningVerifier pinningVerifier = null;

    // true once the stack listens for pins changes
    private static boolean listeningForPinsChanges = false;

    /**
     * Constructs a new HurlStack that adds Approov protection and pinning.
     */
    public ApproovHurlStack() {
        super();
    }

    private static synchronized ApproovPinningVerifier getPinningVerifier() {
        if (!listeningForPinsChanges) {
            listeningForPinsChanges = true;
            ApproovService.registerPinsChangeListener(ApproovHurlStack::pinsChanged);
        }
        if (pinningVerifier == null)
            pinningVerifier = new ApproovPinningVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
        return pinningVerifier;
    }

    // a new verifier is created, so no pooled connection verified earlier is reused
    private static synchronized void pinsChanged() {
        pinningVerifier = null;
    }

    static synchronized void resetForTesting() {
        pinningVerifier = null;
        listeningForPinsChanges = false;
    }

    /**
     * Adds pinning to the connection by overriding the HostnameVerifier with one that pins the connections.
     * Pinning is only applied to https connections, and plain http connections are returned without pinning.
     */
    @Override
    protected HttpURLConnection createConnection(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        if (!(connection instanceof HttpURLConnection))
            // only the host is named as the path, query and user information may carry personal data or a
            // secure string
            throw new IOException("Unexpected connection type for host " + url.getHost() + ": "
                    + connection.getClass().getName());
        HttpURLConnection urlConnection = (HttpURLConnection) connection;

        // apply Approov pinning only to https connections, and only if the mutator asks for it
        boolean pin = false;
        if (urlConnection instanceof HttpsURLConnection) {
            try {
                pin = ApproovService.getServiceMutator().handlePinningShouldProcessRequest(url.toString());
            } catch (RuntimeException | ApproovException e) {
                // any exception from the app's hook, an Approov one included, is the hook's failure and is logged
                // naming the hook
                String message = "ApproovServiceMutator.handlePinningShouldProcessRequest failed: " + e;
                ApproovLog.e(TAG, message);
                throw new ApproovException(message, e);
            }
        }
        if (pin)
            ((HttpsURLConnection) urlConnection).setHostnameVerifier(getPinningVerifier());

        // we follow redirects ourselves, as the platform would carry every Approov header and substituted secret
        // to another origin
        urlConnection.setInstanceFollowRedirects(false);

        return urlConnection;
    }

    /**
     * Routes the request through the ApproovService pipeline and sends it. The request headers and body are
     * read from the Volley Request once and the request is sent from that capture, so the message signature
     * and body digest cover exactly what is sent, even if getHeaders() or getBody() produce a new value on
     * every call. The body content type is added as a header before signing, so it is covered.
     * <p>
     * Redirects are followed here as HttpURLConnection would follow them when
     * HttpURLConnection.getFollowRedirects() is true: 300 to 303 for any method, 307 and 308 for GET and HEAD,
     * a method that permits a body becoming GET without one, never to another scheme, and at most 20
     * follow-ups. Each hop drops Authorization on an origin change and has its Approov protection evaluated
     * again, so the Approov headers are stripped, each substituted secure string is restored to its
     * placeholder, and the hop is protected afresh if Approov protects it.
     */
    @Override
    public HttpResponse executeRequest(Request<?> request, Map<String, String> additionalHeaders)
            throws IOException, AuthFailureError {
        CapturedRequest captured = CapturedRequest.capture(request, additionalHeaders);

        // run the Approov request pipeline on the captured request
        VolleyRequestContext context = new VolleyRequestContext(captured, captured.headers, captured.headers);
        ApproovService.ApproovPreparedRequest prepared = ApproovService.prepareRequest(context);
        prepared.completeProcessing(context);

        // send the request, following any redirects as the platform would
        boolean followRedirects = HttpURLConnection.getFollowRedirects();
        int followUps = 0;
        while (true) {
            HttpResponse response = super.executeRequest(captured, Collections.<String, String>emptyMap());
            if (!followRedirects || !isFollowedRedirect(response.getStatusCode(), captured.methodName))
                return response;
            String location = header(response, "Location");
            if (location == null)
                return response;
            URL current = new URL(captured.getUrl());
            URL next;
            try {
                next = new URL(current, location);
            } catch (MalformedURLException e) {
                return response;
            }
            // as on the platform, never follow to another scheme
            if (!next.getProtocol().equalsIgnoreCase(current.getProtocol()))
                return response;
            if (++followUps > MAX_FOLLOW_UPS) {
                close(response);
                throw new ProtocolException("Too many follow-up requests: " + followUps);
            }
            close(response);

            // protect the hop afresh for its destination
            CapturedRequest hop = captured.redirect(next, sameOrigin(current, next));
            VolleyRequestContext hopContext = new VolleyRequestContext(hop, hop.headers, hop.headers);
            prepared.refreshProtectionForAttempt(new RedirectContext(hopContext, hop));
            captured = hop;
        }
    }

    // the maximum number of redirects followed, as on the platform
    private static final int MAX_FOLLOW_UPS = 20;

    private static boolean isFollowedRedirect(int code, String method) {
        switch (code) {
            case 300:
            case HttpURLConnection.HTTP_MOVED_PERM:
            case HttpURLConnection.HTTP_MOVED_TEMP:
            case HttpURLConnection.HTTP_SEE_OTHER:
                return true;
            case 307:
            case 308:
                return "GET".equals(method) || "HEAD".equals(method);
            default:
                return false;
        }
    }

    // as Android's HttpMethod.permitsRequestBody does
    private static boolean permitsRequestBody(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                || "OPTIONS".equals(method) || "DELETE".equals(method);
    }

    // same scheme, host and port, with default ports made explicit
    private static boolean sameOrigin(URL a, URL b) {
        return a.getProtocol().equalsIgnoreCase(b.getProtocol())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && (effectivePort(a) == effectivePort(b));
    }

    private static int effectivePort(URL url) {
        return (url.getPort() != -1) ? url.getPort() : url.getDefaultPort();
    }

    private static String header(HttpResponse response, String name) {
        List<Header> headers = response.getHeaders();
        if (headers == null)
            return null;
        for (Header header : headers) {
            if (name.equalsIgnoreCase(header.getName()))
                return header.getValue();
        }
        return null;
    }

    // releases a redirect response that is not delivered to Volley
    private static void close(HttpResponse response) {
        InputStream content = response.getContent();
        if (content == null)
            return;
        try {
            content.close();
        } catch (IOException e) {
            // nothing to release
        }
    }

    /**
     * RedirectContext is the request context of a redirect hop, which reads and changes the URL of the
     * captured hop so that it may be restored.
     */
    private static final class RedirectContext implements ApproovRequestContext {
        // the context for the headers of the hop
        private final VolleyRequestContext delegate;
        // the captured hop
        private final CapturedRequest hop;

        RedirectContext(VolleyRequestContext delegate, CapturedRequest hop) {
            this.delegate = delegate;
            this.hop = hop;
        }

        @Override public String getMethod() { return delegate.getMethod(); }
        @Override public String getURL() { return hop.getUrl(); }
        @Override public boolean supportsURLMutation() { return true; }
        @Override public void setURL(String url) { hop.setCapturedUrl(url); }
        @Override public String getHeader(String name) { return delegate.getHeader(name); }
        @Override public List<String> getHeaders(String name) { return delegate.getHeaders(name); }
        @Override public List<String> getHeaderNames() { return new ArrayList<>(hop.headers.keySet()); }
        @Override public void setHeader(String name, String value) { delegate.setHeader(name, value); }
        @Override public void removeHeader(String name) { delegate.removeHeader(name); }
        @Override public boolean supportsHeaderSubstitution() { return delegate.supportsHeaderSubstitution(); }
        @Override public boolean hasBody() { return false; }
        @Override public byte[] getBodyBytes() { return null; }
    }

    /**
     * CapturedRequest is a Volley Request captured once, with its method, URL, merged headers (the stack's
     * additional headers overridden by the request headers, as HurlStack merges them), body and body content
     * type. HurlStack sends it as captured, so the app's getHeaders() and getBody() run exactly once per
     * request.
     */
    static final class CapturedRequest extends Request<Object> {
        // the HTTP method name
        final String methodName;
        // the merged headers, which the pipeline may change
        final Map<String, String> headers;
        // the body, or null if there is none
        private final byte[] body;
        // the content type of the body, or null if there is none
        private final String bodyContentType;
        // the URL, which the pipeline may change
        private String url;

        private CapturedRequest(Request<?> original, String methodName, String url,
                Map<String, String> headers, byte[] body, String bodyContentType) {
            super(volleyMethod(methodName), url, null);
            this.methodName = methodName;
            this.url = url;
            this.headers = headers;
            this.body = body;
            this.bodyContentType = bodyContentType;
            // HurlStack reads the timeout from the request's retry policy
            setRetryPolicy(original.getRetryPolicy());
            setShouldCache(false);
        }

        // reads the headers and body of the Volley Request once
        static CapturedRequest capture(Request<?> request, Map<String, String> additionalHeaders)
                throws AuthFailureError {
            // merge the headers as HurlStack does, with the request headers overriding the additional ones
            Map<String, String> headers = new LinkedHashMap<>();
            if (additionalHeaders != null)
                headers.putAll(additionalHeaders);
            Map<String, String> requestHeaders = request.getHeaders();
            if (requestHeaders != null)
                headers.putAll(requestHeaders);
            // read the body once, where a deprecated GET or POST request is a POST if it has a body
            byte[] body;
            String methodName;
            if (request.getMethod() == Request.Method.DEPRECATED_GET_OR_POST) {
                @SuppressWarnings("deprecation")
                byte[] postBody = request.getPostBody();
                body = postBody;
                methodName = (body != null) ? "POST" : "GET";
            } else {
                body = request.getBody();
                methodName = methodName(request.getMethod());
            }
            String contentType = null;
            if (body != null) {
                @SuppressWarnings("deprecation")
                String type = (request.getMethod() == Request.Method.DEPRECATED_GET_OR_POST)
                        ? request.getPostBodyContentType() : request.getBodyContentType();
                contentType = type;
                // the stack would add the content type after signing, so we add it here so that it is covered
                if ((contentType != null) && (findKey(headers, "Content-Type") == null))
                    headers.put("Content-Type", contentType);
            }
            return new CapturedRequest(request, methodName, request.getUrl(), headers, body, contentType);
        }

        // starts from this request's headers, without a body if the method changes
        CapturedRequest redirect(URL next, boolean sameOrigin) {
            Map<String, String> hopHeaders = new LinkedHashMap<>(headers);
            String hopMethod = methodName;
            byte[] hopBody = body;
            String hopContentType = bodyContentType;

            // a method that permits a body becomes GET without one
            if (permitsRequestBody(methodName)) {
                hopMethod = "GET";
                hopBody = null;
                hopContentType = null;
                removeKey(hopHeaders, "Transfer-Encoding");
                removeKey(hopHeaders, "Content-Length");
                removeKey(hopHeaders, "Content-Type");
            }
            // credentials the app addressed to one origin are not sent to another, while the pipeline strips
            // what it added and restores the app's placeholders when it protects the hop afresh
            if (!sameOrigin)
                removeKey(hopHeaders, "Authorization");
            return new CapturedRequest(this, hopMethod, next.toString(), hopHeaders, hopBody, hopContentType);
        }

        void setCapturedUrl(String url) {
            this.url = url;
        }

        @Override
        public String getUrl() {
            return url;
        }

        @Override
        public Map<String, String> getHeaders() {
            return headers;
        }

        @Override
        public byte[] getBody() {
            return body;
        }

        @Override
        public String getBodyContentType() {
            return (bodyContentType != null) ? bodyContentType : super.getBodyContentType();
        }

        @Override
        protected Response<Object> parseNetworkResponse(NetworkResponse response) {
            throw new UnsupportedOperationException("a captured request is only sent by the stack");
        }

        @Override
        protected void deliverResponse(Object response) {
            throw new UnsupportedOperationException("a captured request is only sent by the stack");
        }

        private static String findKey(Map<String, String> headers, String name) {
            for (String key : headers.keySet()) {
                if ((key != null) && key.equalsIgnoreCase(name))
                    return key;
            }
            return null;
        }

        private static void removeKey(Map<String, String> headers, String name) {
            String key;
            while ((key = findKey(headers, name)) != null)
                headers.remove(key);
        }

        private static String methodName(int method) {
            switch (method) {
                case Request.Method.POST:
                    return "POST";
                case Request.Method.PUT:
                    return "PUT";
                case Request.Method.DELETE:
                    return "DELETE";
                case Request.Method.HEAD:
                    return "HEAD";
                case Request.Method.OPTIONS:
                    return "OPTIONS";
                case Request.Method.TRACE:
                    return "TRACE";
                case Request.Method.PATCH:
                    return "PATCH";
                default:
                    return "GET";
            }
        }

        private static int volleyMethod(String name) {
            switch (name) {
                case "POST":
                    return Request.Method.POST;
                case "PUT":
                    return Request.Method.PUT;
                case "DELETE":
                    return Request.Method.DELETE;
                case "HEAD":
                    return Request.Method.HEAD;
                case "OPTIONS":
                    return Request.Method.OPTIONS;
                case "TRACE":
                    return Request.Method.TRACE;
                case "PATCH":
                    return Request.Method.PATCH;
                default:
                    return Request.Method.GET;
            }
        }
    }

    /**
     * VolleyRequestContext adapts a Volley Request to the stack-neutral ApproovRequestContext used by the
     * ApproovService pipeline. Header reads consult the request headers first, matching the precedence of the
     * underlying stack, and then the additional headers, while header writes go to the additional headers
     * that are passed on to the stack. The headers and URL of a captured request can be changed, so the
     * pipeline substitutes placeholders in them, but those of any other Volley Request cannot.
     */
    static final class VolleyRequestContext implements ApproovRequestContext {
        // the Volley Request
        private final Request<?> request;
        // the request headers, consulted first
        private final Map<String, String> requestHeaders;
        // the additional headers passed on to the stack, which header writes change
        private final Map<String, String> additionalHeaders;

        VolleyRequestContext(Request<?> request, Map<String, String> requestHeaders,
                             Map<String, String> additionalHeaders) {
            this.request = request;
            this.requestHeaders = requestHeaders;
            this.additionalHeaders = additionalHeaders;
        }

        @Override
        public String getMethod() {
            switch (request.getMethod()) {
                case Request.Method.GET:
                    return "GET";
                case Request.Method.POST:
                    return "POST";
                case Request.Method.PUT:
                    return "PUT";
                case Request.Method.DELETE:
                    return "DELETE";
                case Request.Method.HEAD:
                    return "HEAD";
                case Request.Method.OPTIONS:
                    return "OPTIONS";
                case Request.Method.TRACE:
                    return "TRACE";
                case Request.Method.PATCH:
                    return "PATCH";
                default:
                    // DEPRECATED_GET_OR_POST depends on whether a body is present
                    return hasBody() ? "POST" : "GET";
            }
        }

        @Override
        public String getURL() {
            return request.getUrl();
        }

        @Override
        public boolean supportsURLMutation() {
            // the stack sends the captured request, whose URL the pipeline may change
            return request instanceof CapturedRequest;
        }

        @Override
        public boolean supportsHeaderSubstitution() {
            // the stack sends the captured request's headers, so the pipeline can substitute placeholders in
            // them, while another Request's getHeaders() overrides any header the stack sets
            return request instanceof CapturedRequest;
        }

        @Override
        public void setURL(String url) throws ApproovException {
            if (!(request instanceof CapturedRequest))
                throw new ApproovException("URL mutation is not supported for this Volley request");
            ((CapturedRequest) request).setCapturedUrl(url);
        }

        @Override
        public String getHeader(String name) {
            String value = getIgnoreCase(requestHeaders, name);
            if (value == null)
                value = getIgnoreCase(additionalHeaders, name);
            return value;
        }

        @Override
        public List<String> getHeaders(String name) {
            String value = getHeader(name);
            if (value == null)
                return Collections.emptyList();
            List<String> values = new ArrayList<>(1);
            values.add(value);
            return values;
        }

        @Override
        public void setHeader(String name, String value) {
            // replace the header whatever the case of its name, keeping the app's spelling, so a substituted
            // value is never sent beside the placeholder
            String key = null;
            Iterator<String> keys = additionalHeaders.keySet().iterator();
            while (keys.hasNext()) {
                String existing = keys.next();
                if ((existing == null) || !existing.equalsIgnoreCase(name))
                    continue;
                if (key == null)
                    key = existing;
                else
                    keys.remove();
            }
            additionalHeaders.put((key != null) ? key : name, value);
        }

        @Override
        public void removeHeader(String name) {
            Iterator<String> keys = additionalHeaders.keySet().iterator();
            while (keys.hasNext()) {
                String key = keys.next();
                if ((key != null) && key.equalsIgnoreCase(name))
                    keys.remove();
            }
        }

        @Override
        public boolean hasBody() {
            try {
                return request.getBody() != null;
            } catch (AuthFailureError e) {
                // the body is unavailable as auth details could not be supplied, which is logged as a message
                // signature then lacks the body
                ApproovLog.w(TAG, "Unable to read Volley request body for hasBody(): " + e.getMessage());
                return false;
            }
        }

        @Override
        public byte[] getBodyBytes() {
            try {
                return request.getBody();
            } catch (AuthFailureError e) {
                // the body is unavailable as auth details could not be supplied, which is logged as a message
                // signature then lacks the body
                ApproovLog.w(TAG, "Unable to read Volley request body for signing: " + e.getMessage());
                return null;
            }
        }

        private static String getIgnoreCase(Map<String, String> map, String name) {
            String direct = map.get(name);
            if (direct != null)
                return direct;
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if ((entry.getKey() != null) && entry.getKey().equalsIgnoreCase(name))
                    return entry.getValue();
            }
            return null;
        }
    }
}
