/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection.step;

import org.elasticsearch.injection.ProxyPool;

/**
 * Looks in the {@link ProxyPool} to find the proxy object for the given {@code injectableType}
 * and sets it to contain the instance of that type.
 *
 * <p>
 * After resolution, the specified proxy is usable and behaves like the actual instance with
 * respect to method calls. (It, of course, has a separate object identity.)
 *
 * <p>
 * It is an error to run this step unless there is exactly one instance of the {@code injectableType} available.
 */
public record ResolveInstanceProxyStep(
    Class<?> injectableType
) implements InjectionStep {
}
