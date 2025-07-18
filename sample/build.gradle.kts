plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.dagger.hilt.android)
    alias(libs.plugins.kover)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
}

android {
    namespace = "com.sebaslogen.resacaapp.sample"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.sebaslogen.resacaapp"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    buildFeatures { // Enables Jetpack Compose for this module
        compose = true
        buildConfig = true
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles("proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += setOf(
                // Exclude consumer proguard files
                "META-INF/proguard/*",
                // Exclude the Firebase/Fabric/other random properties files
                "/*.properties",
                "fabric/*.properties",
                "META-INF/*.properties",
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
            )
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":resacahilt"))
    implementation(project(":resacakoin"))

    implementation(libs.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.android.material)
    debugImplementation(libs.leakcanary.android)

    // Hilt dependencies
    implementation(libs.dagger.hilt)
    ksp(libs.dagger.hilt.compiler)

    implementation(libs.koin.android)

    // Test dependencies

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlin.coroutines.test)
    // Test rules and transitive dependencies:
    testImplementation(libs.compose.ui.test.junit)
    // Needed for createComposeRule, but not createAndroidComposeRule:
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.dagger.hilt.android.testing)
    kspTest(libs.dagger.hilt.android.compiler)
    testImplementation(libs.koin.android.test)
    // Espresso dependencies for Activity recreation tests
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.androidx.junit.ktx)
    androidTestImplementation(libs.compose.ui.test.junit)
    androidTestImplementation(libs.koin.android.test)


    // Compose dependencies and integration libs

    implementation(libs.androidx.activity.compose)
    // Compose integration with ViewModels
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.navigation.compose)

    // Compose dependencies
    implementation(compose.runtime)
    implementation(compose.ui)
    // Tooling support (Previews, etc.)
    implementation(libs.compose.ui.toolingPreview)
    // Foundation (Border, Background, Box, Image, Scroll, shapes, animations, etc.)
    implementation(compose.foundation)
    // Material Design
    implementation(compose.material)
    implementation(libs.androidx.material.icons.extended)
    // Navigation 3 libs
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

tasks.withType<AbstractTestTask> {
    // Add test output to gradle console
    afterSuite(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
        if (desc.parent == null) { // will match the outermost suite
            println("Results: ${result.resultType} (${result.testCount} tests, ${result.successfulTestCount} successes, ${result.failedTestCount} failures, ${result.skippedTestCount} skipped)")
        }
    }))

    // Disable unit tests for release build type (Robolectric limitations)
    if (name.contains("testReleaseUnitTest")) {
        enabled = false
    }
}


/*
 * Kover code coverage configs for library modules
 */
dependencies {
    kover(project(":resaca"))
    kover(project(":resacahilt"))
    kover(project(":resacakoin"))
}

kover {
    reports {
        // filters for all report types of all build variants
        filters {
            excludes {
                androidGeneratedClasses()
                annotatedBy("androidx.compose.ui.tooling.preview.Preview") // Exclude Previews from code coverage
            }
        }
        variant("debug") {
            filters {
                excludes {
                    androidGeneratedClasses()
                    classes(
                        "*Fragment",
                        "*Fragment\$*",
                        "*Activity",
                        "*Activity\$*",
                        "*.databinding.*",
                        "*.BuildConfig",
                        "*ComposableSingletons\$*",
                        "*ColorKt*",
                        "*ThemeKt*",
                        "*TypeKt*",
                        "hilt_aggregated_deps.*",
                        "*dagger.hilt.internal.aggregatedroot.codegen*",
                        "*com.sebaslogen.resacaapp.sample*", // Ignore sample code
                        "*com.sebaslogen.resaca.ViewModelNewInstanceFactory*", // Skip class that is not used in code but used as backup for ViewModelFactory
                    )
                }
            }

            verify {
                rule {
                    minBound(90)
                }
            }
        }
    }
}
