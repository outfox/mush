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
    from(files("dependencies.json", "THIRD_PARTY_NOTICES.md")) { into("META-INF") }
}

sourceSets {
    main {
        kotlin.srcDir("src/rider/main/kotlin")
        resources.srcDir("src/rider/main/resources")
    }
}

tasks.test {
    // Rider's test classloader does not provide CodeSource locations for fixture classes.
    systemProperty("mush.test.fixtureClasspath", sourceSets.test.get().output.classesDirs.asPath)
}

val testHostCommands = tasks.register("testHostCommands") {
    doLast {
        providers.exec {
            executable("dotnet")
            args("test", "tests/MushHost.Tests/MushHost.Tests.csproj", "--nologo", "-v", "minimal")
            workingDir(rootDir)
        }.result.get()
    }
}

tasks.check { dependsOn(testHostCommands) }

val compileDotNet = tasks.register("compileDotNet") {
    doLast {
        providers.exec {
            executable("dotnet")
            args("build", dotnetSolution, "-c", buildConfiguration, "--nologo", "-v", "q", "/p:HostFullIdentifier=")
            workingDir(rootDir)
        }.result.get()
    }
}

val compileSpikeHost = tasks.register("compileSpikeHost") {
    doLast {
        providers.exec {
            executable("dotnet")
            args("build", "spike/MushHost/MushHost.csproj", "-c", "Debug", "--nologo", "-v", "minimal")
            workingDir(rootDir)
        }.result.get()
    }
}

val testHostViewProtocol = tasks.register("testHostViewProtocol") {
    doLast {
        // Use a separate output directory so checks can run while Rider has the development host open.
        val hostOutput = layout.buildDirectory.dir("host-view-test").get().asFile
        providers.exec {
            executable("dotnet")
            args("build", "spike/MushHost/MushHost.csproj", "-c", "Debug", "-o", hostOutput.absolutePath,
                "--nologo", "-v", "minimal")
            workingDir(rootDir)
        }.result.get()
        providers.exec {
            executable("dotnet")
            args("run", "--project", "spike/harness/ViewProbe", "--no-launch-profile", "--", "--host",
                hostOutput.resolve(if (System.getProperty("os.name").startsWith("Windows")) "MushHost.exe" else "MushHost").absolutePath)
            workingDir(rootDir)
        }.result.get()
    }
}

tasks.check { dependsOn(testHostViewProtocol) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        rider(productVersion) {
            useInstaller = false
        }
        jetbrainsRuntime()
    }
}

tasks.runIde {
    dependsOn(compileSpikeHost)
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
