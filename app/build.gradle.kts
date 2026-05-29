plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "moe.momokko.sillytavernapp"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "moe.momokko.sillytavernapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Package the prebuilt libnode.so from libnode/bin/ and the static git
    // binaries from libgit/bin/ (both land in nativeLibraryDir, the only
    // place Android lets us execute from).
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("libnode/bin")
            jniLibs.srcDir("libgit/bin")
        }
    }

    // node_modules ships many already-compressed assets; keep the bundled .so
    // uncompressed and unstripped during packaging.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.browser:browser:1.8.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}