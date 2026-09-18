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
        versionCode = 33
        versionName = "0.13.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
