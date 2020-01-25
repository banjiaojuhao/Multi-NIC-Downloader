import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    kotlin("jvm")
    val kotlinVersion = "1.3.30"
    id("org.springframework.boot") version "2.1.4.RELEASE"
    id("org.jetbrains.kotlin.plugin.spring") version kotlinVersion
    id("org.jetbrains.kotlin.plugin.jpa") version kotlinVersion
    id("io.spring.dependency-management") version "1.0.6.RELEASE"
}

tasks {
    "bootJar"(BootJar::class) {
//        archiveName = "app.jar"
        mainClassName = "cn.banjiaojuhao.downloader.server.Application"
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(project(":engine"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.2.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.netty:netty-all:4.1.36.Final")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.apache.httpcomponents:httpclient")
}
