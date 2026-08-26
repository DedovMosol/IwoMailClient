plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlinx.kover")
}

android {
    namespace = "com.dedovmosol.iwomail"
    compileSdk = 36

    lint {
        // Existing issues are snapshotted in lint-baseline.xml; CI fails only on
        // NEW lint errors introduced after the baseline.
        baseline = file("lint-baseline.xml")
    }

    defaultConfig {
        applicationId = "com.dedovmosol.iwomail"
        minSdk = 26  // Android 8.0
        targetSdk = 36  // Android 16
        versionCode = 26
        versionName = "1.6.3b"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // Поддержка всех архитектур (32-bit и 64-bit)
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true  // Универсальный APK со всеми архитектурами
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "UTF-8"
    }

    kotlinOptions {
        jvmTarget = "17"
        // Оптимизации для production
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    testOptions {
        // Best practice (developer.android.com/training/testing/local-tests#mocking-dependencies):
        // возвращать дефолты для незамоканных методов android.jar (напр. android.util.Log) вместо
        // выброса "not mocked" — нужно для юнит-тестов crash-resistance, где catch логирует через Log.
        unitTests.isReturnDefaultValues = true
        // Robolectric парсит реальные ресурсы/манифест приложения: нужно для тестов,
        // завязанных на ресурсы (напр. FileProvider + @xml/file_paths в AttachmentLoader).
        unitTests.isIncludeAndroidResources = true
        unitTests.all { }
    }
}

// Room schema export - для проверки миграций при сборке
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}


dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    // ViewModel + Compose интеграция (MVVM): viewModelScope, viewModel(), collectAsStateWithLifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    // ProcessLifecycleOwner — детекция foreground/background всего процесса (единый источник
    // истины для подавления уведомлений при открытом клиенте и немедленного sync при возврате).
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // Compose BOM - используем стабильную версию
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material") // для pullRefresh
    // material-icons-extended убран для уменьшения размера APK
    // Иконки теперь в drawable ресурсах, доступны через AppIcons
    
    // Coil для загрузки изображений
    implementation("io.coil-kt:coil-compose:2.5.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")
    
    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    
    // Network - OkHttp для HTTP запросов
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // Conscrypt - для поддержки старых TLS протоколов (Exchange 2007)
    implementation("org.conscrypt:conscrypt-android:2.5.2")
    
    // JavaMail для POP3/IMAP
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    
    // WorkManager для фоновой синхронизации
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // DataStore для настроек
    // 1.1.1: исправлен официальный баг 1.0.0 «Unable to rename» на Windows —
    // запись использовала File.renameTo, который не умеет заменять существующий
    // файл; в 1.1.1 применён Files.move с REPLACE_EXISTING (issue 227612077).
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    
    // Security для хранения паролей
    // NOTE: security-crypto is officially deprecated (no stable release, last alpha 2021)
    // See: https://developer.android.com/jetpack/androidx/releases/security
    // Current strategy: use EncryptedSharedPreferences with XOR-obfuscated fallback (AccountRepository)
    // Alpha version kept as best available option; migration to alternative pending official recommendation
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // Биометрическая аутентификация: вход в приложение по отпечатку пальца (цель релиза «пароль + дактилоскопия»).
    // Стабильный канал androidx.biometric требует FragmentActivity — MainActivity наследует его
    // (FragmentActivity расширяет ComponentActivity, весь Compose-код работает без изменений).
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    
    // Glance для виджетов
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("com.google.truth:truth:1.1.5")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    
    // Android Testing
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

// Robolectric unit tests run on the JVM. The Android conscrypt AAR ships natives only for
// Android devices; on the unit test classpath it shadows the same Conscrypt classes that
// Robolectric pulls from the OpenJDK variant (which has real JVM natives), so every
// Robolectric test would crash at startup with UnsatisfiedLinkError. Exclude the Android
// artifact from unit test configurations only; the device APK keeps shipping it for
// legacy TLS support (Exchange 2007 SP1).
configurations.all {
    if (name.contains("UnitTest", ignoreCase = true)) {
        exclude(group = "org.conscrypt", module = "conscrypt-android")
    }
}
