import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

/** Runs a git command at configuration time; null when git is unusable (e.g. a source archive). */
fun git(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
    }.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

/** Commit count on a linear main: monotonic, so an update is never read as a downgrade. Needs a full clone. */
val gitCommitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1

/** `1.2.0` on a `v1.2.0` tag, `1.2.0-4-gabc1234` past it, `0.0.0-dev` before the first tag. */
val gitVersionName = git("describe", "--tags", "--dirty", "--always")
    ?.removePrefix("v")
    ?.takeIf { it.first().isDigit() }
    ?: "0.0.0-dev"

/** Semantic-version core for the x-pm-appversion header Proton requires: external-drive-{name}@{semver}-{channel}. */
val semverCore = Regex("""^\d+\.\d+\.\d+""").find(gitVersionName)?.value ?: "0.0.0"

/** Release signing from env (CI) or the git-ignored local.properties; null builds an unsigned release. */
val releaseSigning: Map<String, String>? = run {
    val local = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val keys = listOf("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
    val values = keys.associateWith { (System.getenv(it) ?: local.getProperty(it))?.trim()?.takeIf(String::isNotEmpty) }
    if (values.values.any { it == null }) null else values.mapValues { it.value!! }
}

android {
    namespace = "io.github.joshuagr.neutrino"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.joshuagr.neutrino"
        minSdk = 36
        targetSdk = 36
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        versionCode = gitCommitCount
        versionName = gitVersionName
    }

    signingConfigs {
        releaseSigning?.let { creds ->
            create("release") {
                storeFile = rootProject.file(creds.getValue("RELEASE_STORE_FILE"))
                storePassword = creds.getValue("RELEASE_STORE_PASSWORD")
                keyAlias = creds.getValue("RELEASE_KEY_ALIAS")
                keyPassword = creds.getValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "APP_VERSION_HEADER", "\"external-drive-neutrino@$semverCore-dev\"")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Neutrino (debug)")
        }
        release {
            buildConfigField("String", "APP_VERSION_HEADER", "\"external-drive-neutrino@$semverCore-alpha\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "Neutrino")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
        buildConfig = true
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.proton.drive.sdk)
    implementation(libs.proton.golib)
}
