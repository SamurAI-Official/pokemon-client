/*
 * pokemon-client-rollback: connects the standalone `libretro-rollback` engine (rollback session,
 * prediction, state ring, checksums, UDP) to the `LibretroConsole` used by pokemon-client.
 *
 * JVM-only like the libretro adapter. The engine is consumed from mavenLocal; publish it with
 * ../libretro-rollback/scripts/publish-libretro-rollback.sh before building this module.
 */
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.maven)
}

kotlin {
    jvmToolchain(21)
}

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    // Maven Central is upstream's channel: publishing there needs its namespace and a GPG key. This fork publishes to
    // GitHub Packages instead (.github/workflows/publish.yml), where neither is needed, so Central is only set up when
    // its secrets are there (MAVEN_CENTRAL=true) — the same build file serves both.
    if (System.getenv("MAVEN_CENTRAL") == "true") {
        publishToMavenCentral()
        signAllPublications()
    }
    pom {
        name.set("pokemon-client-rollback")
        description.set(
            "Connects the standalone libretro-rollback engine to LibretroConsole (rollback netcode " +
                "adapter over pokemon-client-libretro)",
        )
        url.set(project.ext.get("url")?.toString())
        licenses {
            license {
                name.set(project.ext.get("license.name")?.toString())
                url.set(project.ext.get("license.url")?.toString())
            }
        }
        developers {
            developer {
                id.set(project.ext.get("developer.id")?.toString())
                name.set(project.ext.get("developer.name")?.toString())
                email.set(project.ext.get("developer.email")?.toString())
                url.set(project.ext.get("developer.url")?.toString())
            }
        }
        scm {
            url.set(project.ext.get("scm.url")?.toString())
        }
    }
}

dependencies {
    api(projects.pokemonClientLibretro)
    api("dev.kotlinds:libretro-rollback:0.1.0-SNAPSHOT")
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
