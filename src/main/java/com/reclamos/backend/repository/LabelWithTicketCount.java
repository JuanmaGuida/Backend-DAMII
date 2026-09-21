package com.reclamos.backend.repository;

import java.util.UUID;

public interface LabelWithTicketCount {
    UUID getId();
    String getCode();
    String getName();
    String getDescription();
    boolean getActive();
    long getTicketCount();
}