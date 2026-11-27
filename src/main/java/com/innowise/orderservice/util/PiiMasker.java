package com.innowise.orderservice.util;

import java.util.regex.Pattern;

/**
 * Masks personal data before it reaches the logs. Mentor rules require logging the
 * bodies of external responses, but those bodies (and URIs) contain emails, so the
 * values are redacted and truncated first.
 */
public final class PiiMasker {

    private static final Pattern EMAIL = Pattern.compile(
            "([A-Za-z0-9])[A-Za-z0-9._%+-]*@([A-Za-z0-9])[A-Za-z0-9.-]*");
    private static final String EMAIL_REPLACEMENT = "$1***@$2***";
    private static final int MAX_BODY_LENGTH = 300;

    private PiiMasker() {
    }

    /**
     * {@code john@example.com} becomes {@code j***@e***}; null-safe.
     */
    public static String maskEmails(String text) {
        return text == null ? null : EMAIL.matcher(text).replaceAll(EMAIL_REPLACEMENT);
    }

    /**
     * Masks emails and truncates the body so a huge response cannot flood the log.
     */
    public static String sanitizeBody(String body) {
        if (body == null) {
            return null;
        }
        String masked = maskEmails(body);
        return masked.length() <= MAX_BODY_LENGTH
                ? masked
                : masked.substring(0, MAX_BODY_LENGTH) + "...";
    }
}
