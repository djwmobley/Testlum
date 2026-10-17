package com.testlum.testing.framework.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutTest {

    @Test
    void namesDifferingOnlyInCaseAreTheSameOnWindowsAndDifferentElsewhere() {
        assertTrue(Layout.sameName("Users.csv", "users.csv", true));
        assertFalse(Layout.sameName("Users.csv", "users.csv", false));
        assertFalse(Layout.sameName("users.csv", "user.csv", true));
    }
}
