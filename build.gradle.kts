import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")

    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))

        // Unlike the Gradle IntelliJ Plugin 1.x, bundledPlugin/bundledModule here resolve a bundled
        // plugin's FULL module layout (including the "modules/" split jars where DatabaseTools'
        // actual API classes - LocalDataSource, DatabaseConnectionManager, etc. - live), so the
        // hand-rolled fileTree glob into Gradle's dependency cache 1.x needed for this is gone.
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.database")
        bundledModule("com.intellij.modules.json")
        // The "Gherkin" marketplace plugin (marketplace ID 9164, XML ID "gherkin") - provides
        // GherkinFile/GherkinScenario/GherkinStep/GherkinTable PSI classes. Version 262.8665.173 is
        // compatible with any 262.* build (confirmed via the plugin repo's own updates API: since
        // 262.8665, until 262.*), including this project's own platformVersion.
        plugin("gherkin", "262.8665.173")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

intellijPlatform {
    // Also drives the built plugin archive's file name (build/distributions/<projectName>-<version>.zip) -
    // pluginConfiguration.name below only affects the <name> element shown inside the IDE.
    projectName = providers.gradleProperty("pluginName")

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    buildSearchableOptions = false

    // Disabled because some JVM distributions (e.g. Microsoft JDK) are missing
    // the Packages directory expected by the IntelliJ instrumenting compiler.
    // @NotNull/@Nullable annotations remain enforced at compile-time and by the IDE.
    instrumentCode = false
}

tasks {
    withType<JavaCompile> {
        // IU-262.10315.125's platform jars are compiled with class file version 69 (Java 25) -
        // javac needs a JDK at least that new just to *read* them off the compileOnly classpath,
        // regardless of this project's own targetCompatibility below. This forks javac on a JDK 25
        // toolchain Gradle locates itself, without requiring the Gradle daemon's own JVM to be 25
        // (the daemon itself just needs Java 17+ per the IntelliJ Platform Gradle Plugin's own
        // requirement).
        javaCompiler.set(project.javaToolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        })
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }
}
