/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection.spec;

/**
 * Modifiers that alter how a constructor parameter is injected.
 */
public enum ParameterModifier {
    /**
     * The object being injected is a {@link java.util.List} of the injectable type,
     * rather than an individual instance.
     */
    LIST,

    /**
     * The object can be a proxy because its methods won't be called during construction.
     * This allows the injector to break circular dependencies by providing a proxy list
     * that is populated after all constructors have finished.
     */
    CAN_BE_PROXIED,
}
