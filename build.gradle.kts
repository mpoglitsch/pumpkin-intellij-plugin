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
