import com.jetbrains.plugin.structure.base.utils.isFile
import org.jetbrains.intellij.platform.gradle.Constants

plugins {
    id("java")
    alias(libs.plugins.kotlinJvm)
    id("org.jetbrains.intellij.platform") version "2.19.0" // https://github.com/JetBrains/intellij-platform-gradle-plugin/releases
}

val dotnetSolution = providers.gradleProperty("DotnetSolution").get()
val buildConfiguration = providers.gradleProperty("BuildConfiguration").get()
val productVersion = providers.gradleProperty("ProductVersion").get()
val dotnetPluginId = providers.gradleProperty("DotnetPluginId").get()
val publishToken = providers.gradleProperty("PublishToken").get()

allprojects {
    repositories {
        maven { setUrl("https://cache-redirector.jetbrains.com/maven-central") }
    }
}

repositories {
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
    }
}

version = providers.gradleProperty("PluginVersion").get()

tasks.processResources {
    from("dependencies.json") { into("META-INF") }
}

sourceSets {
    main {
        kotlin.srcDir("src/rider/main/kotlin")
        resources.srcDir("src/rider/main/resources")
    }
}

val compileDotNet = tasks.register("compileDotNet") {
    doLast {
        providers.exec {
            executable("dotnet")
            args("build", dotnetSolution, "-c", buildConfiguration, "--nologo", "-v", "q", "/p:HostFullIdentifier=")
            workingDir(rootDir)
        }.result.get()
    }
}

dependencies {
    intellijPlatform {
        rider(productVersion) {
            useInstaller = false
        }
        jetbrainsRuntime()
    }
}

tasks.runIde {
    // Match Rider's default heap size of 1.5Gb (default for runIde is 512Mb)
    maxHeapSize = "1500m"
    // Spike wiring: the sandbox opens the sample project and embeds the spike host.
    environment("MUSH_HOST_EXE", "${rootDir}/spike/MushHost/bin/Debug/net10.0/MushHost.exe")
    args("${rootDir}/samples/MushSample/MushSample.slnx")
}

tasks.patchPluginXml {
    val changelogText = file("${rootDir}/CHANGELOG.md").readText()
    val changelogMatches = Regex("(?s)(-.+?)(?=##|\$)").findAll(changelogText)

    changeNotes.set(changelogMatches.map {
        it.groups[1]!!.value.replace("(?s)\r?\n".toRegex(), "<br />\n")
    }.take(1).joinToString())
}

tasks.prepareSandbox {
    dependsOn(compileDotNet)

    val outputFolder = "${rootDir}/src/dotnet/${dotnetPluginId}/bin/${dotnetPluginId}.Rider/${buildConfiguration}"
    val dllFiles = listOf(
        "$outputFolder/${dotnetPluginId}.dll",
        "$outputFolder/${dotnetPluginId}.pdb",
    )

    dllFiles.forEach { f ->
        from(file(f)) { into("${rootProject.name}/dotnet") }
    }

    doLast {
        dllFiles.forEach { f ->
            val file = file(f)
            if (!file.exists()) throw RuntimeException("File $file does not exist")
        }
    }
}

tasks.publishPlugin {
    dependsOn(tasks.buildPlugin)
    token.set(publishToken)
}

val riderModel = configurations.create("riderModel") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(riderModel.name, provider {
        intellijPlatform.platformPath.resolve("lib/rd/rider-model.jar").also {
            check(it.isFile) {
                "rider-model.jar is not found at $riderModel"
            }
        }
    }) {
        builtBy(Constants.Tasks.INITIALIZE_INTELLIJ_PLATFORM_PLUGIN)
    }
}
