package com.jmjava.teamjeopardy.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WagerRulesTest {

    @Test
    void zeroOrNegativeScoreCapsAtOneThousand() {
        assertEquals(1000, WagerRules.maxWager(0));
        assertEquals(1000, WagerRules.maxWager(-200));
        assertEquals(1000, WagerRules.maxWager(3));
        assertEquals(5, WagerRules.maxWager(5));
        assertEquals(400, WagerRules.maxWager(400));
        assertTrue(WagerRules.isValid(5, 0));
        assertTrue(WagerRules.isValid(1000, 0));
        assertFalse(WagerRules.isValid(4, 0));
        assertFalse(WagerRules.isValid(1001, 0));
        assertFalse(WagerRules.isValid(500, 400));
    }
}
