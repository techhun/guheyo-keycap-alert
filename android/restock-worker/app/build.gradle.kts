plugins {
    id("com.android.application")
}

android {
    namespace = "com.techhun.keyboardalert.restock"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.techhun.keyboardalert.restock"
        minSdk = 26
        targetSdk = 35
        versionCode = 27
        versionName = "0.13.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
