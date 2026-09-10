package ai.voiceflow.voiceflow_mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalFormattingBenchmarkTest {
    @Test fun `text only results preserve recovery keys without claiming ASR timings or quality`() {
        val cases = listOf(
            Triple("hello", false, "guard_rejected"),
            Triple("hello", true, "unchanged"),
            Triple("Hello.", true, "changed_review_required"),
        )
        for ((text, accepted, outcome) in cases) {
            val result = LocalFormattingBenchmarkPolicy.resultLabels("hello", text, accepted)
            for (key in listOf("postStopMs", "audioMs", "cleanupMs")) {
                assertTrue("Recovery requires $key to be present.", result.has(key))
                assertTrue("$key is not an audio timing measurement.", result.isNull(key))
            }
            assertFalse(result.getBoolean("paced"))
            assertFalse(result.getBoolean("pipeline"))
            assertEquals(outcome, result.getString("outcome"))
            assertTrue(result.getBoolean("reviewRequired"))
        }
    }

    @Test fun `formatting text and native generation limits are bounded`() {
        LocalFormattingBenchmarkPolicy.validateInput("hello", 4, 60_000, 1024)
        for (text in listOf("", "  ", "a".repeat(6001))) rejects {
            LocalFormattingBenchmarkPolicy.validateInput(text, 4, 60_000, 1024)
        }
        for (threads in listOf(0, 9)) rejects {
            LocalFormattingBenchmarkPolicy.validateInput("hello", threads, 60_000, 1024)
        }
        for (budget in listOf(499L, 120001L)) rejects {
            LocalFormattingBenchmarkPolicy.validateInput("hello", 4, budget, 1024)
        }
        for (tokens in listOf(63, 1025, 2048)) rejects {
            LocalFormattingBenchmarkPolicy.validateInput("hello", 4, 60_000, tokens)
        }
    }

    @Test fun `only the two exact pinned model packages are supported`() {
        val pins = LocalFormattingBenchmarkPolicy.models
        assertEquals(2, pins.size)
        assertEquals(1_834_426_016L, pins.single { it.id == "Qwen3-1.7B-Q8_0" }.bytes)
        assertEquals("061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a",
            pins.single { it.id == "Qwen3-1.7B-Q8_0" }.sha256)
        assertEquals(LocalCleanupModel.SHA256, pins.single { it.id == LocalCleanupModel.ID }.sha256)
    }

    @Test fun `model paths cannot escape the benchmark folder or exploit sibling prefixes`() {
        val temporary = Files.createTempDirectory("formatting-policy-test").toFile()
        try {
            val root = File(temporary, "benchmarks").apply { mkdir() }
            val inside = File(root, "test.gguf").apply { createNewFile() }
            val outside = File(temporary, "outside.gguf").apply { createNewFile() }
            val sibling = File(temporary, "benchmarks-other").apply { mkdir() }
            val siblingFile = File(sibling, "test.gguf").apply { createNewFile() }
            assertEquals(inside.canonicalFile, LocalFormattingBenchmarkPolicy.sandboxFile(root, inside.path))
            rejects { LocalFormattingBenchmarkPolicy.sandboxFile(root, outside.path) }
            rejects { LocalFormattingBenchmarkPolicy.sandboxFile(root, siblingFile.path) }
            rejects { LocalFormattingBenchmarkPolicy.sandboxFile(root, File(root, "../outside.gguf").path) }
            rejects { LocalFormattingBenchmarkPolicy.sandboxFile(root, root.path) }
            rejects { LocalFormattingBenchmarkPolicy.sandboxFile(root, File(root, "missing.gguf").path) }
            val link = File(root, "linked.gguf")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            rejects { LocalFormattingBenchmarkPolicy.sandboxFile(root, link.path) }
        } finally { temporary.deleteRecursively() }
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected invalid benchmark input to be rejected.") }
        catch (_: IllegalArgumentException) { }
    }
}
