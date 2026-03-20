/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection.api;

/**
 * Thrown when the injector detects a problem during configuration or planning,
 * before any constructor calls are made.
 */
public class InjectionConfigurationException extends IllegalStateException {
    public InjectionConfigurationException(String message) {
        super(message);
    }

    public InjectionConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
