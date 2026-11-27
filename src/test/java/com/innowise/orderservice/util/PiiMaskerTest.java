package com.innowise.orderservice.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiMaskerTest {

    @Test
    void maskEmails_shouldHideLocalAndDomainParts() {
        assertEquals("j***@e***", PiiMasker.maskEmails("john@example.com"));
    }

    @Test
    void maskEmails_shouldMaskEveryEmailInsideAJsonBody() {
        String masked = PiiMasker.maskEmails(
                "{\"email\":\"john@example.com\",\"other\":\"ann@mail.org\"}");

        assertFalse(masked.contains("john"));
        assertFalse(masked.contains("ann@"));
        assertTrue(masked.contains("j***@e***"));
        assertTrue(masked.contains("a***@m***"));
    }

    @Test
    void maskEmails_shouldKeepNonEmailTextAndHandleNull() {
        String uuid = UUID.randomUUID().toString();

        assertEquals(uuid, PiiMasker.maskEmails(uuid));
        assertNull(PiiMasker.maskEmails(null));
    }

    @Test
    void sanitizeBody_shouldTruncateLongBodies() {
        String sanitized = PiiMasker.sanitizeBody("x".repeat(400));

        assertEquals(303, sanitized.length());
        assertTrue(sanitized.endsWith("..."));
    }

    @Test
    void sanitizeBody_shouldHandleNull() {
        assertNull(PiiMasker.sanitizeBody(null));
    }
}
