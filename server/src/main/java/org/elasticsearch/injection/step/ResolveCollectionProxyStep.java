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
 * Looks in the {@link ProxyPool} to find the proxy collection containing elements of the given {@code injectableType}
 * and sets it to contain all instances of that type.
 *
 * <p>
 * After resolution, the specified collection is usable and behaves like any other immutable collection:
 * its contents can be inspected, iterated, etc.
 *
 * <p>
 * Since the collection cannot be changed after resolution,
 * this step must run after all instances of the {@code injectableType} have already been created.
 *
 * @param injectableType the type whose proxy collection should be resolved
 */
public record ResolveCollectionProxyStep(
    Class<?> injectableType
) implements InjectionStep {
}
