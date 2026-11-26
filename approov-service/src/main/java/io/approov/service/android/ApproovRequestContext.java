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

package io.approov.service.android;

import java.util.List;

/**
 * ApproovRequestContext is a stack neutral abstraction over a mutable outbound network request. Each
 * networking stack adapter (OkHttp, HttpsURLConnection, Volley, gRPC, Cronet) provides an implementation
 * that maps the operations onto its own request representation. The Approov request pipeline in
 * ApproovService operates only on this interface, so token injection, header and query parameter
 * substitution, and message signing behave identically on every stack.
 */
public interface ApproovRequestContext {
    /**
     * Gets the HTTP method of the request, such as "GET" or "POST". Stacks without an HTTP method, such as
     * gRPC which always maps onto POST, should return the closest equivalent.
     *
     * @return the request method
     */
    String getMethod();

    /**
     * Gets the full URL string for the request, including the scheme, host, path and query. For stacks
     * without full URLs, such as gRPC, this is the https URL of the authority.
     *
     * @return the full URL string
     */
    String getURL();

    /**
     * Gets the target URI of the request exactly as the networking stack sends it, which message signing
     * covers as "@target-uri". It must be the string the stack puts on the wire, never a parsed and
     * normalized form, so that the backend reconstructs the same value from the request it receives. Any
     * fragment is ignored, since it is never sent. The default is getURL(), which is the wire URL on OkHttp,
     * HttpsURLConnection and Volley.
     *
     * @return the target URI as sent
     */
    default String getTargetURI() {
        return getURL();
    }

    /**
     * Determines whether the URL of the request may still be changed. Query parameter substitution is only
     * attempted when this returns true.
     *
     * @return true if setURL() may be called, false otherwise
     */
    boolean supportsURLMutation();

    /**
     * Replaces the request URL. This is only called when supportsURLMutation() is true.
     *
     * @param url is the replacement URL string
     * @throws ApproovException if there was a problem applying the URL
     */
    void setURL(String url) throws ApproovException;

    /**
     * Gets the value of the named header. If the header has multiple values then the last is returned, as
     * Request.header() in OkHttp and HttpURLConnection.getRequestProperty() do.
     *
     * @param name is the header name
     * @return the header value or null if not set
     */
    String getHeader(String name);

    /**
     * Gets all values of the named header, as used by message signing to build the RFC 9421 field values.
     *
     * @param name is the header name
     * @return the list of header values, or an empty list if not set
     */
    List<String> getHeaders(String name);

    /**
     * Gets the names of all headers currently set on the request, each name once. Adapters that evaluate
     * protection again for each network attempt use this to detect an attempt whose headers were changed
     * after protection was applied, such as an authenticator retrying a 401 with a new Authorization
     * header. If this returns null then only a changed URL or method is detected.
     *
     * @return the header names, or null if they cannot be enumerated
     */
    default List<String> getHeaderNames() {
        return null;
    }

    /**
     * Sets the named header to the given value, replacing any previous value.
     *
     * @param name is the header name
     * @param value is the header value
     */
    void setHeader(String name, String value);

    /**
     * Adds a field with the given value to the named header, after any it already has. The layer uses this
     * to restore every original field of a header it substituted when it strips the protection of a rebuilt
     * attempt, such as a redirect, or of a stale one, and without it a header the app sent as several fields
     * is restored as one field holding the last of them.
     * <p>
     * Note that this is for adapters, not for app code.
     *
     * @param name is the header name
     * @param value is the header value
     * @throws UnsupportedOperationException if the request context does not support it
     */
    default void addHeader(String name, String value) {
        throw new UnsupportedOperationException(
                "addHeader: not supported by this request context");
    }

    /**
     * Removes all values of the named header. This is used when stale protection is refreshed, so that the
     * headers added by the previous processed request callback cannot remain with a new token or signature.
     *
     * @param name is the header name
     * @throws UnsupportedOperationException if the request context does not support it
     */
    default void removeHeader(String name) {
        throw new UnsupportedOperationException(
                "removeHeader: not supported by this request context");
    }

    /**
     * Determines whether the Approov pipeline may perform secure string substitution on the headers of this
     * request, that is whether the headers the pipeline sets are the ones the stack sends. A Volley context
     * returns false for a Request other than the one the Approov stack captured and sends, as its
     * getHeaders() would override any header the stack set.
     *
     * @return true if the pipeline should substitute headers on this request, false otherwise
     */
    default boolean supportsHeaderSubstitution() {
        return true;
    }

    /**
     * Determines whether the request carries a body.
     *
     * @return true if the request has a body, false otherwise
     */
    boolean hasBody();

    /**
     * Gets the bytes of the request body if they can be obtained without consuming the body. This is used
     * by message signing to compute a Content-Digest header.
     *
     * @return the body bytes, or null if the body is absent or unavailable, such as a one-shot streaming body
     */
    byte[] getBodyBytes();
}
