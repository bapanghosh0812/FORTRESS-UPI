module github.com/fortress-upi/fortress/services/offline-mesh

go 1.27.0

require (
	github.com/fortress-upi/fortress/shared/crypto v0.0.0
	github.com/fortress-upi/fortress/shared/platform v0.0.0
)

// Monorepo-local modules. GOWORK=off builds (Docker, CI) resolve through these.
replace (
	github.com/fortress-upi/fortress/shared/crypto => ../../shared/crypto
	github.com/fortress-upi/fortress/shared/platform => ../../shared/platform
)
