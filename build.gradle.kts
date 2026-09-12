import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile

plugins {
	id("net.fabricmc.fabric-loom")
	id("com.diffplug.spotless") version "8.8.0"
	id("me.champeau.jmh") version "0.7.3" apply false
	`maven-publish`
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

repositories {
	mavenCentral()
	maven("https://jitpack.io")
}

val aidsfuscatorRuntime by configurations.creating {
	isCanBeConsumed = false
	isCanBeResolved = true
}

dependencies {
	val imguiVersion = "1.92.0"
	val lombokVersion = "1.18.46"
	implementation(project(":eventbus"))
	include(project(":eventbus"))
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
	implementation(include("io.github.spair:imgui-java-binding:$imguiVersion")!!)
	implementation(include("io.github.spair:imgui-java-lwjgl3:$imguiVersion")!!)
	runtimeOnly(include("io.github.spair:imgui-java-natives-windows:$imguiVersion")!!)
	runtimeOnly(include("io.github.spair:imgui-java-natives-linux:$imguiVersion")!!)
	runtimeOnly(include("io.github.spair:imgui-java-natives-macos:$imguiVersion")!!)
	implementation(include("com.googlecode.soundlibs:jlayer:1.0.1-2")!!)
	implementation(include("party.iroiro.luajava:luaj:4.1.0")!!)
	implementation(include("party.iroiro.luajava:luajava:4.1.0")!!)
	runtimeOnly(include("com.github.wagyourtail.luaj:luaj-core:f062b53a34")!!)
	compileOnly("org.projectlombok:lombok:$lombokVersion")
	annotationProcessor("org.projectlombok:lombok:$lombokVersion")
	testCompileOnly("org.projectlombok:lombok:$lombokVersion")
	testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")
	testImplementation(platform("org.junit:junit-bom:5.12.2"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	aidsfuscatorRuntime("org.ow2.asm:asm:9.9.1")
	aidsfuscatorRuntime("org.ow2.asm:asm-analysis:9.9.1")
	aidsfuscatorRuntime("org.ow2.asm:asm-commons:9.9.1")
	aidsfuscatorRuntime("org.ow2.asm:asm-tree:9.9.1")
	aidsfuscatorRuntime("org.ow2.asm:asm-util:9.9.1")
	aidsfuscatorRuntime("com.google.code.gson:gson:2.13.2")
}

tasks.test {
	useJUnitPlatform()
}

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}

	from("Scripting.md") {
		into("assets/janet_reborn")
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
}

java {
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

spotless {
	java {
		target("src/**/*.java")
		googleJavaFormat()
		removeUnusedImports()
		trimTrailingWhitespace()
		endWithNewline()
	}
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}

val aidsfuscatorCommit = "d794194022b65f425bbaa0bbbbe99aff1de229d9"
val aidsfuscatorArchiveSha256 = "a42c8b8fec508ae67d51ce22f768059101b810e67d4acabba5f1451c60e2a623"
val aidsfuscatorArchive =
	layout.buildDirectory.file("tools/aidsfuscator/$aidsfuscatorCommit/source.zip")
val aidsfuscatorSources =
	layout.buildDirectory.dir("tools/aidsfuscator/$aidsfuscatorCommit/sources")
val aidsfuscatorSourceRoot =
	aidsfuscatorSources.map { it.dir("aidsfuscator-$aidsfuscatorCommit") }
val aidsfuscatorClasses =
	layout.buildDirectory.dir("tools/aidsfuscator/$aidsfuscatorCommit/classes")
val aidsfuscatorRunRoot = layout.buildDirectory.dir("aidsfuscator/release")
val aidsfuscatorWorkspace = aidsfuscatorRunRoot.map { it.dir("workspace") }
val stagedObfuscatedJar = aidsfuscatorWorkspace.map { it.file("output.jar") }
val obfuscatedReleaseJar =
	layout.buildDirectory.file("libs/${project.name}-${project.version}-obfuscated.jar")

fun sha256(file: File): String {
	val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))
	return digest.joinToString("") { byte ->
		Integer.toHexString(byte.toInt() and 0xff).padStart(2, '0')
	}
}

val downloadAidsfuscator by tasks.registering {
	description = "Downloads the pinned aidsfuscator source archive."
	outputs.file(aidsfuscatorArchive)
	outputs.upToDateWhen {
		val archive = aidsfuscatorArchive.get().asFile
		archive.isFile && sha256(archive) == aidsfuscatorArchiveSha256
	}

	doLast {
		val destination = aidsfuscatorArchive.get().asFile
		if (destination.isFile && sha256(destination) == aidsfuscatorArchiveSha256) {
			return@doLast
		}

		destination.parentFile.mkdirs()
		val temporary = destination.resolveSibling("${destination.name}.tmp")
		Files.deleteIfExists(temporary.toPath())
		val url =
			"https://github.com/LvStrnggg/aidsfuscator/archive/$aidsfuscatorCommit.zip"
		logger.lifecycle("Downloading pinned aidsfuscator commit $aidsfuscatorCommit")
		val connection = URI.create(url).toURL().openConnection()
		connection.connectTimeout = 30_000
		connection.readTimeout = 60_000
		connection.setRequestProperty("User-Agent", "janet-reborn-gradle-build")
		connection.getInputStream().use { input ->
			temporary.outputStream().use { output -> input.copyTo(output) }
		}

		val actualSha256 = sha256(temporary)
		if (actualSha256 != aidsfuscatorArchiveSha256) {
			Files.deleteIfExists(temporary.toPath())
			throw GradleException(
				"Pinned aidsfuscator archive checksum mismatch: expected " +
					"$aidsfuscatorArchiveSha256, got $actualSha256",
			)
		}
		Files.move(
			temporary.toPath(),
			destination.toPath(),
			StandardCopyOption.REPLACE_EXISTING,
		)
	}
}

val extractAidsfuscator by tasks.registering(Sync::class) {
	description = "Extracts the pinned aidsfuscator sources."
	dependsOn(downloadAidsfuscator)
	from({ zipTree(aidsfuscatorArchive.get().asFile) })
	into(aidsfuscatorSources)
}

val compileAidsfuscator by tasks.registering(JavaCompile::class) {
	description = "Compiles the pinned aidsfuscator sources without requiring Maven."
	dependsOn(extractAidsfuscator)
	source(
		aidsfuscatorSourceRoot.map { root ->
			files(root.dir("api/src/main/java"), root.dir("obfuscator/src/main/java"))
				.asFileTree
				.matching { include("**/*.java") }
		},
	)
	classpath = aidsfuscatorRuntime
	destinationDirectory.set(aidsfuscatorClasses)
	options.encoding = "UTF-8"
	options.release.set(25)
}

val productionJarTaskName = if (tasks.names.contains("remapJar")) "remapJar" else "jar"
val productionJarTask = tasks.named<AbstractArchiveTask>(productionJarTaskName)
val remappedProductionJar = productionJarTask.flatMap { it.archiveFile }

val prepareAidsfuscatorWorkspace by tasks.registering(Sync::class) {
	description = "Prepares an isolated aidsfuscator workspace for the remapped production JAR."
	dependsOn(productionJarTask)
	from(remappedProductionJar) { rename { "input.jar" } }
	from(layout.projectDirectory.dir("gradle/aidsfuscator")) {
		include("config.json", "exclusions.json", "references.json")
	}
	from({
		configurations.compileClasspath.get().files.filter {
			it.isFile && (it.extension == "jar" || it.extension == "zip")
		}
	}) {
		into("libs")
	}
	into(aidsfuscatorWorkspace)
}

val obfuscateReleaseJar by tasks.registering(JavaExec::class) {
	group = "build"
	description = "Obfuscates the final remapped production JAR with pinned aidsfuscator."
	dependsOn(compileAidsfuscator, prepareAidsfuscatorWorkspace)
	classpath(files(aidsfuscatorClasses), aidsfuscatorRuntime)
	mainClass.set("dev.lvstrng.aidsfuscator.Main")
	workingDir(aidsfuscatorRunRoot)
	args("--config=config.json", "--exclusions=exclusions.json", "--references=references.json")
	jvmArgs("-Xmx1G")
	isIgnoreExitValue = false
	inputs.file(remappedProductionJar)
	inputs.files(
		layout.projectDirectory.files(
			"gradle/aidsfuscator/config.json",
			"gradle/aidsfuscator/exclusions.json",
			"gradle/aidsfuscator/references.json",
		),
	)
	inputs.files(configurations.compileClasspath)
	inputs.dir(aidsfuscatorClasses)
	outputs.file(obfuscatedReleaseJar)

	doFirst {
		Files.deleteIfExists(stagedObfuscatedJar.get().asFile.toPath())
		Files.deleteIfExists(obfuscatedReleaseJar.get().asFile.toPath())
	}

	doLast {
		val staged = stagedObfuscatedJar.get().asFile
		if (!staged.isFile || staged.length() == 0L) {
			throw GradleException(
				"Aidsfuscator did not produce workspace/output.jar; see its output above for the cause.",
			)
		}
		try {
			ZipFile(staged).use { zip ->
				if (zip.getEntry("fabric.mod.json") == null) {
					throw GradleException("Obfuscated JAR is missing fabric.mod.json")
				}
			}
		} catch (failure: Exception) {
			if (failure is GradleException) throw failure
			throw GradleException("Aidsfuscator produced an unreadable JAR", failure)
		}

		val output = obfuscatedReleaseJar.get().asFile
		output.parentFile.mkdirs()
		Files.copy(staged.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
		logger.lifecycle("Obfuscated release: ${output.absolutePath}")
	}
}

val verifyObfuscatedRelease by tasks.registering {
	group = "verification"
	description = "Checks Fabric/Mixin metadata, resources, access wideners, and nested dependencies."
	dependsOn(obfuscateReleaseJar)
	inputs.file(remappedProductionJar)
	inputs.file(obfuscatedReleaseJar)

	doLast {
		val inputFile = remappedProductionJar.get().asFile
		val outputFile = obfuscatedReleaseJar.get().asFile
		ZipFile(inputFile).use { input ->
			ZipFile(outputFile).use { output ->
				val requiredMetadata =
					listOf("fabric.mod.json", "janet_reborn.mixins.json")
				requiredMetadata.forEach { name ->
					if (output.getEntry(name) == null) {
						throw GradleException("Obfuscated release is missing required metadata: $name")
					}
				}

				val unchangedResources =
					input.entries().asSequence().filter { entry ->
						!entry.isDirectory &&
							!entry.name.endsWith(".class") &&
							entry.name != "META-INF/MANIFEST.MF" &&
							entry.name != "fabric.mod.json" &&
							!entry.name.endsWith("mixins.json") &&
							!entry.name.endsWith("refmap.json")
					}.toList()
				unchangedResources.forEach { entry ->
					val candidate = output.getEntry(entry.name)
						?: throw GradleException("Obfuscated release is missing resource: ${entry.name}")
					val before = input.getInputStream(entry).use { it.readAllBytes() }
					val after = output.getInputStream(candidate).use { it.readAllBytes() }
					if (!before.contentEquals(after)) {
						throw GradleException("Obfuscation changed protected resource: ${entry.name}")
					}
				}

				val protectedClasses =
					listOf(
						"dev/lifus/janetreborn/platform/minecraft/mixin/MinecraftMixin.class",
					)
				protectedClasses.forEach { name ->
					if (input.getEntry(name) == null) {
						throw GradleException("Input JAR is missing expected protected class: $name")
					}
					output.getEntry(name)
						?: throw GradleException("Obfuscation renamed or removed protected class: $name")
				}
			}
		}
		logger.lifecycle("Verified obfuscated Fabric release: ${outputFile.absolutePath}")
	}
}

tasks.register("obfuscatedRelease") {
	group = "build"
	description = "Runs checks, remaps, obfuscates, and verifies the production Fabric release."
	dependsOn(tasks.named("check"), verifyObfuscatedRelease)
}

tasks.register<JavaExec>("runObfuscatedClient") {
	group = "fabric"
	description = "Launches a Fabric client with only the obfuscated release as the project mod."
	dependsOn(verifyObfuscatedRelease, tasks.named("configureClientLaunch"))
	classpath(configurations.runtimeClasspath, files(obfuscatedReleaseJar))
	mainClass.set("net.fabricmc.devlaunchinjector.Main")
	val launchConfig = layout.projectDirectory.file(".gradle/loom-cache/launch.cfg")
	systemProperty("fabric.dli.config", launchConfig.asFile.absolutePath)
	systemProperty("fabric.dli.env", "client")
	systemProperty("fabric.dli.main", "net.fabricmc.loader.impl.launch.knot.KnotClient")
	jvmArgs("--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
	val clientRunDirectory = layout.buildDirectory.dir("run-obfuscated-client")
	workingDir(clientRunDirectory)
	doFirst { clientRunDirectory.get().asFile.mkdirs() }
}

publishing {
	publications {
		register<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}

	repositories {
	}
}
