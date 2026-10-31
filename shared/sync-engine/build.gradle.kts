import java.time.Duration
import org.jetbrains.kotlin.gradle.dsl.JsSourceMapEmbedMode

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

// apps/android depends on notes:sync-engine; its includeBuild substitutes this build.
group = "notes"

ktlint {
    version.set(libs.versions.ktlint.cli.get())
}

/**
 * Text files compiled into a Kotlin file as a list of name to content: neither an APK's
 * classpath nor a browser bundle can be listed or read as files.
 */
abstract class EmbedFiles : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val from: DirectoryProperty

    @get:Input
    abstract val extension: Property<String>

    /** The generated `val`: `package.name`. */
    @get:Input
    abstract val property: Property<String>

    /** The generated file's name, which mustn't differ from another file's in the package by case alone. */
    @get:Input
    abstract val fileName: Property<String>

    @get:OutputDirectory
    abstract val into: DirectoryProperty

    @TaskAction
    fun write() {
        val pkg = property.get().substringBeforeLast('.')
        val name = property.get().substringAfterLast('.')
        val files = from.get().asFile.listFiles { f -> f.name.endsWith(extension.get()) }!!.sortedBy { it.name }
        // Only this file: one left from a renamed property would clash with it.
        into.get().asFile.deleteRecursively()
        val out = into.get().file("${pkg.replace('.', '/')}/${fileName.get()}").asFile
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine("// Generated from ${from.get().asFile.name}/ by $path.")
                appendLine("@file:Suppress(\"ktlint\")")
                appendLine()
                appendLine("package $pkg")
                appendLine()
                appendLine("internal val $name: List<Pair<String, String>> = listOf(")
                for (f in files) appendLine("    ${literal(f.name)} to ${literal(f.readText())},")
                appendLine(")")
            }
        )
    }

    private fun literal(s: String) = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }
}

val generateMigrations = tasks.register<EmbedFiles>("generateMigrations") {
    from.set(layout.projectDirectory.dir("src/commonMain/migrations"))
    extension.set(".sql")
    property.set("notes.core.db.MIGRATIONS")
    fileName.set("MigrationSql.kt")
    into.set(layout.buildDirectory.dir("generated/migrations"))
}

// The fixtures shared with the server and the web app, for tests on every target.
val generateFixtures = tasks.register<EmbedFiles>("generateFixtures") {
    from.set(layout.projectDirectory.dir("../markdown-fixtures"))
    extension.set(".json")
    property.set("notes.core.MARKDOWN_FIXTURES")
    fileName.set("MarkdownFixtures.kt")
    into.set(layout.buildDirectory.dir("generated/fixtures"))
}

kotlin {
    jvmToolchain(21)
    jvm()
    // The web app's sync engine: an ES module with .d.ts, bundled by Vite. Tests run in Node.
    js {
        outputModuleName.set("sync-engine")
        browser { testTask { enabled = false } }
        nodejs()
        binaries.library()
        generateTypeScriptDefinitions()
        useEsModules()
        compilerOptions {
            target.set("es2015")
            // Vite and the browser's devtools read the Kotlin from the maps; the .kt files aren't served.
            sourceMapEmbedSources.set(JsSourceMapEmbedMode.SOURCE_MAP_SOURCE_CONTENT_ALWAYS)
        }
    }

    compilerOptions {
        optIn.addAll(
            "kotlin.uuid.ExperimentalUuidApi",
            "kotlin.ExperimentalStdlibApi",
            "kotlin.concurrent.atomics.ExperimentalAtomicApi",
            "kotlin.js.ExperimentalJsExport"
        )
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateMigrations)
        }
        commonMain.dependencies {
            api(libs.sqlite.async)
            api(libs.sqlite)
            api(libs.coroutines.core)
            api(libs.serialization.json)
        }
        commonTest {
            kotlin.srcDir(generateFixtures)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test.common)
            implementation(libs.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.commonmark)
            implementation(libs.commonmark.strikethrough)
            implementation(libs.commonmark.tables)
            api(libs.sqlite.bundled)
            api(libs.okhttp)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.junit)
            implementation(libs.mockwebserver)
        }
        jsTest.dependencies {
            implementation(npm("@sqlite.org/sqlite-wasm", libs.versions.sqlite.wasm.get()))
        }
    }
}

// The real notesd, built once per change to its sources; sync tests start a fresh instance per test.
val serverDir = file("../../server")
val serverBin = layout.buildDirectory.file("notesd/notesd")
val buildNotesd = tasks.register<Exec>("buildNotesd") {
    workingDir = serverDir
    commandLine("go", "build", "-o", serverBin.get().asFile.absolutePath, "./cmd/notesd")
    inputs.files(fileTree(serverDir) { include("**/*.go", "**/*.sql", "go.mod", "go.sum") })
        .withPropertyName("serverSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(serverBin)
}

tasks.named<Test>("jvmTest") {
    // A hung server or sync loop fails the run instead of stalling it.
    timeout.set(Duration.ofMinutes(5))
    dependsOn(buildNotesd)
    systemProperty("notes.serverBin", serverBin.get().asFile.absolutePath)
    // More seeds for RandomSyncTest: ./gradlew jvmTest -Pnotes.seeds=1,2,3
    providers.gradleProperty("notes.seeds").orNull?.let { systemProperty("notes.seeds", it) }
    // Crypto fixtures shared with the web app; the Markdown ones are compiled in (generateFixtures).
    systemProperty("notes.sharedDir", file("..").absolutePath)
    inputs.dir(file("../crypto-fixtures"))
}

// Node comes from mise (the web app's), not a download: settings only allow declared repositories.
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().download.set(false)
}
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().download.set(false)
}
