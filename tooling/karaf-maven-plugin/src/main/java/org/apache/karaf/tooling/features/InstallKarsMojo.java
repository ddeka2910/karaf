/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.karaf.tooling.features;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.*;

import org.apache.felix.utils.properties.Properties;
import org.apache.karaf.features.BundleInfo;
import org.apache.karaf.features.Dependency;
import org.apache.karaf.features.internal.model.*;
import org.apache.karaf.kar.internal.Kar;
import org.apache.karaf.tooling.utils.MojoSupport;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

/**
 * Installs kar dependencies into a server-under-construction in target/assembly
 *
 * @goal install-kars
 * @phase process-resources
 * @requiresDependencyResolution runtime
 * @inheritByDefault true
 * @description Install kar dependencies
 */
public class InstallKarsMojo extends MojoSupport {

    /**
     * Base directory used to copy the resources during the build (working directory).
     *
     * @parameter default-value="${project.build.directory}/assembly"
     * @required
     */
    protected String workDirectory;

    /**
     * Features configuration file (etc/org.apache.karaf.features.cfg).
     *
     * @parameter default-value="${project.build.directory}/assembly/etc/org.apache.karaf.features.cfg"
     * @required
     */
    protected File featuresCfgFile;

    /**
     * startup.properties file.
     *
     * @parameter default-value="${project.build.directory}/assembly/etc/startup.properties"
     * @required
     */
    protected File startupPropertiesFile;

    /**
     * default start level for bundles in features that don't specify it.
     *
     * @parameter
     */
    protected int defaultStartLevel = 30;

    /**
     * Directory used during build to construction the Karaf system repository.
     *
     * @parameter default-value="${project.build.directory}/assembly/system"
     * @required
     */
    protected File systemDirectory;

    /**
     * List of features from runtime-scope features xml and kars to be installed into system and listed in startup.properties.
     *
     * @parameter
     */
    private List<String> startupFeatures;

    /**
     * List of features from runtime-scope features xml and kars to be installed into system repo and listed in features service boot features.
     *
     * @parameter
     */
    private List<String> bootFeatures;

    /**
     * List of features from runtime-scope features xml and kars to be installed into system repo and not mentioned elsewhere.
     *
     * @parameter
     */
    private List<String> installedFeatures;

    /**
     * Ignore the dependency attribute (dependency="[true|false]") on bundle
     *
     * @parameter
     */
    protected boolean ignoreDependencyFlag = true;

    private URI system;
    private Properties startupProperties = new Properties();

    // an access layer for available Aether implementation
    protected DependencyHelper dependencyHelper;

    private static final String FEATURES_REPOSITORIES = "featuresRepositories";
    private static final String FEATURES_BOOT = "featuresBoot";

    @SuppressWarnings("deprecation")
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        this.dependencyHelper = DependencyHelperFactory.createDependencyHelper(this.container, this.project, this.mavenSession, getLog());

        getLog().info("Creating system directory");
        systemDirectory.mkdirs();
        system = systemDirectory.toURI();

        if (startupPropertiesFile.exists()) {
            getLog().info("Loading startup.properties");
            try {
                InputStream in = new FileInputStream(startupPropertiesFile);
                try {
                    startupProperties.load(in);
                } finally {
                    in.close();
                }
            } catch (IOException e) {
                throw new MojoFailureException("Could not open existing startup.properties file at " + startupPropertiesFile, e);
            }
        } else {
            getLog().info("Creating startup.properties");
            startupProperties.setHeader(Collections.singletonList("#Bundles to be started on startup, with startlevel"));
            if (!startupPropertiesFile.getParentFile().exists()) {
                startupPropertiesFile.getParentFile().mkdirs();
            }
        }

        Set<String> repositories = new HashSet<String>();
        Map<Feature, Boolean> features = new HashMap<Feature, Boolean>();

        getLog().info("Loading kar and features dependency in compile and runtime scopes");
        Collection<Artifact> dependencies = project.getDependencyArtifacts();
        for (Artifact artifact : dependencies) {
            getLog().info("The startup.properties file is updated using kar and features dependency with a scope different from runtime, or defined in the <startupFeatures/> element");
            boolean addToStartup = !artifact.getScope().equals("runtime");
            if (artifact.getScope().equals("compile") || artifact.getScope().equals("runtime")) {
                if (artifact.getType().equals("kar")) {
                    File karFile = artifact.getFile();
                    getLog().info("Extracting " + artifact.toString() + " kar");
                    try {
                        Kar kar = new Kar(karFile.toURI());
                        kar.extract(new File(system.getPath()), new File(workDirectory));
                        for (URI repositoryUri : kar.getFeatureRepos()) {
                            resolveRepository(repositoryUri.getPath(), repositories, features, true, addToStartup);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Can not install " + artifact.toString() + " kar", e);
                    }
                } else {
                    if (artifact.getClassifier() != null && artifact.getClassifier().equals("features")) {
                        getLog().info("Resolving " + artifact.toString() + " features repository");
                        String repositoryUri = dependencyHelper.artifactToMvn(artifact);
                        try {
                            resolveRepository(repositoryUri, repositories, features, true, addToStartup);
                        } catch (Exception e) {
                            throw new MojoFailureException("Can not install " + artifact.toString() + " features repository", e);
                        }
                    }
                }
            }
        }

        // install features/bundles
        for (Feature feature : features.keySet()) {
            getLog().info("Install " + feature.getName() + " feature");
            try {
                if (features.get(feature) || (startupFeatures != null && startupFeatures.contains(feature.getName()))) {
                    // the feature is a startup feature, updating startup.properties file
                    getLog().info("= Feature " + feature.getName() + " is defined as a startup feature");
                    getLog().info("= Updating startup.properties file");
                    List<String> comment = Arrays.asList(new String[]{"", "# feature: " + feature.getName() + " version: " + feature.getVersion()});
                    for (BundleInfo bundleInfo : feature.getBundles()) {
                        String bundleLocation = bundleInfo.getLocation();
                        String bundleStartLevel = Integer.toString(bundleInfo.getStartLevel() == 0 ? defaultStartLevel : bundleInfo.getStartLevel());
                        if (startupProperties.containsKey(bundleLocation)) {
                            int oldStartLevel = Integer.decode((String) startupProperties.get(bundleLocation));
                            if (oldStartLevel > bundleInfo.getStartLevel()) {
                                startupProperties.put(bundleLocation, bundleStartLevel);
                            }
                        } else {
                            if (comment == null) {
                                startupProperties.put(bundleLocation, bundleStartLevel);
                            } else {
                                startupProperties.put(bundleLocation, comment, bundleStartLevel);
                                comment = null;
                            }
                        }
                    }
                    // add the feature in the system folder
                    resolveFeature(feature, features);
                } else if (bootFeatures != null && bootFeatures.contains(feature.getName())) {
                    // the feature is a boot feature, updating the etc/org.apache.karaf.features.cfg file
                    getLog().info("= Feature " + feature.getName() + " is defined as a boot feature");
                    if (featuresCfgFile.exists()) {
                        getLog().info("= Updating " + featuresCfgFile.getPath());
                        Properties featuresProperties = new Properties();
                        InputStream in = new FileInputStream(featuresCfgFile);
                        try {
                            featuresProperties.load(in);
                        } finally {
                            in.close();
                        }
                        String featuresBoot = featuresProperties.getProperty(FEATURES_BOOT);
                        featuresBoot = featuresBoot != null && featuresBoot.length() > 0 ? featuresBoot + "," : "";
                        if (!featuresBoot.contains(feature.getName())) {
                            featuresBoot = featuresBoot + feature.getName();
                            featuresProperties.put(FEATURES_BOOT, featuresBoot);
                            featuresProperties.save(featuresCfgFile);
                        }
                    }
                    // add the feature in the system folder
                    resolveFeature(feature, features);
                } else if (installedFeatures != null && installedFeatures.contains(feature.getName())) {
                    getLog().info("= Feature " + feature.getName() + " is defined as a installed feature");
                    // add the feature in the system folder
                    resolveFeature(feature, features);
                } else {
                    getLog().debug("= Feature " + feature.getName() + " is not installed");
                }
            } catch (Exception e) {
                throw new MojoFailureException("Can not install " + feature.getName() + " feature", e);
            }
        }

        // install bundles defined in startup.properties
        getLog().info("Installing bundles defined in startup.properties in the system");
        Set<?> startupBundles = startupProperties.keySet();
        for (Object startupBundle : startupBundles) {
            String bundlePath = this.dependencyHelper.pathFromMaven((String) startupBundle);
            File bundleFile = new File(system.resolve(bundlePath));
            if (!bundleFile.exists()) {
                File bundleSource = this.dependencyHelper.resolveById((String) startupBundle, getLog());
                bundleFile.getParentFile().mkdirs();
                copy(bundleSource, bundleFile);
            }
        }

        // generate the startup.properties file
        getLog().info("Generating the startup.properties file");
        try {
            OutputStream out = new FileOutputStream(startupPropertiesFile);
            try {
                startupProperties.save(out);
            } finally {
                out.close();
            }
        } catch (IOException e) {
            throw new MojoFailureException("Can not write " + startupPropertiesFile, e);
        }
    }

    private void resolveRepository(String repository, Set<String> repositories, Map<Feature, Boolean> features, boolean updateFeaturesCfgFile, boolean updateStartupProperties) throws Exception {
        // check if the repository has not been processed
        if (repositories.contains(repository)) {
            return;
        }
        repositories.add(repository);
        // update etc/org.apache.karaf.features.cfg file
        if (updateFeaturesCfgFile && featuresCfgFile.exists()) {
            Properties featuresProperties = new Properties();
            InputStream in = new FileInputStream(featuresCfgFile);
            try {
                featuresProperties.load(in);
            } finally {
                in.close();
            }
            String featureRepos = featuresProperties.getProperty(FEATURES_REPOSITORIES);
            featureRepos = featureRepos != null && featureRepos.length() > 0 ? featureRepos + "," : "";
            if (!featureRepos.contains(repository)) {
                featureRepos = featureRepos + repository;
                featuresProperties.put(FEATURES_REPOSITORIES, featureRepos);
                featuresProperties.save(featuresCfgFile);
            }
        }
        // resolving repository location
        File repositoryFile;
        if (repository.startsWith("mvn")) {
            repositoryFile = dependencyHelper.resolveById(repository, getLog());
            repository = dependencyHelper.pathFromMaven(repository);
        } else {
            repositoryFile = new File(repository);
        }
        // copy the repository file in system folder
        File repositoryFileInSystemFolder = new File(system.resolve(repository));
        if (!repositoryFileInSystemFolder.exists()) {
            repositoryFileInSystemFolder.getParentFile().mkdirs();
            copy(repositoryFile, repositoryFileInSystemFolder);
            // add metadata for snapshot
            if (repository.startsWith("mvn")) {
                Artifact repositoryArtifact = dependencyHelper.mvnToArtifact(repository);
                if (repositoryArtifact.isSnapshot()) {
                    File metadataTarget = new File(repositoryFileInSystemFolder.getParentFile(), "maven-metadata-local.xml");
                    try {
                        MavenUtil.generateMavenMetadata(repositoryArtifact, metadataTarget);
                    } catch (Exception e) {
                        getLog().warn("Could not create maven-metadata-local.xml", e);
                        getLog().warn("It means that this SNAPSHOT could be overwritten by an older one present on remote repositories");
                    }
                }
            }
        }
        // loading the model
        Features featuresModel = JaxbUtil.unmarshal(new FileInputStream(repositoryFile), false);
        // recursively process the inner repositories
        for (String innerRepository : featuresModel.getRepository()) {
            resolveRepository(innerRepository, repositories, features, false, updateStartupProperties);
        }
        // update features
        for (Feature feature : featuresModel.getFeature()) {
            features.put(feature, updateStartupProperties);
        }
    }

    private void resolveFeature(Feature feature, Map<Feature, Boolean> features) throws Exception {
        for (Dependency dependency : feature.getFeature()) {
            for (Feature f : features.keySet()) {
                if (f.getName().equals(dependency.getName())) {
                    resolveFeature(f, features);
                }
            }
        }

        getLog().info("Resolving feature " + feature.getName());

        // installing feature bundles
        getLog().info("= Installing bundles from " + feature.getName() + " feature");
        for (Bundle bundle : feature.getBundle()) {
            if (!ignoreDependencyFlag && bundle.isDependency()) {
                getLog().warn("== Bundle " + bundle.getLocation() + " is defined as dependency, so it's not installed");
            } else {
                getLog().info("== Installing bundle " + bundle.getLocation());
                String bundleLocation = bundle.getLocation();
                // cleanup prefixes
                if (bundleLocation.startsWith("wrap:")) {
                    bundleLocation = bundleLocation.substring("wrap:".length());
                }
                if (bundleLocation.startsWith("blueprint:")) {
                    bundleLocation = bundleLocation.substring("blueprint:".length());
                }
                if (bundleLocation.startsWith("webbundle:")) {
                    bundleLocation = bundleLocation.substring("webbundle:".length());
                }
                if (bundleLocation.startsWith("war:")) {
                    bundleLocation = bundleLocation.substring("war:".length());
                }
                File bundleFile;
                if (bundleLocation.startsWith("mvn:")) {
                    bundleFile = dependencyHelper.resolveById(bundleLocation, getLog());
                    bundleLocation = dependencyHelper.pathFromMaven(bundleLocation);
                } else {
                    bundleFile = new File(new URI(bundleLocation));
                }
                File bundleSystemFile = new File(system.resolve(bundleLocation));
                copy(bundleFile, bundleSystemFile);
                // add metadata for snapshot
                if (bundleLocation.startsWith("mvn")) {
                    Artifact bundleArtifact = dependencyHelper.mvnToArtifact(bundleLocation);
                    if (bundleArtifact.isSnapshot()) {
                        File metadataTarget = new File(bundleSystemFile.getParentFile(), "maven-metadata-local.xml");
                        try {
                            MavenUtil.generateMavenMetadata(bundleArtifact, metadataTarget);
                        } catch (Exception e) {
                            getLog().warn("Could not create maven-metadata-local.xml", e);
                            getLog().warn("It means that this SNAPSHOT could be overwritten by an older one present on remote repositories");
                        }
                    }
                }
            }
        }

        // installing feature config files
        getLog().info("= Installing configuration files from " + feature.getName() + " feature");
        for (ConfigFile configFile : feature.getConfigfile()) {
            getLog().warn("== Installing configuration file " + configFile.getLocation());
            String configFileLocation = configFile.getLocation();
            File configFileFile;
            if (configFileLocation.startsWith("mvn:")) {
                configFileFile = dependencyHelper.resolveById(configFileLocation, getLog());
                configFileLocation = dependencyHelper.pathFromMaven(configFileLocation);
            } else {
                configFileFile = new File(new URI(configFileLocation));
            }
            File configFileSystemFile = new File(system.resolve(configFileLocation));
            copy(configFileFile, configFileSystemFile);
            // add metadata for snapshot
            if (configFileLocation.startsWith("mvn")) {
                Artifact configFileArtifact = dependencyHelper.mvnToArtifact(configFileLocation);
                if (configFileArtifact.isSnapshot()) {
                    File metadataTarget = new File(configFileSystemFile.getParentFile(), "maven-metadata-local.xml");
                    try {
                        MavenUtil.generateMavenMetadata(configFileArtifact, metadataTarget);
                    } catch (Exception e) {
                        getLog().warn("Could not create maven-metadata-local.xml", e);
                        getLog().warn("It means that this SNAPSHOT could be overwritten by an older one present on remote repositories");
                    }
                }
            }
        }
    }

}
