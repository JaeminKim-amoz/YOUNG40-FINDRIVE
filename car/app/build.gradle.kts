plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.findrive.car"
    compileSdk = 35
    useLibrary("android.car", false)
    defaultConfig { applicationId = providers.gradleProperty("findriveApplicationId").getOrElse("app.findrive.car"); minSdk = 28; targetSdk = 35; versionCode = 7; versionName = "0.5.2"; testInstrumentationRunner = "app.findrive.car.PleosSdkInstrumentation"
        val defaultOrigin = if (applicationId == "app.findrive.car.challenge") "https://findrive-review.jkim4430.workers.dev" else ""
        val serviceUrl = providers.gradleProperty("findriveServiceUrl").orElse(providers.environmentVariable("FINDRIVE_SERVICE_URL")).getOrElse(defaultOrigin)
        require(serviceUrl.isEmpty() || serviceUrl.matches(Regex("https?://[A-Za-z0-9.:-]+"))) { "Use a server origin without credentials or a path" }
        buildConfigField("String", "SERVICE_URL", "\"$serviceUrl\"")
    }
    flavorDimensions += "vehicle"
    productFlavors {
        create("simulator") { dimension = "vehicle"; applicationIdSuffix = ".simulator"; buildConfigField("boolean", "SIMULATION", "true") }
        create("pleos") { dimension = "vehicle"; buildConfigField("boolean", "SIMULATION", "false") }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    "pleosImplementation"("ai.pleos.playground:Vehicle:2.3.2")
    "pleosImplementation"("ai.pleos.playground:NaviHelper:3.0.5")
    "pleosImplementation"("ai.pleos.playground:FusedLocation:1.1.3")
    testImplementation("junit:junit:4.13.2")
}
