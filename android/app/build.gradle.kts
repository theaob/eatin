import java.util.Properties

plugins {
    id("com.android.application")
}

// Version: pass -PversionCode=N -PversionName=X (CI uses the run number).
val code = (findProperty("versionCode") as String?)?.toInt() ?: 5
val name = (findProperty("versionName") as String?) ?: "1.$code"

// Release signing: ../../signing/keystore.properties (outside the repo) or
// KEYSTORE_PROPS. Without it the release bundle is built unsigned.
val signingProps = file(System.getenv("KEYSTORE_PROPS") ?: "../../signing/keystore.properties")

android {
    namespace = "com.onurbaykal.eatin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.onurbaykal.eatin"
        minSdk = 24
        targetSdk = 36
        versionCode = code
        versionName = name
    }

    signingConfigs {
        // Same debug key as the old apktool build, so new APKs install over old ones.
        getByName("debug") {
            if (file("../debug.keystore").exists()) {
                storeFile = file("../debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (signingProps.exists()) {
            val p = Properties().apply { signingProps.inputStream().use { load(it) } }
            create("release") {
                storeFile = signingProps.parentFile.resolve(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "woff2"
    }
}

// Copy ../index.html into src/main/assets with three.js and fonts bundled.
val syncGame by tasks.registering(Exec::class) {
    workingDir = rootDir
    commandLine("python3", "sync.py")
    inputs.file("../../index.html")
    inputs.dir("../vendor")
    inputs.file("../sync.py")
    outputs.dir("src/main/assets")
}
tasks.named("preBuild") { dependsOn(syncGame) }

dependencies {
    implementation("com.google.android.gms:play-services-ads:24.4.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")
}
