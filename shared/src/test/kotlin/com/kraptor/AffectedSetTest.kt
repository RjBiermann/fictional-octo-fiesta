package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Red→green for the affected-set classifier (issue #920, epic #910):
 * `scripts/affected-set.sh` maps a changed-file list to `none` | provider names | `ALL`.
 *
 * Fixture-driven (ADR-0005): every `<case>.txt` under `/affected-set/` is a changed-file
 * list piped to the script, `<case>.expected` is the exact stdout contract. No HTTP, no
 * `MainAPI` — this only pins the build-gating decision, never a live site.
 */
class AffectedSetTest {

    /** Every classification fixture; `<name>.txt` → stdin, `<name>.expected` → stdout. */
    private val cases = listOf(
        "empty",                    // empty diff → nothing to build
        "docs-only",                // docs/** → none
        "root-markdown",            // root *.md / FINDINGS-*.md → none
        "non-build-infra",          // audits/, scripts/, .pi/, skills/, LICENSE → none
        "non-build-github",         // .github/** except build.yml → none
        "single-provider",          // one provider dir → that provider
        "multi-provider",           // several provider dirs → all of them, sorted+deduped
        "shared-splice",            // shared/main splices into every provider → ALL
        "shared-test-only",         // shared/** is ALL even for tests/fixtures
        "root-build",               // root build.gradle.kts + gradle wrappers → ALL
        "settings-and-properties",  // settings.gradle.kts / gradle.properties → ALL
        "gradle-infra",             // gradle/, gradlelibs/, vendor/ → ALL
        "repo-json",                // plugin registry input → ALL
        "build-workflow",           // .github/workflows/build.yml → ALL
        "markdown-and-provider",    // markdown + provider mixed → subset (md ignored)
        "unknown-top-level",        // unattributable directory → ALL (fail closed)
        "shared-and-provider",      // full dominates subset → ALL
    )

    private val repoRoot = findRepoRoot()
    private val script = File(repoRoot, "scripts/affected-set.sh")

    private data class Run(val exitCode: Int, val stdout: String, val stderr: String)

    @Test
    fun `every fixture classifies as recorded`() {
        for (name in cases) {
            val run = run(fixture("$name.txt"))
            assertEquals("$name: stderr\n${run.stderr}", 0, run.exitCode)
            assertEquals("$name: classification", resource("$name.expected"), run.stdout)
        }
    }

    @Test
    fun `full and none outputs are single lines with a trailing newline`() {
        assertEquals("ALL\n", run(fixture("shared-splice.txt")).stdout)
        assertEquals("none\n", run(fixture("docs-only.txt")).stdout)
    }

    @Test
    fun `version bumps ignore shared test sources`() {
        val run = run(fixture("shared-test-only.txt"), repoRoot, "--ignore-shared-tests")
        assertEquals(run.stderr, 0, run.exitCode)
        assertEquals("none\n", run.stdout)
    }

    @Test
    fun `ignoring test sources does not mask a real shared source change`() {
        val changed = "shared/src/test/kotlin/com/kraptor/FooTest.kt\n" +
            "shared/src/main/kotlin/com/kraptor/Foo.kt\n"
        val run = run(changed, repoRoot, "--ignore-shared-tests")
        assertEquals(run.stderr, 0, run.exitCode)
        assertEquals("ALL\n", run.stdout)
    }

    @Test
    fun `blank diff lines classify none`() {
        val run = run("\n  \n\n")
        assertEquals(0, run.exitCode)
        assertEquals("none\n", run.stdout)
    }

    @Test
    fun `provider set comes from top-level build files, not a hard-coded list`() {
        val root = Files.createTempDirectory("affected-set-root").toFile()
        try {
            File(root, "ZzzFixtureProvider/src").mkdirs()
            File(root, "ZzzFixtureProvider/build.gradle.kts").writeText("")
            File(root, "not-a-provider").mkdirs()
            File(root, "not-a-provider/notes.txt").writeText("")

            val known = run("ZzzFixtureProvider/src/main/kotlin/Foo.kt\n", root)
            assertEquals("ZzzFixtureProvider\n", known.stdout)

            val unknown = run("not-a-provider/notes.txt\n", root)
            assertEquals("ALL\n", unknown.stdout)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `git diff adapter feeds the same classifier`() {
        assumeTrue("not a git checkout", File(repoRoot, ".git").exists())
        val run = run("", repoRoot, "--diff", "HEAD...HEAD")
        assertEquals(run.stderr, 0, run.exitCode)
        assertEquals("none\n", run.stdout)
    }

    @Test
    fun `unknown option is a usage error`() {
        val run = run("", repoRoot, "--not-an-option")
        assertEquals(2, run.exitCode)
        assertEquals("", run.stdout)
    }

    private fun run(changedPaths: String, root: File = repoRoot, vararg args: String): Run {
        val command = mutableListOf("bash", script.absolutePath, "--repo-root", root.absolutePath)
        command.addAll(args)
        val process = ProcessBuilder(command).directory(repoRoot).start()
        process.outputStream.use { it.write(changedPaths.toByteArray()) }
        // Read stderr concurrently: a child that fills that pipe while we block on stdout
        // would otherwise deadlock, and a hang must fail with a named assertion.
        val stderr = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
        val stdout = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("affected-set.sh timed out", finished)
        return Run(process.exitValue(), stdout, stderr.get(5, TimeUnit.SECONDS))
    }

    private fun fixture(name: String) = resource(name)

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/affected-set/$name")?.bufferedReader()?.readText()
            ?: error("missing test resource /affected-set/$name")

    /** Gradle runs the test task with the provider project as working dir; walk up to the root. */
    private fun findRepoRoot(): File {
        System.getProperty("repoRoot")?.let { return File(it).absoluteFile }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "scripts").isDirectory) return dir
            dir = dir.parentFile
        }
        error("cannot locate repo root from ${System.getProperty("user.dir")}")
    }
}
