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

import com.criticalblue.approovsdk.Approov;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ApproovServiceMutator provides an interface for modifying the behavior of the ApproovService class by
 * overriding the default implementations of the defined callbacks. Opportunities to modify behavior are
 * offered at key points in the service and attestation flows. The same implementation applies to requests
 * made with any supported networking stack (OkHttp, HttpsURLConnection, Volley, gRPC and Cronet), as the
 * request is presented through ApproovRequestContext. All methods have default implementations, so an
 * implementing class only overrides the methods it is interested in.
 * <p>
 * Three standard decision sets are provided. CLOSE_FAILURE has the interface defaults, which proceed with
 * the token on SUCCESS and with the status header only on NO_APPROOV_SERVICE, send a request to an
 * UNKNOWN_URL or UNPROTECTED_URL with no Approov headers, throw ApproovNetworkException for a network failure
 * and throw ApproovFetchStatusException for every other status, REJECTED included. ALWAYS_PROCEED never
 * aborts a request, so the backend decides. DEFAULT is the mutator used if none is set, and it is
 * ALWAYS_PROCEED. An app that wants requests aborted on a failed token fetch selects CLOSE_FAILURE. With
 * both standard sets a secure string is only substituted on SUCCESS. For any other secure string fetch
 * status the status is put in place of the placeholder, written as the Approov-Status header writes it
 * (such as "rejected" or "unknown_key").
 * <p>
 * The token decision applies to the Approov API domains of the app only, so a token fetch failure for any
 * other host is sent untouched unless an overriding hook throws its own exception. An app that wants a
 * request aborted for some further outcome overrides the relevant interceptor hook and throws a standard
 * network stack exception (an IOException), not an Approov specific type, which the layer passes on
 * unchanged on every stack. The aborts of the standard decisions keep their Approov exception types, whether
 * a custom mutator inherits or calls them. Any other exception a hook throws, including an ApproovException
 * the app built or rethrew, fails the request with an ApproovException naming the hook, with the original
 * as its cause. Note that no mutator signs requests, as message signing is independent of the decisions
 * (see ApproovService.enableMessageSigning()).
 */
public interface ApproovServiceMutator {
    /**
     * The decisions that are the interface defaults. These proceed on SUCCESS and NO_APPROOV_SERVICE, send
     * UNKNOWN_URL and UNPROTECTED_URL with no Approov headers (a secrets only API still gets its secure
     * strings) and throw an Approov exception for every other token fetch status on an Approov API domain. A
     * secure string is substituted on SUCCESS, and a failed fetch puts its status in place of the placeholder,
     * so a secure string never aborts the request.
     */
    ApproovServiceMutator CLOSE_FAILURE = new ApproovServiceMutator() {
        @Override
        public String toString() {
            return "ApproovServiceMutator.CLOSE_FAILURE";
        }

        @Override
        public boolean supportsProtectionRefresh() {
            // the default handleInterceptorProcessedRequest makes no changes so it is safe to call again
            return true;
        }
    };

    /**
     * The decisions that never abort a request. Every token fetch status except UNKNOWN_URL and
     * UNPROTECTED_URL proceeds with the status header, and with the token header on SUCCESS. A secure string
     * is only substituted on SUCCESS, and a failed fetch puts its status in place of the placeholder.
     */
    ApproovServiceMutator ALWAYS_PROCEED = new ApproovServiceMutator() {
        @Override
        public String toString() {
            return "ApproovServiceMutator.ALWAYS_PROCEED";
        }

        @Override
        public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url) {
            switch (approovResults.getStatus()) {
                case UNKNOWN_URL:
                case UNPROTECTED_URL:
                    // no Approov headers for a host not added to Approov or a secrets only API (still substituted)
                    return false;
                default:
                    // SUCCESS adds the token and any failure proceeds with the status header only
                    return true;
            }
        }

        @Override
        public boolean supportsProtectionRefresh() {
            // the default handleInterceptorProcessedRequest makes no changes so it is safe to call again
            return true;
        }
    };

    /**
     * The decisions used if no mutator is set, also reinstated by ApproovService.setServiceMutator(null).
     * These are the ALWAYS_PROCEED decisions, so a request is never aborted and the backend decides. An app
     * that wants requests aborted on a failed token fetch sets CLOSE_FAILURE.
     */
    ApproovServiceMutator DEFAULT = ALWAYS_PROCEED;

    /**
     * Determines whether a token fetch status is a network failure, meaning that the attestation could not
     * be performed because of the network rather than because of the app or device. This covers NO_NETWORK,
     * POOR_NETWORK and MITM_DETECTED, which the SDK reports for an untrusted network or a captive portal.
     *
     * @param status is the token fetch status to classify
     * @return true if the status is a network failure, false otherwise
     */
    static boolean isNetworkFailure(Approov.TokenFetchStatus status) {
        switch (status) {
            case NO_NETWORK:
            case POOR_NETWORK:
                return true;
            default:
                // matched by name rather than by enum constant
                return "MITM_DETECTED".equals(status.name());
        }
    }

    /**
     * Decides how to handle the token fetch result from an ApproovService.fetchToken() operation.
     *
     * @param approovResults is the TokenFetchResult obtained by fetchToken
     * @throws ApproovException if the result is a failure
     */
    default void handleFetchTokenResult(Approov.TokenFetchResult approovResults) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        if (isNetworkFailure(status))
            throw new ApproovNetworkException(status, "fetchToken: " + status.toString());
        switch (status) {
            case SUCCESS:
                break;
            default:
                throw new ApproovFetchStatusException(status, "fetchToken: " + status.toString());
        }
    }

    /**
     * Decides how to handle the token fetch result from an ApproovService.fetchSecureString() operation.
     *
     * @param approovResults is the TokenFetchResult obtained
     * @param operation is the operation type, "lookup" or "definition"
     * @param key is the secure string key
     * @throws ApproovException if the result is a failure
     */
    default void handleFetchSecureStringResult(Approov.TokenFetchResult approovResults, String operation, String key)
            throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        String arc = approovResults.getARC();
        String rejectionReasons = approovResults.getRejectionReasons();
        if (isNetworkFailure(status))
            throw new ApproovNetworkException(status,
                    "fetchSecureString " + operation + " for " + key + ": " + status.toString());
        switch (status) {
            case REJECTED:
                throw new ApproovRejectionException("fetchSecureString " + operation + " for " + key + ": "
                        + status + ApproovService.arcDetail(approovResults), arc, rejectionReasons);
            case SUCCESS:
            case UNKNOWN_KEY:
                break;
            default:
                throw new ApproovFetchStatusException(status,
                        "fetchSecureString " + operation + " for " + key + ": " + status.toString());
        }
    }

    /**
     * Decides how to handle the token fetch result from an ApproovService.fetchCustomJWT() operation.
     *
     * @param approovResults is the TokenFetchResult obtained
     * @throws ApproovException if the result is a failure
     */
    default void handleFetchCustomJWTResult(Approov.TokenFetchResult approovResults) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        String arc = approovResults.getARC();
        String rejectionReasons = approovResults.getRejectionReasons();
        if (isNetworkFailure(status))
            throw new ApproovNetworkException(status, "fetchCustomJWT: " + status.toString());
        switch (status) {
            case REJECTED:
                throw new ApproovRejectionException(
                        "fetchCustomJWT: " + status + ApproovService.arcDetail(approovResults), arc, rejectionReasons);
            case SUCCESS:
                break;
            default:
                throw new ApproovFetchStatusException(status, "fetchCustomJWT: " + status.toString());
        }
    }

    /**
     * Decides whether a request should be processed by the Approov request pipeline. This is called at the
     * start of processing for every request, and the default skips a request matching an exclusion URL regex.
     *
     * @param context is the request being processed
     * @return true if the request should be processed, false to pass it through unchanged
     * @throws IOException if an overriding implementation aborts the request with a standard network stack
     * exception, which the default only does for a null context
     */
    default boolean handleInterceptorShouldProcessRequest(ApproovRequestContext context) throws IOException {
        if (context == null)
            throw StandardDecisions.made(new ApproovException(
                    "handleInterceptorShouldProcessRequest: null request"));

        // check if the URL matches one of the exclusion regexs and skip processing in these cases
        String url = context.getURL();
        for (Pattern pattern : ApproovService.getExclusionURLRegexs().values()) {
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decides how to handle the token fetch result obtained during request processing. This interface
     * default is the CLOSE_FAILURE decision, which ALWAYS_PROCEED (the DEFAULT mutator) overrides. SUCCESS
     * adds the token, status and trace ID headers. NO_APPROOV_SERVICE proceeds with the status header only, as
     * evidence that Approov processing occurred, and no token header is sent. An UNKNOWN_URL result (a host not added to Approov) or UNPROTECTED_URL result (a secrets only
     * API, added with -noApproovToken) sends the request with no Approov headers, although a secrets only API
     * still gets its secure strings whatever this returns. NO_NETWORK, POOR_NETWORK and MITM_DETECTED
     * throw ApproovNetworkException and every other status throws ApproovFetchStatusException. Note that for
     * a failure status on a host that is not an Approov API domain the request is sent untouched whatever
     * this returns, unless an overriding implementation throws its own exception.
     *
     * @param approovResults is the TokenFetchResult from Approov
     * @param url is the URL string for which the token was requested
     * @return true to add the Approov headers the status allows (the token, status and trace ID headers on
     * SUCCESS, the status header otherwise), false to proceed without any Approov headers
     * @throws IOException if the request is aborted, with an ApproovException subclass from the default or a
     * standard network stack exception from an overriding implementation
     */
    default boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url)
            throws IOException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        switch (status) {
            case SUCCESS:
            case NO_APPROOV_SERVICE:
                // NO_APPROOV_SERVICE proceeds with the status header, as evidence that Approov processing occurred
                return true;
            case UNKNOWN_URL:
            case UNPROTECTED_URL:
                // no Approov headers for a host not added to Approov or a secrets only API (still substituted)
                return false;
            default:
                // only the host is named, as the URL may carry personal data or a secret and the message
                // reaches logs and, through some stacks, JavaScript
                String host = WireUrl.host(url);
                String what = "Approov token fetch for "
                        + (((host == null) || host.isEmpty()) ? "a URL without a host" : host) + ": " + status;
                if (isNetworkFailure(status))
                    throw StandardDecisions.made(new ApproovNetworkException(status, what));
                throw StandardDecisions.made(new ApproovFetchStatusException(status, what));
        }
    }

    /**
     * Decides how to handle the secure string fetch result while substituting a header during request
     * processing. The fetch used the current header value, minus any required prefix, as the secure string
     * key. This is called once per header being processed for substitution, and only for an https request
     * whose token fetch returned SUCCESS or UNPROTECTED_URL. The default substitutes on SUCCESS and lets the
     * request proceed for every other status. A failed fetch puts its status in place of the placeholder,
     * after any required prefix, written as the Approov-Status header writes it (such as "Bearer rejected"),
     * whatever this returns, so a value a failed result carries is never substituted. SUCCESS leaves the
     * placeholder when this returns false.
     *
     * @param approovResults is the TokenFetchResult from Approov
     * @param header is the header being substituted
     * @return true to substitute the secure string of a SUCCESS result, false not to
     * @throws IOException if an overriding implementation aborts the request with a standard network stack
     * exception, which the default never does
     */
    default boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult approovResults, String header)
            throws IOException {
        return substituteOnSuccess(approovResults);
    }

    /**
     * Decides how to handle the secure string fetch result while substituting a query parameter during
     * request processing. The fetch used the query parameter value as the secure string key. This is called
     * once for each matched query parameter being processed for substitution. The default substitutes on
     * SUCCESS and lets the request proceed for every other status. A failed fetch puts its status in place
     * of the placeholder, written as the Approov-Status header writes it (such as "rejected"), whatever this
     * returns, so a value a failed result carries is never substituted. SUCCESS leaves the placeholder when
     * this returns false.
     *
     * @param approovResults is the TokenFetchResult from Approov
     * @param queryKey is the query parameter key being substituted
     * @return true to substitute the secure string of a SUCCESS result, false not to
     * @throws IOException if an overriding implementation aborts the request with a standard network stack
     * exception, which the default never does
     */
    default boolean handleInterceptorQueryParamSubstitutionResult(Approov.TokenFetchResult approovResults,
            String queryKey) throws IOException {
        return substituteOnSuccess(approovResults);
    }

    /**
     * Determines whether to substitute a secure string, as both standard mutators do, only on SUCCESS. For
     * every other status the request proceeds with the status in place of the placeholder, as that value
     * reaching the backend is the evidence and the backend is the enforcement point.
     *
     * @param approovResults is the secure string fetch result
     * @return true to substitute, false otherwise
     */
    static boolean substituteOnSuccess(Approov.TokenFetchResult approovResults) {
        return approovResults.getStatus() == Approov.TokenFetchStatus.SUCCESS;
    }

    /**
     * Allows further modifications to be made through the request context after Approov has processed a
     * network request. This runs before any message signing, so the signature covers what it changes.
     *
     * @param context is the request that was processed
     * @param changes are the mutations applied to the request by Approov
     * @throws IOException if an overriding implementation aborts the request with a network stack exception
     */
    default void handleInterceptorProcessedRequest(ApproovRequestContext context, ApproovRequestMutations changes)
            throws IOException {
        // no further changes to the request are required
    }

    /**
     * Determines whether a stale protection refresh may safely call handleInterceptorProcessedRequest() more
     * than once for a request. Custom mutators opt out by default.
     *
     * @return true if the processed request callback may be called again, false otherwise
     */
    default boolean supportsProtectionRefresh() {
        return false;
    }

    /**
     * Decides whether certificate pinning should be applied to a request. This is called before pinning is
     * set up for a request on OkHttp, HttpsURLConnection and Volley, and the hook can only remove pinning,
     * never add it. Note that it is not asked on gRPC or Cronet, where pinning is shared by every call on a
     * channel or every request on an engine, so every connection to a host with Approov pins is pinned
     * whatever this returns.
     *
     * @param url is the URL string of the request being processed
     * @return true if pinning should be applied, false to skip it
     * @throws IOException if an overriding implementation aborts the request with a standard network stack
     * exception, which the default never does
     */
    default boolean handlePinningShouldProcessRequest(String url) throws IOException {
        // by default do not skip pinning for any requests
        return true;
    }
}
