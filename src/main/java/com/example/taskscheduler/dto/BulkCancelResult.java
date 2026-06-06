package com.example.taskscheduler.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Result of a bulk-cancel operation with per-id outcome.
 * <p>
 * {@code succeeded} contains the IDs that are now in CANCELLED state (including
 * IDs that were already CANCELLED at request time — bulk cancel is idempotent
 * so retrying the whole list is safe and does not produce spurious failures).
 * {@code failed} contains IDs that could not be cancelled, each with a reason
 * (not found, currently locked, already in a different terminal state).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkCancelResult {

    private List<UUID> succeeded;
    private List<Failure> failed;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Failure {
        private UUID taskId;
        private String reason;
    }
}
