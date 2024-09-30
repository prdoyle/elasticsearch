package org.elasticsearch.entitlement.trampoline;

import org.elasticsearch.entitlement.checks.EntitlementChecks;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class EntitlementTrampoline {
    private static final AtomicReference<EntitlementChecks> INSTANCE = new AtomicReference<>();

    public static void setInstance(EntitlementChecks instance) {
        if (false == INSTANCE.compareAndSet(null, instance)) {
            throw new IllegalStateException("Entitlement trampoline already configured");
        }
    }

    public static EntitlementChecks getInstance() {
        return Objects.requireNonNull(INSTANCE.get(), "Entitlement trampoline not configured");
    }

}
