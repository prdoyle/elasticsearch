/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.plugins.cli;

import joptsimple.OptionSet;

import org.elasticsearch.cli.ProcessInfo;
import org.elasticsearch.cli.Terminal;
import org.elasticsearch.common.cli.EnvironmentAwareCommand;
import org.elasticsearch.env.Environment;
import org.elasticsearch.plugin.scanner.AutoInjectionScanner;
import org.elasticsearch.plugin.scanner.ClassReaders;
import org.elasticsearch.plugins.PluginDescriptor;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * A command for the plugin cli to install a plugin into elasticsearch.
 * <p>
 * The install command takes a plugin id, which may be any of the following:
 * <ul>
 * <li>An official elasticsearch plugin name</li>
 * <li>Maven coordinates to a plugin zip</li>
 * <li>A URL to a plugin zip</li>
 * </ul>
 * <p>
 * Plugins are packaged as zip files. Each packaged plugin must contain a plugin properties file.
 * See {@link PluginDescriptor}.
 * <p>
 * The installation process first extracts the plugin files into a temporary
 * directory in order to verify the plugin satisfies the following requirements:
 * <ul>
 * <li>Jar hell does not exist, either between the plugin's own jars, or with elasticsearch</li>
 * <li>The plugin is not a module already provided with elasticsearch</li>
 * <li>If the plugin contains extra security permissions, the policy file is validated</li>
 * </ul>
 * <p>
 * A plugin may also contain an optional {@code bin} directory which contains scripts. The
 * scripts will be installed into a subdirectory of the elasticsearch bin directory, using
 * the name of the plugin, and the scripts will be marked executable.
 * <p>
 * A plugin may also contain an optional {@code config} directory which contains configuration
 * files specific to the plugin. The config files be installed into a subdirectory of the
 * elasticsearch config directory, using the name of the plugin. If any files to be installed
 * already exist, they will be skipped.
 */
class ScanAutoInjectablesCommand extends EnvironmentAwareCommand {

    private static final String AUTO_INJECTABLE_FILE_NAME = "auto_injectable.json";

    ScanAutoInjectablesCommand() {
        super("Scan for @AutoInjectable classes");
    }

    @Override
    protected void printAdditionalHelp(Terminal terminal) {
        // TODO
    }

    @Override
    public void execute(Terminal terminal, OptionSet options, Environment env, ProcessInfo processInfo) throws Exception {
        generateAutoInjectableFile(env.pluginsFile().resolve(AUTO_INJECTABLE_FILE_NAME));
    }

    private static void generateAutoInjectableFile(Path outputFile) throws IOException {
        List<ClassReader> classReaders = ClassReaders.ofClassPath(); // TODO: Scan plugins too?
        Collection<String> classNames = AutoInjectionScanner.scanForAutoInjectableClasses(classReaders);
        Files.createDirectories(outputFile.getParent());
        try (OutputStream outputStream = Files.newOutputStream(outputFile)) {
            AutoInjectionScanner.writeTo(outputStream, classNames);
        }
    }

}
