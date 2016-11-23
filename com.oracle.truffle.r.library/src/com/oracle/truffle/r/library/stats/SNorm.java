/*
 * This material is distributed under the GNU General Public License
 * Version 2. You may review the terms of this license at
 * http://www.gnu.org/licenses/gpl-2.0.html
 *
 * Copyright (C) 1998 Ross Ihaka
 * Copyright (c) 1998--2008, The R Core Team
 * Copyright (c) 2016, 2016, Oracle and/or its affiliates
 *
 * All rights reserved.
 */
package com.oracle.truffle.r.library.stats;

import com.oracle.truffle.r.runtime.RError;
import com.oracle.truffle.r.runtime.rng.RRNG.NormKind;
import com.oracle.truffle.r.runtime.rng.RandomNumberGenerator;

/**
 * Generation of random value from standard normal distribution N(0,1). Corresponds to
 * {@code snorm.c} in GnuR.
 */
public final class SNorm {
    private SNorm() {
        // only static members
    }

    // TODO: implement other normKinds

    private static final double BIG = 134217728; /* 2^27 */

    public static double normRand(RandomNumberGenerator rand, NormKind normKind) {
        if (normKind != NormKind.INVERSION) {
            throw RError.nyi(null, "unifNorm(): no other NormKind than the default INVERSION is implemented");
        }
        /* unif_rand() alone is not of high enough precision */
        double u1 = rand.genrandDouble();
        u1 = (int) (BIG * u1) + rand.genrandDouble();
        return Random2.qnorm5(u1 / BIG, 0.0, 1.0, true, false);
    }
}
