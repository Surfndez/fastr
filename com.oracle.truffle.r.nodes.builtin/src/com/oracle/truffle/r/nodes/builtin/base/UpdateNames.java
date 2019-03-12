/*
 * Copyright (c) 2013, 2019, Oracle and/or its affiliates. All rights reserved.
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

import static com.oracle.truffle.r.runtime.RDispatch.INTERNAL_GENERIC;
import static com.oracle.truffle.r.runtime.builtins.RBehavior.PURE;
import static com.oracle.truffle.r.runtime.builtins.RBuiltinKind.PRIMITIVE;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.r.nodes.attributes.RemoveFixedAttributeNode;
import com.oracle.truffle.r.nodes.attributes.SpecialAttributesFunctions.GetDimAttributeNode;
import com.oracle.truffle.r.nodes.builtin.RBuiltinNode;
import com.oracle.truffle.r.nodes.helpers.RFactorNodes.GetLevels;
import com.oracle.truffle.r.nodes.unary.CastStringNode;
import com.oracle.truffle.r.nodes.unary.CastStringNodeGen;
import com.oracle.truffle.r.nodes.unary.GetNonSharedNode;
import com.oracle.truffle.r.nodes.unary.IsFactorNode;
import com.oracle.truffle.r.runtime.RError;
import com.oracle.truffle.r.runtime.RError.Message;
import com.oracle.truffle.r.runtime.builtins.RBuiltin;
import com.oracle.truffle.r.runtime.data.RDataFactory;
import com.oracle.truffle.r.runtime.data.RNull;
import com.oracle.truffle.r.runtime.data.RStringVector;
import com.oracle.truffle.r.runtime.data.closures.RClosures;
import com.oracle.truffle.r.runtime.data.model.RAbstractContainer;
import com.oracle.truffle.r.runtime.data.model.RAbstractIntVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractVector;

@RBuiltin(name = "names<-", kind = PRIMITIVE, parameterNames = {"x", "value"}, dispatch = INTERNAL_GENERIC, behavior = PURE)
public abstract class UpdateNames extends RBuiltinNode.Arg2 {

    @Child private CastStringNode castStringNode;
    @Child private GetLevels getFactorLevels;
    @Child private GetDimAttributeNode getDimNode;
    @Child private RemoveFixedAttributeNode removeDimNamesNode;

    static {
        Casts casts = new Casts(UpdateNames.class);
        casts.arg("x");
    }

    private Object castString(Object o) {
        if (castStringNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            castStringNode = insert(CastStringNodeGen.create(false, false, false));
        }
        return castStringNode.executeString(o);
    }

    public abstract Object executeStringVector(RAbstractContainer container, Object o);

    @Specialization
    @TruffleBoundary
    protected RAbstractContainer updateNames(RAbstractContainer container, Object namesArg,
                    @Cached("new()") IsFactorNode isFactorNode,
                    @Cached("createBinaryProfile()") ConditionProfile isFactorProfile,
                    @Cached("create()") GetNonSharedNode nonShared) {
        Object names = namesArg;
        if (isFactorProfile.profile(isFactorNode.executeIsFactor(names))) {
            final RStringVector levels = getFactorLevels(names);
            if (levels != null) {
                names = RClosures.createFactorToVector((RAbstractIntVector) names, true, levels);
            }
        }
        Object newNames = names == RNull.instance ? names : castString(names);
        RAbstractContainer result = ((RAbstractContainer) nonShared.execute(container)).materialize();
        if (newNames == RNull.instance) {
            if (getDimNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getDimNode = GetDimAttributeNode.create();
                removeDimNamesNode = RemoveFixedAttributeNode.createDimNames();
            }
            int[] dims = getDimNode.getDimensions(result);
            if (dims != null && dims.length == 1) {
                removeDimNamesNode.execute(result);
            } else {
                result.setNames(null);
            }
            return result;
        }

        RStringVector stringVector;
        if (newNames instanceof String) {
            stringVector = RDataFactory.createStringVector((String) newNames);
        } else {
            stringVector = (RStringVector) ((RAbstractVector) newNames).materialize().copyDropAttributes();
        }
        if (stringVector.getLength() < result.getLength()) {
            stringVector = (RStringVector) stringVector.copyResized(result.getLength(), true);
        } else if (stringVector.getLength() > result.getLength()) {
            throw error(Message.NAMES_LONGER, stringVector.getLength(), result.getLength());
        } else if (stringVector == names) {
            stringVector = (RStringVector) stringVector.copy();
        }
        if (stringVector.isTemporary()) {
            stringVector.incRefCount();
        }
        result.setNames(stringVector);
        return result;
    }

    private RStringVector getFactorLevels(Object names) {
        if (getFactorLevels == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            getFactorLevels = insert(GetLevels.create());
        }
        assert names instanceof RAbstractIntVector;
        final RStringVector levels = getFactorLevels.execute((RAbstractIntVector) names);
        return levels;
    }

    @Specialization
    protected Object updateNames(RNull n, @SuppressWarnings("unused") RNull names) {
        return n;
    }

    @Specialization
    protected Object updateNames(@SuppressWarnings("unused") RNull n, @SuppressWarnings("unused") Object names) {
        return error(RError.Message.SET_ATTRIBUTES_ON_NULL);
    }

    @Fallback
    protected Object doOthers(@SuppressWarnings("unused") Object target, @SuppressWarnings("unused") Object names) {
        throw error(Message.NAMES_NONVECTOR);
    }
}
