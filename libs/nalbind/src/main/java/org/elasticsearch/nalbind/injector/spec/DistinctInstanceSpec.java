/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nalbind.injector.spec;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Indicates that this spec describes an injectable object that isn't described
 * by any other spec besides {@link AliasSpec}.
 */
public sealed interface DistinctInstanceSpec extends UnambiguousSpec
    permits MethodHandleSpec, ExistingInstanceSpec {
    List<Method> reportInjectedMethods();
}
