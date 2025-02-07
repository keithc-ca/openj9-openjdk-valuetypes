/*
 * Copyright (c) 2020, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package compiler.valhalla.inlinetypes;

import java.lang.invoke.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import jdk.test.lib.Asserts;
import jdk.internal.misc.Unsafe;

import jdk.internal.value.ValueClass;
import jdk.internal.vm.annotation.ImplicitlyConstructible;
import jdk.internal.vm.annotation.LooselyConsistentValue;
import jdk.internal.vm.annotation.NullRestricted;

/**
 * @test TestBufferTearing
 * @key randomness
 * @summary Detect tearing on value class buffer writes due to missing barriers.
 * @library /testlibrary /test/lib /compiler/whitebox /
 * @enablePreview
 * @modules java.base/jdk.internal.misc
 *          java.base/jdk.internal.value
 *          java.base/jdk.internal.vm.annotation
 * @run main/othervm -XX:InlineFieldMaxFlatSize=0 -XX:FlatArrayElementMaxSize=0
 *                   -XX:+UnlockDiagnosticVMOptions -XX:+StressGCM -XX:+StressLCM
 *                   compiler.valhalla.inlinetypes.TestBufferTearing
 * @run main/othervm -XX:InlineFieldMaxFlatSize=0 -XX:FlatArrayElementMaxSize=0
 *                   -XX:+UnlockDiagnosticVMOptions -XX:+StressGCM -XX:+StressLCM
 *                   -XX:+IgnoreUnrecognizedVMOptions -XX:+AlwaysIncrementalInline
 *                   compiler.valhalla.inlinetypes.TestBufferTearing
 * @run main/othervm -XX:InlineFieldMaxFlatSize=0 -XX:FlatArrayElementMaxSize=0
 *                   -XX:CompileCommand=dontinline,*::incrementAndCheck*
 *                   -XX:+UnlockDiagnosticVMOptions -XX:+StressGCM -XX:+StressLCM
 *                   compiler.valhalla.inlinetypes.TestBufferTearing
 * @run main/othervm -XX:InlineFieldMaxFlatSize=0 -XX:FlatArrayElementMaxSize=0
 *                   -XX:CompileCommand=dontinline,*::incrementAndCheck*
 *                   -XX:+UnlockDiagnosticVMOptions -XX:+StressGCM -XX:+StressLCM
 *                   -XX:+IgnoreUnrecognizedVMOptions -XX:+AlwaysIncrementalInline
 *                   compiler.valhalla.inlinetypes.TestBufferTearing
 */

@ImplicitlyConstructible
@LooselyConsistentValue
value class MyValue {
    int x;
    int y;

    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long X_OFFSET;
    private static final long Y_OFFSET;
    static {
        try {
            Field xField = MyValue.class.getDeclaredField("x");
            X_OFFSET = U.objectFieldOffset(xField);
            Field yField = MyValue.class.getDeclaredField("y");
            Y_OFFSET = U.objectFieldOffset(yField);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    MyValue(int x, int y) {
        this.x = x;
        this.y = y;
    }

    MyValue incrementAndCheck() {
        Asserts.assertEQ(x, y, "Inconsistent field values");
        return new MyValue(x + 1, y + 1);
    }

    MyValue incrementAndCheckUnsafe() {
        Asserts.assertEQ(x, y, "Inconsistent field values");
        MyValue vt = U.makePrivateBuffer(this);
        U.putInt(vt, X_OFFSET, x + 1);
        U.putInt(vt, Y_OFFSET, y + 1);
        return U.finishPrivateBuffer(vt);
    }
}

public class TestBufferTearing {
    @NullRestricted
    static MyValue vtField1;
    @NullRestricted
    MyValue vtField2;
    MyValue[] vtField3 = (MyValue[])ValueClass.newNullRestrictedArray(MyValue.class, 1);

    static final MethodHandle incrementAndCheck_mh;

    static {
        try {
            Class<?> clazz = MyValue.class;
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            MethodType mt = MethodType.methodType(MyValue.class);
            incrementAndCheck_mh = lookup.findVirtual(clazz, "incrementAndCheck", mt);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            e.printStackTrace();
            throw new RuntimeException("Method handle lookup failed");
        }
    }

    static class Runner extends Thread {
        TestBufferTearing test;

        public Runner(TestBufferTearing test) {
            this.test = test;
        }

        // TODO: Fix commented out tests which fail after JDK-8345995
        public void run() {
            for (int i = 0; i < 1_000_000; ++i) {
                test.vtField1 = test.vtField1.incrementAndCheck();
//                test.vtField2 = test.vtField2.incrementAndCheck();
                test.vtField3[0] = test.vtField3[0].incrementAndCheck();

                test.vtField1 = test.vtField1.incrementAndCheckUnsafe();
//                test.vtField2 = test.vtField2.incrementAndCheckUnsafe();
                test.vtField3[0] = test.vtField3[0].incrementAndCheckUnsafe();

                try {
                    test.vtField1 = (MyValue)incrementAndCheck_mh.invokeExact(test.vtField1);
//                    test.vtField2 = (MyValue)incrementAndCheck_mh.invokeExact(test.vtField2);
                    test.vtField3[0] = (MyValue)incrementAndCheck_mh.invokeExact(test.vtField3[0]);
                } catch (Throwable t) {
                    throw new RuntimeException("Invoke failed", t);
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // Create threads that concurrently update some value class (array) fields
        // and check the fields of the value classes for consistency to detect tearing.
        TestBufferTearing test = new TestBufferTearing();
        Thread runner = null;
        for (int i = 0; i < 10; ++i) {
            runner = new Runner(test);
            runner.start();
        }
        runner.join();
    }
}
