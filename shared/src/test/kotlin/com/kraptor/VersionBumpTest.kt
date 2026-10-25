package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Red→green for the PR-time version bumper (issue #921, epic #910):
 * `scripts/bump-provider-versions.sh` turns the affected-set vocabulary
 * (`none` | provider names | `ALL`) into incremented `version = N` lines.
 *
 * Fixture-driven (ADR-0005): every `<case>/build.gradle.kts` under `/version-bump/` is a
 * provider build file copied into a temp tree, `<case>/build.gradle.kts.expected` the exact
 * bytes after the bump. No HTTP, no `MainAPI` — this pins the mechanical rewrite only; the
 * workflow wiring (loop guard, commit, push) is pipeline Verification territory.
 */
class VersionBumpTest {

    private val note = "#1331: version bump"

    /** Cases whose bump is a pure rewrite: fixture bytes → expected bytes. */
    private val rewriteCases = listOf(
        "with-comment",      // real provider style: `version = N // #issue: why` → new note
        "plain",             // no trailing comment at all
        "first-match-only",  // only the first `version =` line is the plugin version
        "crlf",              // CRLF build file keeps its line endings
        "no-final-newline",  // `}` without a trailing newline stays that way
    )

    private val repoRoot = findRepoRoot()
    private val script = File(repoRoot, "scripts/bump-provider-versions.sh")

    private data class Run(val exitCode: Int, val stdout: String, val stderr: String)

    @Test
    fun `fixture build files are bumped exactly as recorded`() {
        for (case in rewriteCases) {
            val root = providerRoot(case)
            try {
                val run = run("FixtureProvider\n", root)
                assertEquals("$case: stderr\n${run.stderr}", 0, run.exitCode)
                assertEquals("$case: changed files", "FixtureProvider/build.gradle.kts\n", run.stdout)
                assertEquals("$case: bumped build file", expected(case), readText(buildFile(root)))
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `a file already carrying this run's note is untouched`() {
        val root = providerRoot("already-bumped")
        try {
            val before = readText(buildFile(root))
            val run = run("FixtureProvider\n", root)
            assertEquals(run.stderr, 0, run.exitCode)
            assertEquals("unchanged file is not reported", "", run.stdout)
            assertEquals("version must not move twice", before, readText(buildFile(root)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `ALL bumps every provider discovered from build files`() {
        val root = Files.createTempDirectory("version-bump-root").toFile()
        try {
            writeBuildFile(root, "ZzzProvider", "version = 3\n\ncloudstream {\n    authors = listOf(\"kraptor\")\n}\n")
            writeBuildFile(root, "AaaProvider", "version = 11 // #778: shared xvideos registry row (fleet bump)\n")
            writeBuildFile(root, "MmmProvider", "version = 0\n")
            val notAProvider = File(root, "not-a-provider")
            notAProvider.mkdirs()
            File(notAProvider, "notes.txt").writeText("not a provider")

            val run = run("ALL\n", root)
            assertEquals(run.stderr, 0, run.exitCode)
            assertEquals(
                "AaaProvider/build.gradle.kts\nMmmProvider/build.gradle.kts\nZzzProvider/build.gradle.kts\n",
                run.stdout,
            )
            assertTrue(readText(File(root, "AaaProvider/build.gradle.kts")).startsWith("version = 12 // $note"))
            assertTrue(readText(File(root, "MmmProvider/build.gradle.kts")).startsWith("version = 1 // $note"))
            assertTrue(readText(File(root, "ZzzProvider/build.gradle.kts")).startsWith("version = 4 // $note"))
            assertEquals("non-provider files are never touched", "not a provider", readText(File(root, "not-a-provider/notes.txt")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `none or an empty list bumps nothing`() {
        for (input in listOf("none\n", "", "\n  \n")) {
            val root = providerRoot("plain")
            try {
                val before = readText(buildFile(root))
                val run = run(input, root)
                assertEquals(run.stderr, 0, run.exitCode)
                assertEquals("", run.stdout)
                assertEquals(before, readText(buildFile(root)))
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `re-running the bumper cannot double-bump`() {
        val root = providerRoot("plain")
        try {
            assertEquals(0, run("FixtureProvider\n", root).exitCode)
            val once = readText(buildFile(root))
            val second = run("FixtureProvider\n", root)
            assertEquals(second.stderr, 0, second.exitCode)
            assertEquals("second run changes nothing", "", second.stdout)
            assertEquals(once, readText(buildFile(root)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `dry run reports the bump without writing`() {
        val root = providerRoot("with-comment")
        try {
            val before = readText(buildFile(root))
            val run = run("FixtureProvider\n", root, "--dry-run")
            assertEquals(run.stderr, 0, run.exitCode)
            assertEquals("FixtureProvider/build.gradle.kts\n", run.stdout)
            assertEquals(before, readText(buildFile(root)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unknown provider is a usage error`() {
        val root = providerRoot("plain")
        try {
            val before = readText(buildFile(root))
            val run = run("NoSuchProvider\n", root)
            assertEquals(2, run.exitCode)
            assertEquals("", run.stdout)
            assertTrue(run.stderr, run.stderr.contains("no such provider"))
            assertEquals(before, readText(buildFile(root)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a build file without a version line fails loudly`() {
        val root = providerRoot("no-version-line")
        try {
            val before = readText(buildFile(root))
            val run = run("FixtureProvider\n", root)
            assertEquals(3, run.exitCode)
            assertEquals("", run.stdout)
            assertTrue(run.stderr, run.stderr.contains("has no version = <int> line"))
            assertEquals(before, readText(buildFile(root)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a base that overtook the branch forces a fresh version`() {
        val root = Files.createTempDirectory("version-bump-git").toFile()
        try {
            val providerDir = File(root, "FixtureProvider")
            providerDir.mkdirs()
            val build = File(providerDir, "build.gradle.kts")
            // Base already published 23 (another PR moved it); this branch also bumped
            // 22 → 23 from the same starting point, so its version now collides.
            build.writeText("version = 23 // #100: version bump\n")
            git(root, "init", "-q")
            git(root, "add", ".")
            git(root, "commit", "-q", "-m", "base")
            build.writeText("version = 23 // #101: version bump\n")

            val run = run("FixtureProvider\n", root, "--base-revision", "HEAD")
            assertEquals(run.stderr, 0, run.exitCode)
            assertEquals("FixtureProvider/build.gradle.kts\n", run.stdout)
            assertEquals("version = 24 // $note (v24)\n", readText(build))

            // Ahead of the base now: a re-run (re-trigger, synchronize, rebase) is a no-op.
            val second = run("FixtureProvider\n", root, "--base-revision", "HEAD")
            assertEquals(second.stderr, 0, second.exitCode)
            assertEquals("", second.stdout)
            assertEquals("version = 24 // $note (v24)\n", readText(build))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a base revision that is not a commit is a usage error`() {
        val root = providerRoot("plain")
        try {
            val before = readText(buildFile(root))
            val run = run("FixtureProvider\n", root, "--base-revision", "nope")
            assertEquals(2, run.exitCode)
            assertTrue(run.stderr, run.stderr.contains("not a commit"))
            assertEquals(before, readText(buildFile(root)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the note is required`() {
        val root = providerRoot("plain")
        try {
            val run = run("FixtureProvider\n", root, "--note", "")
            assertEquals(2, run.exitCode)
            assertEquals("", run.stdout)
            assertTrue(run.stderr, run.stderr.contains("--note is required"))
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * The committed tree is the real input: every provider subproject must have a bumpable
     * version line (a builder template without one would only surface at PR time otherwise).
     * Dry run — this test never writes the checked-out repo.
     */
    @Test
    fun `ALL covers every committed provider build file`() {
        val expected = repoRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && File(it, "build.gradle.kts").isFile }
            .map { "${it.name}/build.gradle.kts" }
            .sorted()

        val run = run("ALL\n", repoRoot, "--dry-run")
        assertEquals(run.stderr, 0, run.exitCode)
        assertEquals(expected.joinToString("\n", postfix = "\n"), run.stdout)
        assertFalse("no provider was left without a version line", expected.isEmpty())
    }

    private fun providerRoot(case: String): File {
        val root = Files.createTempDirectory("version-bump-$case").toFile()
        writeBuildFile(root, "FixtureProvider", fixture(case))
        return root
    }

    private fun writeBuildFile(root: File, provider: String, content: String) {
        val dir = File(root, provider)
        dir.mkdirs()
        File(dir, "build.gradle.kts").writeText(content)
    }

    private fun buildFile(root: File) = File(root, "FixtureProvider/build.gradle.kts")

    private fun run(changedProviders: String, root: File, vararg args: String): Run {
        val command = mutableListOf("bash", script.absolutePath, "--repo-root", root.absolutePath)
        command.addAll(args)
        if (!args.contains("--note")) {
            command.addAll(listOf("--note", note))
        }
        val process = ProcessBuilder(command).directory(repoRoot).start()
        process.outputStream.use { it.write(changedProviders.toByteArray()) }
        // Read stderr concurrently: a child that fills that pipe while we block on stdout
        // would otherwise deadlock, and a hang must fail with a named assertion instead of
        // `IllegalThreadStateException` from exitValue().
        val stderr = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
        val stdout = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("bump script timed out", finished)
        return Run(process.exitValue(), stdout, stderr.get(5, TimeUnit.SECONDS))
    }

    private fun git(root: File, vararg args: String) {
        val command = mutableListOf(
            "git", "-C", root.absolutePath,
            "-c", "user.name=version-bump-test",
            "-c", "user.email=version-bump-test@example.invalid",
        )
        command.addAll(args)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("git ${args.joinToString(" ")} timed out", finished)
        assertEquals("git ${args.joinToString(" ")} failed: $output", 0, process.exitValue())
    }

    private fun readText(file: File) = file.readText()

    private fun fixture(case: String): String = resource("$case/build.gradle.kts")

    private fun expected(case: String): String = resource("$case/build.gradle.kts.expected")

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/version-bump/$name")?.bufferedReader()?.readText()
            ?: error("missing test resource /version-bump/$name")

    /** Gradle runs the test task with the provider project as working dir; walk up to the root. */
    private fun findRepoRoot(): File {
        System.getProperty("repoRoot")?.let { return File(it).absoluteFile }
        val start = File(System.getProperty("user.dir") ?: error("no user.dir"))
        var dir: File? = start.absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "scripts").isDirectory) return dir
            dir = dir.parentFile
        }
        error("cannot locate repo root from ${System.getProperty("user.dir")}")
    }
}
