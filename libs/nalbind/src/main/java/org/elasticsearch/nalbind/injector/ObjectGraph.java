/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nalbind.injector;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;

/**
 * The product of dependency injection: a pool of singleton objects all connected to each other.
 */
public class ObjectGraph {
    private final Map<Class<?>, List<Object>> instances;

    ObjectGraph(Map<Class<?>, List<Object>> instances) {
        this.instances = Map.copyOf(instances);
    }

    public <T> T getInstance(Class<T> type) {
        List<Object> candidates = instances.getOrDefault(type, emptyList());
        if (candidates.size() == 1) {
            return type.cast(candidates.get(0));
        } else {
            throw new IllegalStateException("No unique injectable instance of " + type);
        }
    }

}
