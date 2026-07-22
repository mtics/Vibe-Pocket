import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("com.android.compose.screenshot")
    id("org.jetbrains.kotlin.plugin.compose")
}

val standardApplicationId = "au.edu.uts.vibepocket"
val releaseVersionCode = 28
val releaseVersionName = "0.14.1"
val researchNoticeAssets = layout.buildDirectory.dir("generated/researchDebugAssets")

abstract class GenerateResearchNotice @Inject constructor(
    private val files: FileSystemOperations,
) : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        files.sync {
            from(source)
            into(output)
        }
    }
}

android {
    namespace = "au.edu.uts.vibepocket"
    compileSdk = 37

    defaultConfig {
        applicationId = standardApplicationId
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    testOptions {
        screenshotTests {
            imageDifferenceThreshold = 0.0001f
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("standard") {
            dimension = "distribution"
        }
        create("research") {
            dimension = "distribution"
            applicationIdSuffix = ".research"
            versionNameSuffix = "-research"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        val research = variant.productFlavors.any { (dimension, flavor) ->
            dimension == "distribution" && flavor == "research"
        }
        if (research && variant.buildType == "release") variant.enable = false
    }
}

val generateResearchNotice = tasks.register<GenerateResearchNotice>("generateResearchNotice") {
    source.set(rootProject.layout.projectDirectory.file("../THIRD_PARTY_NOTICES.md"))
    output.set(researchNoticeAssets)
}

androidComponents.onVariants(
    androidComponents.selector()
        .withFlavor("distribution" to "research")
        .withBuildType("debug"),
) { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(
        generateResearchNotice,
        GenerateResearchNotice::output,
    )
}

val verifyStandardReleaseArtifact = tasks.register<Exec>("verifyStandardReleaseArtifact") {
    dependsOn("packageStandardRelease")
    workingDir(rootDir)
    commandLine(
        "bash",
        "scripts/verify-standard-apk.sh",
        layout.buildDirectory.file("outputs/apk/standard/release/app-standard-release-unsigned.apk")
            .get().asFile.absolutePath,
        releaseVersionCode.toString(),
        releaseVersionName,
        "release",
    )
}

val verifyStandardFeatureContract = tasks.register<Exec>("verifyStandardFeatureContract") {
    workingDir(rootDir)
    commandLine(
        "bash",
        "scripts/verify-standard-feature-rejected.sh",
        releaseVersionCode.toString(),
        releaseVersionName,
    )
}

val verifyStandardDebugArtifact = tasks.register<Exec>("verifyStandardDebugArtifact") {
    dependsOn("packageStandardDebug")
    workingDir(rootDir)
    commandLine(
        "bash",
        "scripts/verify-standard-apk.sh",
        layout.buildDirectory.file("outputs/apk/standard/debug/app-standard-debug.apk")
            .get().asFile.absolutePath,
        releaseVersionCode.toString(),
        releaseVersionName,
        "debug",
    )
}

val verifyResearchRejectedArtifact = tasks.register<Exec>("verifyResearchRejectedArtifact") {
    dependsOn("packageResearchDebug")
    workingDir(rootDir)
    commandLine(
        "bash",
        "scripts/verify-research-rejected.sh",
        layout.buildDirectory.file("outputs/apk/research/debug/app-research-debug.apk")
            .get().asFile.absolutePath,
        rootProject.layout.projectDirectory.file("../THIRD_PARTY_NOTICES.md").asFile.absolutePath,
        releaseVersionCode.toString(),
        releaseVersionName,
    )
}

val verifyStandardArtifacts = tasks.register("verifyStandardArtifacts") {
    dependsOn(
        verifyStandardDebugArtifact,
        verifyStandardFeatureContract,
        verifyStandardReleaseArtifact,
        verifyResearchRejectedArtifact,
    )
}

tasks.matching { it.name == "packageStandardRelease" }.configureEach {
    finalizedBy(verifyStandardReleaseArtifact)
}

val protectedStandardReleaseProducers = setOf(
    "assembleStandardRelease",
    "bundleStandardRelease",
    "packageStandardReleaseBundle",
    "packageStandardReleaseUniversalApk",
    "signStandardReleaseBundle",
)

tasks.matching { it.name in protectedStandardReleaseProducers }.configureEach {
    dependsOn(verifyStandardReleaseArtifact)
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(verifyStandardArtifacts, "validateScreenshotTest")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4-accessibility")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")
    screenshotTestImplementation("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha15")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
