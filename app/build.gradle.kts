plugins {
  id("com.android.application")
}

android {
  namespace = "com.streamscene.app"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.streamscene.app"
    minSdk = 30          // Android 11+
    targetSdk = 35
    versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
    versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
  }

  // Постоянный ключ подписи, чтобы новые версии ставились поверх старых
  signingConfigs {
    getByName("debug") {
      storeFile = file("debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    debug {
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation("com.github.pedroSG94.RootEncoder:library:2.8.2")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
