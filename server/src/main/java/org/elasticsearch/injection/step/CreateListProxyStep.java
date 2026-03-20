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
 * Creates a proxy {@link java.util.List} for the given element type.
 * The proxy is an empty list placeholder that will be populated later
 * by a corresponding {@link ResolveListProxyStep}.
 *
 * @param elementType the type of element in the list
 */
public record CreateListProxyStep(Class<?> elementType) implements InjectionStep {}
