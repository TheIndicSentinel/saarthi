import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolveException
import org.gradle.api.attributes.Attribute

/**
 * Enables Gradle dependency locking on resolvable Android compile/runtime
 * classpaths (variant + JVM unit-test) and registers `resolveAndLockAll`.
 *
 * Direct library versions are already pinned in `gradle/libs.versions.toml`.
 * Locking pins **transitive** versions so a poisoned or unexpected upgrade
 * cannot silently resolve on the next build.
 *
 * ## Refresh after a version-catalog bump
 *
 * Configuration cache cannot persist lock state. Keep
 * `org.gradle.configuration-cache=true` in `gradle.properties` and pass
 * `--no-configuration-cache` only for this invocation:
 *
 * ```
 * ./gradlew resolveAndLockAll --write-locks --no-configuration-cache
 * ```
 *
 * Then commit every module `gradle.lockfile`. A root `settings-gradle.lockfile`
 * may appear as a version-catalog side effect; it is gitignored (plugin
 * versions stay catalog-pinned).
 *
 * The included `build-logic` build is **not** locked (plugin versions are
 * catalog-pinned). Instrumented-test, lint, and Kotlin-compiler classpaths
 * are ignored — they are unresolvable or noisy under AGP.
 *
 * `org.jetbrains.kotlin:kotlin-stdlib-common` is listed in
 * [org.gradle.api.artifacts.dsl.DependencyLockingHandler.ignoredDependencies]
 * because AGP consistent-resolution injects it into runtime classpaths when
 * compileClasspath was resolved in the same build, but `--write-locks` does
 * not persist that synthetic edge. `kotlin-stdlib` itself remains locked.
 */
fun Project.configureSaarthiDependencyLocking() {
    dependencyLocking {
        ignoredDependencies.add("org.jetbrains.kotlin:kotlin-stdlib-common")
    }

    configurations.configureEach {
        if (isLockableClasspath(name)) {
            resolutionStrategy.activateDependencyLocking()
        }
    }

    tasks.register("resolveAndLockAll") {
        group = "locking"
        description =
            "Resolves lockable Android compile/runtime classpaths so " +
                "`--write-locks` can write gradle.lockfile. " +
                "Usage: ./gradlew resolveAndLockAll --write-locks --no-configuration-cache"
        notCompatibleWithConfigurationCache(
            "Filters and resolves configurations at execution time to write gradle.lockfile",
        )
        doFirst {
            check(gradle.startParameter.isWriteDependencyLocks) {
                "$path must be run with --write-locks. " +
                    "After a catalog bump: ./gradlew resolveAndLockAll --write-locks --no-configuration-cache"
            }
        }
        doLast {
            val lockable = configurations.filter { cfg ->
                cfg.isCanBeResolved && isLockableClasspath(cfg.name)
            }.sortedWith(
                compareBy<Configuration> { cfg ->
                    if (cfg.name.contains("Compile")) 0 else 1
                }.thenBy { it.name },
            )
            logger.lifecycle(
                "Resolving ${lockable.size} lockable configuration(s) in $path",
            )
            lockable.forEach { cfg ->
                logger.lifecycle("  resolve ${cfg.name}")
                cfg.resolveForDependencyLocking()
            }
        }
    }
}

private val artifactTypeAttribute: Attribute<String> =
    Attribute.of("artifactType", String::class.java)

/**
 * Prefer raw [Configuration.resolve] (same graph as configuration-cache
 * serialization). Fall back to an `android-classes-jar` artifact view when
 * AGP project-dependency variant ambiguity fails artifact selection — the
 * graph (and lock state) is already recorded at that point.
 */
private fun Configuration.resolveForDependencyLocking() {
    try {
        resolve()
    } catch (_: ResolveException) {
        incoming.artifactView {
            attributes.attribute(artifactTypeAttribute, "android-classes-jar")
        }.files.files
    }
}

/**
 * Compile/runtime graphs for app/library variants and JVM unit tests.
 *
 * Matches names such as `debugRuntimeClasspath`, `releaseCompileClasspath`,
 * `debugUnitTestCompileClasspath`. Skips lint, Kotlin compiler plugin,
 * instrumented-test, and screenshot graphs that break `--write-locks`.
 */
internal fun isLockableClasspath(configurationName: String): Boolean {
    val name = configurationName
    when {
        name.contains("lint", ignoreCase = true) -> return false
        name.contains("kotlinCompiler", ignoreCase = true) -> return false
        name.contains("kotlinNative", ignoreCase = true) -> return false
        name.contains("androidTest", ignoreCase = true) -> return false
        name.contains("screenshot", ignoreCase = true) -> return false
        name.contains("wearApp", ignoreCase = true) -> return false
        name.contains("androidJdk", ignoreCase = true) -> return false
        name == "androidApis" -> return false
    }
    return name.endsWith("CompileClasspath") || name.endsWith("RuntimeClasspath")
}
