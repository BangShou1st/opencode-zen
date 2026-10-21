plugins {
    id("base")
}

// Two delivery channels, one source tree:
//
//   GitHub Packages (our CI, publish.yml)  com.sharedlibs:<module>:<version>
//   JitPack (their build machine)           com.github.BangShou1st.opencode-zen:<module>:<tag>
//
// JitPack sets JITPACK=true plus GROUP / ARTIFACT / VERSION in the build
// environment. Without that switch the GitHub Packages coordinates are used,
// so a local build and our own publish job are unaffected.
val onJitPack = System.getenv("JITPACK")?.equals("true", ignoreCase = true) == true

group = if (onJitPack) {
    val owner = System.getenv("GROUP")?.takeIf { it.isNotBlank() } ?: "com.github.BangShou1st"
    val repo = System.getenv("ARTIFACT")?.takeIf { it.isNotBlank() } ?: "opencode-zen"
    // JitPack hands out the owner as com.github.<user>; the multi-module
    // coordinate appends the repository name to it. Guard against an owner
    // that already carries it.
    if (owner.endsWith(".$repo")) owner else "$owner.$repo"
} else {
    "com.sharedlibs"
}

// The tag itself is the JitPack version: a consumer asks for v1.4.1 and gets
// v1.4.1. GitHub Packages keeps the plain number.
version = if (onJitPack) {
    System.getenv("VERSION")?.takeIf { it.isNotBlank() } ?: "v1.4.1"
} else {
    "1.4.1"
}

subprojects {
    group = rootProject.group
    version = rootProject.version
}
