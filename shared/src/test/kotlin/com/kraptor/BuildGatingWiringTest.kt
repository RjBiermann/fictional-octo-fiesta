package com.kraptor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wiring assertions for the build-gating epic (issue #918, epic #910).
 *
 * #920's classifier only pays off while both consumers keep consuming it:
 * `.github/workflows/build.yml` must end the run on `none` and build only the affected
 * providers on a subset (#919), and `.github/workflows/version-bump.yml` must bump exactly
 * those providers with its loop guards intact (#921). Both are plain Actions YAML, so no
 * other check in CI notices when a step loses its `if:` guard or a case arm is reworded —
 * the next push simply rebuilds (or republishes) the whole fleet and stays green.
 *
 * These are structural assertions over the committed workflow text, not a GitHub Actions
 * emulator. Two deliberate boundaries:
 *
 * - The `paths:` pre-filter behaviour itself cannot be unit-tested locally (agreed on
 *   #918); it is dry evidence on the PR. Here we only pin that a classifier change still
 *   produces a run, which is what keeps the gate self-healing.
 * - The decision table lives in [AffectedSetTest] — its fixtures double as the epic's
 *   classification evidence — and the rewrite in [VersionBumpTest]. This class pins the
 *   seam between the scripts and the workflows that call them.
 */
class BuildGatingWiringTest {

    private val repoRoot = findRepoRoot()
    private val buildFile = File(repoRoot, ".github/workflows/build.yml")
    private val bumpFile = File(repoRoot, ".github/workflows/version-bump.yml")
    private val buildText = buildFile.readText()
    private val bumpText = bumpFile.readText()
    private val buildSteps = stepsOf(buildFile)
    private val bumpSteps = stepsOf(bumpFile)

    private class Step(val name: String, val body: String) {
        fun has(text: String) = body.contains(text)
        fun matches(regex: Regex) = regex.containsMatchIn(body)
    }

    @Test
    fun `build workflow consumes the classifier contract`() {
        val classify = buildSteps.named("Classify affected set")
        assertTrue(
            "build.yml must classify through the shared script, not an inline diff",
            classify.has("bash scripts/affected-set.sh --diff"),
        )
        // The contract is none | <Provider> lines | ALL; each verdict has exactly one mode.
        assertTrue("ALL (full) must map to mode=full", classify.matches(Regex("""ALL\)\s+mode=full""")))
        assertTrue("none must map to mode=none", classify.matches(Regex("""none\)\s+mode=none""")))
        assertTrue("every other verdict is a subset", classify.matches(Regex("""\*\)\s+mode=subset""")))
        // Fail closed: a classifier error is a full build, never a silent skip.
        assertTrue(
            "an unclassifiable diff must fail closed to a full build",
            classify.has("|| classified=ALL"),
        )
        // No pushed range (dispatch, new branch, zero SHA) is a forced full build too.
        // `mode=full` alone is already satisfied by the ALL arm above, so pin the branch
        // itself: its event/SHA test and the log line only that branch prints.
        assertTrue(
            "a dispatch/zero-SHA push must force a full build",
            classify.has("\"\$EVENT_NAME\" != \"push\"") &&
                classify.has("-z \"\$BEFORE\"") &&
                classify.has("\"\$BEFORE\" =~ ^0+\$") &&
                classify.has("no pushed range to classify"),
        )
    }

    @Test
    fun `a none verdict stops the run before any build or publish step`() {
        val afterClassifier = buildSteps.dropWhile { it.name != "Classify affected set" }.drop(1)
        assertTrue("build.yml lost every step after the classifier", afterClassifier.size > 5)
        for (step in afterClassifier) {
            assertTrue(
                "build.yml step '${step.name}' must skip on a none verdict " +
                    "(if: steps.affected.outputs.mode != 'none') — otherwise a docs-only " +
                    "push still runs cleanup, publish and the Codeberg mirror",
                step.matches(Regex("""if:.*steps\.affected\.outputs\.mode != 'none'""")),
            )
        }
        // Named explicitly: these three are the steps whose accidental un-gating publishes.
        buildSteps.named("Clean old builds")
        buildSteps.named("Build Plugins")
        buildSteps.named("Push builds")
    }

    @Test
    fun `subset verdict builds and republishes only the affected providers`() {
        val build = buildSteps.named("Build Plugins")
        assertTrue("the affected set must drive the task list", build.has("PROVIDERS"))
        assertTrue("full mode keeps the whole-fleet build", build.has("./gradlew test make makePluginsJson"))
        assertTrue("subset mode builds per affected provider", build.has(":test") && build.has(":make"))
        assertTrue(
            "the registry must be regenerated on every non-none run — including the subset arm",
            build.has("./gradlew \"\${tasks[@]}\" makePluginsJson"),
        )
        assertTrue(
            "only the rebuilt providers may be republished",
            build.has("cp \"\$provider/build/\$provider.cs3\""),
        )
    }

    @Test
    fun `subset cleanup drops only providers that no longer exist`() {
        val clean = buildSteps.named("Clean old builds")
        assertTrue("full mode still wipes the builds branch", clean.has("rm -f ./*.cs3"))
        assertTrue(
            "subset mode must decide per artifact, by whether the provider still has a build file",
            clean.has("[[ -f \"\$GITHUB_WORKSPACE/src/\$name/build.gradle.kts\" ]] || rm -f \"\$cs3\""),
        )
    }

    @Test
    fun `a classifier change still produces a build run`() {
        assertTrue(
            "build.yml's push allow-list must include scripts/affected-set.sh — otherwise a " +
                "classifier edit ships ungated and the next push gates on unexercised code",
            buildText.contains("- 'scripts/affected-set.sh'"),
        )
    }

    @Test
    fun `both consumers read the changed-file list from the same classifier`() {
        assertTrue("build.yml must call the shared classifier", buildText.contains("scripts/affected-set.sh"))
        assertTrue("version-bump.yml must call the shared classifier", bumpText.contains("scripts/affected-set.sh"))
        // One contract, one implementation: a second inline classifier would drift.
        assertFalse("build.yml must not classify paths inline", buildText.contains("git diff --name-only"))
        assertFalse("version-bump.yml must not classify paths inline", bumpText.contains("git diff --name-only"))
    }

    @Test
    fun `version bump classifies the PR head against its merge base`() {
        assertTrue("opened + synchronize are the bump triggers", Regex("""types: \[opened, synchronize]""").containsMatchIn(bumpText))
        val classify = bumpSteps.named("Classify affected providers")
        assertTrue("the bump reuses the shared classifier", classify.has("bash scripts/affected-set.sh"))
        assertTrue("three-dot diff: only the PR's own changes may bump providers", classify.has("..."))
        assertTrue(
            "a test-only shared change must not bump all 26 providers",
            classify.has("classify_args=(--ignore-shared-tests \"\${classify_args[@]}\")"),
        )
        assertTrue("an unclassifiable PR writes nothing", classify.has("if ! classified="))
    }

    @Test
    fun `version bump loop guard holds for an App-authenticated push`() {
        val guard = bumpSteps.named("Loop guard")
        assertTrue("the bump commit carries a marker", guard.has("[skip version-bump]"))
        assertTrue(
            "the marker alone is prose anyone can write — bind it to the Actions bot author",
            guard.has("41898282+github-actions[bot]@users.noreply.github.com"),
        )
        assertTrue(
            "a bump commit must carry provider build files only",
            guard.has("^[^/]+/build\\.gradle\\.kts$"),
        )
        // The write step repeats the file-set check: the guard only inspects HEAD.
        val commit = bumpSteps.named("Commit and push the bump")
        assertTrue(
            "the commit step must refuse unexpected staged paths",
            commit.has("^[^/]+/build\\.gradle\\.kts$"),
        )
        assertTrue("the commit step pushes to the PR branch", commit.has("HEAD:refs/heads/"))
    }

    @Test
    fun `version bump pipes the affected set into the bumper and stays a writer only`() {
        // The bump step is where #1334's two regressions lived (26 spurious bumps and a
        // version collision): pin that the affected set actually reaches the bumper with
        // both fixes, not just that some token exists somewhere in the file.
        val bump = bumpSteps.named("Bump affected providers")
        assertTrue(
            "the affected set must be piped into the bumper",
            bump.has("printf '%s\\n' \"\$AFFECTED\"") &&
                bump.has("| bash scripts/bump-provider-versions.sh"),
        )
        assertTrue("the bump note must be passed through", bump.has("--note \"\$note\""))
        assertTrue(
            "the bump must use the target branch's version as its floor (#1334)",
            bump.has("--base-revision \"\$BASE_SHA\""),
        )
        for (forbidden in listOf("gh pr merge", "gh pr review", "gh pr edit", "gh issue", "gh api", "--add-label")) {
            assertFalse(
                "version-bump.yml must never call '$forbidden' — it only writes bump commits",
                bumpText.contains(forbidden),
            )
        }
        // Scope the token: contents: write is the push; anything wider is a boundary break.
        assertTrue("the bump needs contents: write", bumpText.contains("contents: write"))
        assertFalse("version-bump.yml must not request pull-requests: write", bumpText.contains("pull-requests: write"))
        assertFalse("version-bump.yml must not request issues: write", bumpText.contains("issues: write"))
        assertFalse("version-bump.yml must not request actions: write", bumpText.contains("actions: write"))
    }

    @Test
    fun `fork and dependabot PRs are out of scope`() {
        assertTrue(
            "same-repo PRs only — there is no branch here to push a fork's bump to",
            bumpText.contains("pull_request.head.repo.full_name == github.repository"),
        )
        assertTrue(
            "dependabot runs read-only and cannot mint the App token, so it is skipped",
            bumpText.contains("dependabot[bot]"),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun stepsOf(workflow: File): List<Step> {
        // `- name:` sits at six spaces inside `jobs.<job>.steps`.
        val stepName = Regex("""^ {6}- name: (.+)$""")
        val lines = workflow.readLines()
        val steps = mutableListOf<Step>()
        var i = 0
        while (i < lines.size) {
            val match = stepName.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val body = StringBuilder()
            i++
            while (i < lines.size && stepName.find(lines[i]) == null) {
                body.append(lines[i]).append('\n')
                i++
            }
            steps.add(Step(match.groupValues[1].trim(), body.toString()))
        }
        return steps
    }

    private fun List<Step>.named(name: String): Step =
        firstOrNull { it.name == name }
            ?: error("step '$name' is missing from this workflow — re-check the build-gating wiring (#918)")

    /** Gradle runs the test task with the provider project as working dir; walk up to the root. */
    private fun findRepoRoot(): File {
        System.getProperty("repoRoot")?.let { return File(it).absoluteFile }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, ".github/workflows").isDirectory) {
                return dir
            }
            dir = dir.parentFile
        }
        error("cannot locate repo root from ${System.getProperty("user.dir")}")
    }
}
