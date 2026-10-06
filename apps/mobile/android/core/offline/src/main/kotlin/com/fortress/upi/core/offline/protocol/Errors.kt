package com.fortress.upi.core.offline.protocol

/** Stable failure reasons. Codes travel in ABORT frames; never add detail that helps an attacker. */
enum class Reason(val code: Long) {
    MALFORMED(1),
    UNSUPPORTED_VERSION(2),
    UNKNOWN_ISSUER(3),
    BAD_ISSUER_SIGNATURE(4),
    BAD_PAYEE_SIGNATURE(5),
    BAD_SPEND_SIGNATURE(6),
    EXPIRED(7),
    AMOUNT(8),
    WRONG_RECEIVER(9),
    TRANSCRIPT_MISMATCH(10),
    DUPLICATE_NOTE(11),
    MIXED_WALLETS(12),
    REPLAY(13),
    INSUFFICIENT_FUNDS(14),
    ENFORCEMENT_POLICY(15),
    RECEIPT_INVALID(16),
    STATE(17),
    PEER_ABORTED(18),
    INTERNAL(255),
    ;

    companion object {
        fun of(code: Long): Reason = entries.firstOrNull { it.code == code } ?: MALFORMED
    }
}

class OfflineProtocolException(val reason: Reason, cause: Throwable? = null) :
    Exception("offline protocol failure: ${reason.name}", cause)
