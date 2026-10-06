package app_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/fortress-upi/fortress/services/payments/internal/adapters/sandboxpsp"
	"github.com/fortress-upi/fortress/services/payments/internal/app"
)

func newDirectory(t *testing.T) *app.DirectoryService {
	t.Helper()
	d, err := app.NewDirectoryService(sandboxpsp.New(time.Now, 0), app.DefaultLimits())
	if err != nil {
		t.Fatal(err)
	}
	return d
}

func TestDirectoryRejectsMalformedInputBeforeThePSP(t *testing.T) {
	d := newDirectory(t)
	for _, vpa := range []string{"", "noat", "a@", "@bank", "x@1bank", "spaces in@bank"} {
		if _, err := d.ValidateAddress(context.Background(), vpa); !errors.Is(err, app.ErrInvalidAddress) {
			t.Errorf("ValidateAddress(%q) err = %v, want ErrInvalidAddress", vpa, err)
		}
	}
	for _, m := range []string{"", "12345", "5876543210", "98765432101", "98765abcde"} {
		if _, err := d.ResolveMobile(context.Background(), m); !errors.Is(err, app.ErrInvalidAddress) {
			t.Errorf("ResolveMobile(%q) err = %v, want ErrInvalidAddress", m, err)
		}
	}
}

func TestDirectoryLookups(t *testing.T) {
	d := newDirectory(t)
	ctx := context.Background()

	p, err := d.ValidateAddress(ctx, "ananya.rao@okfortress")
	if err != nil || p.Name != "Ananya Rao" {
		t.Fatalf("known VPA = %+v, %v", p, err)
	}
	p, err = d.ValidateAddress(ctx, "123456789012@NOVA0001234.ifsc.npci")
	if err != nil || p.Name == "" {
		t.Fatalf("account+IFSC VPA = %+v, %v", p, err)
	}
	if _, err = d.ValidateAddress(ctx, "unknown.person@okbank"); !errors.Is(err, app.ErrNoUPIAccount) {
		t.Fatalf("unknown VPA err = %v, want ErrNoUPIAccount", err)
	}
	p, err = d.ResolveMobile(ctx, "9876543210")
	if err != nil || p.VPA != "9876543210@fortress" {
		t.Fatalf("mobile = %+v, %v", p, err)
	}
	if _, err = d.ResolveMobile(ctx, "9876500000"); !errors.Is(err, app.ErrNoUPIAccount) {
		t.Fatalf("unmapped mobile err = %v, want ErrNoUPIAccount", err)
	}
}

func TestLimitsValidation(t *testing.T) {
	if err := app.DefaultLimits().Validate(); err != nil {
		t.Fatalf("default limits invalid: %v", err)
	}
	bad := app.DefaultLimits()
	bad.OfflinePerTxnPaise = bad.OfflineWalletPaise + 1
	if bad.Validate() == nil {
		t.Fatal("offline per-payment cap above the vault cap must be rejected")
	}
	bad = app.DefaultLimits()
	bad.PerTxnUPIPaise = bad.DailyUPIPaise + 1
	if bad.Validate() == nil {
		t.Fatal("per-payment limit above the daily limit must be rejected")
	}
}
