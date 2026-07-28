plugins {
    id("java")
}

allprojects {
    group = "com.example"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }
}
