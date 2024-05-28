package org.elasticsearch.nalbind.api;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks an injected parameter to indicate that methods will be called on that object.
 * Establishes an initialization ordering dependency: the injected object
 * must be initialized first.
 * <p>
 * Without this annotation, the injected object may actually be a proxy,
 * to be initialized later.
 * The upside is that there's no problem with circular dependencies,
 * but the downside is that methods cannot be called on the injected object.
 */
@Target(PARAMETER)
@Retention(RUNTIME)
public @interface Now {
}
