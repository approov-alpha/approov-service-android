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

import android.net.http.X509TrustManagerExtensions;
import android.util.Base64;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * ApproovPinningVerifier performs Approov dynamic pinning for stacks that support a HostnameVerifier
 * (HttpsURLConnection, Volley via HurlStack and gRPC via OkHttpChannelBuilder). It enhances the
 * HostnameVerifier your TLS implementation normally uses: the HostnameVerifier passed to the constructor is
 * still called first and pinning is only applied if it passes, so this can only be more secure. This pins
 * to the SHA256 of the public key of any certificate in the chain that vouches for the server's leaf, never
 * of any other certificate the peer presented. That chain is the one the platform trust manager validated
 * or, if the platform does not trust the chain or cannot be asked, the chain walked from the leaf by
 * signature. Host names are matched without regard to case or a trailing dot. Note that this uses the
 * current live Approov pins so is immediately updated if there is a configuration update to the app.
 */
public final class ApproovPinningVerifier implements HostnameVerifier {
    // logging tag
    private static final String TAG = "ApproovPinVerifier";

    // the longest chain walked from the leaf, as OkHttp's chain cleaner allows
    private static final int MAX_CHAIN_LENGTH = 10;

    // the hosts already warned of as listed with no pins while the managed trust roots are empty, which is
    // per process as a verifier is made per connection
    private static final Set<String> warnedOsTrustOnlyHosts = new HashSet<>();

    // HostnameVerifier that would normally be used
    private final HostnameVerifier delegate;

    // trust anchors used to resolve the trust root when the peer does not present it
    private final Set<TrustAnchor> trustAnchors;

    // the source of the live Approov pins
    private final PinProvider pinProvider;

    // the source of the chain the platform trust manager validated
    private final PlatformChainValidator platformValidator;

    /**
     * Constructs an ApproovPinningVerifier which delegates the initial verify to a user defined
     * HostnameVerifier before applying pinning on top.
     *
     * @param delegate is the HostnameVerifier to apply before the pinning
     */
    public ApproovPinningVerifier(HostnameVerifier delegate) {
        this(delegate, ApproovService::getPins, PlatformTrust::trustAnchors, PlatformTrust::validatedChain);
    }

    // for tests only
    ApproovPinningVerifier(HostnameVerifier delegate,
            PinProvider pinProvider, TrustAnchorProvider trustAnchorProvider) {
        this(delegate, pinProvider, trustAnchorProvider, PlatformTrust::validatedChain);
    }

    // for tests only
    ApproovPinningVerifier(HostnameVerifier delegate,
            PinProvider pinProvider, TrustAnchorProvider trustAnchorProvider,
            PlatformChainValidator platformValidator) {
        if (delegate == null)
            throw new IllegalArgumentException("delegate verifier must not be null");
        if (pinProvider == null)
            throw new IllegalArgumentException("pin provider must not be null");
        if (trustAnchorProvider == null)
            throw new IllegalArgumentException("trust anchor provider must not be null");
        if (platformValidator == null)
            throw new IllegalArgumentException("platform chain validator must not be null");
        this.delegate = delegate;
        this.pinProvider = pinProvider;
        this.platformValidator = platformValidator;
        Set<TrustAnchor> anchors = trustAnchorProvider.getTrustAnchors();
        this.trustAnchors = anchors == null
                ? Collections.<TrustAnchor>emptySet()
                : Collections.unmodifiableSet(new HashSet<>(anchors));
    }

    /**
     * PlatformTrust holds the platform default trust, its X509TrustManager and trust anchors, read once per
     * process rather than for every connection.
     */
    private static final class PlatformTrust {
        // the platform default trust manager, or null if there is none
        private static X509TrustManager trustManager;

        // the trust anchors of the platform default trust
        private static Set<TrustAnchor> anchors;

        // true once the platform default trust has been read
        private static boolean loaded;

        // reads the platform default trust, once per process
        private static synchronized void load() {
            if (loaded)
                return;
            loaded = true;

            // the first X509TrustManager is the platform trust manager, and every one adds its accepted issuers
            Set<TrustAnchor> found = new HashSet<>();
            try {
                TrustManagerFactory trustManagerFactory =
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init((KeyStore) null);
                for (TrustManager manager : trustManagerFactory.getTrustManagers()) {
                    if (manager instanceof X509TrustManager) {
                        if (trustManager == null)
                            trustManager = (X509TrustManager) manager;
                        for (X509Certificate cert : ((X509TrustManager) manager).getAcceptedIssuers())
                            found.add(new TrustAnchor(cert, null));
                    }
                }
            } catch (Exception e) {
                ApproovLog.e(TAG, "Unable to read the platform trust store", e);
            }
            anchors = Collections.unmodifiableSet(found);
        }

        static synchronized Set<TrustAnchor> trustAnchors() {
            load();
            return anchors;
        }

        // null if there is no platform trust manager or X509TrustManagerExtensions cannot be used with it
        static List<X509Certificate> validatedChain(X509Certificate[] presented, String host)
                throws CertificateException {
            X509TrustManager manager;
            synchronized (PlatformTrust.class) {
                load();
                manager = trustManager;
            }
            if (manager == null)
                return null;
            X509TrustManagerExtensions extensions;
            try {
                extensions = new X509TrustManagerExtensions(manager);
            } catch (RuntimeException e) {
                return null;
            }
            // the authentication type is not used for server certificate checks
            return extensions.checkServerTrusted(presented, "RSA", host);
        }
    }

    // the base64 of the SHA-256 of the SubjectPublicKeyInfo
    private static String hashPublicKey(PublicKey publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.encodeToString(digest.digest(publicKey.getEncoded()), Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available on Android
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // lowercase and without a single trailing dot, as "example.com." is the same host as "example.com"
    static String normalizeHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    /**
     * Verifies the host with the delegate and then against the Approov pins. Any failure to make the pin
     * check once Approov protection is enabled, such as the SDK failing to give the pins or a peer whose
     * certificates cannot be read, rejects the host, so the stack fails the connection with
     * SSLPeerUnverifiedException and the cause is logged.
     *
     * @param hostname is the host name to be verified
     * @param session is the SSL session of the connection
     * @return true if the host is verified, false otherwise
     */
    @Override
    public boolean verify(String hostname, SSLSession session) {
        // check the delegate function first and only proceed if it passes
        if (!delegate.verify(hostname, session))
            return false;

        // a HostnameVerifier cannot throw an IOException and a RuntimeException would escape the request path
        // of the stack, so any failure to make the pin check rejects the host
        try {
            return verifyPins(hostname, session);
        } catch (ApproovException | SSLException | RuntimeException e) {
            ApproovLog.e(TAG, "Pinning check failed for " + hostname + ": " + e);
            return false;
        }
    }

    // true if the peer matches the pins or the host has none
    private boolean verifyPins(String hostname, SSLSession session) throws ApproovException, SSLException {
        // before initialize() and in bypass mode we apply no Approov pinning and do not ask the SDK for pins,
        // even if another caller initialized it, so only the OS trust already checked by the delegate applies
        if (!ApproovService.isApproovProtectionEnabled()) {
            ApproovLog.d(TAG, "Approov protection not enabled: no Approov pins for " + hostname);
            return true;
        }

        Map<String, List<String>> allPins;
        try {
            allPins = pinProvider.getPins("public-key-sha256");
        } catch (ApproovException | RuntimeException e) {
            ApproovLog.e(TAG, "Approov pinning: pins unavailable for " + hostname + ": " + e);
            return false;
        }
        if (allPins == null) {
            // the installation has never received a configuration, so it holds no pins and the connection
            // proceeds on the OS trust only, which the delegate has already checked
            ApproovLog.w(TAG, "No pinning information available for " + hostname + ": OS trust only");
            return true;
        }
        Set<String> hostPins;
        try {
            hostPins = pinsFor(normalizeHost(hostname), allPins);
        } catch (ApproovException e) {
            ApproovLog.e(TAG, e.getMessage());
            return false;
        }

        // if there are no pins then we accept any certificate
        if (hostPins.isEmpty()) {
            ApproovLog.d(TAG, "No pins for " + hostname + ": OS trust only");
            return true;
        }

        // accept if any certificate that vouches for the leaf matches a pin
        for (X509Certificate cert : vouchingChain(hostname, session)) {
            if (hostPins.contains(hashPublicKey(cert.getPublicKey())))
                return true;
        }

        // the connection is rejected
        ApproovLog.e(TAG, "Approov pinning: no pin matches for " + hostname);
        return false;
    }

    // its own pins, or the "*" managed trust root pins if listed with none; a host not listed has none
    private static Set<String> pinsFor(String host, Map<String, List<String>> allPins) throws ApproovException {
        // collect the pins of every domain that normalizes to the host
        boolean listed = false;
        List<String> pins = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : allPins.entrySet()) {
            String domain = entry.getKey();
            if ((domain == null) || domain.equals("*") || !normalizeHost(domain).equals(host))
                continue;
            listed = true;
            if (entry.getValue() == null)
                throw new ApproovException("Approov pinning: pins unavailable for " + host);
            pins.addAll(entry.getValue());
        }
        Set<String> hostPins = new HashSet<>();
        if (!listed)
            return hostPins;
        if (pins.isEmpty()) {
            // if there are no pins associated with the hostname domain then we use any pins associated with the
            // "*" domain for managed trust roots (note we do not apply this to domains that are not added at all)
            List<String> managed = allPins.get("*");
            if (managed != null)
                pins = managed;
        }
        for (String pin : pins) {
            if (pin != null)
                hostPins.add(pin);
        }
        if (hostPins.isEmpty())
            warnOsTrustOnly(host);
        return hostPins;
    }

    // once per host; falling back to OS trust is a valid development setup, but not a silent one
    static void warnOsTrustOnly(String host) {
        synchronized (warnedOsTrustOnlyHosts) {
            if (!warnedOsTrustOnlyHosts.add(host))
                return;
        }
        ApproovLog.w(TAG, "Approov domain " + host + ": no pins and no managed trust roots, OS trust only");
    }

    // for tests only
    static void resetForTesting() {
        synchronized (warnedOsTrustOnlyHosts) {
            warnedOsTrustOnlyHosts.clear();
        }
    }

    // the chain the platform validated or, if it does not trust it or cannot be asked, walked from the leaf
    private List<X509Certificate> vouchingChain(String hostname, SSLSession session) throws SSLException {
        // get the presented certificates, which must all be X.509
        Certificate[] peer = session.getPeerCertificates();
        if ((peer == null) || (peer.length == 0))
            throw new SSLException("no peer certificates");
        X509Certificate[] presented = new X509Certificate[peer.length];
        for (int i = 0; i < peer.length; i++) {
            if (!(peer[i] instanceof X509Certificate))
                throw new SSLException("peer certificate is not X.509");
            presented[i] = (X509Certificate) peer[i];
        }

        // prefer the chain the platform trust manager validated
        try {
            List<X509Certificate> validated = platformValidator.validatedChain(presented, hostname);
            if ((validated != null) && !validated.isEmpty())
                return validated;
        } catch (CertificateException e) {
            // the app's own trust manager accepted a chain the platform trust store does not, such as a
            // private CA, so we fall back to the chain that vouches by signature
            ApproovLog.d(TAG, "Platform trust does not validate the chain for " + hostname
                    + ": matching pins on the signing chain");
        }
        return signingChain(presented);
    }

    /**
     * Walks the chain of issuers from the leaf. Each next certificate is a presented certificate or trust anchor
     * whose subject is the issuer of the current certificate, which is a CA, and whose key verifies the
     * signature of the current certificate. A presented certificate that signed nothing on the path is never in it.
     *
     * @param presented are the certificates the peer presented, leaf first
     * @return the chain of issuers, leaf first
     */
    private List<X509Certificate> signingChain(X509Certificate[] presented) {
        List<X509Certificate> chain = new ArrayList<>();
        X509Certificate current = presented[0];
        chain.add(current);
        while (chain.size() < MAX_CHAIN_LENGTH) {
            // a self-signed certificate ends the chain
            if (signedBy(current, current))
                break;

            // look for the issuer among the presented certificates first and then the trust anchors
            X509Certificate issuer = null;
            for (int i = 1; (i < presented.length) && (issuer == null); i++) {
                if (!chain.contains(presented[i]) && isCa(presented[i]) && signedBy(current, presented[i]))
                    issuer = presented[i];
            }
            for (TrustAnchor anchor : trustAnchors) {
                if (issuer != null)
                    break;
                X509Certificate anchorCert = anchor.getTrustedCert();
                // a trust anchor is a CA by being trusted, whatever its extensions
                if ((anchorCert != null) && !chain.contains(anchorCert) && signedBy(current, anchorCert))
                    issuer = anchorCert;
            }
            if (issuer == null)
                break;
            chain.add(issuer);
            current = issuer;
        }
        return chain;
    }

    private static boolean isCa(X509Certificate cert) {
        if (cert.getBasicConstraints() < 0)
            return false;
        boolean[] keyUsage = cert.getKeyUsage();
        // keyCertSign is bit 5 of the key usage extension, when present
        return (keyUsage == null) || ((keyUsage.length > 5) && keyUsage[5]);
    }

    // names the issuer as its issuer and carries its signature
    private static boolean signedBy(X509Certificate cert, X509Certificate issuer) {
        try {
            if ((cert.getIssuerX500Principal() == null)
                    || !cert.getIssuerX500Principal().equals(issuer.getSubjectX500Principal()))
                return false;
            cert.verify(issuer.getPublicKey());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
