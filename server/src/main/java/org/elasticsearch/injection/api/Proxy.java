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
 * When applied to a constructor parameter, indicates that
 * the parameter object's identity will never be used (eg. no {@code ==} or {@link System#identityHashCode})
 * and that no methods will be called on the parameter object until after the constructor has returned.
 * <p>
 * These conditions are sufficient to allow the injector to substitute a <em>proxy object</em>,
 * which is useful in situations that would otherwise exhibit circular dependencies.
 */
@Target(PARAMETER)
@Retention(RUNTIME)
public @interface Proxy {
}
