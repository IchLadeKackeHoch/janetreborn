plugins {
	`java-library`
	id("com.diffplug.spotless")
	id("me.champeau.jmh")
}

group = "dev.codeman"
version = rootProject.version

repositories {
	mavenCentral()
}

dependencies {
	testImplementation(platform("org.junit:junit-bom:5.12.2"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
	useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 25
}

java {
	withSourcesJar()
	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

jmh {
	jmhVersion = "1.37"
	jvmArgsAppend = listOf("-Xms64m", "-Xmx256m", "-XX:+UseSerialGC")
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
