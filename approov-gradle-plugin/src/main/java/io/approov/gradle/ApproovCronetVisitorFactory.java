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

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.objectweb.asm.ClassVisitor;

/**
 * ApproovCronetVisitorFactory weaves Cronet builder creation, in STRICT mode, in the configured dependency packages
 * (react-native-nitro-fetch by default). It is registered with InstrumentationScope.ALL, as those libraries are
 * dependencies of the app (a subproject through React Native autolinking, or an AAR) rather than its own classes,
 * and only classes in the configured packages are instrumented.
 */
public abstract class ApproovCronetVisitorFactory
        implements AsmClassVisitorFactory<ApproovCronetVisitorFactory.Parameters> {
    // logger for the rewritten sites
    private static final Logger LOG = Logging.getLogger(ApproovCronetVisitorFactory.class);

    // Parameters holds the dotted dependency packages to weave
    public interface Parameters extends InstrumentationParameters {
        @Input
        ListProperty<String> getPackages();
    }

    @Override
    public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor next) {
        return new ApproovCronetRewriter(next, ApproovCronetRewriter.Mode.STRICT) {
            @Override
            public void visitEnd() {
                super.visitEnd();
                for (Site s : sites())
                    LOG.lifecycle("Approov Cronet weave: rewrote " + s);
            }
        };
    }

    @Override
    public boolean isInstrumentable(ClassData classData) {
        return isInPackages(classData.getClassName(), getParameters().get().getPackages().get());
    }

    static boolean isInPackages(String className, java.util.List<String> packages) {
        for (String p : packages) {
            if (className.startsWith(p + "."))
                return true;
        }
        return false;
    }
}
