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
    testImplementation("org.springframework.boot:spring-boot-test:3.3.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.3.2")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

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
    repositories {
        // Lightweight local consumption: `.\gradlew.bat publishToMavenLocal` then any project
        // just adds `repositories { mavenLocal() }` plus the coordinate below.
        mavenLocal()
        // Optional private ecosystem repository (GitHub Packages / Nexus / Artifactory),
        // supplied by environment so no URL or credential is committed:
        //   ZEN_MAVEN_REPO_URL, ZEN_MAVEN_USER, ZEN_MAVEN_TOKEN
        val ecosystemUrl = System.getenv("ZEN_MAVEN_REPO_URL").orEmpty().trim()
        if (ecosystemUrl.isNotEmpty()) {
            maven {
                name = "zenEcosystem"
                url = uri(ecosystemUrl)
                isAllowInsecureProtocol = ecosystemUrl.startsWith("http://")
                credentials {
                    username = System.getenv("ZEN_MAVEN_USER").orEmpty()
                    password = System.getenv("ZEN_MAVEN_TOKEN").orEmpty()
                }
            }
        }
    }
}
