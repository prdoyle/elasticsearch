/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nalbind.injector.spec;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Indicates that a type should be instantiated by calling the given {@link java.lang.invoke.MethodHandle}
 */
public record MethodHandleSpec(
    Class<?> requestedType,
	MethodHandle methodHandle,
	List<Method> reportInjectedMethods
) implements DistinctInstanceSpec { }
