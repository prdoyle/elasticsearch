/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection.spec;

import java.util.stream.Stream;

/**
 * Represents a type for which multiple implementations have been discovered.
 * <p>
 * This is not an error in itself: if the type is only requested via {@code List<T>},
 * all candidates will be collected. However, if the type is requested directly
 * (as a single instance), the ambiguity is an error.
 *
 * @param requestedType the type that has multiple implementations
 * @param option1 one candidate
 * @param option2 another candidate (or a nested {@code AmbiguousSpec} for more than two)
 */
public record AmbiguousSpec(Class<?> requestedType, InjectionSpec option1, InjectionSpec option2) implements InjectionSpec {

    /**
     * Flattens the binary tree of ambiguous specs into a stream of unambiguous candidates.
     */
    public Stream<UnambiguousSpec> candidates() {
        return Stream.concat(candidatesOf(option1), candidatesOf(option2));
    }

    private static Stream<UnambiguousSpec> candidatesOf(InjectionSpec spec) {
        if (spec instanceof AmbiguousSpec a) {
            return a.candidates();
        } else if (spec instanceof UnambiguousSpec u) {
            return Stream.of(u);
        } else {
            throw new AssertionError("Unexpected spec: " + spec);
        }
    }
}
