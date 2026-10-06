// Package app holds the offline-settlement use case. It orchestrates domain
// verification and the atomic settlement store through narrow ports.
package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/fortress-upi/fortress/services/offline-mesh/internal/domain"
)

// Store is the outbound port for durable settlement. Implementations MUST make
// Settle atomic: either every note claim and the ledger instruction commit
// together, or nothing does. The CockroachDB adapter uses one serializable
// transaction with UNIQUE(note_id) and a transactional outbox row.
type Store interface {
	Settle(ctx context.Context, s domain.Settlement) (domain.Outcome, error)
}

// FraudSink receives double-spend evidence (Kafka topic offline.fraud.v1).
type FraudSink interface {
	DoubleSpend(ctx context.Context, s domain.Settlement)
}

// Config is settlement policy.
type Config struct {
	SettlementGrace  time.Duration
	MinEnforcement   domain.Enforcement
	MaxBundleTotal   uint64
	AllowRedemptions bool
}

// SettleService verifies and settles offline bundles.
type SettleService struct {
	keys  domain.IssuerKeys
	store Store
	fraud FraudSink
	now   func() time.Time
	cfg   Config
	log   *slog.Logger
}

// NewSettleService wires the use case.
func NewSettleService(keys domain.IssuerKeys, store Store, fraud FraudSink, now func() time.Time,
	cfg Config, log *slog.Logger) *SettleService {
	return &SettleService{keys: keys, store: store, fraud: fraud, now: now, cfg: cfg, log: log}
}

// ErrInvalid wraps every client-attributable validation failure.
var ErrInvalid = errors.New("invalid bundle")

// Submit verifies raw and settles it idempotently.
func (s *SettleService) Submit(ctx context.Context, raw []byte) (domain.Outcome, error) {
	b, err := domain.ParseBundle(raw)
	if err != nil {
		return 0, fmt.Errorf("%w: %w", ErrInvalid, err)
	}
	err = b.Verify(s.keys, domain.VerifyPolicy{
		Now:              s.now(),
		SettlementGrace:  s.cfg.SettlementGrace,
		MinEnforcement:   s.cfg.MinEnforcement,
		MaxBundleTotal:   s.cfg.MaxBundleTotal,
		AllowRedemptions: s.cfg.AllowRedemptions,
	})
	if err != nil {
		return 0, fmt.Errorf("%w: %w", ErrInvalid, err)
	}

	settlement := b.Settlement(raw)
	outcome, err := s.store.Settle(ctx, settlement)
	if err != nil {
		return 0, fmt.Errorf("settle: %w", err)
	}
	if outcome == domain.OutcomeConflict {
		// Only reachable with software-enforced keys or a hardware compromise.
		s.log.WarnContext(ctx, "offline double-spend detected",
			"payment_id", settlement.PaymentID.String(),
			"wallet_id", settlement.WalletID.String(),
			"notes", len(settlement.Claims))
		s.fraud.DoubleSpend(ctx, settlement)
	}
	return outcome, nil
}
