buildscript {
    repositories {
        mavenCentral()
    }

    dependencies {
        classpath(kotlin("gradle-plugin", version = "1.3.31"))
    }
}

plugins {
    base
    kotlin("jvm") version "1.3.30" apply false
}

group = "cn.banjiaojuhao.multi-nic-downloader"
version = "0.1.0"

allprojects {

    repositories {
        mavenCentral()
        jcenter()
    }
}