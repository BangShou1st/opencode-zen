plugins {
    id("base")
}

// One delivery channel: JitPack builds the public GitHub tag and serves it as
// com.github.<owner>.<repo>:<module>:<tag>. Consumers need no credentials, no
// PAT and no local Maven repository.
//
// JitPack sets GROUP / ARTIFACT / VERSION in the build environment; the
// defaults below are what this repository publishes under. The version IS the
// tag, with its leading "v" — there is no second numbering scheme.
val owner = System.getenv("GROUP")?.takeIf { it.isNotBlank() } ?: "com.github.BangShou1st"
val repo = System.getenv("ARTIFACT")?.takeIf { it.isNotBlank() } ?: "opencode-zen"

// JitPack hands out the owner as com.github.<user>; the multi-module
// coordinate appends the repository name to it. Guard against an owner that
// already carries it.
group = if (owner.endsWith(".$repo")) owner else "$owner.$repo"

version = System.getenv("VERSION")?.takeIf { it.isNotBlank() } ?: "v1.5.3"

subprojects {
    group = rootProject.group
    version = rootProject.version
}
