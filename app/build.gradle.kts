import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.typefree.ime"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    val usePrivateDict = project.findProperty("usePrivateDict") == "true"

    defaultConfig {
        applicationId = "com.typefree.ime"
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "3.6.0"
        buildConfigField("boolean", "USE_PRIVATE_DICT", usePrivateDict.toString())

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val keystoreProperties = Properties().apply {
        val propsFile = rootProject.file("keystore.properties")
        if (propsFile.isFile) {
            propsFile.inputStream().use { load(it) }
        }
    }

    fun signingValue(propKey: String, envKey: String): String? {
        return keystoreProperties.getProperty(propKey)?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv(envKey)?.trim()?.takeIf { it.isNotEmpty() }
    }

    val releaseStorePath = signingValue("storeFile", "TYPEFREE_KEYSTORE_PATH")
    val releaseStorePassword = signingValue("storePassword", "TYPEFREE_KEYSTORE_PASSWORD")
    val releaseKeyAlias = signingValue("keyAlias", "TYPEFREE_KEY_ALIAS")
    val releaseKeyPassword = signingValue("keyPassword", "TYPEFREE_KEY_PASSWORD")
    val releaseStoreFile = releaseStorePath?.let { path -> rootProject.file(path) }

    signingConfigs {
        if (
            releaseStoreFile != null &&
            releaseStoreFile.isFile &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null
        ) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // ONNX 模型（本地 SenseVoice / silero VAD）不压缩，保证 AssetManager 直接 mmap
        noCompress += "onnx"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    val privateDictDir = rootProject.file("local_private")
    val stagedPrivateAssetsDir = layout.buildDirectory.dir("generated/privateDictAssets")

    // 本地离线语音引擎（FunASR SenseVoice-Small int8 + silero VAD），由 CI 下载或本地
    // 软链到根目录 local_sherpa/（不进 git）。三个文件齐备才注入 APK assets/sherpa/。
    val localAsrDir = rootProject.file("local_sherpa")
    val hasLocalAsrModel = listOf("model.int8.onnx", "tokens.txt", "silero_vad.onnx")
        .all { localAsrDir.resolve(it).isFile }
    val stagedLocalAsrAssetsDir = layout.buildDirectory.dir("generated/localAsrAssets")

    if (hasLocalAsrModel) {
        val stageLocalAsrAssets = tasks.register<Sync>("stageLocalAsrAssets") {
            from(localAsrDir) {
                include("model.int8.onnx", "tokens.txt", "silero_vad.onnx")
                into("sherpa")
            }
            into(stagedLocalAsrAssetsDir)
        }
        tasks.named("preBuild") {
            dependsOn(stageLocalAsrAssets)
        }
    }

    if (usePrivateDict && privateDictDir.exists()) {
        val stagePrivateFlypyAssets = tasks.register<Sync>("stagePrivateFlypyAssets") {
            from("src/main/assets") {
                include("rime/**")
            }
            from(privateDictDir) {
                include("flypy_dict.tsv", "flypy_dz.tsv")
            }
            if (hasLocalAsrModel) {
                from(localAsrDir) {
                    include("model.int8.onnx", "tokens.txt", "silero_vad.onnx")
                    into("sherpa")
                }
            }
            into(stagedPrivateAssetsDir)
        }
        tasks.named("preBuild") {
            dependsOn(stagePrivateFlypyAssets)
        }
    }

    sourceSets {
        getByName("main") {
            if (usePrivateDict && privateDictDir.exists()) {
                if (hasLocalAsrModel) {
                    assets.setSrcDirs(listOf(stagedPrivateAssetsDir, stagedLocalAsrAssetsDir))
                } else {
                    assets.setSrcDirs(listOf(stagedPrivateAssetsDir))
                }
            } else if (hasLocalAsrModel) {
                assets.setSrcDirs(listOf("src/main/assets", stagedLocalAsrAssetsDir))
            }
        }
    }
}

dependencies {
    implementation(project(":rime"))

    // sherpa-onnx JNI runtime (本地离线 SenseVoice 引擎)；AAR 已提交进仓库，
    // 本地与 CI 构建均无需额外下载
    val sherpaAar = file("libs/sherpa-onnx-2.0.1.aar")
    if (sherpaAar.exists()) {
        implementation(files(sherpaAar))
    }

    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
