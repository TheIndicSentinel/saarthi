import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("saarthi.android.library")
            pluginManager.apply("saarthi.android.compose")
            pluginManager.apply("saarthi.hilt")

            val libs = extensions.getByType<org.gradle.api.artifacts.VersionCatalogsExtension>()
                .named("libs")

            dependencies {
                // Every feature gets navigation, viewmodel, core modules
                add("implementation", libs.findLibrary("navigation-compose").get())
                add("implementation", libs.findLibrary("hilt-navigation-compose").get())
                add("implementation", libs.findLibrary("lifecycle-viewmodel-compose").get())
                add("implementation", libs.findLibrary("lifecycle-runtime-compose").get())
                add("implementation", libs.findLibrary("compose-material-icons").get())
                add("implementation", libs.findLibrary("timber").get())
                add("implementation", project(":core:core-common"))
                add("implementation", project(":core:core-ui"))
                add("implementation", project(":core:core-memory"))
                add("implementation", project(":core:core-i18n"))
            }
        }
    }
}
