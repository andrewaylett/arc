@file:Suppress("UnstableApiUsage")

import org.checkerframework.gradle.plugin.CheckerFrameworkExtension

plugins {
  `java-library`
  `jvm-test-suite`
  `maven-publish`
  signing
  id("eu.aylett.conventions") version "0.4.0"
  id("eu.aylett.plugins.version") version "0.4.0"
  id("org.checkerframework") version "0.6.48"
  id("com.diffplug.spotless") version "7.0.0.BETA4"
  checkstyle
  id("info.solidsoft.pitest") version "1.15.0"
  id("com.groupcdg.pitest.github") version "1.0.7"
  id("com.github.spotbugs") version "6.0.27"
}

version = aylett.versions.gitVersion()

group = "eu.aylett.arc"

repositories {
  // Use Maven Central for resolving dependencies.
  mavenCentral()
}

val internal: Configuration by configurations.creating

dependencies {
  implementation(libs.jspecify)
  implementation(libs.checkerframework.qual)
  implementation(libs.spotbugs.annotations)
  testImplementation(libs.hamcrest)

  checkerFramework(libs.checkerframework)

  pitest("org.slf4j:slf4j-api:2.0.16")
  pitest("ch.qos.logback:logback-classic:1.5.15")
  pitest("com.groupcdg.arcmutate:base:1.2.2")
  pitest("com.groupcdg.pitest:pitest-accelerator-junit5:1.0.6")
  pitest("com.groupcdg:pitest-git-plugin:1.1.4")
  pitest("com.groupcdg.pitest:pitest-kotlin-plugin:1.1.6")

  internal("org.junit.jupiter:junit-jupiter:5.11.4")
  internal("org.pitest:pitest-junit5-plugin:1.2.1")
  internal("org.pitest:pitest:1.17.4")
  internal("com.puppycrawl.tools:checkstyle:10.21.1")
}

java {
  withSourcesJar()
  withJavadocJar()
}

testing {
  suites {
    // Configure the built-in test suite
    val test by
        getting(JvmTestSuite::class) {
          // Use JUnit Jupiter test framework
          useJUnitJupiter(internal.dependencies.find { it.group == "org.junit.jupiter" }!!.version)
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

checkstyle {
  toolVersion =
      internal.dependencies
          .find { it.group == "com.puppycrawl.tools" && it.name == "checkstyle" }!!
          .version!!
  maxWarnings = 0
}

tasks.withType(JavaCompile::class) { mustRunAfter(tasks.named("spotlessJavaApply")) }

tasks.named("check").configure { dependsOn(tasks.named("spotlessCheck")) }

val isCI = providers.environmentVariable("CI").isPresent

if (!isCI) {
  tasks.named("spotlessCheck").configure { dependsOn(tasks.named("spotlessApply")) }
}

val historyLocation = projectDir.resolve("build/pitest/history")

pitest {
  targetClasses.add("eu.aylett.*")

  junit5PluginVersion =
      internal.dependencies
          .find { it.group == "org.pitest" && it.name == "pitest-junit5-plugin" }!!
          .version
  verbosity = "NO_SPINNER"
  pitestVersion =
      internal.dependencies.find { it.group == "org.pitest" && it.name == "pitest" }!!.version
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
      group = "verification"
      val location = pitestReportLocation.map { it.file("index.html") }
      doLast { println("Pitest report: file://${location.get()}") }
    }

tasks.named("pitest").configure { finalizedBy(printPitestReportLocation) }

publishing {
  repositories {
    maven {
      name = "GitHubPackages"
      url = uri("https://maven.pkg.github.com/andrewaylett/arc")
      credentials {
        username = System.getenv("GITHUB_ACTOR")
        password = System.getenv("GITHUB_TOKEN")
      }
    }
  }
}

// signing {
//  val signingKey: String? by project
//  val signingPassword: String? by project
//  useInMemoryPgpKeys(signingKey, signingPassword)
//  sign(publishing.publications)
// }
