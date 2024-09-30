/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

import org.elasticsearch.entitlement.runtime.checks.EntitlementChecksImpl;

module org.elasticsearch.entitlement.runtime {
    requires org.elasticsearch.base;
    requires org.elasticsearch.entitlement.trampoline;

    exports org.elasticsearch.entitlement.runtime.api to org.elasticsearch.entitlement.agent;
    exports org.elasticsearch.entitlement.runtime.checks to org.elasticsearch.entitlement.trampoline, org.elasticsearch.entitlement.agent;

    provides org.elasticsearch.entitlement.checks.EntitlementChecks with EntitlementChecksImpl;
}
