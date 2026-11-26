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

import android.content.Context;

import com.criticalblue.approovsdk.Approov;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

// DefaultApproovSdkFacade delegates directly to the Approov SDK
final class DefaultApproovSdkFacade implements ApproovSdkFacade {
    // Approov.getServerAdjustedCurrentTimeMS() exists from SDK 3.7.0; it is looked up once, so the package uses it
    // when the SDK has it and the device clock otherwise
    private static final Method SERVER_ADJUSTED_TIME = serverAdjustedTimeMethod();

    private static Method serverAdjustedTimeMethod() {
        try {
            return Approov.class.getMethod("getServerAdjustedCurrentTimeMS");
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    // true if the SDK provides the server adjusted time
    static boolean hasServerAdjustedTime() {
        return SERVER_ADJUSTED_TIME != null;
    }

    @Override
    public boolean initialize(Context context, String config, String updateConfig, String comment) {
        return Approov.initialize(context, config, updateConfig, comment);
    }

    @Override
    public void setUserProperty(String property) {
        Approov.setUserProperty(property);
    }

    @Override
    public void setDevKey(String devKey) {
        Approov.setDevKey(devKey);
    }

    @Override
    public void fetchApproovToken(Approov.TokenFetchCallback callback, String url) {
        Approov.fetchApproovToken(callback, url);
    }

    @Override
    public Approov.TokenFetchResult fetchApproovTokenAndWait(String url) {
        return Approov.fetchApproovTokenAndWait(url);
    }

    @Override
    public Approov.TokenFetchResult fetchSecureStringAndWait(String key, String newDefinition) {
        return Approov.fetchSecureStringAndWait(key, newDefinition);
    }

    @Override
    public Approov.TokenFetchResult fetchCustomJWTAndWait(String payload) {
        return Approov.fetchCustomJWTAndWait(payload);
    }

    @Override
    public String getDeviceID() {
        return Approov.getDeviceID();
    }

    @Override
    public void setDataHashInToken(String data) {
        Approov.setDataHashInToken(data);
    }

    @Override
    public String getAccountMessageSignature(String message) {
        return Approov.getAccountMessageSignature(message);
    }

    @Override
    public String getInstallMessageSignature(String message) {
        return Approov.getInstallMessageSignature(message);
    }

    @Override
    public Map<String, List<String>> getPins(String pinType) {
        return Approov.getPins(pinType);
    }

    @Override
    public void setInstallAttrsInToken(String attributes) {
        Approov.setInstallAttrsInToken(attributes);
    }

    @Override
    public String fetchConfig() {
        return Approov.fetchConfig();
    }

    @Override
    public long getServerAdjustedCurrentTimeMS() {
        if (SERVER_ADJUSTED_TIME == null)
            return System.currentTimeMillis();
        try {
            return (Long) SERVER_ADJUSTED_TIME.invoke(null);
        } catch (InvocationTargetException e) {
            // the SDK's own failure, such as not being initialized, is reported as the SDK reported it
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException)
                throw (RuntimeException) cause;
            throw new IllegalStateException("Approov SDK getServerAdjustedCurrentTimeMS failed: " + cause, cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Approov SDK getServerAdjustedCurrentTimeMS is not accessible: " + e, e);
        }
    }
}
