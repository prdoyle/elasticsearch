package org.elasticsearch.nalbind.injector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Indicates that a type should be instantiated by calling its constructor.
 */
public record ConstructorSpec(
	Constructor<?> constructor,
	List<Method> reportInjectedMethods
) implements UnambiguousSpec {
	@Override
	public Class<?> requestedType() {
		return constructor.getDeclaringClass();
	}
}
