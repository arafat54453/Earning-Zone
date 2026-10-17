pluginManagement {
    repositories { google(); maven("https://maven-central.storage-download.googleapis.com/maven2/"); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); maven("https://maven-central.storage-download.googleapis.com/maven2/"); mavenCentral()
        flatDir { dirs("${rootDir}/app/libs") }
    }
}
rootProject.name = "Tun2Socks"
include(":app")
