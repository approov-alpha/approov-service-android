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
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;

import javax.net.ssl.HttpsURLConnection;

/**
 * ApproovUrlConnectionWeaver is the runtime target of the Approov Gradle plugin for HttpsURLConnection.
 * The plugin rewrites each url.openConnection() call site in the app to call openConnection(url) here.
 * Note that this class must not be renamed or moved, as the woven byte code refers to it by name.
 */
public final class ApproovUrlConnectionWeaver {
    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovUrlConnectionWeaver() {
    }

    /**
     * Opens a connection for the given URL. An HttpsURLConnection is wrapped so that Approov
     * protection is applied just before the request is sent, after the app has set its headers and
     * body. A request sent before the ApproovService is initialized goes out without Approov
     * processing. Any other connection is returned unchanged.
     *
     * @param url is the URL whose connection is being opened
     * @return the connection, wrapped with Approov protection if it is an HttpsURLConnection
     * @throws IOException if the underlying connection could not be opened
     */
    public static URLConnection openConnection(URL url) throws IOException {
        return wrap(ApproovUrlConnection.openRawConnection(url));
    }

    /**
     * Opens a connection for the given URL through the given proxy, applying Approov protection in
     * the same way as openConnection(url). This is the target for woven url.openConnection(proxy)
     * call sites.
     *
     * @param url is the URL whose connection is being opened
     * @param proxy is the proxy through which to open the connection
     * @return the connection, wrapped with Approov protection if it is an HttpsURLConnection
     * @throws IOException if the underlying connection could not be opened
     */
    public static URLConnection openConnection(URL url, Proxy proxy) throws IOException {
        URLConnection connection = ApproovUrlConnection.openRawConnection(url, proxy);
        if (connection instanceof HttpsURLConnection)
            return new ApproovLazyHttpsURLConnection((HttpsURLConnection) connection, proxy);
        return connection;
    }

    // wraps even before initialization, so an early connection is not left unprotected; non-https is unchanged
    private static URLConnection wrap(URLConnection connection) {
        if (connection instanceof HttpsURLConnection) {
            return new ApproovLazyHttpsURLConnection((HttpsURLConnection) connection);
        }
        return connection;
    }
}
