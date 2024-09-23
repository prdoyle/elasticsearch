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
 * Creates a new collection to hold elements of the given {@code injectableType}
 * and adds it to the {@link ProxyPool}, making it available for injection.
 * Like any step that makes an object available for injection,
 * this step must run before any step that requires the resulting collection object.
 *
 * <p>
 * The collection is initially in an unusable state: it will throw an exception
 * if any attempt is made to access the collection's contents
 * until the collection is {@linkplain ResolveCollectionProxyStep <em>resolved</em>}.
 * In particular, constructors with a collection as a parameter
 * must not attempt to access the collection's contents.
 *
 * @param injectableType the type for which we should create a proxy collection.
 * @see ResolveCollectionProxyStep
 */
public record CreateCollectionProxyStep(
    Class<?> injectableType
) implements InjectionStep {
}
