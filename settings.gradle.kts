import org.gradle.api.initialization.resolve.RepositoriesMode

rootProject.name = "opencode-zen"

include(
    "opencode-zen-spring-boot-autoconfigure",
    "opencode-zen-spring-boot-starter",
)

// JitPack's build machines share an IP, and Maven Central throttles them
// (HTTP 429). Use Google's public Maven Central cache there and keep Maven
// Central as the fallback. Local builds and our GitHub Actions job keep the
// repositories the subprojects declare.
if (System.getenv("JITPACK")?.equals("true", ignoreCase = true) == true) {
    dependencyResolutionManagement {
        repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
        repositories {
            maven {
                name = "GoogleMavenCentralMirror"
                url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
            }
            mavenCentral()
        }
    }
}
