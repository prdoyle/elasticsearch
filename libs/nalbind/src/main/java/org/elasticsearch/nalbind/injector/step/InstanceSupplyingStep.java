/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nalbind.injector.step;

/**
 * An {@link InjectionStep} that causes an object to be available
 * for subsequent steps to look up by a particular requested type.
 * <p>
 * There is no requirement that the object in question be the only one associated
 * with the given type; however, it is invalid to request injection of an object
 * using a type that has more than one object associated with it.
 */
public sealed interface InstanceSupplyingStep extends InjectionStep permits
    InstantiateStep,
    RollUpStep
{
    /**
     * Subsequent steps can find the object by looking it up under this type.
     */
    Class<?> requestedType();
}
