/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.injection.spec;

/**
 * An {@link InjectionSpec} that unambiguously describes how to produce an instance of a type.
 * <p>
 * Contrast with {@link AmbiguousSpec}, which represents a conflict between multiple candidates.
 */
public sealed interface UnambiguousSpec extends InjectionSpec permits SubtypeSpec, ExistingInstanceSpec, MethodHandleSpec {}
