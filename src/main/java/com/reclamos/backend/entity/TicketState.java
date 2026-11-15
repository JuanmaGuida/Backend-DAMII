package com.reclamos.backend.entity;

/**
 * State GOF: each constant owns the behavior available in that lifecycle state.
 * A null destination means the operation is unavailable. Transport policy and
 * transactional effects remain with the callers; Ticket applies the decision.
 */
enum TicketState {
    REGISTERED {
        @Override TicketStatus next(Ticket ticket, TicketAction action, TicketStatus resume) {
            return switch (action) {
                case START_REVIEW -> TicketStatus.IN_REVIEW;
                case CANCEL_OWNER, CANCEL_ADMIN, CANCEL_EXTERNAL_REVIEW -> TicketStatus.CANCELLED;
                case LINK_DUPLICATE -> TicketStatus.DUPLICATE;
                case ACCEPT_DUPLICATE -> TicketStatus.REGISTERED;
                default -> null;
            };
        }
    },
    IN_REVIEW {
        @Override TicketStatus next(Ticket ticket, TicketAction action, TicketStatus resume) {
            return switch (action) {
                case CLASSIFY, ACCEPT_DUPLICATE -> TicketStatus.IN_REVIEW;
                case ROUTE -> "M2".equalsIgnoreCase(ticket.getResponsibleAreaId())
                        ? TicketStatus.IN_PROGRESS : TicketStatus.ROUTED;
                case REQUEST_INFORMATION -> TicketStatus.PENDING_INFORMATION;
                case CANCEL_ADMIN, CANCEL_EXTERNAL_REVIEW -> TicketStatus.CANCELLED;
                case LINK_DUPLICATE -> TicketStatus.DUPLICATE;
                default -> null;
            };
        }
    },
    ROUTED {
        @Override TicketStatus next(Ticket ticket, TicketAction action, TicketStatus resume) {
            return switch (action) {
                case EXTERNAL_STARTED -> TicketStatus.IN_PROGRESS;
                case REQUEST_INFORMATION, EXTERNAL_INFORMATION_REQUIRED -> TicketStatus.PENDING_INFORMATION;
                case EXTERNAL_RETURNED -> TicketStatus.IN_REVIEW;
                case EXTERNAL_RESOLVED -> TicketStatus.RESOLVED;
                case EXTERNAL_REJECTED, CANCEL_EXTERNAL_REVIEW -> TicketStatus.CANCELLED;
                case LINK_DUPLICATE -> TicketStatus.DUPLICATE;
                case ACCEPT_DUPLICATE -> TicketStatus.ROUTED;
                default -> null;
            };
        }
    },
    IN_PROGRESS {
        @Override TicketStatus next(Ticket ticket, TicketAction action, TicketStatus resume) {
            return switch (action) {
                case EXTERNAL_PROGRESS, ACCEPT_DUPLICATE -> TicketStatus.IN_PROGRESS;
                case REQUEST_INFORMATION, EXTERNAL_INFORMATION_REQUIRED -> TicketStatus.PENDING_INFORMATION;
                case EXTERNAL_RETURNED -> TicketStatus.IN_REVIEW;
                case RESOLVE_MANUALLY, EXTERNAL_RESOLVED -> TicketStatus.RESOLVED;
                case EXTERNAL_REJECTED, CANCEL_EXTERNAL_REVIEW -> TicketStatus.CANCELLED;
                case LINK_DUPLICATE -> TicketStatus.DUPLICATE;
                default -> null;
            };
        }
    },
    PENDING_INFORMATION {
        @Override TicketStatus next(Ticket ticket, TicketAction action, TicketStatus resume) {
            return switch (action) {
                // The request owns the persisted resumeStatus, not the current area.
                case ANSWER_INFORMATION -> resume;
                case REVIEW_INFORMATION -> TicketStatus.IN_REVIEW;
                case EXPIRE_INFORMATION, CANCEL_ADMIN, CANCEL_EXTERNAL_REVIEW -> TicketStatus.CANCELLED;
                case LINK_DUPLICATE -> TicketStatus.DUPLICATE;
                case ACCEPT_DUPLICATE -> TicketStatus.PENDING_INFORMATION;
                default -> null;
            };
        }
    },
    DUPLICATE {
        @Override TicketStatus next(Ticket ticket, TicketAction action, TicketStatus resume) {
            return switch (action) {
                case CLOSE_DUPLICATE -> TicketStatus.CLOSED;
                case CANCEL_DUPLICATE -> TicketStatus.CANCELLED;
                case PROMOTE_DUPLICATE -> TicketStatus.IN_REVIEW;
                default -> null;
            };
        }
    },
    RESOLVED {
        @Override TicketStatus next(Ticket ticket, TicketAction action, TicketStatus resume) {
            return switch (action) {
                case CLOSE_RESOLUTION -> TicketStatus.CLOSED;
                case REOPEN -> TicketStatus.IN_PROGRESS;
                default -> null;
            };
        }
    },
    CLOSED,
    CANCELLED;

    static TicketState of(TicketStatus status) {
        return valueOf(status.name());
    }

    TicketStatus next(Ticket ticket, TicketAction action, TicketStatus resume) {
        return null; // Terminal states reject every operation.
    }
}
