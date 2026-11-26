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

import io.approov.service.android.ApproovLog;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;

import io.approov.service.android.ApproovException;
import io.approov.service.android.ApproovPinningVerifier;
import io.approov.service.android.ApproovRequestContext;
import io.approov.service.android.ApproovService;

/**
 * ApproovUrlConnection adds Approov protection to an HttpsURLConnection. Pass each connection through
 * addApproovToConnection and continue with the connection it returns, or apply the io.approov.gradle
 * plugin, which does this for URL.openConnection(). The connection is pinned with the Approov dynamic
 * pins and the request gets the Approov token, any secure string substitutions and any configured
 * message signing.
 * <p>
 * Note that the URL of an HttpsURLConnection cannot be changed once it is opened, so a query parameter
 * substitution is only applied by the connection addApproovToConnection returns. The deprecated
 * addApproov refuses a request that a query parameter substitution applies to.
 */
public class ApproovUrlConnection {
    // logging tag
    private static final String TAG = "ApproovUrlConnection";

    /**
     * ConnectionFactory opens the underlying connections, so that tests can supply connections
     * without opening sockets.
     */
    interface ConnectionFactory {
        URLConnection open(URL url) throws IOException;

        URLConnection open(URL url, Proxy proxy) throws IOException;
    }

    // the connection factory that opens real connections
    private static final ConnectionFactory DEFAULT_CONNECTION_FACTORY = new ConnectionFactory() {
        @Override
        public URLConnection open(URL url) throws IOException {
            return url.openConnection();
        }

        @Override
        public URLConnection open(URL url, Proxy proxy) throws IOException {
            return url.openConnection(proxy);
        }
    };

    // the connection factory currently in use
    private static volatile ConnectionFactory connectionFactory = DEFAULT_CONNECTION_FACTORY;

    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovUrlConnection() {
    }

    static URLConnection openRawConnection(URL url) throws IOException {
        return connectionFactory.open(url);
    }

    static URLConnection openRawConnection(URL url, Proxy proxy) throws IOException {
        return connectionFactory.open(url, proxy);
    }

    static void setConnectionFactoryForTesting(ConnectionFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("setConnectionFactoryForTesting: null factory");
        }
        connectionFactory = factory;
    }

    static void resetForTesting() {
        connectionFactory = DEFAULT_CONNECTION_FACTORY;
        redirectWarningLogged.set(false);
    }

    // true once addApproov has logged its redirect warning, which is logged once per process
    private static final java.util.concurrent.atomic.AtomicBoolean redirectWarningLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    // the warning addApproov logs when it turns redirect following off on a connection it protects
    static final String REDIRECT_WARNING = "addApproov: setInstanceFollowRedirects(true) overridden so the "
            + "Approov headers and secrets are not resent to a redirect target, use addApproovToConnection or the "
            + "io.approov.gradle plugin to follow redirects";

    // wraps the current HostnameVerifier, so any verifier the app installed still runs first
    private static void installPinningVerifier(HttpsURLConnection connection) {
        HostnameVerifier existing = connection.getHostnameVerifier();
        if (existing == null)
            existing = HttpsURLConnection.getDefaultHostnameVerifier();
        connection.setHostnameVerifier(new ApproovPinningVerifier(existing));
    }

    /**
     * Adds Approov to the given connection in place. The connection is pinned with the Approov
     * dynamic pins, the Approov token and any TraceID are added as headers, secure string header
     * substitutions are applied and any message signing is performed, without a body digest. This
     * must be called before the connection is opened. Note that a connection this protects does not
     * follow redirects, even if the app turned them on, so the app receives the 3xx response and a
     * warning is logged once per process. Before Approov protection is enabled the connection
     * proceeds without Approov processing. A connection that is already protected, such as one the
     * io.approov.gradle plugin's woven openConnection() returned, is left as it is and keeps following
     * redirects itself, protecting each hop afresh.
     * <p>
     * Deprecated: use addApproovToConnection, or the io.approov.gradle plugin, instead.
     *
     * @param connection is the HttpsURLConnection to which Approov is being added
     * @throws IOException if there was a problem, such as the service mutator deciding the request must not
     * proceed or a query parameter substitution applying to the URL
     */
    @Deprecated
    public static void addApproov(HttpsURLConnection connection) throws IOException {
        // a connection that is already Approov protected protects itself
        if (connection instanceof ApproovProtectedConnection)
            return;

        // a query parameter substitution cannot be applied in place so we fail rather than send the
        // placeholder value
        if (ApproovService.isApproovProtectionEnabled()
                && anyQueryParamSubstitutionApplies(connection.getURL().toString()))
            throw new ApproovException("addApproov: query parameter substitution requires addApproovToConnection");

        // ensure the request is pinned even if it is later excluded from Approov token processing
        if (shouldPin(connection))
            installPinningVerifier(connection);

        // run the Approov request pipeline on the connection
        UrlConnectionRequestContext context = new UrlConnectionRequestContext(connection);
        ApproovService.ApproovPreparedRequest prepared = ApproovService.prepareRequest(context);
        prepared.completeProcessing(context);

        // the platform would resend every request header to a redirect target, so a protected
        // connection hands the 3xx to the app instead, even over the app's own choice
        if (prepared.isProcessed()) {
            connection.setInstanceFollowRedirects(false);
            if (redirectWarningLogged.compareAndSet(false, true))
                ApproovLog.w(TAG, REDIRECT_WARNING);
        }
    }

    /**
     * Adds Approov to the given connection. Continue with the returned connection and configure the request on it:
     * Approov protection is applied when the request is about to be sent, so the headers, method and body set on
     * the returned connection are all protected. The connection is then pinned with the Approov dynamic pins, the
     * Approov token and any TraceID are added as headers, secure string substitutions are applied, the request is
     * signed once its body has been written, so that signing can include a body digest, and redirects are followed
     * by the connection itself, each hop protected afresh. A request the service mutator refuses fails when it is
     * sent, from connect, getOutputStream, getResponseCode or the other methods that declare IOException, as on a
     * connection the Gradle plugin wraps.
     *
     * @param connection is the HttpsURLConnection to which Approov is being added
     * @return the connection to be used by the caller
     * @throws IOException is declared for compatibility: a request that cannot be protected fails when it is sent
     */
    public static HttpsURLConnection addApproovToConnection(HttpsURLConnection connection) throws IOException {
        // a connection that is already Approov protected is returned unchanged
        if (connection instanceof ApproovProtectedConnection)
            return connection;

        // the same lazy connection the Gradle plugin returns for a woven URL.openConnection(), so protection is
        // applied to the request as the app finally configures it, not as it was when it was wrapped
        return new ApproovLazyHttpsURLConnection(connection);
    }

    /**
     * Applies Approov protection to the connection now, from the request as configured so far. The lazy connection
     * calls it just before the request is sent.
     *
     * @param connection is the HttpsURLConnection to protect
     * @return the protected connection
     * @throws IOException if there was a problem, such as the service mutator deciding the request must not proceed
     */
    static HttpsURLConnection protectNow(HttpsURLConnection connection) throws IOException {
        return protectNow(connection, Collections.<String, List<String>>emptyMap());
    }

    /**
     * Applies Approov protection to the connection now, as protectNow(connection), with the headers the app set on
     * the request, which restore any the platform does not report back from getRequestProperties.
     *
     * @param connection is the HttpsURLConnection to protect
     * @param appHeaders are the headers the app set on the request
     * @return the protected connection
     * @throws IOException if there was a problem, such as the service mutator deciding the request must not proceed
     */
    static HttpsURLConnection protectNow(HttpsURLConnection connection, Map<String, List<String>> appHeaders)
            throws IOException {
        // ensure the request is pinned even if it is later excluded from Approov token processing, the
        // wrapper copies the verifier
        if (shouldPin(connection))
            installPinningVerifier(connection);

        // we always return the wrapper, even for a simple GET, as it refreshes stale protection just
        // before the request is sent
        ApproovBufferedHttpsURLConnection buffered =
                new ApproovBufferedHttpsURLConnection(connection, appHeaders);
        buffered.prepareApproov();
        return buffered;
    }

    private static boolean shouldPin(HttpsURLConnection connection) throws IOException {
        return shouldPin(connection.getURL());
    }

    // as the service mutator decides; a hook exception is reported as an ApproovException naming the hook
    static boolean shouldPin(URL url) throws IOException {
        try {
            return ApproovService.getServiceMutator().handlePinningShouldProcessRequest(
                    url.toString());
        } catch (RuntimeException | ApproovException e) {
            // any exception the app's hook throws, an Approov one included, is the hook's failure
            String message = "ApproovServiceMutator.handlePinningShouldProcessRequest failed: " + e;
            ApproovLog.e(TAG, message);
            throw new ApproovException(message, e);
        }
    }

    private static boolean anyQueryParamSubstitutionApplies(String url) {
        for (java.util.regex.Pattern pattern : ApproovService.getSubstitutionQueryParams().values()) {
            if (pattern.matcher(url).find())
                return true;
        }
        return false;
    }

    /**
     * UrlConnectionRequestContext adapts an HttpsURLConnection protected in place to the
     * ApproovRequestContext of the ApproovService pipeline. Its URL cannot be changed and its body is
     * not available, as the caller writes the body after the processing.
     */
    static final class UrlConnectionRequestContext implements ApproovRequestContext {
        // the connection being protected
        private final HttpsURLConnection connection;

        UrlConnectionRequestContext(HttpsURLConnection connection) {
            this.connection = connection;
        }

        @Override
        public String getMethod() {
            // a GET with a body (setDoOutput(true)) is sent as a POST by the platform
            String method = connection.getRequestMethod();
            if ("GET".equals(method) && connection.getDoOutput())
                return "POST";
            return method;
        }

        @Override
        public String getURL() {
            return connection.getURL().toString();
        }

        @Override
        public boolean supportsURLMutation() {
            return false;
        }

        @Override
        public void setURL(String url) throws ApproovException {
            throw new ApproovException("setURL: not supported in place, use addApproovToConnection");
        }

        @Override
        public String getHeader(String name) {
            return connection.getRequestProperty(name);
        }

        @Override
        public List<String> getHeaders(String name) {
            Map<String, List<String>> properties = connection.getRequestProperties();
            for (Map.Entry<String, List<String>> entry : properties.entrySet()) {
                if ((entry.getKey() != null) && entry.getKey().equalsIgnoreCase(name))
                    return entry.getValue();
            }
            return Collections.emptyList();
        }

        @Override
        public void setHeader(String name, String value) {
            connection.setRequestProperty(name, value);
        }

        @Override
        public void removeHeader(String name) {
            connection.setRequestProperty(name, null);
        }

        @Override
        public void addHeader(String name, String value) {
            connection.addRequestProperty(name, value);
        }

        @Override
        public boolean hasBody() {
            return connection.getDoOutput();
        }

        @Override
        public byte[] getBodyBytes() {
            // the body is written by the caller after Approov processing so it is not available here
            return null;
        }
    }
}
