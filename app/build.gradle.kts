plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.noisemachine.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.noisemachine.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// collection-ktx:1.2.0 (via ui-unit-android) duplicates classes already in
// collection-jvm:1.4.0; the newer artifact wins.
configurations.all {
    exclude(group = "androidx.collection", module = "collection-ktx")
    // androidx.collection:collection:1.1.0 (legacy) duplicates classes in
    // collection-jvm:1.4.0, its renamed successor; drop the legacy one.
    exclude(group = "androidx.collection", module = "collection")
    // The local m2repo holds stub (manifest-only) AARs for the plain compose
    // coordinates; the real artifacts are the "-android"-suffixed ones.
    // Redirect every stub module to its real counterpart.
    resolutionStrategy.dependencySubstitution {
        val compose168 = listOf(
            "androidx.compose.animation:animation",
            "androidx.compose.animation:animation-core",
            "androidx.compose.foundation:foundation",
            "androidx.compose.foundation:foundation-layout",
            "androidx.compose.material:material-icons-core",
            "androidx.compose.material:material-ripple",
            "androidx.compose.runtime:runtime",
            "androidx.compose.runtime:runtime-saveable",
            "androidx.compose.ui:ui",
            "androidx.compose.ui:ui-geometry",
            "androidx.compose.ui:ui-graphics",
            "androidx.compose.ui:ui-text",
            "androidx.compose.ui:ui-tooling-preview",
            "androidx.compose.ui:ui-unit",
            "androidx.compose.ui:ui-util"
        )
        for (stub in compose168) {
            val (g, a) = stub.split(":")
            substitute(module("$g:$a")).using(module("$g:$a-android:1.6.8"))
        }
        substitute(module("androidx.compose.material3:material3"))
            .using(module("androidx.compose.material3:material3-android:1.2.1"))
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
}
