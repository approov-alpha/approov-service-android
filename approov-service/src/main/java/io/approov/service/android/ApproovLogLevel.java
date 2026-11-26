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

/**
 * ApproovLogLevel sets how much the package writes to the device log, using ApproovService.setLoggingLevel().
 * Each level includes the ones before it and INFO is the default. The names and order match ApproovLogLevel
 * in the iOS package.
 */
public enum ApproovLogLevel {
    /** Nothing from the package, errors included. */
    OFF,
    /** Errors only. */
    ERROR,
    /** Errors and warnings. */
    WARNING,
    /** Errors, warnings and information (the default). */
    INFO,
    /**
     * Everything, including per-request lines, the loggable token (whose claims carry the device ID),
     * request URLs and the device ID.
     */
    DEBUG
}
