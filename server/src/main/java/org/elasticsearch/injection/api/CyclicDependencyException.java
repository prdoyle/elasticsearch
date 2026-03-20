/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection.api;

import java.util.List;

/**
 * Thrown when the injector detects a cyclic dependency that cannot be resolved via list proxying.
 */
public class CyclicDependencyException extends InjectionConfigurationException {
    private final List<String> dependencySteps;

    public CyclicDependencyException(String message, List<String> dependencySteps) {
        super(message + ": " + String.join(" -> ", dependencySteps));
        this.dependencySteps = List.copyOf(dependencySteps);
    }

    public List<String> dependencySteps() {
        return dependencySteps;
    }
}
