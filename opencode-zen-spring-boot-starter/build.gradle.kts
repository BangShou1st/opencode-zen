plugins {
    id("java-library")
    id("maven-publish")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":opencode-zen-spring-boot-autoconfigure"))
    api("org.springframework.boot:spring-boot-starter:3.3.2")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "opencode-zen-spring-boot-starter"
            from(components["java"])
        }
    }
    // No publishing repository on purpose. The artifact reaches consumers
    // through JitPack, which builds the public GitHub tag and repackages what
    // `publishToMavenLocal` leaves in the build machine's local Maven
    // repository — that task needs no repository declaration. There is no
    // private registry and no local channel.
}
