package ai.voiceflow.voiceflow_mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalAutocorrectTest {
    @Test fun replacementsNeverCascade() {
        assertEquals("Tiara C", PersonalAutocorrect.apply("Teeara Tiara", listOf(
            AutocorrectRule("Teeara", "Tiara"), AutocorrectRule("Tiara", "C"))))
    }

    @Test fun matchesWholeWordsIgnoringCaseWithoutDamagingMixedLanguage() {
        assertEquals("Besok Tiara, bukan Teearaku. TIARA?", PersonalAutocorrect.apply(
            "Besok teeara, bukan Teearaku. TIARA?", listOf(AutocorrectRule("Teeara", "Tiara"))))
    }

    @Test fun longerPhrasesWinAndReplacementIsLiteral() {
        assertEquals("\$team / Tiara", PersonalAutocorrect.apply("Teeara team / Teeara", listOf(
            AutocorrectRule("Teeara", "Tiara"), AutocorrectRule("Teeara team", "\$team"))))
    }

    @Test fun unicodeAndIdentifierBoundariesAreProtected() {
        assertEquals("éTeeara Teeara2 _Teeara Tiara", PersonalAutocorrect.apply(
            "éTeeara Teeara2 _Teeara Teeara", listOf(AutocorrectRule("Teeara", "Tiara"))))
    }
}
