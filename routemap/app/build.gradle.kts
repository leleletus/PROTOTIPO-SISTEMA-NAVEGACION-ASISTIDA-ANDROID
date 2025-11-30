import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose) // <-- ADD THIS LINE
    // No necesitas el plugin de compose aquí si ya usas composeOptions y buildFeatures
}

android {
    namespace = "com.djvemo.routemap"
    compileSdk = 36 // Sintaxis corregida

    // Carga de las claves de API movida dentro del bloque 'android'
    val apikeysFile = rootProject.file("app/apikeys.properties")
    val apikeys = Properties()
    if (apikeysFile.exists()) {
        apikeys.load(FileInputStream(apikeysFile))
    } else {
        throw GradleException("El archivo 'apikeys.properties' no se encuentra. Créalo en la raíz del módulo 'app'.")
    }

    defaultConfig {
        applicationId = "com.djvemo.routemap"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Accede a las propiedades de forma segura
        val orsApiKey = apikeys.getProperty("ORS_API_KEY", "DEFAULT_ORS_KEY")
        val googleMapsApiKey = apikeys.getProperty("GOOGLE_MAPS_API_KEY", "DEFAULT_GOOGLE_MAPS_KEY")

        buildConfigField("String", "ORS_API_KEY", "\"$orsApiKey\"")
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = googleMapsApiKey
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    // Bloque buildFeatures unificado y corregido
    buildFeatures {
        compose = true
        buildConfig = true // Habilita la generación de BuildConfig
    }

    // El bloque composeOptions es necesario para configurar el compilador de Compose
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1" // Asegúrate de usar una versión compatible
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.play.services.location)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.android.gms:play-services-maps:19.2.0")
    // Retrofit + Gson
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
