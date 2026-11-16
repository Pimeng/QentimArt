plugins {
    `java-library`
    application
}

group = "io.qentimart"
version = "0.1.0"

// 不依赖 toolchain 自动下载：用运行 Gradle 的 JDK（需 ≥ 21）编译，目标字节码锁定 Java 21。
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21) // 只允许 Java 21 API，保证最低版本可用
    options.encoding = "UTF-8"
}

application {
    mainClass.set("qentimart.internal.Cli")
    applicationName = "qentimart-java"
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "qentimart.internal.Cli")
    }
}

// 端到端测试位于 tests/，由 tests/e2e.mjs 直接用 javac 编译运行，不纳入 Gradle 源集。
