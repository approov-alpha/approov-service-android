# Approov Gradle Plugin (`io.approov.gradle`)

The plugin protects your app's OkHttp clients, `HttpsURLConnection`, Volley queues and Cronet engines with Approov at build time, including the Cronet engines of react-native-nitro-fetch. With no source changes, a plain `OkHttpClient` or `CronetEngine` sends an Approov token, message signatures and connection validation.

The plugin is an optional front end for [`io.approov:service.android`](../README.md). It only weaves: it does not initialize or configure `ApproovService`. Your app still initializes `ApproovService` once, then configures it.

## Requirements

- Android Gradle Plugin 8.6 or later (React Native 0.76 and later), AGP 9 included. The plugin does not support AGP 7.x.
- JDK 17 or later to run the build.
- The application module depends on `io.approov:service.android`, at the same version as the plugin.

## Applying the plugin

Approov publishes the plugin to Maven Central, not to the Gradle Plugin Portal, as `io.approov:service.android-gradle-plugin`. So `mavenCentral()` in `pluginManagement` is sufficient:

```gradle
// settings.gradle
pluginManagement { repositories { mavenCentral(); google() } }

// app build.gradle: plugins {} form
plugins {
    id 'com.android.application'
    id 'io.approov.gradle' version '3.7.0'
}
dependencies { implementation 'io.approov:service.android:3.7.0' }
```

The `buildscript` form, for example in a project that React Native or Expo generated:

```gradle
// android/build.gradle
buildscript {
    repositories { mavenCentral(); google() }
    dependencies { classpath 'io.approov:service.android-gradle-plugin:3.7.0' }
}

// android/app/build.gradle
apply plugin: 'com.android.application'
apply plugin: 'io.approov.gradle'
```

Then initialize in `Application.onCreate()`, as the [package README](../README.md#initializing) shows, and build your clients as usual:

```java
// your Approov account ID, from your onboarding email or "approov sdk -getConfigString"
ApproovService.initialize(context, "<your-approov-account-id>");
// then configure, straight after initialize and before protected requests

// no Approov networking code: the plugin wires this up
OkHttpClient client = new OkHttpClient.Builder().build();   // automatically protected
```

A woven client sends requests without Approov processing before initialization. After initialization, the same client is protected. A request that the package processes before your configuration calls uses the defaults, so configure immediately after `initialize`. See [Initialize, then configure](../ADVANCED.md#initialize-then-configure).

## What it weaves

At compile time, the plugin redirects supported networking call sites, and method references to them, to an Approov runtime helper:

| Stack | Woven call site | Redirected to |
|---|---|---|
| OkHttp (Retrofit and Ktor when given that client) | `OkHttpClient.Builder.build()`, `OkHttpClient.Builder::build` | `ApproovOkHttpWeaver.build(builder)` |
| `HttpsURLConnection` | `URL.openConnection()`, `URL.openConnection(proxy)` | `ApproovUrlConnectionWeaver.openConnection(...)`, protection applied when the request is sent |
| Volley | `Volley.newRequestQueue(context)` (single argument) | `ApproovVolleyWeaver.newRequestQueue(context)` |
| Cronet | `new CronetEngine.Builder(context)`, `provider.createBuilder()` | `new ApproovCronetEngineBuilder(context, site)`, `ApproovCronetWeaver.createBuilder(provider, site)` |
| React Native networking | React Native's `OkHttpClientProvider` | `ApproovReactNativeWeaver.protectBuilder(builder)`, `ApproovReactNativeWeaver.protectClient(client)` |

The plugin weaves OkHttp, `HttpsURLConnection` and Volley in `PROJECT` scope, which is the classes of the application module. It never weaves the Approov SDK, the package itself, OkHttp, Okio or Kotlin internals. Woven code is never rewritten two times, and an explicit adapter call on a woven object does not protect it again.

These are not woven, so they are not protected unless your code uses the adapter APIs of the package:

- `new OkHttpClient()`, `OkHttpClient::new` and other constructor references
- `URL.openStream()`
- `Volley.newRequestQueue(context, stack)`, unless the stack is `ApproovVolley.getBaseHttpStack()`
- Retrofit without `client(...)`, and Ktor's OkHttp engine without `preconfigured`
- serializable method references
- `android.net.http.HttpEngine` and `ExperimentalCronetEngine.Builder` (use `ApproovCronet.newBuilder`)
- gRPC (use `ApproovChannelBuilder` and `ApproovClientInterceptor`)
- clients created by reflection
- clients built in libraries (Cronet in the configured packages excepted)
- clients built in library modules that do not apply the plugin, in dynamic feature modules or in pure JVM modules

Nothing detects an unwoven client at runtime. Read the weave report instead.

## Weave reports

`reportApproovWeave<Variant>` runs with `assemble<Variant>`. It writes `build/outputs/approov/weave-<variant>.txt` and logs `N call site(s) woven (OkHttp a, HttpsURLConnection b, Volley c, Cronet d), M not woven`. It warns:

- for each site that it cannot protect, from the list above
- when the plugin wove nothing in the module, unless the React Native weave applies
- when `org.chromium.net:cronet-fallback` is on the runtime classpath (see [Cronet](#cronet))

This report covers your module's own classes only. Two other reports are in the same directory:

| File | Lists | Written by |
|---|---|---|
| `react-native-weave-<variant>.txt` | the sites woven in React Native's `OkHttpClientProvider` | `checkApproovReactNativeWeave<Variant>`, before every build |
| `cronet-weave-<variant>.txt` | the sites woven in `cronetDependencyPackages`, such as react-native-nitro-fetch | `checkApproovCronetWeave<Variant>`, before every build |

So a React Native app whose own classes build no client sees `woven call sites: 0` in `weave-<variant>.txt`. Look in the other two files to make sure that React Native's networking and the library are covered.

## Plugin and library versions

Approov releases the plugin and `io.approov:service.android` together, at the same version. For every variant, `checkApproovServiceVersion<Variant>` fails the build when:

- nothing on the runtime classpath provides the library, so the woven call sites would fail at startup with `NoClassDefFoundError`. The old coordinates (`io.approov:approov-service*`, `com.github.approov:approov-service*`) count as missing.
- the library resolves a version that is different from the plugin's own

A project dependency on the library, or a local `.aar` or `.jar` that contains the woven entry points, also provides it. For local development only, `-Papproov.allowVersionMismatch=true` turns either failure into a warning. Do not use it in CI or for a release.

## Cronet

In an application module, a second transform rewrites Cronet builder creation in the dependency packages that you list. AGP then processes the dependency jars, which adds some build time:

```gradle
approov {
    // dotted packages of dependencies whose Cronet builder creation is woven; the default is
    // react-native-nitro-fetch. [] weaves the app's own classes only.
    cronetDependencyPackages = ['com.margelo.nitro.nitrofetch']
}
```

- In your own classes, a Cronet shape that the weave cannot protect is a build warning that names the class and method. An example is a `CronetEngine.Builder` subclass.
- In a configured package, the same shape fails the build. `checkApproovCronetWeave<Variant>` also fails the build if the plugin would rewrite nothing in a configured package that is in the app. This can occur after a library release changes how it creates its engine.
- `cronetDependencyPackages` rejects a single-segment package (`io`, `com`) and any package that contains or is inside `io.approov.service` or `org.chromium.net`.
- The plugin adds the string resource `approov_cronet_weave`, which lists the woven packages. At `initialize`, the package logs an error if react-native-nitro-fetch is in the app and the marker does not list its package.

Cronet validates connections only on a native provider: `org.chromium.net:cronet-embedded`, Google Play services Cronet, or the Android 14+ `HttpEngine` provider. Cronet's Java fallback (`cronet-fallback`) accepts certificate public keys but never uses them to validate the connection. On it, requests to hosts that have keys fail, and other hosts use OS trust. Use `cronet-embedded` for apps that must work on devices without Play services.

## React Native

In an application module that applies React Native's Gradle plugin (`com.facebook.react`), the plugin also weaves one React Native class, `com.facebook.react.modules.network.OkHttpClientProvider`. In any other module, this part never runs.

- **Covered:** every client that React Native takes from the provider. This includes `fetch`, `XMLHttpRequest`, Fresco and Expo `fetch`, and Expo modules that use the provider, such as expo-file-system's `DownloadTask` and `UploadTask`. A client that your app's `OkHttpClientFactory` returns is protected too.
- **Order:** React Native networking derives a client for each request with `newBuilder()`, and can add network interceptors after Approov. These run after the request is signed (see [Derived clients](../ADVANCED.md#derived-clients)).
- **Not covered:** WebSockets, and a module that builds its own `OkHttpClient`, such as expo-file-system's `File.downloadFileAsync`.
- **Check task:** `checkApproovReactNativeWeave<Variant>` fails the build when `OkHttpClientProvider` is missing or no longer has the shape that the weave protects. So a React Native release that changes it cannot ship unprotected.

Initialize `ApproovService` natively in `Application.onCreate`, then configure, natively or from JavaScript. You do not need a patch or a `postinstall` step. For Expo, the Approov Package for React Native ships a config plugin that adds this Gradle plugin to the generated Android project.

## Inspecting the weave

Read the reports above, or disassemble an instrumented class and look for the redirect:

```sh
javap -c -p \
  app/build/intermediates/classes/debug/transformDebugClassesWithAsm/dirs/<your/Class>.class \
  | grep -E "OkHttpClient\$Builder.build|ApproovOkHttpWeaver.build"
# expect: invokestatic … ApproovOkHttpWeaver.build:(Lokhttp3/OkHttpClient$Builder;)Lokhttp3/OkHttpClient;
```
