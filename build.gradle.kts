plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
    id("io.github.goooler.shadow") version "8.1.8"
}

group = "top.chancelethay"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    compileOnly("org.popcraft:chunky-common:1.4.49")
    compileOnly("me.clip:placeholderapi:2.11.7")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    shadowJar {
    }

    assemble {
        dependsOn(reobfJar)
    }
}