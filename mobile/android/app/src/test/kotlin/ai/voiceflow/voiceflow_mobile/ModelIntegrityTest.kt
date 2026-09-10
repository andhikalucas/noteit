package ai.voiceflow.voiceflow_mobile

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelIntegrityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val abcSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun exactHashAndLengthAreBothRequired() {
        val file = temporary.newFile().apply { writeText("abc") }
        assertTrue(ModelIntegrity.verify(file, 3, abcSha))
        assertFalse(ModelIntegrity.verify(file, 4, abcSha))
        file.writeText("abd")
        assertFalse(ModelIntegrity.verify(file, 3, abcSha))
    }

    @Test fun invalidReplacementPreservesExistingBytes() {
        val target = temporary.newFile("installed").apply { writeText("original model") }
        val candidate = temporary.newFile("download").apply { writeText("bad") }
        try { ModelIntegrity.install(candidate, target, 3, abcSha); fail("Expected verification failure") }
        catch (_: IllegalStateException) { }
        assertEquals("original model", target.readText())
    }

    @Test fun verifiedReplacementInstallsWithoutDeleteGap() {
        val target = temporary.newFile("installed").apply { writeText("original model") }
        val candidate = temporary.newFile("download").apply { writeText("abc") }
        ModelIntegrity.install(candidate, target, 3, abcSha)
        assertEquals("abc", target.readText())
        assertFalse(candidate.exists())
    }

    @Test fun failedRenamePreservesCandidateAndExistingTarget() {
        val target = temporary.newFolder("directory")
        java.io.File(target, "occupied").writeText("keep")
        val candidate = temporary.newFile("download").apply { writeText("abc") }
        try { ModelIntegrity.install(candidate, target, 3, abcSha); fail("Expected install failure") }
        catch (_: IllegalStateException) { }
        assertEquals("keep", java.io.File(target, "occupied").readText())
        assertEquals("abc", candidate.readText())
    }
}
