package com.reclamos.backend.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quita datos personales del texto antes de enviarlo al proveedor de IA: mails,
 * enlaces, teléfonos, documentos y números largos (CBU, tarjetas). Las
 * direcciones y los números de calle cortos se conservan porque la
 * clasificación los usa.
 */
public final class PiiRedactor {
    private static final Pattern EMAIL = Pattern.compile("[\\w.%+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern DOCUMENT = Pattern.compile("\\b\\d{1,2}\\.\\d{3}\\.\\d{3}\\b");
    private static final Pattern NUMBER_RUN = Pattern.compile("\\+?\\d[\\d\\s().-]{6,}\\d");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final int MIN_DIGITS = 8;

    private PiiRedactor() {
    }

    public static String redact(String text) {
        if (text == null) {
            return "";
        }
        String result = EMAIL.matcher(text).replaceAll("[email]");
        result = URL.matcher(result).replaceAll("[enlace]");
        result = DOCUMENT.matcher(result).replaceAll("[documento]");
        Matcher numbers = NUMBER_RUN.matcher(result);
        StringBuilder out = new StringBuilder();
        while (numbers.find()) {
            String run = numbers.group();
            numbers.appendReplacement(out, Matcher.quoteReplacement(shouldRedact(run) ? "[número]" : run));
        }
        numbers.appendTail(out);
        return out.toString();
    }

    private static boolean shouldRedact(String run) {
        if (ISO_DATE.matcher(run).matches()) {
            return false;
        }
        return run.chars().filter(Character::isDigit).count() >= MIN_DIGITS;
    }
}
