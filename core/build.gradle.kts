// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
}
