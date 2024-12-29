@file:Suppress("UnstableApiUsage")

import org.checkerframework.gradle.plugin.CheckerFrameworkExtension

/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Java library project to get you started.
 * For more details on building Java & JVM projects, please refer to https://docs.gradle.org/8.10.2/userguide/building_java_projects.html in the Gradle documentation.
 * This project uses @Incubating APIs which are subject to change.
 */

plugins {
  `java-library`
  `jvm-test-suite`
  `maven-publish`
  id("eu.aylett.conventions") version "0.4.0"
  id("eu.aylett.plugins.version") version "0.4.0"
  id("org.checkerframework") version "0.6.45"
  id("com.diffplug.spotless") version "7.0.0.BETA4"
  checkstyle
  id("info.solidsoft.pitest") version "1.15.0"
  id("com.groupcdg.pitest.github") version "1.0.7"
}

version = aylett.versions.gitVersion()

group = "eu.aylett.arc"

repositories {
  // Use Maven Central for resolving dependencies.
  mavenCentral()
}

dependencies {
  api(libs.jspecify)
  api(libs.checkerframework.qual)
  testImplementation(libs.hamcrest)

  checkerFramework(libs.checkerframework)

  pitest("org.slf4j:slf4j-api:2.0.16")
  pitest("ch.qos.logback:logback-classic:1.5.9")
  pitest("com.groupcdg.arcmutate:base:1.2.2")
  pitest("com.groupcdg.pitest:pitest-accelerator-junit5:1.0.6")
  pitest("com.groupcdg:pitest-git-plugin:1.1.4")
  pitest("com.groupcdg.pitest:pitest-kotlin-plugin:1.1.6")
}

testing {
  suites {
    // Configure the built-in test suite
    val test by
        getting(JvmTestSuite::class) {
          // Use JUnit Jupiter test framework
          useJUnitJupiter("5.10.3")
        }
  }
}

aylett { jvm { jvmVersion = 21 } }

configure<CheckerFrameworkExtension> {
  extraJavacArgs =
      listOf(
          // "-AcheckPurityAnnotations",
          "-AconcurrentSemantics",
      )
  checkers =
      listOf(
          "org.checkerframework.checker.nullness.NullnessChecker",
          "org.checkerframework.common.initializedfields.InitializedFieldsChecker",
          "org.checkerframework.checker.lock.LockChecker",
      )
}

spotless {
  java {
    importOrder("", "java|javax|jakarta", "\\#", "\\#java|\\#javax|\\#jakarta").semanticSort()
    removeUnusedImports()
    eclipse().configFile("../config/eclipse-java-formatter.xml")
    formatAnnotations()
  }
  kotlinGradle {
    ktlint()
    ktfmt()
  }
}

checkstyle { toolVersion = "10.21.0" }

tasks.withType(JavaCompile::class) { mustRunAfter(tasks.named("spotlessJavaApply")) }

tasks.named("check").configure { dependsOn(tasks.named("spotlessCheck")) }

val isCI = providers.environmentVariable("CI").isPresent

if (!isCI) {
  tasks.named("spotlessCheck").configure { dependsOn(tasks.named("spotlessApply")) }
}

val historyLocation = projectDir.resolve("build/pitest/history")

pitest {
  targetClasses.add("eu.aylett.*")

  junit5PluginVersion = "1.2.1"
  verbosity = "NO_SPINNER"
  pitestVersion = "1.15.1"
  failWhenNoMutations = false
  mutators = listOf("STRONGER", "EXTENDED")
  timeoutFactor = BigDecimal.TEN

  exportLineCoverage = true
  features.add("+auto_threads")
  if (isCI) {
    // Running in GitHub Actions
    features.addAll("+git(from[HEAD~1])", "+gitci(level[warning])")
    outputFormats = listOf("html", "xml", "gitci")
    failWhenNoMutations = false
  } else {
    historyInputLocation = historyLocation
    historyOutputLocation = historyLocation
    features.addAll("-gitci")
    outputFormats = listOf("html", "xml")
    failWhenNoMutations = true
  }

  jvmArgs.add("--add-opens=java.base/java.lang=ALL-UNNAMED")
}

val pitestReportLocation: Provider<Directory> = project.layout.buildDirectory.dir("reports/pitest")

val printPitestReportLocation by
    tasks.registering {
      val location = pitestReportLocation.map { it.file("index.html") }
      doLast { println("Pitest report: file://${location.get()}") }
    }

tasks.named("pitest").configure { finalizedBy(printPitestReportLocation) }
