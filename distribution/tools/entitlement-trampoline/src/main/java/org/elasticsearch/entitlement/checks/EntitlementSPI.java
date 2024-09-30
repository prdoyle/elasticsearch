/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.checks;

import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;

class EntitlementSPI {
    static final AtomicReference<EntitlementChecks> INSTANCE = new AtomicReference<>();

    static EntitlementChecks loadInstance() {
        ServiceLoader<EntitlementChecks> loader = ServiceLoader.load(EntitlementChecks.class);
        List<EntitlementChecks> instances = loader.stream().map(ServiceLoader.Provider::get).toList();
        if (instances.isEmpty()) {
            throw new IllegalStateException("No implementation of EntitlementChecks available");
        } else if (instances.size() >= 2) {
            throw new IllegalStateException("Multiple implementations of EntitlementChecks available: "
                + instances.stream().map(i -> i.getClass().getSimpleName()).toList());
        }
        return INSTANCE.compareAndExchange(null, instances.get(0));
    }
}
