/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection.api;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * When applied to a {@code List<T>} parameter, indicates that the injector must provide
 * the actual, fully-populated list rather than a proxy.
 * <p>
 * By default, {@code List<T>} parameters receive a proxy list that is populated after
 * all constructors have finished. This allows circular dependencies to be broken:
 * a class can accept a {@code List<T>} where one of the {@code T} implementations
 * depends on this very class.
 * <p>
 * Using {@code @Actual} opts out of proxying, creating a hard ordering constraint:
 * all implementations of {@code T} must be instantiated before this constructor is called.
 * If that ordering is impossible (due to a cycle), the injector will report it as a
 * {@link CyclicDependencyException}.
 */
@Target(PARAMETER)
@Retention(RUNTIME)
public @interface Actual {}
