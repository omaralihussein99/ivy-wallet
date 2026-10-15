plugins {
    id("ivy.feature")
}

android {
    namespace = "com.ivy.shopping"
}

dependencies {
    implementation(projects.shared.data.core)
    implementation(projects.shared.ui.core)
    implementation(projects.shared.ui.navigation)

    testImplementation(projects.shared.ui.testing)
}
