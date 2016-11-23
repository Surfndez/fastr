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

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.r.runtime.rng.RandomNumberGenerator;

/**
 * Generation of random value from standard exponential distribution. Corresponds to {@code sexp.c}
 * in GnuR.
 */
public final class SExp {
    private SExp() {
        // only static members
    }

    /* q[k-1] = sum(log(2)^k / k!) k=1,..,n, */
    /* The highest n (here 16) is determined by q[n-1] = 1.0 */
    /* within standard precision */
    @CompilationFinal(dimensions = 1) private static final double[] q = {
                    0.6931471805599453,
                    0.9333736875190459,
                    0.9888777961838675,
                    0.9984959252914960,
                    0.9998292811061389,
                    0.9999833164100727,
                    0.9999985691438767,
                    0.9999998906925558,
                    0.9999999924734159,
                    0.9999999995283275,
                    0.9999999999728814,
                    0.9999999999985598,
                    0.9999999999999289,
                    0.9999999999999968,
                    0.9999999999999999,
                    1.0000000000000000
    };

    public static double expRand(RandomNumberGenerator generator) {
        double a = 0.;
        // precaution if u = 0 is ever returned
        double u = generator.genrandDouble();
        while (u <= 0. || u >= 1.) {
            u = generator.genrandDouble();
        }

        for (;;) {
            u += u;
            if (u > 1.) {
                break;
            }
            a += q[0];
        }
        u -= 1.;

        if (u <= q[0]) {
            return a + u;
        }

        int i = 0;
        double ustar = generator.genrandDouble();
        double umin = ustar;
        do {
            ustar = generator.genrandDouble();
            if (umin > ustar) {
                umin = ustar;
            }
            i++;
        } while (u > q[i]);
        return a + umin * q[0];
    }
}
