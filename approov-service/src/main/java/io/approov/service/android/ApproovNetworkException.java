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

// ApproovNetworkException is thrown if there is a network issue (NO_NETWORK, POOR_NETWORK or
// MITM_DETECTED) that is likely to be temporary, so a user initiated retry should be performed
public class ApproovNetworkException extends ApproovFetchStatusException {

    /**
     * Constructs an Approov exception due to a networking error.
     *
     * @param message is the basic information about the exception cause
     */
    public ApproovNetworkException(String message) {
        super(null, message);
    }

    /**
     * Constructs an Approov exception due to a networking error with a specific fetch status.
     *
     * @param status is the fetch status that caused the error
     * @param message is the basic information about the exception cause
     */
    public ApproovNetworkException(Approov.TokenFetchStatus status, String message) {
        super(status, message);
    }
}
