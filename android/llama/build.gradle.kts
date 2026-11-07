plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "dev.lomio.llama"
  compileSdk = libs.versions.compileSdk.get().toInt()
  defaultConfig {
    minSdk = libs.versions.minSdk.get().toInt()
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation(libs.kotlinx.coroutines.android)
}
