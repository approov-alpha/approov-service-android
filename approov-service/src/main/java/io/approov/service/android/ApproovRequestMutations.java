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

import java.util.Collections;
import java.util.List;

/**
 * ApproovRequestMutations records the changes that the Approov request pipeline applied to a request. It is
 * passed to the processed request callback of the ApproovServiceMutator so that the mutator of your app
 * knows exactly which headers were added or substituted. It is read only to the app, as the layer relies on
 * it to strip its headers from a redirect or retry.
 */
public class ApproovRequestMutations {
    // the header that carries the Approov token, or null if none was added
    private String tokenHeaderKey;

    // the prefix placed before the token in the token header, or null if no token header was added
    private String tokenHeaderPrefix;

    // the header that carries the Approov TraceID, or null if none was added
    private String traceIDHeaderKey;

    // the header that reports the Approov fetch status, or null if none was added
    private String statusHeaderKey;

    // the header names whose values were substituted with secure strings
    private List<String> substitutionHeaderKeys = Collections.emptyList();

    // the original URL prior to any query parameter substitution with a secure string, or null
    private String originalURL;

    // the query parameter keys that were substituted with secure strings
    private List<String> substitutionQueryParamKeys = Collections.emptyList();

    /**
     * Gets the name of the header holding the Approov token.
     *
     * @return the token header name or null if no token was added
     */
    public String getTokenHeaderKey() {
        return tokenHeaderKey;
    }

    void setTokenHeaderKey(String tokenHeaderKey) {
        this.tokenHeaderKey = tokenHeaderKey;
    }

    /**
     * Gets the prefix placed before the token in the token header, as configured when the header was added.
     *
     * @return the token prefix (empty if none) or null if no token header was added
     */
    public String getTokenHeaderPrefix() {
        return tokenHeaderPrefix;
    }

    void setTokenHeaderPrefix(String tokenHeaderPrefix) {
        this.tokenHeaderPrefix = tokenHeaderPrefix;
    }

    /**
     * Gets the name of the header holding the Approov TraceID.
     *
     * @return the TraceID header name or null if none was added
     */
    public String getTraceIDHeaderKey() {
        return traceIDHeaderKey;
    }

    void setTraceIDHeaderKey(String traceIDHeaderKey) {
        this.traceIDHeaderKey = traceIDHeaderKey;
    }

    /**
     * Gets the name of the header reporting the Approov token fetch status, as set with
     * ApproovService.setStatusHeader().
     *
     * @return the status header name or null if none was added
     */
    public String getStatusHeaderKey() {
        return statusHeaderKey;
    }

    void setStatusHeaderKey(String statusHeaderKey) {
        this.statusHeaderKey = statusHeaderKey;
    }

    /**
     * Gets the names of headers whose values were substituted with secure strings. A header whose secure
     * string fetch failed, carrying the fetch status in place of its placeholder, is not listed.
     *
     * @return the list of substituted header names, never null
     */
    public List<String> getSubstitutionHeaderKeys() {
        return substitutionHeaderKeys;
    }

    void setSubstitutionHeaderKeys(List<String> substitutionHeaderKeys) {
        this.substitutionHeaderKeys =
                (substitutionHeaderKeys != null) ? substitutionHeaderKeys : Collections.<String>emptyList();
    }

    /**
     * Gets the original URL prior to query parameter substitution, if a query parameter was substituted with
     * a secure string.
     *
     * @return the original URL or null if no query parameter was substituted with a secure string
     */
    public String getOriginalURL() {
        return originalURL;
    }

    /**
     * Gets the query parameter keys that were substituted with secure strings. A query parameter whose secure
     * string fetch failed, carrying the fetch status in place of its placeholder, is not listed.
     *
     * @return the list of substituted query parameter keys, never null
     */
    public List<String> getSubstitutionQueryParamKeys() {
        return substitutionQueryParamKeys;
    }

    void setSubstitutionQueryParamResults(String originalURL, List<String> substitutionQueryParamKeys) {
        this.originalURL = originalURL;
        this.substitutionQueryParamKeys =
                (substitutionQueryParamKeys != null) ? substitutionQueryParamKeys : Collections.<String>emptyList();
    }
}
