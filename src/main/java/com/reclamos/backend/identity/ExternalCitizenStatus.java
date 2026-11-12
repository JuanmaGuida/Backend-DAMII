package com.reclamos.backend.identity;

/** M1 restriction, independent of M2 active/roles. No undocumented automatic unblock. */
public enum ExternalCitizenStatus {
    UNKNOWN, ACTIVE, BLOCKED, DECEASED;
    public boolean restricted() { return this == BLOCKED || this == DECEASED; }
}
