plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "net.letsbot.chat"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"${providers.gradleProperty("VERSION_NAME").get()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        lintConfig = file("lint.xml")
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.lifecycle.process)
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    publishToMavenCentral()
    // Sign only when a key is supplied (CI release job) so local `publishToMavenLocal` keeps working.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    // JitPack builds (JITPACK=true) publish under the JitPack group so that every POM dependency between the modules
    // resolves from JitPack too. Maven Central and local builds keep the net.letsbot coordinates.
    val onJitPack = providers.environmentVariable("JITPACK").orNull == "true"
    coordinates(
        if (onJitPack) "com.github.Lets-Bot.letsbot-chat-android" else providers.gradleProperty("GROUP").get(),
        "chat-sdk",
        (if (onJitPack) providers.environmentVariable("VERSION").orNull else null)
            ?: providers.gradleProperty("VERSION_NAME").get(),
    )
    pom {
        name.set("LetsBot In-App Chat SDK")
        description.set("LetsBot in-app chat for Android: hosted chat screen, verified identity, push notifications and unread badge.")
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
