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
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ApproovClassVisitorFactory produces the class visitor that rewrites the construction of OkHttp,
 * HttpsURLConnection, Volley and Cronet clients in the app's own classes. It must be an abstract class with
 * no-argument construction so that the Android Gradle Plugin can instantiate it and supply its parameters.
 */
public abstract class ApproovClassVisitorFactory
        implements AsmClassVisitorFactory<InstrumentationParameters.None> {

    // ASM API level used throughout
    private static final int ASM_API = Opcodes.ASM9;

    // OkHttpClient.Builder.build() is redirected to ApproovOkHttpWeaver.build(builder)
    private static final String OKHTTP_BUILDER = "okhttp3/OkHttpClient$Builder";
    private static final String OKHTTP_BUILD = "build";
    private static final String OKHTTP_BUILD_DESC = "()Lokhttp3/OkHttpClient;";
    private static final String OKHTTP_WEAVER = "io/approov/service/android/okhttp/ApproovOkHttpWeaver";
    private static final String OKHTTP_WEAVER_DESC = "(Lokhttp3/OkHttpClient$Builder;)Lokhttp3/OkHttpClient;";

    // URL.openConnection() is redirected to ApproovUrlConnectionWeaver.openConnection(url)
    private static final String URL_OWNER = "java/net/URL";
    private static final String OPEN_CONNECTION = "openConnection";
    private static final String OPEN_CONNECTION_DESC = "()Ljava/net/URLConnection;";
    private static final String URL_WEAVER = "io/approov/service/android/httpsurlconn/ApproovUrlConnectionWeaver";
    private static final String URL_WEAVER_DESC = "(Ljava/net/URL;)Ljava/net/URLConnection;";

    // URL.openConnection(proxy) is redirected to ApproovUrlConnectionWeaver.openConnection(url, proxy)
    private static final String OPEN_CONNECTION_PROXY_DESC = "(Ljava/net/Proxy;)Ljava/net/URLConnection;";
    private static final String URL_WEAVER_PROXY_DESC =
            "(Ljava/net/URL;Ljava/net/Proxy;)Ljava/net/URLConnection;";

    // Volley.newRequestQueue(context) is redirected to ApproovVolleyWeaver.newRequestQueue(context)
    private static final String VOLLEY_OWNER = "com/android/volley/toolbox/Volley";
    private static final String NEW_REQUEST_QUEUE = "newRequestQueue";
    private static final String NEW_REQUEST_QUEUE_DESC =
            "(Landroid/content/Context;)Lcom/android/volley/RequestQueue;";
    private static final String VOLLEY_WEAVER = "io/approov/service/android/volley/ApproovVolleyWeaver";
    // the Volley redirect is static to static, so its descriptor is the same as the original, unlike the OkHttp
    // and URL redirects that take the former receiver as their first argument
    private static final String VOLLEY_WEAVER_DESC = NEW_REQUEST_QUEUE_DESC;

    // the bootstrap method of the method references that are rewritten
    static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";
    static final String METAFACTORY = "metafactory";

    // logger for the Cronet weave messages
    private static final Logger LOG = Logging.getLogger(ApproovClassVisitorFactory.class);

    // the weaver takes the former receiver first, so LambdaMetafactory accepts bound and unbound references
    static Handle wovenHandle(Handle h) {
        String owner = h.getOwner();
        String name = h.getName();
        String desc = h.getDesc();
        if ((h.getTag() == Opcodes.H_INVOKEVIRTUAL) && OKHTTP_BUILDER.equals(owner) && OKHTTP_BUILD.equals(name)
                && OKHTTP_BUILD_DESC.equals(desc))
            return new Handle(Opcodes.H_INVOKESTATIC, OKHTTP_WEAVER, OKHTTP_BUILD, OKHTTP_WEAVER_DESC, false);
        if ((h.getTag() == Opcodes.H_INVOKEVIRTUAL) && URL_OWNER.equals(owner) && OPEN_CONNECTION.equals(name)) {
            if (OPEN_CONNECTION_DESC.equals(desc))
                return new Handle(Opcodes.H_INVOKESTATIC, URL_WEAVER, OPEN_CONNECTION, URL_WEAVER_DESC, false);
            if (OPEN_CONNECTION_PROXY_DESC.equals(desc))
                return new Handle(Opcodes.H_INVOKESTATIC, URL_WEAVER, OPEN_CONNECTION, URL_WEAVER_PROXY_DESC, false);
        }
        if ((h.getTag() == Opcodes.H_INVOKESTATIC) && VOLLEY_OWNER.equals(owner) && NEW_REQUEST_QUEUE.equals(name)
                && NEW_REQUEST_QUEUE_DESC.equals(desc))
            return new Handle(Opcodes.H_INVOKESTATIC, VOLLEY_WEAVER, NEW_REQUEST_QUEUE, VOLLEY_WEAVER_DESC, false);
        return null;
    }

    @Override
    public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor nextClassVisitor) {
        return newApproovClassVisitor(nextClassVisitor);
    }

    // without the ClassContext so unit tests can use it; Cronet builders are rewritten in LENIENT mode
    static ClassVisitor newApproovClassVisitor(ClassVisitor nextClassVisitor) {
        ApproovCronetRewriter cronet = new ApproovCronetRewriter(nextClassVisitor, ApproovCronetRewriter.Mode.LENIENT) {
            @Override
            public void visitEnd() {
                super.visitEnd();
                for (Site site : sites())
                    LOG.lifecycle("Approov Cronet weave: rewrote " + site);
                for (String warning : warnings())
                    LOG.warn("Approov Cronet weave: " + warning);
            }
        };
        return new ApproovClassVisitor(cronet);
    }

    @Override
    public boolean isInstrumentable(ClassData classData) {
        // we never weave the Approov SDK, whose own OkHttp calls to the Approov cloud would then recurse (this
        // must hold even under InstrumentationScope.ALL), the service layer itself, which calls build() on
        // protected builders, or the OkHttp, Okio, Volley and Kotlin internals
        String name = classData.getClassName();
        return !name.startsWith("com.criticalblue.approovsdk.")
                && !name.startsWith("io.approov.service.")
                && !name.startsWith("io.approov.util.")
                && !name.startsWith("okhttp3.")
                && !name.startsWith("okio.")
                && !name.startsWith("com.android.volley.")
                && !name.startsWith("kotlin.");
    }

    /**
     * Wraps each method with the visitor that rewrites its call sites.
     */
    private static final class ApproovClassVisitor extends ClassVisitor {
        ApproovClassVisitor(ClassVisitor next) {
            super(ASM_API, next);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new ApproovMethodVisitor(mv);
        }
    }

    /**
     * Redirects each supported construction call to its static Approov weaver method. For a virtual call the
     * receiver already on the operand stack becomes the first argument, so the stack effect is unchanged.
     */
    private static final class ApproovMethodVisitor extends MethodVisitor {
        ApproovMethodVisitor(MethodVisitor next) {
            super(ASM_API, next);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                String descriptor, boolean isInterface) {
            if ((opcode == Opcodes.INVOKEVIRTUAL)
                    && OKHTTP_BUILDER.equals(owner)
                    && OKHTTP_BUILD.equals(name)
                    && OKHTTP_BUILD_DESC.equals(descriptor)) {
                // OkHttpClient.Builder.build() -> ApproovOkHttpWeaver.build(builder)
                super.visitMethodInsn(Opcodes.INVOKESTATIC, OKHTTP_WEAVER, OKHTTP_BUILD, OKHTTP_WEAVER_DESC, false);
            } else if ((opcode == Opcodes.INVOKEVIRTUAL)
                    && URL_OWNER.equals(owner)
                    && OPEN_CONNECTION.equals(name)
                    && OPEN_CONNECTION_DESC.equals(descriptor)) {
                // URL.openConnection() -> ApproovUrlConnectionWeaver.openConnection(url)
                super.visitMethodInsn(Opcodes.INVOKESTATIC, URL_WEAVER, OPEN_CONNECTION, URL_WEAVER_DESC, false);
            } else if ((opcode == Opcodes.INVOKEVIRTUAL)
                    && URL_OWNER.equals(owner)
                    && OPEN_CONNECTION.equals(name)
                    && OPEN_CONNECTION_PROXY_DESC.equals(descriptor)) {
                // URL.openConnection(proxy) -> ApproovUrlConnectionWeaver.openConnection(url, proxy)
                super.visitMethodInsn(Opcodes.INVOKESTATIC, URL_WEAVER, OPEN_CONNECTION, URL_WEAVER_PROXY_DESC, false);
            } else if ((opcode == Opcodes.INVOKESTATIC)
                    && VOLLEY_OWNER.equals(owner)
                    && NEW_REQUEST_QUEUE.equals(name)
                    && NEW_REQUEST_QUEUE_DESC.equals(descriptor)) {
                // Volley.newRequestQueue(context) -> ApproovVolleyWeaver.newRequestQueue(context)
                super.visitMethodInsn(Opcodes.INVOKESTATIC, VOLLEY_WEAVER, NEW_REQUEST_QUEUE,
                        VOLLEY_WEAVER_DESC, false);
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        }

        /**
         * Rewrites a method reference to a woven call, such as OkHttpClient.Builder::build, url::openConnection or
         * Volley::newRequestQueue, to use the weaver handle. Only plain LambdaMetafactory.metafactory call sites
         * are rewritten, as altMetafactory is used for serializable lambdas whose deserialization checks the
         * original handle, and these are listed by the weave report instead.
         */
        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... args) {
            if (LAMBDA_METAFACTORY.equals(bootstrap.getOwner()) && METAFACTORY.equals(bootstrap.getName())
                    && (args.length == 3) && (args[1] instanceof Handle)) {
                Handle woven = wovenHandle((Handle) args[1]);
                if (woven != null) {
                    Object[] rewritten = args.clone();
                    rewritten[1] = woven;
                    super.visitInvokeDynamicInsn(name, descriptor, bootstrap, rewritten);
                    return;
                }
            }
            super.visitInvokeDynamicInsn(name, descriptor, bootstrap, args);
        }
    }
}
