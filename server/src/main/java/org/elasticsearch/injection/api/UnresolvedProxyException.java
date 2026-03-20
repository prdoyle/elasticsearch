/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection.api;

/**
 * Thrown when a proxy list is accessed before it has been resolved.
 * This typically means a constructor is trying to use a {@code List<T>} parameter
 * during construction, before all implementations of {@code T} have been instantiated.
 * <p>
 * To break circular dependencies, the injector provides a proxy {@link java.util.List} that is
 * populated later. If a constructor tries to iterate or query this list, this exception is thrown.
 * The {@link Actual @Actual} annotation can be used to request a non-proxy list,
 * at the cost of creating a hard ordering constraint.
 */
public class UnresolvedProxyException extends IllegalStateException {
    public UnresolvedProxyException(String message) {
        super(message);
    }
}
