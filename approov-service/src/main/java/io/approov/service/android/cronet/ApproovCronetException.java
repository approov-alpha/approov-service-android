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

package io.approov.service.android.cronet;

import org.chromium.net.NetworkException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLPeerUnverifiedException;

// ApproovCronetException is the failure delivered to UrlRequest.Callback.onFailed for a Cronet request, with
// the error codes real Cronet reports for the same failure.
final class ApproovCronetException extends NetworkException {
    // net::ERR_FAILED
    static final int ERR_FAILED = -2;

    // net::ERR_TIMED_OUT
    static final int ERR_TIMED_OUT = -7;

    // net::ERR_CONNECTION_REFUSED
    static final int ERR_CONNECTION_REFUSED = -102;

    // net::ERR_NAME_NOT_RESOLVED
    static final int ERR_NAME_NOT_RESOLVED = -105;

    // net::ERR_SSL_PINNED_KEY_NOT_IN_CERT_CHAIN
    static final int ERR_SSL_PINNED_KEY_NOT_IN_CERT_CHAIN = -150;

    // net::ERR_INVALID_URL
    static final int ERR_INVALID_URL = -300;

    // net::ERR_TOO_MANY_REDIRECTS
    static final int ERR_TOO_MANY_REDIRECTS = -310;

    // the public error code reported for the failure
    private final int errorCode;

    // the net error reported as the internal error code
    private final int internalErrorCode;

    // reports the net error Cronet would report for the cause
    ApproovCronetException(String message, Throwable cause) {
        this(message, cause, internalCodeOf(cause));
    }

    // reports the given net error with the public code Cronet pairs with it
    ApproovCronetException(String message, Throwable cause, int internalErrorCode) {
        super(message, cause);
        this.internalErrorCode = internalErrorCode;
        this.errorCode = errorCodeOf(internalErrorCode);
    }

    @Override
    public int getErrorCode() {
        return errorCode;
    }

    @Override
    public int getCronetInternalErrorCode() {
        return internalErrorCode;
    }

    // as NetworkExceptionImpl.immediatelyRetryable()
    @Override
    public boolean immediatelyRetryable() {
        switch (errorCode) {
            case ERROR_NETWORK_CHANGED:
            case ERROR_TIMED_OUT:
            case ERROR_CONNECTION_CLOSED:
            case ERROR_CONNECTION_TIMED_OUT:
            case ERROR_CONNECTION_RESET:
                return true;
            default:
                return false;
        }
    }

    // a pinning failure anywhere in the cause chain wins
    private static int internalCodeOf(Throwable cause) {
        for (Throwable t = cause; t != null; t = (t.getCause() == t) ? null : t.getCause()) {
            if (t instanceof SSLPeerUnverifiedException)
                return ERR_SSL_PINNED_KEY_NOT_IN_CERT_CHAIN;
        }
        if (cause instanceof ConnectException)
            return ERR_CONNECTION_REFUSED;
        if (cause instanceof UnknownHostException)
            return ERR_NAME_NOT_RESOLVED;
        if (cause instanceof SocketTimeoutException)
            return ERR_TIMED_OUT;
        return ERR_FAILED;
    }

    // as Cronet's native NetErrorToUrlRequestError does
    private static int errorCodeOf(int netError) {
        switch (netError) {
            case ERR_CONNECTION_REFUSED:
                return ERROR_CONNECTION_REFUSED;
            case ERR_NAME_NOT_RESOLVED:
                return ERROR_HOSTNAME_NOT_RESOLVED;
            case ERR_TIMED_OUT:
                return ERROR_TIMED_OUT;
            default:
                return ERROR_OTHER;
        }
    }
}
