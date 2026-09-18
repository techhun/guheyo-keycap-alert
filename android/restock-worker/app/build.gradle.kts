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
        versionCode = 29
        versionName = "0.13.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
