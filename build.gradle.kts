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
val hostExecutableName = if (System.getProperty("os.name").startsWith("Windows")) "MushHost.exe" else "MushHost"
// The sample is a runIde fixture. Mush's own host can reference any Godot game project; no game-local host is needed.
val mushGameProject = providers.gradleProperty("MushGameProject")
    .map { file(it) }.getOrElse(file("samples/MushSample/MushSample.csproj"))
val jnaArch = if (System.getProperty("os.arch") == "aarch64") "aarch64" else "amd64"
// Task actions cannot reach the build script under the configuration cache; this provider resolves when it is stored.
val jnaBootLibraryPath = provider { intellijPlatform.platformPath.resolve("lib/jna/$jnaArch").toString() }

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

tasks.register<JavaExec>("testLinuxEmbedding") {
    group = "verification"
    description = "Exercise native embedding on Linux with an X11/XWayland display, including a suspended host."
    dependsOn(tasks.testClasses)
    // JavaExec has no Rider test classloader to supply the platform's bundled Kotlin and JNA libraries.
    classpath = sourceSets.test.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    mainClass.set("dev.twodog.mush.embed.X11EmbeddingProbe")
    providers.gradleProperty("MushTestHost").orNull?.let { args("--host", it) }
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val jnaPath = jnaBootLibraryPath
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Djna.boot.library.path=${jnaPath.get()}") })
    onlyIf { System.getProperty("os.name") == "Linux" }
}

val compileLinuxGpuNative = tasks.register<Exec>("compileLinuxGpuNative") {
    group = "build"
    description = "Build the experimental Linux EGL GPU importer (requires EGL/GLES/Vulkan development headers)."
    onlyIf { System.getProperty("os.name") == "Linux" }
    executable("sh")
    args("spike/native/build.sh", layout.buildDirectory.dir("native").get().asFile.absolutePath)
    workingDir(rootDir)
}

val compileGpuShareProbe = tasks.register<Exec>("compileGpuShareProbe") {
    onlyIf { System.getProperty("os.name") == "Linux" }
    executable("dotnet")
    args("build", "spike/harness/GpuShareProbe", "--nologo", "-v", "minimal")
    workingDir(rootDir)
}

tasks.register<JavaExec>("testLinuxGpuSharing") {
    group = "verification"
    description = "Test actual Godot Vulkan memory in a second process's EGL importer, including frame leases and resizing."
    dependsOn(tasks.testClasses, compileLinuxGpuNative, compileGpuShareProbe)
    // This standalone probe only needs our classes plus Rider's bundled Kotlin/JNA. Including the entire platform
    // compile classpath also fingerprints Rider's multi-gigabyte distribution archive for every cold probe build.
    classpath = files(sourceSets.main.get().output, sourceSets.test.get().output,
        provider { intellijPlatform.platformPath.resolve("lib/util-8.jar") })
    mainClass.set("dev.twodog.mush.embed.GpuSharingProbe")
    args(file("spike/harness/GpuShareProbe/bin/Debug/net10.0/GpuShareProbe").absolutePath)
    if (providers.gradleProperty("MushWaylandGpuUi").orNull == "true") {
        executable(file(providers.gradleProperty("MushJbrHome").get()).resolve("bin/java").absolutePath)
        args("--wayland")
        jvmArgs("-Dawt.toolkit.name=WLToolkit", "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED", "--add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED")
    } else javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val jnaPath = jnaBootLibraryPath
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Djna.boot.library.path=${jnaPath.get()}") })
    val nativeDir = layout.buildDirectory.dir("native").get().asFile.absolutePath
    systemProperty("jna.library.path", nativeDir)
    environment("LD_LIBRARY_PATH", nativeDir + ":" + (System.getenv("LD_LIBRARY_PATH") ?: ""))
    onlyIf { System.getProperty("os.name") == "Linux" }
}

val testHostCommands = tasks.register<Exec>("testHostCommands") {
    executable("dotnet")
    args("test", "tests/MushHost.Tests/MushHost.Tests.csproj", "--nologo", "-v", "minimal")
    workingDir(rootDir)
}

tasks.check { dependsOn(testHostCommands) }

val compileDotNet = tasks.register<Exec>("compileDotNet") {
    executable("dotnet")
    args("build", dotnetSolution, "-c", buildConfiguration, "--nologo", "-v", "q", "/p:HostFullIdentifier=")
    workingDir(rootDir)
}

val compileSpikeHost = tasks.register<Exec>("compileSpikeHost") {
    executable("dotnet")
    args("build", "spike/MushHost/MushHost.csproj", "-c", "Debug", "--nologo", "-v", "minimal",
        "/p:MushGameProject=${mushGameProject.absolutePath}")
    workingDir(rootDir)
}

// Use a separate output directory so checks can run while Rider has the development host open.
val hostViewTestOutput = layout.buildDirectory.dir("host-view-test").get().asFile

val compileHostViewTestHost = tasks.register<Exec>("compileHostViewTestHost") {
    executable("dotnet")
    // 2dog's native/tool copy targets concatenate OutputPath and filenames, so keep its trailing separator.
    args("build", "spike/MushHost/MushHost.csproj", "-c", "Debug", "-o", hostViewTestOutput.absolutePath + "/",
        "--nologo", "-v", "minimal")
    workingDir(rootDir)
}

val testHostViewProtocol = tasks.register<Exec>("testHostViewProtocol") {
    dependsOn(compileHostViewTestHost)
    executable("dotnet")
    args("run", "--project", "spike/harness/ViewProbe", "--no-launch-profile", "--", "--host",
        hostViewTestOutput.resolve(hostExecutableName).absolutePath)
    workingDir(rootDir)
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
    // Spike wiring: the sandbox opens the selected game and embeds Mush's own host.
    environment("MUSH_HOST_EXE", "${rootDir}/spike/MushHost/bin/Debug/net10.0/$hostExecutableName")
    args(mushGameProject.absolutePath)
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
    ).map(::file)
    val pluginDotnetDir = "${rootProject.name}/dotnet"

    dllFiles.forEach { f ->
        from(f) { into(pluginDotnetDir) }
    }

    doLast {
        dllFiles.forEach { f ->
            if (!f.exists()) throw RuntimeException("File $f does not exist")
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
