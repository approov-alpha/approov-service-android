# Approov Package for Android

![Java](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)
![Android](https://img.shields.io/badge/Android-minSdk%2023-3DDC84?logo=android&logoColor=white)
![Maven Central](https://img.shields.io/maven-central/v/io.approov/service.android?logo=apachemaven&logoColor=white&label=Maven%20Central)
![Approov SDK](https://img.shields.io/badge/Approov%20SDK-3.7.0-0a7bbb)
![Message Signing](https://img.shields.io/badge/Message%20Signing-RFC%209421-1f6feb)

This package is the client API of [Approov](https://www.approov.io) for Android apps. It works with OkHttp (and Retrofit or Ktor on OkHttp), `HttpsURLConnection`, Volley, gRPC and Cronet, including react-native-nitro-fetch.

After you initialize it with your account ID, each request that your app sends to a protected domain carries an Approov token and message signatures. The token is a signed JWT. It shows that the request came from a genuine, unmodified copy of your app on a device that passed Approov's checks. Your backend checks it to decide whether to accept the request.

The package also validates the TLS connection to each protected domain. It uses the certificate public keys configured for the domain, or else the [Managed Trust Roots](https://docs.approov.io/security-configuration/dynamic-pinning/#managed-trust-roots) that Approov maintains for your account.

## BEFORE YOU START

You need:

* an Approov account, trial or paid, and its [account ID](#your-account-id)
* your API domains [added to Approov](https://docs.approov.io/getting-started/add-api-domains/). These are your protected domains.

Then:

1. [Add the dependency](#adding-the-dependency).
2. [Initialize](#initializing) `ApproovService` with your account ID.
3. [Make your requests](#making-requests) through the package.
4. [Look in the debug log](ADVANCED.md#diagnostics) to see that each request carries a token.
5. [Register your app and check tokens on your backend](#next-steps). Until your backend checks the token, your API is not protected.

Optional features are in [ADVANCED.md](ADVANCED.md). Every public class and method is in [REFERENCE.md](REFERENCE.md).

## ADDING THE DEPENDENCY

Make sure that `mavenCentral()` is in your repositories. Then add the package to the dependencies of your app module:

```groovy
dependencies {
    implementation("io.approov:service.android:3.7.0")
}
```

It replaces the per-stack packages `io.approov:service.okhttp`, `io.approov:service.httpsurlconn`, `io.approov:service.volley` and `io.approov:service.grpc`.

The package supports Android 6.0 (API level 23) and later. Add these permissions to your app manifest:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
```

The Approov SDK's manifest sets `android:label="@string/app_name"`. If your app's label is a literal or another resource, the manifest merge fails with `Attribute application@label value=(...) ... is also present at [io.approov:approov-android-sdk:3.7.0]`. To keep your label:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application
        android:label="My App"
        tools:replace="android:label">
```

The package ships its own R8 rules, so a minified app needs no Approov rules of its own.

### OkHttp

The Approov SDK makes its own calls with OkHttp, so the package brings OkHttp 5.3.2 at run time, through the OkHttp BOM. The BOM moves every OkHttp artifact in your app to the same version, and an app that asks for a later OkHttp keeps it. If your code uses OkHttp types, `ApproovOkHttp` included, declare OkHttp in your app. If your app compiles Kotlin, it needs Kotlin 2.1 or later. To stay on OkHttp 4, see [OkHttp version](ADVANCED.md#okhttp-version).

Volley, gRPC and Cronet come from your app, at the versions it declares.

### The Gradle plugin

The optional Gradle plugin `io.approov.gradle` protects the clients that your code builds, with no source changes, and the Cronet engines of react-native-nitro-fetch.

The plugin is on Maven Central, not on the Gradle Plugin Portal. Add `mavenCentral()` to `pluginManagement`, and apply the plugin after `com.android.application`:

```groovy
// settings.gradle
pluginManagement {
    repositories { mavenCentral(); google(); gradlePluginPortal() }
}

// app/build.gradle
plugins {
    id 'com.android.application'
    id 'io.approov.gradle' version '3.7.0'
}
dependencies {
    implementation 'io.approov:service.android:3.7.0'
}
```

The plugin and the library must have the same version, or the build fails. The plugin needs Android Gradle Plugin 8.6 or later (React Native 0.76 and later). The [plugin README](approov-gradle-plugin/README.md) shows the `buildscript` form.

## INITIALIZING

Initialize `ApproovService` once, in the `onCreate` method of your `Application` class, before the first request. A request sent before `initialize` goes out without Approov protection.

```kotlin
import android.app.Application
import android.util.Log
import io.approov.service.android.ApproovException
import io.approov.service.android.ApproovService
import java.util.UUID

class YourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // your own correlation ID for this app session; not an Approov value
        val sessionId = UUID.randomUUID().toString()
        try {
            // your Approov account ID, from your onboarding email or "approov sdk -getConfigString"
            ApproovService.initialize(applicationContext, "<your-approov-account-id>")
        } catch (e: RuntimeException) {
            // the value did not arrive as a complete account ID: continue without Approov
            // protection rather than not at all; your backend remains the enforcement point
            Log.e("YourApp", "Approov account ID rejected (session $sessionId); starting in bypass mode", e)
            ApproovService.initialize(applicationContext, "")
        }
        // then configure, straight after initialize and before the app issues protected
        // requests; only the settings your app uses, for example:
        // ApproovService.setTokenHeader("Authorization", "Bearer ")
        // ApproovService.addSubstitutionHeader("Api-Key", null)
        if (ApproovService.isApproovServiceEnabled() && ApproovService.isApproovProtectionEnabled()) {
            try {
                Log.i("YourApp", "Approov active: deviceID=${ApproovService.getDeviceID()} session=$sessionId")
            } catch (e: ApproovException) {
                Log.w("YourApp", "Approov device ID not available (session $sessionId)", e)
            }
        } else {
            Log.w("YourApp", "Approov bypass mode (session $sessionId): requests are sent without Approov protection")
        }
    }
}
```

<details>
<summary>Java</summary>

```java
import android.app.Application;
import android.util.Log;
import io.approov.service.android.ApproovException;
import io.approov.service.android.ApproovService;
import java.util.UUID;

public class YourApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // your own correlation ID for this app session; not an Approov value
        String sessionId = UUID.randomUUID().toString();
        try {
            // your Approov account ID, from your onboarding email or "approov sdk -getConfigString"
            ApproovService.initialize(getApplicationContext(), "<your-approov-account-id>");
        } catch (RuntimeException e) {
            // the value did not arrive as a complete account ID: continue without Approov
            // protection rather than not at all; your backend remains the enforcement point
            Log.e("YourApp", "Approov account ID rejected (session " + sessionId + "); starting in bypass mode", e);
            ApproovService.initialize(getApplicationContext(), "");
        }
        // then configure, straight after initialize and before the app issues protected
        // requests; only the settings your app uses, for example:
        // ApproovService.setTokenHeader("Authorization", "Bearer ");
        // ApproovService.addSubstitutionHeader("Api-Key", null);
        if (ApproovService.isApproovServiceEnabled() && ApproovService.isApproovProtectionEnabled()) {
            try {
                Log.i("YourApp", "Approov active: deviceID=" + ApproovService.getDeviceID() + " session=" + sessionId);
            } catch (ApproovException e) {
                Log.w("YourApp", "Approov device ID not available (session " + sessionId + ")", e);
            }
        } else {
            Log.w("YourApp", "Approov bypass mode (session " + sessionId + "): requests are sent without Approov protection");
        }
    }
}
```
</details>

About this example:

* `initialize` throws when the SDK rejects the call:
  * `IllegalArgumentException` if the value is not a complete, unaltered account ID. The `catch` block then starts the package in [bypass mode](ADVANCED.md#bypass-initialization), which sends requests without Approov protection.
  * `IllegalStateException` if the SDK is already initialized with a different account ID or comment, for example by an earlier `initialize` call. When protection is already on, the package ignores the empty account ID in the `catch` block, so protection stays on. Otherwise the `catch` block starts bypass mode.
* Logging the device ID is optional. Approov knows an installation only by its device ID, so log it next to an identifier of your own.
* A setting applies only to requests that the package processes after the call. See [initialize, then configure](ADVANCED.md#initialize-then-configure).

### Your account ID

Your account ID is in your onboarding email. You can also get it at any time from the Approov CLI, which calls it the SDK config string:

```sh
approov sdk -getConfigString
```

It looks like `#your-account#p6nZ...=`. It is the same for every app in your account, and it is not a secret. The whole value must reach the app unchanged, including its punctuation.

### From a bundled approov.config

You can also put the account ID and optional settings in an `approov.config` file in your app's assets, and call `ApproovService.initialize(applicationContext)`. This call also throws `ApproovException` if the file is missing or not valid:

```kotlin
try {
    ApproovService.initialize(applicationContext)
} catch (e: Exception) {
    Log.e("YourApp", "Approov initialization failed; starting in bypass mode", e)
    ApproovService.initialize(applicationContext, "")
}
```

The file is a JSON object. `config` holds your account ID. Each other key is named after the configuration call that it makes:

```json
{
  "config": "<your-approov-account-id>",
  "tokenHeader": {"name": "Authorization", "prefix": "Bearer "},
  "exclusionURLRegexes": ["^https://health\\.example\\.com/"]
}
```

The package applies these settings before your app's first request. [REFERENCE.md](REFERENCE.md#initialize-from-approovconfig) lists every key. A build variant can have its own file, for example `src/debug/assets/approov.config`. Put a `devKey` only in a debug variant's file.

## MAKING REQUESTS

You can protect requests in two ways:

* Use the networking objects that the package gives you, shown below for each stack.
* Apply the [Gradle plugin](ADVANCED.md#the-gradle-plugin) and build them as you do now. It does not change a client built another way, such as `new OkHttpClient()` or a library's own client. The weave report, `build/outputs/approov/weave-<variant>.txt`, warns about each site that it could not protect.

### OkHttp, Retrofit and Ktor on OkHttp

```kotlin
val client = ApproovOkHttp.getOkHttpClient()

// with your own settings: set the builder before getting the client
ApproovOkHttp.setOkHttpClientBuilder(OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS))
val customClient = ApproovOkHttp.getOkHttpClient()

// or protect a builder whose clients you manage yourself
val ownClient = ApproovOkHttp.protect(OkHttpClient.Builder()).build()

val retrofit = Retrofit.Builder().baseUrl("https://api.example.com/").client(ApproovOkHttp.getOkHttpClient()).build()
```

### HttpsURLConnection

With the Gradle plugin, `URL.openConnection()` needs no change. Otherwise, continue with the connection that `addApproovToConnection` returns:

```kotlin
var connection = URL("https://api.example.com/v1/items").openConnection() as HttpsURLConnection
connection = ApproovUrlConnection.addApproovToConnection(connection)
connection.setRequestProperty("Accept", "application/json")
```

### Volley

```kotlin
val queue = Volley.newRequestQueue(context, ApproovVolley.getBaseHttpStack())
```

### gRPC

```kotlin
val channel = ApproovChannelBuilder.forAddress("grpc.example.com", 443).build()
val protectedChannel = ClientInterceptors.intercept(channel, ApproovClientInterceptor(channel))
```

### Cronet

Use this in place of `CronetEngine.Builder(context)` or `provider.createBuilder()`:

```kotlin
val engine = ApproovCronet.newBuilder(context).enableHttp2(true).enableQuic(true).build()
```

Cronet needs a native provider, such as `org.chromium.net:cronet-embedded`. See [Cronet](ADVANCED.md#cronet).

### react-native-nitro-fetch

Apply the Gradle plugin in `android/app/build.gradle`, and initialize natively in `Application.onCreate`. The plugin protects the Cronet engines that nitro-fetch builds.

<details>
<summary>Java</summary>

```java
OkHttpClient client = ApproovOkHttp.getOkHttpClient();
ApproovOkHttp.setOkHttpClientBuilder(new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS));
OkHttpClient custom = ApproovOkHttp.protect(new OkHttpClient.Builder()).build();

HttpsURLConnection connection = (HttpsURLConnection) new URL("https://api.example.com/v1/items").openConnection();
connection = ApproovUrlConnection.addApproovToConnection(connection);

RequestQueue queue = Volley.newRequestQueue(context, ApproovVolley.getBaseHttpStack());

ManagedChannel channel = ApproovChannelBuilder.forAddress("grpc.example.com", 443).build();
Channel protectedChannel = ClientInterceptors.intercept(channel, new ApproovClientInterceptor(channel));

CronetEngine engine = ApproovCronet.newBuilder(context).enableHttp2(true).enableQuic(true).build();
```
</details>

### What each request carries

Each request to a protected domain carries:

* the Approov token, in the `Approov-Token` header
* the `Approov-Status` header, with the result of the attestation
* your [secure strings](ADVANCED.md#secure-strings), over `https`
* the `install` and `account` message signatures, and a `Content-Digest` of the body when the package can read it

If the app cannot get a token, for example with no network, the request still goes out by default. It then carries only the `Approov-Status` header, and your backend decides. The package sends requests to all other domains unchanged. See [What each request carries](ADVANCED.md#what-each-request-carries).

## CUSTOMIZING THE BEHAVIOUR

[ADVANCED.md](ADVANCED.md) explains how to change the defaults:

* [Service mutators](ADVANCED.md#service-mutators). `ApproovServiceMutator.CLOSE_FAILURE`, or a mutator of your own, fails a request in the app when the token fetch fails.
* [Message signing](ADVANCED.md#message-signing). Choose the signatures and what they cover, or call `ApproovService.disableMessageSigning()`.
* [Header names](ADVANCED.md#header-names), [secure strings](ADVANCED.md#secure-strings), [token binding](ADVANCED.md#token-binding) and [excluded URLs](ADVANCED.md#excluding-urls)
* [Networking stacks](ADVANCED.md#networking-stacks) and [diagnostics](ADVANCED.md#diagnostics)

## NEXT STEPS

1. [Register your app's signing certificate](https://docs.approov.io/getting-started/add-app-signing-certificates/#add-an-android-signing-certificate).
2. [Check tokens and signatures on your backend](https://docs.approov.io/getting-started/integrate-backend/). This is the step that protects your API.
3. [Go live](https://docs.approov.io/getting-started/go-live/). Monitor first, then let your backend reject requests without a valid token.

To report a security issue, see [SECURITY.md](SECURITY.md).

## UPGRADING FROM 3.5.x

Read the [changelog](CHANGELOG.md) for the full list of changes, and test your app and backend together.

### What your backend receives

* Every protected request carries the new `Approov-Status` header, and the install and account signatures.
* By default, a failed token fetch sends the request with that header and no token header. Your backend must accept this case and decide.
* A failed secure string fetch puts its status in place of the placeholder, and the request goes ahead. 3.5.x threw an exception.
* A secrets-only API (`-noApproovToken`) gets its secure strings again.

### Code changes

* The coordinates change to `io.approov:service.android`, and the Java packages to `io.approov.service.android` and its `okhttp`, `httpsurlconn`, `volley`, `grpc` and `cronet` adapters. The message signing helpers are in `io.approov.util.universal.sig`.
* The entry points change, for example `ApproovOkHttp.getOkHttpClient()` in place of `ApproovService.getOkHttpClient()`.
* By default, a failed token fetch no longer fails the request. For the 3.5.x token decisions and exception types, install `ApproovServiceMutator.CLOSE_FAILURE`.
* Message signing is on by default, and is not a mutator. Remove the `ApproovMessageSigning` mutator. To send unsigned requests, call `ApproovService.disableMessageSigning()`.
* `isInitialized()` is replaced by `isApproovServiceEnabled()` and `isApproovProtectionEnabled()`.
* `prefetch`, `warmTokenCache`, `setUseApproovStatusIfNoToken`, `setProceedOnNetworkFail`, `ApproovVolley.newRequestQueue` and the secure string helpers are removed. Leave a placeholder in the URL or header, and the package substitutes it on every stack. [REFERENCE.md](REFERENCE.md#removed-in-370) lists the replacements.
* `setInstallAttrsInToken` is renamed `setInstallAttributes`. `getMessageSignature` is a deprecated alias of `getAccountMessageSignature`.
* `addExclusionURLRegex` with an invalid regular expression now throws `IllegalArgumentException`.
* `ApproovUrlConnection.addApproov(conn)` is deprecated, and its connection no longer follows redirects. Use `conn = ApproovUrlConnection.addApproovToConnection(conn)` or the Gradle plugin.
* `initialize` never resets configuration. If the SDK throws, its own exception reaches your code.

## LICENSE

This package is licensed under the [MIT License](LICENSE). It uses the Approov SDK, which is proprietary software of Approov Ltd and is not covered by the MIT License. See [NOTICE](NOTICE).
