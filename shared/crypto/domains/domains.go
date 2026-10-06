// Package domains holds every signature domain-separation label. Each label is
// versioned; changing a structure means minting a new label, never editing one.
// Kotlin twin: com.fortress.upi.core.offline.protocol.Domains.
package domains

const (
	NoteCert     = "FORTRESS/NOTE-CERT/v1"
	ReceiverCert = "FORTRESS/RECEIVER-CERT/v1"
	Offer        = "FORTRESS/OFFER/v1"
	Spend        = "FORTRESS/SPEND/v1"
	Receipt      = "FORTRESS/RECEIPT/v1"
	Transcript   = "FORTRESS/TRANSCRIPT/v1"
	Request      = "FORTRESS/REQ/v1"
	Binding      = "FORTRESS/BIND/v1"
)
