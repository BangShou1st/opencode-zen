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
    repositories {
        // Lightweight local consumption: `.\gradlew.bat publishToMavenLocal`.
        mavenLocal()
        // Optional private ecosystem repository (GitHub Packages / Nexus / Artifactory).
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
