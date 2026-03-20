/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection.step;

/**
 * Makes all instances of {@link #subtype()} also available as {@link #supertype()}.
 * <p>
 * After this step executes, any instance registered under the subtype key
 * will also be retrievable under the supertype key.
 *
 * @param subtype the concrete type whose instances are being rolled up
 * @param supertype the abstract type under which the instances should also be available
 */
public record RollupStep(Class<?> subtype, Class<?> supertype) implements InjectionStep {
    public RollupStep {
        assert supertype.isAssignableFrom(subtype) : subtype + " is not a subtype of " + supertype;
    }
}
