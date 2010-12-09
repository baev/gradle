/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.plugins.idea

import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency
import org.gradle.api.specs.Specs
import org.gradle.plugins.idea.model.ModuleLibrary
import org.gradle.plugins.idea.model.Path
import org.gradle.plugins.idea.model.PathFactory
import org.gradle.api.tasks.*
import org.gradle.plugins.idea.model.Module
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.ResolvedConfiguration
import org.gradle.api.artifacts.SelfResolvingDependency
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.Configuration

/**
 * Generates an IDEA module file.
 *
 * @author Hans Dockter
 */
public class IdeaModule extends XmlGeneratorTask<Module> {
    /**
     * The content root directory of the module.
     */
    @InputFiles
    File moduleDir

    /**
     * The dirs containing the production sources.
     */
    @InputFiles
    Set<File> sourceDirs

    /**
     * The dirs containing the test sources.
     */
    @InputFiles
    Set<File> testSourceDirs

    /**
     * The dirs to be excluded by idea.
     */
    @InputFiles
    Set<File> excludeDirs

    /**
     * The idea output dir for the production sources. If {@code null} no entry for output dirs is created.
     */
    @InputFiles @Optional
    File outputDir

    /**
     * The idea output dir for the test sources. If {@code null} no entry for test output dirs is created.
     */
    @InputFiles @Optional
    File testOutputDir

    /**
     * The JDK to use for this module. If this is {@code null} the value of the existing or default ipr XML (inherited)
     * is used. If it is set to <code>inherited</code>, the project SDK is used. Otherwise the SDK for the corresponding
     * value of java version is used for this module
     */
    @Input @Optional
    String javaVersion = org.gradle.plugins.idea.model.Module.INHERITED

    /**
     * Whether to download and add sources associated with the dependency jars.
     */
    @Input
    boolean downloadSources = true

    /**
     * Whether to download and add javadoc associated with the dependency jars.
     */
    @Input
    boolean downloadJavadoc = false

    /**
     * The variables to be used for replacing absolute paths in the iml entries. For example, you might add a
     * {@code GRADLE_USER_HOME} variable to point to the Gradle user home dir.
     */
    Map<String, File> variables = [:]

    /**
     * The keys of this map are the Intellij scopes. Each key points to another map that has two keys, plus and minus.
     * The values of those keys are sets of  {@link org.gradle.api.artifacts.Configuration}  objects. The files of the
     * plus configurations are added minus the files from the minus configurations.
     */
    Map<String, Map<String, Configuration>> scopes = [:]

    @Override protected Module create() {
        return new Module(xmlTransformer, pathFactory)
    }

    @Override protected void configure(Module module) {
        module.configure(getContentPath(), getSourcePaths(), getTestSourcePaths(), getExcludePaths(), getOutputPath(), getTestOutputPath(),
                getDependencies(), javaVersion)
    }

    protected Path getContentPath() {
        getPath(project.projectDir)
    }

    protected Path getOutputPath() {
        getOutputDir() ? getPath(getOutputDir()) : null
    }

    protected Path getTestOutputPath() {
        getTestOutputDir() ? getPath(getTestOutputDir()) : null
    }

    protected Set getSourcePaths() {
        getSourceDirs().collect { getPath(it) }
    }

    protected Set getTestSourcePaths() {
        getTestSourceDirs().collect { getPath(it) }
    }

    protected Set getExcludePaths() {

        getExcludeDirs().collect { getPath(it) }
    }

    protected Set getDependencies() {
        scopes.keySet().inject([] as LinkedHashSet) {result, scope ->
            result + (getModuleLibraries(scope) + getModules(scope))
        }
    }

    protected Set getModules(String scope) {
        if (scopes[scope]) {
            return getScopeDependencies(scopes[scope], { it instanceof ProjectDependency}).collect { ProjectDependency projectDependency ->
                projectDependency.dependencyProject
            }.collect { project ->
                new org.gradle.plugins.idea.model.ModuleDependency(project.name, scope)
            }
        }
        return []
    }

    protected Set getModuleLibraries(String scope) {
        if (scopes[scope]) {
            Set firstLevelDependencies = getScopeDependencies(scopes[scope], { it instanceof ExternalDependency})

            ResolvedConfiguration resolvedConfiguration = project.configurations.detachedConfiguration((firstLevelDependencies as Dependency[])).resolvedConfiguration
            def allResolvedDependencies = getAllDeps(resolvedConfiguration.firstLevelModuleDependencies)

            Set sourceDependencies = getResolvableDependenciesForAllResolvedDependencies(allResolvedDependencies) { dependency ->
                addSourceArtifact(dependency)
            }
            Map sourceFiles = downloadSources ? getFiles(sourceDependencies, "sources") : [:]

            Set javadocDependencies = getResolvableDependenciesForAllResolvedDependencies(allResolvedDependencies) { dependency ->
                addJavadocArtifact(dependency)
            }
            Map javadocFiles = downloadJavadoc ? getFiles(javadocDependencies, "javadoc") : [:]

            List moduleLibraries = resolvedConfiguration.getFiles(Specs.SATISFIES_ALL).collect { File binaryFile ->
                File sourceFile = sourceFiles[binaryFile.name]
                File javadocFile = javadocFiles[binaryFile.name]
                new ModuleLibrary([getPath(binaryFile)] as Set, javadocFile ? [getPath(javadocFile)] as Set : [] as Set, sourceFile ? [getPath(sourceFile)] as Set : [] as Set, [] as Set, scope)
            }
            moduleLibraries.addAll(getSelfResolvingFiles(getScopeDependencies(scopes[scope],
                    { it instanceof SelfResolvingDependency && !(it instanceof ProjectDependency)}), scope))
            return moduleLibraries as LinkedHashSet
        }
        return []
    }

    private def getSelfResolvingFiles(Collection dependencies, String scope) {
        dependencies.inject([] as LinkedHashSet) { result, SelfResolvingDependency selfResolvingDependency ->
            result.addAll(selfResolvingDependency.resolve().collect { File file ->
                new ModuleLibrary([getPath(file)] as Set, [] as Set, [] as Set, [] as Set, scope)
            })
            result
        }
    }

    private Set getScopeDependencies(Map<String, Configuration> configurations, Closure filter) {
        Set firstLevelDependencies = new LinkedHashSet()
        configurations.plus.each { Configuration configuration ->
            firstLevelDependencies.addAll(configuration.getAllDependencies().findAll(filter))
        }
        configurations.minus.each { Configuration configuration ->
            configuration.getAllDependencies().findAll(filter).each { minusDep ->
                // This deals with dependencies that are defined in different scopes with different
                // artifacts. Right now we accept the fact, that in such a situation some artifacts
                // might be duplicated in Idea (they live in different scopes then). 
                if (minusDep instanceof ExternalDependency) {
                    ExternalDependency removeCandidate = firstLevelDependencies.find { it == minusDep }
                    if (removeCandidate && removeCandidate.artifacts == minusDep.artifacts) {
                        firstLevelDependencies.remove(removeCandidate)
                    }
                } else {
                    firstLevelDependencies.remove(minusDep)
                }
            }
        }
        return firstLevelDependencies
    }

    private def getFiles(Set dependencies, String classifier) {
        return project.configurations.detachedConfiguration((dependencies as Dependency[])).files.inject([:]) { result, sourceFile ->
            String key = sourceFile.name.replace("-${classifier}.jar", '.jar')
            result[key] = sourceFile
            result
        }
    }

    private List getResolvableDependenciesForAllResolvedDependencies(Set allResolvedDependencies, Closure configureClosure) {
        return allResolvedDependencies.collect { ResolvedDependency resolvedDependency ->
            def dependency = new DefaultExternalModuleDependency(resolvedDependency.moduleGroup, resolvedDependency.moduleName, resolvedDependency.moduleVersion,
                    resolvedDependency.configuration)
            dependency.transitive = false
            configureClosure.call(dependency)
            dependency
        }
    }

    protected Set getAllDeps(Set deps) {
        Set result = []
        deps.each { ResolvedDependency resolvedDependency ->
            if (resolvedDependency.children) {
                result.addAll(getAllDeps(resolvedDependency.children))
            }
            result.add(resolvedDependency)
        }
        result
    }

    protected def addSourceArtifact(DefaultExternalModuleDependency dependency) {
        dependency.artifact { artifact ->
            artifact.name = dependency.name
            artifact.type = 'source'
            artifact.extension = 'jar'
            artifact.classifier = 'sources'
        }
    }

    protected def addJavadocArtifact(DefaultExternalModuleDependency dependency) {
        dependency.artifact { artifact ->
            artifact.name = dependency.name
            artifact.type = 'javadoc'
            artifact.extension = 'jar'
            artifact.classifier = 'javadoc'
        }
    }

    protected Path getPath(File file) {
        return pathFactory.path(file)
    }

    protected PathFactory getPathFactory() {
        PathFactory factory = new PathFactory()
        factory.addPathVariable('MODULE_DIR', getOutputFile().parentFile)
        variables.each { key, value ->
            factory.addPathVariable(key, value)
        }
        return factory
    }
}