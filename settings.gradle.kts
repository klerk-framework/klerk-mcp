plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "klerk-mcp"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        mavenCentral() {
            content {
                excludeGroup("dev.klerkframework")
            }
        }
        maven ("https://jitpack.io") {
            content {
                includeGroup("dev.klerkframework")
            }
        }
    }
}
