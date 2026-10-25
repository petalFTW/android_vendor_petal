plugins { id("com.android.application") version "8.11.1" }

android {
    namespace = "org.petal.backup"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.petal.backup"
        minSdk = 35
        targetSdk = 36
        versionCode = 2
        versionName = "0.2-local-dev"
    }
    buildTypes {
        debug { applicationIdSuffix = ".dev" }
    }
    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("src", "android/src/main/java"))
            res.setSrcDirs(listOf("android/src/main/res"))
            manifest.srcFile("android/src/main/AndroidManifest.xml")
        }
        getByName("test") { java.setSrcDirs(listOf("tests", "android/src/test/java")) }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

dependencies { testImplementation("junit:junit:4.13.2") }
