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

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.approov.util.universal.http.sfv.ByteSequenceItem;
import io.approov.util.universal.http.sfv.Dictionary;
import io.approov.util.universal.http.sfv.ListElement;
import io.approov.util.universal.sig.ComponentProvider;
import io.approov.util.universal.sig.SignatureBaseBuilder;
import io.approov.util.universal.sig.SignatureParameters;
import io.approov.util.universal.tink.subtle.EllipticCurves;

/**
 * ApproovDefaultMessageSigning performs HTTP message signing (RFC 9421) of the requests Approov protects, on
 * every networking stack, and provides the signature parameters factory API that configures it. As it
 * operates on ApproovRequestContext, the same configuration applies to requests made with OkHttp,
 * HttpsURLConnection, Volley and gRPC.
 * <p>
 * Message signing is not a service mutator, and installing a mutator never switches it on or off. It is on
 * by default and ApproovService.disableMessageSigning() switches it off. While it is on, every request
 * carrying the Approov token header is signed last, after the mutator's decisions, the secure string
 * substitutions and the mutator's processed request callback, so the signature covers what is sent. A request
 * is signed again whenever its protection is applied again, such as on a redirect within a protected domain.
 * <p>
 * The default factory from generateDefaultSignatureParametersFactory() produces both the install (ECDSA
 * P-256, with a per app installation key held in the device secure hardware) and the account (HMAC-SHA256,
 * with the shared account key) signatures, as the members "install" and "account" of the same Signature and
 * Signature-Input dictionaries over the same covered components. Both are produced so that a device without
 * secure hardware for the install key still yields a verifiable signature, and the backend chooses which it
 * verifies. A signature that cannot be produced is omitted and the request proceeds with the other one, or
 * unsigned. The only deliberate failures are configuration errors: a required body digest that cannot be
 * generated, or an unsupported signature algorithm.
 * <p>
 * Note that apps never construct this class, but pass a SignatureParametersFactory to either
 * ApproovService.enableMessageSigning() or ApproovService.putMessageSigningHostFactory() instead.
 */
public class ApproovDefaultMessageSigning {
    // logging tag
    private static final String TAG = "ApproovMsgSign";

    /**
     * Constant for the SHA-256 digest algorithm (used for body digests).
     */
    public static final String DIGEST_SHA256 = "sha-256";

    /**
     * Constant for the SHA-512 digest algorithm (used for body digests).
     */
    public static final String DIGEST_SHA512 = "sha-512";

    /**
     * Constant for the ECDSA P-256 with SHA-256 algorithm (used when signing with the install private key).
     */
    public final static String ALG_ES256 = "ecdsa-p256-sha256";

    /**
     * Constant for the HMAC with SHA-256 algorithm (used when signing with the account signing key).
     */
    public final static String ALG_HS256 = "hmac-sha256";

    /**
     * Signature dictionary member name for the install message signature.
     */
    public final static String SIG_ID_INSTALL = "install";

    /**
     * Signature dictionary member name for the account message signature.
     */
    public final static String SIG_ID_ACCOUNT = "account";

    // the factory used for every host without a factory of its own, or null to sign nothing, which is
    // volatile as it is read on the request threads
    private volatile SignatureParametersFactory defaultFactory;

    // factories for individual hosts, keyed by the lowercase host name with no port and no brackets for an
    // IPv6 address, read on the request threads
    private final Map<String, SignatureParametersFactory> hostFactories = new ConcurrentHashMap<>();

    // true once a missing install signature has been logged as a warning in this process, so that an account
    // or device that never has one does not log on every request
    private static final AtomicBoolean installMissingLogged = new AtomicBoolean();

    // true once a missing account signature has been logged as a warning in this process
    private static final AtomicBoolean accountMissingLogged = new AtomicBoolean();

    // true once a request that proceeded with no signature has been logged as an error in this process
    private static final AtomicBoolean unsignedLogged = new AtomicBoolean();

    // only ApproovService constructs one
    ApproovDefaultMessageSigning() {
    }

    // for unit tests only, so that the once per process logs can be written again
    static void resetForTesting() {
        installMissingLogged.set(false);
        accountMissingLogged.set(false);
        unsignedLogged.set(false);
    }

    @Override
    public String toString() {
        return "ApproovDefaultMessageSigning";
    }

    // for every host without a factory of its own; null signs nothing for such hosts
    ApproovDefaultMessageSigning setDefaultFactory(SignatureParametersFactory factory) {
        this.defaultFactory = factory;
        return this;
    }

    // host matched without regard to case or port; a null factory removes it so the default applies
    ApproovDefaultMessageSigning putHostFactory(String hostName, SignatureParametersFactory factory) {
        if (hostName == null)
            throw new IllegalArgumentException("hostName must not be null");
        String key = hostName.toLowerCase(Locale.ROOT);
        if (factory == null)
            hostFactories.remove(key);
        else
            hostFactories.put(key, factory);
        return this;
    }

    // the factory for every host without a factory of its own, or null
    SignatureParametersFactory getDefaultFactory() {
        return defaultFactory;
    }

    // the host's own factory, matched as by putHostFactory, or null if it has none
    SignatureParametersFactory getHostFactory(String hostName) {
        if (hostName == null)
            throw new IllegalArgumentException("hostName must not be null");
        return hostFactories.get(hostName.toLowerCase(Locale.ROOT));
    }

    // the host's factory if there is one, or else the default factory
    private SignatureParametersFactory factoryFor(ContextComponentProvider provider) {
        String host = provider.getHost();
        SignatureParametersFactory factory =
                (host != null) ? hostFactories.get(host.toLowerCase(Locale.ROOT)) : null;
        return (factory != null) ? factory : defaultFactory;
    }

    // the SDK's DER ES256 signature as the raw 64 byte r||s form RFC 9421 requires, accepting only strict DER
    static byte[] es256DerToRaw(byte[] der) {
        byte[] raw;
        byte[] canonical;
        try {
            raw = EllipticCurves.ecdsaDer2Ieee(der, 64);
            canonical = EllipticCurves.ecdsaIeee2Der(raw);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("Not an ASN.1 DER ES256 signature: " + e, e);
        }
        // ecdsaDer2Ieee checks the DER structure but not that r and s fit in 32 bytes, so we only accept input
        // that is the canonical encoding of the result
        if (!Arrays.equals(canonical, der)) {
            throw new IllegalArgumentException("Not an ASN.1 DER ES256 signature: integer exceeds 32 bytes");
        }
        return raw;
    }

    /**
     * Signs a request that the Approov request pipeline has protected. The request is only modified if it
     * carries the Approov token header and a factory applies to its host. A signature that cannot be
     * produced for any reason, such as an SDK failure, a URL a parser rejects or a failure in a custom
     * factory, is omitted and the request proceeds with the other signature, or unsigned. The only failures
     * that stop the request are the app's own configuration errors, which are IOExceptions so that every
     * network stack reports them as a failed request.
     *
     * @param context is the request as the pipeline and the service mutator left it
     * @param changes is the protection the pipeline applied to it
     * @throws RequiredBodyDigestException if a body digest configured as required cannot be generated
     * @throws ApproovException if there was a problem, such as an unsupported signature algorithm
     */
    void sign(ApproovRequestContext context, ApproovRequestMutations changes) throws ApproovException {
        if ((changes == null) || (changes.getTokenHeaderKey() == null)) {
            // the request doesn't have an Approov token header, so it is not signed
            return;
        }
        // select the factory and build the signature parameters, where only an unavailable required body digest
        // fails closed and every other failure proceeds unsigned, as the backend is the enforcement point
        ContextComponentProvider provider;
        SignatureParametersFactory factory;
        SignatureParameters params;
        try {
            provider = new ContextComponentProvider(context);
            factory = factoryFor(provider);
            if (factory == null) {
                // no factory applies to this host so the request is not signed
                return;
            }
            params = factory.buildSignatureParameters(provider, changes);
        } catch (RequiredBodyDigestException e) {
            throw e;
        } catch (Exception e) {
            ApproovLog.e(TAG, "Message signing: failed to build signature parameters, proceeding unsigned: " + e);
            return;
        }
        if (params == null) {
            // no signature to be added to the request
            return;
        }

        // determine the algorithms to sign with, which is any explicit algorithm set on the parameters or
        // otherwise the algorithms configured on the factory, and with no usable algorithm the request proceeds
        // unsigned while an unsupported algorithm is a configuration error that fails the request
        List<String> algs;
        try {
            if (params.getAlg() != null)
                algs = Collections.singletonList(params.getAlg());
            else
                algs = factory.getAlgs();
        } catch (RuntimeException e) {
            ApproovLog.e(TAG, "Message signing: no usable algorithm, proceeding unsigned: " + e);
            return;
        }
        if ((algs == null) || algs.isEmpty()) {
            ApproovLog.e(TAG, "Message signing: no usable algorithm, proceeding unsigned: empty algorithm list");
            return;
        }
        for (String alg : algs) {
            if (!ALG_ES256.equals(alg) && !ALG_HS256.equals(alg))
                throw new ApproovException("Message signing: unsupported algorithm identifier: " + alg);
        }

        // every remaining failure leaves the request to proceed without the signature concerned, or unsigned
        try {
            addSignatures(context, provider, params, algs);
        } catch (RuntimeException e) {
            context.removeHeader("Signature");
            context.removeHeader("Signature-Input");
            context.removeHeader("Signature-Base-Digest");
            ApproovLog.e(TAG, "Message signing: failed to add the signatures, proceeding unsigned: " + e);
        }
    }

    // one signature per algorithm over the same components, omitting any that cannot be produced
    private static void addSignatures(ApproovRequestContext context, ContextComponentProvider provider,
            SignatureParameters params, List<String> algs) {
        // each signature has its own signature base since the base includes the algorithm
        // WARNING never log a signature base as it contains an Approov token which provides access to your API
        Map<String, ListElement<?>> signatures = new LinkedHashMap<>();
        Map<String, ListElement<?>> signatureInputs = new LinkedHashMap<>();
        Map<String, String> messages = new LinkedHashMap<>();
        Map<String, String> missing = new LinkedHashMap<>();
        for (String alg : algs) {
            SignatureParameters algParams = new SignatureParameters(params).setAlg(alg);
            String message;
            try {
                message = new SignatureBaseBuilder(algParams, provider).createSignatureBase();
            } catch (Exception e) {
                ApproovLog.e(TAG, "Message signing: failed to build the signature base, proceeding unsigned: " + e);
                return;
            }
            String sigId = ALG_ES256.equals(alg) ? SIG_ID_INSTALL : SIG_ID_ACCOUNT;
            byte[] signature = ALG_ES256.equals(alg) ? installSignature(message, missing)
                    : accountSignature(message, missing);
            if (signature == null) {
                // this signature could not be produced so we proceed with any other
                continue;
            }
            signatures.put(sigId, ByteSequenceItem.valueOf(signature));
            signatureInputs.put(sigId, algParams.toComponentValue());
            messages.put(sigId, message);
        }
        if (signatures.isEmpty()) {
            // an error once per process, as the same cause usually applies to every request
            String line = "Message signing: no signature produced, proceeding unsigned";
            for (Map.Entry<String, String> entry : missing.entrySet())
                line += ": " + entry.getKey() + " signature " + entry.getValue();
            if (unsignedLogged.compareAndSet(false, true))
                ApproovLog.e(TAG, line);
            else
                ApproovLog.d(TAG, line);
            return;
        }

        // a warning once per process for each kind of signature that is missing while the other is sent
        for (Map.Entry<String, String> entry : missing.entrySet()) {
            String line = "Message signing: " + entry.getKey() + " signature " + entry.getValue()
                    + ", signed without it";
            AtomicBoolean logged = SIG_ID_INSTALL.equals(entry.getKey()) ? installMissingLogged : accountMissingLogged;
            if (logged.compareAndSet(false, true))
                ApproovLog.w(TAG, line);
            else
                ApproovLog.d(TAG, line);
        }

        // each signature is a byte sequence member of the same dictionaries, serialized as colon delimited base64
        // (install=:<base64>:), and both are serialized before either header is set
        String signatureHeader = Dictionary.valueOf(signatures).serialize();
        String signatureInputHeader = Dictionary.valueOf(signatureInputs).serialize();
        context.setHeader("Signature", signatureHeader);
        context.setHeader("Signature-Input", signatureInputHeader);

        // add a digest of each signature base in debug mode
        if (params.isDebugMode()) {
            try {
                MessageDigest digestBuilder = MessageDigest.getInstance("SHA-256");
                Map<String, ListElement<?>> digests = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : messages.entrySet()) {
                    digestBuilder.reset();
                    byte[] digest = digestBuilder.digest(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    digests.put(entry.getKey(), ByteSequenceItem.valueOf(digest));
                }
                context.setHeader("Signature-Base-Digest", Dictionary.valueOf(digests).serialize());
            } catch (NoSuchAlgorithmException e) {
                ApproovLog.d(TAG, "Message signing: digest algorithm unavailable, no debug entry: " + e);
            }
        }
        // WARNING never log the full request as it contains an Approov token which provides access to your API
    }

    // the raw 64 byte r||s value RFC 9421 requires for ecdsa-p256-sha256, or null if unavailable with the reason
    // put in missing
    private static byte[] installSignature(String message, Map<String, String> missing) {
        String base64;
        try {
            base64 = ApproovService.getInstallMessageSignature(message);
        } catch (ApproovException e) {
            missing.put(SIG_ID_INSTALL, "unavailable: " + e);
            return null;
        }
        if (base64.isEmpty()) {
            missing.put(SIG_ID_INSTALL, "unavailable");
            return null;
        }
        byte[] signature;
        try {
            signature = Base64.decode(base64, Base64.NO_WRAP);
        } catch (Exception e) {
            missing.put(SIG_ID_INSTALL, "not base64: " + e);
            return null;
        }
        // decode the signature from ASN.1 DER format
        try {
            return es256DerToRaw(signature);
        } catch (Exception e) {
            missing.put(SIG_ID_INSTALL, "not ASN.1 DER ES256: " + e);
            return null;
        }
    }

    // null if unavailable with the reason put in missing, for instance as the account key is only delivered on a
    // successful attestation
    private static byte[] accountSignature(String message, Map<String, String> missing) {
        String base64;
        try {
            base64 = ApproovService.getAccountMessageSignature(message);
        } catch (ApproovException e) {
            missing.put(SIG_ID_ACCOUNT, "unavailable: " + e);
            return null;
        }
        if (base64.isEmpty()) {
            missing.put(SIG_ID_ACCOUNT, "unavailable");
            return null;
        }
        try {
            return Base64.decode(base64, Base64.NO_WRAP);
        } catch (Exception e) {
            missing.put(SIG_ID_ACCOUNT, "not base64: " + e);
            return null;
        }
    }

    /**
     * Generates a default SignatureParametersFactory with predefined settings. This produces both the install
     * and the account signatures over the method, the target URI, the token, trace and status headers, the
     * Authorization, Content-Length and Content-Type headers when present, a SHA-256 Content-Digest of the
     * body when one can be computed, a created timestamp and an expiry of fifteen seconds.
     *
     * @return a new SignatureParametersFactory
     */
    public static SignatureParametersFactory generateDefaultSignatureParametersFactory() {
        return generateDefaultSignatureParametersFactory(null);
    }

    /**
     * Generates a default SignatureParametersFactory with optional base parameters.
     *
     * @param baseParametersOverride are the base parameters to use, or null for the defaults
     * @return a new SignatureParametersFactory
     */
    public static SignatureParametersFactory generateDefaultSignatureParametersFactory(
            SignatureParameters baseParametersOverride) {
        // default expiry seconds, which must encompass the worst case request retry time and clock skew
        long defaultExpiresLifetime = 15;
        SignatureParameters baseParameters;
        if (baseParametersOverride != null) {
            baseParameters = baseParametersOverride;
        } else {
            baseParameters = new SignatureParameters()
                    .addComponentIdentifier(ComponentProvider.DC_METHOD)
                    .addComponentIdentifier(ComponentProvider.DC_TARGET_URI);
        }
        return new SignatureParametersFactory()
                .setBaseParameters(baseParameters)
                .setUseInstallAndAccountMessageSigning()
                .setAddCreated(true)
                .setExpiresLifetime(defaultExpiresLifetime)
                .setAddApproovTokenHeader(true)
                .setAddApproovTraceIDHeader(true)
                .setAddApproovStatusHeader(true)
                .addOptionalHeaders("Authorization", "Content-Length", "Content-Type")
                .setBodyDigestConfig(DIGEST_SHA256, false);
    }

    /**
     * SignatureParametersFactory creates the SignatureParameters for each request with configurable settings.
     * Each request passed to the factory builds a new SignatureParameters instance based on the configured
     * settings and specific to the request.
     */
    public static class SignatureParametersFactory {
        // the base parameters that are copied for every new generated message signature, initialized so that a
        // bare SignatureParametersFactory() is safe to use without calling setBaseParameters()
        protected SignatureParameters baseParameters = new SignatureParameters();
        // the algorithm to use for body digests, or null if no body digest is to be used
        protected String bodyDigestAlgorithm;
        // true if a body digest is required, as body digests cannot be generated for requests that have no body
        // or whose body is unavailable
        protected boolean bodyDigestRequired;
        // true to produce the install message signature (ECDSA P-256 with the per installation key)
        protected boolean useInstallMessageSigning = true;
        // true to produce the account message signature (HMAC-SHA256 with the shared account key), where both
        // are true by default so that a bare factory produces both signatures unless one is selected explicitly
        protected boolean useAccountMessageSigning = true;
        // true to add the "created" timestamp field to the signature parameters
        protected boolean addCreated;
        // expiration lifetime in seconds, where the "expires" field is added if this is greater than 0
        protected long expiresLifetime;
        // true to add the Approov token header to the signature parameters (strongly advised)
        protected boolean addApproovTokenHeader;
        // true to add the Approov TraceID header to the signature parameters if it is present
        protected boolean addApproovTraceIDHeader;
        // true to add the Approov status header to the signature parameters if it is present, so that the
        // reported fetch status cannot be stripped or altered without invalidating the signature
        protected boolean addApproovStatusHeader;
        // headers to add to the message signature if they are present in the request, initialized so that a
        // bare SignatureParametersFactory() is safe to use
        protected List<String> optionalHeaders = new ArrayList<>();

        /**
         * Sets the base parameters for the factory, which are copied for every new generated message signature.
         *
         * @param baseParameters are the base parameters to set
         * @return the current instance for method chaining
         */
        public SignatureParametersFactory setBaseParameters(SignatureParameters baseParameters) {
            this.baseParameters = baseParameters;
            return this;
        }

        /**
         * Configures the body digest settings for the factory. If set, then requests with available bodies will
         * have the digest created and added as a header to the request, with the header included in the
         * message signature of the request.
         *
         * @param bodyDigestAlgorithm is the digest algorithm to use, or null to disable it
         * @param required is true if the body digest is required
         * @return the current instance for method chaining
         * @throws IllegalArgumentException if an unsupported algorithm is specified
         */
        public SignatureParametersFactory setBodyDigestConfig(String bodyDigestAlgorithm, boolean required) {
            if (bodyDigestAlgorithm == null) {
                required = false;
            } else if (!bodyDigestAlgorithm.equals(DIGEST_SHA256)
                    && !bodyDigestAlgorithm.equals(DIGEST_SHA512)) {
                throw new IllegalArgumentException("Unsupported body digest algorithm: " + bodyDigestAlgorithm);
            }
            this.bodyDigestAlgorithm = bodyDigestAlgorithm;
            this.bodyDigestRequired = required;
            return this;
        }

        /**
         * Configures the factory to produce only the install message signature.
         *
         * @return the current instance for method chaining
         */
        public SignatureParametersFactory setUseInstallMessageSigning() {
            this.useInstallMessageSigning = true;
            this.useAccountMessageSigning = false;
            return this;
        }

        /**
         * Configures the factory to produce only the account message signature.
         *
         * @return the current instance for method chaining
         */
        public SignatureParametersFactory setUseAccountMessageSigning() {
            this.useInstallMessageSigning = false;
            this.useAccountMessageSigning = true;
            return this;
        }

        /**
         * Configures the factory to produce both the install and the account message signatures, as members
         * "install" and "account" of the same Signature and Signature-Input dictionaries over the same covered
         * components. This is the default of a newly constructed factory.
         *
         * @return the current instance for method chaining
         */
        public SignatureParametersFactory setUseInstallAndAccountMessageSigning() {
            this.useInstallMessageSigning = true;
            this.useAccountMessageSigning = true;
            return this;
        }

        /**
         * Gets the signature algorithms configured on this factory, in the order the signatures are emitted.
         *
         * @return the list of algorithm identifiers
         * @throws IllegalStateException if neither signature is enabled
         */
        public List<String> getAlgs() {
            List<String> algs = new ArrayList<>(2);
            if (useInstallMessageSigning)
                algs.add(ALG_ES256);
            if (useAccountMessageSigning)
                algs.add(ALG_HS256);
            if (algs.isEmpty())
                throw new IllegalStateException("No message signing algorithm is enabled");
            return algs;
        }

        /**
         * Sets whether the "created" field should be added to the signature parameters. It holds the time from
         * ApproovService.getServerAdjustedCurrentTimeMS(), so it agrees with the backend's clock.
         *
         * @param addCreated is true to add the "created" field
         * @return the current instance for method chaining
         */
        public SignatureParametersFactory setAddCreated(boolean addCreated) {
            this.addCreated = addCreated;
            return this;
        }

        /**
         * Sets the expiration lifetime for the signature parameters. Only a value greater than 0 causes the
         * expires attribute to be added to the SignatureParameters for a request, counted from the time
         * ApproovService.getServerAdjustedCurrentTimeMS() gives.
         *
         * @param expiresLifetime is the expiration lifetime in seconds, or 0 or less for no expiration
         * @return the current instance for method chaining
         */
        public SignatureParametersFactory setExpiresLifetime(long expiresLifetime) {
            this.expiresLifetime = expiresLifetime;
            return this;
        }

        /**
         * Sets whether the Approov token header should be added to the signature parameters.
         *
         * @param addApproovTokenHeader is true to add the Approov token header
         * @return the current instance for method chaining
         */
        public SignatureParametersFactory setAddApproovTokenHeader(boolean addApproovTokenHeader) {
            this.addApproovTokenHeader = addApproovTokenHeader;
            return this;
        }

        /**
         * Sets whether the optional Approov TraceID header should be added to the signature parameters.
         *
         * @param addApproovTraceIDHeader is true to add the Approov TraceID header
         * @return the current instance for method chaining
         */
        public SignatureParametersFactory setAddApproovTraceIDHeader(boolean addApproovTraceIDHeader) {
            this.addApproovTraceIDHeader = addApproovTraceIDHeader;
            return this;
        }

        /**
         * Sets whether the Approov status header, when present on the request, is covered by the signature so
         * that the reported fetch status cannot be stripped or altered without invalidating the signature.
         *
         * @param addApproovStatusHeader is true to cover the Approov status header
         * @return the current instance for method chaining
         */
        public SignatureParametersFactory setAddApproovStatusHeader(boolean addApproovStatusHeader) {
            this.addApproovStatusHeader = addApproovStatusHeader;
            return this;
        }

        /**
         * Adds optional headers to the signature parameters. Headers configured as optional are added to the
         * generated SignatureParameters if the target request includes the header, and are otherwise ignored.
         *
         * @param headers are the headers to add
         * @return the current instance for method chaining
         */
        public SignatureParametersFactory addOptionalHeaders(String... headers) {
            if (this.optionalHeaders == null) {
                this.optionalHeaders = new ArrayList<>(Arrays.asList(headers));
            } else {
                this.optionalHeaders.addAll(Arrays.asList(headers));
            }
            return this;
        }

        /**
         * Generates a body digest for the request if possible.
         *
         * @param provider is the component provider for the request
         * @param requestParameters are the signature parameters to update
         * @return true if the body digest was successfully generated, false otherwise
         */
        protected boolean generateBodyDigest(ContextComponentProvider provider,
                SignatureParameters requestParameters) {
            byte[] body = provider.getContext().getBodyBytes();
            // ignore absent or unavailable bodies as these will likely require more specific knowledge, while an
            // empty body has a digest like any other
            if (body == null) {
                return false;
            }

            // calculate the digest
            String javaAlgorithm;
            switch (bodyDigestAlgorithm) {
                case DIGEST_SHA256:
                    javaAlgorithm = "SHA-256";
                    break;
                case DIGEST_SHA512:
                    javaAlgorithm = "SHA-512";
                    break;
                default:
                    return false;
            }
            byte[] digest;
            try {
                digest = MessageDigest.getInstance(javaAlgorithm).digest(body);
            } catch (NoSuchAlgorithmException e) {
                return false;
            }

            // generate the header value and add the digest header to the request
            Dictionary digestHeader = Dictionary.valueOf(Collections.singletonMap(
                    bodyDigestAlgorithm, ByteSequenceItem.valueOf(digest)));
            provider.getContext().setHeader("Content-Digest", digestHeader.serialize());

            // add the header to the SignatureParameters
            requestParameters.addComponentIdentifier("Content-Digest");
            return true;
        }

        /**
         * Builds the signature parameters for a given request. A subclass may override it to decide the
         * parameters of each request, including by handing the request to another factory, or return null to
         * leave the request unsigned. Note that the signatures made are those of the factory installed, from
         * its getAlgs(), whichever factory built the parameters.
         *
         * @param provider is the component provider for the request
         * @param changes are the request mutations that were applied
         * @return the generated SignatureParameters, or null if the request is not to be signed
         * @throws RequiredBodyDigestException if a body digest configured as required cannot be generated
         * @throws ApproovException if there was a problem
         */
        public SignatureParameters buildSignatureParameters(ContextComponentProvider provider,
                ApproovRequestMutations changes) throws ApproovException {
            // the algorithm is left unset so that one signature per configured algorithm is produced over these
            // same parameters, but a subclass may set an explicit algorithm to produce that single signature
            SignatureParameters requestParameters = new SignatureParameters(baseParameters);
            if (addCreated || (expiresLifetime > 0)) {
                long currentTime = ApproovService.signatureTimeMillis() / 1000;
                if (addCreated) {
                    requestParameters.setCreated(currentTime);
                }
                if (expiresLifetime > 0) {
                    requestParameters.setExpires(currentTime + expiresLifetime);
                }
            }
            if (addApproovTokenHeader) {
                requestParameters.addComponentIdentifier(changes.getTokenHeaderKey());
            }
            if (addApproovTraceIDHeader && (changes.getTraceIDHeaderKey() != null)) {
                requestParameters.addComponentIdentifier(changes.getTraceIDHeaderKey());
            }
            if (addApproovStatusHeader && (changes.getStatusHeaderKey() != null)) {
                requestParameters.addComponentIdentifier(changes.getStatusHeaderKey());
            }
            if (optionalHeaders != null) {
                for (String headerName : optionalHeaders) {
                    if (provider.hasField(headerName)) {
                        requestParameters.addComponentIdentifier(headerName);
                    }
                }
            }
            if (bodyDigestAlgorithm != null) {
                if (!generateBodyDigest(provider, requestParameters) && bodyDigestRequired) {
                    throw new RequiredBodyDigestException("Message signing: required body digest not generated");
                }
            }
            return requestParameters;
        }
    }

    // RequiredBodyDigestException is thrown if a body digest configured as required cannot be generated, which
    // fails the request on every networking stack
    public static class RequiredBodyDigestException extends ApproovException {
        /**
         * Constructs an Approov exception due to a required body digest that cannot be generated.
         *
         * @param message is the basic information about the exception cause
         */
        public RequiredBodyDigestException(String message) {
            super(message);
        }
    }

    /**
     * ContextComponentProvider implements the ComponentProvider interface over ApproovRequestContext, so that
     * the same signature base building applies to requests made with any supported networking stack.
     */
    public static final class ContextComponentProvider implements ComponentProvider {
        // the request this provider reads
        private final ApproovRequestContext context;

        ContextComponentProvider(ApproovRequestContext context) {
            this.context = context;
        }

        /**
         * Gets the request this provider reads.
         *
         * @return the request context
         */
        public ApproovRequestContext getContext() {
            return context;
        }

        // read from the request each time and never parsed or encoded again, so the backend rebuilds the same value
        private String wireURL() {
            return WireUrl.withoutFragment(context.getTargetURI());
        }

        // lowercase, without port or IPv6 brackets, for selecting a host factory
        String getHost() {
            String host = WireUrl.host(wireURL());
            if ((host != null) && (host.length() >= 2) && host.startsWith("[") && host.endsWith("]"))
                host = host.substring(1, host.length() - 1);
            return host;
        }

        @Override
        public String getMethod() {
            return context.getMethod();
        }

        /**
         * Gets the authority, which is the lowercase host, in brackets for an IPv6 address, followed by the port
         * only when it is not the default port of the scheme.
         */
        @Override
        public String getAuthority() {
            String url = wireURL();
            String host = WireUrl.host(url);
            if (host == null)
                return null;
            int port = WireUrl.port(url);
            if (port == WireUrl.MALFORMED_PORT)
                return host + ":" + WireUrl.portText(url);
            if ((port == -1) || (port == defaultPort(WireUrl.scheme(url))))
                return host;
            return host + ":" + port;
        }

        // gets the default port of http and https, or -1 for any other scheme
        private static int defaultPort(String scheme) {
            if ("https".equals(scheme))
                return 443;
            if ("http".equals(scheme))
                return 80;
            return -1;
        }

        @Override
        public String getScheme() {
            return WireUrl.scheme(wireURL());
        }

        /**
         * Gets the target URI, which is the URL as the stack sends it without any fragment, as that is never sent.
         */
        @Override
        public String getTargetUri() {
            return wireURL();
        }

        /**
         * Gets the request target, which is the path and query as they appear in the request line.
         */
        @Override
        public String getRequestTarget() {
            String url = wireURL();
            String query = WireUrl.query(url);
            return (query == null) ? getPath(url) : getPath(url) + "?" + query;
        }

        // gets the path as sent, which is "/" when the URL has none
        private static String getPath(String url) {
            String path = WireUrl.path(url);
            return path.isEmpty() ? "/" : path;
        }

        @Override
        public String getPath() {
            return getPath(wireURL());
        }

        /**
         * Gets the query as sent with its leading "?", or "?" alone when there is none.
         */
        @Override
        public String getQuery() {
            String query = WireUrl.query(wireURL());
            return (query == null) ? "?" : "?" + query;
        }

        /**
         * Gets a query parameter. The name is the encoded name from the component identifier and the value is
         * the decoded value of the parameter encoded again with the application/x-www-form-urlencoded
         * percent-encode set, with a space as "%20". Parameters are split and decoded as HttpUrl in OkHttp does,
         * so the values are those of approov-service-okhttp.
         */
        @Override
        public String getQueryParam(String name) {
            String query = WireUrl.query(wireURL());
            if (query == null) {
                throw new IllegalArgumentException("Could not find query parameter named " + name);
            }
            String decodedName = formDecode(name);
            List<String> values = new ArrayList<>();
            int pos = 0;
            while (pos <= query.length()) {
                int ampersand = query.indexOf('&', pos);
                if (ampersand < 0)
                    ampersand = query.length();
                int equals = query.indexOf('=', pos);
                String key;
                String value;
                if ((equals < 0) || (equals > ampersand)) {
                    key = query.substring(pos, ampersand);
                    value = null;
                } else {
                    key = query.substring(pos, equals);
                    value = query.substring(equals + 1, ampersand);
                }
                if (formDecode(key).equals(decodedName))
                    values.add((value == null) ? null : formDecode(value));
                pos = ampersand + 1;
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Could not find query parameter named " + name);
            } else if (values.size() > 1) {
                // a repeated parameter name must not be included, which returning null indicates
                return null;
            }
            String value = values.get(0);
            return (value == null) ? "" : formEncode(value);
        }

        // decodes application/x-www-form-urlencoded text, where + is a space and %XX a byte of UTF-8, and a
        // malformed escape is kept as it is
        private static String formDecode(String text) {
            if ((text.indexOf('%') < 0) && (text.indexOf('+') < 0))
                return text;
            ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
            for (int i = 0; i < text.length(); ) {
                int c = text.codePointAt(i);
                if (c == '+') {
                    out.write(' ');
                } else if ((c == '%') && (i + 2 < text.length())
                        && (Character.digit(text.charAt(i + 1), 16) >= 0)
                        && (Character.digit(text.charAt(i + 2), 16) >= 0)) {
                    out.write((Character.digit(text.charAt(i + 1), 16) << 4)
                            | Character.digit(text.charAt(i + 2), 16));
                    i += 3;
                    continue;
                } else {
                    byte[] bytes = new String(Character.toChars(c)).getBytes(StandardCharsets.UTF_8);
                    out.write(bytes, 0, bytes.length);
                }
                i += Character.charCount(c);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }

        // percent-encodes UTF-8 text with the application/x-www-form-urlencoded percent-encode set, which leaves
        // only ASCII letters, digits and *-._
        private static String formEncode(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder(bytes.length);
            for (byte b : bytes) {
                int c = b & 0xff;
                if (((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z')) || ((c >= '0') && (c <= '9'))
                        || (c == '*') || (c == '-') || (c == '.') || (c == '_')) {
                    out.append((char) c);
                } else {
                    out.append('%').append(HEX_DIGITS[c >> 4]).append(HEX_DIGITS[c & 0xf]);
                }
            }
            return out.toString();
        }

        // the uppercase hexadecimal digits used for percent-encoding
        private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

        @Override
        public String getStatus() {
            throw new IllegalStateException("Only requests are supported");
        }

        @Override
        public boolean hasField(String name) {
            return !context.getHeaders(name).isEmpty();
        }

        @Override
        public String getField(String name) {
            return ComponentProvider.combineFieldValues(context.getHeaders(name));
        }

        @Override
        public boolean hasBody() {
            return context.hasBody();
        }
    }
}
