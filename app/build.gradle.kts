plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.studydock.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.studydock.app"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 6
        versionName = "1.2.0"
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    // Pinned for AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35 toolchain compatibility
    //noinspection GradleDependency
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    //noinspection GradleDependency
    androidTestImplementation("androidx.test:runner:1.6.2")
    //noinspection GradleDependency
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    //noinspection GradleDependency
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    //noinspection GradleDependency
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    //noinspection GradleDependency
    implementation("androidx.room:room-runtime:2.6.1")
    //noinspection GradleDependency
    implementation("androidx.room:room-ktx:2.6.1")
    //noinspection GradleDependency
    ksp("androidx.room:room-compiler:2.6.1")
}
