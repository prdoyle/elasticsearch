/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection.spec;

import org.elasticsearch.injection.api.Actual;

import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Captures the pertinent info required to inject one of the arguments of a constructor.
 * @param name is for troubleshooting; it's not strictly needed
 * @param formalType is the declared class of the parameter
 * @param injectableType is the target type of the injection dependency
 * @param modifiers flags that alter how this parameter is injected
 */
public record ParameterSpec(String name, Class<?> formalType, Class<?> injectableType, Set<ParameterModifier> modifiers) {

    public ParameterSpec(String name, Class<?> formalType, Class<?> injectableType) {
        this(name, formalType, injectableType, Set.of());
    }

    public static ParameterSpec from(Parameter parameter) {
        if (parameter.getType() == List.class) {
            Class<?> elementType = extractListElementType(parameter);
            if (elementType != null) {
                Set<ParameterModifier> modifiers = EnumSet.of(ParameterModifier.LIST, ParameterModifier.CAN_BE_PROXIED);
                if (parameter.isAnnotationPresent(Actual.class)) {
                    modifiers.remove(ParameterModifier.CAN_BE_PROXIED);
                }
                return new ParameterSpec(parameter.getName(), parameter.getType(), elementType, Set.copyOf(modifiers));
            }
        }
        return new ParameterSpec(parameter.getName(), parameter.getType(), parameter.getType());
    }

    public boolean canBeProxied() {
        return modifiers.contains(ParameterModifier.CAN_BE_PROXIED);
    }

    public boolean isList() {
        return modifiers.contains(ParameterModifier.LIST);
    }

    private static Class<?> extractListElementType(Parameter parameter) {
        Type genericType = parameter.getParameterizedType();
        if (genericType instanceof ParameterizedType pt) {
            Type[] typeArgs = pt.getActualTypeArguments();
            if (typeArgs.length == 1 && typeArgs[0] instanceof Class<?> elementType) {
                return elementType;
            }
        }
        return null;
    }
}
