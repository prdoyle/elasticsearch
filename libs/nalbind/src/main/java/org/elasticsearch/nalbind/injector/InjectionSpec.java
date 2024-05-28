package org.elasticsearch.nalbind.injector;

/**
 * Describes the means by which an object instance is created for some given type.
 */
public sealed interface InjectionSpec
	permits AmbiguousSpec, UnambiguousSpec
{
	Class<?> requestedType();
}
