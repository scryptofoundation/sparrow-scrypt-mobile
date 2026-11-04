import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

//Release builds are signed with the key keystore.properties names; that file and the key stay outside git, and without
//them the release build is left unsigned
val releaseSigning = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.sparrowwallet.mobile"
    compileSdk = 35
    defaultConfig {
        //Sparrow-Scrypt's own id, so the app does not sit in Sparrow Wallet's namespace (the code namespace is unchanged)
        applicationId = "io.github.scryptofoundation.sparrowscrypt"
        minSdk = 26
        targetSdk = 35
        versionCode = 47
        versionName = "0.17.0-beta.1"
    }
    signingConfigs {
        if(releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            if(releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // lets JVM tests build stub Android objects such as ContextWrapper(null)
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core-crypto"))
    implementation(project(":electrum"))
    implementation(project(":storage"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation(libs.kotlinx.serialization.json)
    // BC-UR animated QR codes, the same version desktop Sparrow uses
    implementation("com.sparrowwallet:hummingbird:1.7.6")
    // mwebd scanner: gomobile-built daemon + the same gRPC client the desktop uses
    implementation(files("libs/mwebd.aar"))
    implementation("io.grpc:grpc-okhttp:1.79.0")
    implementation("io.grpc:grpc-protobuf:1.79.0")
    implementation("io.grpc:grpc-stub:1.79.0")
    implementation("com.google.protobuf:protobuf-java:4.34.0")
    testImplementation(kotlin("test"))
}
