package org.elasticsearch.nalbind.injector.runtime;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

public class ProxyRuntime {
    public static final Map<String, MutableCallSite> callSites = new ConcurrentHashMap<>();
    public static final AtomicInteger numCallSites = new AtomicInteger(0);

    public static void notYetSet() {
        throw new IllegalStateException(
            "Cannot invoke method on object that is not fully constructed. " +
                "Use the @Now annotation on your method's parameter to indicate that you need to call a method on it");
    }

    @SuppressWarnings("unused")
    public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type) {
        return requireNonNull(callSites.remove(name), ()->"CallSite not found: \"" + name + "\"");
    }

}
