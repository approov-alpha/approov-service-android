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

import com.android.build.api.instrumentation.InstrumentationScope;
import com.android.build.api.variant.Variant;
import org.gradle.api.Project;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.tasks.TaskProvider;

import kotlin.Unit;

/**
 * Registers the React Native weave for a variant of an application that applies React Native's Gradle plugin.
 * Nothing here runs for any other build, so the weaves of the other stacks are unchanged.
 */
final class ApproovReactNativeConfigurer {
    // React Native's Gradle plugin, which every React Native application module applies
    static final String REACT_NATIVE_PLUGIN = "com.facebook.react";

    // attribute used to select the class jars of the dependencies
    private static final Attribute<String> ARTIFACT_TYPE = Attribute.of("artifactType", String.class);

    private ApproovReactNativeConfigurer() {
    }

    // whether the module is a React Native application
    static boolean appliesTo(Project project, boolean application) {
        return application && project.getPluginManager().hasPlugin(REACT_NATIVE_PLUGIN);
    }

    static void configure(Project project, Variant variant) {
        variant.getInstrumentation().transformClassesWith(
                ApproovReactNativeVisitorFactory.class,
                InstrumentationScope.ALL,
                params -> Unit.INSTANCE);
        // check before every build that React Native's provider is there and has the shape the weave protects
        String name = variant.getName();
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        TaskProvider<ApproovReactNativeCheckTask> check = project.getTasks().register(
                "checkApproovReactNativeWeave" + cap, ApproovReactNativeCheckTask.class, t -> {
                    t.setGroup("verification");
                    t.setDescription("Fails unless the Approov weave protects React Native's OkHttpClientProvider "
                            + "in this React Native application");
                    t.getClasspath().from(variant.getRuntimeConfiguration().getIncoming().artifactView(view -> {
                        view.setLenient(true);
                        view.attributes(a -> a.attribute(ARTIFACT_TYPE, "android-classes-jar"));
                    }).getFiles());
                    t.getReport().set(project.getLayout().getBuildDirectory()
                            .file("outputs/approov/react-native-weave-" + name + ".txt"));
                });
        variant.getLifecycleTasks().registerPreBuild(check);
    }
}
