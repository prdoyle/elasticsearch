/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection.spec;

import java.util.Collection;

public enum ParameterModifier {
    /**
     * The object being injected is a {@link Collection} of the injectable type,
     * rather than an individual instance.
     */
    COLLECTION,

    /**
     * The object can be a proxy because its methods won't be called.
     */
    CAN_BE_PROXIED,
}
