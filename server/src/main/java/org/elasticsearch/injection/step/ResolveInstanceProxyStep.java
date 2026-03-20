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
 * Resolves a proxy previously created by a {@link CreateInstanceProxyStep},
 * pointing it at the real instance of the given type.
 *
 * @param type the interface type whose proxy should be resolved
 */
public record ResolveInstanceProxyStep(Class<?> type) implements InjectionStep {}
