plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")

    // Gradle IntelliJ Plugin 1.x only puts com.intellij.database's top-level jars on the
    // compileOnly classpath (database-plugin.jar, database-plugin-frontend.jar) - it predates
    // this platform's heavily-modularized plugin layout, so the "modules/" split jars where the
    // actual API classes live (LocalDataSource, DatabaseConnectionManager, etc.) are missing.
    // `intellij.ideaDependency` is only populated by the intellij plugin's own task action (not
    // during configuration), so it can't be referenced here at all - a lazy Provider still gets
    // evaluated too early for Gradle's task-dependency analysis. Instead, glob directly into
    // Gradle's own dependency cache for the already-resolved ideaIU SDK: the hash directory
    // segment varies, hence the wildcard, but the platform version is this project's own
    // `platformVersion` property, not machine-specific.
    compileOnly(fileTree(
        "${gradle.gradleUserHomeDir}/caches/modules-2/files-2.1/com.jetbrains.intellij.idea/ideaIU/${providers.gradleProperty("platformVersion").get()}"
    ) {
        include(
            "*/ideaIU-${providers.gradleProperty("platformVersion").get()}/plugins/DatabaseTools/lib/modules/intellij.database.core.impl.jar",
            "*/ideaIU-${providers.gradleProperty("platformVersion").get()}/plugins/DatabaseTools/lib/modules/intellij.database.connectivity.jar",
            "*/ideaIU-${providers.gradleProperty("platformVersion").get()}/plugins/DatabaseTools/lib/modules/intellij.database.impl.jar",
            "*/ideaIU-${providers.gradleProperty("platformVersion").get()}/plugins/DatabaseTools/lib/modules/intellij.database.jdbcConsole.jar"
        )
    })
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

intellij {
    pluginName.set(providers.gradleProperty("pluginName").get())
    version.set(providers.gradleProperty("platformVersion").get())
    type.set(providers.gradleProperty("platformType").get())
    plugins.set(
        providers.gradleProperty("platformPlugins").get()
            .split(",")
            .map(String::trim)
            .filter(String::isNotEmpty)
    )
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }

    patchPluginXml {
        sinceBuild.set(providers.gradleProperty("pluginSinceBuild").get())
        untilBuild.set(providers.gradleProperty("pluginUntilBuild").get())
    }

    buildSearchableOptions {
        enabled = false
    }

    // Disabled because some JVM distributions (e.g. Microsoft JDK) are missing
    // the Packages directory expected by the IntelliJ instrumenting compiler.
    // @NotNull/@Nullable annotations remain enforced at compile-time and by the IDE.
    instrumentCode {
        enabled = false
    }

}
