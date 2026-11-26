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

import org.chromium.net.CronetEngine;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.net.ssl.SSLPeerUnverifiedException;

import okio.ByteString;

/**
 * PinnedCronetEngines provides pinned Cronet engines, one generation per Approov pin set. Cronet takes
 * pins only when an engine is built and Approov's pins change while the app runs, so a pin change builds
 * a new engine. No pin verdict is kept, as Cronet checks each connection against the pins of its own host
 * in the current generation. The first generation is built for the first request, after its token fetch,
 * so pins delivered by that fetch apply to its first connection.
 * <p>
 * Each generation comes from a fresh real builder with your app's recorded settings replayed, so the pins
 * of one generation never accumulate into the next, and has its own storage directory, since Cronet
 * forbids two live engines sharing one disk cache. It disables Cronet's pin bypass for locally installed
 * trust anchors and pins each Approov domain exactly, falling back to the "*" Managed Trust Roots if a
 * domain has no pins of its own. A host Cronet refuses as a pin host, such as an IP literal, is left to OS
 * trust, although cleartext to it still fails. Cronet's Java fallback engine, which accepts pins but never
 * checks them, is only used for hosts without pins.
 * <p>
 * No pins means OS trust only. Pins that cannot be read once protection is enabled, or a pin that is not a
 * base64 SHA-256 hash, fail every connection closed with SSLPeerUnverifiedException, as the OkHttp adapter
 * does. A request holds its generation from the moment it is chosen until its real request has started,
 * and retired generations are shut down once no request holds them and their requests have finished.
 */
final class PinnedCronetEngines implements CronetEngineSource {
    // BuilderSource supplies a new real builder carrying your app's settings for each generation
    interface BuilderSource {
        CronetEngine.Builder newBuilder();
    }

    // PinSource supplies the Approov pins as domains to base64 SHA-256 SPKI hashes, where "*" is the Managed
    // Trust Roots
    interface PinSource {
        Map<String, List<String>> pins() throws Exception;
    }

    // Diagnostics receives the diagnostics, which go to the Android log in production and a recorder in JVM tests
    interface Diagnostics {
        void info(String message);

        void warning(String message);

        void error(String message);
    }

    // kinds of Cronet engine, of which only NATIVE is known to enforce the pins
    enum EngineKind { NATIVE, JAVA_FALLBACK, OTHER }

    // bundled native Cronet's engine, whose name survives R8 through consumer-rules.pro
    static final String NATIVE_ENGINE = "org.chromium.net.impl.CronetUrlRequestContext";

    // Cronet's HttpURLConnection backed fallback engine, which accepts pins but never checks them
    private static final String JAVA_ENGINE = "org.chromium.net.impl.JavaCronetEngine";

    // version string prefix of the fallback engine, which identifies it if its class was renamed
    private static final String JAVA_ENGINE_VERSION_PREFIX = "CronetHttpURLConnection/";

    // the message of CronetUrlRequestContext for a request made after shutdown()
    static final String SHUT_DOWN = "Engine is shut down.";

    // size of a public-key-sha256 pin, which is a SHA-256 hash
    private static final int SHA256_BYTES = 32;

    // Generation is one real engine built for one pin set
    private static final class Generation {
        // the real engine
        final CronetEngine engine;

        // the fingerprint of the pin set the engine was built for
        final String fingerprint;

        // the hosts the generation has pins for
        final Set<String> hostsWithPins;

        // false for Cronet's Java fallback engine, which accepts pins but never checks them
        final boolean pinsEnforced;

        // the storage directory of the generation, or null if none is set
        final File dir;

        // the number of the generation, used in the logs
        final int number;

        // the number of requests holding this generation until their real request has started
        int leases;

        // true once a deferred shutdown has been logged
        boolean deferralLogged;

        Generation(CronetEngine engine, String fingerprint, Set<String> hostsWithPins, boolean pinsEnforced, File dir,
                int number) {
            this.engine = engine;
            this.fingerprint = fingerprint;
            this.hostsWithPins = hostsWithPins;
            this.pinsEnforced = pinsEnforced;
            this.dir = dir;
            this.number = number;
        }
    }

    // source of fresh real builders with your app's settings
    private final BuilderSource builders;

    // source of the Approov pins
    private final PinSource pinSource;

    // directory under which each generation gets its own storage directory, or null for none
    private final File storageRoot;

    // destination of the diagnostics
    private final Diagnostics log;

    // lock guarding the generations
    private final Object lock = new Object();

    // the current generation, or null if none has been built
    private Generation current;

    // generations replaced by a pin change or shutdown that are not yet shut down
    private final List<Generation> retired = new ArrayList<>();

    // count of the generations built, used to number them
    private int counter;

    // true once your app has called shutdown()
    private boolean shutDown;

    // the network every generation is bound to, or UNBIND_NETWORK_HANDLE for the default network
    private long boundNetwork = CronetEngine.UNBIND_NETWORK_HANDLE;

    // hosts already warned of as listed with no pins while the Managed Trust Roots are empty, across generations
    private final Set<String> warnedOsTrustOnlyHosts = new HashSet<>();

    // no engine is built until one is needed
    PinnedCronetEngines(BuilderSource builders, PinSource pinSource, File storageRoot, Diagnostics log) {
        this.builders = builders;
        this.pinSource = pinSource;
        this.storageRoot = storageRoot;
        this.log = log;
    }

    @Override
    public Lease acquire() throws IOException {
        synchronized (lock) {
            Generation gen = currentGeneration();
            gen.leases++;

            // a request started before shutdown() still runs, on a generation retired at once so that the
            // deferred shutdown covers it
            if (shutDown) {
                current = null;
                if (!retired.contains(gen))
                    retired.add(gen);
            }
            return new GenerationLease(gen);
        }
    }

    @Override
    public CronetEngine current() throws IOException {
        synchronized (lock) {
            if (shutDown)
                throw new IllegalStateException(SHUT_DOWN);
            return currentGeneration().engine;
        }
    }

    @Override
    public boolean isShutDown() {
        synchronized (lock) {
            return shutDown;
        }
    }

    @Override
    public void bindToNetwork(long networkHandle) {
        synchronized (lock) {
            boundNetwork = networkHandle;
            if (current != null)
                current.engine.bindToNetwork(networkHandle);
        }
    }

    // builds a new generation if the pins changed; must be called holding the lock
    private Generation currentGeneration() throws IOException {
        shutDownRetired();

        // read the pins
        Map<String, List<String>> pins;
        try {
            pins = pinSource.pins();
        } catch (Exception e) {
            // pins that cannot be read once protection is enabled fail the connection closed, with the platform
            // pinning exception
            log.error("Approov pinning: pins unavailable: " + e);
            SSLPeerUnverifiedException failure = new SSLPeerUnverifiedException("Approov pinning: pins unavailable");
            failure.initCause(e);
            throw failure;
        }
        if (pins == null)
            pins = Collections.emptyMap();

        // we only build a new generation if the pin set has changed
        String fingerprint = fingerprint(pins);
        Generation gen = current;
        if ((gen != null) && gen.fingerprint.equals(fingerprint))
            return gen;
        Generation next = build(pins, fingerprint);
        if (gen != null)
            retired.add(gen);
        current = next;
        shutDownRetired();
        return next;
    }

    // for tests and diagnostics, as a request checks the generation it holds
    boolean isPinned(String host) {
        synchronized (lock) {
            return (current != null) && current.hostsWithPins.contains(normalizedHost(host));
        }
    }

    @Override
    public void shutdown() {
        synchronized (lock) {
            shutDown = true;
            if (current != null)
                retired.add(current);
            current = null;
            shutDownRetired();
        }
    }

    // fails if a pin is not a base64 SHA-256 hash
    private Generation build(Map<String, List<String>> pins, String fingerprint) throws SSLPeerUnverifiedException {
        List<String> wildcards = new ArrayList<>();
        Map<String, List<String>> normalized = normalizedPins(pins, wildcards);
        List<String> managedRoots = normalized.containsKey("*")
                ? normalized.get("*") : Collections.<String>emptyList();
        // every pin is checked before a Cronet builder is made, as a pin that is not a base64 SHA-256 hash
        // cannot be applied and fails closed rather than leaving its host, valid pins included, on OS trust
        Map<String, Set<byte[]>> decoded = new LinkedHashMap<>();
        List<String> unusable = new ArrayList<>();
        List<String> osTrustOnly = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : normalized.entrySet()) {
            String host = entry.getKey();
            if (host.equals("*"))
                continue;
            List<String> effective = entry.getValue().isEmpty() ? managedRoots : entry.getValue();
            // with no pins and no Managed Trust Roots any certificate OS trust accepts is valid, warned once per
            // host below
            if (effective.isEmpty()) {
                osTrustOnly.add(host);
                continue;
            }
            Set<byte[]> hashes = decode(effective);
            if (hashes == null)
                unusable.add(host);
            else
                decoded.put(host, hashes);
        }
        Collections.sort(osTrustOnly);
        for (String host : osTrustOnly) {
            if (warnedOsTrustOnlyHosts.add(host))
                log.warning("Approov domain " + host + ": no pins and no managed trust roots, OS trust only");
        }
        if (!unusable.isEmpty()) {
            Collections.sort(unusable);
            String message = "Approov pins cannot be applied: a pin for " + join(unusable)
                    + " is not a base64 SHA-256 hash";
            log.error(message + ", every connection fails until the pins are corrected");
            throw new SSLPeerUnverifiedException(message);
        }

        // make the builder with its own storage directory and no pin bypass for local trust anchors
        int number = ++counter;
        File dir = null;
        if (storageRoot != null) {
            dir = new File(storageRoot, "gen-" + number);
            deleteRecursively(dir);
            dir.mkdirs();
        }
        CronetEngine.Builder builder = builders.newBuilder();
        if (dir != null)
            builder.setStoragePath(dir.getAbsolutePath());
        builder.enablePublicKeyPinningBypassForLocalTrustAnchors(false);

        // add the pins for each host
        List<String> pinned = new ArrayList<>();
        Set<String> withPins = new HashSet<>();
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<String, Set<byte[]>> entry : decoded.entrySet()) {
            String host = entry.getKey();
            withPins.add(host);
            try {
                builder.addPublicKeyPins(host, entry.getValue(), false, EXPIRY);
                pinned.add(host);
            } catch (IllegalArgumentException e) {
                // the pins are valid, so Cronet refused the host as an IP literal or a non-STD3 name and it
                // stays reachable on OS trust, reported below
                skipped.add(host + " (" + (isIpLiteral(host) ? "IP literal" : "rejected by Cronet as a pin host") + ")");
            }
        }
        if (!skipped.isEmpty()) {
            Collections.sort(skipped);
            log.error("generation " + number + ": Cronet cannot pin these hosts, so they are not Approov-pinned on "
                    + "Android and use OS trust only: " + join(skipped));
        }
        if (!wildcards.isEmpty()) {
            Collections.sort(wildcards);
            log.error("generation " + number + ": wildcard pin keys ignored: " + wildcards);
        }

        // build the engine, bound to your app's network if it chose one, and check what kind it is
        CronetEngine engine = builder.build();
        if (boundNetwork != CronetEngine.UNBIND_NETWORK_HANDLE)
            engine.bindToNetwork(boundNetwork);
        String version;
        try {
            version = engine.getVersionString();
        } catch (RuntimeException e) {
            version = "";
        }
        boolean pinsEnforced = true;
        switch (engineKind(engine.getClass(), version)) {
            case JAVA_FALLBACK:
                // used, but never for a host with pins, as its requests fail closed in checkConnection, while
                // hosts without pins go out on OS trust as on any engine
                pinsEnforced = false;
                if (!withPins.isEmpty()) {
                    List<String> refused = new ArrayList<>(withPins);
                    Collections.sort(refused);
                    log.error("generation " + number + ": Cronet Java engine does not enforce pins, requests "
                            + "fail for pinned hosts: " + join(refused));
                }
                break;
            case OTHER:
                log.error("generation " + number + ": Cronet engine " + engine.getClass().getName() + " " + version
                        + " is not bundled native Cronet, pin enforcement is not verified");
                break;
            default:
                break;
        }
        Collections.sort(pinned);
        log.info("generation " + number + " built: " + pinned.size() + " pinned host(s) " + pinned
                + ", fingerprint " + fingerprint.substring(0, 12) + ", engine " + engine.getClass().getName()
                + " " + version);
        return new Generation(engine, fingerprint, withPins, pinsEnforced, dir, number);
    }

    // GenerationLease is a request's hold on a generation
    private final class GenerationLease implements Lease {
        // the generation held
        private final Generation gen;

        // true once the lease has been released
        private boolean released;

        GenerationLease(Generation gen) {
            this.gen = gen;
        }

        @Override
        public CronetEngine engine() {
            return gen.engine;
        }

        @Override
        public void checkConnection(String scheme, String host) throws SSLPeerUnverifiedException {
            if (!gen.hostsWithPins.contains(normalizedHost(host)))
                return;
            if (!"https".equals(scheme))
                throw new SSLPeerUnverifiedException("Approov pinning: cleartext connection to pinned host " + host);
            if (!gen.pinsEnforced) {
                throw new SSLPeerUnverifiedException("Cronet's Java fallback engine cannot enforce the Approov pins "
                        + "for " + host);
            }
        }

        @Override
        public void release() {
            synchronized (lock) {
                if (released)
                    return;
                released = true;
                gen.leases--;
                shutDownRetired();
            }
        }
    }

    // shuts down retired generations that no request holds and Cronet allows; must be called holding the lock
    private void shutDownRetired() {
        Iterator<Generation> it = retired.iterator();
        while (it.hasNext()) {
            Generation gen = it.next();
            if (gen.leases > 0) {
                // a request is between choosing this generation and starting on it
                logDeferral(gen);
                continue;
            }
            try {
                gen.engine.shutdown();
                if (gen.dir != null)
                    deleteRecursively(gen.dir);
                it.remove();
                log.info("generation " + gen.number + " retired and shut down");
            } catch (IllegalStateException e) {
                // Cronet refuses while requests are running
                logDeferral(gen);
            }
        }
    }

    // logs once per generation
    private void logDeferral(Generation gen) {
        if (!gen.deferralLogged) {
            gen.deferralLogged = true;
            log.info("generation " + gen.number + " shutdown deferred: requests in flight");
        }
    }

    // null if a pin is not a base64 32-byte hash, which Cronet would refuse
    private static Set<byte[]> decode(List<String> pins) {
        Set<byte[]> out = new HashSet<>();
        for (String pin : pins) {
            ByteString bytes = (pin == null) ? null : ByteString.decodeBase64(pin);
            if ((bytes == null) || (bytes.size() != SHA256_BYTES))
                return null;
            out.add(bytes.toByteArray());
        }
        return out;
    }

    // independent of the order of domains and pins
    private static String fingerprint(Map<String, List<String>> pins) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : new TreeMap<>(pins).entrySet()) {
            List<String> sorted = new ArrayList<>((entry.getValue() == null)
                    ? Collections.<String>emptyList() : entry.getValue());
            Collections.sort(sorted);
            canonical.append(entry.getKey()).append('=').append(join(sorted)).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return ByteString.of(digest).hex();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // lowercased and with one trailing dot removed, as pin keys are matched
    static String normalizedHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    // merges colliding spellings, moves wildcard keys into wildcards and keeps the "*" Managed Trust Roots entry
    static Map<String, List<String>> normalizedPins(Map<String, List<String>> raw, List<String> wildcards) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(raw.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            List<String> own = (raw.get(key) == null) ? Collections.<String>emptyList() : raw.get(key);
            if (key.equals("*")) {
                out.put("*", new ArrayList<>(own));
            } else if (key.indexOf('*') >= 0) {
                wildcards.add(key);
            } else {
                String host = normalizedHost(key);
                List<String> merged = out.get(host);
                if (merged == null) {
                    merged = new ArrayList<>();
                    out.put(host, merged);
                }
                for (String pin : own) {
                    if (!merged.contains(pin))
                        merged.add(pin);
                }
            }
        }
        return out;
    }

    // only bundled native Cronet is known to enforce pins; the Java fallback is found by name or version string
    static EngineKind engineKind(Class<?> engineClass, String version) {
        // any other engine is used rather than refused, as Android HttpEngine and Play Services Cronet forward
        // pins to a Chromium network stack and Google can switch apps to HttpEngine with a server-side flag
        if (engineClass.getName().equals(JAVA_ENGINE) || engineClass.getSimpleName().equals("JavaCronetEngine")
                || ((version != null) && version.startsWith(JAVA_ENGINE_VERSION_PREFIX)))
            return EngineKind.JAVA_FALLBACK;
        if (engineClass.getName().equals(NATIVE_ENGINE))
            return EngineKind.NATIVE;
        return EngineKind.OTHER;
    }

    // Cronet's own test (digits and dots) plus IPv6 literals, which fail its STD3 check
    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0)
            return true;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!(Character.isDigit(c) || (c == '.')))
                return false;
        }
        return true;
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(value);
        }
        return sb.toString();
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children)
                deleteRecursively(child);
        }
        file.delete();
    }

    // expiry of the pins set on Cronet, far in the future as Approov rotates pins through configuration updates
    private static final Date EXPIRY;

    static {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.YEAR, 10);
        EXPIRY = calendar.getTime();
    }
}
