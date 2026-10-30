plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.kotlin.plugin.serialization")
  alias(libs.plugins.secrets)
}

if (file("google-services.json").exists()) {
  apply(plugin = "com.google.gms.google-services")
}

android {
  namespace = "com.music.vibe"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.music.vibe"
    minSdk = 26
    targetSdk = 36
    versionCode = 530
    versionName = "2.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField("String", "LASTFM_API_KEY", "\"\"")
    buildConfigField("String", "LASTFM_SECRET", "\"\"")
    buildConfigField("String", "LISTEN_TOGETHER_SERVER", "\"https://bitchord-listen-together.onrender.com\"")

    ndk {
      abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
    }
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      // Keystore Configuration:
      // Store File: ${rootDir}/debug.keystore
      // Store Password: android
      // Key Alias: androiddebugkey
      // Key Password: android
      // Certificate Fingerprints:
      // SHA-1: E4:2C:8C:E2:C1:8F:2D:73:9F:4A:24:6F:9C:DB:F3:31:F3:3B:1D:1E
      // SHA-256: 62:76:8A:F3:6C:75:3F:64:16:3E:BA:0D:81:25:47:32:79:32:8F:94:DE:69:81:6E:FE:F3:DC:54:DA:A8:15:9F
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging {
    resources {
      excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
    jniLibs {
      excludes += listOf("lib/x86/**", "lib/x86_64/**")
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

tasks.matching { it.name.contains("AarMetadata") }.configureEach {
  enabled = false
}

val newPipeExtractorRaw: Configuration by configurations.creating {
  isTransitive = false
  isCanBeConsumed = false
}

dependencies {
  newPipeExtractorRaw("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
}

val newPipeExtractorStripped = tasks.register<org.gradle.api.tasks.bundling.Jar>(
  "stripNewPipeExtractorUtils") {
  archiveFileName.set("NewPipeExtractor-v0.26.3-noutils.jar")
  destinationDirectory.set(layout.buildDirectory.dir("stripped-libs"))
  from(newPipeExtractorRaw.elements.map { files -> files.map { zipTree(it.asFile) } }) {
    exclude("org/schabi/newpipe/extractor/utils/Utils.class")
    exclude("org/schabi/newpipe/extractor/utils/Utils\$*.class")
  }
}

dependencies {
  implementation(project(":shared"))
  implementation(project(":sharedUi"))

  // Compose
  val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
  implementation(composeBom)
  implementation("androidx.compose.foundation:foundation:1.10.0")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-graphics")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.activity:activity-compose:1.9.3")
  implementation("androidx.navigation:navigation-compose:2.8.5")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
  implementation("androidx.lifecycle:lifecycle-process:2.8.7")
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.appcompat:appcompat:1.7.0")
  debugImplementation("androidx.compose.ui:ui-tooling")

  // Media playback: Media3 / ExoPlayer
  implementation("androidx.media3:media3-exoplayer:1.11.0")
  implementation("androidx.media3:media3-session:1.11.0")
  implementation("androidx.media3:media3-common:1.11.0")
  implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
  implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
  implementation("androidx.media3:media3-exoplayer-dash:1.11.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")

  // Images: Coil 3 + Palette
  implementation("io.coil-kt.coil3:coil-compose:3.0.4")
  implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
  implementation("androidx.palette:palette-ktx:1.0.0")

  // Frosted glass / blur
  implementation("dev.chrisbanes.haze:haze:1.3.1")
  implementation("dev.chrisbanes.haze:haze-materials:1.3.1")

  // QR
  implementation("com.google.zxing:core:3.5.3")

  // Markdown
  implementation("com.halilibo.compose-richtext:richtext-ui-material3:0.20.0")
  implementation("com.halilibo.compose-richtext:richtext-commonmark:0.20.0")

  // Innertube: Ktor + kotlinx.serialization
  implementation("io.ktor:ktor-client-core:3.5.2")
  implementation("io.ktor:ktor-client-okhttp:3.5.2")
  implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
  implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
  implementation("io.ktor:ktor-client-websockets:3.5.2")

  // YouTube stream extraction
  implementation("com.github.MetrolistGroup.innertubex:innertubex-android:v0.7.0")

  // Stream resolution: NewPipe stripped
  implementation(files(newPipeExtractorStripped))
  implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
  implementation("org.jsoup:jsoup:1.22.2")
  implementation("com.google.code.findbugs:jsr305:3.0.2")
  implementation("com.google.protobuf:protobuf-javalite:4.35.0")
  implementation("org.mozilla:rhino:1.8.1")
  implementation("org.mozilla:rhino-engine:1.8.1")

  // Auth/session storage
  implementation("androidx.security:security-crypto:1.1.0-alpha06")

  // JS module execution: QuickJS VM
  implementation("io.github.dokar3:quickjs-kt-android:1.0.14")

  // SMB
  implementation("com.hierynomus:smbj:0.15.0")

  // Automix ONNX
  implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")

  // Casting
  implementation("com.google.android.gms:play-services-cast-framework:22.2.0")
  implementation("androidx.mediarouter:mediarouter:1.8.1")

  // Firebase
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.auth)
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.messaging)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)

  testImplementation("junit:junit:4.13.2")
  testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core)
}

