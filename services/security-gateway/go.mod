module github.com/fortress-upi/fortress/services/security-gateway

go 1.27.0

require (
	github.com/fortress-upi/fortress/shared/crypto v0.0.0
	github.com/fortress-upi/fortress/shared/platform v0.0.0
)

replace (
	github.com/fortress-upi/fortress/shared/crypto => ../../shared/crypto
	github.com/fortress-upi/fortress/shared/platform => ../../shared/platform
)
