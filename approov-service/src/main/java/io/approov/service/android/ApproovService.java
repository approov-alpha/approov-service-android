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
import android.os.StrictMode;
import android.os.SystemClock;

import androidx.annotation.RestrictTo;

import com.criticalblue.approovsdk.Approov;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * ApproovService provides a mediation layer to the Approov SDK for Android. It provides one facade
 * for initialization, configuration, secure strings, custom JWTs and message signing, together with
 * the request processing that every networking stack adapter (OkHttp, HttpsURLConnection, Volley,
 * gRPC and Cronet) routes its requests through, so every stack gets the same features.
 */
public class ApproovService {
    // logging tag
    private static final String TAG = "ApproovService";

    // default header that will be added to Approov enabled requests
    private static final String APPROOV_TOKEN_HEADER = "Approov-Token";

    // default header that will carry any optional Approov TraceID debug value from the SDK
    private static final String APPROOV_TRACE_ID_HEADER = "Approov-TraceID";

    // default header that reports the Approov token fetch status on requests processed by Approov
    private static final String APPROOV_STATUS_HEADER = "Approov-Status";

    // default prefix to be added before the Approov token by default
    private static final String APPROOV_TOKEN_PREFIX = "";

    // default age after which a supporting adapter refreshes protection just before transmission,
    // well below the default 15 second signature expiry
    private static final long DEFAULT_STALE_PROTECTION_REFRESH_MS = 3000;

    // true once any initialize has succeeded, including bypass mode with an empty account ID
    private static boolean approovServiceEnabled = false;

    // true once the Approov SDK is initialized with an account ID so that requests are protected
    private static boolean approovProtectionEnabled = false;

    // true once the options of the bundled approov.config have been applied in this process
    private static boolean configFileApplied = false;

    // the user property that identifies this layer to the Approov SDK unless a host framework is set
    private static final String DEFAULT_USER_PROPERTY = "approov-service-android/" + BuildConfig.APPROOV_SERVICE_VERSION;

    // the identity of an Approov framework package hosting this layer (such as "approov-react-native/3.7.0"),
    // set as the user property instead of the default, or null if none has been set
    private static String hostFrameworkIdentity = null;

    // header to be used to send Approov tokens
    private static String approovTokenHeader = APPROOV_TOKEN_HEADER;

    // header used to send any optional Approov TraceID debug value provided by the SDK
    private static String approovTraceIDHeader = APPROOV_TRACE_ID_HEADER;

    // header used to report the Approov fetch status to the backend, or null if disabled
    private static String approovStatusHeader = APPROOV_STATUS_HEADER;

    // any prefix String to be added before the transmitted Approov token
    private static String approovTokenPrefix = APPROOV_TOKEN_PREFIX;

    // any header to be used for binding in Approov tokens or null if not set
    private static String bindingHeader = null;

    // age after which protection is refreshed before transmission, or <= 0 if refresh is disabled
    private static long staleProtectionRefreshMS = DEFAULT_STALE_PROTECTION_REFRESH_MS;

    // the Attestation Response Code (ARC) from the most recent fetch this layer performed,
    // or empty if none, if that fetch carried none or if ARC is not enabled for the account
    private static String lastARC = "";

    // the mutator instance used to customize ApproovService behavior at key points in the flow
    private static ApproovServiceMutator serviceMutator = ApproovServiceMutator.DEFAULT;

    // the message signing applied to protected requests while enabled, holding the default and
    // per host signature parameters factories
    private static ApproovDefaultMessageSigning messageSigning = new ApproovDefaultMessageSigning()
            .setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());

    // true if message signing is enabled, which is on by default with both signatures
    private static boolean messageSigningEnabled = true;

    // map of headers that should have their values substituted for secure strings, mapped to
    // their required prefixes
    private static Map<String, String> substitutionHeaders = new HashMap<>();

    // set of query parameters that may be substituted, specified by the key name, mapped to
    // the compiled Pattern
    private static Map<String, Pattern> substitutionQueryParams = new HashMap<>();

    // set of URL regexs that should be excluded from any Approov protection, mapped to the
    // compiled Pattern
    private static Map<String, Pattern> exclusionURLRegexs = new HashMap<>();

    // listeners informed when the dynamic pinning configuration changes, so that adapters holding
    // derived pinning state (such as an OkHttp CertificatePinner) can rebuild it
    private static final List<Runnable> pinsChangeListeners = new CopyOnWriteArrayList<>();

    // the boundary to the Approov SDK, replaceable by unit tests
    private static volatile ApproovSdkFacade sdkFacade = new DefaultApproovSdkFacade();

    // the system monotonic clock
    private static final MonotonicClock SYSTEM_MONOTONIC_CLOCK = SystemClock::elapsedRealtime;

    // the monotonic clock used for protection ages, replaceable by unit tests
    private static volatile MonotonicClock monotonicClock = SYSTEM_MONOTONIC_CLOCK;

    /**
     * Construction is disallowed as this is a static only class.
     */
    protected ApproovService() {
    }

    /**
     * Initializes the ApproovService with the Approov account ID and a comment. Every caller in the
     * process, for example native code and a React Native module, may call this and all of them drive
     * the same service state.
     * <p>
     * A non-empty account ID is always passed to the Approov SDK with the comment unchanged and the SDK
     * decides: the first initialization succeeds, a repeat with the same account ID and comment returns
     * at once, and anything else the SDK rejects is thrown to the caller with the service state
     * unchanged. An empty account ID enables the service in bypass mode, without Approov protection,
     * and is ignored once protection is enabled. Note that initialization never resets any
     * configuration set before or after it, and this form never reads a bundled approov.config file.
     *
     * @param context is the Application context
     * @param config is the Approov account ID, or empty for bypass mode with no SDK initialization
     * @param comment is the comment passed to the SDK unchanged, or null for no comment
     * @throws IllegalArgumentException if the context or account ID is null, or the SDK rejects the account ID
     * @throws IllegalStateException if the SDK is already initialized with a different account ID or comment
     */
    public static void initialize(Context context, String config, String comment) {
        // pins-change listeners run outside this monitor, as a listener may take its adapter's lock
        if (initializeLocked(context, config, comment, null))
            notifyPinsChanged();

        // report once per process any Cronet library that the Gradle plugin did not weave
        CronetWeaveCheck.atInitialize(context);
    }

    /**
     * Initializes the ApproovService from the approov.config file bundled in the app assets, which holds
     * the Approov account ID and may hold a comment and configuration options. Different build variants may
     * bundle different files through their Gradle source sets, such as src/debug/assets/approov.config.
     * The file is a JSON object, or a 3.5.x file whose first non-blank line is the account ID. The account
     * ID and comment are used as by initialize(context, config, comment), with an empty account ID for bypass
     * mode.
     * <p>
     * The options of the file are applied once per process, by the call that first enables this package,
     * in bypass mode or with protection, or that turns on protection after bypass mode, even if another
     * Approov package already initialized the SDK. They are in force before the package reports itself
     * enabled. A call that finds the package already enabled applies none of them, and nothing set before
     * or after is reset. An unknown key is logged and ignored, and an option with a wrong type or value is
     * logged and skipped, so neither fails initialization. The reading of the file logs key names only; the
     * configuration calls it makes log as they do for any caller, and the account ID, comment and
     * development key are never logged.
     * <p>
     * A persistent configuration stored by an earlier launch with setPersistentConfiguration is applied in the
     * same pass: each of its keys replaces the same key of the file, a list or object as a whole. It is deleted
     * instead if it was stored with a bundled file whose content differs from this one, if it was stored by
     * another version of the app (version code or version name), or if it cannot be read.
     *
     * @param context is the Application context
     * @throws ApproovException if the file is missing, cannot be read, is not valid or has no config
     * @throws IllegalArgumentException if the context is null, or the SDK rejects the account ID
     * @throws IllegalStateException if the SDK is already initialized with a different account ID or comment
     */
    public static void initialize(Context context) throws ApproovException {
        if (context == null)
            throw new IllegalArgumentException("initialize: null context");

        // the file is read before the monitor is taken, so that the read never holds up a request
        ApproovConfigFile file;
        byte[] content;
        try {
            content = ApproovConfigFile.readAsset(context);
            if (content == null)
                throw new ApproovException(ApproovConfigFile.ASSET_NAME + " not found in the app assets");
            file = ApproovConfigFile.parse(content);
        } catch (ApproovException e) {
            ApproovLog.e(TAG, "initialize: " + e.getMessage());
            throw new ApproovException("initialize: " + e.getMessage());
        }

        // any persistent configuration stored by an earlier launch replaces options of the file, read before the
        // monitor as the file is; initialize is often called on the main thread, where StrictMode would report the
        // small read and any delete of a record in the app's files, so both are allowed for this call only
        ApproovPersistentConfiguration.Launch launch;
        StrictMode.ThreadPolicy policy = StrictMode.allowThreadDiskReads();
        try {
            StrictMode.allowThreadDiskWrites();
            launch = ApproovPersistentConfiguration.forLaunch(context, content);
        } finally {
            StrictMode.setThreadPolicy(policy);
        }
        if (launch.stored != null)
            file = file.withPersistent(launch.stored.options, launch.stored.source);
        file.setPersistentNotice(launch.notice);

        // pins-change listeners run outside this monitor, as a listener may take its adapter's lock
        if (initializeLocked(context, file.getConfig(), file.getComment(), file))
            notifyPinsChanged();

        // report once per process any Cronet library that the Gradle plugin did not weave
        CronetWeaveCheck.atInitialize(context);
    }

    /**
     * Stores a persistent configuration: options in the form of the bundled approov.config that the file form,
     * initialize(context), applies at the next launch of the app, so that they are in force from its first
     * request. Nothing is applied in the running process. At the next launch each key replaces the same key of
     * the bundled file, a list or object as a whole, and keys the configuration does not have keep the value of
     * the file. The configuration is deleted at that launch if the content of the bundled file or the version of
     * the app (version code or version name) has changed since it was stored, so every update of the app starts
     * from its own bundled file. The explicit forms of initialize neither apply nor delete it.
     * <p>
     * The options are checked as the bundled file is, but strictly: content that is not one valid JSON object,
     * is larger than 64 KiB, holds config, comment or devKey, or has a known key with a wrong type or value
     * (such as an unknown mutator or a regular expression that does not compile) is rejected and nothing is
     * stored. An unknown key is stored and ignored when it is applied. Storing the same options and source again
     * writes nothing. The configuration is kept in the no-backup files of the app and written atomically. A
     * rejected configuration is also logged as an error naming the cause. This may be called from any thread; it
     * reads and writes a file, so avoid calling it on the main thread.
     *
     * @param context is any context of the app
     * @param json is a JSON object of approov.config options, without config, comment or devKey
     * @param source is a tag for where the configuration comes from, such as an update ID, logged when it is
     *        applied and not secret, or null for none
     * @throws ApproovException if the configuration is not valid or cannot be stored, naming the cause and never
     *         a value, with nothing stored
     * @throws IllegalArgumentException if the context is null
     */
    public static void setPersistentConfiguration(Context context, String json, String source)
            throws ApproovException {
        try {
            ApproovPersistentConfiguration.store(context, json, source);
        } catch (ApproovException | IllegalArgumentException e) {
            ApproovLog.e(TAG, e.getMessage());
            throw e;
        }
    }

    /**
     * Deletes any persistent configuration stored with setPersistentConfiguration, so that the next launch uses
     * the bundled approov.config alone. Nothing changes in the running process. It is not an error if there is
     * none. This may be called from any thread.
     *
     * @param context is any context of the app
     * @throws ApproovException if the configuration exists and cannot be deleted
     * @throws IllegalArgumentException if the context is null
     */
    public static void clearPersistentConfiguration(Context context) throws ApproovException {
        ApproovPersistentConfiguration.clear(context);
    }

    /**
     * Gets the source tag of the persistent configuration currently stored with setPersistentConfiguration. This
     * is what is stored, not what is in force: it includes a configuration stored in this launch, which applies
     * from the next one, and one the next launch may still delete because the bundled approov.config has changed
     * or it cannot be read. This may be called from any thread.
     *
     * @param context is any context of the app
     * @return the source tag, or null if no persistent configuration is stored or it was stored without one
     * @throws IllegalArgumentException if the context is null
     */
    public static String getPersistentConfigurationSource(Context context) {
        return ApproovPersistentConfiguration.getSource(context);
    }

    // the state changes of initialize() under the monitor, and the options of any bundled file, applied by the
    // call that first enables the package; an SDK exception propagates before any state changes
    private static synchronized boolean initializeLocked(Context context, String config, String comment,
            ApproovConfigFile file) {
        // check the arguments
        if (context == null)
            throw new IllegalArgumentException("initialize: null context");
        if (config == null)
            throw new IllegalArgumentException("initialize: null config");

        // an empty account ID enables bypass mode, but never downgrades active protection
        if (config.isEmpty()) {
            if (approovProtectionEnabled) {
                ApproovLog.i(TAG, "initialize: empty config ignored, Approov protection already enabled");
            } else {
                ApproovLog.i(TAG, "initialize: bypass mode, Approov protection not active");
                boolean serviceWasEnabled = approovServiceEnabled;
                approovServiceEnabled = true;
                if ((file != null) && !serviceWasEnabled)
                    applyConfigFile(file);
            }
            return false;
        }

        // forward every non-empty account ID with the comment exactly as given, so the SDK is the only
        // judge of a repeat call
        boolean newlyInitialized;
        try {
            newlyInitialized = sdkFacade.initialize(context.getApplicationContext(), config, "auto", comment);
        } catch (RuntimeException e) {
            ApproovLog.e(TAG, "initialize: Approov SDK failed: " + e);
            throw e;
        }
        boolean protectionWasEnabled = approovProtectionEnabled;
        approovServiceEnabled = true;
        approovProtectionEnabled = true;
        if (newlyInitialized)
            ApproovLog.i(TAG, "initialize: Approov protection enabled");
        else
            ApproovLog.d(TAG, "initialize: Approov SDK already initialized with the same config and comment");

        // set the user property when protection is first enabled and on every new SDK initialization
        if (newlyInitialized || !protectionWasEnabled)
            applyUserProperty("initialize");

        // the options of a bundled file apply when this package first enables protection, even over an SDK that
        // another Approov package initialized, never on a repeat
        if ((file != null) && !protectionWasEnabled)
            applyConfigFile(file);

        // listeners are told when protection is first enabled, since pinners built before hold no pins,
        // and on every new SDK initialization, which may carry new options
        return newlyInitialized || !protectionWasEnabled;
    }

    // applies the options of the bundled file once per process, under the monitor so that no other initialize
    // returns and no request is processed before they are in force; an option a setter rejects is logged by its
    // key and skipped, and nothing else is reset
    private static synchronized void applyConfigFile(ApproovConfigFile file) {
        if (configFileApplied)
            return;
        configFileApplied = true;

        // the logging level first, so the rest is logged at the level the file asks for, then the lines about
        // unknown keys and skipped values
        if (file.getLogLevel() != null)
            setLoggingLevel(file.getLogLevel());
        if (file.getConfig().isEmpty())
            ApproovLog.w(TAG, "initialize: " + ApproovConfigFile.ASSET_NAME + " config is empty, bypass mode");
        ApproovPersistentConfiguration.Notice notice = file.getPersistentNotice();
        if ((notice != null) && notice.warning)
            ApproovLog.w(TAG, notice.message);
        else if (notice != null)
            ApproovLog.i(TAG, notice.message);
        for (ApproovConfigFile.Diagnostic diagnostic : file.getDiagnostics()) {
            if (diagnostic.error)
                ApproovLog.e(TAG, diagnostic.message);
            else
                ApproovLog.i(TAG, diagnostic.message);
        }

        // each option through its public setter; the log names the key only, never the value
        for (ApproovConfigFile.Option option : file.getOptions()) {
            if (ApproovConfigFile.DEV_KEY.equals(option.key) && !approovProtectionEnabled) {
                ApproovLog.w(TAG, ApproovConfigFile.ASSET_NAME + ": " + option.key
                        + ": not applied, Approov protection not enabled");
                continue;
            }
            try {
                option.action.apply();
            } catch (Exception e) {
                ApproovLog.e(TAG, option.rejected);
            }
        }
        ApproovLog.d(TAG, ApproovConfigFile.ASSET_NAME + ": options applied");
        if (file.isPersistent()) {
            String source = file.getPersistentSource();
            ApproovLog.i(TAG, "initialize: persistent configuration applied (source "
                    + ((source != null) ? ApproovConfigFile.loggable(source, 128) : "none") + ")");
        }
    }

    // sets the user property on the SDK: the host framework identity if one is set, else this layer's own
    private static synchronized void applyUserProperty(String caller) {
        String property = (hostFrameworkIdentity != null) ? hostFrameworkIdentity : DEFAULT_USER_PROPERTY;
        try {
            sdkFacade.setUserProperty(property);
        } catch (RuntimeException e) {
            // the property is diagnostic only and must not fail the caller
            ApproovLog.e(TAG, caller + ": Approov user property not set: " + e);
        }
    }

    /**
     * Sets the identity of the Approov framework package that hosts this layer, such as
     * "approov-react-native/3.7.0" for the React Native package, which then replaces
     * "approov-service-android/&lt;version&gt;" as the Approov SDK user property. It may be called
     * before or after initialize(): before, the initialization sets it; once Approov protection is
     * enabled, it is set on the SDK at once. The last identity set applies, and setting the identity
     * already in force does nothing.
     * <p>
     * Note that this is for Approov's own framework packages only and is not part of the API for apps.
     *
     * @param identity is the framework package name and version, as "name/version"
     * @throws IllegalArgumentException if the identity is null or empty
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    public static synchronized void setHostFrameworkIdentity(String identity) {
        if ((identity == null) || identity.isEmpty())
            throw new IllegalArgumentException("setHostFrameworkIdentity: null or empty identity");
        if (identity.equals(hostFrameworkIdentity))
            return;
        hostFrameworkIdentity = identity;
        ApproovLog.d(TAG, "setHostFrameworkIdentity " + identity);
        if (approovProtectionEnabled)
            applyUserProperty("setHostFrameworkIdentity");
    }

    /**
     * Initializes the ApproovService with the Approov account ID and no comment, so a null comment is
     * passed to the SDK.
     *
     * @param context is the Application context
     * @param config is the Approov account ID, or empty for bypass mode with no SDK initialization
     * @throws IllegalArgumentException if the context or account ID is null, or the SDK rejects the account ID
     * @throws IllegalStateException if the SDK is already initialized with a different account ID or comment
     */
    public static void initialize(Context context, String config) {
        initialize(context, config, null);
    }

    /**
     * Sets how much the package logs on every stack. OFF logs nothing and each other level logs its own
     * severity and the ones above it, with INFO as the default. This may be called at any time and is
     * not changed by initialize(). Note that enabling debug logging for the ApproovService tag (with
     * "adb shell setprop log.tag.ApproovService DEBUG") raises any level other than OFF to DEBUG, so
     * the debug lines of a release build can be read. No secure string value, substituted query,
     * message signature or account ID is ever logged.
     *
     * @param level is the logging level
     * @throws IllegalArgumentException if the level is null
     */
    public static void setLoggingLevel(ApproovLogLevel level) {
        if (level == null)
            throw new IllegalArgumentException("setLoggingLevel: null level");
        ApproovLog.setLevel(level);
        ApproovLog.d(TAG, "setLoggingLevel " + level);
    }

    // for unit tests only
    static synchronized void resetForTesting() {
        ApproovLog.setLevel(ApproovLogLevel.INFO);
        approovServiceEnabled = false;
        approovProtectionEnabled = false;
        configFileApplied = false;
        ApproovPersistentConfiguration.resetForTesting();
        hostFrameworkIdentity = null;
        approovTokenHeader = APPROOV_TOKEN_HEADER;
        approovTraceIDHeader = APPROOV_TRACE_ID_HEADER;
        approovStatusHeader = APPROOV_STATUS_HEADER;
        approovTokenPrefix = APPROOV_TOKEN_PREFIX;
        bindingHeader = null;
        staleProtectionRefreshMS = DEFAULT_STALE_PROTECTION_REFRESH_MS;
        lastARC = "";
        substitutionHeaders = new HashMap<>();
        substitutionQueryParams = new HashMap<>();
        exclusionURLRegexs = new HashMap<>();
        serviceMutator = ApproovServiceMutator.DEFAULT;
        messageSigning = new ApproovDefaultMessageSigning()
                .setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
        messageSigningEnabled = true;
        ApproovDefaultMessageSigning.resetForTesting();
        pinsChangeListeners.clear();
        CronetWeaveCheck.resetForTesting();
        ApproovPinningVerifier.resetForTesting();
        messageSignatureDeprecationLogged.set(false);
        sdkFacade = new DefaultApproovSdkFacade();
        monotonicClock = SYSTEM_MONOTONIC_CLOCK;
    }

    static synchronized void setSdkFacadeForTesting(ApproovSdkFacade facade) {
        if (facade == null)
            throw new IllegalArgumentException("setSdkFacadeForTesting: null facade");
        sdkFacade = facade;
    }

    static synchronized void setMonotonicClockForTesting(MonotonicClock clock) {
        if (clock == null)
            throw new IllegalArgumentException("setMonotonicClockForTesting: null clock");
        monotonicClock = clock;
    }

    /**
     * Indicates whether the ApproovService is enabled, which is true once any initialize has
     * succeeded, including bypass mode with an empty account ID. Before that, requests pass through
     * the networking adapters without Approov processing.
     *
     * @return true if the service has been enabled by a successful initialize, false otherwise
     */
    public static synchronized boolean isApproovServiceEnabled() {
        return approovServiceEnabled;
    }

    /**
     * Indicates whether Approov protection is enabled, which is true once the Approov SDK has been
     * initialized with a non-empty account ID. It is false before initialization and in bypass mode,
     * where requests pass through without Approov processing and the SDK is never called.
     *
     * @return true if the Approov SDK is initialized and requests are protected, false otherwise
     */
    public static synchronized boolean isApproovProtectionEnabled() {
        return approovProtectionEnabled;
    }

    /**
     * Sets a development key indicating that the app is a development version and it should
     * pass attestation even if the app is not registered or it is running on an emulator. The
     * development key value can be rotated at any point in the account if a version of the app
     * containing the development key is accidentally released. This is primarily used for
     * situations where the app package must be modified or resigned in some way as part of
     * the testing process.
     *
     * @param devKey is the development key to be used
     * @throws ApproovException if there was a problem, such as Approov protection not being enabled
     */
    public static synchronized void setDevKey(String devKey) throws ApproovException {
        requireProtection("setDevKey");
        callSdk("setDevKey", sdk -> {
            sdk.setDevKey(devKey);
            return null;
        });
        ApproovLog.d(TAG, "setDevKey");
    }

    /**
     * Sets the header that the Approov token is added on, as well as an optional prefix String (such as
     * "Bearer "). By default the token is provided on "Approov-Token" with no prefix. If no token could
     * be obtained but the request proceeds, the header is not added at all, not even the prefix, and the
     * reason is reported on the status header. Failure information is never placed in this header.
     *
     * @param header is the header to place the Approov token on
     * @param prefix is any prefix String for the Approov token header, or null for none
     */
    public static synchronized void setTokenHeader(String header, String prefix) {
        ApproovLog.d(TAG, "setTokenHeader " + header + ", " + prefix);
        approovTokenHeader = header;
        approovTokenPrefix = (prefix != null) ? prefix : APPROOV_TOKEN_PREFIX;
    }

    /**
     * Gets the header that is used to add the Approov token.
     *
     * @return String of the header used for the Approov token
     */
    public static synchronized String getTokenHeader() {
        return approovTokenHeader;
    }

    /**
     * Gets the prefix that is added before the Approov token in the header.
     *
     * @return String of the prefix added before the Approov token, empty if none
     */
    public static synchronized String getTokenPrefix() {
        return approovTokenPrefix;
    }

    /**
     * Sets the header name that is used to pass any optional Approov TraceID debug value. By default
     * the TraceID is provided on "Approov-TraceID" if one is available.
     *
     * @param header is the name of the header on which to place the Approov TraceID, or null to disable it
     */
    public static synchronized void setTraceIDHeader(String header) {
        ApproovLog.d(TAG, "setTraceIDHeader " + header);
        approovTraceIDHeader = header;
    }

    /**
     * Gets the name of the header that is used to hold the optional Approov TraceID.
     *
     * @return String the name of the header used for the Approov TraceID, or null if disabled
     */
    public static synchronized String getTraceIDHeader() {
        return approovTraceIDHeader;
    }

    /**
     * Sets the header name that is used to report the Approov token fetch status to your backend API on
     * requests processed by Approov. By default this is "Approov-Status". The value is the SDK fetch
     * status name exactly as the SDK gives it, for example "SUCCESS", "NO_NETWORK" or "REJECTED", the same
     * names as on iOS, so the backend can tell why a request carries no Approov token. A failure status is only
     * reported to an Approov API domain, and the header is never sent to a host not added to Approov,
     * to a secrets-only API or on excluded requests. Any value the app set on the header is replaced.
     * Note that if this is disabled, a request that could not be protected is sent with no Approov
     * header at all.
     *
     * @param header is the name of the header on which to report the Approov fetch status, or null to disable it
     */
    public static synchronized void setStatusHeader(String header) {
        ApproovLog.d(TAG, "setStatusHeader " + header);
        approovStatusHeader = header;
    }

    /**
     * Gets the name of the header that is used to report the Approov fetch status.
     *
     * @return String the name of the header used for the Approov fetch status, or null if disabled
     */
    public static synchronized String getStatusHeader() {
        return approovStatusHeader;
    }

    // the SDK fetch status name exactly as the SDK gives it, never converted, so the text follows the SDK
    static String buildStatusHeaderValue(Approov.TokenFetchResult approovResults) {
        return approovResults.getStatus().name();
    }

    // one consistent snapshot, so a request never mixes one configuration's header with another's prefix
    private static synchronized String[] snapshotTokenHeader() {
        return new String[] { approovTokenHeader, approovTokenPrefix };
    }

    /**
     * Sets the header that the Approov token is added on, as well as an optional prefix String.
     * Deprecated: use setTokenHeader instead.
     *
     * @param header is the header to place the Approov token on
     * @param prefix is any prefix String for the Approov token header, or null for none
     */
    @Deprecated
    public static void setApproovHeader(String header, String prefix) {
        setTokenHeader(header, prefix);
    }

    /**
     * Sets the header name that is used to pass any optional Approov TraceID debug value.
     * Deprecated: use setTraceIDHeader instead.
     *
     * @param header is the name of the header on which to place the Approov TraceID, or null to disable it
     */
    @Deprecated
    public static void setApproovTraceIDHeader(String header) {
        setTraceIDHeader(header);
    }

    /**
     * Gets the header that is used to add the Approov token. Deprecated: use getTokenHeader instead.
     *
     * @return String of the header used for the Approov token
     */
    @Deprecated
    public static String getApproovTokenHeader() {
        return getTokenHeader();
    }

    /**
     * Gets the name of the header that is used to hold the optional Approov TraceID. Deprecated: use
     * getTraceIDHeader instead.
     *
     * @return String the name of the header used for the Approov TraceID, or null if disabled
     */
    @Deprecated
    public static String getApproovTraceIDHeader() {
        return getTraceIDHeader();
    }

    /**
     * Gets the prefix that is added before the Approov token in the header. Deprecated: use
     * getTokenPrefix instead.
     *
     * @return String of the prefix added before the Approov token
     */
    @Deprecated
    public static String getApproovTokenPrefix() {
        return getTokenPrefix();
    }

    /**
     * Sets a binding header that must be present on all requests using the Approov service. A header
     * should be chosen whose value is unchanging for most requests (such as an Authorization header).
     * A hash of the header value is included in the issued Approov tokens to bind them to the value.
     * This may then be verified by the backend API integration. This method should typically only be
     * called once.
     *
     * @param header is the header to use for Approov token binding
     * @throws IllegalArgumentException if the header is already configured for secure string substitution
     */
    public static synchronized void setBindingHeader(String header) {
        ApproovLog.d(TAG, "setBindingHeader " + header);
        if ((header != null) && (findSubstitutionHeaderKey(header) != null)) {
            throw new IllegalArgumentException("setBindingHeader " + header + ": already a substitution header");
        }
        bindingHeader = header;
    }

    /**
     * Gets any current binding header.
     *
     * @return binding header or null if not set
     */
    public static synchronized String getBindingHeader() {
        return bindingHeader;
    }

    /**
     * Sets the period after which a request held between Approov protection and transmission has its
     * token and any message signature refreshed, by the adapters that support this. The default is 3000
     * milliseconds.
     *
     * @param periodMS is the refresh threshold in milliseconds, or <= 0 to disable it
     */
    public static synchronized void setStaleProtectionRefreshPeriod(long periodMS) {
        ApproovLog.d(TAG, "setStaleProtectionRefreshPeriod " + periodMS);
        staleProtectionRefreshMS = periodMS;
    }

    /**
     * Gets the period after which a request held between protection and transmission is refreshed.
     *
     * @return refresh threshold in milliseconds, or <= 0 when disabled
     */
    public static synchronized long getStaleProtectionRefreshPeriod() {
        return staleProtectionRefreshMS;
    }

    /**
     * Sets the ApproovServiceMutator instance to handle callbacks from the ApproovService
     * implementation. This enables customization of ApproovService operations at key points in the
     * configuration and attestation flows, and applies to all networking stacks.
     *
     * @param mutator is the ApproovServiceMutator with callback handlers, or null for
     * ApproovServiceMutator.DEFAULT (the ALWAYS_PROCEED decisions)
     */
    public static synchronized void setServiceMutator(ApproovServiceMutator mutator) {
        if (mutator == null) {
            mutator = ApproovServiceMutator.DEFAULT;
        }
        ApproovLog.d(TAG, "setServiceMutator " + describe(mutator));
        serviceMutator = mutator;
    }

    // the mutator for a log line, by its class name if its toString throws
    static String describe(ApproovServiceMutator mutator) {
        try {
            return String.valueOf(mutator);
        } catch (RuntimeException e) {
            return mutator.getClass().getName();
        }
    }

    /**
     * Gets the active service mutator instance that is handling callbacks from ApproovService.
     *
     * @return the service mutator instance, never null
     */
    public static synchronized ApproovServiceMutator getServiceMutator() {
        return serviceMutator;
    }

    /**
     * Switches message signing on with the default signature parameters factory, which produces both
     * the install signature (member "install", ecdsa-p256-sha256, with a per installation key held in
     * the device secure hardware) and the account signature (member "account", hmac-sha256, with the
     * account key delivered on attestation) over the same covered components. Note that this is also
     * the default state, so this call is only needed to restore it after disableMessageSigning().
     */
    public static void enableMessageSigning() {
        enableMessageSigning(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory());
    }

    /**
     * Switches message signing on with the given default factory. Message signing is on by default, with
     * the default factory that produces both the install and the account signature. Once on, every
     * request carrying the Approov token header, on every networking stack, is signed after the service
     * mutator's decisions and the secure string substitutions, so the signature covers what is sent, and
     * it is signed again whenever its protection is reapplied. Signing works the same with any service
     * mutator and is not changed by setServiceMutator or initialize(). A signature that cannot be
     * produced is omitted and the request proceeds with the other signature, or unsigned.
     *
     * @param defaultFactory is the signature parameters factory for every host without its own, or null
     * for the default factory
     */
    public static synchronized void enableMessageSigning(
            ApproovDefaultMessageSigning.SignatureParametersFactory defaultFactory) {
        if (defaultFactory == null)
            defaultFactory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory();
        ApproovLog.d(TAG, "enableMessageSigning");
        messageSigning.setDefaultFactory(defaultFactory);
        messageSigningEnabled = true;
    }

    /**
     * Switches message signing off, so no Signature or Signature-Input header is added to any request.
     * The configured factories are kept for a later enableMessageSigning.
     */
    public static synchronized void disableMessageSigning() {
        ApproovLog.d(TAG, "disableMessageSigning");
        messageSigningEnabled = false;
    }

    /**
     * Indicates whether message signing is enabled.
     *
     * @return true if protected requests are signed, false otherwise
     */
    public static synchronized boolean isMessageSigningEnabled() {
        return messageSigningEnabled;
    }

    /**
     * Signs requests to one host with the given signature parameters factory instead of the default
     * factory passed to enableMessageSigning. This applies while message signing is enabled and is not
     * removed by initialize().
     *
     * @param hostName is the host name, matched without regard to case and without any port
     * @param factory is the signature parameters factory for the host, or null to remove it so that the default applies
     */
    public static synchronized void putMessageSigningHostFactory(String hostName,
            ApproovDefaultMessageSigning.SignatureParametersFactory factory) {
        ApproovLog.d(TAG, "putMessageSigningHostFactory " + hostName);
        messageSigning.putHostFactory(hostName, factory);
    }

    /**
     * Gets the class of the default signature parameters factory, or of the factory of one host, so that an
     * Approov framework package that replaces a factory can tell an app's own subclass from the standard
     * factory without calling it.
     * <p>
     * Note that this is for Approov's own framework packages only and is not part of the API for apps.
     *
     * @param hostName is the host name, matched as by putMessageSigningHostFactory, or null for the default factory
     * @return the class of the factory, or null if the host has no factory of its own
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    public static synchronized Class<? extends ApproovDefaultMessageSigning.SignatureParametersFactory>
            getMessageSigningFactoryClass(String hostName) {
        ApproovDefaultMessageSigning.SignatureParametersFactory factory = (hostName == null)
                ? messageSigning.getDefaultFactory() : messageSigning.getHostFactory(hostName);
        return (factory != null) ? factory.getClass() : null;
    }

    // null while message signing is disabled
    static synchronized ApproovDefaultMessageSigning getActiveMessageSigning() {
        return messageSigningEnabled ? messageSigning : null;
    }

    /**
     * Adds the name of a header which should be subject to secure strings substitution. This means that
     * if the header is present then the value will be used as a key to look up a secure string value
     * which will be substituted into the header value instead. This allows easy migration to the use of
     * secure strings. A required prefix may be specified to deal with cases such as the use of "Bearer "
     * prefixed before values in an authorization header. Note that the placeholder is only substituted
     * once the request is known to go to an https URL that Approov protects, on every adapter (on Volley
     * in the map the Request's getHeaders() returns). If the secure string fetch fails, the status is put in
     * place of the placeholder, after the prefix, as the status header writes it (such as "Bearer rejected").
     *
     * @param header is the header to be marked for substitution
     * @param requiredPrefix is any required prefix to the value being substituted or null if not required
     * @throws IllegalArgumentException if the header is already configured for token binding
     */
    public static synchronized void addSubstitutionHeader(String header, String requiredPrefix) {
        ApproovLog.d(TAG, "addSubstitutionHeader " + header + ", " + requiredPrefix);
        if ((header != null) && (bindingHeader != null)
                && header.equalsIgnoreCase(bindingHeader)) {
            throw new IllegalArgumentException("addSubstitutionHeader " + header + ": already the binding header");
        }

        String existingKey = findSubstitutionHeaderKey(header);
        if (existingKey != null)
            substitutionHeaders.remove(existingKey);
        if (requiredPrefix == null)
            substitutionHeaders.put(header, "");
        else
            substitutionHeaders.put(header, requiredPrefix);
    }

    /**
     * Removes a header previously added using addSubstitutionHeader.
     *
     * @param header is the header to be removed for substitution
     */
    public static synchronized void removeSubstitutionHeader(String header) {
        ApproovLog.d(TAG, "removeSubstitutionHeader " + header);
        String existingKey = findSubstitutionHeaderKey(header);
        if (existingKey != null)
            substitutionHeaders.remove(existingKey);
    }

    // the stored key matching without regard to case, or null if there is none
    private static String findSubstitutionHeaderKey(String header) {
        if ((header == null) || (substitutionHeaders == null))
            return null;
        for (String key : substitutionHeaders.keySet()) {
            if (header.equalsIgnoreCase(key))
                return key;
        }
        return null;
    }

    /**
     * Gets the map of headers that are subject to substitution.
     *
     * @return a map of headers that are subject to substitution, mapped to the required prefix
     */
    public static synchronized Map<String, String> getSubstitutionHeaders() {
        return new HashMap<>(substitutionHeaders);
    }

    /**
     * Adds a key name for a query parameter that should be subject to secure strings substitution. This
     * means that if the query parameter is present in a URL then the value will be used as a key to look
     * up a secure string value which will be substituted as the query parameter value instead. This
     * allows easy migration to the use of secure strings. Note that the placeholder is only substituted
     * once the request is known to go to an https URL that Approov protects. If the secure string fetch
     * fails, the status is put in place of the placeholder as the status header writes it (such as
     * "rejected").
     *
     * @param key is the query parameter key name to be added for substitution
     */
    public static synchronized void addSubstitutionQueryParam(String key) {
        ApproovLog.d(TAG, "addSubstitutionQueryParam " + key);
        try {
            Pattern pattern = Pattern.compile("[\\?&]" + Pattern.quote(key) + "=([^&;]+)");
            substitutionQueryParams.put(key, pattern);
        } catch (PatternSyntaxException e) {
            ApproovLog.e(TAG, "addSubstitutionQueryParam " + key + " error: " + e.getMessage());
        }
    }

    /**
     * Removes a query parameter key name previously added using addSubstitutionQueryParam.
     *
     * @param key is the query parameter key name to be removed for substitution
     */
    public static synchronized void removeSubstitutionQueryParam(String key) {
        ApproovLog.d(TAG, "removeSubstitutionQueryParam " + key);
        substitutionQueryParams.remove(key);
    }

    /**
     * Gets the map of substitution query parameters.
     *
     * @return a map of query parameters to be substituted, mapped to the compiled Pattern
     */
    public static synchronized Map<String, Pattern> getSubstitutionQueryParams() {
        return new HashMap<>(substitutionQueryParams);
    }

    /**
     * Adds an exclusion URL regular expression. If a URL for a request matches this regular expression
     * then it will not be subject to any Approov protection. Note that this facility must be used with
     * EXTREME CAUTION due to the impact of dynamic pinning. Pinning may be applied to all domains added
     * using Approov, and updates to the pins are received when an Approov fetch is performed. If you
     * exclude some URLs on domains that are protected with Approov, then these will be protected with
     * Approov pins but without a path to update the pins until a URL is used that is not excluded. Thus
     * you are responsible for ensuring that there is always a possibility of calling a non-excluded URL,
     * or you should make an explicit call to fetchToken if there are persistent pinning failures.
     * Conversely, use of this option may allow a connection to be established before any dynamic pins
     * have been received via Approov, thus potentially opening the channel to a MitM.
     *
     * @param urlRegex is the regular expression that will be compared against URLs to exclude them
     * @throws IllegalArgumentException if urlRegex is not a valid regular expression, in which case nothing is added
     */
    public static synchronized void addExclusionURLRegex(String urlRegex) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(urlRegex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("addExclusionURLRegex: invalid regular expression "
                    + urlRegex + ": " + e.getDescription(), e);
        }
        exclusionURLRegexs.put(urlRegex, pattern);
        ApproovLog.d(TAG, "addExclusionURLRegex " + urlRegex);
    }

    /**
     * Removes an exclusion URL regular expression previously added using addExclusionURLRegex.
     *
     * @param urlRegex is the regular expression that will be compared against URLs to exclude them
     */
    public static synchronized void removeExclusionURLRegex(String urlRegex) {
        ApproovLog.d(TAG, "removeExclusionURLRegex " + urlRegex);
        exclusionURLRegexs.remove(urlRegex);
    }

    /**
     * Gets a copy of the current exclusion URL regexs.
     *
     * @return Map of the exclusion regexs to their respective Patterns
     */
    public static synchronized Map<String, Pattern> getExclusionURLRegexs() {
        return new HashMap<>(exclusionURLRegexs);
    }

    /**
     * Registers a listener that is informed whenever the dynamic pinning configuration may have changed.
     * Networking stack adapters that hold derived pinning state (such as the OkHttp CertificatePinner)
     * register here so they can rebuild that state. Listeners cannot be removed. Note that this is for
     * adapters, including an app's own adapter for a third-party SDK that keeps a copy of the pins. A
     * configuration change is notified from a request through one of the package's networking stacks,
     * not from fetchToken: the SDK keeps a change that fetchToken receives, and getPins returns the new
     * pins at once, but the listener runs on the next such request, before its pin check.
     *
     * @param listener is the listener to run on pinning configuration changes
     */
    public static void registerPinsChangeListener(Runnable listener) {
        pinsChangeListeners.add(listener);
    }

    static void notifyPinsChanged() {
        for (Runnable listener : pinsChangeListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                ApproovLog.e(TAG, "Pins change listener failed: " + e.getMessage());
            }
        }
    }

    /**
     * Indicates whether lines that carry the loggable token, a request URL or the device ID may be
     * logged. These are only logged at the DEBUG level, so a release build does not write them to the
     * device log unless debug logging is enabled for the ApproovService tag. The URL is logged without
     * user information, query or fragment.
     *
     * @return true if sensitive lines may be logged, false otherwise
     */
    static boolean sensitiveLogging() {
        return ApproovLog.isDebugEnabled();
    }

    // a call into the Approov SDK through the facade
    private interface SdkCall<T> {
        T call(ApproovSdkFacade sdk);
    }

    /**
     * Makes a call into the Approov SDK. Every SDK call made after initialization goes through here or
     * fetchResult, so that a RuntimeException thrown by the SDK reaches the caller as an
     * ApproovException with the SDK's exception as its cause. On the request path an ApproovException
     * is an IOException, which every network stack reports as a failed request rather than as an
     * uncaught exception.
     *
     * @param name is the SDK method, for the log
     * @param call is the call to make
     * @return the SDK's result
     * @throws ApproovException if the SDK throws a RuntimeException
     */
    private static <T> T callSdk(String name, SdkCall<T> call) throws ApproovException {
        try {
            return call.call(sdkFacade);
        } catch (RuntimeException e) {
            String message = "Approov SDK " + name + " failed: " + e;
            ApproovLog.e(TAG, message);
            throw new ApproovException(message, e);
        }
    }

    // as callSdk, and also reports a missing result as an ApproovException
    private static Approov.TokenFetchResult fetchResult(String name, SdkCall<Approov.TokenFetchResult> call)
            throws ApproovException {
        Approov.TokenFetchResult result = callSdk(name, call);
        if (result == null) {
            ApproovLog.e(TAG, "Approov SDK " + name + " returned no result");
            throw new ApproovException("Approov SDK " + name + " returned no result");
        }
        return result;
    }

    /**
     * Reports a RuntimeException raised while processing a request as an ApproovException with it as
     * the cause. This is the last line on the request path of every adapter, so whatever goes wrong
     * inside the layer, including a service mutator hook throwing a RuntimeException, the network stack
     * sees an IOException and reports a failed request.
     *
     * @param where is the pipeline step, for the log and the message
     * @param e is the RuntimeException
     * @return the exception to throw
     */
    static ApproovException requestProcessingFailure(String where, RuntimeException e) {
        if (e instanceof UnsafeHeaderValueException)
            return ((UnsafeHeaderValueException) e).toApproovException();
        ApproovLog.e(TAG, "Approov " + where + " failed: " + e);
        return new ApproovException("Approov " + where + " failed: " + e, e);
    }

    // a call to a service mutator hook on the request path
    private interface HookCall<T> {
        T call() throws IOException;
    }

    /**
     * Calls a service mutator hook on the request path. An IOException thrown by the hook that is not an
     * Approov type is the app's own abort and passes through unchanged, as does an ApproovException
     * raised by a standard CLOSE_FAILURE decision during this call. Any other ApproovException and any
     * RuntimeException is the hook's failure: it is logged naming the hook and reported as an
     * ApproovException with it as the cause, so the request fails through the network stack's normal
     * error channel.
     *
     * @param hook is the name of the hook, for the log and the message
     * @param call is the call to make
     * @return the hook's result
     * @throws IOException if the hook throws its own IOException, makes a standard decision or fails
     */
    private static <T> T callHook(String hook, HookCall<T> call) throws IOException {
        java.util.Set<Throwable> enclosing = StandardDecisions.begin();
        try {
            return call.call();
        } catch (UnsafeHeaderValueException e) {
            // a value the hook set that a header cannot carry: never quoted
            throw e.toApproovException();
        } catch (RuntimeException e) {
            throw mutatorFailure(hook, e);
        } catch (ApproovException e) {
            if (StandardDecisions.contains(e))
                throw e;
            throw mutatorFailure(hook, e);
        } finally {
            StandardDecisions.end(enclosing);
        }
    }

    /**
     * Reports the failure of a service mutator hook, which is a RuntimeException or an ApproovException
     * that is not a standard decision, by logging it naming the hook and returning an ApproovException
     * with it as the cause. The adapters that ask the mutator whether to pin a connection report its
     * failure the same way.
     *
     * @param hook is the name of the hook
     * @param e is the exception the hook threw
     * @return the exception to throw
     */
    static ApproovException mutatorFailure(String hook, Exception e) {
        String message = "ApproovServiceMutator." + hook + " failed: " + e;
        ApproovLog.e(TAG, message);
        return new ApproovException(message, e);
    }

    // UnsafeHeaderValueException is thrown if a header value cannot be carried, naming only the header.
    static final class UnsafeHeaderValueException extends RuntimeException {
        UnsafeHeaderValueException(String header) {
            super("Approov header " + header + ": invalid character in value");
        }

        // logs this exception and converts it, keeping the message
        ApproovException toApproovException() {
            ApproovLog.e(TAG, getMessage());
            return new ApproovException(getMessage());
        }
    }

    // names the header, never the value; the placeholder stays and the request proceeds
    private static void warnUnsafeHeaderSecureString(String header) {
        ApproovLog.w(TAG, "Secure string not substituted in header " + header
                + ": invalid character in value, placeholder left");
    }

    // horizontal tab and printable ASCII only, which every supported network stack can carry
    static boolean isSafeHeaderValue(String value) {
        if (value == null)
            return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c != '\t') && ((c < 0x20) || (c > 0x7e)))
                return false;
        }
        return true;
    }

    // every header value set by the pipeline, message signing or a hook is checked before it reaches the stack
    static ApproovRequestContext checkedContext(ApproovRequestContext context) {
        if ((context == null) || (context instanceof CheckedRequestContext))
            return context;
        return new CheckedRequestContext(context);
    }

    // CheckedRequestContext is a request context that refuses header values a header cannot carry
    private static final class CheckedRequestContext implements ApproovRequestContext {
        // the request context being wrapped
        private final ApproovRequestContext delegate;

        CheckedRequestContext(ApproovRequestContext delegate) {
            this.delegate = delegate;
        }

        @Override public String getMethod() { return delegate.getMethod(); }
        @Override public String getURL() { return delegate.getURL(); }
        @Override public String getTargetURI() { return delegate.getTargetURI(); }
        @Override public boolean supportsURLMutation() { return delegate.supportsURLMutation(); }
        @Override public void setURL(String url) throws ApproovException { delegate.setURL(url); }
        @Override public String getHeader(String name) { return delegate.getHeader(name); }
        @Override public List<String> getHeaders(String name) { return delegate.getHeaders(name); }
        @Override public List<String> getHeaderNames() { return delegate.getHeaderNames(); }
        @Override public void removeHeader(String name) { delegate.removeHeader(name); }
        @Override public boolean supportsHeaderSubstitution() { return delegate.supportsHeaderSubstitution(); }
        @Override public boolean hasBody() { return delegate.hasBody(); }
        @Override public byte[] getBodyBytes() { return delegate.getBodyBytes(); }

        @Override
        public void setHeader(String name, String value) {
            if (!isSafeHeaderValue(value))
                throw new UnsafeHeaderValueException(name);
            delegate.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            if (!isSafeHeaderValue(value))
                throw new UnsafeHeaderValueException(name);
            delegate.addHeader(name, value);
        }
    }

    // only direct methods throw when protection is not enabled, never the request path
    private static void requireProtection(String method) throws ApproovException {
        if (!isApproovProtectionEnabled()) {
            ApproovLog.e(TAG, method + ": Approov protection not enabled");
            throw new ApproovException(method + ": Approov protection not enabled");
        }
    }

    /**
     * Gets the device ID used by Approov to identify the particular device that the SDK is running on.
     * Note that different Approov apps on the same device will return a different ID. Moreover, the ID
     * may be changed by an uninstall and reinstall of the app.
     *
     * @return String of the device ID
     * @throws ApproovException if there was a problem, such as Approov protection not being enabled
     */
    public static String getDeviceID() throws ApproovException {
        requireProtection("getDeviceID");
        String deviceID = callSdk("getDeviceID", ApproovSdkFacade::getDeviceID);
        if (sensitiveLogging())
            ApproovLog.d(TAG, "getDeviceID: " + deviceID);
        return deviceID;
    }

    /**
     * Gets the current time in milliseconds since the epoch, adjusted by the offset between the device clock and the
     * clock of the Approov servers that the SDK learns at each attestation. Before the installation's first
     * attestation it is the device clock. A device whose clock is wrong still gets a time the backend agrees with,
     * which is why message signatures take their created and expires times from it. Use it for timestamps of your
     * own that your backend compares with its clock. With an Approov SDK earlier than 3.7.0 it is the device clock.
     *
     * @return the current time in milliseconds since the epoch, adjusted to the Approov servers' clock
     * @throws ApproovException if there was a problem, such as Approov protection not being enabled
     */
    public static long getServerAdjustedCurrentTimeMS() throws ApproovException {
        requireProtection("getServerAdjustedCurrentTimeMS");
        return callSdk("getServerAdjustedCurrentTimeMS", ApproovSdkFacade::getServerAdjustedCurrentTimeMS);
    }

    // the time message signatures are stamped with: the server adjusted time while protection is enabled, so a wrong
    // device clock does not put created and expires outside the backend's window, and otherwise the device clock;
    // a signature is never left out for want of it, so an SDK failure falls back to the device clock and is logged
    static long signatureTimeMillis() {
        if (isApproovProtectionEnabled()) {
            try {
                return sdkFacade.getServerAdjustedCurrentTimeMS();
            } catch (RuntimeException e) {
                ApproovLog.w(TAG, "Message signing: server adjusted time unavailable, using the device clock: " + e);
            }
        }
        return System.currentTimeMillis();
    }

    /**
     * Directly sets the data hash to be included in subsequently fetched Approov tokens. If the hash is
     * different from any previously set value then this will cause the next token fetch operation to
     * fetch a new token with the correct payload data hash. The hash appears in the 'pay' claim of the
     * Approov token as a base64 encoded string of the SHA256 hash of the data. Note that the data is
     * hashed locally and never sent to the Approov cloud service.
     *
     * @param data is the data to be hashed and set in the token
     * @throws ApproovException if there was a problem, such as Approov protection not being enabled
     */
    public static void setDataHashInToken(String data) throws ApproovException {
        requireProtection("setDataHashInToken");
        callSdk("setDataHashInToken", sdk -> {
            sdk.setDataHashInToken(data);
            return null;
        });
        ApproovLog.d(TAG, "setDataHashInToken");
    }

    /**
     * Performs an Approov token fetch for the given URL. This should be used in situations where it is
     * not possible to use the networking interception to add the token. This operation will sometimes
     * require network access so may take some time to complete. If the attestation fails for any reason
     * then an ApproovException is thrown. Note that the returned token should NEVER be cached by your
     * app, you should call this function when it is needed.
     *
     * @param url is the full URL (including path) for the token fetch
     * @return String of the fetched token
     * @throws ApproovException if there was a problem, such as Approov protection not being enabled
     */
    public static String fetchToken(String url) throws ApproovException {
        requireProtection("fetchToken");
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = fetchResult("fetchApproovTokenAndWait", sdk -> sdk.fetchApproovTokenAndWait(url));
        } catch (ApproovException e) {
            recordLastARC(null);
            throw e;
        }
        recordLastARC(approovResults);
        ApproovLog.d(TAG, "fetchToken: " + approovResults.getStatus());

        // process the status using the mutator
        getServiceMutator().handleFetchTokenResult(approovResults);
        return approovResults.getToken();
    }

    /**
     * Gets the signature for the given message. This uses an account specific message signing key that
     * is transmitted to the SDK after a successful fetch if the facility is enabled for the account.
     * Note that if the attestation failed then the signing key provided is actually random so that the
     * signature will be incorrect. An Approov token should always be included in the message being
     * signed and sent alongside this signature to prevent replay attacks.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature
     * @throws ApproovException if there was a problem, such as Approov protection not being enabled
     */
    public static String getAccountMessageSignature(String message) throws ApproovException {
        requireProtection("getAccountMessageSignature");
        String signature = callSdk("getAccountMessageSignature", sdk -> sdk.getAccountMessageSignature(message));
        ApproovLog.d(TAG, "getAccountMessageSignature");
        if (signature == null)
            throw new ApproovException("getAccountMessageSignature: no signature available");
        return signature;
    }

    /**
     * Gets the account signature for the given message, as getAccountMessageSignature. The first call in
     * the process logs a deprecation warning. Deprecated: use getAccountMessageSignature instead.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature
     * @throws ApproovException if there was a problem, such as Approov protection not being enabled
     */
    @Deprecated
    public static String getMessageSignature(String message) throws ApproovException {
        if (messageSignatureDeprecationLogged.compareAndSet(false, true))
            ApproovLog.w(TAG, "getMessageSignature is deprecated: use getAccountMessageSignature");
        requireProtection("getMessageSignature");
        return getAccountMessageSignature(message);
    }

    // whether the deprecation warning of getMessageSignature was logged in this process
    private static final AtomicBoolean messageSignatureDeprecationLogged = new AtomicBoolean();

    /**
     * Gets the install signature for the given message. This uses an app install specific message
     * signing key that is generated the first time an app launches. This signing mechanism uses an ECC
     * key pair where the private key is managed by the secure element or trusted execution environment
     * of the device. Where it can, Approov uses attested key pairs to perform the message signing.
     * <p>
     * An Approov token should always be included in the message being signed and sent alongside this
     * signature to prevent replay attacks.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature in ASN.1 DER format
     * @throws ApproovException if there was a problem, such as Approov protection not being enabled
     */
    public static String getInstallMessageSignature(String message) throws ApproovException {
        requireProtection("getInstallMessageSignature");
        String signature = callSdk("getInstallMessageSignature", sdk -> sdk.getInstallMessageSignature(message));
        ApproovLog.d(TAG, "getInstallMessageSignature");
        if (signature == null)
            throw new ApproovException("getInstallMessageSignature: no signature available");
        return signature;
    }

    /**
     * Fetches a secure string with the given key. If newDef is not null then a secure string for the
     * particular app instance may be defined. In this case the new value is returned as the secure
     * string. Use of an empty string for newDef removes the string entry. Note that this call may
     * require network transaction and thus may block for some time, so should not be called from the UI
     * thread. Note that the returned string should NEVER be cached by your app, you should call this
     * function when it is needed.
     *
     * @param key is the secure string key to be found
     * @param newDef is any new definition for the secure string, or null for lookup only
     * @return secure string (should not be cached by your app) or null if it was not defined
     * @throws ApproovException if there was a problem, such as Approov protection not being enabled
     */
    public static String fetchSecureString(String key, String newDef) throws ApproovException {
        requireProtection("fetchSecureString");
        // determine the type of operation as the values themselves cannot be logged
        String type = "lookup";
        if (newDef != null)
            type = "definition";

        // fetch any secure string keyed by the value, reporting any exception the SDK throws
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = fetchResult("fetchSecureStringAndWait", sdk -> sdk.fetchSecureStringAndWait(key, newDef));
        } catch (ApproovException e) {
            recordLastARC(null);
            throw e;
        }
        recordLastARC(approovResults);
        ApproovLog.d(TAG, "fetchSecureString " + type + " for " + key + ": " + approovResults.getStatus());

        // process the returned Approov status using the mutator
        getServiceMutator().handleFetchSecureStringResult(approovResults, type, key);
        return approovResults.getSecureString();
    }

    /**
     * Fetches a custom JWT with the given payload. Note that this call will require network transaction
     * and thus will block for some time, so should not be called from the UI thread.
     *
     * @param payload is the marshaled JSON object for the claims to be included
     * @return custom JWT string
     * @throws ApproovException if there was a problem, such as Approov protection not being enabled
     */
    public static String fetchCustomJWT(String payload) throws ApproovException {
        requireProtection("fetchCustomJWT");
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = fetchResult("fetchCustomJWTAndWait", sdk -> sdk.fetchCustomJWTAndWait(payload));
        } catch (ApproovException e) {
            recordLastARC(null);
            throw e;
        }
        recordLastARC(approovResults);
        ApproovLog.d(TAG, "fetchCustomJWT: " + approovResults.getStatus());

        // process the returned Approov status using the mutator
        getServiceMutator().handleFetchCustomJWTResult(approovResults);
        return approovResults.getToken();
    }

    /**
     * Gets the Attestation Response Code (ARC) from the most recent fetch this layer performed, whether
     * a token, secure string or custom JWT fetch from the request path of any networking stack or from a
     * direct method. It performs no fetch of its own, so it can be read after a rejected request to
     * correlate with your backend's view. Prefer receiving the ARC on the server side where possible.
     * <p>
     * The empty string is returned when no fetch has been made since initialization, when the last
     * fetch carried no ARC, when ARC is not enabled for the account, or when the last fetch failed
     * without an ARC.
     *
     * @return the ARC of the most recent fetch, or an empty string
     */
    public static synchronized String getLastARC() {
        return lastARC;
    }

    // a null or empty ARC clears the previous value, since the ARC belongs to the last fetch
    static synchronized void recordLastARC(Approov.TokenFetchResult approovResults) {
        String arc = (approovResults != null) ? approovResults.getARC() : null;
        lastARC = (arc != null) ? arc : "";
    }

    /**
     * Sets an install attributes token to be sent to the server and associated with this particular app
     * installation for future Approov token fetches. The token must be signed, within its expiry time
     * and bound to the correct device ID for it to be accepted by the server. Calling this method
     * ensures that the next call to fetch an Approov token will not use a cached version, so that this
     * information can be transmitted to the server.
     *
     * @param attrs is the signed JWT holding the new install attributes
     * @throws ApproovException if there was a problem, such as an invalid attrs parameter or Approov
     * protection not being enabled
     */
    public static void setInstallAttributes(String attrs) throws ApproovException {
        requireProtection("setInstallAttributes");
        callSdk("setInstallAttrsInToken", sdk -> {
            sdk.setInstallAttrsInToken(attrs);
            return null;
        });
        ApproovLog.d(TAG, "setInstallAttributes");
    }

    /**
     * Gets the dynamic pins the Approov SDK holds for the app's API domains, keyed by domain, of the
     * given type. The "*" domain holds the pins of the managed trust roots, applied to a domain that has
     * no pins of its own.
     *
     * @param pinType is the pin type, "public-key-sha256"
     * @return the pins keyed by domain, or null if the SDK holds none
     * @throws ApproovException if there was a problem, such as Approov protection not being enabled
     */
    public static Map<String, List<String>> getPins(String pinType) throws ApproovException {
        requireProtection("getPins");
        return callSdk("getPins", sdk -> sdk.getPins(pinType));
    }

    // uses reflection so that older SDK versions without the facility remain supported
    private static String getTokenFetchTraceID(Approov.TokenFetchResult approovResults) {
        try {
            Method method = approovResults.getClass().getMethod("getTraceID");
            Object value = method.invoke(approovResults);
            return (value instanceof String) ? (String) value : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ApproovPreparedRequest holds the outcome of the first part of request processing performed by
     * prepareRequest. The token fetch and the header and query substitutions have been resolved, but
     * the mutator processed-request callback and message signing have not yet run. Callers that need to
     * defer them, for example until the request body has been buffered, hold this object and later call
     * completeProcessing.
     */
    public static final class ApproovPreparedRequest {
        // the mutator in force when the request was first processed
        private final ApproovServiceMutator mutator;

        // the mutations applied to the request so far
        private final ApproovRequestMutations changes;

        // true if the processed-request callback applies to this request
        private final boolean invokeProcessedCallback;

        // URL whose domain the current protection was issued for, which follows the request if a redirect
        // moves it to a new domain
        private String protectedURL;

        // monotonic time in milliseconds at which the current protection was applied
        private long protectedAtMillis;

        // names of every header the processed-request callback has added on any attempt, accumulated so
        // they can be stripped from retries built from the application-level request
        private List<String> mutatorAddedHeaders = new ArrayList<>();

        // true once the processed-request callback has run
        private boolean processedCallbackCompleted;

        // URL (after any query substitution) of the attempt the current protection was applied to, where
        // an attempt that differs was rebuilt by the network stack and is classified afresh
        private String attemptURL;

        // method of the attempt the current protection was applied to
        private String attemptMethod;

        // every header substitution made on this request, as placeholder and a digest of the value, so
        // the placeholders can be restored on any later attempt still carrying a secret
        private List<HeaderSubstitution> headerSubstitutions;

        // every query parameter substitution made on this request, as parameter, placeholder and a
        // digest of the value, so a retry to a URL the layer produced has the placeholders restored
        private List<QuerySubstitution> querySubstitutions;

        // SHA-256 digests of every URL of this request into which a query secure string was substituted,
        // where a redirect target is the server's URL and is left exactly as the server sent it
        private final List<byte[]> substitutedURLDigests = new ArrayList<>();

        // SHA-256 digest of the URL the app gave, whose query values are the app's placeholders
        private final byte[] appURLDigest;

        // true if the URL of the attempt the current protection was applied to is the server's, such as a
        // redirect target, whose query values are never looked up
        private boolean attemptServerURL;

        // SHA-256 digest of each header's values, keyed by lower case name, as the protected attempt
        // reached the network, or null before that, so an attempt whose headers differ is known to be
        // rebuilt (digests so no credential or secure string is kept)
        private Map<String, byte[]> attemptHeaderDigests;

        // the names of the layer's own headers configured when the request was first processed, which
        // every attempt of the request uses
        private final LayerHeaders layerHeaders;

        // names of every token, status and trace ID header the layer has added on any attempt, so a
        // rebuilt attempt still carrying one has it stripped
        private List<String> layerAddedHeaders = new ArrayList<>();

        ApproovPreparedRequest(ApproovServiceMutator mutator, ApproovRequestMutations changes,
                boolean invokeProcessedCallback, String fetchURL, long protectedAtMillis,
                ApproovRequestContext context, List<HeaderSubstitution> headerSubstitutions,
                List<QuerySubstitution> querySubstitutions, LayerHeaders layerHeaders,
                List<String> layerAddedHeaders) {
            this.mutator = mutator;
            this.layerHeaders = layerHeaders;
            this.layerAddedHeaders = new ArrayList<>(layerAddedHeaders);
            this.changes = changes;
            this.invokeProcessedCallback = invokeProcessedCallback;
            this.protectedURL = fetchURL;
            this.protectedAtMillis = protectedAtMillis;
            this.attemptURL = context.getURL();
            this.attemptMethod = context.getMethod();
            this.headerSubstitutions = headerSubstitutions;
            this.querySubstitutions = querySubstitutions;
            this.appURLDigest = HeaderSubstitution.digest(fetchURL);
            if (!querySubstitutions.isEmpty())
                rememberSubstitutedURL(context.getURL());
        }

        // the URL the app gave or one the layer produced from it, never a redirect target
        private boolean isAppURL(String url) {
            return MessageDigest.isEqual(HeaderSubstitution.digest(url), appURLDigest) || isSubstitutedURL(url);
        }

        private synchronized void rememberSubstitutedURL(String url) {
            byte[] digest = HeaderSubstitution.digest(url);
            for (byte[] known : substitutedURLDigests) {
                if (MessageDigest.isEqual(known, digest))
                    return;
            }
            substitutedURLDigests.add(digest);
        }

        private synchronized boolean isSubstitutedURL(String url) {
            byte[] digest = HeaderSubstitution.digest(url);
            for (byte[] known : substitutedURLDigests) {
                if (MessageDigest.isEqual(known, digest))
                    return true;
            }
            return false;
        }

        /**
         * Gets the mutations applied to the request so far.
         *
         * @return the request mutations, never null
         */
        public ApproovRequestMutations getChanges() {
            return changes;
        }

        /**
         * Asks the mutator that processed this request whether an attempt to a URL is pinned, as every
         * attempt of a request is decided by the mutator in force when the layer first processed it. Note
         * that this is for adapters, not for app code.
         *
         * @param url is the URL of the attempt
         * @return true if the attempt is pinned, false otherwise
         * @throws IOException if the mutator aborts the attempt
         */
        public boolean handlePinningShouldProcessRequest(String url) throws IOException {
            return mutator.handlePinningShouldProcessRequest(url);
        }

        /**
         * Indicates whether the layer processed this request, so that the processed-request callback will
         * be invoked by completeProcessing and its later attempts are re-evaluated. This is true for a
         * token-protected request (SUCCESS), a secrets-only request (UNPROTECTED_URL), or a failure the
         * mutator lets proceed on an Approov API domain. It is false for an excluded request, before
         * initialization or in bypass mode, for a host not added to Approov (UNKNOWN_URL), and for a
         * failure on a host that is not an Approov API domain.
         *
         * @return true if the processed-request callback applies to this request, false otherwise
         */
        public boolean isProcessed() {
            return invokeProcessedCallback;
        }

        /**
         * Indicates whether this request can safely refresh its protection. Before the processed callback
         * first runs refresh is safe for every mutator, and afterwards the mutator must support it.
         *
         * @return true if refresh is supported, false otherwise
         */
        public synchronized boolean supportsProtectionRefresh() {
            try {
                return refreshSupported();
            } catch (ApproovException e) {
                // already logged naming the hook
                return false;
            }
        }

        // as supportsProtectionRefresh
        private synchronized boolean refreshSupported() throws ApproovException {
            // before the callback's first invocation refreshing is safe for every mutator, because the
            // callback will still run only once
            return invokeProcessedCallback
                    && (!processedCallbackCompleted || mutatorSupportsRefresh());
        }

        private boolean mutatorSupportsRefresh() throws ApproovException {
            try {
                return mutator.supportsProtectionRefresh();
            } catch (RuntimeException e) {
                throw mutatorFailure("supportsProtectionRefresh", e);
            }
        }

        // always before the first invocation, afterwards only if the mutator supports protection refresh
        private synchronized boolean canReinvokeProcessedCallback() throws ApproovException {
            return !processedCallbackCompleted || mutatorSupportsRefresh();
        }

        /**
         * Fetches the Approov token for the given URL, updating the token binding hash and any changed
         * pins first, classifies the attempt from the result and applies the token, status and trace ID
         * headers it allows.
         *
         * @param context is the request context to update
         * @param url is the URL for which to fetch the token
         * @return the fetch result and what the attempt carries
         * @throws IOException if the fetch result must abort the request
         */
        private Classified fetchAndApplyToken(ApproovRequestContext context, String url)
                throws IOException {
            Approov.TokenFetchResult results = bindAndFetchToken(context, url);
            if (sensitiveLogging())
                ApproovLog.d(TAG, "Refreshed token for " + ApproovLog.loggableURL(url) + ": " + results.getLoggableToken());
            updatePinsIfConfigChanged(results);
            Channel channel = classify(mutator, results, url);
            List<String> added = applyLayerHeaders(context, results, channel, layerHeaders, changes);
            synchronized (this) {
                layerAddedHeaders = accumulate(layerAddedHeaders, added);
            }
            return new Classified(results, channel);
        }

        /**
         * Refreshes this request when its protection has exceeded the configured age. The protection is
         * stripped and applied afresh, or removed if the refreshed fetch reports that the URL is not
         * protected. It is only performed if the mutator supports protection refresh, since the
         * processed-request callback runs again. Adapters call this at their closest available
         * pre-transmission hook.
         *
         * @param context is the request context to refresh
         * @return true if the protection was refreshed or removed, false if it was still fresh, refresh is
         * disabled or the mutator does not support it
         * @throws IOException if refresh must abort the request
         */
        public boolean refreshProtectionIfStale(ApproovRequestContext context)
                throws IOException {
            try {
                return refreshIfStale(checkedContext(context));
            } catch (RuntimeException e) {
                throw requestProcessingFailure("protection refresh", e);
            }
        }

        // as refreshProtectionIfStale
        private boolean refreshIfStale(ApproovRequestContext context) throws IOException {
            long refreshPeriodMS = getStaleProtectionRefreshPeriod();
            long protectedAt;
            synchronized (this) {
                protectedAt = protectedAtMillis;
            }
            if ((refreshPeriodMS <= 0) || (protectedAt < 0))
                return false;

            // check the age, where a clock that rolled back always refreshes
            long heldMS = monotonicClock.elapsedRealtime() - protectedAt;
            boolean clockRolledBack = heldMS < 0;
            if (!clockRolledBack && (heldMS <= refreshPeriodMS))
                return false;
            if (!refreshSupported()) {
                ApproovLog.d(TAG, "refreshProtection: held " + heldMS + "ms, mutator does not support refresh");
                return false;
            }

            // strip the protection and apply it afresh, as for a rebuilt attempt
            ApproovLog.d(TAG, "refreshProtection: held " + heldMS + "ms, refreshing before transmission");
            return reapplyProtection(context, false, "Stale request");
        }

        /**
         * Ensures the Approov protection applied to this request is correct for the transmission attempt
         * about to be made. Stale protection is refreshed, and the protection is re-evaluated whenever the
         * attempt is not the one it was applied to, as happens when the network stack rebuilds the request
         * to follow a redirect or retry. Adapters that see every attempt, such as the Cronet adapter, call
         * this immediately before each attempt.
         * <p>
         * An attempt whose URL, method or headers differ from the one the protection was applied to was
         * rebuilt. Its token, status, trace ID and signature headers and any headers added by the
         * processed-request callback are removed, secure string headers and query parameters the layer
         * substituted are restored to their placeholders, and on an origin change any Authorization header
         * is removed, as OkHttp does. The attempt is then classified afresh for its own URL, with the
         * header names, token prefix and mutator captured when the request was first processed. Note that
         * a redirect target URL is left exactly as the server sent it: its query values are never mapped back
         * to the app's placeholders nor looked up as secure string keys.
         *
         * @param context is the request context for the attempt, mutated in place
         * @return true if the request was changed, false if it should proceed unchanged
         * @throws IOException if the attempt must not proceed
         */
        public boolean refreshProtectionForAttempt(ApproovRequestContext context)
                throws IOException {
            try {
                return refreshForAttempt(checkedContext(context));
            } catch (RuntimeException e) {
                throw requestProcessingFailure("protection refresh", e);
            }
        }

        // as refreshProtectionForAttempt
        private boolean refreshForAttempt(ApproovRequestContext context) throws IOException {
            if (!invokeProcessedCallback)
                return false;

            // determine if the attempt was rebuilt by comparing it with the protected one
            String baselineURL;
            String baselineMethod;
            synchronized (this) {
                baselineURL = attemptURL;
                baselineMethod = attemptMethod;
            }
            String url = context.getURL();
            String method = context.getMethod();
            boolean rebuilt = !url.equals(baselineURL)
                    || ((method != null) && (baselineMethod != null)
                            && !method.equalsIgnoreCase(baselineMethod))
                    || headersChanged(context);
            boolean originChanged = rebuilt && !isSameOrigin(baselineURL, url);

            // a rebuilt attempt is always stripped and protected afresh, whatever the mutator
            if (rebuilt)
                return reapplyProtection(context, originChanged,
                        "Request rebuilt for " + (originChanged ? "a new origin" : "the same origin"));

            // the attempt as transmitted is the baseline for later attempts if it is the first to reach
            // the network or its protection was refreshed
            boolean refreshed = refreshIfStale(context);
            synchronized (this) {
                if (refreshed || (attemptHeaderDigests == null))
                    attemptHeaderDigests = digestHeaders(context);
            }
            return refreshed;
        }

        /**
         * Determines whether the headers of an attempt differ from those of the protected attempt as it
         * reached the network, including the headers the layer manages. Headers are compared by digest,
         * so a secure string header is unchanged while it holds the value the layer installed. Before the
         * first network attempt, or if the request context cannot enumerate its headers, no change is
         * detected.
         *
         * @param context is the request context for the attempt
         * @return true if a header was added, removed or changed, false otherwise
         */
        private boolean headersChanged(ApproovRequestContext context) {
            Map<String, byte[]> baseline;
            synchronized (this) {
                baseline = attemptHeaderDigests;
            }
            if (baseline == null)
                return false;
            Map<String, byte[]> current = digestHeaders(context);
            if (current == null)
                return false;

            // compare every header in either set
            Set<String> names = new HashSet<>(baseline.keySet());
            names.addAll(current.keySet());
            for (String name : names) {
                byte[] was = baseline.get(name);
                byte[] now = current.get(name);
                if ((was != null) && (now != null) && MessageDigest.isEqual(was, now))
                    continue;
                // log the name only, as header values may be credentials
                ApproovLog.d(TAG, "Header " + name + " changed since protection was applied");
                return true;
            }
            return false;
        }

        // keyed by lower case name, or null if the request context cannot enumerate its headers
        private static Map<String, byte[]> digestHeaders(ApproovRequestContext context) {
            List<String> names = context.getHeaderNames();
            if (names == null)
                return null;
            Map<String, byte[]> digests = new HashMap<>();
            for (String name : names) {
                String key = name.toLowerCase(Locale.ROOT);
                if (digests.containsKey(key))
                    continue;
                MessageDigest digest = HeaderSubstitution.sha256();
                for (String value : context.getHeaders(name)) {
                    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                    // length prefixed so that the value boundaries are part of the digest
                    digest.update(new byte[] { (byte) (bytes.length >>> 24),
                            (byte) (bytes.length >>> 16), (byte) (bytes.length >>> 8),
                            (byte) bytes.length });
                    digest.update(bytes);
                }
                digests.put(key, digest.digest());
            }
            return digests;
        }

        /**
         * Strips the Approov protection of this request and applies it afresh for the attempt about to be
         * made, which is either a rebuilt or a stale one. The processed-request callback runs again only
         * if the mutator supports protection refresh, while message signing, when enabled, always covers
         * the reprotected attempt.
         *
         * @param context is the request context for the attempt, mutated in place
         * @param originChanged is true if the attempt targets a different origin
         * @param reason is why the protection is reapplied, for the log
         * @return true as the request is always changed on this path
         * @throws IOException if the attempt must not proceed
         */
        private boolean reapplyProtection(ApproovRequestContext context,
                boolean originChanged, String reason) throws IOException {
            ApproovLog.d(TAG, reason + ": reapplying Approov protection");

            // none of this request's state is changed until the re-protection is applied or removed, so a
            // retry after an exception re-enters with the same knowledge of what the layer added
            List<String> previousAddedHeaders;
            List<HeaderSubstitution> previousSubstitutions;
            List<QuerySubstitution> previousQuerySubstitutions;
            synchronized (this) {
                previousAddedHeaders = new ArrayList<>(mutatorAddedHeaders);
                previousAddedHeaders.addAll(layerAddedHeaders);
                previousSubstitutions = headerSubstitutions;
                previousQuerySubstitutions = querySubstitutions;
            }

            // credentials the app addressed to one origin are not sent to another
            if (originChanged)
                context.removeHeader("Authorization");

            // the query of any other URL, such as a redirect target, is the server's and is sent as it is
            boolean appQuery;
            synchronized (this) {
                String url = context.getURL();
                appQuery = url.equals(attemptURL) ? !attemptServerURL : isAppURL(url);
            }

            // the headers the layer and the processed-request callback added were issued for a previous
            // attempt, so they are removed and a protected attempt has them applied afresh below
            for (String header : previousAddedHeaders)
                context.removeHeader(header);

            // restore the app's placeholders so the attempt starts from what the app sent
            for (HeaderSubstitution substitution : previousSubstitutions)
                substitution.restore(context);
            restoreQueryPlaceholders(context, previousQuerySubstitutions);

            // an excluded URL gets no Approov processing at all, so remove the protection applied for the
            // previous attempt rather than transmit it
            String fetchURL = context.getURL();
            if (!callHook("handleInterceptorShouldProcessRequest",
                    () -> mutator.handleInterceptorShouldProcessRequest(context))) {
                removeProtection(context, fetchURL, !appQuery);
                return true;
            }

            // a cleartext attempt to a host that is not an Approov API domain receives nothing the layer adds,
            // and the SDK is not asked, as for a first attempt
            if (isCleartextOffApi(fetchURL)) {
                removeProtection(context, fetchURL, !appQuery);
                logCleartextOffApi(context, fetchURL);
                return true;
            }

            // the attempt is classified afresh from its own token fetch, so a host not added to Approov, or a
            // failure on a host that is not an API domain, receives nothing the layer adds
            Classified classified = fetchAndApplyToken(context, fetchURL);
            if (!classified.channel.applies()) {
                removeProtection(context, fetchURL, !appQuery);
                withholdSecureStrings(context, fetchURL, classified.results, classified.channel);
                return true;
            }

            ApproovRequestMutations attemptChanges = new ApproovRequestMutations();
            List<HeaderSubstitution> substitutions = Collections.emptyList();
            List<QuerySubstitution> queryParamSubstitutions = Collections.emptyList();
            if (!withholdSecureStrings(context, fetchURL, classified.results, classified.channel)) {
                substitutions = applyHeaderSubstitutions(context, mutator, attemptChanges);
                if (appQuery)
                    queryParamSubstitutions = applyQueryParamSubstitutions(context, mutator, attemptChanges);
            }
            changes.setSubstitutionHeaderKeys(attemptChanges.getSubstitutionHeaderKeys());
            changes.setSubstitutionQueryParamResults(attemptChanges.getOriginalURL(),
                    attemptChanges.getSubstitutionQueryParamKeys());
            if (!queryParamSubstitutions.isEmpty())
                rememberSubstitutedURL(context.getURL());

            // sign the request as it will be transmitted, and reinvoke the processed-request callback only if
            // the mutator supports that
            boolean invokeCallback = canReinvokeProcessedCallback();
            if (!invokeCallback)
                ApproovLog.d(TAG, "Protection reapplied without the processed request callback of " + describe(mutator));
            completeProcessing(context, invokeCallback);
            if (!queryParamSubstitutions.isEmpty())
                rememberSubstitutedURL(context.getURL());
            synchronized (this) {
                protectedURL = fetchURL;
                attemptURL = context.getURL();
                attemptMethod = context.getMethod();
                attemptHeaderDigests = digestHeaders(context);
                attemptServerURL = !appQuery;
                headerSubstitutions = accumulate(previousSubstitutions, substitutions);
                querySubstitutions = accumulate(previousQuerySubstitutions, queryParamSubstitutions);
            }
            return true;
        }

        /**
         * Records that the request proceeds without Approov protection, after the headers the layer and
         * the callback added have been removed and the substitutions restored. Stale protection refresh is
         * disabled until the request is protected again for a later attempt, and the record of what the
         * layer added and substituted is kept so a later retry is stripped in the same way.
         *
         * @param context is the request context the protection was removed from
         * @param fetchURL is the unprotected URL the removal was decided for
         * @param serverURL is true if the URL is the server's, such as a redirect target
         */
        private void removeProtection(ApproovRequestContext context, String fetchURL, boolean serverURL) {
            ApproovLog.d(TAG, "Removing Approov protection issued for a previous attempt");
            changes.setTokenHeaderKey(null);
            changes.setTokenHeaderPrefix(null);
            changes.setStatusHeaderKey(null);
            changes.setTraceIDHeaderKey(null);
            changes.setSubstitutionHeaderKeys(null);
            changes.setSubstitutionQueryParamResults(null, null);
            synchronized (this) {
                protectedAtMillis = -1;
                protectedURL = fetchURL;
                attemptURL = context.getURL();
                attemptMethod = context.getMethod();
                attemptHeaderDigests = digestHeaders(context);
                attemptServerURL = serverURL;
            }
        }

        /**
         * Determines whether two URLs have the same origin (scheme, host and port, with default ports made
         * explicit), as OkHttp decides whether a redirect keeps the Authorization header. The URLs are read
         * as the stack sends them, with WireUrl, since java.net.URI rejects some queries the stacks send.
         * URLs without a scheme or host, or with a malformed port, are treated as different origins.
         *
         * @param a is the first URL
         * @param b is the second URL
         * @return true if the URLs have the same origin, false otherwise
         */
        private static boolean isSameOrigin(String a, String b) {
            String schemeA = WireUrl.scheme(a);
            String hostA = WireUrl.host(a);
            int portA = effectivePort(schemeA, WireUrl.port(a));
            int portB = effectivePort(WireUrl.scheme(b), WireUrl.port(b));
            return (schemeA != null) && schemeA.equals(WireUrl.scheme(b))
                    && (hostA != null) && !hostA.isEmpty() && hostA.equals(WireUrl.host(b))
                    && (portA != WireUrl.MALFORMED_PORT) && (portA == portB);
        }

        /**
         * Restores the app's placeholder to every occurrence of a substituted query parameter whose value
         * is one the layer installed, when the attempt's URL is one the layer produced for this request,
         * so the attempt starts from what the app sent. Any other URL, such as a redirect target, is the
         * server's and is left exactly as the server sent it.
         *
         * @param context is the request context for the attempt, mutated in place
         * @param substitutions are the query parameter substitutions made on this request
         * @throws ApproovException if the restored URL cannot be applied
         */
        private void restoreQueryPlaceholders(ApproovRequestContext context,
                List<QuerySubstitution> substitutions) throws ApproovException {
            if (substitutions.isEmpty() || !context.supportsURLMutation())
                return;
            String url = context.getURL();
            if (!isSubstitutedURL(url))
                return;
            String restored = url;
            List<String> keys = new ArrayList<>();
            for (QuerySubstitution substitution : substitutions) {
                if (!keys.contains(substitution.key))
                    keys.add(substitution.key);
            }
            for (String key : keys) {
                Matcher matcher = queryParamPattern(key).matcher(restored);
                StringBuilder rebuilt = null;
                int lastEnd = 0;
                while (matcher.find()) {
                    String placeholder = installedPlaceholder(substitutions, key, matcher.group(1));
                    if (placeholder == null)
                        continue;
                    if (rebuilt == null)
                        rebuilt = new StringBuilder();
                    rebuilt.append(restored, lastEnd, matcher.start(1)).append(placeholder);
                    lastEnd = matcher.end(1);
                }
                if (rebuilt != null)
                    restored = rebuilt.append(restored, lastEnd, restored.length()).toString();
            }
            if (!restored.equals(url))
                context.setURL(restored);
        }

        // null if the value is not one the layer installed
        private static String installedPlaceholder(List<QuerySubstitution> substitutions,
                String key, String value) {
            for (QuerySubstitution substitution : substitutions) {
                if (substitution.key.equals(key) && substitution.isInstalledValue(value))
                    return substitution.placeholder;
            }
            return null;
        }

        // the earlier entries followed by any later ones not already present
        private static <T> List<T> accumulate(List<T> earlier, List<T> later) {
            List<T> all = new ArrayList<>(earlier.size() + later.size());
            all.addAll(earlier);
            for (T entry : later) {
                if (!all.contains(entry))
                    all.add(entry);
            }
            return all;
        }

        // the scheme's default port made explicit, or -1 if the scheme has none
        private static int effectivePort(String scheme, int port) {
            if (port != -1)
                return port;
            if ("https".equals(scheme))
                return 443;
            if ("http".equals(scheme))
                return 80;
            return -1;
        }

        /**
         * Invokes the mutator processed-request callback for the request if it is subject to Approov
         * processing, and then signs it if message signing is enabled. Note that any request body should
         * be available through the context by the time this is called, so a body digest can be made.
         *
         * @param context is the request context to complete processing on
         * @throws IOException if the request must not proceed
         */
        public void completeProcessing(ApproovRequestContext context) throws IOException {
            try {
                completeProcessing(checkedContext(context), true);
            } catch (RuntimeException e) {
                throw requestProcessingFailure("request processing", e);
            }
            boolean querySubstituted;
            synchronized (this) {
                querySubstituted = !querySubstitutions.isEmpty();
            }
            // the URL as the processed-request callback left it, which a retry the
            // stack builds from the application-level request carries
            if (querySubstituted)
                rememberSubstitutedURL(context.getURL());
        }

        // invokes the processed-request callback only if asked, then signs the request if signing is enabled
        private void completeProcessing(ApproovRequestContext context, boolean invokeCallback)
                throws IOException {
            if (!invokeProcessedCallback)
                return;

            List<String> addedHeaders = new ArrayList<>();
            ApproovRequestContext tracking = new AddedHeaderTrackingContext(context, addedHeaders);
            if (invokeCallback) {
                callHook("handleInterceptorProcessedRequest", () -> {
                    mutator.handleInterceptorProcessedRequest(tracking, changes);
                    return null;
                });
            }

            // message signing runs last, over whatever the processed-request callback changed, and only a
            // request carrying the token header is signed
            ApproovDefaultMessageSigning signing = getActiveMessageSigning();
            if (signing != null)
                signing.sign(tracking, changes);
            synchronized (this) {
                mutatorAddedHeaders = accumulate(mutatorAddedHeaders, addedHeaders);
                protectedAtMillis = monotonicClock.elapsedRealtime();
                if (invokeCallback)
                    processedCallbackCompleted = true;
                // the attempt the protection was applied to is the request as the callback left it, so a
                // callback that changes the URL or method does not make its own first attempt look rebuilt
                attemptURL = context.getURL();
                attemptMethod = context.getMethod();
            }
        }

        // AddedHeaderTrackingContext is a request context that records the headers newly added by the processed callback
        private static final class AddedHeaderTrackingContext implements ApproovRequestContext {
            // the request context being wrapped
            private final ApproovRequestContext delegate;

            // the names of the headers added through this context
            private final List<String> addedHeaders;

            AddedHeaderTrackingContext(ApproovRequestContext delegate, List<String> addedHeaders) {
                this.delegate = delegate;
                this.addedHeaders = addedHeaders;
            }

            @Override public String getMethod() { return delegate.getMethod(); }
            @Override public String getURL() { return delegate.getURL(); }
            @Override public String getTargetURI() { return delegate.getTargetURI(); }
            @Override public boolean supportsURLMutation() { return delegate.supportsURLMutation(); }
            @Override public void setURL(String url) throws ApproovException { delegate.setURL(url); }
            @Override public String getHeader(String name) { return delegate.getHeader(name); }
            @Override public List<String> getHeaders(String name) { return delegate.getHeaders(name); }
            @Override public List<String> getHeaderNames() { return delegate.getHeaderNames(); }
            @Override public boolean supportsHeaderSubstitution() {
                return delegate.supportsHeaderSubstitution();
            }
            @Override public boolean hasBody() { return delegate.hasBody(); }
            @Override public byte[] getBodyBytes() { return delegate.getBodyBytes(); }

            @Override
            public void setHeader(String name, String value) {
                if (delegate.getHeaders(name).isEmpty() && !containsIgnoreCase(addedHeaders, name))
                    addedHeaders.add(name);
                delegate.setHeader(name, value);
            }

            @Override
            public void removeHeader(String name) {
                delegate.removeHeader(name);
            }

            @Override
            public void addHeader(String name, String value) {
                if (delegate.getHeaders(name).isEmpty() && !containsIgnoreCase(addedHeaders, name))
                    addedHeaders.add(name);
                delegate.addHeader(name, value);
            }

            private static boolean containsIgnoreCase(List<String> values, String value) {
                for (String existing : values) {
                    if (existing.equalsIgnoreCase(value))
                        return true;
                }
                return false;
            }
        }
    }

    /**
     * Processes an outbound request through the Approov request pipeline. This performs, in order, the
     * mutator processing decision (which by default honors exclusion URL regexs), the token binding
     * update, the Approov token fetch with the token, status and optional TraceID headers, secure string
     * header and query parameter substitution, the mutator processed-request callback and finally, if
     * enabled, message signing. All networking stack adapters route their requests through this method
     * so that Approov behaves the same on every stack. Until Approov protection is enabled the request
     * is returned unchanged and the SDK is not called.
     *
     * @param context is the request to process, which is mutated in place
     * @return the mutations applied to the request, never null
     * @throws IOException if the request must not proceed, such as an ApproovException from CLOSE_FAILURE or
     * a custom mutator's own exception
     */
    public static ApproovRequestMutations processRequest(ApproovRequestContext context) throws IOException {
        ApproovPreparedRequest prepared = prepareRequest(context);
        prepared.completeProcessing(context);
        return prepared.getChanges();
    }

    /**
     * Performs all request processing except the final mutator processed-request callback and message
     * signing. This is used by adapters that must defer the callback, such as the buffered
     * HttpsURLConnection wrapper which needs the request body for message signing body digests. Most
     * adapters should use processRequest instead. Until Approov protection is enabled the request is
     * left unchanged and the SDK is not called.
     *
     * @param context is the request to process, which is mutated in place
     * @return the prepared request state, used to later complete the processing
     * @throws IOException if the request must not proceed
     */
    public static ApproovPreparedRequest prepareRequest(ApproovRequestContext context) throws IOException {
        try {
            return prepare(checkedContext(context));
        } catch (RuntimeException e) {
            throw requestProcessingFailure("request processing", e);
        }
    }

    // the processing of prepareRequest on a checked request context
    private static ApproovPreparedRequest prepare(ApproovRequestContext context) throws IOException {
        // cache the mutator for the duration of the processing so it is not changed mid-flight
        ApproovServiceMutator mutator = getServiceMutator();
        ApproovRequestMutations changes = new ApproovRequestMutations();

        // before any initialize, and in bypass mode, a request goes out without Approov processing and
        // the SDK is not called
        if (!isApproovProtectionEnabled()) {
            ApproovLog.d(TAG, (isApproovServiceEnabled() ? "Bypass mode" : "ApproovService not initialized")
                    + ": request proceeds without Approov processing");
            return new ApproovPreparedRequest(mutator, changes, false,
                    context.getURL(), -1, context, Collections.<HeaderSubstitution>emptyList(),
                    Collections.<QuerySubstitution>emptyList(), null, Collections.<String>emptyList());
        }

        // first check if we are to proceed with any Approov processing, as an excluded request carries no
        // secure string
        if (!callHook("handleInterceptorShouldProcessRequest",
                () -> mutator.handleInterceptorShouldProcessRequest(context))) {
            return new ApproovPreparedRequest(mutator, changes, false,
                    context.getURL(), -1, context, Collections.<HeaderSubstitution>emptyList(),
                    Collections.<QuerySubstitution>emptyList(), null, Collections.<String>emptyList());
        }

        // a cleartext request to a host that is not an Approov API domain goes out untouched without asking
        // the SDK, which could only answer BAD_URL after any fetch queued ahead of it
        String url = context.getURL();
        if (isCleartextOffApi(url)) {
            logCleartextOffApi(context, url);
            String[] tokenHeader = snapshotTokenHeader();
            LayerHeaders layerHeaders = new LayerHeaders(tokenHeader[0], tokenHeader[1], getStatusHeader(),
                    getTraceIDHeader());
            return new ApproovPreparedRequest(mutator, changes, false, url, -1,
                    context, Collections.<HeaderSubstitution>emptyList(),
                    Collections.<QuerySubstitution>emptyList(), layerHeaders, Collections.<String>emptyList());
        }

        // request an Approov token for the request URL, bound to the value of any token
        // binding header (presence is optional)
        Approov.TokenFetchResult approovResults = bindAndFetchToken(context, url);

        // provide information about the obtained token or error (note "approov token -check" can be used
        // to check the validity of the token and if you use token annotations they will appear here to
        // determine why a request is being rejected), only with debug logging as it carries the device ID
        if (sensitiveLogging())
            ApproovLog.d(TAG, "Token for " + ApproovLog.loggableURL(url) + ": " + approovResults.getLoggableToken());

        // force a pinning rebuild if there is any dynamic config update
        updatePinsIfConfigChanged(approovResults);

        // classify the request from the token fetch status and the mutator's token decision and add the
        // headers it allows, with the header names and prefix kept for every later attempt
        Channel channel = classify(mutator, approovResults, url);
        String[] tokenHeader = snapshotTokenHeader();
        LayerHeaders layerHeaders = new LayerHeaders(tokenHeader[0], tokenHeader[1], getStatusHeader(),
                getTraceIDHeader());
        if (!channel.applies()) {
            // a host not added to Approov, or a failure on a host that is not an API domain, goes out
            // untouched with no secure string looked up or substituted and nothing signed
            withholdSecureStrings(context, url, approovResults, channel);
            return new ApproovPreparedRequest(mutator, changes, false, url, -1,
                    context, Collections.<HeaderSubstitution>emptyList(),
                    Collections.<QuerySubstitution>emptyList(), layerHeaders, Collections.<String>emptyList());
        }
        List<String> layerAdded = applyLayerHeaders(context, approovResults, channel, layerHeaders, changes);

        // we now deal with any header substitutions, which may require further fetches but these should
        // be using cached results, and then any query parameter substitutions, but only for a request on a
        // protected channel that is https
        List<HeaderSubstitution> headerSubstitutions = Collections.emptyList();
        List<QuerySubstitution> querySubstitutions = Collections.emptyList();
        if (!withholdSecureStrings(context, url, approovResults, channel)) {
            headerSubstitutions = applyHeaderSubstitutions(context, mutator, changes);
            querySubstitutions = applyQueryParamSubstitutions(context, mutator, changes);
        }

        return new ApproovPreparedRequest(mutator, changes, true, url,
                monotonicClock.elapsedRealtime(), context, headerSubstitutions, querySubstitutions,
                layerHeaders, layerAdded);
    }

    // LayerHeaders holds the names of the layer's own headers for a request, captured when first processed
    static final class LayerHeaders {
        // the token header, or null if disabled
        final String token;

        // the prefix added before the token
        final String tokenPrefix;

        // the status header, or null if disabled
        final String status;

        // the trace ID header, or null if disabled
        final String traceID;

        LayerHeaders(String token, String tokenPrefix, String status, String traceID) {
            this.token = token;
            this.tokenPrefix = tokenPrefix;
            this.status = status;
            this.traceID = traceID;
        }
    }

    // Channel is what one attempt of a request carries, decided from its token fetch status and the
    // mutator's token decision, where secure strings follow the channel (SUCCESS or UNPROTECTED_URL)
    private static final class Channel {
        // the channel of an attempt that carries nothing
        static final Channel NOTHING = new Channel(false, false, false);

        // true if the token and trace ID headers, and with them the signatures, are added (SUCCESS only)
        final boolean tokenHeaders;

        // true if the status header is added
        final boolean statusHeader;

        // true if secure strings may be substituted (still only over https)
        final boolean secrets;

        Channel(boolean tokenHeaders, boolean statusHeader, boolean secrets) {
            this.tokenHeaders = tokenHeaders;
            this.statusHeader = statusHeader;
            this.secrets = secrets;
        }

        // true if the attempt carries anything the layer may add
        boolean applies() {
            return tokenHeaders || statusHeader || secrets;
        }
    }

    // Classified is a token fetch result with the channel it decided
    private static final class Classified {
        // the token fetch result
        final Approov.TokenFetchResult results;

        // the channel the result decided
        final Channel channel;

        Classified(Approov.TokenFetchResult results, Channel channel) {
            this.results = results;
            this.channel = channel;
        }
    }

    // any status other than SUCCESS, UNKNOWN_URL (host not added) and UNPROTECTED_URL (secrets-only API)
    private static boolean isFailureStatus(Approov.TokenFetchStatus status) {
        return (status != Approov.TokenFetchStatus.SUCCESS) && (status != Approov.TokenFetchStatus.UNKNOWN_URL)
                && (status != Approov.TokenFetchStatus.UNPROTECTED_URL);
    }

    /**
     * Classifies an attempt from its token fetch result. A failure status for a host that is not an
     * Approov API domain carries nothing, and the mutator's token decision is only consulted for an
     * abort of the app's own. Otherwise the mutator's token decision is made and any abort stands. When
     * it proceeds, SUCCESS carries the token, status and trace ID headers and the signatures, while any
     * other status carries only the status header on an Approov API domain. Secure strings follow the
     * channel, whatever the token decision.
     *
     * @param mutator is the mutator deciding
     * @param results is the token fetch result
     * @param url is the URL the token was fetched for
     * @return what the attempt carries
     * @throws IOException if the mutator aborts the attempt
     */
    private static Channel classify(ApproovServiceMutator mutator, Approov.TokenFetchResult results, String url)
            throws IOException {
        Approov.TokenFetchStatus status = results.getStatus();
        boolean failure = isFailureStatus(status);
        if (failure && !isApiDomain(url)) {
            // a custom mutator may opt in to aborting here, but a standard decision never does
            consultForFailureOffApi(mutator, results, url);
            if (ApproovLog.isDebugEnabled())
                ApproovLog.d(TAG, "Approov token fetch for " + loggableHost(url) + ": " + status + arcDetail(results)
                        + ", not an Approov API domain, proceeding untouched");
            return Channel.NOTHING;
        }
        boolean proceedWithHeaders = callHook("handleInterceptorFetchTokenResult",
                () -> mutator.handleInterceptorFetchTokenResult(results, url));
        boolean success = status == Approov.TokenFetchStatus.SUCCESS;
        boolean statusHeader = proceedWithHeaders && (success || failure || isApiDomain(url));
        if (failure && ApproovLog.isDebugEnabled())
            ApproovLog.d(TAG, "Approov token fetch for " + loggableHost(url) + ": " + status + arcDetail(results)
                    + (statusHeader ? ", proceeding with the status header only"
                            : ", proceeding with no Approov header"));
        return new Channel(proceedWithHeaders && success, statusHeader,
                success || (status == Approov.TokenFetchStatus.UNPROTECTED_URL));
    }

    /**
     * Consults the mutator's token decision for a failure status on a host that is not an Approov API
     * domain. An app may abort such a request by throwing from the hook, which follows the rules of
     * every hook. A standard decision is ignored here, and the hook's answer adds nothing, so the
     * request goes out untouched.
     *
     * @param mutator is the mutator deciding
     * @param results is the token fetch result
     * @param url is the request URL
     * @throws IOException if the app aborts the request or the hook fails
     */
    private static void consultForFailureOffApi(ApproovServiceMutator mutator, Approov.TokenFetchResult results,
            String url) throws IOException {
        String hook = "handleInterceptorFetchTokenResult";
        java.util.Set<Throwable> enclosing = StandardDecisions.begin();
        try {
            mutator.handleInterceptorFetchTokenResult(results, url);
        } catch (UnsafeHeaderValueException e) {
            throw e.toApproovException();
        } catch (RuntimeException e) {
            throw mutatorFailure(hook, e);
        } catch (IOException e) {
            if (StandardDecisions.contains(e)) {
                ApproovLog.d(TAG, "handleInterceptorFetchTokenResult: abort ignored for " + loggableHost(url)
                        + ", not an Approov API domain");
                return;
            }
            if (e instanceof ApproovException)
                throw mutatorFailure(hook, e);
            throw e;
        } finally {
            StandardDecisions.end(enclosing);
        }
    }

    /**
     * Indicates whether the host of a URL is an Approov API domain, added with "approov api -add" with or
     * without -noApproovToken. This is a key of the SDK pin set other than "*", compared case
     * insensitively and ignoring one trailing dot. An empty pin set, or one the SDK cannot supply, lists
     * no host.
     *
     * @param url is the request URL
     * @return true if the host is an Approov API domain, false otherwise
     */
    private static boolean isApiDomain(String url) {
        String host = WireUrl.host(url);
        if ((host == null) || host.isEmpty())
            return false;
        Map<String, List<String>> pins;
        try {
            pins = callSdk("getPins", sdk -> sdk.getPins("public-key-sha256"));
        } catch (ApproovException e) {
            // already logged, so no host is known to be an API domain
            return false;
        }
        if (pins == null)
            return false;
        String wanted = ApproovPinningVerifier.normalizeHost(host);
        for (String domain : pins.keySet()) {
            if ((domain != null) && !"*".equals(domain) && ApproovPinningVerifier.normalizeHost(domain).equals(wanted))
                return true;
        }
        return false;
    }

    // never the path or query
    private static String loggableHost(String url) {
        String host = WireUrl.host(url);
        return ((host == null) || host.isEmpty()) ? "a URL without a host" : host;
    }

    // ", ARC x" and ", rejection reasons y" for those present, or an empty string
    static String arcDetail(Approov.TokenFetchResult results) {
        StringBuilder details = new StringBuilder();
        String arc = results.getARC();
        if ((arc != null) && !arc.isEmpty())
            details.append(", ARC ").append(arc);
        String reasons = results.getRejectionReasons();
        if ((reasons != null) && !reasons.isEmpty())
            details.append(", rejection reasons ").append(reasons);
        return details.toString();
    }

    // records each header in the request mutations, with null for one the attempt does not carry
    private static List<String> applyLayerHeaders(ApproovRequestContext context, Approov.TokenFetchResult results,
            Channel channel, LayerHeaders names, ApproovRequestMutations changes) throws ApproovException {
        List<String> added = new ArrayList<>();
        changes.setTokenHeaderKey(null);
        changes.setTokenHeaderPrefix(null);
        changes.setStatusHeaderKey(null);
        changes.setTraceIDHeaderKey(null);
        if (channel.tokenHeaders) {
            setTokenHeader(context, names.token, names.tokenPrefix, results);
            changes.setTokenHeaderKey(names.token);
            changes.setTokenHeaderPrefix(names.tokenPrefix);
            added.add(names.token);
        }
        // the status header replaces any value the app may have set itself, so that the backend only sees
        // what this layer observed
        if (channel.statusHeader && (names.status != null)) {
            context.setHeader(names.status, buildStatusHeaderValue(results));
            changes.setStatusHeaderKey(names.status);
            added.add(names.status);
        }
        // the TraceID debug header whenever the SDK supplies a value with a token
        String traceID = getTokenFetchTraceID(results);
        if (channel.tokenHeaders && (names.traceID != null) && (traceID != null)) {
            setSdkHeader(context, names.traceID, traceID, "trace ID");
            changes.setTraceIDHeaderKey(names.traceID);
            added.add(names.traceID);
        }
        return added;
    }

    /**
     * Decides whether the secure strings of an attempt are withheld, logging each placeholder that is
     * left by its header or query parameter name and never its value. A processed request that is not
     * https, or whose token fetch returned BAD_URL, keeps its placeholders with a warning. A request
     * whose channel allows no secure string keeps them with a debug line giving the token fetch status,
     * ARC and rejection reasons.
     *
     * @param context is the request, read for the placeholders it carries
     * @param url is the request URL
     * @param results is the token fetch result for it
     * @param channel is what the attempt carries
     * @return true if no secure string may be substituted, false otherwise
     */
    private static boolean withholdSecureStrings(ApproovRequestContext context, String url,
            Approov.TokenFetchResult results, Channel channel) {
        boolean cleartext = !isHttps(url) || (results.getStatus() == Approov.TokenFetchStatus.BAD_URL);
        if (cleartext && channel.applies()) {
            String reason = !isHttps(url) ? "request is not https"
                    : "token fetch status BAD_URL" + arcDetail(results);
            for (String what : placeholdersCarried(context, url))
                warnSecureStringWithheld(what, reason);
            return true;
        }
        if (!cleartext && channel.secrets)
            return false;
        if (ApproovLog.isDebugEnabled()) {
            for (String what : placeholdersCarried(context, url))
                ApproovLog.d(TAG, "Secure string not substituted in " + what + ": token fetch status "
                        + results.getStatus() + arcDetail(results) + ", placeholder left");
        }
        return true;
    }

    // as "header X" or "query parameter x", never the value
    private static Set<String> placeholdersCarried(ApproovRequestContext context, String url) {
        Set<String> carried = new LinkedHashSet<>();
        if (context.supportsHeaderSubstitution()) {
            for (Map.Entry<String, String> entry : getSubstitutionHeaders().entrySet()) {
                String value = context.getHeader(entry.getKey());
                String prefix = entry.getValue();
                if ((value != null) && value.startsWith(prefix) && (value.length() > prefix.length()))
                    carried.add("header " + entry.getKey());
            }
        }
        if (context.supportsURLMutation()) {
            for (Map.Entry<String, Pattern> entry : getSubstitutionQueryParams().entrySet()) {
                if (findInQuery(url, entry.getValue(), -1) != null)
                    carried.add("query parameter " + entry.getKey());
            }
        }
        return carried;
    }

    // names the header or query parameter, never the value
    static void warnSecureStringWithheld(String what, String reason) {
        ApproovLog.w(TAG, "Secure string not substituted in " + what + ": " + reason + ", placeholder left");
    }

    // for the URL as the stack sends it
    private static boolean isHttps(String url) {
        return (url != null) && "https".equals(WireUrl.scheme(url));
    }

    /**
     * Indicates whether a request is cleartext to a host that is not an Approov API domain. Such a request can
     * never be protected: the SDK answers BAD_URL for any URL that is not https without a network fetch, but
     * only once the request reaches the head of its fetch queue, so asking it would hold the request behind
     * any attestation in progress, as happens to a development server request at app start. A cleartext
     * request to an Approov API domain is still fetched, so the service mutator decides it.
     *
     * @param url is the request URL
     * @return true if the request goes out untouched without a token fetch, false otherwise
     */
    private static boolean isCleartextOffApi(String url) {
        return (url != null) && !isHttps(url) && !isApiDomain(url);
    }

    // logs a cleartext request sent without a token fetch, and each placeholder it keeps, never a value
    private static void logCleartextOffApi(ApproovRequestContext context, String url) {
        if (!ApproovLog.isDebugEnabled())
            return;
        ApproovLog.d(TAG, "Request to " + loggableHost(url)
                + " is not https and not to an Approov API domain, proceeding untouched without a token fetch");
        for (String what : placeholdersCarried(context, url))
            ApproovLog.d(TAG, "Secure string not substituted in " + what + ": request is not https, placeholder left");
    }

    // HeaderSubstitution is a secure string header substitution made on a request, kept as the app's
    // fields and a digest of the installed value so the placeholder can be restored on a rebuilt request
    static final class HeaderSubstitution {
        // the name of the substituted header
        final String header;

        // every field of the header as the app sent it, in order, which is never a substituted value
        final List<String> originalFields;

        // digest of the value the layer installed
        private final byte[] installedDigest;

        // digest of the installed value trimmed, as network stacks such as OkHttp store it
        private final byte[] installedTrimmedDigest;

        // the installed value is only kept as a digest
        HeaderSubstitution(String header, List<String> originalFields, String installed) {
            this.header = header;
            this.originalFields = new ArrayList<>(originalFields);
            this.installedDigest = digest(installed);
            this.installedTrimmedDigest = digest(installed.trim());
        }

        /**
         * Restores every original field of the header, in order, if the header is still the single field
         * holding the value the layer installed. A value the app changed is left as it is. Where the stack
         * cannot add header fields, the last original field, the one the layer substituted, is restored on
         * its own.
         *
         * @param context is the request context to restore the header in
         */
        void restore(ApproovRequestContext context) {
            List<String> current = context.getHeaders(header);
            if ((current.size() != 1) || !isInstalledValue(current.get(0)) || originalFields.isEmpty())
                return;
            String substituted = originalFields.get(originalFields.size() - 1);
            if (originalFields.size() == 1) {
                context.setHeader(header, substituted);
                return;
            }
            context.setHeader(header, originalFields.get(0));
            try {
                for (int i = 1; i < originalFields.size(); i++)
                    context.addHeader(header, originalFields.get(i));
            } catch (UnsupportedOperationException e) {
                context.setHeader(header, substituted);
            }
        }

        // as given or as the network stack stores it
        boolean isInstalledValue(String value) {
            if (value == null)
                return false;
            byte[] valueDigest = digest(value);
            return MessageDigest.isEqual(valueDigest, installedDigest)
                    || MessageDigest.isEqual(valueDigest, installedTrimmedDigest);
        }

        private static byte[] digest(String value) {
            return sha256().digest(value.getBytes(StandardCharsets.UTF_8));
        }

        static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
    }

    // QuerySubstitution is a secure string query parameter substitution made on a request, kept as the
    // parameter, the app's placeholder and digests of the installed value, never the value itself
    static final class QuerySubstitution {
        // the query parameter key
        final String key;

        // the app's placeholder value
        final String placeholder;

        // digest of the value the layer installed
        private final byte[] installedDigest;

        // digest of the installed value as the network stack stores it in the URL, or null if the same
        private final byte[] storedDigest;

        QuerySubstitution(String key, String placeholder, String installed, String stored) {
            this.key = key;
            this.placeholder = placeholder;
            this.installedDigest = HeaderSubstitution.digest(installed);
            this.storedDigest = (stored == null) ? null : HeaderSubstitution.digest(stored);
        }

        // as substituted or as stored
        boolean isInstalledValue(String value) {
            byte[] valueDigest = HeaderSubstitution.digest(value);
            return MessageDigest.isEqual(valueDigest, installedDigest)
                    || ((storedDigest != null) && MessageDigest.isEqual(valueDigest, storedDigest));
        }
    }

    /**
     * Fetches the secure string keyed by a placeholder found in a request and applies the service
     * mutator's substitution decision. Every substitution the layer makes, on every networking stack, is
     * decided here and only by the mutator's two substitution hooks. A secure string is substituted only on
     * SUCCESS and only if the mutator accepts it. A failed fetch puts its status in place of the placeholder,
     * written as the status header writes it, whatever the mutator returns, so the backend sees why no secure
     * string was delivered and never a value a failed result may carry. A SUCCESS the mutator declines, or
     * one without a value, leaves the placeholder.
     *
     * @param mutator is the mutator deciding on the result
     * @param header is true for a header substitution, false for a query parameter
     * @param name is the header name or query parameter key being substituted
     * @param key is the placeholder value, used as the secure string key
     * @return the secure string or fetch status to put in place of the placeholder, or null to leave it
     * @throws IOException if the mutator decides the outcome must abort the request
     */
    private static Replacement decideSubstitution(ApproovServiceMutator mutator, boolean header, String name,
            String key) throws IOException {
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = fetchResult("fetchSecureStringAndWait", sdk -> sdk.fetchSecureStringAndWait(key, null));
        } catch (ApproovException e) {
            recordLastARC(null);
            throw e;
        }
        recordLastARC(approovResults);
        String what = (header ? "header " : "query parameter ") + name;
        boolean substitute = header
                ? callHook("handleInterceptorHeaderSubstitutionResult",
                        () -> mutator.handleInterceptorHeaderSubstitutionResult(approovResults, name))
                : callHook("handleInterceptorQueryParamSubstitutionResult",
                        () -> mutator.handleInterceptorQueryParamSubstitutionResult(approovResults, name));
        Approov.TokenFetchStatus status = approovResults.getStatus();
        boolean success = (status == Approov.TokenFetchStatus.SUCCESS);
        String secureString = (substitute && success) ? approovResults.getSecureString() : null;
        if (secureString != null)
            return new Replacement(secureString, true);

        // the value reaching the backend is the evidence of the outcome, so only the name, status, ARC and
        // rejection reasons are logged, never a value
        if (ApproovLog.isDebugEnabled()) {
            if (success && substitute)
                ApproovLog.d(TAG, "Secure string not substituted in " + what + ": no secure string for " + status
                        + ", placeholder left");
            else
                ApproovLog.d(TAG, "Secure string not substituted in " + what + ": secure string fetch status "
                        + status + arcDetail(approovResults)
                        + (success ? ", placeholder left" : ", status sent in its place"));
        }
        return success ? null : new Replacement(buildStatusHeaderValue(approovResults), false);
    }

    // Replacement is what a placeholder is replaced with: a secure string, or the status of a failed fetch
    private static final class Replacement {
        // the value put in place of the placeholder
        final String value;

        // true for a secure string, false for a fetch status
        final boolean secureString;

        Replacement(String value, boolean secureString) {
            this.value = value;
            this.secureString = secureString;
        }
    }

    // only within the query (after the first '?' and before any '#'), from the given index
    private static Matcher findInQuery(String url, Pattern pattern, int from) {
        int hash = url.indexOf('#');
        int end = (hash < 0) ? url.length() : hash;
        int question = url.indexOf('?');
        if ((question < 0) || (question >= end))
            return null;
        int start = Math.max(question, from);
        if (start >= end)
            return null;
        Matcher matcher = pattern.matcher(url);
        matcher.region(start, end);
        return matcher.find() ? matcher : null;
    }

    // only within the query (after the first '?' and before any '#'), or null if there is no such occurrence
    private static String queryOccurrenceValue(String url, Pattern pattern, int occurrence) {
        Matcher matcher = findInQuery(url, pattern, -1);
        for (int i = 0; (matcher != null) && (i < occurrence); i++) {
            if (!matcher.find())
                matcher = null;
        }
        return (matcher == null) ? null : matcher.group(1);
    }

    // names the query parameter, never the value
    private static void warnUnsafeQuerySecureString(String queryKey) {
        ApproovLog.w(TAG, "Secure string not substituted in query parameter " + queryKey
                + ": value cannot be carried in the URL, placeholder left");
    }

    private static Pattern queryParamPattern(String key) {
        return Pattern.compile("[\\?&]" + Pattern.quote(key) + "=([^&;]+)");
    }

    /**
     * Substitutes secure strings, or the status of a failed fetch, into the configured headers of the
     * request whose values carry a placeholder, recording in changes the names of the headers given a
     * secure string. A request whose headers the stack cannot change opts out.
     *
     * @param context is the request to substitute, mutated in place
     * @param mutator is the mutator deciding on each substitution result
     * @param changes are the request mutations to update
     * @return the substitutions made, so they can later be undone
     * @throws IOException if a substitution result must abort the request
     */
    private static List<HeaderSubstitution> applyHeaderSubstitutions(ApproovRequestContext context,
            ApproovServiceMutator mutator, ApproovRequestMutations changes) throws IOException {
        if (!context.supportsHeaderSubstitution())
            return Collections.emptyList();
        Map<String, String> subsHeaders = getSubstitutionHeaders();
        Map<String, String> setSubstitutionHeaders = new LinkedHashMap<>(subsHeaders.size());
        Map<String, List<String>> originalFields = new HashMap<>(subsHeaders.size());
        List<String> secureStringHeaders = new ArrayList<>(subsHeaders.size());
        for (Map.Entry<String, String> entry : subsHeaders.entrySet()) {
            String header = entry.getKey();
            String prefix = entry.getValue();
            String value = context.getHeader(header);
            if ((value != null) && value.startsWith(prefix) && (value.length() > prefix.length())) {
                Replacement replacement = decideSubstitution(mutator, true, header,
                        value.substring(prefix.length()));
                if ((replacement != null) && !isSafeHeaderValue(replacement.value)) {
                    warnUnsafeHeaderSecureString(header);
                    replacement = null;
                }
                if (replacement != null) {
                    setSubstitutionHeaders.put(header, prefix + replacement.value);
                    if (replacement.secureString)
                        secureStringHeaders.add(header);
                    // every field of the header, in order, so that all can be restored
                    originalFields.put(header, new ArrayList<>(context.getHeaders(header)));
                }
            }
        }
        if (setSubstitutionHeaders.isEmpty())
            return Collections.emptyList();
        List<HeaderSubstitution> substitutions = new ArrayList<>(setSubstitutionHeaders.size());
        for (Map.Entry<String, String> entry : setSubstitutionHeaders.entrySet()) {
            context.setHeader(entry.getKey(), entry.getValue());
            substitutions.add(new HeaderSubstitution(entry.getKey(),
                    originalFields.get(entry.getKey()), entry.getValue()));
        }
        // only the headers given a secure string are reported, while a status is still restored later
        changes.setSubstitutionHeaderKeys(secureStringHeaders);
        return substitutions;
    }

    /**
     * Substitutes secure strings, or the status of a failed fetch, into every occurrence of the configured
     * query parameters of the request URL when the stack supports changing the URL, recording in changes
     * the original URL and the keys given a secure string, if any.
     *
     * @param context is the request to substitute, mutated in place
     * @param mutator is the mutator deciding on each substitution result
     * @param changes are the request mutations to update
     * @return the substitutions made, so they can later be undone
     * @throws IOException if a substitution result must abort the request
     */
    private static List<QuerySubstitution> applyQueryParamSubstitutions(ApproovRequestContext context,
            ApproovServiceMutator mutator, ApproovRequestMutations changes) throws IOException {
        if (!context.supportsURLMutation())
            return Collections.emptyList();
        String originalURL = context.getURL();
        String acceptedURL = originalURL;
        boolean tried = false;
        List<String> queryKeys = new ArrayList<>();
        List<MadeQuerySubstitution> made = new ArrayList<>();
        for (Map.Entry<String, Pattern> entry : getSubstitutionQueryParams().entrySet()) {
            String queryKey = entry.getKey();
            Pattern pattern = entry.getValue();
            // every occurrence of the parameter in the query is substituted, each value looked up as its
            // own key and read back on its own, and the key is matched literally
            int occurrence = 0;
            Matcher matcher = findInQuery(acceptedURL, pattern, -1);
            while (matcher != null) {
                String placeholder = matcher.group(1);
                int start = matcher.start(1);
                int from = matcher.end(1);
                // look up the existing value as a key for a secure string
                Replacement replacement = decideSubstitution(mutator, false, queryKey, placeholder);
                if (replacement != null) {
                    // substitute into a candidate URL and read this occurrence back as the stack will send
                    // it, since a value the stack does not carry unchanged would reach the backend changed,
                    // so this occurrence then keeps its placeholder under every mutator
                    String installed = replacement.value;
                    String candidate = new StringBuilder(acceptedURL).replace(start, from, installed).toString();
                    tried = true;
                    String sent = urlAsStored(context, candidate);
                    if ((sent != null) && isCarriedInQuery(sent, pattern, occurrence, installed)) {
                        acceptedURL = candidate;
                        from = start + installed.length();
                        made.add(new MadeQuerySubstitution(queryKey, occurrence, placeholder, installed));
                        if (replacement.secureString && !queryKeys.contains(queryKey))
                            queryKeys.add(queryKey);
                    } else {
                        warnUnsafeQuerySecureString(queryKey);
                    }
                }
                occurrence++;
                matcher = findInQuery(acceptedURL, pattern, from);
            }
        }
        if (tried)
            context.setURL(acceptedURL);
        if (made.isEmpty())
            return Collections.emptyList();

        // only the parameters given a secure string are reported, while a status is still restored later
        if (!queryKeys.isEmpty())
            changes.setSubstitutionQueryParamResults(originalURL, queryKeys);

        // record each substitution with the value as the stack now stores it in the URL, which may differ
        // from the secure string by its encoding
        String storedURL = context.getURL();
        List<QuerySubstitution> substitutions = new ArrayList<>(made.size());
        for (MadeQuerySubstitution substitution : made) {
            substitutions.add(new QuerySubstitution(substitution.key, substitution.placeholder,
                    substitution.installed, storedQueryValue(storedURL, substitution)));
        }
        return substitutions;
    }

    /**
     * Gives a candidate URL to the request and gets it back as the stack will send it, which is the
     * context's target URI. Note that Cronet holds the URL as given and canonicalises it only when
     * sending, so its context reports the sent form as the target URI.
     *
     * @param context is the request context
     * @param candidate is the candidate URL
     * @return the URL as the stack will send it, or null if the stack refuses it
     */
    private static String urlAsStored(ApproovRequestContext context, String candidate) {
        try {
            context.setURL(candidate);
            return context.getTargetURI();
        } catch (ApproovException e) {
            return null;
        }
    }

    /**
     * Indicates whether a substituted query parameter is carried unchanged in a URL as the stack sends
     * it. The given occurrence of the parameter, read back from the encoded query, must be printable
     * ASCII and equal the secure string as it is or after percent-decoding as UTF-8. A '+' is carried
     * unchanged.
     *
     * @param url is the URL as the stack will send it
     * @param pattern is the pattern matching each value of the parameter
     * @param occurrence is the occurrence of the parameter, from 0
     * @param secureString is the value substituted
     * @return true if the backend receives the secure string unchanged, false otherwise
     */
    static boolean isCarriedInQuery(String url, Pattern pattern, int occurrence, String secureString) {
        String value = queryOccurrenceValue(url, pattern, occurrence);
        if (value == null)
            return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20) || (c > 0x7e))
                return false;
        }
        return value.equals(secureString) || secureString.equals(percentDecode(value));
    }

    private static String storedQueryValue(String storedURL, MadeQuerySubstitution substitution) {
        return queryOccurrenceValue(storedURL, queryParamPattern(substitution.key), substitution.occurrence);
    }

    // decodes %XX sequences as UTF-8, leaving '+' as it is; null if malformed
    private static String percentDecode(String value) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= value.length())
                    return null;
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if ((high < 0) || (low < 0))
                    return null;
                bytes.write((high << 4) | low);
                i += 2;
            } else {
                byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    // MadeQuerySubstitution is a query parameter substitution made while the URL is being rebuilt
    private static final class MadeQuerySubstitution {
        // the query parameter key
        final String key;

        // the occurrence of the parameter, from 0
        final int occurrence;

        // the app's placeholder value
        final String placeholder;

        // the value the layer installed
        final String installed;

        MadeQuerySubstitution(String key, int occurrence, String placeholder, String installed) {
            this.key = key;
            this.occurrence = occurrence;
            this.placeholder = placeholder;
            this.installed = installed;
        }
    }

    // the prefix alone when no token was obtained; failure information is never placed in the token header
    private static String buildTokenHeaderValue(String prefix, Approov.TokenFetchResult results) {
        String token = results.getToken();
        return prefix + ((token == null) ? "" : token);
    }

    // a prefix a header cannot carry is a configuration error, a token an SDK problem; neither is ever quoted
    private static void setTokenHeader(ApproovRequestContext context, String header, String prefix,
            Approov.TokenFetchResult results) throws ApproovException {
        if (!isSafeHeaderValue(prefix))
            throw new UnsafeHeaderValueException(header);
        String token = results.getToken();
        if (!isSafeHeaderValue(token))
            throw sdkValueFailure("token", header);
        context.setHeader(header, buildTokenHeaderValue(prefix, results));
    }

    // a value a header cannot carry is reported as an SDK problem
    private static void setSdkHeader(ApproovRequestContext context, String header, String value, String what)
            throws ApproovException {
        if (!isSafeHeaderValue(value))
            throw sdkValueFailure(what, header);
        context.setHeader(header, value);
    }

    // an SDK problem, never the app's configuration error; names the header, never the value
    private static ApproovException sdkValueFailure(String what, String header) {
        String message = "Approov header " + header + ": invalid character in SDK " + what;
        ApproovLog.e(TAG, message);
        return new ApproovException(message);
    }

    // also records the ARC
    private static Approov.TokenFetchResult fetchRequestToken(String url) throws ApproovException {
        Approov.TokenFetchResult results;
        try {
            results = fetchResult("fetchApproovTokenAndWait", sdk -> sdk.fetchApproovTokenAndWait(url));
        } catch (ApproovException e) {
            recordLastARC(null);
            throw e;
        }
        recordLastARC(results);
        return results;
    }

    // held while a request sets its token binding value and fetches its token
    private static final Object BINDING_LOCK = new Object();

    /**
     * Fetches the token for a request, bound to the value of the token binding header if it is configured
     * and the request carries it. The SDK holds one binding value for the process, so setting the value
     * and fetching are one step for bound requests, otherwise two requests binding different values could
     * each get a token bound to the other's value. Requests without a binding value fetch concurrently.
     *
     * @param context is the request
     * @param url is the URL to fetch the token for
     * @return the fetch result, never null
     * @throws ApproovException if the SDK fails
     */
    private static Approov.TokenFetchResult bindAndFetchToken(ApproovRequestContext context, String url)
            throws ApproovException {
        String currentBindingHeader = getBindingHeader();
        String bindingValue = (currentBindingHeader != null) ? context.getHeader(currentBindingHeader) : null;
        if (bindingValue == null)
            return fetchRequestToken(url);
        synchronized (BINDING_LOCK) {
            callSdk("setDataHashInToken", sdk -> {
                sdk.setDataHashInToken(bindingValue);
                return null;
            });
            return fetchRequestToken(url);
        }
    }

    // notifies the pins-change listeners on a configuration change or when the SDK asks for the pins
    private static void updatePinsIfConfigChanged(Approov.TokenFetchResult results) throws ApproovException {
        // a configuration change is acknowledged with fetchConfig, and derived pinning state is rebuilt
        // on either signal (once if both are set)
        boolean configChanged = results.isConfigChanged();
        if (configChanged) {
            callSdk("fetchConfig", ApproovSdkFacade::fetchConfig);
            ApproovLog.d(TAG, "Dynamic configuration updated");
        }
        if (configChanged || results.isForceApplyPins()) {
            notifyPinsChanged();
            ApproovLog.d(TAG, "Pins rebuild requested");
        }
    }
}
