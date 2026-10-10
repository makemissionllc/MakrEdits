import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.makemission.makredits"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.makemission.makredits"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val filmcraftRoot = rootProject.projectDir.parentFile
val rustLibraries = layout.projectDirectory.dir("src/main/jniLibs")
val localProperties = Properties().apply {
    rootProject.file("local.properties").inputStream().use { input -> load(input) }
}
val androidSdkDirectory = localProperties.getProperty("sdk.dir")
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")

val buildFilmcraftRust = tasks.register<Exec>("buildFilmcraftRust") {
    workingDir(filmcraftRoot)
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-P", "26", "-o", rustLibraries.asFile.absolutePath,
        "build", "--release", "--no-default-features", "-p", "filmcraft-android-entry",
    )
    if (androidSdkDirectory != null) {
        environment("ANDROID_HOME", androidSdkDirectory)
    }
    outputs.dir(rustLibraries)
}

tasks.configureEach {
    if (name.startsWith("merge") &&
        (name.endsWith("NativeLibs") || name.endsWith("JniLibFolders"))
    ) {
        dependsOn(buildFilmcraftRust)
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
