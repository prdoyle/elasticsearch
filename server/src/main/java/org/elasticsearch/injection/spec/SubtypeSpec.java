/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection.spec;

/**
 * Indicates that the {@link #requestedType()} should be injected using an instance of a specified {@link #subtype()}.
 * <p>
 * This is created automatically when the injector discovers that a class implements an interface
 * or extends an abstract class. When someone requests the supertype, the injector redirects to the subtype.
 *
 * @param requestedType the type that was requested (the supertype)
 * @param subtype the concrete type that provides the implementation
 */
public record SubtypeSpec(Class<?> requestedType, Class<?> subtype) implements UnambiguousSpec {
    public SubtypeSpec {
        assert requestedType.isAssignableFrom(subtype) : subtype + " is not a subtype of " + requestedType;
        assert requestedType != subtype : "SubtypeSpec should redirect to a different type, not " + requestedType;
    }
}
