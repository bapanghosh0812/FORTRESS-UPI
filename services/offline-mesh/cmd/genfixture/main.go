// Command genfixture emits a throwaway issuer public key and a valid signed
// offline bundle for CI load tests. The issuer private key exists only in this
// process's memory and is discarded on exit.
//
//	go run ./cmd/genfixture > fixture.json
package main

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"time"

	"github.com/fortress-upi/fortress/services/offline-mesh/internal/testkit"
	"github.com/fortress-upi/fortress/shared/crypto/sig"
)

type fixture struct {
	IssuerKeys string `json:"issuerKeys"`
	BundleB64  string `json:"bundleB64"`
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "genfixture:", err)
		os.Exit(1)
	}
}

func run() error {
	iss, err := testkit.NewIssuer()
	if err != nil {
		return err
	}
	now := time.Now()
	wallet := testkit.RandomID()
	n1, err := iss.IssueNote(wallet, 10_000, now.Add(-time.Minute), now.Add(24*time.Hour))
	if err != nil {
		return err
	}
	n2, err := iss.IssueNote(wallet, 5_000, now.Add(-time.Minute), now.Add(24*time.Hour))
	if err != nil {
		return err
	}
	receiver := testkit.RandomID()
	raw, err := testkit.BuildBundle(testkit.BundleSpec{
		PaymentID:  testkit.RandomID(),
		ReceiverID: receiver,
		Transcript: sha256.Sum256(receiver[:]),
		CreatedAt:  uint64(now.UnixMilli()), //nolint:gosec // wall clock is post-1970
		Notes:      []*testkit.Note{n1, n2},
		Applied:    []uint64{10_000, 2_300},
	})
	if err != nil {
		return err
	}
	spki, err := sig.MarshalPublicKey(&iss.Priv.PublicKey)
	if err != nil {
		return err
	}
	return json.NewEncoder(os.Stdout).Encode(fixture{
		IssuerKeys: hex.EncodeToString(iss.KeyID) + ":" + base64.StdEncoding.EncodeToString(spki),
		BundleB64:  base64.StdEncoding.EncodeToString(raw),
	})
}
