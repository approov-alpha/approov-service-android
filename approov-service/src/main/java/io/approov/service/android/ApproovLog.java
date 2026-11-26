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

import android.util.Log;

/**
 * ApproovLog writes all the lines of the package to the device log, gated by the level set with
 * ApproovService.setLoggingLevel(). Debug logging enabled for the ApproovService tag, for example with
 * "adb shell setprop log.tag.ApproovService DEBUG", raises any level but OFF to DEBUG for every tag of
 * the package, so that support can read the debug lines of a release build. This class is thread-safe.
 * <p>
 * Note that this is for adapters, not for app code.
 */
public final class ApproovLog {
    // the tag whose platform debug setting raises the level to DEBUG
    static final String SUPPORT_TAG = "ApproovService";

    // the level set by the app, volatile as it is read on every request thread
    private static volatile ApproovLogLevel level = ApproovLogLevel.INFO;

    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovLog() {
    }

    // sets the level of logging
    static void setLevel(ApproovLogLevel newLevel) {
        level = newLevel;
    }

    private static boolean isEnabled(ApproovLogLevel wanted) {
        // OFF is never raised by the platform debug setting
        ApproovLogLevel current = level;
        if (current == ApproovLogLevel.OFF)
            return false;
        if (current.compareTo(wanted) >= 0)
            return true;
        return supportDebug();
    }

    // whether debug logging is enabled for the support tag by the platform setting
    private static boolean supportDebug() {
        try {
            return Log.isLoggable(SUPPORT_TAG, Log.DEBUG);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Determines whether debug lines, including the loggable token, are written.
     *
     * @return true if d() writes, false otherwise
     */
    public static boolean isDebugEnabled() {
        return isEnabled(ApproovLogLevel.DEBUG);
    }

    /**
     * Gets the URL as it may be logged: the scheme, host, any port and the path, without the user
     * information, query or fragment, which may carry personal data or a substituted secure string.
     *
     * @param url is the URL as the stack sends it
     * @return the loggable form of the URL
     */
    public static String loggableURL(String url) {
        if (url == null)
            return null;

        // drop any query or fragment
        int end = url.length();
        for (char c : new char[] {'?', '#'}) {
            int i = url.indexOf(c);
            if ((i >= 0) && (i < end))
                end = i;
        }
        String base = url.substring(0, end);

        // drop any user information from the authority
        int schemeEnd = base.indexOf("://");
        if (schemeEnd < 0)
            return base;
        int authorityStart = schemeEnd + 3;
        int authorityEnd = base.indexOf('/', authorityStart);
        if (authorityEnd < 0)
            authorityEnd = base.length();
        int at = base.lastIndexOf('@', authorityEnd - 1);
        if (at >= authorityStart)
            base = base.substring(0, authorityStart) + base.substring(at + 1);
        return base;
    }

    /**
     * Writes an error line at ERROR and above.
     *
     * @param tag is the logging tag
     * @param message is the message to be logged
     */
    public static void e(String tag, String message) {
        if (isEnabled(ApproovLogLevel.ERROR))
            Log.e(tag, message);
    }

    /**
     * Writes an error line with its throwable at ERROR and above.
     *
     * @param tag is the logging tag
     * @param message is the message to be logged
     * @param throwable is the throwable to be logged with the message
     */
    public static void e(String tag, String message, Throwable throwable) {
        if (isEnabled(ApproovLogLevel.ERROR))
            Log.e(tag, message, throwable);
    }

    /**
     * Writes a warning line at WARNING and above.
     *
     * @param tag is the logging tag
     * @param message is the message to be logged
     */
    public static void w(String tag, String message) {
        if (isEnabled(ApproovLogLevel.WARNING))
            Log.w(tag, message);
    }

    /**
     * Writes an information line at INFO and above.
     *
     * @param tag is the logging tag
     * @param message is the message to be logged
     */
    public static void i(String tag, String message) {
        if (isEnabled(ApproovLogLevel.INFO))
            Log.i(tag, message);
    }

    /**
     * Writes a debug line at DEBUG.
     *
     * @param tag is the logging tag
     * @param message is the message to be logged
     */
    public static void d(String tag, String message) {
        if (isEnabled(ApproovLogLevel.DEBUG))
            Log.d(tag, message);
    }
}
