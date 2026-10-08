plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Override from ~/.gradle/gradle.properties or the command line: -Pletsbot.appKey=... -Pletsbot.baseUrl=...
val letsbotAppKey = providers.gradleProperty("letsbot.appKey").getOrElse("YOUR_APP_KEY")
val letsbotBaseUrl = providers.gradleProperty("letsbot.baseUrl").getOrElse("https://letsbot.net")

android {
    namespace = "net.letsbot.chat.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.letsbot.chat.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "LETSBOT_APP_KEY", "\"$letsbotAppKey\"")
        buildConfigField("String", "LETSBOT_BASE_URL", "\"$letsbotBaseUrl\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
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
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":chat-sdk"))
    implementation(project(":chat-sdk-compose"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
}
