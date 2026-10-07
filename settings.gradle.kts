pluginManagement {
    repositories {
        maven { url = uri("file:///home/hatch/m2repo") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("file:///home/hatch/m2repo") }
    }
}
rootProject.name = "NoiseMachine"
include(":app")
