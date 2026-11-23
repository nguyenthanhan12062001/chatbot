plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "com.chatai.an"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.chatai.an"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Signing configs. Keystore release duoc luu trong thu muc du an (release-key.jks).
  // Mat khau ca keystore va key: Minhthu2103@
  signingConfigs {
    create("release") {
      storeFile = file("${rootDir}/release-key.jks")
      storePassword = "Minhthu2103@"
      keyAlias = "release"
      keyPassword = "Minhthu2103@"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // AGP tu dung debug keystore mac dinh (~/.android/debug.keystore), khong gan signingConfig
      // de tranh loi vi file debug.keystore chua ton tai trong du an.
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    viewBinding = true
    buildConfig = true
    aidl = true
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
    }
  }

  lint {
    checkReleaseBuilds = false
    abortOnError = false
    disable.add("MissingTranslation")
    disable.add("ExtraTranslation")
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.google.material)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.recyclerview)
  implementation(libs.androidx.drawerlayout)
  implementation(libs.androidx.activity)
  implementation(libs.androidx.fragment)
  implementation(libs.androidx.lifecycle.viewmodel)
  implementation(libs.androidx.lifecycle.livedata)
  implementation(libs.androidx.room.runtime)
  annotationProcessor(libs.androidx.room.compiler)
  implementation(libs.glide)
  implementation(libs.gson)
  implementation(libs.okhttp)
  implementation(libs.shizuku.api)
  implementation(libs.shizuku.provider)

  testImplementation(libs.junit)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.robolectric)

  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
}
