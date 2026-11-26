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

package io.approov.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * ApproovGradlePlugin protects the app's networking with Approov at build time. It registers a class visitor
 * factory with the Android Gradle Plugin that rewrites the construction of the supported stacks in the module's
 * own classes, so that the resulting clients and connections are routed through the Approov adapters. These are
 * OkHttpClient.Builder.build() and the method reference OkHttpClient.Builder::build for OkHttp, which also cover
 * Retrofit and Ktor when they are given an OkHttpClient built that way in woven code (Retrofit's client(...),
 * Ktor's preconfigured), URL.openConnection() and URL.openConnection(Proxy) for HttpsURLConnection,
 * Volley.newRequestQueue(context) for Volley, and new CronetEngine.Builder(context) and provider.createBuilder()
 * for Cronet, which in an application module are also woven in the dependency packages of the approov extension
 * (react-native-nitro-fetch by default).
 *
 * <p>In a React Native application it also weaves React Native's OkHttpClientProvider, so React Native's own
 * fetch, XMLHttpRequest and Expo fetch are protected (see ApproovReactNativeConfigurer). Retrofit without
 * client(...), new OkHttpClient(), URL.openStream(), android.net.http.HttpEngine and clients built in libraries
 * (other than those Cronet packages and React Native's provider) or in modules that do not apply the plugin are
 * not woven. The reportApproovWeave task of each variant lists what was woven in the module
 * and warns about the construction sites it cannot protect, and checkApproovServiceVersion fails the build when
 * the io.approov:service.android runtime is missing. The app still calls ApproovService.initialize(...) once.
 */
public class ApproovGradlePlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        // the Android Gradle Plugin must be applied first, so fail with a clear message rather than an
        // UnknownDomainObjectException
        boolean isAndroid = project.getPlugins().hasPlugin("com.android.application")
                || project.getPlugins().hasPlugin("com.android.library");
        if (!isAndroid) {
            throw new GradleException("Approov Gradle plugin (io.approov.gradle) must be applied to an "
                    + "Android module: apply 'com.android.application' or 'com.android.library' before it");
        }

        // the approov extension, which weaves react-native-nitro-fetch by default
        ApproovExtension extension = project.getExtensions().create("approov", ApproovExtension.class);
        extension.getCronetDependencyPackages().convention(
                java.util.Collections.singletonList(ApproovExtension.NITRO_FETCH_PACKAGE));

        // the Android Gradle Plugin types are kept out of this class, so the check above still fails clearly when
        // it is not on the classpath
        ApproovAndroidConfigurer.configure(project);

        project.getLogger().info("Approov Gradle plugin applied: weaving OkHttp, HttpsURLConnection, Volley and "
                + "Cronet construction sites");
    }
}
