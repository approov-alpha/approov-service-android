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

package io.approov.service.android.okhttp;

import io.approov.service.android.ApproovLog;

import java.io.IOException;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.SSLPeerUnverifiedException;

import io.approov.service.android.ApproovException;
import io.approov.service.android.ApproovService;
import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.Handshake;
import okhttp3.Interceptor;

/**
 * OkHttpPins holds the Approov dynamic pins as an OkHttp CertificatePinner that is rebuilt whenever the
 * dynamic pin configuration changes, and checks a connection against the current pins for a host. Each
 * check uses the pins as they are at that time, so a pin update takes effect on the next request rather
 * than only on new connections. It does not decide whether a request is pinned: the caller makes that
 * decision and only calls check when pinning applies.
 */
class OkHttpPins {
    // logging tag
    private final static String TAG = "ApproovPinning";

    // the certificate pinner to use for pinning that may be rebuilt if there is a change in the pinning configuration,
    // which only ever holds pins that were read and built successfully, so a check that reads it while a rebuild
    // fails gets the last good pins and never an empty pinner
    private CertificatePinner certificatePinner;

    // true while the pinner holds no pins at all, as the SDK may return none until a token has been fetched,
    // in which case the pins are read again before each connection check
    private boolean pinsEmpty = true;

    // true while the last pins read that was written failed, in which case the pins are read again before each
    // connection check, and the check fails if they still cannot be read
    private boolean pinsFailed;

    // the Approov domains, lowercased, that have no pins of their own while the managed trust roots (the "*"
    // pin set) are empty or absent, so rely on OS trust only, and those of them already warned of
    private Set<String> osTrustOnlyHosts = Collections.emptySet();
    private final Set<String> warnedOsTrustOnlyHosts = new HashSet<>();

    // the number of pin reads started and the number of the read whose pins the pinner holds, so that a read
    // that started before the one that last wrote is stale and is not written over it
    private long pinReadsStarted;
    private long pinReadWritten;

    // no pins are read until the first connection check
    OkHttpPins() {
        certificatePinner = new CertificatePinner.Builder().build();
    }

    /**
     * Rebuilds the pinning configuration. This is called when the dynamic configuration changes, and the next
     * connection check uses the new pins.
     */
    void buildPins() {
        try {
            buildPinsChecked();
        } catch (ApproovException e) {
            // off the request path a pins failure is only logged and the next connection check reads the pins again
            ApproovLog.e(TAG, "Approov pins not built: " + e.getMessage());
        }
    }

    private void buildPinsChecked() throws ApproovException {
        // read outside this monitor to keep the lock order ApproovService then pinner
        boolean protectionEnabled = ApproovService.isApproovProtectionEnabled();
        long read;
        synchronized (this) {
            read = ++pinReadsStarted;
        }
        Map<String, List<String>> allPins = null;
        ApproovException failure = null;
        if (protectionEnabled) {
            // the SDK is only asked for pins once protection is enabled, and the empty pinner left before then
            // is rebuilt on the next connection check
            try {
                allPins = ApproovService.getPins("public-key-sha256");
            } catch (ApproovException e) {
                failure = e;
            }
        }
        synchronized (this) {
            // a newer read has already been applied so these pins are older, and are not written; a failure of
            // this read is still the caller's if the newer read failed too, so it is never swallowed
            if (read < pinReadWritten) {
                if ((failure != null) && pinsFailed)
                    throw failure;
                return;
            }
            pinReadWritten = read;
            if (failure == null) {
                try {
                    buildPinsLocked(allPins);
                } catch (RuntimeException e) {
                    // a pin or domain pattern the pinner rejects
                    failure = new ApproovException("Approov pins cannot be applied: " + e, e);
                }
            }

            // a failure keeps the last good pinner, so a check already past its own read never uses an empty
            // one, and marks the pins as failed, so the next check reads them again and fails closed if it cannot
            pinsFailed = (failure != null);
        }
        if (failure != null)
            throw failure;
    }

    private void buildPinsLocked(Map<String, List<String>> allPins) {
        // the pinner is built in full before anything is written, so a pin it rejects leaves the last good pins
        CertificatePinner.Builder pinBuilder = new CertificatePinner.Builder();
        boolean anyPins = false;
        Set<String> osTrustOnly = new HashSet<>();
        Set<String> pinnedHosts = new HashSet<>();
        if (allPins != null) {
            for (Map.Entry<String, List<String>> entry : allPins.entrySet()) {
                String domain = entry.getKey();
                if (!domain.equals("*")) {
                    // the * domain is for managed trust roots and should not be added directly
                    List<String> pins = entry.getValue();

                    // if there are no pins then we try and use any managed trust roots
                    if (pins.isEmpty() && (allPins.get("*") != null))
                        pins = allPins.get("*");

                    // with no pins and no managed trust roots the domain relies on OS trust only
                    if (pins.isEmpty())
                        osTrustOnly.add(normalizeHost(domain));
                    else
                        pinnedHosts.add(normalizeHost(domain));

                    // add the required pins for the domain
                    for (String pin : pins) {
                        pinBuilder = pinBuilder.add(normalizeHost(domain), "sha256/" + pin);
                        anyPins = true;
                    }
                }
            }
        }
        certificatePinner = pinBuilder.build();
        // pin keys match without regard to case, so a spelling of a host with no pins never hides the pins of
        // another spelling
        osTrustOnly.removeAll(pinnedHosts);
        osTrustOnlyHosts = osTrustOnly;
        pinsEmpty = !anyPins;
    }

    private static String normalizeHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    // once per host; OS trust only is a valid development setup, but not a silent one
    private void warnIfOsTrustOnly(String host) {
        String normalized = normalizeHost(host);
        synchronized (this) {
            if (!osTrustOnlyHosts.contains(normalized) || !warnedOsTrustOnlyHosts.add(normalized))
                return;
        }
        ApproovLog.w(TAG, "Approov domain " + normalized + ": no pins and no managed trust roots, OS trust only");
    }

    // so the first connection after a fetch delivers pins is pinned, and pins whose last read failed are read
    // again; of two reads at once, the later one wins
    private void rebuildPinsIfNeeded() throws ApproovException {
        boolean rebuild;
        synchronized (this) {
            rebuild = pinsEmpty || pinsFailed;
        }

        // the rebuild runs outside this monitor to keep the lock order ApproovService then pinner
        if (!rebuild)
            return;
        buildPinsChecked();

        // a newer read that failed while this one was made leaves the pins unavailable to this check too
        synchronized (this) {
            if (pinsFailed)
                throw new ApproovException("Approov pins not read: a concurrent read failed");
        }
    }

    // package-private so that tests can observe the pin reads
    synchronized CertificatePinner getCertificatePinner() {
        return certificatePinner;
    }

    /**
     * Checks the connection of the chain against the current pins for the host, returning normally when the
     * connection may be used and otherwise throwing SSLPeerUnverifiedException. This class never closes the
     * connection on failure, as OkHttp may have coalesced this host onto an HTTP/2 connection of another host,
     * and the next request on it is checked again. OkHttp itself discards the exchange of an attempt whose
     * network interceptor throws, which closes an HTTP/1.1 connection, while an HTTP/2 connection stays in use.
     *
     * @param chain is the network interceptor chain of the request
     * @param host is the host of the request
     * @throws IOException if the pins are unavailable, a cleartext connection reaches a pinned host or no
     * pin matches the peer certificates, always as an SSLPeerUnverifiedException
     */
    void check(Interceptor.Chain chain, String host) throws IOException {
        // the pins are read again while none exist or the last read failed, and if they cannot be read this is a
        // pinning failure reported with the platform pinning exception
        try {
            rebuildPinsIfNeeded();
        } catch (ApproovException e) {
            ApproovLog.e(TAG, "Approov pinning: pins unavailable for " + host + ": " + e);
            SSLPeerUnverifiedException failure = new SSLPeerUnverifiedException(
                    "Approov pinning: pins unavailable for " + host);
            failure.initCause(e);
            throw failure;
        }

        // pins are looked up per host ignoring case and one trailing dot, as on every stack, by normalizing
        // the host since OkHttp's CertificatePinner compares the strings as given
        String normalizedHost = normalizeHost(host);
        warnIfOsTrustOnly(normalizedHost);
        Connection connection = chain.connection();
        Handshake handshake = (connection != null) ? connection.handshake() : null;
        if (handshake == null) {
            // with no TLS handshake this is a cleartext connection, which must never reach a pinned host and
            // fails like a pin mismatch, while a host without pins is left to the app's own policy (Android's
            // cleartext traffic policy included)
            if (getCertificatePinner().findMatchingPins(normalizedHost).isEmpty())
                return;
            ApproovLog.w(TAG, "Approov pinning: cleartext connection to pinned host " + normalizedHost);
            throw new SSLPeerUnverifiedException(
                    "Approov pinning: cleartext connection to pinned host " + normalizedHost);
        }
        // check the peer certificates against the current pins for this host on every connection check, as a
        // cached verdict keyed by the TLS handshake would not take the host into account
        List<Certificate> certs = handshake.peerCertificates();
        try {
            getCertificatePinner().check(normalizedHost, certs);
        } catch (SSLPeerUnverifiedException e) {
            // OkHttp's message quotes the pins, so only the host is logged
            ApproovLog.e(TAG, "Approov pinning: no pin matches for " + normalizedHost);
            throw e;
        }
    }
}
