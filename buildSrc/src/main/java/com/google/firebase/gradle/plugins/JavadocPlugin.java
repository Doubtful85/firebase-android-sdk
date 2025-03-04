// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.firebase.gradle.plugins;

import static com.google.firebase.gradle.plugins.ClosureUtil.closureOf;
import static com.google.firebase.gradle.plugins.ProjectUtilsKt.toBoolean;

import com.android.build.gradle.LibraryExtension;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.external.javadoc.CoreJavadocOptions;
import org.gradle.external.javadoc.StandardJavadocDocletOptions;
import org.jetbrains.dokka.gradle.DokkaAndroidPlugin;
import org.jetbrains.dokka.gradle.DokkaTask;

/**
 * This plugin modifies the java plugin's javadoc task to be firebase friendly. It does the
 * following
 *
 * <ul>
 *   <li>It adds android's source sets to the task's sources.
 *   <li>It changes the classpath to include ANDROID_HOME
 *   <li>Since firebase documents are generated by the doclava doclet, we wire in sensible defaults
 *       to enable building javadocs externally while supporting our internal publishing process.
 *   <li>Kotlin projects use dokka to produce documentation
 *   <li>Projects with `publishJavadoc = false` produce an empty javadoc
 * </ul>
 */
public class JavadocPlugin implements Plugin<Project> {

  private static final String UNFILTERED_API_TXT_BUILD_PATH = "tmp/javadoc/unfiltered-api.txt";

  @Override
  public void apply(Project project) {
    project.afterEvaluate(
        p -> {
          FirebaseLibraryExtension ext =
              project.getExtensions().findByType(FirebaseLibraryExtension.class);
          if (ext == null) {
            project
                .getLogger()
                .lifecycle(
                    "[Doclava] Project {} is not a firebase library, skipping...",
                    project.getPath());
            return;
          }
          @SuppressWarnings("unchecked")
          boolean includeFireEscapeArtifacts =
              toBoolean(
                  ((Map<String, Object>) project.getProperties())
                      .getOrDefault("includeFireEscapeArtifacts", "false"));
          if (!ext.publishJavadoc || !includeFireEscapeArtifacts) {
            applyDummyJavadoc(project);
          } else if (project.getPlugins().hasPlugin("kotlin-android")) {
            applyDokka(project);
          } else {
            applyDoclava(project);
          }
        });
  }

  private static void applyDoclava(Project project) {
    project.getConfigurations().maybeCreate("javadocCustomConfig");
    project.getDependencies().add("javadocCustomConfig", "com.google.doclava:doclava:1.0.6");

    // setting overwrite as java-library adds the javadoc task by default and it does not work
    // with our @hide tags.
    Javadoc generateJavadoc = project.getTasks().create("generateJavadoc", Javadoc.class);
    project.configure(
        generateJavadoc,
        closureOf(
            (Javadoc javadoc) -> {
              project
                  .getTasks()
                  .all(
                      it -> {
                        if (it.getName().equals("assembleRelease")) {
                          javadoc.dependsOn(it);
                        }
                      });
              // Besides third party libraries, firestore depends on the sibling module
              // :immutable-collection,
              // which needs to be in the classpath when javadoc is run
              // Ref:
              // https://stackoverflow.com/questions/41076271/javadoc-generation-error-package-does-not-exist-in-multi-module-project
              if (ProjectUtilsKt.isAndroid(project)) {
                LibraryExtension android =
                    project.getExtensions().getByType(LibraryExtension.class);
                javadoc.setClasspath(
                    javadoc.getClasspath().plus(project.files(android.getBootClasspath())));
                // We include the Timestamp file in common's javadoc source set and remove it from
                // Firestore"s source set
                // This comes with the risks of Timestamp's javadoc and binary releases having
                // disconnected timelines.
                // Given the repeated tedium in dealing with javadoc, this risk seems worth taking
                if ("firebase-common".equals(project.getName())) {
                  Project firestoreProject = project.findProject(":firebase-firestore");
                  javadoc.setSource(
                      android
                          .getSourceSets()
                          .getByName("main")
                          .getJava()
                          .getSourceFiles()
                          .plus(
                              firestoreProject.files(
                                  "src/main/java/com/google/firebase/Timestamp.java")));

                } else if ("firebase-firestore".equals(project.getName())) {
                  javadoc.setSource(
                      android
                          .getSourceSets()
                          .getByName("main")
                          .getJava()
                          .getSourceFiles()
                          .minus(
                              project.files("src/main/java/com/google/firebase/Timestamp.java")));
                } else {
                  javadoc.setSource(
                      android.getSourceSets().getByName("main").getJava().getSrcDirs());
                }
                android
                    .getLibraryVariants()
                    .all(
                        variant -> {
                          if (!"release".equals(variant.getName().toLowerCase())) {
                            return;
                          }
                          javadoc.setClasspath(
                              javadoc
                                  .getClasspath()
                                  .plus(
                                      getJars(variant.getCompileConfiguration())
                                          .plus(getJars(variant.getRuntimeConfiguration()))));

                          // this includes compiled sources which avoids "cannot find symbol" errors
                          javadoc.setClasspath(
                              javadoc
                                  .getClasspath()
                                  .plus(
                                      project.files(variant.getJavaCompile().getDestinationDir())));
                        });
              } else {
                JavaPluginConvention java =
                    project.getConvention().getPlugin(JavaPluginConvention.class);
                javadoc.setSource(java.getSourceSets().getByName("main").getAllJava());
                javadoc.setClasspath(java.getSourceSets().getByName("main").getCompileClasspath());
              }
              ((StandardJavadocDocletOptions) javadoc.getOptions()).noTimestamp(false);

              Configuration javadocClasspath =
                  project.getConfigurations().findByName("javadocClasspath");

              if (javadocClasspath != null) {
                javadoc.setClasspath(javadoc.getClasspath().plus(getJars(javadocClasspath)));
              }

              // Gradle passes the (unsupported) -doctitle option to the doclava doclet.
              // We set the title to null to overcome this issue
              javadoc.setTitle(null);
              javadoc.getOptions().setDoclet("com.google.doclava.Doclava");

              // set book path and project path used in g3 templates
              CoreJavadocOptions options = (CoreJavadocOptions) javadoc.getOptions();
              options
                  .addMultilineMultiValueOption("hdf")
                  .setValue(
                      ImmutableList.of(
                          ImmutableList.of("book.path", "/docs/reference/_book.yaml"),
                          ImmutableList.of("project.path", "/_project.yaml")));

              // root path assumed by docs
              options.addStringOption("toroot", "/docs/reference/android/");

              // TODO(ashwinraghav) : These currently fail because of unknown annotations in
              // Firestore
              // @Documented, @TypeQualifierNickname, @Retention which are relatively minor
              // b/74115412
              // options.addMultilineStringsOption("error").setValue(["103", "104"])

              options.addMultilineStringsOption("warning").setValue(ImmutableList.of("101", "106"));

              // destination for generated toc yaml
              options.addStringOption("yaml", "_toc.yaml");

              if (project.hasProperty("templatedir")) {
                options.addStringOption(
                    "templatedir", project.getProperties().get("templatedir").toString());
              }

              // Custom doclet can be specified in certain scenarios
              if (project.hasProperty("docletpath")) {
                options.setDocletpath(
                    ImmutableList.of(project.file(project.getProperties().get("docletpath"))));
              } else {
                options.setDocletpath(
                    ImmutableList.copyOf(
                        project.getConfigurations().getByName("javadocCustomConfig").getFiles()));
              }

              ImmutableList.Builder<List<String>> federationApiFiles = ImmutableList.builder();
              ImmutableList.Builder<List<String>> federationUrls = ImmutableList.builder();

              if (project.hasProperty("android_api_file")) {
                federationUrls.add(ImmutableList.of("Android", "https://developer.android.com"));
                federationApiFiles.add(
                    ImmutableList.of(
                        "Android", project.getProperties().get("android_api_file").toString()));
              }
              if (project.hasProperty("android_support_library_api_file")) {
                federationUrls.add(
                    ImmutableList.of("Android Support Library", "https://developer.android.com"));
                federationApiFiles.add(
                    ImmutableList.of(
                        "Android Support Library",
                        project
                            .getProperties()
                            .get("android_support_library_api_file")
                            .toString()));
              }
              if (project.hasProperty("google_play_services_api_file")) {
                federationUrls.add(
                    ImmutableList.of(
                        "Google Play services", "https://developers.google.com/android"));
                federationApiFiles.add(
                    ImmutableList.of(
                        "Google Play services",
                        project.getProperties().get("google_play_services_api_file").toString()));
              }
              if (project.hasProperty("tasks_api_file")) {
                federationUrls.add(
                    ImmutableList.of("Tasks", "https://developers.google.com/android"));
                federationApiFiles.add(
                    ImmutableList.of(
                        "Tasks", project.getProperties().get("tasks_api_file").toString()));
              }

              // set federated links for external projects that need to be linked
              options.addMultilineMultiValueOption("federate").setValue(federationUrls.build());
              options
                  .addMultilineMultiValueOption("federationapi")
                  .setValue(federationApiFiles.build());
            }));
    project
        .getTasks()
        .create(
            TasksKt.JAVADOC_TASK_NAME,
            task -> {
              task.dependsOn(generateJavadoc);
              createEmptyApiFile(project);
            });
  }

  private static void applyDokka(Project project) {
    project.apply(ImmutableMap.of("plugin", DokkaAndroidPlugin.class));
    DokkaTask dokka = (DokkaTask) project.getTasks().getByName("dokka");
    dokka.setOutputDirectory(project.getBuildDir() + "/docs/javadoc/reference");
    applyDummyJavadoc(project).dependsOn(dokka);
  }

  private static Task applyDummyJavadoc(Project project) {
    return project
        .getTasks()
        .create(
            TasksKt.JAVADOC_TASK_NAME,
            task -> {
              task.doLast(
                  t -> {
                    createEmptyApiFile(project);
                  });
              task.doLast(t -> project.mkdir(project.getBuildDir() + "/docs/javadoc/reference"));
            });
  }

  private static void createEmptyApiFile(Project project) {
    File dir = project.file(project.getBuildDir() + "/tmp/javadoc");
    project.mkdir(dir);
    try {
      project.file(dir + "/api.txt").createNewFile();
    } catch (IOException e) {
      throw new GradleException("Unable to create file", e);
    }
  }

  private static FileCollection getJars(Configuration configuration) {
    return configuration
        .getIncoming()
        .artifactView(
            view ->
                view.attributes(
                    attrs -> attrs.attribute(Attribute.of("artifactType", String.class), "jar")))
        .getArtifacts()
        .getArtifactFiles();
  }
}
