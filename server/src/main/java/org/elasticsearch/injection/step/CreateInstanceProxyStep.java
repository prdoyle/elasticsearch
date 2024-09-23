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
 * Creates a new proxy for a single instance of the given {@code injectableType}
 * and adds it to the {@link ProxyPool}, making it available for injection.
 * Like any step that makes an object available for injection,
 * this step must run before any step that requires the resulting object.
 *
 * <p>
 * The proxy object is initially in an unusable state:
 * it will throw an exception if any method is called
 * until the proxy is {@linkplain ResolveInstanceProxyStep <em>resolved</em>}.
 *
 * @param injectableType the type for which we should create a proxy.
 * @see ResolveInstanceProxyStep
 */
public record CreateInstanceProxyStep(
    Class<?> injectableType
) implements InjectionStep {
}
