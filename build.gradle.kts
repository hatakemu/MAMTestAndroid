// Top-level build file
buildscript {
    repositories {
        google()
        mavenCentral()
        jcenter() // 不要なら削除可
    }
    dependencies {
        classpath("org.javassist:javassist:3.29.2-GA")
        classpath(files("${rootDir}/intune-sdk/GradlePlugin/com.microsoft.intune.mam.build.jar"))
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
