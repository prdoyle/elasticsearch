/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection.spec;

import org.elasticsearch.injection.api.Proxy;

import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

import static java.util.Collections.unmodifiableSet;
import static org.elasticsearch.injection.spec.ParameterModifier.CAN_BE_PROXIED;
import static org.elasticsearch.injection.spec.ParameterModifier.COLLECTION;

/**
 * Captures the pertinent info required to inject one of the arguments of a constructor.
 * @param name for troubleshooting; it's not strictly needed
 * @param formalType the declared class of the parameter
 * @param injectableType the target type of the injection dependency
 * @param modifiers flags that specify particular variations on injection behaviour
 */
public record ParameterSpec(
    String name,
    Class<?> formalType,
    Class<?> injectableType,
    Set<ParameterModifier> modifiers
) {
    public static ParameterSpec from(Parameter parameter) {
        var modifiers = EnumSet.noneOf(ParameterModifier.class);
        Class<?> injectableType;
        if (parameter.isAnnotationPresent(Proxy.class)) {
            modifiers.add(CAN_BE_PROXIED);
        }
        if (Iterable.class.isAssignableFrom(parameter.getType()) && parameter.getType().isAssignableFrom(Collection.class)) {
            modifiers.add(COLLECTION);
            modifiers.add(CAN_BE_PROXIED);
            var pt = (ParameterizedType) parameter.getParameterizedType();
            injectableType = rawClass(pt.getActualTypeArguments()[0]);
        } else {
            injectableType = parameter.getType();
        }
        return new ParameterSpec(parameter.getName(), parameter.getType(), injectableType, unmodifiableSet(modifiers));
    }

    public static Class<?> rawClass(Type sourceType) {
        if (sourceType instanceof ParameterizedType pt) {
            return (Class<?>)pt.getRawType();
        } else {
            return (Class<?>)sourceType;
        }
    }

    public boolean canBeProxied() {
        return modifiers.contains(CAN_BE_PROXIED);
    }
}
