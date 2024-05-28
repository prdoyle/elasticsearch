package org.elasticsearch.nalbind.injector;

sealed public interface UnambiguousSpec extends InjectionSpec
	permits ConstructorSpec, AliasSpec
{ }
