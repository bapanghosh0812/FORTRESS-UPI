package app_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/fortress-upi/fortress/services/payments/internal/adapters/memstore"
	"github.com/fortress-upi/fortress/services/payments/internal/adapters/sandboxpsp"
	"github.com/fortress-upi/fortress/services/payments/internal/app"
	"github.com/fortress-upi/fortress/services/payments/internal/domain"
)

type rig struct {
	svc *app.Service
	now time.Time
}

func newRig() *rig {
	r := &rig{now: time.Date(2026, 10, 5, 12, 0, 0, 0, time.UTC)}
	clock := func() time.Time { return r.now }
	psp := sandboxpsp.New(clock, 800*time.Millisecond)
	r.svc = app.New(memstore.New(), psp, psp, clock, app.Config{MaxPaise: 1_00_000_00}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	return r
}

func req(device, key string, amount uint64) domain.Payment {
	return domain.Payment{
		DeviceID: device, IdempotencyKey: key, AccountRef: "acc_fortress_4402",
		PayeeVPA: "asha.store@fortresspsp", PayeeName: "Asha Kirana Store", AmountPaise: amount,
	}
}

func TestHappyPathSettlesWithUPIRef(t *testing.T) {
	r, ctx := newRig(), context.Background()
	p, err := r.svc.Create(ctx, req("dev_a", "0123456789abcdef0001", 14_000))
	if err != nil {
		t.Fatal(err)
	}
	if p.State != domain.Created || p.CLChallenge == "" {
		t.Fatalf("created = %+v", p)
	}
	if p, err = r.svc.Authorize(ctx, "dev_a", p.ID, "sandbox:"+p.CLChallenge); err != nil || p.State != domain.Pending {
		t.Fatalf("authorize = %+v, %v", p, err)
	}
	if p, _ = r.svc.Status(ctx, "dev_a", p.ID); p.State != domain.Pending {
		t.Fatalf("settled too early: %s", p.State)
	}
	r.now = r.now.Add(time.Second)
	if p, _ = r.svc.Status(ctx, "dev_a", p.ID); p.State != domain.Success || len(p.UPIRef) != 12 {
		t.Fatalf("final = %+v", p)
	}
	list, _ := r.svc.Transactions(ctx, "dev_a")
	if len(list) != 1 {
		t.Fatalf("history = %d", len(list))
	}
}

func TestIdempotentCreateAndKeyReuse(t *testing.T) {
	r, ctx := newRig(), context.Background()
	a, _ := r.svc.Create(ctx, req("dev_a", "0123456789abcdef0002", 500))
	b, err := r.svc.Create(ctx, req("dev_a", "0123456789abcdef0002", 500))
	if err != nil || a.ID != b.ID {
		t.Fatalf("retry created a second payment: %v %v", a.ID, b.ID)
	}
	if _, err := r.svc.Create(ctx, req("dev_a", "0123456789abcdef0002", 900)); !errors.Is(err, domain.ErrIdempotency) {
		t.Fatalf("key reuse with new amount: %v", err)
	}
}

func TestRejections(t *testing.T) {
	r, ctx := newRig(), context.Background()
	p, _ := r.svc.Create(ctx, req("dev_a", "0123456789abcdef0003", 500))

	if _, err := r.svc.Status(ctx, "dev_b", p.ID); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("other device read someone else's payment: %v", err)
	}
	failed, err := r.svc.Authorize(ctx, "dev_a", p.ID, "sandbox:forged")
	if err != nil || failed.State != domain.Failed {
		t.Fatalf("forged credential: %+v %v", failed, err)
	}
	bad := req("dev_a", "0123456789abcdef0004", 500)
	bad.PayeeVPA = "not a vpa"
	if _, err := r.svc.Create(ctx, bad); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("invalid vpa: %v", err)
	}
	other := req("dev_a", "0123456789abcdef0005", 500)
	other.AccountRef = "acc_someone_else"
	if _, err := r.svc.Create(ctx, other); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("foreign account: %v", err)
	}
}

func TestBankFailureAndDeemed(t *testing.T) {
	r, ctx := newRig(), context.Background()
	f, _ := r.svc.Create(ctx, req("dev_a", "0123456789abcdef0006", 1_013))
	d, _ := r.svc.Create(ctx, req("dev_a", "0123456789abcdef0007", 1_077))
	f, _ = r.svc.Authorize(ctx, "dev_a", f.ID, "sandbox:"+f.CLChallenge)
	d, _ = r.svc.Authorize(ctx, "dev_a", d.ID, "sandbox:"+d.CLChallenge)
	r.now = r.now.Add(time.Second)
	if f, _ = r.svc.Status(ctx, "dev_a", f.ID); f.State != domain.Failed || f.Reason == "" {
		t.Fatalf("failure = %+v", f)
	}
	if d, _ = r.svc.Status(ctx, "dev_a", d.ID); d.State != domain.Deemed {
		t.Fatalf("deemed = %+v", d)
	}
}
