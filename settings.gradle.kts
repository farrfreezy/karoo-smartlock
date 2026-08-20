pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // karoo-ext is published only to GitHub Packages, which always requires
        // authentication (a PAT with read:packages, or the CI GITHUB_TOKEN).
        // Empty credentials keep configuration working; resolution fails only
        // when the dependency is actually fetched without valid credentials.
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GPR_USER")
                    ?: System.getenv("GITHUB_ACTOR")
                    ?: ""
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GPR_KEY")
                    ?: System.getenv("GITHUB_TOKEN")
                    ?: ""
            }
        }
    }
}

rootProject.name = "karoo-smartlock"
include(":app")
