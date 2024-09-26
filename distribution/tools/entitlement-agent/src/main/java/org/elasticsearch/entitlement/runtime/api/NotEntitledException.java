/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.runtime.api;

import java.util.Objects;

public class NotEntitledException extends RuntimeException {
    public static final String CLASS_LOADER_INFO;

    static {
        CLASS_LOADER_INFO = Objects.toString(NotEntitledException.class.getClassLoader());
        System.err.println("!!!!!!!!!!!!!!!!!!! My class loader is " + CLASS_LOADER_INFO);
    }
    public NotEntitledException(String message) {
        super(message);
    }

    public NotEntitledException(String message, Throwable cause) {
        super(message, cause);
    }
}
