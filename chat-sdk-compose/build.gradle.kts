plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "net.letsbot.chat.compose"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    api(project(":chat-sdk"))
    implementation(platform(libs.compose.bom))
    api(libs.compose.ui)
    implementation(libs.androidx.activity.compose)
}

mavenPublishing {
    publishToMavenCentral()
    // Sign only when a key is supplied (CI release job) so local `publishToMavenLocal` keeps working.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates("net.letsbot", "chat-sdk-compose", providers.gradleProperty("VERSION_NAME").get())
    pom {
        name.set("LetsBot In-App Chat SDK for Jetpack Compose")
        description.set("Jetpack Compose entry point (LetsBotChatScreen) for the LetsBot in-app chat SDK.")
        inceptionYear.set("2026")
        url.set("https://letsbot.net/developers/in-app-chat")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("letsbot")
                name.set("LetsBot IT Team")
                email.set("support@letsbot.net")
                organization.set("LetsBot")
                organizationUrl.set("https://letsbot.net")
            }
        }
        scm {
            url.set("https://github.com/Lets-Bot/letsbot-chat-android")
            connection.set("scm:git:git://github.com/Lets-Bot/letsbot-chat-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/Lets-Bot/letsbot-chat-android.git")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Lets-Bot/letsbot-chat-android/issues")
        }
    }
}
