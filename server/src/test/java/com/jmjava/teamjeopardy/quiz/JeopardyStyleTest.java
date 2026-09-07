package com.jmjava.teamjeopardy.quiz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeopardyStyleTest {

    @Test
    void formatsAnswersAsQuestions() {
        assertEquals("What is Strategy?", JeopardyStyle.whatIs("Strategy"));
        assertEquals("Who is alex?", JeopardyStyle.whoIs("alex"));
        assertEquals("What is PR #12?", JeopardyStyle.whatIs("What is PR #12?"));
    }

    @Test
    void redactsAnswerTokens() {
        assertEquals("class ___ {", JeopardyStyle.redact("class Foo {", "Foo"));
        assertEquals("void ___(int x)", JeopardyStyle.redact("void save(int x)", "save"));
    }

    @Test
    void detectsLeaksAndInterrogatives() {
        assertTrue(JeopardyStyle.promptLeaksAnswer("Name the Widget type.", "What is Widget?"));
        assertFalse(JeopardyStyle.promptLeaksAnswer("This type lives under src/main.", "What is Widget?"));
        assertTrue(JeopardyStyle.looksLikeQuestion("How many modules were indexed?"));
        assertFalse(JeopardyStyle.looksLikeQuestion("Ingest recorded this many Maven modules."));
    }
}
