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

package io.approov.service.android.httpsurlconn;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketPermission;
import java.net.URL;
import java.security.Permission;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;

import io.approov.service.android.ApproovException;
import io.approov.service.android.ApproovPinningVerifier;
import io.approov.service.android.ApproovRequestContext;
import io.approov.service.android.ApproovService;

/**
 * ApproovBufferedHttpsURLConnection is the HttpsURLConnection returned by addApproovToConnection. It
 * buffers the request body so that message signing runs just before the request is sent and can
 * include a body digest, applies any query parameter substitution to the URL, and follows redirects
 * itself, protecting each hop afresh.
 * <p>
 * Note that the body is not buffered if the app sets a streaming mode with setFixedLengthStreamingMode
 * or setChunkedStreamingMode, so it is then streamed to the network and cannot be included in a body
 * digest.
 */
final class ApproovBufferedHttpsURLConnection extends HttpsURLConnection implements ApproovProtectedConnection {
    // the connection the app passed in, which is used for the request unless the URL is substituted
    private final HttpsURLConnection originalRequest;

    // the URL as the app made it, which getURL reports until a redirect is followed, never the URL with
    // substituted secure strings
    private final URL appURL;

    // the proxy the app opened the connection with, if known, for reopened and redirected connections
    private Proxy proxy;

    // the URL the request is sent to, after any query parameter substitution
    private URL configuredUrl;

    // the request settings, held here until the request is sent
    private String requestMethod;
    private int connectTimeout;
    private int readTimeout;
    private boolean doInput;
    private boolean doOutput;
    private boolean allowUserInteraction;
    private boolean useCaches;
    private boolean defaultUseCaches;
    private long ifModifiedSince;
    private boolean instanceFollowRedirects;

    // the streaming mode the app set, or -1 if not set
    private int fixedLengthStreamingMode = -1;
    private long fixedLengthStreamingModeLong = -1L;
    private int chunkLength = -1;

    // the socket factory and hostname verifier for the request
    private SSLSocketFactory sslSocketFactory;
    private HostnameVerifier hostnameVerifier;

    // the request headers, held here until the request is sent
    private Map<String, List<String>> requestProperties;

    // the buffered request body and the stream the app writes it to, or null if no body has been written
    private ByteArrayOutputStream requestBodyBuffer;
    private OutputStream bufferedOutputStream;

    // the stream the body is written to directly in a streaming mode, once obtained
    private OutputStream streamingOutputStream;

    // true once the response has been asked for, after which the body can no longer be written
    private boolean responseRequested;

    // the connection the request is sent on, or null until it is created
    private HttpsURLConnection networkRequest;

    // true once the processed-request callback has run
    private boolean processedRequestApplied;

    // true once the request body has been sent
    private boolean requestBodyTransmitted;

    // the maximum number of follow-up requests for redirects, as on the platform
    private static final int MAX_FOLLOW_UPS = 20;

    // true once the redirects of the request have been followed, or need not be
    private boolean redirectsFollowed;

    // the URL, method and headers of the request currently held by networkRequest
    private URL networkURL;
    private String networkMethod;
    private Map<String, List<String>> networkHeaders;

    // the URL of the last redirect followed, as the server gave it, or null
    private volatile URL redirectedURL;

    // the prepared request whose completion, including message signing, is deferred until the request is sent
    private ApproovService.ApproovPreparedRequest prepared;

    // the request context through which the pipeline mutates this connection
    private final BufferedRequestContext context = new BufferedRequestContext();

    // call prepareApproov() afterwards to run the Approov request pipeline
    ApproovBufferedHttpsURLConnection(HttpsURLConnection request) {
        super(request.getURL());
        this.originalRequest = request;
        this.appURL = request.getURL();
        this.configuredUrl = request.getURL();
        this.requestMethod = request.getRequestMethod();
        this.connectTimeout = request.getConnectTimeout();
        this.readTimeout = request.getReadTimeout();
        this.doInput = request.getDoInput();
        this.doOutput = request.getDoOutput();
        this.allowUserInteraction = request.getAllowUserInteraction();
        this.useCaches = request.getUseCaches();
        this.defaultUseCaches = request.getDefaultUseCaches();
        this.ifModifiedSince = request.getIfModifiedSince();
        this.instanceFollowRedirects = request.getInstanceFollowRedirects();
        this.sslSocketFactory = request.getSSLSocketFactory();
        this.hostnameVerifier = request.getHostnameVerifier();
        this.requestProperties = copyRequestProperties(request.getRequestProperties());
    }

    // call prepareApproov() afterwards; appHeaders are the headers the app set on the request, which restore any
    // the platform does not report back, as the JDK does for Authorization and Proxy-Authorization
    ApproovBufferedHttpsURLConnection(HttpsURLConnection request, Map<String, List<String>> appHeaders) {
        this(request);
        for (Map.Entry<String, List<String>> entry : appHeaders.entrySet()) {
            if (findHeaderKey(entry.getKey()) == null)
                requestProperties.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
    }

    // so a connection reopened for a substituted URL or a redirect uses it too
    void setProxy(Proxy proxy) {
        this.proxy = proxy;
    }

    private java.net.URLConnection openRaw(URL url) throws IOException {
        return (proxy != null) ? ApproovUrlConnection.openRawConnection(url, proxy)
                : ApproovUrlConnection.openRawConnection(url);
    }

    // the processed-request callback is deferred until sending, so the buffered body can be signed
    void prepareApproov() throws IOException {
        prepared = ApproovService.prepareRequest(context);
    }

    // a copy, or null if no body has been written
    byte[] getBufferedRequestBody() {
        if (requestBodyBuffer == null) {
            return null;
        }
        return requestBodyBuffer.toByteArray();
    }

    private static Map<String, List<String>> copyRequestProperties(Map<String, List<String>> headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (headers == null) {
            return copy;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            List<String> values = entry.getValue();
            if (values == null) {
                copy.put(entry.getKey(), new ArrayList<>());
            } else {
                copy.put(entry.getKey(), new ArrayList<>(values));
            }
        }
        return copy;
    }

    // as URLConnection; timeouts, redirects, socket factory and hostname verifier are accepted at any time
    private void ensureNotConnected() {
        if (connected)
            throw new IllegalStateException("Already connected");
    }

    private void setHeaderValue(String key, String value) {
        List<String> values = new ArrayList<>(1);
        values.add(value);
        String existingKey = findHeaderKey(key);
        requestProperties.put(existingKey != null ? existingKey : key, values);
    }

    private void addHeaderValue(String key, String value) {
        String existingKey = findHeaderKey(key);
        String storedKey = existingKey != null ? existingKey : key;
        List<String> values = requestProperties.get(storedKey);
        if (values == null) {
            values = new ArrayList<>();
            requestProperties.put(storedKey, values);
        }
        values.add(value);
    }

    private String findHeaderKey(String key) {
        for (String candidate : requestProperties.keySet()) {
            if (candidate.equalsIgnoreCase(key)) {
                return candidate;
            }
        }
        return null;
    }

    // a GET with a body is sent as a POST by HttpURLConnection
    private String effectiveMethod() {
        if ("GET".equals(requestMethod) && (doOutput || (requestBodyBuffer != null)))
            return "POST";
        return requestMethod;
    }

    private synchronized void applyProcessedRequestIfNeeded() throws IOException {
        if (processedRequestApplied) {
            return;
        }
        // a buffered body has its final length now, so we set the Content-Length here for signing to cover it
        if (!isStreamingModeSet() && (doOutput || (requestBodyBuffer != null))) {
            int length = (requestBodyBuffer != null) ? requestBodyBuffer.size() : 0;
            setHeaderValue("Content-Length", Integer.toString(length));
        }
        if (prepared != null) {
            // refresh the protection if it went stale while the app held the connection, otherwise just
            // complete the processing
            if (!prepared.refreshProtectionIfStale(context))
                prepared.completeProcessing(context);
        }
        processedRequestApplied = true;
    }

    private synchronized HttpsURLConnection createNetworkRequestIfNeeded() throws IOException {
        if (networkRequest != null) {
            return networkRequest;
        }

        HttpsURLConnection targetRequest;
        if (configuredUrl.toString().equals(originalRequest.getURL().toString())) {
            targetRequest = originalRequest;
        } else {
            java.net.URLConnection reopened = openRaw(configuredUrl);
            if (!(reopened instanceof HttpsURLConnection)) {
                // never the URL, as it holds the substituted secure strings
                throw new IOException("Substituted URL is not https");
            }
            targetRequest = (HttpsURLConnection) reopened;
        }

        targetRequest.setConnectTimeout(connectTimeout);
        targetRequest.setReadTimeout(readTimeout);
        targetRequest.setDoInput(doInput);
        targetRequest.setDoOutput(doOutput);
        targetRequest.setAllowUserInteraction(allowUserInteraction);
        targetRequest.setUseCaches(useCaches);
        targetRequest.setDefaultUseCaches(defaultUseCaches);
        targetRequest.setIfModifiedSince(ifModifiedSince);
        // we follow redirects ourselves in followRedirects
        targetRequest.setInstanceFollowRedirects(false);
        if (sslSocketFactory != null) {
            targetRequest.setSSLSocketFactory(sslSocketFactory);
        }
        if (hostnameVerifier != null) {
            targetRequest.setHostnameVerifier(hostnameVerifier);
        }
        try {
            targetRequest.setRequestMethod(effectiveMethod());
        } catch (ProtocolException e) {
            throw new IOException("Failed to set request method", e);
        }
        if (fixedLengthStreamingModeLong >= 0) {
            targetRequest.setFixedLengthStreamingMode(fixedLengthStreamingModeLong);
        } else if (fixedLengthStreamingMode >= 0) {
            targetRequest.setFixedLengthStreamingMode(fixedLengthStreamingMode);
        } else if (chunkLength >= 0) {
            targetRequest.setChunkedStreamingMode(chunkLength);
        }
        for (Map.Entry<String, List<String>> entry : requestProperties.entrySet()) {
            List<String> values = entry.getValue();
            if ((values == null) || values.isEmpty()) {
                continue;
            }
            targetRequest.setRequestProperty(entry.getKey(), values.get(0));
            for (int i = 1; i < values.size(); i++) {
                targetRequest.addRequestProperty(entry.getKey(), values.get(i));
            }
        }

        networkRequest = targetRequest;
        networkURL = configuredUrl;
        networkMethod = context.getMethod();
        networkHeaders = copyRequestProperties(requestProperties);
        return networkRequest;
    }

    private void ensureRequestReadyForNetwork() throws IOException {
        applyProcessedRequestIfNeeded();
        createNetworkRequestIfNeeded();
    }

    private synchronized void ensureRequestBodyTransmitted() throws IOException {
        ensureRequestReadyForNetwork();
        if (requestBodyTransmitted) {
            return;
        }

        if (doOutput || (requestBodyBuffer != null)) {
            OutputStream outputStream = networkRequest.getOutputStream();
            if (requestBodyBuffer != null) {
                requestBodyBuffer.writeTo(outputStream);
            }
            outputStream.close();
        }

        requestBodyTransmitted = true;
        connected = true;
    }

    private synchronized void ensureResponseReady() throws IOException {
        responseRequested = true;
        ensureRequestBodyTransmitted();
        if (!redirectsFollowed) {
            followRedirects();
            redirectsFollowed = true;
        }
    }

    // as the platform would; each hop drops Authorization on an origin change and is protected afresh
    private void followRedirects() throws IOException {
        if (!instanceFollowRedirects)
            return;
        int followUps = 0;
        while (true) {
            int code = networkRequest.getResponseCode();
            if (!isFollowedRedirect(code, networkMethod))
                return;
            String location = networkRequest.getHeaderField("Location");
            if (location == null)
                return;
            URL next;
            try {
                next = new URL(networkURL, location);
            } catch (MalformedURLException e) {
                return;
            }
            // as on the platform, never from https to http
            if (!next.getProtocol().equalsIgnoreCase(networkURL.getProtocol()))
                return;
            if (++followUps > MAX_FOLLOW_UPS)
                throw new ProtocolException("Too many follow-up requests: " + followUps);

            String method = networkMethod;
            Map<String, List<String>> headers = copyRequestProperties(networkHeaders);
            if (permitsRequestBody(method)) {
                // redirects carry no request body
                if (!"PROPFIND".equals(method))
                    method = "GET";
                removeHeader(headers, "Transfer-Encoding");
                removeHeader(headers, "Content-Length");
                removeHeader(headers, "Content-Type");
            }
            // credentials the app addressed to one origin are not sent to another
            if (!sameOrigin(networkURL, next))
                removeHeader(headers, "Authorization");
            discardResponse(networkRequest);

            // protect the hop afresh for its own URL
            RedirectHopContext hop = new RedirectHopContext(next.toString(), method, headers);
            if (prepared != null)
                prepared.refreshProtectionForAttempt(hop);
            URL hopURL;
            try {
                hopURL = new URL(hop.url);
            } catch (MalformedURLException e) {
                // never the exception's message, as it may quote a substituted secret
                throw new ApproovException("Malformed redirect URL after Approov protection");
            }
            networkRequest = openRedirectHop(hopURL, method, headers);
            networkURL = hopURL;
            networkMethod = method;
            networkHeaders = headers;
            redirectedURL = next;
        }
    }

    // as on the platform
    private static boolean isFollowedRedirect(int code, String method) {
        switch (code) {
            case 300:
            case HTTP_MOVED_PERM:
            case HTTP_MOVED_TEMP:
            case HTTP_SEE_OTHER:
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
                || "PROPPATCH".equals(method) || "REPORT".equals(method) || "OPTIONS".equals(method)
                || "DELETE".equals(method) || "PROPFIND".equals(method) || "MKCOL".equals(method)
                || "LOCK".equals(method);
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

    private static void removeHeader(Map<String, List<String>> headers, String name) {
        java.util.Iterator<String> names = headers.keySet().iterator();
        while (names.hasNext()) {
            if (names.next().equalsIgnoreCase(name))
                names.remove();
        }
    }

    // releases a redirect response that is not delivered to the app
    private static void discardResponse(HttpsURLConnection connection) {
        try {
            InputStream body = connection.getInputStream();
            if (body != null)
                body.close();
        } catch (IOException e) {
            // nothing to release
        }
    }

    // the hop carries no body and is pinned if the service mutator pins its URL
    private HttpsURLConnection openRedirectHop(URL url, String method, Map<String, List<String>> headers)
            throws IOException {
        java.net.URLConnection opened = openRaw(url);
        if (!(opened instanceof HttpsURLConnection))
            throw new IOException("Redirect target is not https");
        HttpsURLConnection hop = (HttpsURLConnection) opened;
        hop.setConnectTimeout(connectTimeout);
        hop.setReadTimeout(readTimeout);
        hop.setDoInput(doInput);
        hop.setAllowUserInteraction(allowUserInteraction);
        hop.setUseCaches(useCaches);
        hop.setDefaultUseCaches(defaultUseCaches);
        hop.setIfModifiedSince(ifModifiedSince);
        hop.setInstanceFollowRedirects(false);
        if (sslSocketFactory != null)
            hop.setSSLSocketFactory(sslSocketFactory);
        HostnameVerifier verifier = (hostnameVerifier != null) ? hostnameVerifier : hop.getHostnameVerifier();
        if (!(verifier instanceof ApproovPinningVerifier) && ApproovUrlConnection.shouldPin(url))
            verifier = new ApproovPinningVerifier(verifier);
        hop.setHostnameVerifier(verifier);
        hop.setRequestMethod(method);
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            List<String> values = entry.getValue();
            if ((values == null) || values.isEmpty())
                continue;
            hop.setRequestProperty(entry.getKey(), values.get(0));
            for (int i = 1; i < values.size(); i++)
                hop.addRequestProperty(entry.getKey(), values.get(i));
        }
        return hop;
    }

    /**
     * RedirectHopContext is the request context of a redirect hop, holding its URL, method and headers
     * before the connection is opened. The hop carries no body.
     */
    private static final class RedirectHopContext implements ApproovRequestContext {
        // the URL of the hop, which the pipeline may substitute
        private String url;

        // the request method of the hop
        private final String method;

        // the request headers of the hop
        private final Map<String, List<String>> headers;

        RedirectHopContext(String url, String method, Map<String, List<String>> headers) {
            this.url = url;
            this.method = method;
            this.headers = headers;
        }

        @Override public String getMethod() { return method; }
        @Override public String getURL() { return url; }
        @Override public boolean supportsURLMutation() { return true; }
        @Override public void setURL(String url) { this.url = url; }
        @Override public boolean hasBody() { return false; }
        @Override public byte[] getBodyBytes() { return null; }

        @Override
        public String getHeader(String name) {
            List<String> values = getHeaders(name);
            return values.isEmpty() ? null : values.get(values.size() - 1);
        }

        @Override
        public List<String> getHeaders(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name))
                    return (entry.getValue() != null) ? entry.getValue() : Collections.<String>emptyList();
            }
            return Collections.emptyList();
        }

        @Override
        public List<String> getHeaderNames() {
            return new ArrayList<>(headers.keySet());
        }

        @Override
        public void setHeader(String name, String value) {
            removeHeader(name);
            List<String> values = new ArrayList<>(1);
            values.add(value);
            headers.put(name, values);
        }

        @Override
        public void addHeader(String name, String value) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    List<String> values = new ArrayList<>(entry.getValue());
                    values.add(value);
                    entry.setValue(values);
                    return;
                }
            }
            setHeader(name, value);
        }

        @Override
        public void removeHeader(String name) {
            ApproovBufferedHttpsURLConnection.removeHeader(headers, name);
        }
    }

    // a streaming mode means the body is not buffered in memory
    private boolean isStreamingModeSet() {
        return (fixedLengthStreamingModeLong >= 0) || (fixedLengthStreamingMode >= 0) || (chunkLength >= 0);
    }

    // as the platform does for the TLS accessors, which throw before the connection is made and send nothing
    private void ensureEstablished() {
        if (!connected)
            throw new IllegalStateException("Connection has not yet been established");
    }

    @Override
    public String getCipherSuite() {
        ensureEstablished();
        try {
            ensureResponseReady();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare network request", e);
        }
        return networkRequest.getCipherSuite();
    }

    @Override
    public Certificate[] getLocalCertificates() {
        ensureEstablished();
        try {
            ensureResponseReady();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare network request", e);
        }
        return networkRequest.getLocalCertificates();
    }

    @Override
    public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
        ensureEstablished();
        try {
            ensureResponseReady();
        } catch (IOException e) {
            SSLPeerUnverifiedException exception =
                    new SSLPeerUnverifiedException("Failed to prepare network request");
            exception.initCause(e);
            throw exception;
        }
        return networkRequest.getServerCertificates();
    }

    @Override
    public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
        ensureEstablished();
        try {
            ensureResponseReady();
        } catch (IOException e) {
            SSLPeerUnverifiedException exception =
                    new SSLPeerUnverifiedException("Failed to prepare network request");
            exception.initCause(e);
            throw exception;
        }
        return networkRequest.getPeerPrincipal();
    }

    @Override
    public Principal getLocalPrincipal() {
        ensureEstablished();
        try {
            ensureResponseReady();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare network request", e);
        }
        return networkRequest.getLocalPrincipal();
    }

    @Override
    public void disconnect() {
        if (networkRequest != null) {
            networkRequest.disconnect();
        } else {
            originalRequest.disconnect();
        }
    }

    @Override
    public boolean usingProxy() {
        if (networkRequest != null) {
            return networkRequest.usingProxy();
        }
        return originalRequest.usingProxy();
    }

    /**
     * Connects. A request that may still carry a body is not sent yet, as the app may write the body
     * afterwards, and it is signed and sent when the response is asked for. A request without a body
     * is protected and sent now.
     */
    @Override
    public synchronized void connect() throws IOException {
        if (doOutput || (requestBodyBuffer != null)) {
            connected = true;
            return;
        }
        ensureResponseReady();
        networkRequest.connect();
        connected = true;
    }

    /**
     * Gets the URL of the request as the app made it, never with the secure strings substituted into
     * it, or the URL of the last redirect followed, as the platform reports it.
     */
    @Override
    public URL getURL() {
        URL redirected = redirectedURL;
        return (redirected != null) ? redirected : appURL;
    }

    @Override
    public void setConnectTimeout(int timeout) {
        this.connectTimeout = timeout;
    }

    @Override
    public int getConnectTimeout() {
        return connectTimeout;
    }

    @Override
    public synchronized void setReadTimeout(int timeout) {
        this.readTimeout = timeout;
        // a read timeout still applies to a connection in use
        if (networkRequest != null)
            networkRequest.setReadTimeout(timeout);
    }

    @Override
    public int getReadTimeout() {
        return readTimeout;
    }

    @Override
    public void setDoInput(boolean doinput) {
        ensureNotConnected();
        this.doInput = doinput;
    }

    @Override
    public boolean getDoInput() {
        return doInput;
    }

    @Override
    public void setDoOutput(boolean dooutput) {
        ensureNotConnected();
        this.doOutput = dooutput;
    }

    @Override
    public boolean getDoOutput() {
        return doOutput;
    }

    @Override
    public void setAllowUserInteraction(boolean allowuserinteraction) {
        ensureNotConnected();
        this.allowUserInteraction = allowuserinteraction;
    }

    @Override
    public boolean getAllowUserInteraction() {
        return allowUserInteraction;
    }

    @Override
    public void setUseCaches(boolean usecaches) {
        ensureNotConnected();
        this.useCaches = usecaches;
    }

    @Override
    public boolean getUseCaches() {
        return useCaches;
    }

    @Override
    public void setDefaultUseCaches(boolean defaultusecaches) {
        this.defaultUseCaches = defaultusecaches;
    }

    @Override
    public boolean getDefaultUseCaches() {
        return defaultUseCaches;
    }

    @Override
    public void setIfModifiedSince(long ifmodifiedsince) {
        ensureNotConnected();
        this.ifModifiedSince = ifmodifiedsince;
    }

    @Override
    public long getIfModifiedSince() {
        return ifModifiedSince;
    }

    @Override
    public void setInstanceFollowRedirects(boolean followRedirects) {
        this.instanceFollowRedirects = followRedirects;
    }

    @Override
    public boolean getInstanceFollowRedirects() {
        return instanceFollowRedirects;
    }

    @Override
    public void setRequestMethod(String method) throws ProtocolException {
        if (connected)
            throw new ProtocolException("Can't reset method: already connected");
        this.requestMethod = method;
    }

    @Override
    public String getRequestMethod() {
        return requestMethod;
    }

    @Override
    public void setFixedLengthStreamingMode(int contentLength) {
        ensureNotConnected();
        this.fixedLengthStreamingMode = contentLength;
        this.fixedLengthStreamingModeLong = -1L;
        this.chunkLength = -1;
    }

    @Override
    public void setFixedLengthStreamingMode(long contentLength) {
        ensureNotConnected();
        this.fixedLengthStreamingModeLong = contentLength;
        this.fixedLengthStreamingMode = -1;
        this.chunkLength = -1;
    }

    @Override
    public void setChunkedStreamingMode(int chunklen) {
        ensureNotConnected();
        this.chunkLength = chunklen;
        this.fixedLengthStreamingMode = -1;
        this.fixedLengthStreamingModeLong = -1L;
    }

    @Override
    public void setRequestProperty(String key, String value) {
        ensureNotConnected();
        setHeaderValue(key, value);
    }

    @Override
    public void addRequestProperty(String key, String value) {
        ensureNotConnected();
        addHeaderValue(key, value);
    }

    @Override
    public String getRequestProperty(String key) {
        String existingKey = findHeaderKey(key);
        List<String> values = existingKey == null ? null : requestProperties.get(existingKey);
        if ((values == null) || values.isEmpty()) {
            return null;
        }
        return values.get(values.size() - 1);
    }

    @Override
    public Map<String, List<String>> getRequestProperties() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : requestProperties.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public void setSSLSocketFactory(SSLSocketFactory sf) {
        if (sf == null)
            throw new IllegalArgumentException("no SSLSocketFactory specified");
        this.sslSocketFactory = sf;
    }

    @Override
    public SSLSocketFactory getSSLSocketFactory() {
        return sslSocketFactory;
    }

    @Override
    public void setHostnameVerifier(HostnameVerifier v) {
        if (v == null)
            throw new IllegalArgumentException("no HostnameVerifier specified");
        this.hostnameVerifier = v;
    }

    @Override
    public HostnameVerifier getHostnameVerifier() {
        return hostnameVerifier;
    }

    @Override
    public synchronized OutputStream getOutputStream() throws IOException {
        // as on the platform, once the response has been asked for the body is final
        if (responseRequested)
            throw new ProtocolException("cannot write request body after response has been read");
        doOutput = true;
        connected = true;

        // in a streaming mode the body goes straight to the network, so message signing runs now without a
        // body digest and a second call returns the same stream, as on the platform
        if (isStreamingModeSet()) {
            if (streamingOutputStream == null) {
                ensureRequestReadyForNetwork();
                requestBodyTransmitted = true;
                streamingOutputStream = networkRequest.getOutputStream();
            }
            return streamingOutputStream;
        }

        if (requestBodyBuffer == null) {
            requestBodyBuffer = new ByteArrayOutputStream();
            bufferedOutputStream = new OutputStream() {
                @Override
                public void write(int b) {
                    requestBodyBuffer.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    requestBodyBuffer.write(b, off, len);
                }

                @Override
                public void flush() {
                    // nothing to flush until the real request is sent
                }

                @Override
                public void close() {
                    // keep the buffer available until the wrapped request is finalized
                }
            };
        }
        return bufferedOutputStream;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        ensureResponseReady();
        return networkRequest.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
        try {
            ensureResponseReady();
            return networkRequest.getErrorStream();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public int getResponseCode() throws IOException {
        ensureResponseReady();
        return networkRequest.getResponseCode();
    }

    @Override
    public String getResponseMessage() throws IOException {
        ensureResponseReady();
        return networkRequest.getResponseMessage();
    }

    /**
     * Gets the permission to connect to the request's host and port, as the platform builds it from
     * the URL. Nothing is protected or sent, so the app may still configure the request afterwards.
     */
    @Override
    public Permission getPermission() throws IOException {
        URL url = getURL();
        int port = (url.getPort() != -1) ? url.getPort() : url.getDefaultPort();
        return new SocketPermission(url.getHost() + ":" + port, "connect,resolve");
    }

    @Override
    public Map<String, List<String>> getHeaderFields() {
        // as on the platform, a failed request is reported by the response header accessors as absent
        // headers, and as the failure by the methods that declare IOException
        try {
            ensureResponseReady();
        } catch (IOException e) {
            return Collections.emptyMap();
        }
        return networkRequest.getHeaderFields();
    }

    @Override
    public String getHeaderField(String name) {
        try {
            ensureResponseReady();
        } catch (IOException e) {
            return null;
        }
        return networkRequest.getHeaderField(name);
    }

    @Override
    public String getHeaderField(int n) {
        try {
            ensureResponseReady();
        } catch (IOException e) {
            return null;
        }
        return networkRequest.getHeaderField(n);
    }

    @Override
    public String getHeaderFieldKey(int n) {
        try {
            ensureResponseReady();
        } catch (IOException e) {
            return null;
        }
        return networkRequest.getHeaderFieldKey(n);
    }

    @Override
    public Object getContent() throws IOException {
        ensureResponseReady();
        return networkRequest.getContent();
    }

    @Override
    public Object getContent(Class[] classes) throws IOException {
        ensureResponseReady();
        return networkRequest.getContent(classes);
    }

    /**
     * BufferedRequestContext adapts this buffered connection to the ApproovRequestContext of the
     * ApproovService pipeline. Its URL can be changed, taking effect when the network connection is
     * created, and the buffered body is available for message signing. Its header writes are accepted
     * after the app's changes are refused, as the deferred processed-request callback makes them.
     */
    private final class BufferedRequestContext implements ApproovRequestContext {
        @Override
        public String getMethod() {
            return effectiveMethod();
        }

        @Override
        public String getURL() {
            return configuredUrl.toString();
        }

        @Override
        public boolean supportsURLMutation() {
            return true;
        }

        @Override
        public void setURL(String url) throws ApproovException {
            try {
                configuredUrl = new URL(url);
            } catch (MalformedURLException e) {
                // never the exception's message, as it may quote the URL with the substituted secure strings
                throw new ApproovException("Query parameter substitution: invalid URL");
            }
        }

        @Override
        public String getHeader(String name) {
            List<String> values = getHeaders(name);
            if (values.isEmpty())
                return null;
            return values.get(values.size() - 1);
        }

        @Override
        public List<String> getHeaders(String name) {
            for (Map.Entry<String, List<String>> entry : requestProperties.entrySet()) {
                if ((entry.getKey() != null) && entry.getKey().equalsIgnoreCase(name)) {
                    List<String> values = entry.getValue();
                    return (values != null) ? values : Collections.<String>emptyList();
                }
            }
            return Collections.emptyList();
        }

        @Override
        public void setHeader(String name, String value) {
            // bypasses the connected check as this is used by the deferred processed-request callback
            setHeaderValue(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            addHeaderValue(name, value);
        }

        @Override
        public void removeHeader(String name) {
            String matchingKey = null;
            for (String key : requestProperties.keySet()) {
                if (key.equalsIgnoreCase(name)) {
                    matchingKey = key;
                    break;
                }
            }
            if (matchingKey != null)
                requestProperties.remove(matchingKey);
        }

        @Override
        public boolean hasBody() {
            return doOutput || (requestBodyBuffer != null);
        }

        @Override
        public byte[] getBodyBytes() {
            return getBufferedRequestBody();
        }
    }
}
