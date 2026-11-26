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
import org.objectweb.asm.ClassVisitor;

/**
 * Weaves React Native's OkHttpClientProvider, and no other class, in an application that applies React Native's
 * Gradle plugin. Registered in InstrumentationScope.ALL because the class is in the react-android dependency.
 */
public abstract class ApproovReactNativeVisitorFactory
        implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    // logger for the rewritten sites
    private static final Logger LOG = Logging.getLogger(ApproovReactNativeVisitorFactory.class);

    @Override
    public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor next) {
        return new ApproovReactNativeRewriter(next) {
            @Override
            public void visitEnd() {
                super.visitEnd();
                for (String s : sites())
                    LOG.lifecycle("Approov React Native weave: rewrote " + s);
            }
        };
    }

    @Override
    public boolean isInstrumentable(ClassData classData) {
        return ApproovReactNativeRewriter.PROVIDER_CLASS.equals(classData.getClassName());
    }
}
