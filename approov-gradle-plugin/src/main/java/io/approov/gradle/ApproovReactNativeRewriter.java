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

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rewrites React Native's OkHttpClientProvider so that every OkHttp client and builder it hands out carries the
 * Approov interceptors: the builder returned by createClientBuilder(), which every client React Native builds
 * comes from (the networking module behind fetch, WebSocket, Fresco, dev support) and which Expo modules use
 * directly, and the client returned by an app's own OkHttpClientFactory. Only that one class is rewritten, and
 * only React Native's public names are matched, so the rewrite does not depend on the private or internal
 * members React Native renames between releases. The inserted calls take one reference and leave one, so the
 * stack shape and the stack map frames are unchanged.
 */
class ApproovReactNativeRewriter extends ClassVisitor {
    // ASM API level used throughout
    private static final int ASM_API = Opcodes.ASM9;

    // the one class that is rewritten
    static final String PROVIDER_CLASS = "com.facebook.react.modules.network.OkHttpClientProvider";

    // createClientBuilder() returns are passed through ApproovReactNativeWeaver.protectBuilder(builder)
    private static final String CREATE_CLIENT_BUILDER = "createClientBuilder";
    private static final String CREATE_CLIENT_BUILDER_DESC = "()Lokhttp3/OkHttpClient$Builder;";

    // the result of OkHttpClientFactory.createNewNetworkModuleClient() is passed through
    // ApproovReactNativeWeaver.protectClient(client)
    private static final String FACTORY = "com/facebook/react/modules/network/OkHttpClientFactory";
    private static final String CREATE_NEW_CLIENT = "createNewNetworkModuleClient";
    private static final String CREATE_NEW_CLIENT_DESC = "()Lokhttp3/OkHttpClient;";

    // the weaver in the service library
    static final String WEAVER = "io/approov/service/android/okhttp/ApproovReactNativeWeaver";
    private static final String PROTECT_BUILDER = "protectBuilder";
    private static final String PROTECT_BUILDER_DESC = "(Lokhttp3/OkHttpClient$Builder;)Lokhttp3/OkHttpClient$Builder;";
    private static final String PROTECT_CLIENT = "protectClient";
    private static final String PROTECT_CLIENT_DESC = "(Lokhttp3/OkHttpClient;)Lokhttp3/OkHttpClient;";

    // the rewritten sites, for the build log and the tests
    private final List<String> sites = new ArrayList<>();

    // whether the class already calls the weaver, so a second pass does not protect twice
    private boolean alreadyWoven;

    // the counts the visitEnd check needs
    private int builderReturns;
    private int factoryCalls;

    ApproovReactNativeRewriter(ClassVisitor next) {
        super(ASM_API, next);
    }

    List<String> sites() {
        return Collections.unmodifiableList(sites);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
            String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        boolean builderMethod = ((access & Opcodes.ACC_STATIC) != 0) && CREATE_CLIENT_BUILDER.equals(name)
                && CREATE_CLIENT_BUILDER_DESC.equals(descriptor);
        return new MethodVisitor(ASM_API, mv) {
            @Override
            public void visitInsn(int opcode) {
                if (builderMethod && (opcode == Opcodes.ARETURN)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, WEAVER, PROTECT_BUILDER, PROTECT_BUILDER_DESC, false);
                    builderReturns++;
                    sites.add(PROVIDER_CLASS + "." + name + "(): return -> ApproovReactNativeWeaver.protectBuilder(builder)");
                }
                super.visitInsn(opcode);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean itf) {
                if (WEAVER.equals(owner))
                    alreadyWoven = true;
                super.visitMethodInsn(opcode, owner, mname, mdesc, itf);
                if ((opcode == Opcodes.INVOKEINTERFACE) && FACTORY.equals(owner) && CREATE_NEW_CLIENT.equals(mname)
                        && CREATE_NEW_CLIENT_DESC.equals(mdesc)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, WEAVER, PROTECT_CLIENT, PROTECT_CLIENT_DESC, false);
                    factoryCalls++;
                    sites.add(PROVIDER_CLASS + "." + name + descriptor.substring(0, descriptor.indexOf(')') + 1)
                            + ": OkHttpClientFactory.createNewNetworkModuleClient() -> ApproovReactNativeWeaver.protectClient(client)");
                }
            }
        };
    }

    @Override
    public void visitEnd() {
        super.visitEnd();
        if (alreadyWoven)
            throw new ApproovWeaveException(PROVIDER_CLASS + " was already woven by Approov: it must be woven once");
        // React Native's networking would otherwise be silently unprotected
        if ((builderReturns == 0) || (factoryCalls == 0)) {
            throw new ApproovWeaveException(PROVIDER_CLASS + " no longer has the shape the Approov weave expects "
                    + "(createClientBuilder() returning OkHttpClient.Builder, and a call to "
                    + "OkHttpClientFactory.createNewNetworkModuleClient()), so React Native's own networking would NOT "
                    + "be protected by Approov: update the Approov Gradle plugin for this React Native version");
        }
    }
}
