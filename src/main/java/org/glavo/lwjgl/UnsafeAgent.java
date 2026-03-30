/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glavo.lwjgl;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.AccessFlag;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;

public final class UnsafeAgent {

    private static final String MEMORY_UTIL_CLASS = "org/lwjgl/system/MemoryUtil";

    private static final ClassDesc CD_Unsafe = ClassDesc.of("jdk.internal.misc.Unsafe");
    private static final MethodTypeDesc MTD_getUnsafe = MethodTypeDesc.of(CD_Unsafe);

    // memGetXxx method name -> Unsafe method name
    private static final Map<String, String> GET_METHODS = Map.of(
            "memGetByte", "getByte",
            "memGetShort", "getShort",
            "memGetInt", "getInt",
            "memGetLong", "getLong",
            "memGetFloat", "getFloat",
            "memGetDouble", "getDouble",
            "memGetAddress", "getAddress"
    );

    // memPutXxx method name -> Unsafe method name
    private static final Map<String, String> PUT_METHODS = Map.of(
            "memPutByte", "putByte",
            "memPutShort", "putShort",
            "memPutInt", "putInt",
            "memPutLong", "putLong",
            "memPutFloat", "putFloat",
            "memPutDouble", "putDouble",
            "memPutAddress", "putAddress"
    );

    private static Instrumentation instrumentation;

    public static void premain(String agentArgs, Instrumentation inst) {
        init(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        init(inst);
    }

    private static void init(Instrumentation inst) {
        instrumentation = inst;
        inst.addTransformer(new MemoryUtilTransformer());
    }

    /**
     * Ensure {@code jdk.internal.misc} is exported to the unnamed module of the given class loader.
     */
    private static void ensureExported(Module target) {
        if (target == null) return;
        Module javaBase = Object.class.getModule();
        String miscPackage = CD_Unsafe.packageName();

        if (!javaBase.isExported(miscPackage, target)) {
            instrumentation.redefineModule(javaBase,
                    Set.of(),
                    Map.of(miscPackage, Set.of(target)),
                    Map.of(),
                    Set.of(),
                    Map.of()
            );
        }
    }

    private static final class MemoryUtilTransformer implements ClassFileTransformer {

        @Override
        public byte[] transform(Module module,
                                ClassLoader loader, String className,
                                Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) {
            if (!MEMORY_UTIL_CLASS.equals(className)) {
                return null;
            }

            ensureExported(module);

            try {
                byte[] result = transformMemoryUtil(classfileBuffer);
                System.out.println("[lwjgl-unsafe-agent] Successfully transformed MemoryUtil");
                return result;
            } catch (Throwable t) {
                System.err.println("[lwjgl-unsafe-agent] Failed to transform MemoryUtil");
                t.printStackTrace(System.err);
                return null;
            }
        }
    }

    private static byte[] transformMemoryUtil(byte[] classfileBuffer) {
        var cf = ClassFile.of();
        var model = cf.parse(classfileBuffer);

        return cf.transformClass(model, (classBuilder, classElement) -> {
            if (classElement instanceof MethodModel mm && mm.flags().has(AccessFlag.STATIC)) {
                String name = mm.methodName().stringValue();
                String desc = mm.methodType().stringValue();

                String unsafeName = GET_METHODS.get(name);
                if (unsafeName != null && isGetDescriptor(desc)) {
                    rewriteMethod(classBuilder, mm, unsafeName);
                    return;
                }

                unsafeName = PUT_METHODS.get(name);
                if (unsafeName != null && isPutDescriptor(desc)) {
                    rewriteMethod(classBuilder, mm, unsafeName);
                    return;
                }
            }
            classBuilder.with(classElement);
        });
    }

    /**
     * Check if descriptor matches {@code (J)X} — a get method taking a long address
     * and returning a primitive value.
     */
    private static boolean isGetDescriptor(String desc) {
        return desc.length() == 4 && desc.startsWith("(J)");
    }

    /**
     * Check if descriptor matches {@code (JX)V} — a put method taking a long address
     * and a primitive value, returning void.
     */
    private static boolean isPutDescriptor(String desc) {
        return desc.length() == 5 && desc.startsWith("(J") && desc.endsWith(")V");
    }

    /**
     * Replace the method body with a direct call to {@code jdk.internal.misc.Unsafe}.
     * Non-code attributes (annotations, etc.) are preserved.
     */
    private static void rewriteMethod(ClassBuilder classBuilder, MethodModel mm, String unsafeMethodName) {
        var mtd = MethodTypeDesc.ofDescriptor(mm.methodType().stringValue());

        classBuilder.withMethod(mm.methodName().stringValue(), mtd, mm.flags().flagsMask(), mb -> {
            // Preserve non-code method attributes (annotations, etc.)
            for (var me : mm) {
                if (!(me instanceof CodeModel)) {
                    mb.with(me);
                }
            }

            // Generate new code body
            mb.withCode(cb -> generateCode(cb, unsafeMethodName, mtd));
        });

        System.out.println("[lwjgl-unsafe-agent] rewrote " + mm);
    }

    /**
     * Generate bytecode that delegates to {@code jdk.internal.misc.Unsafe}.
     * <p>
     * For get methods ({@code (J)X}):
     * <pre>
     *   Unsafe.getUnsafe().getXxx(address)
     * </pre>
     * For put methods ({@code (JX)V}):
     * <pre>
     *   Unsafe.getUnsafe().putXxx(address, value)
     * </pre>
     */
    private static void generateCode(CodeBuilder cb, String unsafeMethodName, MethodTypeDesc mtd) {
        // Push the Unsafe instance
        cb.invokestatic(CD_Unsafe, "getUnsafe", MTD_getUnsafe);

        // Push the address parameter (slot 0, type long)
        cb.lload(0);

        if (mtd.returnType().descriptorString().equals("V")) {
            // Put method: load value parameter (slot 2) and invoke putXxx
            ClassDesc valueType = mtd.parameterType(1);
            loadValue(cb, valueType, 2);
            cb.invokevirtual(CD_Unsafe, unsafeMethodName,
                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_long, valueType));
            cb.return_();
        } else {
            // Get method: invoke getXxx and return the value
            ClassDesc returnType = mtd.returnType();
            cb.invokevirtual(CD_Unsafe, unsafeMethodName,
                    MethodTypeDesc.of(returnType, ConstantDescs.CD_long));
            emitReturn(cb, returnType);
        }
    }

    private static void loadValue(CodeBuilder cb, ClassDesc type, int slot) {
        switch (type.descriptorString()) {
            case "J" -> cb.lload(slot);
            case "F" -> cb.fload(slot);
            case "D" -> cb.dload(slot);
            default -> cb.iload(slot);  // B, S, I, C, Z
        }
    }

    private static void emitReturn(CodeBuilder cb, ClassDesc type) {
        switch (type.descriptorString()) {
            case "J" -> cb.lreturn();
            case "F" -> cb.freturn();
            case "D" -> cb.dreturn();
            default -> cb.ireturn();  // B, S, I, C, Z
        }
    }
}
