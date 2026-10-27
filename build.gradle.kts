plugins {
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.maven) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.dokka)
}

allprojects {
    group = "dev.kotlinds"
    // -SNAPSHOT between releases: consumed through mavenLocal (`./gradlew publishToMavenLocal`) while developing.
    version = "0.1.2-SNAPSHOT"
    project.ext.set("url", "https://github.com/kotlinds/pokemon-client")
    project.ext.set("license.name", "Apache 2.0")
    project.ext.set("license.url", "https://www.apache.org/licenses/LICENSE-2.0.txt")
    project.ext.set("developer.id", "nathanfallet")
    project.ext.set("developer.name", "Nathan Fallet")
    project.ext.set("developer.email", "contact@nathanfallet.me")
    project.ext.set("developer.url", "https://www.nathanfallet.me")
    project.ext.set("scm.url", "https://github.com/kotlinds/pokemon-client.git")

    repositories {
        // The engine (dev.kotlinds:libretro-rollback) comes from GitHub Packages now: published there by its own
        // workflow, so a clean clone builds without a local `publishToMavenLocal` (mavenLocal still wins when
        // something was published locally while developing).
        mavenLocal()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/SamurAI-Official/pokemon-client")
            content { includeGroup("dev.kotlinds") }
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user")?.toString()
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key")?.toString()
            }
        }
        mavenCentral()
    }
}

// Where the fork publishes: GitHub Packages, one URL per repository, all of them views of the same owner's packages
// (the app resolves dev.kotlinds from the project repository's URL). Publishing needs `packages: write`; see
// .github/workflows/publish.yml. Upstream's Maven Central path stays configured but only runs with its own secrets.
subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension>("publishing") {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/SamurAI-Official/pokemon-client")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user")?.toString()
                        password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key")?.toString()
                    }
                }
            }
        }
    }
}

dependencies {
    dokka(projects.pokemonClient)
    dokka(projects.pokemonClientLibretro)
    dokka(projects.pokemonClientRollback)

    kover(projects.pokemonClient)
    kover(projects.pokemonClientLibretro)
    // pokemon-client-rollback is left out of the aggregated report on purpose: it depends on the engine
    // (dev.kotlinds:libretro-rollback), which comes from a repository and not from this build, and kover cannot
    // resolve a kover variant of an external artifact ("No matching variant ... attribute 'org.gradle.usage' with
    // value 'kover'"), so aggregating it fails the whole report. Its own tests still run with the build.
}
