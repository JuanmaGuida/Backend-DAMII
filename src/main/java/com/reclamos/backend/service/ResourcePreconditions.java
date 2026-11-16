package com.reclamos.backend.service;

import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.PreconditionFailedException;
import java.util.regex.Pattern;

public final class ResourcePreconditions {
    private static final Pattern TAG = Pattern.compile("(?:W/)?\"[\\x21\\x23-\\x7e\\x80-\\xff]*\"");
    private ResourcePreconditions() { }

    public static String etag(String kind, Object id, long version) {
        return "\"" + kind + "-" + id + "-v" + version + "\"";
    }

    /** RFC 9110 strong comparison; weak validators never satisfy If-Match. */
    public static void check(String ifMatch, String current) {
        if (ifMatch == null) return;
        String value = ifMatch.trim();
        if (value.equals("*")) return; // The authorized resource has already been loaded.
        boolean matches = false;
        int offset = 0;
        while (offset < value.length()) {
            var matcher = TAG.matcher(value).region(offset, value.length());
            if (!matcher.lookingAt()) throw invalid();
            matches |= matcher.group().equals(current);
            offset = matcher.end();
            while (offset < value.length() && (value.charAt(offset) == ' ' || value.charAt(offset) == '\t')) offset++;
            if (offset == value.length()) {
                if (!matches) throw new PreconditionFailedException();
                return;
            }
            if (value.charAt(offset++) != ',') throw invalid();
            while (offset < value.length() && (value.charAt(offset) == ' ' || value.charAt(offset) == '\t')) offset++;
        }
        throw invalid();
    }

    private static InvalidTicketRequestException invalid() {
        return new InvalidTicketRequestException("If-Match debe contener * o una lista de entity-tags entre comillas");
    }
}
