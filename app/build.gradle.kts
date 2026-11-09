import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

// 默认不把任何密钥打进 APK（APK 要公开下载）：配置用 adb 推到眼镜上的 config.properties。
// 只给自己用、确实想打进去时：./gradlew :app:assembleDebug -PembedSecrets=true
val embedSecrets = (findProperty("embedSecrets") as String?) == "true"
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (embedSecrets && f.exists()) f.reader(Charsets.UTF_8).use { load(it) }
}

fun secret(key: String): String =
    (secrets.getProperty(key) ?: "").trim().replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.huihou.glasses"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.huihou.glasses"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        listOf("DASHSCOPE_API_KEY", "DASHSCOPE_BASE_URL", "QWEN_MODEL", "FEISHU_WEBHOOK", "FEISHU_SECRET", "USER_NAMES")
            .forEach { buildConfigField("String", it, "\"${secret(it)}\"") }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 课程项目直接用 debug 签名，方便 adb 安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/9/previous-compilation-data.bin")
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
