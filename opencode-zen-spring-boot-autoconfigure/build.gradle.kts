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
    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    api("org.springframework.boot:spring-boot-autoconfigure:3.3.2")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.3.2")
}

// This shared library ships NO tests: it is a lean artifact that other
// projects depend on, and its correctness is checked from the outside — by
// the opencode-zen-consumer-tests project, which resolves the PUBLISHED
// coordinate and exercises the artifact the way a consumer does. A suite here
// would prove the sources compile, not that the artifact a consumer gets
// works.

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "opencode-zen-spring-boot-autoconfigure"
            from(components["java"])
        }
    }
    // No publishing repository on purpose. The artifact reaches consumers
    // through JitPack, which builds the public GitHub tag and repackages what
    // `publishToMavenLocal` leaves in the build machine's local Maven
    // repository — that task needs no repository declaration. There is no
    // private registry and no local channel.
}
