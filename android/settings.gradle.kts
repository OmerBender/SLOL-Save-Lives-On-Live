pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
        maven { url = uri("https://maven.aliyun.com/repository/public/") }
        maven { url = uri("https://jitpack.io") }
        google()
        mavenCentral()
        maven {
            url = uri("https://androidsdk.insta360.com/repository/maven-public/")
            isAllowInsecureProtocol = true
            credentials {
                username = providers.gradleProperty("INSTA360_MAVEN_USERNAME").orNull
                    ?: System.getenv("INSTA360_MAVEN_USERNAME")
                    ?: ""
                password = providers.gradleProperty("INSTA360_MAVEN_PASSWORD").orNull
                    ?: System.getenv("INSTA360_MAVEN_PASSWORD")
                    ?: ""
            }
        }
    }
}

rootProject.name = "Rescue360Detector"
include(":app")
