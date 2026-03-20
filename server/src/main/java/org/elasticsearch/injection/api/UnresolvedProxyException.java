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
 * Thrown when a proxy is accessed before it has been resolved.
 * This typically means a constructor is trying to use a proxy parameter
 * during construction, before the target has been instantiated.
 * <p>
 * To break circular dependencies, the injector provides proxy objects (either a proxy
 * {@link java.util.List} or a JDK dynamic proxy for interfaces) that are resolved later.
 * If a constructor tries to use the proxy before resolution, this exception is thrown.
 * The {@link Actual @Actual} annotation can be used to request a non-proxy instance,
 * at the cost of creating a hard ordering constraint.
 */
public class UnresolvedProxyException extends IllegalStateException {
    public UnresolvedProxyException(String message) {
        super(message);
    }
}
