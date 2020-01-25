plugins {
    kotlin("jvm")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.2.1")
    implementation("io.netty:netty-all:4.1.36.Final")
    implementation("io.github.microutils:kotlin-logging:1.6.24")
    implementation("ch.qos.logback:logback-classic:1.2.3")
}
