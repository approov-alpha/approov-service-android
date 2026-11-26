# Reference

This page lists every public class and method of the Approov Package for Android (`io.approov:service.android` 3.7.0). The signatures are in Java. From Kotlin, you call the static methods the same way, for example `ApproovService.initialize(context, accountID)`. [ADVANCED.md](ADVANCED.md) explains the options that a standard integration does not need.

- [Error model](#error-model)
- [ApproovService](#approovservice): [lifecycle](#lifecycle), [headers](#headers), [request configuration](#request-configuration), [logging](#setlogginglevel), [service mutator](#service-mutator), [message signing](#message-signing), [direct methods](#direct-methods), [request pipeline for adapters](#request-pipeline-for-adapters)
- [ApproovServiceMutator](#approovservicemutator)
- [ApproovRequestContext](#approovrequestcontext), [ApproovRequestMutations](#approovrequestmutations)
- [ApproovDefaultMessageSigning](#approovdefaultmessagesigning)
- [Exceptions](#exceptions)
- [ApproovPinningVerifier](#approovpinningverifier)
- Adapters: [OkHttp](#ioapproovserviceandroidokhttp), [HttpsURLConnection](#ioapproovserviceandroidhttpsurlconn), [Volley](#ioapproovserviceandroidvolley), [gRPC](#ioapproovserviceandroidgrpc), [Cronet](#ioapproovserviceandroidcronet)
- [Message signing helpers](#ioapproovutiluniversalsig)
- [Removed in 3.7.0](#removed-in-370)

## Error model

This section tells what happens to a request that the package cannot fully protect, and which exceptions your app can get.

The installed [`ApproovServiceMutator`](#approovservicemutator) decides whether such a request still goes ahead. See also [service mutators](ADVANCED.md#service-mutators) and [what happens by default](ADVANCED.md#what-happens-by-default).

The default, `ApproovServiceMutator.DEFAULT`, is `ALWAYS_PROCEED`. With it, the request always goes ahead. The status is in the `Approov-Status` header, and the request has no token header if the token fetch gave no token.

`ApproovServiceMutator.CLOSE_FAILURE` handles each token fetch status like this:

* `SUCCESS`: the request goes ahead with the token, status and trace headers, its secure strings, and its signatures when signing is enabled.
* `NO_APPROOV_SERVICE`: the request goes ahead with the status header only. It has no token header.
* `UNPROTECTED_URL`: the request is sent with its secure strings and no Approov header. This is a [secrets-only API](ADVANCED.md#secrets-only-apis), added with `-noApproovToken`.
* `UNKNOWN_URL`: the request is sent unchanged.
* `NO_NETWORK`, `POOR_NETWORK` and `MITM_DETECTED`: the request fails with `ApproovNetworkException`.
* Any other status: the request fails with `ApproovFetchStatusException`.

The message of these two exceptions is `Approov token fetch for <host>: <status>`. It names the host only, never the URL.

On OkHttp, the package makes these decisions after the connection is up. OkHttp cannot connect when there is no network or when the TLS handshake fails. An example is a proxy whose CA the device does not trust. Then OkHttp fails the call itself, with `UnknownHostException` or `SSLHandshakeException`, before the package runs. So the app gets that `IOException`, not `ApproovNetworkException`, under either mutator.

A request that goes ahead after a failed token fetch never carries a token header, a trace ID, a secure string or a signature.

The mutator decides what happens after a failed token fetch only for a request to one of your API domains. Your API domains are the protected domains that the SDK configuration lists, other than the `*` entry. The package compares hosts without regard to case and without a trailing dot. For any other host, or when the SDK lists no protected domains, the package sends the request unchanged:

* A standard mutator never aborts it, and the answer of the hook has no effect.
* A custom mutator may choose to abort it. To do this, it throws its own exception from `handleInterceptorFetchTokenResult`.

Under both standard mutators, a secure string fetch with a status other than `SUCCESS` never fails the request. The status replaces the placeholder, in the same form as in the `Approov-Status` header: `REJECTED`, `UNKNOWN_KEY`, `NO_NETWORK` and so on.

With any mutator, these other exceptions can reach your app from a request:

* Configuration errors in your app. If the package cannot generate a required body digest, it throws `ApproovDefaultMessageSigning.RequiredBodyDigestException`. An unsupported signing algorithm throws `ApproovException`. A header value from your app that a header cannot carry also throws `ApproovException`.
* A connection validation failure on one of your API domains throws `javax.net.ssl.SSLPeerUnverifiedException`.
* An abort that your app chose through a mutator throws a standard network stack exception (`IOException` or a subclass).

Message signing that is on with no usable algorithm is not one of these errors. The package sends the request unsigned and logs an error.

These problems fail the request with an `ApproovException` whose cause is the original problem:

* An exception that the Approov SDK throws while the package processes the request.
* A token or trace ID from the SDK that a header cannot carry. The message is `Approov header <name>: invalid character in SDK token`.
* A `RuntimeException` or Approov exception from a mutator hook. This does not apply to an abort from the `CLOSE_FAILURE` decisions that the hook inherits or calls.

Only an `IOException` leaves the request path. On gRPC, a failed call closes with `Status.UNAVAILABLE`, with the exception as its cause. On Cronet, `onFailed` receives a `NetworkException` that carries the exception. See the adapters below.

`ApproovException` extends `java.io.IOException`, so it is a checked exception.

Every method that calls the Approov SDK first checks [isApproovProtectionEnabled](#isapproovprotectionenabled). While it is `false`, the method throws `ApproovException` with the message `<method>: Approov protection not enabled`, without calling the SDK. These methods are `setDevKey`, `getDeviceID`, `setDataHashInToken`, `fetchToken`, `getAccountMessageSignature`, `getMessageSignature`, `getInstallMessageSignature`, `fetchSecureString`, `fetchCustomJWT`, `setInstallAttributes` and `getPins`.

The request path never throws for this reason. Before initialization and in bypass mode, requests go out without Approov processing.

## ApproovService

`io.approov.service.android.ApproovService` has static methods only. Its constructor is `protected`.

### Lifecycle

#### initialize

```java
static void initialize(Context context, String config)
static void initialize(Context context, String config, String comment)
```

Initializes the package with your Approov account ID.

The `config` parameter is your Approov account ID. It is the string in your onboarding email, and `approov sdk -getConfigString` prints it (the CLI calls it the SDK config string). It identifies your account to the SDK. It is not a secret. It is the same for every app in your account.

The package passes every non-empty account ID to the Approov SDK, with the comment exactly as you passed it. The SDK then decides. The package does not store or compare the account ID.

* The first initialization succeeds.
* For a later call with the same account ID and comment, the SDK reports that it is already initialized, and `initialize` returns immediately. So every part of your app that uses Approov may call `initialize`.
* For a later call with a different account ID or comment, the SDK rejects the call, and `initialize` throws `IllegalStateException`. Protection stays on with the first account ID and comment.

`initialize` never resets your configuration. It changes only what `isApproovServiceEnabled` and `isApproovProtectionEnabled` report. Listeners from [`registerPinsChangeListener`](#registerpinschangelistener) run when protection is first enabled, or when the SDK reports a new initialization.

Once per process, `initialize` logs an error if react-native-nitro-fetch is in your app without the weave of the [Gradle plugin](ADVANCED.md#the-gradle-plugin).

An empty `config` turns on bypass mode:

* The package does not call the SDK.
* Requests go out with no Approov processing. Connections are validated with OS trust only.
* A later non-empty account ID turns on protection, if the SDK accepts it.
* The package ignores an empty account ID after protection is on.

The package passes the `comment` parameter to the SDK unchanged, for example `options:...` or `reinit...`. See [SDK initialization options](https://docs.approov.io/direct-sdk-integration/initialization-options/#sdk-initialization-options). The two-argument form passes `null`. The SDK treats `null` and `""` as different comments.

Throws:

* `IllegalArgumentException` if `context` or `config` is `null`, or if the SDK rejects the account ID.
* `IllegalStateException` if the SDK is already initialized with a different account ID or comment.

The SDK's exception reaches you unchanged, and the package state stays as it was. Guard this call, as shown in the [README](README.md#initializing).

These two forms never read a bundled `approov.config`.

#### initialize from approov.config

```java
static void initialize(Context context) throws ApproovException
```

Reads `approov.config` from the app assets. Then it initializes the package with the `config` and `comment` of the file, exactly as `initialize(context, config, comment)` does. An empty `config` turns on bypass mode, and the call logs a `WARNING` line when it enters bypass mode. Each build variant can bundle its own file through its Gradle source set, such as `src/debug/assets/approov.config`.

The file is a JSON object in UTF-8.

* The package ignores a leading byte order mark.
* If the first character that is not whitespace is not `{`, the content is a 3.5.x file. Its first non-blank line is the `config`, and it has no other settings. Here, whitespace is Unicode whitespace. If such a line holds `{` or `"`, the package rejects it as not valid JSON.
* A number that is not finite as a double (`1e400`) makes the file not valid JSON.
* The package rejects a file larger than 1 MiB.
* The package rejects all of the content if it is not one valid JSON object. Examples are a syntax error, a truncated file, a top-level array, string, number, `true`, `false` or `null`, or a duplicate key at any depth.

| Key | Value | Applied with |
| --- | --- | --- |
| `config` | string, required | `initialize` |
| `comment` | string, or `null` for no comment | `initialize` |
| `devKey` | string, for debug variants only | `setDevKey` |
| `tokenHeader` | `{"name": string, "prefix": string}`, prefix `""` if absent | `setTokenHeader` |
| `traceIDHeader` | string, or `null` to disable | `setTraceIDHeader` |
| `statusHeader` | string, or `null` to disable | `setStatusHeader` |
| `bindingHeader` | string, or `null` for none | `setBindingHeader` |
| `mutator` | `"CLOSE_FAILURE"` or `"ALWAYS_PROCEED"`. `ALWAYS_PROCEED` is the default. | `setServiceMutator` |
| `messageSigning` | `true` or `false`. Signing is on by default. `false` switches it off. `true` switches it on with the default factory, which replaces a default factory that your app set before. | `enableMessageSigning()` or `disableMessageSigning()` |
| `logLevel` | `"OFF"`, `"ERROR"`, `"WARNING"`, `"INFO"` or `"DEBUG"` | `setLoggingLevel` |
| `substitutionHeaders` | object of header name to required prefix, `""` for none | `addSubstitutionHeader` |
| `substitutionQueryParams` | array of query parameter names | `addSubstitutionQueryParam` |
| `exclusionURLRegexes` | array of regular expressions, JSON escaped, so `"\\."` is `\.` | `addExclusionURLRegex` |

The package applies the settings once per process. The file-form call that applies them is one of these:

* the call that first enables the package (bypass mode included)
* the call that turns on protection after bypass mode

This is true even when another Approov package already initialized the SDK.

* The package applies the settings after the SDK accepts the account ID. It applies them before the package reports itself enabled, and before any other `initialize` returns.
* `logLevel` is applied first and `bindingHeader` last.
* A call that finds the package already enabled applies nothing. The explicit forms also apply nothing.
* Nothing is reset. A key that the file does not have keeps its default, or the value your app set.
* In bypass mode, the package does not apply `devKey`, and it logs this.

Value rules:

* The package ignores an unknown key and logs one `INFO` line that names it.
* The package skips a key with a wrong type or value, and logs an `ERROR` line that names it. The other keys still apply. Examples:
  * an empty header name
  * an unknown mutator or level
  * a regular expression that does not compile
  * a binding header that is also a substitution header
  * `null` for any key other than the three headers and `comment`
* In an array or in the `substitutionHeaders` object, the package skips a bad element and applies the others. An empty array or object adds nothing.
* An empty `prefix` is no prefix. The package passes an empty `comment` to the SDK as an empty comment.

When the package reads and applies the file, it logs key names only, never values. The configuration calls that it makes log as they do for any caller, at `DEBUG`. No line at any level contains the account ID, the comment or the development key.

Throws:

* `ApproovException` if the file is missing, cannot be read, is not valid or has no `config`. The message names the cause, never the content. The package initializes and applies nothing.
* `IllegalArgumentException` if `context` is `null` or the SDK rejects the account ID, as for `initialize(context, config, comment)`. The package applies nothing.
* `IllegalStateException` if the SDK is already initialized with a different account ID or comment, as for `initialize(context, config, comment)`. The package applies nothing.

#### setPersistentConfiguration

```java
static void setPersistentConfiguration(Context context, String json, String source) throws ApproovException
```

Stores settings for the next launch.

* `json` is a JSON object with the keys of [`approov.config`](#initialize-from-approovconfig) other than `config`, `comment` and `devKey`.
* `source` is a tag that tells where the settings come from, such as an update ID, or `null`. The package logs it when it applies the settings, so it must not be a secret.

The package applies nothing in the running app. To use the settings, your app must restart.

At the next launch, the file-form `initialize(context)` applies your bundled file with these settings in place of the values of the file. It does this one time, in its single pass, before the package reports itself enabled.

* Each key replaces the same key of the file completely. `tokenHeader`, `substitutionHeaders`, `substitutionQueryParams` and `exclusionURLRegexes` replace the bundled value. They do not add to it.
* A key that the stored settings do not have keeps the value of the file.
* One `INFO` line, `persistent configuration applied (source <tag>)`, reports this.
* The explicit forms of `initialize` do not apply or delete the stored settings.

In these cases, the file-form `initialize` deletes the stored settings and does not apply them:

* The content of your bundled `approov.config` is not the same as when the settings were stored. The `INFO` line is `persistent configuration deleted: store release changed the bundled approov.config`.
* The version code or version name of the app is not the same as when the settings were stored. This applies even when the bundled file is the same. The `INFO` line is `persistent configuration deleted: app version changed`. So every update of the app starts from its own bundled file.
* The package cannot read the settings, or they are in a format that this version does not know. The package logs a `WARNING` line.

The package writes both lines in the apply pass, after it applies the `logLevel` for that pass. So `"OFF"` silences them. The file-form `initialize` reads, and can delete, the stored settings with `StrictMode` disk reads and writes allowed for that step only. So a debug `StrictMode` policy does not stop an `initialize` call on the main thread.

The package checks the settings as it checks the bundled file, but it skips nothing. It rejects these settings and stores nothing:

* content that is not one valid JSON object, by the rules of the bundled file
* content larger than 64 KiB
* objects and arrays nested 127 or more levels deep, with the settings object counted as one level. The package rejects them as not valid JSON. The stored record wraps the settings in one more object, and this limit makes sure that the package can always read the record back.
* `config`, `comment` or `devKey`
* a known key with a wrong type or value. Examples are an unknown mutator, a regular expression that does not compile, or a binding header that is also a substitution header.
* a `source` longer than 1024 bytes, or a `source` that holds an unpaired surrogate

The package also checks the binding header and the substitution headers against the keys of the bundled file that the settings leave in place.

* The package stores an unknown key. When it applies the settings, it logs the key and ignores it.
* A line about a key from the stored settings names `approov.config (persistent)` instead of `approov.config`. Examples are an unknown key, or a value that a setter rejects at the next launch.
* If the settings and source are the same as the stored ones, the package does not write them again.
* The package keeps the settings in the no-backup files of the app, so they never go into a backup or to another device. Each write replaces the previous one atomically.
* One `INFO` line, `persistent configuration stored, applies at the next launch`, reports a write. The package logs the key names at `DEBUG`, never the values.

You may call it from any thread. It reads and writes a small file, so avoid calls on the main thread.

Throws:

* `ApproovException` if the package rejects the settings or cannot store them. The message names the cause, never a value. The package stores nothing and keeps the previous settings.
* `IllegalArgumentException` if `context` is `null`.

For both exceptions, one `ERROR` line, `setPersistentConfiguration: <cause>`, logs the cause as thrown.

#### clearPersistentConfiguration

```java
static void clearPersistentConfiguration(Context context) throws ApproovException
```

Deletes the settings stored with `setPersistentConfiguration`, so the next launch uses the bundled `approov.config` alone. Nothing changes in the running app. It is not an error if no settings are stored. You may call it from any thread.

Throws:

* `ApproovException` if the settings exist and the package cannot delete them.
* `IllegalArgumentException` if `context` is `null`.

#### getPersistentConfigurationSource

```java
static String getPersistentConfigurationSource(Context context)
```

Returns the `source` of the settings that are stored now with `setPersistentConfiguration`. Returns `null` if no settings are stored, or if they were stored without a source.

The value tells what is stored, not what is in force. Settings stored in this launch apply from the next launch. The next launch may still discard them if the bundled `approov.config` or the app version changed, or if the package cannot read them.

You may call it from any thread. Throws `IllegalArgumentException` if `context` is `null`.

#### isApproovServiceEnabled

```java
static boolean isApproovServiceEnabled()
```

Returns whether the package is enabled. It returns `true` after a call to `initialize` succeeds, bypass mode included. A later rejected call does not change it.

#### isApproovProtectionEnabled

```java
static boolean isApproovProtectionEnabled()
```

Returns whether requests are protected. It returns `true` after the Approov SDK is initialized with a non-empty account ID. It returns `false` before initialization and in bypass mode.

After a successful `initialize`, check `isApproovServiceEnabled()` and then this method. At that point, this method is `false` only in deliberate bypass mode.

#### setLoggingLevel

```java
static void setLoggingLevel(ApproovLogLevel level)
```

Sets how much the package logs. It applies on every stack, the Cronet adapter included.

The levels are `ApproovLogLevel.OFF` (nothing, not even errors), `ERROR`, `WARNING`, `INFO` and `DEBUG`. Each level includes the ones before it. The default is `INFO`.

* It is thread-safe. You can call it at any time, including before `initialize`, and `initialize` keeps it.
* `null` throws `IllegalArgumentException` and leaves the level unchanged.
* There is no getter.

Debug lines include the loggable token, with its request URL. The claims of the token contain the device ID. The package logs the URL without user information, query or fragment. Debug lines also include the device ID.

Support can read the debug lines of a release build without a new build. To turn on debug logging for the `ApproovService` tag, use `adb shell setprop log.tag.ApproovService DEBUG`. This raises any level except `OFF` to `DEBUG`, for every tag of the package. The property never overrides `OFF`.

No line at any level contains a secure string value, a substituted query, a message signature or the account ID.

These events are logged at `DEBUG` only:

* A secure string not delivered at runtime. That is a secure string status other than `SUCCESS`, or a request whose token fetch status allows none.
* A request that goes ahead after a failed token fetch.
* A failure on a host that is not one of your API domains.

Each of these lines gives the header or query parameter name or the host, the status, and the ARC and rejection reasons when present. The warnings for a placeholder left on a request that is not `https`, or whose URL is `BAD_URL`, stay at `WARNING`. See [Diagnostics](ADVANCED.md#diagnostics).

`ApproovLogLevel` is declared as `public enum ApproovLogLevel { OFF, ERROR, WARNING, INFO, DEBUG }`.

`ApproovLog` is a `public final class` with no public constructor. Its methods are `e`, `w`, `i`, `d`, `isDebugEnabled()` and `loggableURL(url)`. Every log line of the package uses it. It is for adapters, not for app code. It is public only so the adapters in the sub-packages can use it. Your app sets the level with `setLoggingLevel`.

### Headers

#### setTokenHeader, getTokenHeader, getTokenPrefix

```java
static void setTokenHeader(String header, String prefix)
static String getTokenHeader()
static String getTokenPrefix()
```

Sets and reads the header that carries the Approov token, and an optional prefix for it.

* The default header is `Approov-Token`.
* The prefix is for values such as `"Bearer "`. A `null` prefix means no prefix, never the literal text `null`.
* `getTokenPrefix` returns `""` when there is no prefix.

When a request goes ahead without a token, the package does not send the header, not even the prefix. The package never puts failure information in the header. Each request reads the name and prefix together, as one snapshot.

#### setTraceIDHeader, getTraceIDHeader

```java
static void setTraceIDHeader(String header)
static String getTraceIDHeader()
```

Sets and reads the header for the optional Approov TraceID debug value. The default is `Approov-TraceID`. `null` turns it off.

#### setStatusHeader, getStatusHeader

```java
static void setStatusHeader(String header)
static String getStatusHeader()
```

Sets and reads the header that reports the token fetch status, with the status name exactly as the SDK gives it: `SUCCESS`, `NO_NETWORK`, `MITM_DETECTED`, `NO_APPROOV_SERVICE`, `REJECTED` and so on. The default is `Approov-Status`. `null` turns it off. See [The Approov-Status header](ADVANCED.md#the-approov-status-header).

* The package sends it on every request that the token decision lets go ahead with Approov headers, with any mutator. The package sends a failure status only to one of your API domains.
* It replaces any value your app set.
* The package never sends it to a domain that is not in Approov (`UNKNOWN_URL`), to a secrets-only API (`UNPROTECTED_URL`), or on excluded requests.
* It reports the token fetch only.

#### Deprecated header aliases

```java
@Deprecated static void setApproovHeader(String header, String prefix)   // setTokenHeader
@Deprecated static void setApproovTraceIDHeader(String header)           // setTraceIDHeader
@Deprecated static String getApproovTokenHeader()                        // getTokenHeader
@Deprecated static String getApproovTokenPrefix()                        // getTokenPrefix
@Deprecated static String getApproovTraceIDHeader()                      // getTraceIDHeader
```

These are the 3.5.x names. They are deprecated and kept for this release. Each one calls the new name:

* `setApproovHeader` calls `setTokenHeader`.
* `setApproovTraceIDHeader` calls `setTraceIDHeader`.
* `getApproovTokenHeader` calls `getTokenHeader`.
* `getApproovTokenPrefix` calls `getTokenPrefix`.
* `getApproovTraceIDHeader` calls `getTraceIDHeader`.

### Request configuration

#### setBindingHeader, getBindingHeader

```java
static void setBindingHeader(String header)
static String getBindingHeader()
```

Sets and reads the header used for [token binding](https://docs.approov.io/backend-integration/token-binding/). On every request that has this header, a hash of the header value goes in the `pay` claim of the token. On those requests, the binding header overrides `setDataHashInToken`. See also [Token binding](ADVANCED.md#token-binding).

`getBindingHeader` returns `null` if no binding header is set.

`setBindingHeader` throws `IllegalArgumentException` if the header is already a substitution header.

#### setStaleProtectionRefreshPeriod, getStaleProtectionRefreshPeriod

```java
static void setStaleProtectionRefreshPeriod(long periodMS)
static long getStaleProtectionRefreshPeriod()
```

Sets and reads the stale protection refresh period. If a request waits between processing and transmission for longer than this period, the package refreshes its protection, on stacks that can do this. The default is 3000 ms. A value of `0` or less turns it off. It does not apply to OkHttp. On OkHttp, the package protects every network attempt when OkHttp writes it. See [stale protection refresh](ADVANCED.md#stale-protection-refresh).

#### addSubstitutionHeader, removeSubstitutionHeader, getSubstitutionHeaders

```java
static void addSubstitutionHeader(String header, String requiredPrefix)
static void removeSubstitutionHeader(String header)
static Map<String, String> getSubstitutionHeaders()
```

Adds, removes and lists the headers whose value is a [secure string](ADVANCED.md#secure-strings) key to substitute.

* `requiredPrefix` is optional. The key is the part of the value after it.
* The package matches header names without regard to case.
* Put the placeholder in the header, on every stack. On Volley, put it in the map your `Request` returns from `getHeaders()`.
* The pipeline substitutes the placeholder only for an `https` URL that Approov protects.
* If the secure string fetch status is not `SUCCESS`, the package puts the status in place of the placeholder, after the prefix. An example is `Bearer REJECTED`.
* `getSubstitutionHeaders` returns a copy, mapping each header to its prefix.
* Changes take effect whether you make them before or after `initialize`.

`addSubstitutionHeader` throws `IllegalArgumentException` if the header is the binding header.

#### addSubstitutionQueryParam, removeSubstitutionQueryParam, getSubstitutionQueryParams

```java
static void addSubstitutionQueryParam(String key)
static void removeSubstitutionQueryParam(String key)
static Map<String, Pattern> getSubstitutionQueryParams()
```

Adds, removes and lists the query parameters whose value is a secure string key to substitute.

* The package matches the key as a literal string.
* The package substitutes each occurrence separately.
* If the secure string fetch status is not `SUCCESS`, the package puts the status in place of the value, for example `?api_key=REJECTED`.
* `getSubstitutionQueryParams` returns a copy, mapping each key to the pattern the package uses.

#### addExclusionURLRegex, removeExclusionURLRegex, getExclusionURLRegexs

```java
static void addExclusionURLRegex(String urlRegex)
static void removeExclusionURLRegex(String urlRegex)
static Map<String, Pattern> getExclusionURLRegexs()
```

Adds, removes and lists regular expressions for URLs that Approov does not process. See [Excluding URLs](ADVANCED.md#excluding-urls).

A request whose URL matches one of them (`Matcher.find`) has no Approov processing and no token fetch. Connection validation still applies.

An invalid expression throws `IllegalArgumentException`, with the `PatternSyntaxException` as its cause. The package adds nothing and keeps the exclusions that are already added. Every Approov package does the same.

Use this with care. Only token fetches update the public key hashes for a domain. On OkHttp, Volley and gRPC, the package still validates an excluded request. It uses the hashes from the last request that fetched a token. So your app must also call URLs on that domain that are not excluded.

#### registerPinsChangeListener

```java
static void registerPinsChangeListener(Runnable listener)
```

Registers a listener that runs whenever the public key hashes that [`getPins`](#getpins) returns may have changed. That happens when:

* protection is turned on
* the SDK reports a new initialization
* a request through one of the networking stacks of the package finds that the configuration changed
* the fetch of such a request asks for the new hashes to be applied

`fetchToken` does not run the listener. The SDK keeps a configuration change that `fetchToken` receives, and applies it immediately. So `getPins` already returns the new hashes. The listener runs on the next request through one of the networking stacks of the package, before the package validates the connection of that request.

* The listener runs outside the lock of the package.
* The package logs and ignores an exception from a listener.
* You cannot remove a listener.

Adapters that keep their own copy of the public key hashes register here. Your app's own adapter for a third-party SDK may register here too. See [Request pipeline for adapters](#request-pipeline-for-adapters).

### Service mutator

#### setServiceMutator, getServiceMutator

```java
static void setServiceMutator(ApproovServiceMutator mutator)
static ApproovServiceMutator getServiceMutator()
```

Installs and reads the mutator that makes the request decisions on every stack. See [service mutators](ADVANCED.md#service-mutators).

* `null` reinstates `ApproovServiceMutator.DEFAULT`, which is `ALWAYS_PROCEED`.
* `getServiceMutator` never returns `null`.
* `initialize` keeps whichever mutator is installed.
* Installing a mutator never turns message signing on or off.

The mutator that was installed when the package first processed a request makes all the decisions for that request.

### Message signing

#### enableMessageSigning

```java
static void enableMessageSigning()
static void enableMessageSigning(ApproovDefaultMessageSigning.SignatureParametersFactory defaultFactory)
```

Turns message signing on. See [Message signing](ADVANCED.md#message-signing).

Signing is on by default, with the default factory. Call this method to switch signing on again after `disableMessageSigning`, or to set another default factory.

When signing is on, the package signs (RFC 9421) every request that has the token header, on every stack. Signing occurs after the mutator decisions, the substitutions and the processed request callback. The package signs the request again each time it applies the protection again.

* With no argument, or with `null`, it uses `ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()`. That produces both the `install` signature (`ecdsa-p256-sha256`) and the `account` signature (`hmac-sha256`), over the same components.
* `defaultFactory` applies to every host that has no host factory.

If the package cannot make a signature, it leaves that signature out. The request goes ahead with the other signature, or unsigned. The package logs a warning for each kind of missing signature, and an error for a request that goes unsigned, one time for each process. Later occurrences log at `DEBUG`. `initialize` does not change the switch or the factories.

#### disableMessageSigning, isMessageSigningEnabled

```java
static void disableMessageSigning()
static boolean isMessageSigningEnabled()
```

`disableMessageSigning` turns signing off. `isMessageSigningEnabled` reports whether signing is on. While signing is off, the package adds no `Signature` or `Signature-Input` header. The package keeps the factories for a later `enableMessageSigning`.

#### putMessageSigningHostFactory

```java
static void putMessageSigningHostFactory(String hostName, ApproovDefaultMessageSigning.SignatureParametersFactory factory)
```

Sets the factory used to sign requests to `hostName`, in place of the default factory, while signing is on.

* The package matches `hostName` without regard to case, and without the port.
* A `null` factory removes the host's factory.

Throws `IllegalArgumentException` if `hostName` is `null`.

### Direct methods

These methods call the Approov SDK for your code. Each one returns a value or does an action.

* While protection is not enabled, each throws `ApproovException` (`<method>: Approov protection not enabled`) without calling the SDK.
* If the SDK throws a `RuntimeException`, the method wraps it in an `ApproovException` as the cause.

The fetch methods pass the SDK status to the mutator's `handleFetchTokenResult`, `handleFetchSecureStringResult` and `handleFetchCustomJWTResult`. By default:

* `NO_NETWORK`, `POOR_NETWORK` and `MITM_DETECTED` throw `ApproovNetworkException`.
* `REJECTED` throws `ApproovRejectionException` from `fetchSecureString` and `fetchCustomJWT`.
* Any other unsuccessful status throws `ApproovFetchStatusException`.

A fetch may use the network, so do not call these methods on the main thread.

#### setDevKey

```java
static void setDevKey(String devKey) throws ApproovException
```

Sets a [development key](https://docs.approov.io/operations/app-signing-certificates/development-and-testing/#using-a-development-key). With it, a development build passes attestation even if it is not registered or runs on an emulator.

#### getDeviceID

```java
static String getDeviceID() throws ApproovException
```

Returns the device ID Approov uses for this installation. Different Approov apps on one device have different IDs. A reinstall may change the ID. Log it next to your own session or user ID, so you can match the two later.

#### getServerAdjustedCurrentTimeMS

```java
static long getServerAdjustedCurrentTimeMS() throws ApproovException
```

Returns the current time in milliseconds since the epoch. The value is corrected by the offset between the device clock and the clock of the Approov servers. The SDK learns this offset at each attestation.

* Before the first attestation of the installation, the value is the device clock.
* Message signatures take their `created` and `expires` times from it. So a device with a wrong clock still signs within the time window of your backend.
* Use it for your own timestamps that your backend compares with its clock.

#### setDataHashInToken

```java
static void setDataHashInToken(String data) throws ApproovException
```

Sets data for the `pay` claim. The base64-encoded SHA-256 hash of the data goes in the `pay` claim of tokens fetched after this call. The hash is made on the device, and the data is never sent. A different value makes the next fetch return a new token. On requests that have the binding header, the binding header overrides this data.

#### fetchToken

```java
static String fetchToken(String url) throws ApproovException
```

Fetches an Approov token for `url`. Use it for cases that the networking objects cannot cover. Never cache the token.

#### getAccountMessageSignature, getInstallMessageSignature

```java
static String getAccountMessageSignature(String message) throws ApproovException
static String getInstallMessageSignature(String message) throws ApproovException
@Deprecated static String getMessageSignature(String message) throws ApproovException   // getAccountMessageSignature
```

Signs `message` with one of two keys:

* `getAccountMessageSignature` uses the account message signing key. It returns an HMAC-SHA256 signature, base64 encoded.
* `getInstallMessageSignature` uses the installation key. It returns an ECDSA P-256 signature as base64 encoded ASN.1 DER.

After a failed attestation, the account key is random, so the signature fails verification. To prevent replay, include an Approov token in the message that you sign. Request signing does not need these methods. See [message signing](#message-signing).

`getMessageSignature` is deprecated. It is the name that the per-stack packages used, and it stays for this release. It calls `getAccountMessageSignature` and returns the same value. The first call to it in the process logs a warning that names `getAccountMessageSignature`.

#### fetchSecureString

```java
static String fetchSecureString(String key, String newDef) throws ApproovException
```

Fetches the [secure string](https://docs.approov.io/backend-integration/secure-strings/) for `key`. With a non-null `newDef`, it defines an app instance secure string and returns it. A `newDef` of `""` removes the app instance secure string.

* Returns `null` if the key is not defined (`UNKNOWN_KEY`).
* Returns any UTF-8 value, including values the automatic substitution does not use.

Never cache the result.

#### fetchCustomJWT

```java
static String fetchCustomJWT(String payload) throws ApproovException
```

Fetches a [custom JWT](https://docs.approov.io/backend-integration/custom-jwts/) carrying the claims of the JSON object `payload`.

#### getLastARC

```java
static String getLastARC()
```

Returns the Attestation Response Code of the most recent fetch the package made. That fetch may be in a direct method, or on the request path of any stack. On the request path it may be a token fetch, a secure string substitution, a stale refresh or a redirect. This method makes no fetch and no SDK call, and it never throws.

It returns `""` when:

* the package made no fetch after initialization
* the last fetch had no ARC
* ARC is not enabled
* the last fetch failed without an ARC

#### setInstallAttributes

```java
static void setInstallAttributes(String attrs) throws ApproovException
```

Passes a signed install attributes JWT to the SDK's `Approov.setInstallAttrsInToken`. The SDK associates it with this installation in future tokens, and the next token fetch does not use a cached token. See [application installation attributes](https://docs.approov.io/app-integration/application-installation-attributes/).

This method replaces `setInstallAttrsInToken`, renamed in 3.7.0.

#### getPins

```java
static Map<String, List<String>> getPins(String pinType) throws ApproovException
```

Returns the certificate public key hashes of each protected domain that the SDK holds, keyed by domain, for the `pinType` `"public-key-sha256"`. Returns `null` if the SDK holds none.

* The `*` entry holds the Managed Trust Roots. They apply to a domain with no public key hashes of its own.
* `*` is never a pattern. Approov API domains never contain wildcards.
* On every stack, the package looks up the hashes for the exact host, without regard to case and ignoring one trailing dot.

The package's own connection validation reads the hashes through this method.

### Request pipeline for adapters

These methods let a networking adapter, or app code with its own stack, run the same pipeline as the built-in adapters. Until protection is enabled, each leaves the request or URL unchanged and calls no SDK method.

They declare `IOException`, and throw:

* the `CLOSE_FAILURE` aborts, as `ApproovException` subclasses
* the own exception of a custom mutator, unchanged
* any other failure as an `ApproovException`, with the failure as its cause

#### processRequest

```java
static ApproovRequestMutations processRequest(ApproovRequestContext context) throws IOException
```

Runs the full pipeline on `context` and changes `context` in place. Returns the changes that it made. It never returns `null`. The steps, in order:

1. the processing decision of the mutator (exclusions, by default)
2. token binding
3. the token fetch, with the token, status and trace headers
4. header substitution
5. query parameter substitution, when the context supports URL mutation
6. the processed request callback
7. message signing, when it is enabled

#### prepareRequest

```java
static ApproovService.ApproovPreparedRequest prepareRequest(ApproovRequestContext context) throws IOException
```

Runs the pipeline up to, but not including, the processed request callback and message signing. It is for adapters that must wait for the request body, such as the buffered `HttpsURLConnection` wrapper, or for the moment of transmission.

#### ApproovService.ApproovPreparedRequest

A `public static final class` that `prepareRequest` returns. It has no public constructor. The members marked *for adapters, not for app code* are public only because the adapters are in sub-packages.

| Method | Behaviour |
| :--- | :--- |
| `ApproovRequestMutations getChanges()` | The changes made so far. Never `null`. |
| `boolean isProcessed()` | Whether the package processed the request. If it did, the processed request callback applies, and the package evaluates later attempts again. `true` for a token-protected (`SUCCESS`) or secrets-only (`UNPROTECTED_URL`) request, and for a failure that goes ahead on one of your API domains. `false` before initialization, in bypass mode, for an excluded request, for `UNKNOWN_URL`, and for a failure on any other host. |
| `boolean handlePinningShouldProcessRequest(String url) throws IOException` | *For adapters, not for app code.* Asks whether connection validation applies to an attempt to `url`. It asks the mutator that processed this request, not the one installed now. The OkHttp network interceptor calls it for every network attempt after protection is enabled, including excluded and unprocessed attempts. gRPC and Cronet never call it. |
| `boolean supportsProtectionRefresh()` | Whether the package may refresh the protection. Always `true` before the callback runs for the first time. After that, `true` only if the mutator supports it. If the mutator hook fails, the package logs the failure and returns `false`. |
| `void completeProcessing(ApproovRequestContext context) throws IOException` | Runs the processed request callback, then message signing. For a body digest, the body should be available through the context. |
| `boolean refreshProtectionIfStale(ApproovRequestContext context) throws IOException` | Refreshes protection that is older than the refresh period, when the mutator supports refresh. It removes the protection and applies it again for the refreshed status. A secrets-only API keeps only its secure strings. If the refreshed fetch reports a host that is not in Approov, the package removes the protection. Returns `true` if the request changed. |
| `boolean refreshProtectionForAttempt(ApproovRequestContext context) throws IOException` | Call it before each network attempt. It refreshes stale protection. If the URL, method or headers of the attempt changed, it removes what the package added and restores the placeholders. It restores every original field of a substituted header through `addHeader`. Then it protects the attempt again for its new URL, with any mutator. Returns `true` if the request changed. The package compares attempts with the URL and method that the request had when `completeProcessing` finished. So a processed request callback that changes the URL keeps its headers. |

## ApproovServiceMutator

`io.approov.service.android.ApproovServiceMutator` is an interface. All its methods have defaults, which are the `CLOSE_FAILURE` decisions. See [service mutators](ADVANCED.md#service-mutators).

| Member | Behaviour |
| :--- | :--- |
| `static final ApproovServiceMutator CLOSE_FAILURE` | The 3.5.x token decisions. Substitutes a secure string on `SUCCESS`. For any other secure string status it puts the status in place of the placeholder, and never aborts. Supports protection refresh. |
| `static final ApproovServiceMutator ALWAYS_PROCEED` | Never aborts. Adds the Approov headers for every status except `UNKNOWN_URL` and `UNPROTECTED_URL`: the token header on `SUCCESS` only, the status header otherwise. Substitutes on `SUCCESS`, and otherwise puts the status in place of the placeholder. Supports protection refresh. |
| `static final ApproovServiceMutator DEFAULT` | Declared as the interface type. It is `ALWAYS_PROCEED`, the mutator used if none is set. An app that wants requests aborted on a failed token fetch sets `CLOSE_FAILURE`. |
| `static boolean isNetworkFailure(Approov.TokenFetchStatus status)` | `true` for `NO_NETWORK`, `POOR_NETWORK` and `MITM_DETECTED`. The SDK reports `MITM_DETECTED` for an untrusted network or a captive portal. |
| `static boolean substituteOnSuccess(Approov.TokenFetchResult result)` | The substitution decision of both standard mutators. `true` on `SUCCESS`, `false` for every other status. Never throws. |
| `boolean handleInterceptorShouldProcessRequest(ApproovRequestContext context) throws IOException` | Whether a request is processed. By default `false` for a URL that matches an exclusion, otherwise `true`. |
| `boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult result, String url) throws IOException` | Decides what happens to the request after a token fetch. `true` adds the Approov headers that the status allows. On `SUCCESS`, these are the token, status and trace headers and the signatures. On any other status, this is the status header, on one of your API domains. `false` sends the request with no Approov headers. A thrown exception aborts the request. The [error model](#error-model) describes the default. For a failure status on a host that is not one of your API domains, the request goes out unchanged, whatever this hook returns. The package then ignores an abort from a standard decision, and only your own exception aborts the request. This hook does not control secure strings. Secure strings follow the status. |
| `boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult result, String header) throws IOException` | The package calls it only for a `SUCCESS` or `UNPROTECTED_URL` request over `https`. `true` substitutes the secure string of a `SUCCESS` result, and `false` keeps the placeholder. For any other status, the status replaces the placeholder, whatever this hook returns. The package never uses a value that the result may carry. A thrown exception aborts the request. The default is `substituteOnSuccess`. |
| `boolean handleInterceptorQueryParamSubstitutionResult(Approov.TokenFetchResult result, String queryKey) throws IOException` | As above, for one occurrence of a query parameter. |
| `void handleInterceptorProcessedRequest(ApproovRequestContext context, ApproovRequestMutations changes) throws IOException` | Makes final changes through the context, before message signing. The default does nothing. |
| `boolean supportsProtectionRefresh()` | Whether the processed request callback may run again on a refreshed or rebuilt request. The default is `false`. OkHttp does not call it. On OkHttp, the callback runs on every network attempt. |
| `boolean handlePinningShouldProcessRequest(String url) throws IOException` | Whether connection validation applies to the request. The default is `true`. On OkHttp, the package calls it for every network attempt, including excluded attempts, through the mutator that processed that attempt. On `HttpsURLConnection` and Volley, the package also calls it. gRPC channels and Cronet engines validate every connection to an Approov domain and do not call it. This is because all calls on a channel share its handshake, and all requests on an engine share its built-in hashes. The hook can only remove connection validation, so this is the stricter behaviour. For a connection without Approov validation on those stacks, build a channel or engine without the Approov wiring. |
| `void handleFetchTokenResult(Approov.TokenFetchResult result) throws ApproovException` | Maps a `fetchToken` result. `SUCCESS` returns. Network statuses throw `ApproovNetworkException`. Others throw `ApproovFetchStatusException`. |
| `void handleFetchSecureStringResult(Approov.TokenFetchResult result, String operation, String key) throws ApproovException` | Maps a `fetchSecureString` result. `operation` is `"lookup"` or `"definition"`. `SUCCESS` and `UNKNOWN_KEY` return. `REJECTED` throws `ApproovRejectionException`. Network statuses throw `ApproovNetworkException`. Others throw `ApproovFetchStatusException`. |
| `void handleFetchCustomJWTResult(Approov.TokenFetchResult result) throws ApproovException` | Maps a `fetchCustomJWT` result. `SUCCESS` returns. `REJECTED` throws `ApproovRejectionException`. Network statuses throw `ApproovNetworkException`. Others throw `ApproovFetchStatusException`. |

An interceptor hook aborts a request only by throwing an `IOException`. Your own abort should be a standard network exception such as `java.net.ConnectException`, never an Approov type.

The package logs a `RuntimeException` from any hook as `ApproovServiceMutator.<name> failed`. The request then fails with an `ApproovException` that carries the `RuntimeException`.

## ApproovRequestContext

`io.approov.service.android.ApproovRequestContext` is the mutable view of an outbound request that the pipeline and the mutator hooks work on. It is the same on every stack, and each adapter implements it.

| Method | Behaviour |
| :--- | :--- |
| `String getMethod()` | The HTTP method. `POST` for gRPC. |
| `String getURL()` | The full URL. For gRPC, the `https` URL of the authority. |
| `default String getTargetURI()` | The URL exactly as the stack sends it, signed as `@target-uri`. Defaults to `getURL()`. |
| `boolean supportsURLMutation()` | Whether `setURL` may be called. Query substitution runs only when this is `true`. |
| `void setURL(String url) throws ApproovException` | Replaces the URL. |
| `String getHeader(String name)` | The last value of the header, or `null`. This matches what OkHttp's `Request.header` and `HttpURLConnection.getRequestProperty` return. |
| `List<String> getHeaders(String name)` | Every value of the header. Never `null`. |
| `default List<String> getHeaderNames()` | Every header name, once each. Returns `null`, the default, if the stack cannot list them. |
| `void setHeader(String name, String value)` | Sets the header, replacing any value. |
| `default void addHeader(String name, String value)` | *For adapters, not for app code.* Adds a field after the fields that the header already has. Before the package protects a rebuilt attempt again, such as a redirect, or refreshes stale protection, it removes the protection of the earlier attempt. There it uses this method to restore every original field of a header that it substituted. The default throws `UnsupportedOperationException`. In that case, the package restores a header sent as several fields as one field that holds the last of them. The OkHttp, `HttpsURLConnection` and gRPC contexts implement it. The Volley and Cronet contexts do not. |
| `default void removeHeader(String name)` | Removes every value. The default throws `UnsupportedOperationException`. |
| `default boolean supportsHeaderSubstitution()` | Whether the headers the pipeline sets are the ones the stack sends, so it may substitute them. `true` by default, and on the requests of every adapter, Volley included. |
| `boolean hasBody()` | Whether the request has a body. |
| `byte[] getBodyBytes()` | The body, if it can be read without consuming it. Otherwise `null`. |

## ApproovRequestMutations

`io.approov.service.android.ApproovRequestMutations` records what the pipeline changed on a request. It is passed to `handleInterceptorProcessedRequest`.

It has a public no-argument constructor and public getters. Its setters are package-private, so only the pipeline records what it changed. Otherwise an app could make the package treat a request as unprotected.

| Method | Behaviour |
| :--- | :--- |
| `String getTokenHeaderKey()` | The token header name, or `null` if no token header was added. |
| `String getTokenHeaderPrefix()` | The prefix the token was sent with (`""` if none), or `null` if no token header was added. |
| `String getTraceIDHeaderKey()` | The TraceID header name, or `null`. |
| `String getStatusHeaderKey()` | The status header name, or `null`. |
| `List<String> getSubstitutionHeaderKeys()` | The headers substituted with a secure string. Never `null`. A header that carries a failed fetch status is not listed. |
| `String getOriginalURL()` | The URL before query substitution, or `null` if no query parameter was substituted with a secure string. |
| `List<String> getSubstitutionQueryParamKeys()` | The query parameters substituted with a secure string. Never `null`. One that carries a failed fetch status is not listed. |

## ApproovDefaultMessageSigning

`io.approov.service.android.ApproovDefaultMessageSigning` signs protected requests (RFC 9421) on every stack. It also provides the factory API that configures signing.

You never construct it, and it has no public constructor. Pass a factory to `ApproovService.enableMessageSigning` or `putMessageSigningHostFactory`.

| Member | Value or behaviour |
| :--- | :--- |
| `DIGEST_SHA256`, `DIGEST_SHA512` | `"sha-256"` and `"sha-512"`, the body digest algorithms. |
| `ALG_ES256`, `ALG_HS256` | `"ecdsa-p256-sha256"` for the install key, and `"hmac-sha256"` for the account key. |
| `SIG_ID_INSTALL`, `SIG_ID_ACCOUNT` | `"install"` and `"account"`, the members of the `Signature` dictionary. |
| `static SignatureParametersFactory generateDefaultSignatureParametersFactory()` | Both signatures. They cover `@method` and `@target-uri`, and the token, TraceID and status headers. They also cover `Authorization`, `Content-Length` and `Content-Type` when present. They also add an optional SHA-256 `Content-Digest` (an empty body included), `created`, and an `expires` 15 seconds later. They cover each component one time. A token header that is also one of these headers, such as `setTokenHeader("Authorization", "Bearer ")`, appears one time. An optional header also appears one time (RFC 9421 section 2). |
| `static SignatureParametersFactory generateDefaultSignatureParametersFactory(SignatureParameters baseParametersOverride)` | The same settings, over the base components you give. `null` means `@method` and `@target-uri`. |

### SignatureParametersFactory

`public static class ApproovDefaultMessageSigning.SignatureParametersFactory`. Every request that it applies to shares one instance. A new `SignatureParametersFactory()` produces both signatures, and covers nothing else until you configure it.

| Method | Behaviour |
| :--- | :--- |
| `setBaseParameters(SignatureParameters)` | Base components, copied into every signature. |
| `setBodyDigestConfig(String algorithm, boolean required)` | Adds a `Content-Digest` with `DIGEST_SHA256` or `DIGEST_SHA512`. `null` turns it off. With `required`, if the package cannot digest the body of a request, the request fails with `RequiredBodyDigestException`. Throws `IllegalArgumentException` for any other algorithm. |
| `setUseInstallMessageSigning()`, `setUseAccountMessageSigning()`, `setUseInstallAndAccountMessageSigning()` | Produce the install signature only, the account signature only, or both. Both is the default. |
| `List<String> getAlgs()` | The algorithms, in the order that the package emits them. Throws `IllegalStateException` if neither is enabled. |
| `setAddCreated(boolean)` | Adds the `created` parameter. |
| `setExpiresLifetime(long seconds)` | Adds `expires`. A value of `0` or less adds none. |
| `setAddApproovTokenHeader(boolean)`, `setAddApproovTraceIDHeader(boolean)`, `setAddApproovStatusHeader(boolean)` | Cover the token, TraceID and status headers, when present. |
| `addOptionalHeaders(String...)` | Headers covered when the request has them. |
| `SignatureParameters buildSignatureParameters(ContextComponentProvider provider, ApproovRequestMutations changes) throws ApproovException` | Builds the parameters for one request. It is public, so a subclass may override it. An override may pass the request to another factory. To do this, it calls this method on that factory. An override may also return `null` to leave the request unsigned. An override must be public. If an override throws anything other than `RequiredBodyDigestException`, the package sends the request unsigned and logs an error. Throws `RequiredBodyDigestException` if it cannot make a required digest. |
| `protected boolean generateBodyDigest(ContextComponentProvider provider, SignatureParameters parameters)` | Adds the `Content-Digest` header and component when the body is available, that is when `getBodyBytes()` is not `null`. A zero-length body counts. |

All setters return the factory, for chaining.

The package makes the signatures of the installed factory (its `getAlgs()`), whichever factory built the parameters. The exception is when the returned parameters set an algorithm. So a subclass that delegates to a factory with other algorithms must configure itself the same way.

The configuration fields are `protected`, for subclasses: `baseParameters`, `bodyDigestAlgorithm`, `bodyDigestRequired`, `useInstallMessageSigning`, `useAccountMessageSigning`, `addCreated`, `expiresLifetime`, `addApproovTokenHeader`, `addApproovTraceIDHeader`, `addApproovStatusHeader` and `optionalHeaders`.

* An unsupported algorithm on a built parameter set fails the request with `ApproovException`.
* If no algorithm is usable, the package sends the request unsigned and logs an error that names the cause. This includes two cases: neither signature is enabled (so `getAlgs()` throws `IllegalStateException`), or an override returns an empty list or throws.

### ContextComponentProvider

`public static final class ApproovDefaultMessageSigning.ContextComponentProvider implements io.approov.util.universal.sig.ComponentProvider` reads the RFC 9421 components of a request from its `ApproovRequestContext`, as sent. `getContext()` returns the context.

These methods implement `ComponentProvider`:

* `getMethod()` and `getScheme()`.
* `getTargetUri()`, with the fragment removed.
* `getAuthority()`: the host in lowercase, in brackets for IPv6, with the port only when it is not the default.
* `getPath()`: `/` when empty.
* `getQuery()`: `?` alone when there is no query.
* `getRequestTarget()`.
* `getQueryParam(name)`: the decoded value, re-encoded with the form set, with a space as `%20`. It returns `null` for a parameter that appears more than once, and throws `IllegalArgumentException` for one that is missing.
* `hasField(name)`, and `getField(name)`, which combines repeated values.
* `hasBody()`.
* `getStatus()`, which throws `IllegalStateException` because only requests are supported.

### RequiredBodyDigestException

`public static class ApproovDefaultMessageSigning.RequiredBodyDigestException extends ApproovException`, with the constructor `(String message)`. It means that the package could not generate a required body digest. Each request with this problem fails as an ordinary failed request.

## Exceptions

| Class | Extends | Members |
| :--- | :--- | :--- |
| `ApproovException` | `java.io.IOException` | Constructors `(String)`, `(String, Throwable)` and `(Throwable)`. |
| `ApproovFetchStatusException` | `ApproovException` | `(Approov.TokenFetchStatus status, String message)`. `Approov.TokenFetchStatus getTokenFetchStatus()`, which may be `null`. |
| `ApproovNetworkException` | `ApproovFetchStatusException` | `(String)` and `(Approov.TokenFetchStatus, String)`. A `NO_NETWORK`, `POOR_NETWORK` or `MITM_DETECTED` failure. A retry that the user starts may solve it. On OkHttp, the package does not throw it for a call that OkHttp cannot connect (no network, failed TLS handshake). That call fails in OkHttp first. |
| `ApproovRejectionException` | `ApproovFetchStatusException` | `(String message, String arc, String rejectionReasons)`. `String getARC()` returns the [Attestation Response Code](https://docs.approov.io/app-integration/frontend/attestation/#attestation-response-code). `String getRejectionReasons()` returns a comma separated list when the feature is enabled, otherwise `null`. |
| `ApproovDefaultMessageSigning.RequiredBodyDigestException` | `ApproovException` | See above. |

All are in `io.approov.service.android`.

## ApproovPinningVerifier

```java
public final class ApproovPinningVerifier implements javax.net.ssl.HostnameVerifier
ApproovPinningVerifier(HostnameVerifier delegate)
boolean verify(String hostname, SSLSession session)
```

The connection validation used for `HttpsURLConnection`, Volley and gRPC. See [Connection validation](ADVANCED.md#connection-validation).

The constructor throws `IllegalArgumentException` if `delegate` is `null`.

`verify` runs `delegate` first. Then it checks that a public key in the chain that vouches for the server certificate matches the current public key hashes for the host. If the host has no hashes, it checks against the Managed Trust Roots instead.

The verifier finds the chain to check like this:

* Normally it is the chain that the platform trust manager validated (`X509TrustManagerExtensions.checkServerTrusted`), which ends at the trust anchor that the platform chose.
* The platform may not trust the presented chain, for example with your own trust manager for a private CA. Or the verifier may not be able to ask the platform. In those cases, the verifier builds the chain from the server certificate by signature. Each issuer must be a CA certificate (basic constraints, and `keyCertSign` when key usage is present). Its subject must be the issuer that the certificate names. Its key must verify the signature of the certificate. The verifier gets a root that the server did not present from the trust anchors.
* The verifier never matches a certificate that the server presented but that is not in that chain.

Other details:

* The verifier compares host names and protected domains in lowercase, with one trailing dot removed.
* It reads the hashes on every call and caches no verdict. It reads the platform trust store one time per process.
* Before initialization and in bypass mode, it applies only `delegate`, which is OS trust.

`verify` never throws. After protection is enabled, if the verifier cannot do the check, it rejects the host. The stack then fails the handshake with `SSLPeerUnverifiedException`.

* The verifier rejects a listed host if it cannot read the hashes of that host.
* The verifier accepts a host with no hashes and no Managed Trust Roots on OS trust, and logs the host. It does the same for any host when the installation has no configuration yet (no protected domains at all).
* A listed host can have no hashes while the Managed Trust Roots (`*`) are empty or absent. For such a host, the verifier logs a warning one time per host per process. The warning names the host, never a hash.

## io.approov.service.android.okhttp

### ApproovOkHttp

```java
static void setOkHttpClientBuilder(OkHttpClient.Builder builder)
static void setOkHttpClientBuilder(String builderName, OkHttpClient.Builder builder)
static OkHttpClient getOkHttpClient()
static OkHttpClient getOkHttpClient(String builderName)
static OkHttpClient.Builder protect(OkHttpClient.Builder builder)
static Interceptor networkInterceptor()
```

OkHttp is a runtime dependency of the package. So code that calls these methods must declare OkHttp itself. These methods need OkHttp 4.9.2 or later. See [OkHttp version](ADVANCED.md#okhttp-version).

* `getOkHttpClient` returns a cached client. On first use, the package builds it with `protect`, from the builder set for that name, or from a plain builder if no builder was set. You may call it before `initialize`. The client protects its requests after protection is enabled.
* `setOkHttpClientBuilder` sets the builder for the default client or a named client, and clears that cached client.
* `protect` adds the Approov interceptors to your builder and returns the same builder.
  * It removes any Approov interceptors that were added before, in any position.
  * It adds `ApproovInterceptor` as the last network interceptor. It adds a small application interceptor as the last application interceptor.
  * So your own interceptors run before the package. Your network interceptors never see the Approov headers, and the signature covers a change that they make.
  * Add your interceptors before you call it. See [interceptor order](ADVANCED.md#interceptor-order).
* `networkInterceptor` returns the shared `ApproovInterceptor`, for an app that adds it to its own builder. Add it as the last network interceptor. Prefer `protect`. Without the application interceptor that `protect` adds:
  * The package substitutes query placeholders only in an attempt to the URL of the original request of the call.
  * A response to an attempt whose query had a substituted secure string reaches your app with `Cache-Control: no-store`.

These methods throw `IllegalArgumentException`:

* `getOkHttpClient(String)` for a `null` name.
* `setOkHttpClientBuilder` for a `null` builder, or a `null` name.
* `protect` for a `null` builder.

### ApproovOkHttpWeaver

```java
static OkHttpClient build(OkHttpClient.Builder builder)
```

The runtime target of the [Gradle plugin](ADVANCED.md#the-gradle-plugin)'s rewrite of `OkHttpClient.Builder.build()`. It calls `ApproovOkHttp.protect(builder).build()`. Its name and signature do not change, because woven bytecode refers to them.

### ApproovReactNativeWeaver

```java
static OkHttpClient.Builder protectBuilder(OkHttpClient.Builder builder)
static OkHttpClient protectClient(OkHttpClient client)
```

The runtime target of the Gradle plugin's [React Native](approov-gradle-plugin/README.md#react-native) weave, which rewrites React Native's `OkHttpClientProvider`. Your app does not call it.

* `protectBuilder` calls `ApproovOkHttp.protect(builder)` on the builder that the provider creates, and returns the same builder.
* `protectClient` protects the client that your app's `OkHttpClientFactory` returns. It derives a new client with `newBuilder()`, so the new client keeps the settings and interceptors of your client and shares its connection pool and dispatcher.
* Both return `null` for `null`. So when your factory returns `null`, React Native uses its default client, which `protectBuilder` protects.

### ApproovInterceptor

```java
public final class ApproovInterceptor implements okhttp3.Interceptor   // no public constructor
Response intercept(Interceptor.Chain chain) throws IOException
```

`ApproovInterceptor` is the network interceptor that applies Approov to every network attempt of a call. It has no public constructor. Only `ApproovOkHttp` creates it, and every client shares one instance. `protect`, `getOkHttpClient` and the woven `build()` install it, and `ApproovOkHttp.networkInterceptor()` returns it.

It must be the last network interceptor. It protects each attempt just before OkHttp writes it, after the connection is up. OkHttp builds every retry, redirect and authenticator follow-up from the request of the app before the network interceptors run. So each attempt reaches the interceptor without the changes that the package made to an earlier attempt. For each attempt, after protection is enabled, it does these steps:

1. runs the pipeline: the token fetch, the token, status and trace headers, and the secure string substitutions
2. for a processed attempt, runs the processed request callback, whatever `supportsProtectionRefresh()` returns, then message signing
3. validates the connection against the current public key hashes, if the mutator that processed the attempt applies connection validation to it (`ApproovPreparedRequest.handlePinningShouldProcessRequest`). Hashes from the fetch of this attempt apply. The interceptor also checks excluded attempts.
4. sends the attempt

Before initialization and in bypass mode, it sends the attempt unchanged, with no connection validation.

It runs only after OkHttp resolves the host and connects. With no network, or when the TLS handshake fails (for example to an untrusted proxy), the call fails in OkHttp before the interceptor runs. There is no fetch and no mutator decision, and nothing is sent. The app gets the `IOException` from OkHttp, not `ApproovNetworkException`.

* If a call is cancelled before it is signed, including during the token fetch, the interceptor does not sign or send it. OkHttp handles a cancel that arrives after that check.
* The response carries the request that the interceptor received, with its placeholders. So the network interceptors of the app, `networkResponse()`, `priorResponse()` and the `Cache` of the client never see the token, the signature or a substituted secure string.
* The connection validation hook `handlePinningShouldProcessRequest` receives the URL that the interceptor received, with its placeholders.
* The interceptor substitutes query placeholders only in an app attempt. An app attempt has the scheme, host and port of the app URL. It also has the same values for each substitution query parameter. The app URL is the URL of the call after the application interceptors of the app. If the attempt has no tag from the `protect` application interceptor, the app URL is the original URL of the call. So the interceptor never looks up the query of a redirect target as secure string keys.
* The processed request callback may change the path and query of the attempt. It cannot change the host or port, because OkHttp fixes them for a network interceptor. A change of host or port fails with `ApproovException`.
* The package signs the attempt as the network interceptors of the app left it. The `Content-Type`, and the `Content-Length` or `Transfer-Encoding`, are as the OkHttp bridge set them.
* A validation failure throws `SSLPeerUnverifiedException` and does not close the connection. HTTP/2 may share that connection.
* The package gives the hashes to the OkHttp `CertificatePinner` keyed by the request host, in lowercase and with one trailing dot removed. The `CertificatePinner` compares the strings as given. A `*.` key cannot occur. The `CertificatePinner` would read such a key as a pattern.
* A listed host can have no hashes while the Managed Trust Roots are empty or absent. For such a host, the interceptor logs a warning one time per host (`ApproovPinning` tag).
* The `Cache` of the client never stores a response to an attempt whose query had a substituted secure string. The interceptor marks the response `no-store`. The `protect` application interceptor restores the `Cache-Control` of the server for your app.
* OkHttp never runs a network interceptor for a WebSocket upgrade, so the upgrade goes out without Approov processing.

## io.approov.service.android.httpsurlconn

### ApproovUrlConnection

```java
static HttpsURLConnection addApproovToConnection(HttpsURLConnection connection) throws IOException
@Deprecated static void addApproov(HttpsURLConnection connection) throws IOException
```

`addApproovToConnection` returns a wrapper. Continue with the wrapper, not the original connection. The wrapper:

* applies the query substitutions to the URL
* validates the connection
* buffers a request body for the digest
* applies the protection, and the stale refresh, when the request is sent
* follows redirects itself, and protects each hop again for the URL of that hop
* reports a failed request through the methods that declare `IOException`. The header getters return absent headers instead.

Set the headers, method and body on the wrapper. The package protects what you set after the call, because it applies the protection when the request is sent. `addApproovToConnection` does not fail for a request that the package cannot protect. The `ApproovException` comes from `connect`, `getOutputStream`, `getResponseCode` or another method that declares `IOException`. Its `throws IOException` stays for compatibility.

See [HttpsURLConnection](ADVANCED.md#httpsurlconnection).

`addApproov` is deprecated. Use `addApproovToConnection`, or the `io.approov.gradle` plugin, which weaves `URL.openConnection()`. `addApproov` still works. It protects the connection in place, before it is connected, without a body digest. It throws `ApproovException` when a query substitution applies.

`addApproov` protects the connection when all of these conditions are true:

* protection is enabled
* the connection is not already protected
* the mutator processes the request

When `addApproov` protects the connection, it calls `setInstanceFollowRedirects(false)`, even if your app set `true`. So the platform never sends the Approov headers or a substituted secret again to a redirect target. It logs one warning per process, and your app receives the 3xx.

In these other cases, redirect behaviour does not change:

* before protection is enabled
* in bypass mode
* for an excluded URL
* for a host that is not in Approov
* for a failure on a host that is not one of your API domains
* for a connection that is already protected, such as one that the woven `openConnection()` of the plugin returned. That connection continues to follow redirects itself, and protects each hop again.

See [Redirects and retries](ADVANCED.md#redirects-and-retries).

Both methods:

* throw the abort of the mutator as an `IOException`
* sign a GET with `setDoOutput(true)` as the POST that it is sent as
* do nothing more for a connection that is already protected (`ApproovProtectedConnection`)
* let the connection go ahead without Approov processing before protection is enabled

### ApproovUrlConnectionWeaver

```java
static URLConnection openConnection(URL url) throws IOException
static URLConnection openConnection(URL url, Proxy proxy) throws IOException
```

The runtime target of the Gradle plugin's rewrite of `URL.openConnection()` and `URL.openConnection(proxy)`.

It returns an `HttpsURLConnection` in a wrapper. So the package applies the protection when the request is sent, after your app sets its headers and body. The wrapper keeps the streaming mode of your app and the proxy. It returns any other connection unchanged.

### ApproovProtectedConnection

```java
public interface ApproovProtectedConnection
```

A marker for a connection that already has Approov protection, such as the wrappers above. The explicit methods check it, so that they do not protect a connection two times.

## io.approov.service.android.volley

### ApproovVolley

```java
static BaseHttpStack getBaseHttpStack()
```

`getBaseHttpStack` returns the shared Approov stack. Pass it to `Volley.newRequestQueue(context, stack)`.

Secure strings need no extra call. Put a placeholder in a substitution header that your `Request` returns from `getHeaders()`, or in a substitution query parameter of its URL. The stack substitutes it through the pipeline, as on every other stack. There is no helper for headers, URLs or parameter maps. See [Removed in 3.7.0](#removed-in-370).

### ApproovHurlStack

```java
public class ApproovHurlStack extends com.android.volley.toolbox.HurlStack
ApproovHurlStack()
HttpResponse executeRequest(Request<?> request, Map<String, String> additionalHeaders) throws IOException, AuthFailureError
protected HttpURLConnection createConnection(URL url) throws IOException
```

The stack that `getBaseHttpStack` returns.

`executeRequest` does the following:

* It reads `getHeaders()` and `getBody()` of the request one time, and runs the pipeline on that capture.
* The pipeline substitutes header and query parameter placeholders into the headers and URL of the capture. The stack then sends exactly the capture, so it sends what was substituted.
* The stack sends the content type of the body as a header, so the package signs it.
* It follows redirects itself while `HttpURLConnection.getFollowRedirects()` is `true`, and protects each hop again. See [Redirects and retries](ADVANCED.md#redirects-and-retries).

`createConnection` installs `ApproovPinningVerifier` on HTTPS connections. It leaves plain HTTP connections unvalidated.

The stack replaces its verifier each time the public key hashes change, so a connection opened before the change is not reused.

### ApproovVolleyWeaver

```java
static RequestQueue newRequestQueue(Context context)
```

The runtime target of the Gradle plugin's rewrite of `Volley.newRequestQueue(context)`. It returns a queue on the Approov stack.

## io.approov.service.android.grpc

### ApproovChannelBuilder

```java
public class ApproovChannelBuilder extends io.grpc.ForwardingChannelBuilder2<ApproovChannelBuilder>
static ApproovChannelBuilder forAddress(String host, int port)
static ApproovChannelBuilder forTarget(String target)
static ApproovChannelBuilder forTarget(String target, ChannelCredentials creds)
ManagedChannel build()
ApproovChannelBuilder usePlaintext()
```

Builds an OkHttp-transport `ManagedChannel` whose TLS connections are validated with `ApproovPinningVerifier`. Every other `ManagedChannelBuilder` setting goes to the underlying builder.

* `build()` keeps a weak reference to each channel. Each time the public key hashes change, it moves the channel to idle (`enterIdle()`). Calls in flight finish. The next call connects, and the package validates the connection again. The package skips channels that are shut down.
* `forTarget(target, creds)` throws `IllegalArgumentException`. When `ChannelCredentials` are given, grpc-okhttp fixes the TLS settings, the hostname verifier included. Use `forTarget(String)` or `forAddress(String, int)` instead.
* `usePlaintext()` logs a warning. The package does not validate a plaintext channel. A call that `ApproovClientInterceptor` protects on such a channel fails before it is sent.

The constructors and `delegate()` are `protected`.

### ApproovClientInterceptor

```java
public class ApproovClientInterceptor implements io.grpc.ClientInterceptor
ApproovClientInterceptor(Channel channel)
ApproovClientInterceptor(String host)
<ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next)
```

Runs the metadata of each call through the pipeline just before `ClientCall.start`. This may fetch a token over the network. `@target-uri` is `https://` with the `:authority` and `:path` of the call.

The package also adds call credentials to each call, combined with the call credentials that the call already has. The channel applies them when it creates the stream, before it writes anything. These credentials fail these calls with `Status.UNAVAILABLE`:

* a call that the package aborted
* a call that the package protected (it has the token header) on a transport whose security level is `NONE`

The status cause is the abort (an `ApproovException`, or the `IOException` of your mutator), or an `SSLPeerUnverifiedException`. The call still starts, so the listener of your app is installed and its later calls are legal.

The package leaves calls that it does not process to your app. Use the `host` constructor to name the host of the channel when you do not have a `Channel`.

## io.approov.service.android.cronet

Cronet is a `compileOnly` dependency of the package. These classes need a Cronet dependency in your app. See [Cronet](ADVANCED.md#cronet).

### ApproovCronet

```java
static CronetEngine.Builder newBuilder(Context context)
static CronetEngine.Builder newBuilder(CronetProvider provider)
static boolean isProtected(CronetEngine engine)
```

`newBuilder` returns an `ApproovCronetEngineBuilder`.

* Use `newBuilder(context)` in place of `new CronetEngine.Builder(context)`. It chooses the provider as that constructor does.
* Use `newBuilder(provider)` in place of `provider.createBuilder()`.

`isProtected` reports whether an engine is an Approov engine.

### ApproovCronetEngineBuilder

```java
public final class ApproovCronetEngineBuilder extends ExperimentalCronetEngine.Builder
ApproovCronetEngineBuilder(Context context, String site)
ExperimentalCronetEngine build()
```

It is an `ExperimentalCronetEngine.Builder`, as the builders of real Cronet are.

* The constructor makes one real builder, as `new CronetEngine.Builder(context)` does. So if Cronet is unavailable, the constructor throws.
* The package applies each setting to that real builder when it records the setting. So a refused value throws at that call.
* The first engine generation uses that real builder.
* `build()` returns an `ApproovCronetEngine` without building a real engine.

The Gradle plugin uses the constructor to replace `new CronetEngine.Builder(context)`. `site` names the woven call site, for diagnostics. The builder keeps only the application context.

### ApproovCronetEngine

```java
public final class ApproovCronetEngine extends ExperimentalCronetEngine
ExperimentalUrlRequest.Builder newUrlRequestBuilder(String url, UrlRequest.Callback callback, Executor executor)
ExperimentalBidirectionalStream.Builder newBidirectionalStreamBuilder(String url, BidirectionalStream.Callback callback, Executor executor)
String getVersionString()
void shutdown()
void bindToNetwork(long networkHandle)
void startNetLogToFile(String fileName, boolean logAll)
void startNetLogToDisk(String dirPath, boolean logAll, int maxSize)
void stopNetLog()
byte[] getGlobalMetricsDeltas()
URLConnection openConnection(URL url) throws IOException
URLConnection openConnection(URL url, Proxy proxy) throws IOException
URLStreamHandlerFactory createURLStreamHandlerFactory()
```

It is an `ExperimentalCronetEngine`, as the engines of real Cronet are. Its request builders and requests are `ExperimentalUrlRequest.Builder` and `ExperimentalUrlRequest`. `setIdempotency` and `disableConnectionMigration` go to the real request.

Requests:

* `newUrlRequestBuilder` throws `NullPointerException` for a `null` URL, callback or executor, with the Cronet messages.
* When a request from it starts, the request runs through the pipeline on an Approov thread. The package sends it on the real engine for the current public key hashes.
* `newBidirectionalStreamBuilder` throws `UnsupportedOperationException`.

Shutdown and network binding:

* `shutdown` shuts down every engine generation. A generation with requests in flight shuts down after they finish. In the same case, the Cronet engine throws instead. A request started before `shutdown` still runs.
* A request built or started after `shutdown` throws `IllegalStateException("Engine is shut down.")` at `build()` or `start()`, as on Cronet. A second `shutdown` does not throw.
* `bindToNetwork` binds the current generation and every later one.

Other engine methods:

* `getVersionString`, the net log methods (`startNetLogToDisk` included) and `getGlobalMetricsDeltas` act on the current generation, and build it if necessary.
* They cannot do this after `shutdown`. They also cannot do this when the package cannot build the generation, because it cannot read or apply the hashes, or because Cronet fails. Then they log the reason. `getVersionString` returns `"ApproovCronet/unavailable"`. The net log methods do nothing. `getGlobalMetricsDeltas` returns an empty array.
* `openConnection(URL)` and `createURLStreamHandlerFactory` use the validated engine but add no Approov headers. The package logs this one time per engine. When the package cannot make a generation, `openConnection` throws the `SSLPeerUnverifiedException`, and `createURLStreamHandlerFactory` throws an `IllegalStateException`.
* `openConnection(URL, Proxy)` does what the Cronet engine does. `Proxy.NO_PROXY` behaves as `openConnection(URL)`, and any other proxy throws `UnsupportedOperationException`. It never opens the platform's own connection.

These members are inherited and not overridden. None of them sends anything:

* `addRequestFinishedListener` and `removeRequestFinishedListener`. The package never calls a listener, because its `RequestFinishedInfo` would contain the substituted secure strings.
* `getActiveRequestCount`.
* The network quality listeners and estimates. They keep the Cronet defaults: no effect, or an unknown value.
* On the request builder, `setRequestFinishedListener` and `setRawCompressionDictionary`.

The response info shows your app the URL that it requested on each hop. A redirect target (`newLocationUrl`, the `Location` header, the URL chain) is the URL of the server, as the server sent it.

Connection validation needs a native Cronet provider: `cronet-embedded`, Play services Cronet, or the Android 14+ `HttpEngine` provider. On the Java fallback of Cronet (`cronet-fallback`), requests to hosts with public key hashes fail with `SSLPeerUnverifiedException`. Other hosts use OS trust.

When a request fails, `UrlRequest.Callback.onFailed` receives a `NetworkException`. The subclass of it that the package uses is not public. Its cause is the exception of the package, the `IOException` of your mutator, or an `SSLPeerUnverifiedException`.

Its three values are the values that real Cronet reports for the same failure. Cronet reports almost every net error as `ERROR_OTHER`, a public key mismatch included. The net error itself is in `getCronetInternalErrorCode()`.

| Failure | `getErrorCode()` | `getCronetInternalErrorCode()` | `immediatelyRetryable()` |
|---|---|---|---|
| validation failure (an `SSLPeerUnverifiedException` anywhere in the cause chain) | `ERROR_OTHER` | -150 `ERR_SSL_PINNED_KEY_NOT_IN_CERT_CHAIN` | `false` |
| redirect limit | `ERROR_OTHER` | -310 `ERR_TOO_MANY_REDIRECTS` | `false` |
| URL whose authority cannot be verified | `ERROR_OTHER` | -300 `ERR_INVALID_URL` | `false` |
| app abort with `ConnectException` | `ERROR_CONNECTION_REFUSED` | -102 `ERR_CONNECTION_REFUSED` | `false` |
| app abort with `UnknownHostException` | `ERROR_HOSTNAME_NOT_RESOLVED` | -105 `ERR_NAME_NOT_RESOLVED` | `false` |
| app abort with `SocketTimeoutException` | `ERROR_TIMED_OUT` | -7 `ERR_TIMED_OUT` | `true`, as Cronet's `ERROR_TIMED_OUT` |
| anything else (a `CLOSE_FAILURE` abort, a rejection, any other exception) | `ERROR_OTHER` | -2 `ERR_FAILED` | `false` |

The message of the exception names the host, never the URL. If the package takes a URL from the message of the cause, it shortens that URL to the scheme and host. The cause itself does not change.

### ApproovCronetWeaver

```java
static CronetEngine.Builder createBuilder(CronetProvider provider, String site)
```

The runtime target of the Gradle plugin's rewrite of `provider.createBuilder()`.

## io.approov.util.universal.sig

The RFC 9421 helpers that you use to customise signing. They moved here from `io.approov.util.sig`, so they cannot collide with the copy in another Approov package.

* `SignatureParameters` (`Cloneable`) holds the parameters and covered components of one signature.
  * Constructors `()`, and a copy constructor `(SignatureParameters)`.
  * `addComponentIdentifier(String)`, which lowercases a field name, and `addComponentIdentifier(StringItem)`. Both add nothing for a component already covered with the same parameters (RFC 9421 section 2).
  * `containsComponentIdentifier(String)`, which ignores parameters, and `containsComponentIdentifier(StringItem)`.
  * Getters and chaining setters for `alg`, `created`, `expires`, `keyid`, `nonce` and `tag`: `getAlg`, `setAlg`, `getCreated`, `setCreated(Long)`, `getExpires`, `setExpires(Long)`, `getKeyid`, `setKeyid`, `getNonce`, `setNonce`, `getTag` and `setTag`.
  * `getCustomParameter`, `setCustomParameter` and `setParameters(Map)`.
  * `isDebugMode` and `setDebugMode`. Debug mode adds a `Signature-Base-Digest` header.
  * `toComponentIdentifier()`, `toComponentValue()` and `toString()`.
  * `static fromDictionaryEntry(Dictionary, String)`. It keeps the components of a received `Signature-Input` as received, repeats included, so a verifier can reject them.
* `ComponentProvider` is the interface that supplies component values: `getMethod`, `getAuthority`, `getScheme`, `getTargetUri`, `getRequestTarget`, `getPath`, `getQuery`, `getQueryParam`, `getStatus`, `hasField`, `getField`, `hasBody` and `getComponentValue`. It also defines the derived component names `DC_METHOD`, `DC_TARGET_URI`, `DC_AUTHORITY`, `DC_SCHEME`, `DC_PATH`, `DC_QUERY`, `DC_QUERY_PARAM`, `DC_REQUEST_TARGET` and `DC_STATUS`, plus `PATTERN_WHITESPACE` and `static combineFieldValues(List<String>)`.
* `SignatureBaseBuilder` has the constructor `(SignatureParameters, ComponentProvider)` and the method `String createSignatureBase()`.

The Structured Fields types in `io.approov.util.universal.http.sfv` (`Dictionary`, `StringItem`, `InnerList`, `Parser` and the other item types) are public because these helpers use them. Apart from this, they are not part of the API of the package.

The Android build generates `io.approov.service.android.BuildConfig`. It is not API. It carries the standard fields (`DEBUG`, `LIBRARY_PACKAGE_NAME`, `BUILD_TYPE`) and `APPROOV_SERVICE_VERSION`.

## Removed in 3.7.0

| Removed | Replacement |
| :--- | :--- |
| `isInitialized()` | `isApproovServiceEnabled()`. Use `isApproovProtectionEnabled()` to know whether requests are protected. |
| `prefetch()` | None. The Approov SDK manages prefetching. |
| `warmTokenCache()` | None. The SDK manages prefetching. gRPC call creation, Volley enqueue and the woven `URL.openConnection()` no longer start a fetch. |
| `setUseApproovStatusIfNoToken(boolean)`, `getUseApproovStatusIfNoToken()` | The `Approov-Status` header (`setStatusHeader`). Failure information never goes in the token header. |
| `setProceedOnNetworkFail(boolean)`, `getProceedOnNetworkFail()` | None. The default mutator, `ALWAYS_PROCEED`, sends requests after a network failure. `CLOSE_FAILURE`, or a custom mutator, fails them. |
| `setInstallAttrsInToken(String)` | `setInstallAttributes(String)` |
| `ApproovVolley.newRequestQueue(Context)` | `Volley.newRequestQueue(context, ApproovVolley.getBaseHttpStack())` |
| `ApproovService.substituteQueryParams(URL)`, `ApproovService.substituteQueryParam(URL, String)` | Put the placeholder in the request URL. The pipeline substitutes it only for an `https` URL that Approov protects. This works on OkHttp, Cronet and Volley, and on `HttpsURLConnection` through `addApproovToConnection` or the Gradle plugin. The old helpers put the secret in the URL before the package knew where the request would go. |
| `ApproovVolley.substituteQueryParamInURLString(String, String)` | Put the placeholder in the request URL. The Volley stack substitutes it through the pipeline. |
| `ApproovVolley.substituteQueryParam(Map, String)` | None. The package could not remove a secure string in a request body from a request that should not contain it. Send it as a header placeholder from `getHeaders()`, or as a query placeholder in the URL. |
| `ApproovVolley.substituteHeader(Map, String, String)` | Put the placeholder in the header your `Request` returns from `getHeaders()`, and register the header with `addSubstitutionHeader`. The Volley stack substitutes it through the pipeline, as every other stack does, only for an `https` URL that Approov protects. The old helper substituted in `getHeaders()`, before the package knew where the request would go. |
| `ApproovPinningVerifier(HostnameVerifier, boolean prefetchOnVerify)` | `ApproovPinningVerifier(HostnameVerifier)` |
| `ApproovMessageSigning` as a service mutator (`setServiceMutator(new ApproovMessageSigning().setDefaultFactory(factory))`) | `ApproovService.enableMessageSigning(factory)` and `putMessageSigningHostFactory(host, factory)`. The new name of the class is `ApproovDefaultMessageSigning`. It has no public constructor, and no `setDefaultFactory`, `putHostFactory`, `handleInterceptorProcessedRequest` or `supportsProtectionRefresh`. |
| `io.approov.util.sig.*`, `io.approov.util.http.sfv.*` | `io.approov.util.universal.sig.*`, `io.approov.util.universal.http.sfv.*` |
| `ApproovRequestMutations` setters (`setTokenHeaderKey`, `setTokenHeaderPrefix`, `setTraceIDHeaderKey`, `setStatusHeaderKey`, `setSubstitutionHeaderKeys`, `setSubstitutionQueryParamResults`) as public methods | None. They are package-private, so only the pipeline records what it changed. The getters are unchanged. |
