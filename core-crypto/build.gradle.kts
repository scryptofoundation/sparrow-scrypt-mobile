plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    // iosArm64() / iosSimulatorArm64() — parked until Apple hardware exists (see DESIGN.md).

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json) // @Serializable on the tracer's result types
        }
        // JVM and Android share the same actuals: libsecp256k1 (constant-time) for keys, BouncyCastle for
        // ECDSA so signatures are byte-identical to the desktop's drongo
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(libs.secp256k1.kmp)
                implementation(libs.bouncycastle)
            }
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)
        jvmMain.dependencies {
            implementation(libs.secp256k1.kmp.jni.jvm)
        }
        androidMain.dependencies {
            implementation(libs.secp256k1.kmp.jni.android)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core) // runBlocking for the tracer's suspend calls
        }
    }
}

// The parity tests read ../vectors (the tracker recordings among them): a changed recording reruns them
tasks.named<Test>("jvmTest") {
    inputs.dir(rootProject.file("vectors")).withPropertyName("vectors").withPathSensitivity(PathSensitivity.RELATIVE)
}

android {
    namespace = "com.sparrowwallet.mobile.crypto"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
