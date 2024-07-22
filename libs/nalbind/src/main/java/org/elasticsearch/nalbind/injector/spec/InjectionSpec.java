/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nalbind.injector.spec;

import org.elasticsearch.nalbind.injector.step.InjectionStep;

/**
 * Describes the means by which an object instance is created for some given type.
 * Differs from {@link InjectionStep} in that:
 *
 * <ul>
 *     <li>
 *         this describes the requirements, while {@link InjectionStep} describes the solution
 *     </li>
 *     <li>
 *         this is declarative, while {@link InjectionStep} is imperative
 *     </li>
 * </ul>
 */
public sealed interface InjectionSpec permits AmbiguousSpec, UnambiguousSpec {
    Class<?> requestedType();
}
