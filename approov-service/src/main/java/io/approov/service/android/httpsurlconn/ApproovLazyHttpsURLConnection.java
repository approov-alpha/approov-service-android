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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.net.Proxy;
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


/**
 * ApproovLazyHttpsURLConnection is the HttpsURLConnection the Approov Gradle plugin returns for a woven
 * URL.openConnection() call, and that addApproovToConnection returns. It forwards all configuration to the
 * underlying connection and applies Approov protection the first time the request is about to be sent, as the
 * app sets its headers and body only after it has the connection. The connection is then protected,
 * with any streaming mode the app set and the proxy it was opened with, and this wrapper delegates to
 * the connection that returns.
 */
final class ApproovLazyHttpsURLConnection extends HttpsURLConnection implements ApproovProtectedConnection {
    // the underlying connection returned by URL.openConnection(), which receives all configuration until
    // the request is sent
    private final HttpsURLConnection real;

    // the connection calls are delegated to, which is the underlying connection until protection is applied
    // and the protected connection afterwards
    private HttpsURLConnection delegate;

    // true once Approov protection has been applied
    private boolean materialized;

    // the proxy the connection was opened with, or null
    private final Proxy proxy;

    // the streaming mode the app set before protection was applied, which the platform connection cannot
    // report back, or -1 if not set
    private int fixedLengthStreamingMode = -1;
    private long fixedLengthStreamingModeLong = -1L;
    private int chunkLength = -1;

    // the headers the app set through this wrapper before protection was applied, as the platform connection
    // may not report them all back (the JDK hides Authorization and Proxy-Authorization)
    private final Map<String, List<String>> appHeaders = new LinkedHashMap<>();

    ApproovLazyHttpsURLConnection(HttpsURLConnection real) {
        this(real, null);
    }

    ApproovLazyHttpsURLConnection(HttpsURLConnection real, Proxy proxy) {
        super(real.getURL());
        this.real = real;
        this.delegate = real;
        this.proxy = proxy;
    }

    // once, just before the request is sent, when all the configuration set on this wrapper has reached it
    private synchronized void materialize() throws IOException {
        if (!materialized) {
            // an exception is passed on unchanged and leaves the delegate as the underlying connection, so a
            // retry attempts protection again rather than sending the request unprotected
            HttpsURLConnection protectedConnection = ApproovUrlConnection.protectNow(real, appHeaders);
            if (protectedConnection instanceof ApproovBufferedHttpsURLConnection) {
                ApproovBufferedHttpsURLConnection buffered = (ApproovBufferedHttpsURLConnection) protectedConnection;
                buffered.setProxy(proxy);
                // the streaming mode cannot be read back from the platform connection, so we replay the app's
                // choice and the body streams rather than being buffered in memory
                if (fixedLengthStreamingModeLong >= 0)
                    buffered.setFixedLengthStreamingMode(fixedLengthStreamingModeLong);
                else if (fixedLengthStreamingMode >= 0)
                    buffered.setFixedLengthStreamingMode(fixedLengthStreamingMode);
                else if (chunkLength >= 0)
                    buffered.setChunkedStreamingMode(chunkLength);
            }
            delegate = protectedConnection;
            // only mark as materialized once protection has been applied
            materialized = true;
        }
    }

    @Override
    public void connect() throws IOException {
        materialize();
        delegate.connect();
        connected = true;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        materialize();
        return delegate.getOutputStream();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        materialize();
        return delegate.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
        try {
            materialize();
        } catch (IOException e) {
            return null;
        }
        return delegate.getErrorStream();
    }

    @Override
    public int getResponseCode() throws IOException {
        materialize();
        return delegate.getResponseCode();
    }

    @Override
    public String getResponseMessage() throws IOException {
        materialize();
        return delegate.getResponseMessage();
    }

    @Override
    public Object getContent() throws IOException {
        materialize();
        return delegate.getContent();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Object getContent(Class[] classes) throws IOException {
        materialize();
        return delegate.getContent(classes);
    }

    /**
     * Gets the permission to connect, which depends only on the URL, without applying the protection,
     * so the app may still configure the request.
     */
    @Override
    public synchronized Permission getPermission() throws IOException {
        return delegate.getPermission();
    }

    @Override
    public Map<String, List<String>> getHeaderFields() {
        // as on the platform, a failed request is reported by the response header accessors as absent
        // headers, and as the failure by the methods that declare IOException
        if (!materializeQuietly())
            return Collections.emptyMap();
        return delegate.getHeaderFields();
    }

    @Override
    public String getHeaderField(String name) {
        if (!materializeQuietly())
            return null;
        return delegate.getHeaderField(name);
    }

    @Override
    public String getHeaderField(int n) {
        if (!materializeQuietly())
            return null;
        return delegate.getHeaderField(n);
    }

    @Override
    public String getHeaderFieldKey(int n) {
        if (!materializeQuietly())
            return null;
        return delegate.getHeaderFieldKey(n);
    }

    @Override
    public long getHeaderFieldDate(String name, long defaultValue) {
        if (!materializeQuietly())
            return defaultValue;
        return delegate.getHeaderFieldDate(name, defaultValue);
    }

    @Override
    public String getCipherSuite() {
        materializeUnchecked();
        return delegate.getCipherSuite();
    }

    @Override
    public Certificate[] getLocalCertificates() {
        materializeUnchecked();
        return delegate.getLocalCertificates();
    }

    @Override
    public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException {
        try {
            materialize();
        } catch (IOException e) {
            SSLPeerUnverifiedException ex = new SSLPeerUnverifiedException("Failed to prepare request");
            ex.initCause(e);
            throw ex;
        }
        return delegate.getServerCertificates();
    }

    @Override
    public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
        try {
            materialize();
        } catch (IOException e) {
            SSLPeerUnverifiedException ex = new SSLPeerUnverifiedException("Failed to prepare request");
            ex.initCause(e);
            throw ex;
        }
        return delegate.getPeerPrincipal();
    }

    @Override
    public Principal getLocalPrincipal() {
        materializeUnchecked();
        return delegate.getLocalPrincipal();
    }

    // for an accessor that cannot throw an IOException; false if the request failed
    private boolean materializeQuietly() {
        try {
            materialize();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // for an accessor that cannot throw an IOException, reporting a failure as an IllegalStateException
    private void materializeUnchecked() {
        try {
            materialize();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare request", e);
        }
    }

    @Override
    public void disconnect() {
        delegate.disconnect();
    }

    @Override
    public boolean usingProxy() {
        return delegate.usingProxy();
    }

    @Override
    public URL getURL() {
        return delegate.getURL();
    }

    @Override
    public void setRequestMethod(String method) throws ProtocolException {
        delegate.setRequestMethod(method);
    }

    @Override
    public String getRequestMethod() {
        return delegate.getRequestMethod();
    }

    @Override
    public synchronized void setRequestProperty(String key, String value) {
        delegate.setRequestProperty(key, value);
        if (!materialized && (value != null)) {
            List<String> values = new ArrayList<>(1);
            values.add(value);
            String existingKey = findAppHeaderKey(key);
            appHeaders.put(existingKey != null ? existingKey : key, values);
        }
    }

    @Override
    public synchronized void addRequestProperty(String key, String value) {
        delegate.addRequestProperty(key, value);
        if (!materialized && (value != null)) {
            String existingKey = findAppHeaderKey(key);
            String storedKey = existingKey != null ? existingKey : key;
            List<String> values = appHeaders.get(storedKey);
            if (values == null) {
                values = new ArrayList<>();
                appHeaders.put(storedKey, values);
            }
            values.add(value);
        }
    }

    // header names are case insensitive
    private String findAppHeaderKey(String key) {
        for (String candidate : appHeaders.keySet()) {
            if (candidate.equalsIgnoreCase(key))
                return candidate;
        }
        return null;
    }

    @Override
    public String getRequestProperty(String key) {
        return delegate.getRequestProperty(key);
    }

    @Override
    public Map<String, List<String>> getRequestProperties() {
        return delegate.getRequestProperties();
    }

    @Override
    public void setDoInput(boolean doInput) {
        delegate.setDoInput(doInput);
    }

    @Override
    public boolean getDoInput() {
        return delegate.getDoInput();
    }

    @Override
    public void setDoOutput(boolean doOutput) {
        delegate.setDoOutput(doOutput);
    }

    @Override
    public boolean getDoOutput() {
        return delegate.getDoOutput();
    }

    @Override
    public void setConnectTimeout(int timeout) {
        delegate.setConnectTimeout(timeout);
    }

    @Override
    public int getConnectTimeout() {
        return delegate.getConnectTimeout();
    }

    @Override
    public void setReadTimeout(int timeout) {
        delegate.setReadTimeout(timeout);
    }

    @Override
    public int getReadTimeout() {
        return delegate.getReadTimeout();
    }

    @Override
    public void setUseCaches(boolean useCaches) {
        delegate.setUseCaches(useCaches);
    }

    @Override
    public boolean getUseCaches() {
        return delegate.getUseCaches();
    }

    @Override
    public void setDefaultUseCaches(boolean defaultUseCaches) {
        delegate.setDefaultUseCaches(defaultUseCaches);
    }

    @Override
    public boolean getDefaultUseCaches() {
        return delegate.getDefaultUseCaches();
    }

    @Override
    public void setAllowUserInteraction(boolean allowUserInteraction) {
        delegate.setAllowUserInteraction(allowUserInteraction);
    }

    @Override
    public boolean getAllowUserInteraction() {
        return delegate.getAllowUserInteraction();
    }

    @Override
    public void setInstanceFollowRedirects(boolean followRedirects) {
        delegate.setInstanceFollowRedirects(followRedirects);
    }

    @Override
    public boolean getInstanceFollowRedirects() {
        return delegate.getInstanceFollowRedirects();
    }

    @Override
    public void setIfModifiedSince(long ifModifiedSince) {
        delegate.setIfModifiedSince(ifModifiedSince);
    }

    @Override
    public long getIfModifiedSince() {
        return delegate.getIfModifiedSince();
    }

    @Override
    public synchronized void setFixedLengthStreamingMode(int contentLength) {
        delegate.setFixedLengthStreamingMode(contentLength);
        fixedLengthStreamingMode = contentLength;
        fixedLengthStreamingModeLong = -1L;
        chunkLength = -1;
    }

    @Override
    public synchronized void setFixedLengthStreamingMode(long contentLength) {
        delegate.setFixedLengthStreamingMode(contentLength);
        fixedLengthStreamingModeLong = contentLength;
        fixedLengthStreamingMode = -1;
        chunkLength = -1;
    }

    @Override
    public synchronized void setChunkedStreamingMode(int chunklen) {
        delegate.setChunkedStreamingMode(chunklen);
        chunkLength = chunklen;
        fixedLengthStreamingMode = -1;
        fixedLengthStreamingModeLong = -1L;
    }

    @Override
    public void setSSLSocketFactory(SSLSocketFactory sf) {
        delegate.setSSLSocketFactory(sf);
    }

    @Override
    public SSLSocketFactory getSSLSocketFactory() {
        return delegate.getSSLSocketFactory();
    }

    @Override
    public void setHostnameVerifier(HostnameVerifier verifier) {
        delegate.setHostnameVerifier(verifier);
    }

    @Override
    public HostnameVerifier getHostnameVerifier() {
        return delegate.getHostnameVerifier();
    }
}
