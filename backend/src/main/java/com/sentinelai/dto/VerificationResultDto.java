package com.sentinelai.dto;

import java.util.UUID;

/**
 * Response body for {@code GET /api/logs/{id}/verify}.
 *
 * @param valid     {@code true} if the Ed25519 signature matches the record's current content
 * @param recordId  the UUID of the verified record
 * @param algorithm signing algorithm — always "Ed25519" when signing is enabled
 * @param reason    human-readable explanation when {@code valid} is {@code false}
 */
public record VerificationResultDto(
        boolean valid,
        UUID recordId,
        String algorithm,
        String reason
) {}
