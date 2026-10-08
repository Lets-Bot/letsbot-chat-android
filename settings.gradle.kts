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
    }
}

rootProject.name = "letsbot-chat-android"

include(":chat-sdk")
include(":chat-sdk-compose")

// The sample app lives in `example/` but keeps the conventional `:sample` project path.
include(":sample")
project(":sample").projectDir = file("example")
