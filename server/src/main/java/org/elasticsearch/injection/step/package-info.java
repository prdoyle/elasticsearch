/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

/**
 * Objects that describe one operation to be performed by the <code>PlanInterpreter</code>.
 * Injection is achieved by executing the steps in order.
 * <p>
 * Step types:
 * <ul>
 *     <li>{@link org.elasticsearch.injection.step.InstantiateStep} — construct a new object via a MethodHandle</li>
 *     <li>{@link org.elasticsearch.injection.step.RollupStep} — make subtype instances available as a supertype</li>
 *     <li>{@link org.elasticsearch.injection.step.CreateListProxyStep} — create a proxy list placeholder</li>
 *     <li>{@link org.elasticsearch.injection.step.ResolveListProxyStep} — populate a proxy list with actual instances</li>
 * </ul>
 * <p>
 * See <code>PlanInterpreter</code> for more details on the execution model.
 */
package org.elasticsearch.injection.step;
