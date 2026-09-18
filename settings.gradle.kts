pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
rootProject.name = "apk-scope"
include(":app")
include(":fixture")
include(":riskfixture")
include(":obfuscatedfixture")
include(":pinnedfixture")
include(":core:model")
include(":core:common")
include(":core:network")
include(":core:crossprofile")
include(":core:report")
include(":core:database")
include(":core:sandbox")
include(":core:staticanalysis")
include(":core:risk")
