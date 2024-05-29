/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.nalbind.injector.spi;

import org.elasticsearch.core.internal.provider.ProviderLocator;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Set;

public interface ClassFinder {
    Collection<Class<?>> classesOnClasspathWithAnnotation(Class<? extends Annotation> annotation);

    class Holder {

        private Holder() {}

        private static final String PROVIDER_NAME = "nalbind";
        private static final String PROVIDER_MODULE_NAME = "org.elasticsearch.nalbind.impl";
        private static final Set<String> MISSING_MODULES = Set.of("org.ow2.asm");

        public static final ClassFinder CLASS_FINDER = (new ProviderLocator<>(
            PROVIDER_NAME,
            ClassFinder.class,
            PROVIDER_MODULE_NAME,
            MISSING_MODULES
        )).get();
    }
}
