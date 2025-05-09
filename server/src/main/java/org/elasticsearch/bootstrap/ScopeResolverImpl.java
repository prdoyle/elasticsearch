/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.bootstrap;

import org.elasticsearch.entitlement.runtime.policy.FileAccessTree;
import org.elasticsearch.entitlement.runtime.policy.PathLookup;
import org.elasticsearch.entitlement.runtime.policy.Policy;
import org.elasticsearch.entitlement.runtime.policy.PolicyManager;
import org.elasticsearch.entitlement.runtime.policy.PolicyManager.PolicyScope;
import org.elasticsearch.entitlement.runtime.policy.Scope;
import org.elasticsearch.entitlement.runtime.policy.ScopeOracle;
import org.elasticsearch.entitlement.runtime.policy.entitlements.Entitlement;
import org.elasticsearch.entitlement.runtime.policy.entitlements.FilesEntitlement;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.plugins.PluginsLoader;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toUnmodifiableMap;
import static org.elasticsearch.entitlement.runtime.policy.PolicyManager.ALL_UNNAMED;
import static org.elasticsearch.entitlement.runtime.policy.PolicyManager.ComponentKind.APM_AGENT;
import static org.elasticsearch.entitlement.runtime.policy.PolicyManager.ComponentKind.PLUGIN;
import static org.elasticsearch.entitlement.runtime.policy.PolicyManager.ComponentKind.SERVER;
import static org.elasticsearch.entitlement.runtime.policy.PolicyManager.ComponentKind.UNKNOWN;

public class ScopeResolverImpl implements ScopeOracle {
    private static final Logger logger = LogManager.getLogger(ScopeResolverImpl.class);
    private final Map<Module, String> pluginNameByModule;
    private final Map<Module, PolicyManager.ModuleEntitlements> moduleEntitlementsMap = new ConcurrentHashMap<>();
    private final Map<String, List<Entitlement>> serverEntitlements;
    private final List<Entitlement> apmAgentEntitlements;
    private final Map<String, Map<String, List<Entitlement>>> pluginsEntitlements;
    private final PathLookup pathLookup;
    private final Map<String, Path> sourcePaths;


    /**
     * Paths that are only allowed for a single module. Used to generate
     * structures to indicate other modules aren't allowed to use these
     * files in {@link FileAccessTree}s.
     */
    private final List<FileAccessTree.ExclusivePath> exclusivePaths;


    /**
     * The package name containing classes from the APM agent.
     */
    private final String apmAgentPackageName;

    private ScopeResolverImpl(
        Map<Module, String> pluginNameByModule,
        String apmAgentPackageName
    ) {
        this.pluginNameByModule = pluginNameByModule;
        this.apmAgentPackageName = apmAgentPackageName;
        this.serverEntitlements = buildScopeEntitlementsMap(requireNonNull(serverPolicy));
        this.apmAgentEntitlements = apmAgentEntitlements;
        this.pluginsEntitlements = requireNonNull(pluginPolicies).entrySet()
            .stream()
            .collect(toUnmodifiableMap(Map.Entry::getKey, e -> buildScopeEntitlementsMap(e.getValue())));
        this.pathLookup = requireNonNull(pathLookup);
        this.sourcePaths = sourcePaths;
        this.exclusivePaths = exclusivePaths;

        List<FileAccessTree.ExclusiveFileEntitlement> exclusiveFileEntitlements = new ArrayList<>();
        for (var e : serverEntitlements.entrySet()) {
            validateEntitlementsPerModule(SERVER.componentName, e.getKey(), e.getValue(), exclusiveFileEntitlements);
        }
        validateEntitlementsPerModule(APM_AGENT.componentName, ALL_UNNAMED, apmAgentEntitlements, exclusiveFileEntitlements);
        for (var p : pluginsEntitlements.entrySet()) {
            for (var m : p.getValue().entrySet()) {
                validateEntitlementsPerModule(p.getKey(), m.getKey(), m.getValue(), exclusiveFileEntitlements);
            }
        }
        List<FileAccessTree.ExclusivePath> exclusivePaths = FileAccessTree.buildExclusivePathList(
            exclusiveFileEntitlements,
            pathLookup,
            FileAccessTree.DEFAULT_COMPARISON
        );
        FileAccessTree.validateExclusivePaths(exclusivePaths, FileAccessTree.DEFAULT_COMPARISON);
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
                return getModuleScopeEntitlements(
                    serverEntitlements,
                    moduleName,
                    componentName,
                    getComponentPathFromClass(requestingClass)
                );
            }
            case APM_AGENT -> {
                // The APM agent is the only thing running non-modular in the system classloader
                return policyEntitlements(
                    componentName,
                    getComponentPathFromClass(requestingClass),
                    PolicyManager.ALL_UNNAMED,
                    apmAgentEntitlements
                );
            }
            case UNKNOWN -> {
                return defaultEntitlements(componentName, null, moduleName);
            }
            default -> {
                assert policyScope.kind() == PLUGIN;
                var pluginEntitlements = pluginsEntitlements.get(componentName);
                if (pluginEntitlements == null) {
                    return defaultEntitlements(componentName, sourcePaths.get(componentName), moduleName);
                } else {
                    return getModuleScopeEntitlements(pluginEntitlements, moduleName, componentName, sourcePaths.get(componentName));
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

    private PolicyManager.ModuleEntitlements getModuleScopeEntitlements(
        Map<String, List<Entitlement>> scopeEntitlements,
        String scopeName,
        String componentName,
        Path componentPath
    ) {
        var entitlements = scopeEntitlements.get(scopeName);
        if (entitlements == null) {
            return defaultEntitlements(componentName, componentPath, scopeName);
        }
        return policyEntitlements(componentName, componentPath, scopeName, entitlements);
    }

    // pkg private for testing
    static Path getComponentPathFromClass(Class<?> requestingClass) {
        var codeSource = requestingClass.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            return null;
        }
        try {
            return Paths.get(codeSource.getLocation().toURI());
        } catch (Exception e) {
            // If we get a URISyntaxException, or any other Exception due to an invalid URI, we return null to safely skip this location
            logger.info(
                "Cannot get component path for [{}]: [{}] cannot be converted to a valid Path",
                requestingClass.getName(),
                codeSource.getLocation().toString()
            );
            return null;
        }
    }

    PolicyManager.ModuleEntitlements policyEntitlements(String componentName, Path componentPath, String moduleName, List<Entitlement> entitlements) {
        FilesEntitlement filesEntitlement = FilesEntitlement.EMPTY;
        for (Entitlement entitlement : entitlements) {
            if (entitlement instanceof FilesEntitlement) {
                filesEntitlement = (FilesEntitlement) entitlement;
            }
        }
        return new PolicyManager.ModuleEntitlements(
            componentName,
            entitlements.stream().collect(groupingBy(Entitlement::getClass)),
            FileAccessTree.of(componentName, moduleName, filesEntitlement, pathLookup, componentPath, exclusivePaths),
            PolicyManager.getLogger(componentName, moduleName)
        );
    }

    PolicyManager.ModuleEntitlements defaultEntitlements(String componentName, Path componentPath, String moduleName) {
        return new PolicyManager.ModuleEntitlements(componentName, Map.of(), getDefaultFileAccess(componentPath), PolicyManager.getLogger(componentName, moduleName));
    }

    private FileAccessTree getDefaultFileAccess(Path componentPath) {
        return FileAccessTree.withoutExclusivePaths(FilesEntitlement.EMPTY, pathLookup, componentPath);
    }

    private static Map<String, List<Entitlement>> buildScopeEntitlementsMap(Policy policy) {
        return policy.scopes().stream().collect(toUnmodifiableMap(Scope::moduleName, Scope::entitlements));
    }

    private static void validateEntitlementsPerModule(
        String componentName,
        String moduleName,
        List<Entitlement> entitlements,
        List<FileAccessTree.ExclusiveFileEntitlement> exclusiveFileEntitlements
    ) {
        Set<Class<? extends Entitlement>> found = new HashSet<>();
        for (var e : entitlements) {
            if (found.contains(e.getClass())) {
                throw new IllegalArgumentException(
                    "[" + componentName + "] using module [" + moduleName + "] found duplicate entitlement [" + e.getClass().getName() + "]"
                );
            }
            found.add(e.getClass());
            if (e instanceof FilesEntitlement fe) {
                exclusiveFileEntitlements.add(new FileAccessTree.ExclusiveFileEntitlement(componentName, moduleName, fe));
            }
        }
    }

}
