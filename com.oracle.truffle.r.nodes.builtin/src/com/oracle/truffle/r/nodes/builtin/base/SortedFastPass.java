/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 3 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 3 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.r.nodes.builtin.base;

import static com.oracle.truffle.r.nodes.builtin.CastBuilder.Predef.abstractVectorValue;
import static com.oracle.truffle.r.nodes.builtin.CastBuilder.Predef.numericValue;
import static com.oracle.truffle.r.nodes.builtin.CastBuilder.Predef.toBoolean;
import static com.oracle.truffle.r.runtime.RError.Message.INVALID_LOGICAL;
import static com.oracle.truffle.r.runtime.RError.Message.ONLY_ATOMIC_CAN_BE_SORTED;
import static com.oracle.truffle.r.runtime.builtins.RBehavior.PURE;
import static com.oracle.truffle.r.runtime.builtins.RBuiltinKind.INTERNAL;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.r.nodes.builtin.RBuiltinNode;
import com.oracle.truffle.r.runtime.RRuntime;
import com.oracle.truffle.r.runtime.builtins.RBuiltin;
import com.oracle.truffle.r.runtime.data.RIntSequence;

/**
 * Fast path check if a vector is already sorted. For now we simply return {@code FALSE}. This
 * should be improved.
 */
@RBuiltin(name = "sorted_fpass", kind = INTERNAL, parameterNames = {"x", "decr", "nalast"}, behavior = PURE)
public abstract class SortedFastPass extends RBuiltinNode.Arg3 {

    static {
        Casts casts = new Casts(SortedFastPass.class);
        casts.arg("x").allowNull().mustBe(abstractVectorValue(), ONLY_ATOMIC_CAN_BE_SORTED);
        casts.arg("decr").defaultError(INVALID_LOGICAL, "decr").mustBe(numericValue()).asLogicalVector().findFirst().map(toBoolean());
        casts.arg("nalast").mustBe(numericValue(), INVALID_LOGICAL, "nalast").asLogicalVector().findFirst();
    }

    @Specialization
    protected byte isSorted(RIntSequence x, boolean decr, @SuppressWarnings("unused") byte nalast) {

        if (decr) {
            return RRuntime.asLogical(x.getStride() < 0);
        } else {
            return RRuntime.asLogical(x.getStride() >= 0);
        }
    }

    @Fallback
    public byte isSorted(@SuppressWarnings("unused") Object x, @SuppressWarnings("unused") Object decr, @SuppressWarnings("unused") Object nalast) {
        return RRuntime.LOGICAL_FALSE;
    }

    public static SortedFastPass create() {
        return SortedFastPassNodeGen.create();
    }
}
