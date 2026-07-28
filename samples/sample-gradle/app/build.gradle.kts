plugins {
    id("java")
    id("application")
}

dependencies {
    implementation(project(":api"))
    implementation("org.springframework:spring-context:6.1.14")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

application {
    mainClass.set("com.example.app.AppMain")
}
