package com.fortress.upi.core.crypto

/**
 * Signature / AEAD domain-separation labels. Twin of `shared/crypto/domains` (Go).
 * Labels are versioned and immutable: a new structure gets a new label.
 */
object Domains {
    const val NOTE_CERT = "FORTRESS/NOTE-CERT/v1"
    const val RECEIVER_CERT = "FORTRESS/RECEIVER-CERT/v1"
    const val OFFER = "FORTRESS/OFFER/v1"
    const val SPEND = "FORTRESS/SPEND/v1"
    const val RECEIPT = "FORTRESS/RECEIPT/v1"
    const val TRANSCRIPT = "FORTRESS/TRANSCRIPT/v1"
    const val REQUEST = "FORTRESS/REQ/v1"
    const val BINDING = "FORTRESS/BIND/v1"

    const val SESSION_INFO = "FORTRESS/SESSION/v1"
    const val SAS = "FORTRESS/SAS/v1"
    const val AAD_OFFER = "FORTRESS/AAD/OFFER/v1"
    const val AAD_PAY = "FORTRESS/AAD/PAY/v1"
    const val AAD_RECEIPT = "FORTRESS/AAD/RECEIPT/v1"
}
