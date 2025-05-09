/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.bootstrap;

import org.elasticsearch.entitlement.runtime.policy.PolicyManager;
import org.elasticsearch.entitlement.runtime.policy.PolicyManager.PolicyScope;
import org.elasticsearch.entitlement.runtime.policy.ScopeOracle;
import org.elasticsearch.plugins.PluginsLoader;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.elasticsearch.entitlement.runtime.policy.PolicyManager.ALL_UNNAMED;
import static org.elasticsearch.entitlement.runtime.policy.PolicyManager.ComponentKind.APM_AGENT;
import static org.elasticsearch.entitlement.runtime.policy.PolicyManager.ComponentKind.PLUGIN;
import static org.elasticsearch.entitlement.runtime.policy.PolicyManager.ComponentKind.SERVER;
import static org.elasticsearch.entitlement.runtime.policy.PolicyManager.ComponentKind.UNKNOWN;

public class ScopeResolverImpl implements ScopeOracle {
    private final Map<Module, String> pluginNameByModule;
    private final Map<Module, PolicyManager.ModuleEntitlements> moduleEntitlementsMap = new ConcurrentHashMap<>();


    /**
     * The package name containing classes from the APM agent.
     */
    private final String apmAgentPackageName;

    private ScopeResolverImpl(Map<Module, String> pluginNameByModule, String apmAgentPackageName) {
        this.pluginNameByModule = pluginNameByModule;
        this.apmAgentPackageName = apmAgentPackageName;
    }

    public static ScopeOracle create(Stream<PluginsLoader.PluginLayer> pluginLayers, String apmAgentPackageName) {
        Map<Module, String> pluginNameByModule = new HashMap<>();

        pluginLayers.forEach(pluginLayer -> {
            var pluginName = pluginLayer.pluginBundle().pluginDescriptor().getName();
            if (pluginLayer.pluginModuleLayer() != null && pluginLayer.pluginModuleLayer() != ModuleLayer.boot()) {
                // This plugin is a Java Module
                for (var module : pluginLayer.pluginModuleLayer().modules()) {
                    pluginNameByModule.put(module, pluginName);
                }
            } else {
                // This plugin is not modularized
                pluginNameByModule.put(pluginLayer.pluginClassLoader().getUnnamedModule(), pluginName);
            }
        });

        return new ScopeResolverImpl(pluginNameByModule, apmAgentPackageName);
    }

    @Override
    public PolicyScope resolveClassToScope(Class<?> clazz) {
        var module = clazz.getModule();
        var scopeName = getScopeName(module);
        if (isServerModule(module)) {
            return PolicyScope.server(scopeName);
        }
        String pluginName = pluginNameByModule.get(module);
        if (pluginName != null) {
            return PolicyScope.plugin(pluginName, scopeName);
        }
        if (module.isNamed() == false && clazz.getPackageName().startsWith(apmAgentPackageName)) {
            // The APM agent is the only thing running non-modular in the system classloader
            return PolicyScope.apmAgent(ALL_UNNAMED);
        }
        return PolicyScope.unknown(scopeName);
    }

    @Override
    public boolean isTriviallyAllowed(Class<?> requestingClass) {
        return PolicyManager.isTriviallyAllowedInProd(requestingClass);
    }

    @Override
    public PolicyManager.ModuleEntitlements getEntitlements(Class<?> requestingClass) {
        return moduleEntitlementsMap.computeIfAbsent(requestingClass.getModule(), m -> computeEntitlements(requestingClass));
    }

    private PolicyManager.ModuleEntitlements computeEntitlements(Class<?> requestingClass, PolicyManager policyManager) {
        var policyScope = resolveClassToScope(requestingClass);
        var componentName = policyScope.componentName();
        var moduleName = policyScope.moduleName();

        switch (policyScope.kind()) {
            case SERVER -> {
                return policyManager.getModuleScopeEntitlements(
                    policyManager.serverEntitlements,
                    moduleName,
                    SERVER.componentName,
                    PolicyManager.getComponentPathFromClass(requestingClass)
                );
            }
            case APM_AGENT -> {
                // The APM agent is the only thing running non-modular in the system classloader
                return policyManager.policyEntitlements(
                    APM_AGENT.componentName,
                    PolicyManager.getComponentPathFromClass(requestingClass),
                    PolicyManager.ALL_UNNAMED,
                    policyManager.apmAgentEntitlements
                );
            }
            case UNKNOWN -> {
                return policyManager.defaultEntitlements(UNKNOWN.componentName, null, moduleName);
            }
            default -> {
                assert policyScope.kind() == PLUGIN;
                var pluginEntitlements = policyManager.pluginsEntitlements.get(componentName);
                if (pluginEntitlements == null) {
                    return policyManager.defaultEntitlements(componentName, policyManager.sourcePaths.get(componentName), moduleName);
                } else {
                    return policyManager.getModuleScopeEntitlements(pluginEntitlements, moduleName, componentName, policyManager.sourcePaths.get(componentName));
                }
            }
        }
    }


    private static boolean isServerModule(Module requestingModule) {
        return requestingModule.isNamed() && requestingModule.getLayer() == ModuleLayer.boot();
    }

    public static String getScopeName(Module requestingModule) {
        if (requestingModule.isNamed() == false) {
            return ALL_UNNAMED;
        } else {
            return requestingModule.getName();
        }
    }
}
