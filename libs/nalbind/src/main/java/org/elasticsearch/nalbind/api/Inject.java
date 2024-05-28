package org.elasticsearch.nalbind.api;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Indicates a constructor to be called in order to instantiate an {@link InjectableSingleton}.
 */
@Target(CONSTRUCTOR)
@Retention(RUNTIME)
public @interface Inject {
}
