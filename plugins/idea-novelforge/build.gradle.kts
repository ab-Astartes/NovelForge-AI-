plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.novelforge"
version = "0.5.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2024.2")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.novelforge.studio"
        name = "NovelForge Studio"
        version = project.version.toString()
        description = """
            AI小说锻造工坊 — 在IntelliJ IDEA内嵌NovelForge Studio面板。
            一键启动/管理本地StudioServer，通过JCEF浏览器加载Studio UI。
        """.trimIndent()
        vendor {
            name = "NovelForge"
        }
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks {
    buildPlugin {
        archiveFileName = "novelforge-studio-idea-${project.version}.zip"
    }
    patchPluginXml {
        sinceBuild = "242"
    }
}
