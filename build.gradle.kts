plugins {
    id("java")
}

group = "org.glavo"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

tasks.withType<JavaCompile> {
    options.release.set(25)
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "org.glavo.lwjgl.UnsafeAgent",
            "Agent-Class" to "org.glavo.lwjgl.UnsafeAgent"
        )
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}