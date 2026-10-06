package app

import (
	"context"
	"errors"
	"regexp"
)

// Limits is the payment policy published to devices. It encodes NPCI,
// sponsor-bank and RBI rules. When a circular changes a cap, operators change
// configuration and every app follows on its next sync; devices never
// hard-code these numbers.
type Limits struct {
	PerTxnUPIPaise     uint64 `json:"perTxnUpiPaise"`
	DailyUPIPaise      uint64 `json:"dailyUpiPaise"`
	DailyCount         int    `json:"dailyCount"`
	OfflinePerTxnPaise uint64 `json:"offlinePerTxnPaise"`
	OfflineWalletPaise uint64 `json:"offlineWalletPaise"`
	NewDeviceCapPaise  uint64 `json:"newDeviceCapPaise"`
	Source             string `json:"source"`
}

// DefaultLimits mirrors the caps in force when this build was made: UPI
// ₹1 lakh per payment and per day, UPI Lite ₹1,000 per payment and ₹5,000
// balance, ₹5,000 in the first 24 hours on a new device. Production sets
// every field explicitly from the current circulars.
func DefaultLimits() Limits {
	return Limits{
		PerTxnUPIPaise:     1_00_000_00,
		DailyUPIPaise:      1_00_000_00,
		DailyCount:         20,
		OfflinePerTxnPaise: 1_000_00,
		OfflineWalletPaise: 5_000_00,
		NewDeviceCapPaise:  5_000_00,
		Source:             "Fortress policy service",
	}
}

// Validate rejects a policy that could not have come from a sane config.
func (l Limits) Validate() error {
	switch {
	case l.PerTxnUPIPaise == 0 || l.DailyUPIPaise == 0 || l.DailyCount <= 0:
		return errors.New("limits: UPI limits must be positive")
	case l.PerTxnUPIPaise > l.DailyUPIPaise:
		return errors.New("limits: per-payment limit exceeds the daily limit")
	case l.OfflinePerTxnPaise == 0 || l.OfflinePerTxnPaise > l.OfflineWalletPaise:
		return errors.New("limits: offline per-payment cap must be positive and within the vault cap")
	}
	return nil
}

// Payee is the registered name the payee's bank returned for an address.
type Payee struct {
	VPA  string `json:"vpa"`
	Name string `json:"name"`
}

// Profile is the device owner's UPI identity.
type Profile struct {
	Name   string `json:"name"`
	VPA    string `json:"vpa"`
	Mobile string `json:"mobile"`
}

// Directory lookup errors.
var (
	ErrInvalidAddress = errors.New("invalid_address")
	ErrNoUPIAccount   = errors.New("vpa_not_found")
)

// Directory is the sponsor PSP's address services: validate-address
// (ReqValAdd), the UPI number / mobile mapper, and the device's profile.
type Directory interface {
	ValidateAddress(ctx context.Context, vpa string) (Payee, error)
	ResolveMobile(ctx context.Context, mobile string) (Payee, error)
	Profile(ctx context.Context, deviceID string) (Profile, error)
}

var (
	vpaPattern    = regexp.MustCompile(`^[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9.]{1,64}$`)
	mobilePattern = regexp.MustCompile(`^[6-9][0-9]{9}$`)
)

// DirectoryService validates input before it reaches the PSP and serves the policy.
type DirectoryService struct {
	dir    Directory
	limits Limits
}

// NewDirectoryService returns the lookup use case.
func NewDirectoryService(dir Directory, limits Limits) (*DirectoryService, error) {
	if err := limits.Validate(); err != nil {
		return nil, err
	}
	return &DirectoryService{dir: dir, limits: limits}, nil
}

// Limits returns the published payment policy.
func (d *DirectoryService) Limits() Limits { return d.limits }

// ValidateAddress returns the registered name for a UPI address.
func (d *DirectoryService) ValidateAddress(ctx context.Context, vpa string) (Payee, error) {
	if !vpaPattern.MatchString(vpa) {
		return Payee{}, ErrInvalidAddress
	}
	return d.dir.ValidateAddress(ctx, vpa)
}

// ResolveMobile finds the UPI address linked to an Indian mobile number.
func (d *DirectoryService) ResolveMobile(ctx context.Context, mobile string) (Payee, error) {
	if !mobilePattern.MatchString(mobile) {
		return Payee{}, ErrInvalidAddress
	}
	return d.dir.ResolveMobile(ctx, mobile)
}

// Profile returns the device owner's UPI identity.
func (d *DirectoryService) Profile(ctx context.Context, deviceID string) (Profile, error) {
	return d.dir.Profile(ctx, deviceID)
}
