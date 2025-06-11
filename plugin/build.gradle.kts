
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val pluginName = "godot_exoplayer"
val pluginPackageName = "org.godotengine.plugin.android.godot_exoplayer"

android {
    namespace = pluginPackageName
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 24
        targetSdk= 35
        manifestPlaceholders["godotPluginName"] = pluginName
        manifestPlaceholders["godotPluginPackageName"] = pluginPackageName
        buildConfigField("String", "GODOT_PLUGIN_NAME", "\"$pluginName\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.godotengine:godot:4.4.1.stable")
    // exoplayer dependencies
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
}

// Set the archivesBaseName using the public API (requires the base plugin to be applied)
base {
    archivesName = pluginName
}

// BUILD TASKS DEFINITION
val copyDebugAARToDemoAddons by tasks.registering(Copy::class) {
    description = "Copies the generated debug AAR binary to the plugin's addons directory"
    from("build/outputs/aar")
    include("$pluginName-debug.aar")
    into("demo/addons/$pluginName/bin/debug")
}

val copyReleaseAARToDemoAddons by tasks.registering(Copy::class) {
    description = "Copies the generated release AAR binary to the plugin's addons directory"
    from("build/outputs/aar")
    include("$pluginName-release.aar")
    into("demo/addons/$pluginName/bin/release")
}

val cleanDemoAddons by tasks.registering(Delete::class) {
    delete("demo/addons/$pluginName")
}

val copyAddonsToDemo by tasks.registering(Copy::class) {
    description = "Copies the export scripts templates to the plugin's addons directory"
    dependsOn(cleanDemoAddons)
    // Both debug and release tasks should be finalized after this copy finishes
    finalizedBy(copyDebugAARToDemoAddons)
    finalizedBy(copyReleaseAARToDemoAddons)
    from("export_scripts_template")
    into("demo/addons/$pluginName")
}

tasks.named("assemble").configure {
    finalizedBy(copyAddonsToDemo)
}

tasks.named<Delete>("clean").configure {
    dependsOn(cleanDemoAddons)
}
