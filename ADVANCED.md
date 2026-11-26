# Advanced Options

The Approov Package for Android works through the networking objects that it gives you, or that the Gradle plugin protects. After you call `initialize` with your Approov account ID, the package adds the `Approov-Status` header to each request to your protected domains, other than [secrets-only APIs](#secrets-only-apis). When it can get a token, it also adds the Approov token and message signatures. Your backend checks the token, and decides.

A standard integration needs nothing from this document, as the [README](README.md) shows. The options below change the defaults, for apps with specific needs. [REFERENCE.md](REFERENCE.md) documents every class and method.

| What you need | Read |
| :--- | :--- |
| Understand what reaches your backend | [What each request carries](#what-each-request-carries), [What happens by default](#what-happens-by-default) |
| Fail requests in the app when there is no token | [Service mutators](#service-mutators) |
| Choose the signatures, or switch signing off | [Message signing](#message-signing) |
| Configure startup or runtime activation | [Initialize, then configure](#initialize-then-configure), [Bypass initialization](#bypass-initialization) |
| Investigate an unexpected result | [Diagnostics](#diagnostics) |

## What each request carries

What the package adds to a request depends on the token fetch status that the Approov SDK reports for the request URL. This is the same on every networking stack.

| Request goes to | Token fetch status | What the package adds |
| :--- | :--- | :--- |
| a protected domain that you added with `approov api -add` | `SUCCESS` | the `Approov-Token`, `Approov-TraceID` and `Approov-Status` headers, your secure strings (over `https`), the signatures, and a validated connection |
| a [secrets-only API](#secrets-only-apis) | `UNPROTECTED_URL` | your secure strings (over `https`) and a validated connection. No Approov headers and no signatures. |
| a protected domain | a failure status, such as `NO_NETWORK` or `NO_APPROOV_SERVICE` | the `Approov-Status` header with the status name, and a validated connection. No token header (not an empty one), no trace header, no secure strings and no signatures. |
| any other domain | any | nothing. The connection uses OS trust only. |

The headers your backend can receive:

* `Approov-Token` carries the Approov token, a short-lived signed JWT. Your backend checks it and rejects a request whose token is missing or not valid. The token also carries the public key of the app installation, for the `install` signature.
* `Approov-Status` carries the result of the attestation. See [The Approov-Status header](#the-approov-status-header).
* `Signature` and `Signature-Input` carry the RFC 9421 [message signatures](#message-signing), on a request that carries a token. `Content-Digest` carries a SHA-256 digest of the body, when the package can read the body.
* `Approov-TraceID` is an optional debug header. Your backend passes it on unchanged.

### Secrets-only APIs

Some APIs need a secret, such as a third-party API key, but cannot check an Approov token. Add such an API with:

```sh
approov api -add api.thirdparty.example -noApproovToken
```

The SDK then reports `UNPROTECTED_URL` for its URLs. The package validates its connections and substitutes your [secure strings](#secure-strings) over `https`, but adds no Approov header and no signature. When the SDK cannot attest, for example with `NO_APPROOV_SERVICE`, the request carries `Approov-Status` with that status and keeps its placeholders.

### The Approov-Status header

The package sends `Approov-Status` on every request to a token-protected domain that goes out with Approov headers, `SUCCESS` included. It holds the SDK status name unchanged: `SUCCESS`, `NO_NETWORK`, `MITM_DETECTED`, `NO_APPROOV_SERVICE`, `REJECTED` and so on. It tells your backend why a request carries no token, so your backend can log the reason and act on it. The header reports the token fetch only, never a secure string result.

## What happens by default

For a request to a protected domain, the package fetches an Approov token. It then applies the decisions of the installed [service mutator](#service-mutators). The default, `ApproovServiceMutator.DEFAULT`, is `ALWAYS_PROCEED`. It never fails a request because of its token fetch:

* On `SUCCESS`, the request carries the token, the status and trace headers, the secure strings and both signatures.
* On a failure status, the request goes out with the status in `Approov-Status` and no token header. Its secure string placeholders stay as the app wrote them.
* A failed secure string fetch puts its status in place of the placeholder, for example `X-Api-Key: REJECTED`. It never fails the request.

Your backend is the enforcement point. Under every mutator, these still fail the request:

* a TLS connection that fails validation, with `javax.net.ssl.SSLPeerUnverifiedException`
* a configuration error in your app, such as a required body digest that the package cannot make
* an abort that your own mutator asks for

Only an `IOException` leaves the request path, so the package never crashes the app. Each stack reports it through its usual error channel (see [Networking stacks](#networking-stacks)).

## Message signing

Message signing is on by default. The package signs every request that carries an Approov token header, on every networking stack, with both of these signatures:

* The install signature, `ecdsa-p256-sha256`. It uses a key that is unique to the app installation, kept in the secure hardware of the device when the device has it. Its dictionary member is `install`.
* The account signature, `hmac-sha256`. It uses a shared account key, which the app receives when it passes attestation. Its member is `account`.

Both are members of the same `Signature` and `Signature-Input` headers. Your backend verifies the signature that it is set up for, as [Installation Message Signing](https://docs.approov.io/operations/message-signing/installation/) and [Account Message Signing](https://docs.approov.io/operations/message-signing/account/) describe. Make sure that your backend, and any proxy in front of it, accept these headers.

```kotlin
ApproovService.disableMessageSigning()      // switch it off; the factories are kept
ApproovService.isMessageSigningEnabled()    // whether it is on
ApproovService.enableMessageSigning()       // switch it on again, with both signatures
```

Signing does not depend on the service mutator, and `initialize` does not change it. The package signs last, after the mutator decisions, the substitutions and the processed request callback. So the signature covers what is sent. It signs each redirect, retry and refresh again.

### When a request goes unsigned

* If the package cannot make one signature, the request goes ahead with the other. The package logs a warning for each kind of missing signature, one time for each process.
* If it can make neither, the request goes ahead unsigned, and the package logs an error one time for each process.
* Later occurrences log at `DEBUG` only, under the `ApproovMsgSign` tag.
* A request that goes ahead without a token carries no signature.

Only two conditions fail the request: an algorithm that is not supported, and a required body digest that the package cannot make.

### What the default signature covers

* the request method and target URI
* the token, trace and status headers, when present
* the `Authorization`, `Content-Length` and `Content-Type` headers, when present
* a SHA-256 `Content-Digest` of the body, when the package can calculate one
* a `created` timestamp and a 15 second `expires`, from `getServerAdjustedCurrentTimeMS()`, so a device with a wrong clock still signs within the time window

The components are the values that the stack sends. The package makes the body digest on OkHttp (for a replayable body of known length), on the buffered `HttpsURLConnection` wrapper, on Volley and on Cronet. It does not make one on the in-place `addApproov` or on gRPC.

**Do not write an explicit default port** (`:443` for `https`) in a URL that you call. Some stacks sign the port and others remove it, so an edge that normalises the URL cannot make the signed value again. Your backend builds the components from the raw request target that it received, not from a URL object that normalises it.

### Choosing the signatures and the covered components

A signature parameters factory decides which signatures a request gets and which components they cover. Give a factory to `enableMessageSigning`, or set one for a host with `putMessageSigningHostFactory`. Start from the default factory:

```kotlin
import io.approov.service.android.ApproovDefaultMessageSigning
import io.approov.service.android.ApproovService

val factory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
    .setUseInstallMessageSigning()          // install only; or setUseAccountMessageSigning(), or
                                            // setUseInstallAndAccountMessageSigning() (the default)
    .setExpiresLifetime(60)                 // default 15s
    .addOptionalHeaders("X-Request-Id")     // covered when present
    .setBodyDigestConfig(ApproovDefaultMessageSigning.DIGEST_SHA256, true) // digest required: a body that
                                            // cannot be digested fails the request with
                                            // RequiredBodyDigestException, an IOException
                                            // (a configuration error)

// a factory is shared by every request it applies to, so use a separate
// instance for a host that needs different settings
val paymentsFactory = ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
    .setExpiresLifetime(5)

ApproovService.enableMessageSigning(factory)
ApproovService.putMessageSigningHostFactory("payments.example.com", paymentsFactory)
```

The host match ignores case and the port. `putMessageSigningHostFactory(host, null)` removes a host factory.

To decide the signature of each request, subclass `SignatureParametersFactory` and override the public `buildSignatureParameters(provider, changes)`. Return the `SignatureParameters` for the request, or `null` to send it unsigned. The package calls the override for many requests at the same time, so make it thread-safe and do not block in it. An exception from it sends the request unsigned and logs an error. The factory that you installed decides which signatures the package makes, with its own `getAlgs()`.

## Header names

You can change the name of each header that the package adds. `null` turns the trace or status header off:

```kotlin
ApproovService.setTokenHeader("Authorization", "Bearer ")   // default "Approov-Token", no prefix
ApproovService.setTraceIDHeader(null)                       // default "Approov-TraceID"; null disables
ApproovService.setStatusHeader("X-Approov-Fetch-Status")    // default "Approov-Status"; null disables
```

A `null` prefix means no prefix. When the status header is off, a request that the package could not protect carries no explanation. A prefix with a character other than tab or printable ASCII fails the request with `ApproovException("Approov header <name>: invalid character in value")`.

## Bypass initialization

Initialize with an empty string instead of the account ID to start the package in bypass mode:

```kotlin
ApproovService.initialize(applicationContext, "")
```

In bypass mode the package is enabled but protects nothing: `isApproovServiceEnabled()` is `true` and `isApproovProtectionEnabled()` is `false`. Requests go out with no token, signature, secure string or Approov connection validation, only OS trust. The methods that call the SDK throw `ApproovException` (`<method>: Approov protection not enabled`).

Use it, for example, while your app fetches the account ID from a remote source, or as the guard in the README example. A later `initialize` with the account ID turns Approov on at runtime, if the SDK accepts it. Networking objects that you already have protect their requests from then on. After protection is on, the package ignores an empty string.

A request before any `initialize` is handled the same way as in bypass mode. The package does not fail or hold it.

## Initialize, then configure

Call `initialize` once, in `Application.onCreate`, and handle any exception that it throws. It is synchronous. Then make every configuration call that your app needs, such as headers, substitutions, exclusions, the service mutator, message signing and the logging level.

A setting applies only to requests that the package processes after the call. Before that, requests use the defaults: the `ALWAYS_PROCEED` mutator, the `Approov-Token` header, no binding header, exclusions or substitutions, signing on with both signatures, and logging at `INFO`. Connection validation does not depend on this order, because its keys come from the SDK configuration.

`initialize` never resets configuration. Native code and a React Native module in one process share one state, and the last write wins, so configure in one place. The package sends every account ID that is not empty to the SDK, with the comment exactly as given:

* A first initialization succeeds.
* For the same account ID and comment again, `initialize` returns immediately.
* The SDK rejects a different account ID or comment, or a damaged account ID. Its `IllegalStateException` or `IllegalArgumentException` reaches your code, and the package state does not change.

The comment of `initialize(context, accountID, comment)` goes to the SDK unchanged, for example for `options:...` [startup flags](https://docs.approov.io/direct-sdk-integration/initialization-options/#sdk-initialization-options). The two-argument form passes `null`, which the SDK treats as different from `""`.

A one-line native bootstrap in a React Native app, or the native initialization of the Expo config plugin, can initialize for you. Such a call can be the file form, `initialize(context)`, which applies the options in your bundled `approov.config` before the first request (see [REFERENCE.md](REFERENCE.md#initialize-from-approovconfig)). Otherwise it configures nothing, and your app makes its own configuration calls. The Gradle plugin does not initialize or configure.

### Persistent configuration

An over-the-air update, such as an Expo EAS Update, replaces only JavaScript, which runs after native startup. `setPersistentConfiguration(context, json, source)` stores settings in the form of `approov.config`, without `config`, `comment` and `devKey`. From the next start, the file-form `initialize` applies them over your bundled file, before the first request. Nothing changes in the running app. `clearPersistentConfiguration(context)` removes them.

The package discards the stored settings if the bundled `approov.config` or the app version changed. So make the code built into every release call `setPersistentConfiguration` or `clearPersistentConfiguration` at startup. A person who can change your JavaScript can also change these settings, the service mutator and message signing included, so sign your updates. See [REFERENCE.md](REFERENCE.md#setpersistentconfiguration).

## Token binding

[Token binding](https://docs.approov.io/backend-integration/token-binding/) ties the Approov token to the value of a header on the request, usually an OAuth `Authorization` header. Then nobody can replay the token with different credentials. A hash of the value goes in the `pay` claim of the token, which your backend checks.

```kotlin
ApproovService.setBindingHeader("Authorization")
```

Use a header whose value changes rarely, because a new value makes the next token fetch return a new token. Each request's token is bound to that request's own header value. The binding header overrides `setDataHashInToken` on every request that carries it. A header cannot be both a binding header and a substitution header.

## Secure strings

[Secure strings](https://docs.approov.io/backend-integration/secure-strings/) let you remove API keys and other secrets from your app. Approov supplies them only to attested installations. Put a secure string key, the placeholder, where the secret goes, and register the headers and query parameters that carry keys:

```kotlin
ApproovService.addSubstitutionHeader("X-Api-Key", null)          // whole value is the key
ApproovService.addSubstitutionHeader("Authorization", "Bearer ") // value after the prefix is the key
ApproovService.addSubstitutionQueryParam("api_key")
```

The package substitutes a secret only for an `https` URL that the SDK reports as `SUCCESS` or `UNPROTECTED_URL`. Otherwise the placeholder goes out as the app wrote it. If the secure string fetch does not succeed, the package puts the status in place of the placeholder, for example `Authorization: Bearer REJECTED` or `?api_key=UNKNOWN_KEY`. So your backend sees why it received no secret.

* A key uses only `A-Z`, `a-z`, `0-9`, `.`, `_`, `~` and `-`, and is at most 64 bytes.
* Header names match without regard to case. The package substitutes every occurrence of a query parameter.
* A value that a header or URL cannot carry keeps its placeholder, and a warning names the header or parameter, never the value.

Keep a query placeholder in the request URL, for example `https://api.example.com/v1/shapes?api_key=shapes_api_key_placeholder`. On OkHttp, do not add it in a network interceptor. On Volley, return a header placeholder from your `Request`'s `getHeaders()`.

## Excluding URLs

The package sends requests whose URL matches an exclusion regular expression unchanged, without a token fetch:

```kotlin
ApproovService.addExclusionURLRegex("https://api\\.example\\.com/health.*")
```

The match uses `Matcher.find`, so use `^` and `$` to match the whole URL. Connection validation still applies to excluded requests. On OkHttp, Volley and gRPC, it uses the keys from the last request that fetched a token. An invalid regular expression throws `IllegalArgumentException` and adds nothing.

## Connection validation

The package validates the TLS connection to each domain that you added to Approov. It uses the certificate public keys configured for the domain, or the [Managed Trust Roots](https://docs.approov.io/security-configuration/dynamic-pinning/#managed-trust-roots) for a domain with no keys of its own. API names such as `ApproovPinningVerifier`, `getPins` and `handlePinningShouldProcessRequest` refer to this validation.

* Each validation reads the current keys for the exact host. The lookup ignores case and one trailing dot.
* The package matches keys only against the chain that vouches for the server certificate.
* A connection that fails validation fails with `javax.net.ssl.SSLPeerUnverifiedException`.
* Until the installation receives its first configuration, only OS trust validates the connection. The same applies to a domain with no keys while the Managed Trust Roots are empty. The package logs a warning one time for each such host.

OkHttp validates in the network interceptor of the package, after the token fetch of each attempt. `HttpsURLConnection`, Volley and gRPC use `ApproovPinningVerifier`, a `HostnameVerifier` that runs the platform verifier first. Cronet builds one engine for each key set.

`handlePinningShouldProcessRequest` in a [service mutator](#service-mutators) skips validation for chosen requests on OkHttp, `HttpsURLConnection` and Volley. gRPC channels and Cronet engines do not ask it. They validate every connection to a domain with keys.

## OkHttp version

The package brings OkHttp 5.3.2 as a runtime dependency, through the OkHttp BOM. The Approov SDK makes its own calls with OkHttp, so an app whose networking is only `HttpsURLConnection`, Volley or Cronet still gets OkHttp from the package. OkHttp 5 tries the IPv6 and IPv4 addresses of a host side by side. On a network where IPv6 does not answer, requests and token fetches do not wait for the connect timeout.

* The version is a minimum. Gradle uses the highest OkHttp version that your app asks for.
* The BOM moves every other OkHttp artifact in the app, such as `logging-interceptor` or `okhttp-sse`, to the same version as `okhttp`.
* Code that uses OkHttp types, `ApproovOkHttp` included, needs its own OkHttp declaration.
* An app module that compiles Kotlin needs Kotlin 2.1 or later, or the build stops with `The binary version of its metadata is 2.2.0, expected version is 2.0.0`.

With Android Gradle Plugin 8.7 or 8.9, an app with two or more OkHttp 5 companion artifacts can fail with `5 files found with path 'META-INF/versions/9/OSGI-INF/MANIFEST.MF'`. Exclude the file:

```groovy
android {
    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}
```

### Staying on OkHttp 4

An app that cannot move to OkHttp 5 now can stay on OkHttp 4. Enforce the OkHttp 4 BOM, which moves every OkHttp artifact back together:

```groovy
dependencies {
    implementation("io.approov:service.android:3.7.0")
    implementation(enforcedPlatform("com.squareup.okhttp3:okhttp-bom:4.12.0"))
}
```

Do not force a version on `okhttp` alone, for example with `strictly("4.12.0")`, and do not exclude the OkHttp group. A mix of OkHttp 4 and 5 artifacts fails in R8 with `Missing class okhttp3.internal._UtilCommonKt`, or crashes with `NoClassDefFoundError`. On OkHttp 4 the package works as on OkHttp 5, without the side-by-side connections.

React Native ships OkHttp 4. The React Native package declares this package with `exclude group: "com.squareup.okhttp3"`. Every declaration of this package in a React Native app must have that exclusion.

## Service mutators

An `ApproovServiceMutator` holds the decisions of the package, such as whether a failed token fetch fails the request. One mutator makes the decisions for every networking stack. It never switches message signing on or off. The package supplies three:

* `ApproovServiceMutator.ALWAYS_PROCEED` never fails a request because of its token fetch.
* `ApproovServiceMutator.DEFAULT` is `ALWAYS_PROCEED`. `setServiceMutator(null)` puts it back, and `initialize` keeps the installed mutator.
* `ApproovServiceMutator.CLOSE_FAILURE` keeps the 3.5.x token decisions. It fails a request to a protected domain in the app when the token fetch fails.

| Token fetch status, protected domain | `ALWAYS_PROCEED` (default) | `CLOSE_FAILURE` |
| :--- | :--- | :--- |
| `SUCCESS` | goes ahead with the token | same |
| `NO_APPROOV_SERVICE` | goes ahead with the status header only | same |
| `NO_NETWORK`, `POOR_NETWORK`, `MITM_DETECTED` | goes ahead with the status header only | fails with `ApproovNetworkException`. A retry may solve it. |
| any other failure (`REJECTED`, `INTERNAL_ERROR`, `BAD_URL`, ...) | goes ahead with the status header only | fails with `ApproovFetchStatusException` |

The message of both exceptions is `Approov token fetch for <host>: <status>`. A request to another domain goes out unchanged under both mutators. To install `CLOSE_FAILURE`, call `setServiceMutator`, or set `"mutator": "CLOSE_FAILURE"` in `approov.config`:

```kotlin
ApproovService.setServiceMutator(ApproovServiceMutator.CLOSE_FAILURE)
```

On OkHttp, the package runs only after OkHttp connects. With no network, or when the TLS handshake fails, OkHttp fails the call first, with `UnknownHostException` or `SSLHandshakeException`. So the app gets that `IOException`, not `ApproovNetworkException`, under both mutators. `HttpsURLConnection`, Volley and Cronet decide before they connect.

### Writing your own mutator

Write your own mutator when your app needs another decision, for example to fail only `NO_NETWORK` requests. Every method has a default, so override only what you need. The interface defaults are the `CLOSE_FAILURE` decisions, so a mutator that should otherwise proceed delegates to `ALWAYS_PROCEED`.

To abort a request, throw a standard network exception, such as `java.net.ConnectException`, not an Approov type. Every adapter passes it on unchanged, so it reaches the error handling of your app as other network failures do. Any other exception from a hook fails the request with an `ApproovException` that names the hook, with the original as its cause. The `CLOSE_FAILURE` aborts that a mutator inherits or calls keep their Approov exception types.

```kotlin
import android.util.Log
import com.criticalblue.approovsdk.Approov
import io.approov.service.android.ApproovRequestContext
import io.approov.service.android.ApproovRequestMutations
import io.approov.service.android.ApproovService
import io.approov.service.android.ApproovServiceMutator

class AppPolicy : ApproovServiceMutator {
    private val base = ApproovServiceMutator.ALWAYS_PROCEED

    // with no network the request will not reach the backend, so fail it now as an
    // ordinary network error instead of sending it; every other outcome proceeds
    override fun handleInterceptorFetchTokenResult(result: Approov.TokenFetchResult, url: String): Boolean {
        if (result.status == Approov.TokenFetchStatus.NO_NETWORK) {
            Log.w("AppPolicy", "no network for Approov attestation, not sending $url")
            throw java.net.ConnectException("no network")
        }
        return base.handleInterceptorFetchTokenResult(result, url)
    }

    override fun handleInterceptorHeaderSubstitutionResult(result: Approov.TokenFetchResult, header: String): Boolean =
        base.handleInterceptorHeaderSubstitutionResult(result, header)

    override fun handleInterceptorQueryParamSubstitutionResult(result: Approov.TokenFetchResult, queryKey: String): Boolean =
        base.handleInterceptorQueryParamSubstitutionResult(result, queryKey)

    // add app metadata after Approov processing; message signing, if enabled, runs after this and covers it
    override fun handleInterceptorProcessedRequest(context: ApproovRequestContext, changes: ApproovRequestMutations) {
        context.setHeader("X-Client-Platform", "android")
    }

    // the callback above only replaces a header, so it is safe to run again on a refresh
    override fun supportsProtectionRefresh(): Boolean = true

    // skip Approov connection validation for a telemetry host
    override fun handlePinningShouldProcessRequest(url: String): Boolean =
        !url.startsWith("https://metrics.example.com/")
}

ApproovService.setServiceMutator(AppPolicy())
```

Keep hooks fast and without side effects, because they run on the request path.

| Hook | Decides |
| :--- | :--- |
| `handleInterceptorShouldProcessRequest` | whether a request gets Approov processing at all. By default, every request that is not [excluded](#excluding-urls) gets it. |
| `handleInterceptorFetchTokenResult` | `true` sends the request with the Approov headers that the status allows, `false` sends it without them, and an exception aborts it |
| `handleInterceptorHeaderSubstitutionResult`, `handleInterceptorQueryParamSubstitutionResult` | whether to substitute a secure string on `SUCCESS` |
| `handleInterceptorProcessedRequest` | final changes to the processed request. Message signing covers them. |
| `supportsProtectionRefresh` | whether the processed request callback can run again on a stale or rebuilt request. The default is `false`. The standard mutators return `true`. |
| `handlePinningShouldProcessRequest` | whether Approov connection validation applies |
| `handleFetchTokenResult`, `handleFetchSecureStringResult`, `handleFetchCustomJWTResult` | how the direct methods turn a result into an exception |

## Networking stacks

Every stack goes through the same pipeline. The steps are the mutator's processing decision, token binding, the token fetch, secure string substitution, the processed request callback and message signing. The stacks differ in how they let the package in, and how a failure reaches your code. The package does not cover the Ktor CIO engine, Flutter networking or WebSockets.

### OkHttp, Retrofit and Ktor on OkHttp

`ApproovOkHttp.getOkHttpClient()` returns a cached client, built from the builder that you set with `setOkHttpClientBuilder`. `ApproovOkHttp.protect(builder)` adds the Approov interceptors to a builder that you manage. A failed request reaches `execute()` as an `IOException`, or `Callback.onFailure`.

#### Interceptor order

`protect` adds `ApproovInterceptor` as the last network interceptor, and a small application interceptor as the last application interceptor. So all your interceptors run before the package. They never see the Approov headers or the substituted secrets, and the signature covers their changes. Add your interceptors before you call `protect`.

The package protects each network attempt just before OkHttp writes it. So each retry, redirect and authenticator follow-up gets a token and signatures for its own URL. The processed request callback runs on every attempt, so make sure that it is safe to repeat.

#### Derived clients

`client.newBuilder()` copies the Approov interceptors. A network interceptor that you add to the derived builder runs after `ApproovInterceptor`. It sees the token, the signature and the substituted secrets, and the signature does not cover its changes. To put the Approov interceptors last again, call `protect` on the derived builder. React Native networking derives its clients this way, so a development tool that records requests also records those values.

Your OkHttp `Cache` never stores a response to a request whose query carried a substituted secure string.

### HttpsURLConnection

For code that calls `URL.openConnection()`, the [Gradle plugin](#the-gradle-plugin) wraps the connection. For code that the plugin does not weave, continue with the connection that `ApproovUrlConnection.addApproovToConnection(connection)` returns. The wrapper buffers a request body for the `Content-Digest`, but not a streaming upload. It applies the protection when the request is sent, so it covers the headers and body that you set after the call. It follows redirects itself. A failed request fails when it is sent, from `getResponseCode` or another method that declares `IOException`.

The deprecated `addApproov(connection)` protects a connection in place, without a body digest. It fails when a query substitution applies. Its connection follows no redirect, so your app receives the 3xx response.

### Volley

`Volley.newRequestQueue(context, ApproovVolley.getBaseHttpStack())` sends every request through the package. The stack reads `getHeaders()` and `getBody()` one time and sends exactly those values, so the signature covers what is sent. It follows redirects itself. A failed request reaches your error listener as a `VolleyError`.

### gRPC

`ApproovChannelBuilder.forAddress(host, port)` or `forTarget(target)` builds an OkHttp-transport channel whose connections the package validates. `ApproovClientInterceptor` runs the metadata of each call through the pipeline immediately before `ClientCall.start`. A call that the package aborts, or that it protected on a transport without TLS, fails with `Status.UNAVAILABLE` before anything is written. The package fetches the token for `https://` and the authority of the channel, so an exclusion cannot match a method name.

### Cronet

`ApproovCronet.newBuilder(context)` and `ApproovCronet.newBuilder(provider)` return an `ApproovCronetEngineBuilder` in place of the Cronet builder. Cronet is a `compileOnly` dependency, so the app brings its own Cronet. Connection validation needs a native provider:

* `org.chromium.net:cronet-embedded`, native Cronet bundled in the APK, which works without Google Play services. react-native-nitro-fetch already depends on `cronet-embedded` 143.7445.0.
* Google Play services Cronet (`com.google.android.gms:play-services-cronet`)
* the Android 14+ `HttpEngine` provider

Cronet's Java fallback (`org.chromium.net:cronet-fallback`) accepts keys but never validates against them. On it, requests to hosts that have keys fail, and other hosts use OS trust. The Gradle plugin warns when `cronet-fallback` is on the runtime classpath.

Cronet takes keys only when it builds an engine. So the adapter keeps one real engine for each key set, and builds a new one with your recorded settings when the keys change. A failure reaches `UrlRequest.Callback.onFailed` as a `NetworkException`, with the error codes that real Cronet reports. [REFERENCE.md](REFERENCE.md#approovcronetengine) has the table.

`CronetEngine.openConnection(URL)` and `createURLStreamHandlerFactory` use the validated engine but carry no Approov headers. The package does not support bidirectional streams, and never calls a request finished listener.

#### react-native-nitro-fetch

With the Gradle plugin applied, the package protects the nitro-fetch engines with no patch. Initialize natively in `Application.onCreate`, then configure. `initialize` logs an error if nitro-fetch is in the app without the weave of the plugin.

nitro-fetch prefetch and token refresh requests can go out before Approov is set up, with no token or connection validation. Do not use `prefetchOnAppStart`, `registerPrefetch` or `registerTokenRefresh` with protected endpoints.

### Redirects and retries

The package protects each redirect again for its new location. A redirect to a domain that is not in Approov carries no Approov headers and no substituted secret. A redirect to another protected domain gets a token fetched for its URL, and new signatures. A redirect to another origin also drops `Authorization`. The package keeps the target URL exactly as the server sent it.

`HttpsURLConnection` (through `addApproovToConnection` or the plugin), Volley and Cronet follow redirects themselves, to a maximum of 20 follow-ups. `HttpsURLConnection` and Volley never follow a redirect from `https` to `http`, and obey `setInstanceFollowRedirects` and `HttpURLConnection.setFollowRedirects`.

On the stacks other than OkHttp, a request to a domain that is not in Approov can redirect into a protected domain. It then arrives there without protection. Add every domain that the app calls to Approov, including alias domains that redirect.

### Stale protection refresh

A request can wait between Approov processing and sending, for example during a device doze period. If it waited longer than the refresh period, the package removes its protection and applies it again. The default period is 3000 ms:

```kotlin
ApproovService.setStaleProtectionRefreshPeriod(1000)   // <= 0 disables
```

The refresh runs on `HttpsURLConnection` through `addApproovToConnection` or the plugin, on Cronet, and on each Volley redirect hop. It runs only for a mutator whose `supportsProtectionRefresh()` returns `true`. OkHttp does not need it, because the package protects every attempt when OkHttp writes it. The in-place `addApproov` and gRPC get no refresh.

## The Gradle plugin

`io.approov.gradle` rewrites networking call sites at build time, so that the objects that your code builds are protected without source changes. This rewrite is called weaving. The plugin does not initialize or configure the package. It needs Android Gradle Plugin 8.6 or later, and JDK 17 or later to run the build.

| Stack | Woven call site |
| :--- | :--- |
| OkHttp (Retrofit and Ktor when given that client) | `OkHttpClient.Builder.build()` |
| `HttpsURLConnection` | `URL.openConnection()`, `URL.openConnection(proxy)` |
| Volley | `Volley.newRequestQueue(context)` (single argument) |
| Cronet | `new CronetEngine.Builder(context)`, `provider.createBuilder()` |

The plugin weaves OkHttp, `HttpsURLConnection` and Volley in your app module's own classes only. It weaves Cronet also in the dependency packages that you list, react-native-nitro-fetch by default:

```groovy
approov {
    cronetDependencyPackages = ['com.margelo.nitro.nitrofetch']   // the default; [] weaves the app's classes only
}
```

The weave does not protect `new OkHttpClient()`, `URL.openStream()`, Retrofit without `client(...)`, gRPC, or clients built in libraries. Nothing detects an unwoven client at runtime. So read the weave report, `build/outputs/approov/weave-<variant>.txt`, which warns about each site that the plugin could not protect.

The build fails if the runtime classpath does not supply `io.approov:service.android` at the version of the plugin. In a React Native application module, the plugin also weaves React Native's `OkHttpClientProvider`, which protects `fetch` and `XMLHttpRequest`. The [plugin README](approov-gradle-plugin/README.md) has the details.

## R8 and packaging

The AAR ships its consumer R8 rules, so a minified app needs no Approov rules of its own. Volley, gRPC and Cronet are `compileOnly`. The BOM alignment of your OkHttp artifacts depends on the Gradle module metadata, which Maven Central supplies next to the POM. The package bundles Google Tink under `io.approov.util.universal`, so it cannot conflict with the Tink of your app.

## Diagnostics

The package writes a diagnostic line for each token fetch, and for each secure string that it does not deliver. These lines are at `DEBUG`, and the default level is `INFO`. To write them, set the level in a debug build:

```kotlin
ApproovService.setLoggingLevel(ApproovLogLevel.DEBUG)
```

Or, with no rebuild, enable debug logging for the `ApproovService` tag. This works on a release build too, and raises any level except `OFF` to `DEBUG`:

```sh
adb shell setprop log.tag.ApproovService DEBUG
```

The package never logs a token. For each fetch, it logs the [loggable form](https://docs.approov.io/core-concepts/approov-tokens/architecture-and-security/#loggable-tokens) of the result, with the request URL without its query. Its `arc` claim, the [Attestation Response Code](https://docs.approov.io/app-integration/frontend/attestation/#attestation-response-code), tells why attestation gave that result:

```
D/ApproovService: Token for https://api.example.com/v1/items: {"did":"...","exp":1757400000,"arc":"IXPSB7TRK26LXE3M","sip":"a1b2c3", ...}
```

To [decode an `arc`](https://docs.approov.io/app-integration/frontend/attestation/#decoding-an-attestation-response-code), run `approov token -showArcInfoCurl` one time. It prints a `curl` command with an `<arc>` placeholder. Run it with the `arc` value from the log, and the response lists the flags that caused the result, for example `app-not-registered`. Your account's [metrics](https://docs.approov.io/operations/metrics-graphs/) show the same results in total. A [development signing certificate](https://docs.approov.io/operations/app-signing-certificates/development-and-testing/#development-app-signing-certificates) lets debug builds and emulators pass attestation.

The `DEBUG` lines also report a request that goes ahead after a failed token fetch, and a secure string that the package does not deliver:

```
D/ApproovService: Secure string not substituted in header Api-Key: secure string fetch status REJECTED, ARC IXPSB7TRK26LXE3M, rejection reasons root, status sent in its place
D/ApproovService: Approov token fetch for api.example.com: NO_APPROOV_SERVICE, proceeding with the status header only
```

`ApproovService.getLastARC()` returns the ARC of the most recent fetch, without a new fetch. No line at any level carries a secure string value, a message signature or the account ID. The tags are `ApproovService`, `ApproovMsgSign`, `ApproovPinning`, `ApproovPinVerifier`, `ApproovOkHttp`, `ApproovUrlConnection`, `ApproovHurlStack`, `ApproovGrpc` and `ApproovCronet`.
