package fce

import (
	"bytes"
	"encoding/hex"
	"errors"
	"testing"
)

// Golden vector shared with core/crypto FceTest.kt — do not change one side only.
const goldenHex = "0100000008000000000000000502000000026162"

func TestGoldenVector(t *testing.T) {
	got, err := NewEncoder().Uint64(1, 5).String(2, "ab").Finish()
	if err != nil {
		t.Fatal(err)
	}
	if hex.EncodeToString(got) != goldenHex {
		t.Fatalf("golden mismatch: %x", got)
	}
}

func TestRoundTrip(t *testing.T) {
	items := [][]byte{{1, 2, 3}, {}, bytes.Repeat([]byte{9}, 300)}
	enc, err := NewEncoder().Bytes(1, []byte("x")).Uint64(4, 42).List(9, items).Finish()
	if err != nil {
		t.Fatal(err)
	}
	m, err := Decode(enc, 1, 4, 9)
	if err != nil {
		t.Fatal(err)
	}
	if v, _ := m.Uint64(4); v != 42 {
		t.Fatalf("uint64 = %d", v)
	}
	got, err := m.List(9)
	if err != nil || len(got) != 3 || !bytes.Equal(got[2], items[2]) {
		t.Fatalf("list mismatch: %v %v", got, err)
	}
}

func TestRejectsNonCanonical(t *testing.T) {
	if _, err := NewEncoder().Uint64(2, 1).Uint64(1, 1).Finish(); !errors.Is(err, ErrOrder) {
		t.Fatalf("encoder accepted descending tags: %v", err)
	}
	desc, _ := hex.DecodeString("0200000001aa0100000001bb")
	if _, err := Decode(desc, 1, 2); !errors.Is(err, ErrOrder) {
		t.Fatalf("decoder accepted descending tags: %v", err)
	}
	dup, _ := hex.DecodeString("0100000001aa0100000001bb")
	if _, err := Decode(dup, 1); !errors.Is(err, ErrOrder) {
		t.Fatalf("decoder accepted duplicate tag: %v", err)
	}
}

func TestRejectsUnknownAndTruncated(t *testing.T) {
	enc, _ := NewEncoder().Uint64(1, 1).Uint64(7, 1).Finish()
	if _, err := Decode(enc, 1); !errors.Is(err, ErrUnknownTag) {
		t.Fatalf("unknown tag accepted: %v", err)
	}
	if _, err := Decode(enc[:len(enc)-1], 1, 7); !errors.Is(err, ErrTruncated) {
		t.Fatalf("truncation accepted: %v", err)
	}
	huge, _ := hex.DecodeString("01ffffffff")
	if _, err := Decode(huge, 1); !errors.Is(err, ErrTruncated) {
		t.Fatalf("oversized length accepted: %v", err)
	}
}

func TestUint64Range(t *testing.T) {
	if _, err := NewEncoder().Uint64(1, 1<<63).Finish(); !errors.Is(err, ErrRange) {
		t.Fatalf("encoder accepted value >= 2^63: %v", err)
	}
	raw, _ := hex.DecodeString("01000000088000000000000000")
	m, err := Decode(raw, 1)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Uint64(1); !errors.Is(err, ErrRange) {
		t.Fatalf("decoder accepted value >= 2^63: %v", err)
	}
}

func TestSigningInputDomainSeparation(t *testing.T) {
	a := SigningInput("A", []byte("B"))
	if !bytes.Equal(a, []byte{'A', 0, 'B'}) {
		t.Fatalf("unexpected signing input %x", a)
	}
}

func FuzzDecode(f *testing.F) {
	seed, _ := hex.DecodeString(goldenHex)
	f.Add(seed)
	f.Fuzz(func(t *testing.T, b []byte) {
		m, err := Decode(b, 1, 2, 3, 6)
		if err != nil {
			return
		}
		_, _ = m.List(6)
		_, _ = m.Uint64(1)
		_, _ = m.String(2, 64)
	})
}

func TestEncoderErrorsAreSticky(t *testing.T) {
	if _, err := NewEncoder().String(1, string([]byte{0xff})).Uint64(2, 1).Finish(); !errors.Is(err, ErrUTF8) {
		t.Fatalf("invalid utf-8 accepted: %v", err)
	}
	if _, err := NewEncoder().List(1, make([][]byte, MaxListItems+1)).Finish(); !errors.Is(err, ErrTooLarge) {
		t.Fatalf("oversized list accepted: %v", err)
	}
	if _, err := NewEncoder().Bytes(1, make([]byte, MaxMessageSize)).Finish(); !errors.Is(err, ErrTooLarge) {
		t.Fatalf("oversized field accepted: %v", err)
	}
	if _, err := NewEncoder().List(1, [][]byte{make([]byte, MaxMessageSize)}).Finish(); !errors.Is(err, ErrTooLarge) {
		t.Fatalf("oversized list item accepted: %v", err)
	}
	if _, err := Decode(make([]byte, MaxMessageSize+1)); !errors.Is(err, ErrTooLarge) {
		t.Fatalf("oversized message accepted: %v", err)
	}
}

func TestAccessorValidation(t *testing.T) {
	enc, err := NewEncoder().Bytes(1, []byte{1, 2, 3}).Bytes(2, []byte{0xff}).Bytes(3, []byte{0, 0, 0, 2, 0, 0, 0, 1}).Finish()
	if err != nil {
		t.Fatal(err)
	}
	m, err := Decode(enc, 1, 2, 3, 4)
	if err != nil {
		t.Fatal(err)
	}
	if !m.Has(1) || m.Has(4) {
		t.Fatal("Has() wrong")
	}
	if _, err := m.Fixed(1, 4); !errors.Is(err, ErrBadLength) {
		t.Fatalf("Fixed accepted wrong length: %v", err)
	}
	if _, err := m.Uint64(1); !errors.Is(err, ErrBadLength) {
		t.Fatalf("Uint64 accepted 3 bytes: %v", err)
	}
	if _, err := m.String(2, 8); !errors.Is(err, ErrUTF8) {
		t.Fatalf("String accepted invalid utf-8: %v", err)
	}
	if _, err := m.List(3); !errors.Is(err, ErrTruncated) {
		t.Fatalf("List accepted truncated items: %v", err)
	}
	if _, err := m.Bytes(4); !errors.Is(err, ErrMissingTag) {
		t.Fatalf("missing tag not reported: %v", err)
	}
}
