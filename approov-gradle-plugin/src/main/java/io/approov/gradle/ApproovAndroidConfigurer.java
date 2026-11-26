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

import com.android.build.api.artifact.ScopedArtifact;
import com.android.build.api.instrumentation.FramesComputationMode;
import com.android.build.api.instrumentation.InstrumentationScope;
import com.android.build.api.variant.AndroidComponentsExtension;
import com.android.build.api.variant.ScopedArtifacts;
import com.android.build.api.variant.Variant;

import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

import java.util.Collections;

import kotlin.Unit;

/**
 * ApproovAndroidConfigurer registers the parts of the plugin that depend on the Android Gradle Plugin, kept out
 * of ApproovGradlePlugin so that it loads even when the Android Gradle Plugin is not on the classpath.
 */
final class ApproovAndroidConfigurer {
    private ApproovAndroidConfigurer() {
    }

    // registers the weave, its report and its checks for every variant
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void configure(Project project) {
        AndroidComponentsExtension<?, ?, Variant> androidComponents =
                project.getExtensions().getByType(AndroidComponentsExtension.class);
        // Cronet in dependencies is woven only where the app is packaged
        ApproovExtension extension = project.getExtensions().findByType(ApproovExtension.class);
        boolean application = (extension != null)
                && project.getPluginManager().hasPlugin("com.android.application");
        androidComponents.onVariants(androidComponents.selector().all(), variant -> {
            // weave OkHttp, HttpsURLConnection, Volley and Cronet in the module's own classes only, as a library's
            // own OkHttp client (the Approov SDK's included) must not be routed through Approov
            variant.getInstrumentation().transformClassesWith(
                    ApproovClassVisitorFactory.class,
                    InstrumentationScope.PROJECT,
                    params -> Unit.INSTANCE);
            // the redirected calls keep the stack shape and the Cronet rewrite only replaces a NEW type with a
            // subclass, so copying the stack map frames is enough
            variant.getInstrumentation().setAsmFramesComputationMode(
                    FramesComputationMode.COPY_FRAMES);
            // the plugin and the library are one release: fail when they differ
            ApproovVersionConfigurer.configure(project, variant);
            // React Native's own networking, only in a React Native application (see ApproovReactNativeConfigurer)
            boolean reactNative = ApproovReactNativeConfigurer.appliesTo(project, application);
            registerWeaveReport(project, variant, reactNative, application ? extension : null);
            if (application)
                ApproovCronetConfigurer.configure(project, variant, extension);
            if (reactNative)
                ApproovReactNativeConfigurer.configure(project, variant);
            return Unit.INSTANCE;
        });
    }

    // the report, run by assemble, covers what the weave protects in the module's own classes and what it cannot,
    // and names the reports of the React Native and Cronet dependency weaves that apply to the variant, the latter
    // from the extension, null where Cronet in dependencies is not woven
    static void registerWeaveReport(Project project, Variant variant, boolean reactNative,
            ApproovExtension cronet) {
        String name = variant.getName();
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        TaskProvider<ApproovWeaveReportTask> report = project.getTasks().register("reportApproovWeave" + cap,
                ApproovWeaveReportTask.class, t -> {
                    t.setGroup("verification");
                    t.setDescription("Lists the network client construction sites the Approov weave protects, "
                            + "and those it cannot, in the " + name + " classes of this module");
                    t.getLocation().set(project.getPath() + " variant " + name);
                    t.getCronetFallback().set(variant.getRuntimeConfiguration().getIncoming()
                            .getResolutionResult().getRootComponent()
                            .map(ApproovServiceVersionCheck::cronetFallback));
                    t.getReactNativeWeave().set(reactNative);
                    if (cronet != null)
                        t.getCronetPackages().set(cronet.getCronetDependencyPackages());
                    else
                        t.getCronetPackages().set(Collections.<String>emptyList());
                    t.getReport().set(project.getLayout().getBuildDirectory()
                            .file("outputs/approov/weave-" + name + ".txt"));
                });
        // the report reads the module's own classes
        variant.getArtifacts().forScope(ScopedArtifacts.Scope.PROJECT).use(report).toGet(
                ScopedArtifact.CLASSES.INSTANCE, ApproovWeaveReportTask::getJars, ApproovWeaveReportTask::getDirs);
        // the report runs with the assemble task of the variant
        String assemble = "assemble" + cap;
        project.getTasks().configureEach(t -> {
            if (t.getName().equals(assemble))
                t.dependsOn(report);
        });
    }
}
