/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.workers.WorkerExecutor;

import java.io.File;

import javax.inject.Inject;

public abstract class AutoInjectionTask extends DefaultTask {

    private static final String GENERATED_RESOURCES_DIR = "generated-resources/";
    private static final String AUTO_INJECTABLES_FILE = "auto_injectable.json";
    private static final String AUTO_INJECTABLES_PATH = GENERATED_RESOURCES_DIR + AUTO_INJECTABLES_FILE;

    private final WorkerExecutor workerExecutor;
    private FileCollection scannerClasspath;
    private FileCollection classpath;
    private ExecOperations execOperations;
    private ProjectLayout projectLayout;

    @Inject
    public AutoInjectionTask(WorkerExecutor workerExecutor, ExecOperations execOperations, ProjectLayout projectLayout) {
        this.workerExecutor = workerExecutor;
        this.execOperations = execOperations;
        this.projectLayout = projectLayout;

        getOutputFile().convention(projectLayout.getBuildDirectory().file(AUTO_INJECTABLES_PATH));
    }

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @CompileClasspath
    public FileCollection getClasspath() {
        return classpath.filter(File::exists);
    }

    public void setClasspath(FileCollection classpath) {
        this.classpath = classpath;
    }

    public void setScannerClasspath(FileCollection scannerClasspath) {
        this.scannerClasspath = scannerClasspath;
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileCollection getScannerClasspath() {
        return scannerClasspath;
    }

    @TaskAction
    public void scan() {
        getLogger().info("Scanning for auto injection classes");
        File outputFile = projectLayout.getBuildDirectory().file(AUTO_INJECTABLES_PATH).get().getAsFile();

        ExecResult execResult = LoggedExec.javaexec(execOperations, spec -> {
            spec.classpath(scannerClasspath.plus(getClasspath()).getAsPath());
            spec.getMainClass().set("org.elasticsearch.plugin.scanner.AutoInjectionScanner");
            spec.args(outputFile);
            spec.setErrorOutput(System.err);
            spec.setStandardOutput(System.out);
        });
        execResult.assertNormalExitValue();
    }
}
