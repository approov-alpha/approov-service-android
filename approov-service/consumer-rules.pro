# Approov service layer consumer rules: keep the adapter entry points that are
# referenced reflectively or only used when the corresponding stack is present.
# Volley and gRPC are compileOnly, so R8 must not report them missing in an app
# without them. OkHttp is a dependency of the library and gets no such rule: a
# missing OkHttp class is an error.
-keep class io.approov.service.android.** { *; }
-dontwarn com.android.volley.**
-dontwarn io.grpc.**

# Tink is bundled relocated under io.approov.util.universal and is otherwise left
# for R8 to shrink: no rule keeps its classes. This is Tink's own
# META-INF/proguard/protobuf.pro restated for the relocated package (the
# original names the package before relocation). It only keeps fields of
# protobuf messages that are already live, so it retains nothing on its own.
-keepclassmembers class * extends io.approov.util.universal.tink.shaded.protobuf.GeneratedMessageLite {
  <fields>;
}

# Cronet is compileOnly: an app without Cronet never loads the cronet adapter, so
# R8 must not report its Cronet references as missing classes
-dontwarn org.chromium.net.**
# The cronet adapter identifies the real Cronet engine by class name
# (PinnedCronetEngines.engineKind), so R8 must not rename these. -keepnames still
# lets R8 remove a class the app does not use.
# Bundled native Cronet (cronet-embedded): allowed without a warning.
-keepnames class org.chromium.net.impl.CronetUrlRequestContext
# Java fallback (cronet-fallback): refused, it never checks pins. Also matched by its version string.
-keepnames class org.chromium.net.impl.JavaCronetEngine
# Android HttpEngine wrapper (httpengine-native-provider): used, logged as an error, named in the log.
-keepnames class org.chromium.net.impl.AndroidHttpEngineWrapper

# The Approov SDK AAR ships no R8 rules, and its native library constructs Java
# objects it looks up by name (for example AttestationServicesResponse(String,
# byte[], long, int, int)). R8 removes members only native code uses, and
# initialize() then fails with NoSuchMethodError in a minified app. Keep the whole
# SDK until the SDK ships its own rules, as
# approov-service-react-native does. The SDK references Play Integrity and Play
# services tasks classes that apps without those libraries omit.
-keep class com.criticalblue.approovsdk.** { *; }
-dontwarn com.google.android.gms.tasks.OnFailureListener
-dontwarn com.google.android.gms.tasks.OnSuccessListener
-dontwarn com.google.android.gms.tasks.Task
-dontwarn com.google.android.play.core.integrity.IntegrityManager
-dontwarn com.google.android.play.core.integrity.IntegrityManagerFactory
-dontwarn com.google.android.play.core.integrity.IntegrityServiceException
-dontwarn com.google.android.play.core.integrity.IntegrityTokenRequest$Builder
-dontwarn com.google.android.play.core.integrity.IntegrityTokenRequest
-dontwarn com.google.android.play.core.integrity.IntegrityTokenResponse
