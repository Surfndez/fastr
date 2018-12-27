/*
 * Copyright (c) 2015, 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.r.nodes.access.vector;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.profiles.ValueProfile;
import com.oracle.truffle.r.nodes.access.vector.PositionCheckNodeFactory.Mat2indsubNodeGen;
import com.oracle.truffle.r.nodes.access.vector.PositionsCheckNode.PositionProfile;
import com.oracle.truffle.r.nodes.attributes.SpecialAttributesFunctions.GetDimAttributeNode;
import com.oracle.truffle.r.nodes.profile.VectorLengthProfile;
import com.oracle.truffle.r.runtime.RError;
import com.oracle.truffle.r.runtime.RError.Message;
import com.oracle.truffle.r.runtime.RRuntime;
import com.oracle.truffle.r.runtime.RType;
import com.oracle.truffle.r.runtime.data.RDataFactory;
import com.oracle.truffle.r.runtime.data.REmpty;
import com.oracle.truffle.r.runtime.data.RMissing;
import com.oracle.truffle.r.runtime.data.RSymbol;
import com.oracle.truffle.r.runtime.data.RTypedValue;
import com.oracle.truffle.r.runtime.data.model.RAbstractContainer;
import com.oracle.truffle.r.runtime.data.model.RAbstractDoubleVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractIntVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractStringVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractVector;
import com.oracle.truffle.r.runtime.nodes.RBaseNode;

/**
 * Handles casting of a position (there is a position for each dimension of the target vector) to
 * either integer vector, logical vector or missing, which can than be handled by
 * {@link WriteIndexedVectorNode}.
 *
 * This node delegates to {@link PositionCastNode} that casts to integer, or logical, or missing, or
 * string vector and then this node handles further error reporting, position value transformation
 * (e.g., names to integer indexes) and normalization.
 * 
 * The subclasses implement logic specific to subset/subscript in the abstract {@code execute}
 * method, so in practice via {@code Specialization}s called from the generated {@code execute}
 * method.
 *
 * Note one special exception: {@link PositionCastNode} does not cast {@code double}s if the mode is
 * subset, and instead the double positions are handled in the {@link PositionCheckSubsetNode}.
 */
abstract class PositionCheckNode extends RBaseNode {

    private final Class<?> positionClass;
    private final int dimensionIndex;
    protected final int numDimensions;
    private final VectorLengthProfile positionLengthProfile = VectorLengthProfile.create();
    private final ValueProfile positionClassProfile = ValueProfile.createClassProfile();
    protected final BranchProfile error = BranchProfile.create();
    protected final boolean replace;
    protected final RType containerType;
    @Child private PositionCastNode castNode;
    @Child private PositionCharacterLookupNode characterLookup;

    @Child private Matrix2IndexCache matrix2IndexCache;

    private final ElementAccessMode mode;

    PositionCheckNode(ElementAccessMode mode, RType containerType, Object positionValue, int dimensionIndex, int numDimensions, boolean exact, boolean replace) {
        this.positionClass = positionValue.getClass();
        this.dimensionIndex = dimensionIndex;
        this.numDimensions = numDimensions;
        this.mode = mode;
        this.replace = replace;
        this.containerType = containerType;
        this.castNode = PositionCastNode.create(mode, replace);
        if (positionValue instanceof String || positionValue instanceof RAbstractStringVector) {
            boolean useNAForNotFound = !replace && isListLike(containerType) && mode.isSubscript();
            characterLookup = new PositionCharacterLookupNode(mode, numDimensions, dimensionIndex, useNAForNotFound, exact);
        }
        if (mode.isSubset() && numDimensions == 1) {
            matrix2IndexCache = new Matrix2IndexCache();
        }
    }

    protected static boolean isListLike(RType type) {
        switch (type) {
            case Language:
            case Expression:
            case PairList:
            case List:
                return true;
        }
        return false;
    }

    public boolean isIgnoreDimension() {
        return positionClass == RMissing.class;
    }

    public Class<?> getPositionClass() {
        return positionClass;
    }

    public final boolean isSupported(Object object) {
        return object.getClass() == positionClass;
    }

    public static PositionCheckNode createNode(ElementAccessMode mode, RType containerType, Object position, int positionIndex, int numDimensions, boolean exact, boolean replace, boolean recursive) {
        if (mode.isSubset()) {
            return PositionCheckSubsetNodeGen.create(mode, containerType, position, positionIndex, numDimensions, exact, replace);
        } else {
            return PositionCheckSubscriptNodeGen.create(mode, containerType, position, positionIndex, numDimensions, exact, replace, recursive);
        }
    }

    protected boolean isMultiDimension() {
        return numDimensions > 1;
    }

    public final Object execute(PositionProfile profile, RAbstractContainer vector, int[] vectorDimensions, int vectorLength, Object position) {
        Object castPosition = castNode.execute(positionClass.cast(position));

        if (mode.isSubscript() && isMissing()) {
            if (!isListLike(containerType)) {
                throw error(Message.SUBSCRIPT_BOUNDS);
            }
        }

        int dimensionLength;
        if (numDimensions == 1) {
            dimensionLength = vectorLength;
        } else {
            assert vectorDimensions != null;
            assert vectorDimensions.length == numDimensions;
            dimensionLength = vectorDimensions[dimensionIndex];
        }

        if (mode.isSubset() && numDimensions == 1) {
            int[] vectorDim = matrix2IndexCache.getVectorDimsNode.getDimensions(vector);
            if (matrix2IndexCache.nullDimensionsProfile.profile(vectorDim != null) && vectorDim.length == 2) {
                if (vector instanceof RAbstractVector) {
                    if (castPosition instanceof RAbstractVector) {
                        RAbstractVector vectorPosition = (RAbstractVector) castPosition;
                        int[] posDim = matrix2IndexCache.getVectorPosDimsNode.getDimensions(vectorPosition);
                        if (posDim != null && posDim.length == 2 && posDim[1] == vectorDim.length) {
                            if (castPosition instanceof RAbstractIntVector || castPosition instanceof RAbstractDoubleVector) {
                                castPosition = matrix2IndexCache.mat2indsub.execute(vectorDim, vectorPosition, posDim);
                            }
                        }
                    }
                }
            }
        }

        if (characterLookup != null) {
            castPosition = characterLookup.execute(vector, (RAbstractStringVector) castPosition, dimensionLength);
        }

        RTypedValue positionVector = (RTypedValue) profilePosition(castPosition);

        int positionLength;
        if (positionVector instanceof RMissing) {
            positionLength = -1;
        } else {
            positionLength = positionLengthProfile.profile(((RAbstractVector) positionVector).getLength());
        }

        assert isValidCastedType(positionVector) : "result type of a position cast node must be integer or logical";

        return execute(profile, dimensionLength, positionVector, positionLength);
    }

    private final ValueProfile castedValue = ValueProfile.createClassProfile();

    private Object profilePosition(Object positionVector) {
        return castedValue.profile(positionVector);
    }

    private static boolean isValidCastedType(RTypedValue positionVector) {
        RType type = positionVector.getRType();
        return type == RType.Integer || type == RType.Logical || type == RType.Character || type == RType.Double || type == RType.Null;
    }

    public abstract Object execute(PositionProfile statistics, int dimensionLength, Object position, int positionLength);

    /*
     * Transcribed from GnuR subscript.c/mat2indsub
     *
     * Special Matrix Subscripting: Handles the case x[i] where x is an n-way array and i is a
     * matrix with n columns. This code returns a vector containing the subscripts to be extracted
     * when x is regarded as unravelled.
     *
     * Negative indices are not allowed.
     *
     * A zero/NA anywhere in a row will cause a zero/NA in the same position in the result.
     */
    public abstract static class Mat2indsubNode extends RBaseNode {

        public abstract RAbstractVector execute(int[] vectorDimensions, RAbstractVector pos, int[] positionDimensions);

        private final BranchProfile error = BranchProfile.create();
        private final BranchProfile na = BranchProfile.create();

        @Specialization
        protected RAbstractIntVector doInt(int[] vectorDimensions, RAbstractIntVector intPos, int[] positionDimensions) {
            int nrs = positionDimensions[0];
            int[] iv = new int[nrs];

            assert vectorDimensions.length == 2;

            for (int i = 0; i < nrs; i++) {
                iv[i] = 1;
            }
            for (int i = 0; i < nrs; i++) {
                int tdim = 1;
                for (int j = 0; j < vectorDimensions.length; j++) {

                    int k = intPos.getDataAt(i + j * nrs);
                    if (k == RRuntime.INT_NA || k == 0) {
                        na.enter();
                        iv[i] = k;
                        break;
                    }
                    if (k < 0) {
                        error.enter();
                        throw error(RError.Message.GENERIC, "negative values are not allowed in a matrix subscript");
                    }
                    int dim = vectorDimensions[j];
                    if (k > dim) {
                        error.enter();
                        throw error(RError.Message.SUBSCRIPT_BOUNDS);
                    }
                    iv[i] += (k - 1) * tdim;
                    tdim *= dim;
                }
            }
            return RDataFactory.createIntVector(iv, intPos.isComplete());
        }

        @Specialization
        protected RAbstractDoubleVector doDouble(int[] vectorDimensions, RAbstractDoubleVector doublePos, int[] positionDimensions) {
            int nrs = positionDimensions[0];
            double[] iv = new double[nrs];

            assert vectorDimensions.length == 2;

            for (int i = 0; i < nrs; i++) {
                iv[i] = 1;
            }
            for (int i = 0; i < nrs; i++) {
                int tdim = 1;
                for (int j = 0; j < vectorDimensions.length; j++) {
                    double k = doublePos.getDataAt(i + j * nrs);
                    if (RRuntime.isNAorNaN(k) || k == 0) {
                        na.enter();
                        iv[i] = k;
                        break;
                    }
                    if (k < 0) {
                        error.enter();
                        throw error(RError.Message.GENERIC, "negative values are not allowed in a matrix subscript");
                    }
                    int dim = vectorDimensions[j];
                    if (k > dim) {
                        error.enter();
                        throw error(RError.Message.SUBSCRIPT_BOUNDS);
                    }
                    iv[i] += (k - 1) * tdim;
                    tdim *= dim;
                }
            }
            return RDataFactory.createDoubleVector(iv, doublePos.isComplete());
        }
    }

    private final class Matrix2IndexCache extends Node {
        @Child private Mat2indsubNode mat2indsub;
        @Child private GetDimAttributeNode getVectorDimsNode;
        @Child private GetDimAttributeNode getVectorPosDimsNode;
        private final ConditionProfile nullDimensionsProfile;

        Matrix2IndexCache() {
            this.getVectorDimsNode = GetDimAttributeNode.create();
            this.nullDimensionsProfile = ConditionProfile.createBinaryProfile();
            this.mat2indsub = Mat2indsubNodeGen.create();
            this.getVectorPosDimsNode = GetDimAttributeNode.create();
        }
    }

    public boolean isEmptyPosition(Object position) {
        if (positionClass == REmpty.class) {
            return false;
        }
        Object castPosition = positionClassProfile.profile(position);
        return castPosition instanceof RAbstractContainer && ((RAbstractContainer) castPosition).getLength() == 0;
    }

    public boolean isMissing() {
        return positionClass == RMissing.class || positionClass == REmpty.class || positionClass == RSymbol.class;
    }
}
