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
 * Creates a JDK dynamic proxy for the given interface type.
 * The proxy delegates to a resolved instance once available;
 * before resolution, all method calls throw
 * {@link org.elasticsearch.injection.api.UnresolvedProxyException}.
 *
 * @param type the interface type to proxy
 */
public record CreateInstanceProxyStep(Class<?> type) implements InjectionStep {}
