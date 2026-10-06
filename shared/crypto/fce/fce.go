// Package fce implements Fortress Canonical Encoding v1 (FCE/1), the strict
// tag-length-value format used for every structure that is signed or
// encrypted across the device/server boundary.
//
// Wire format:
//
//	field   = tag:u8 || len:u32be || value[len]
//	message = field*   (tags strictly ascending; unknown tags rejected)
//
// Strict ordering makes the encoding canonical: one logical message has exactly
// one valid byte representation, so signatures can never be re-interpreted.
// The Kotlin twin lives in apps/mobile/android/core/crypto (Fce.kt); both
// test suites share golden vectors.
package fce

import (
	"encoding/binary"
	"errors"
	"fmt"
	"math"
	"unicode/utf8"
)

const (
	// MaxMessageSize bounds every decoded message (DoS protection).
	MaxMessageSize = 64 << 10
	// MaxListItems bounds list fields.
	MaxListItems = 64

	headerSize = 5
)

// Errors returned by the codec. They are deliberately coarse: callers map all
// of them to a single "malformed" response so parsers are not error oracles.
var (
	ErrTruncated  = errors.New("fce: truncated input")
	ErrOrder      = errors.New("fce: tags not strictly ascending")
	ErrUnknownTag = errors.New("fce: unknown tag")
	ErrMissingTag = errors.New("fce: missing required tag")
	ErrTooLarge   = errors.New("fce: size limit exceeded")
	ErrBadLength  = errors.New("fce: invalid field length")
	ErrRange      = errors.New("fce: integer out of range")
	ErrUTF8       = errors.New("fce: invalid utf-8")
)

// Encoder builds an FCE message. Methods are chainable; the first error is
// sticky and reported by Finish.
type Encoder struct {
	buf     []byte
	lastTag int
	err     error
}

// NewEncoder returns an empty encoder.
func NewEncoder() *Encoder { return &Encoder{lastTag: -1} }

// Bytes appends a raw field.
func (e *Encoder) Bytes(tag uint8, v []byte) *Encoder {
	if e.err != nil {
		return e
	}
	if int(tag) <= e.lastTag {
		e.err = ErrOrder
		return e
	}
	if len(v) > MaxMessageSize-headerSize || len(e.buf)+headerSize+len(v) > MaxMessageSize {
		e.err = ErrTooLarge
		return e
	}
	e.lastTag = int(tag)
	var hdr [headerSize]byte
	hdr[0] = tag
	binary.BigEndian.PutUint32(hdr[1:], uint32(len(v))) //nolint:gosec // len(v) <= MaxMessageSize checked above
	e.buf = append(e.buf, hdr[:]...)
	e.buf = append(e.buf, v...)
	return e
}

// Uint64 appends an 8-byte big-endian integer. Values above MaxInt64 are
// rejected for parity with the JVM's signed Long.
func (e *Encoder) Uint64(tag uint8, v uint64) *Encoder {
	if v > math.MaxInt64 {
		if e.err == nil {
			e.err = ErrRange
		}
		return e
	}
	var b [8]byte
	binary.BigEndian.PutUint64(b[:], v)
	return e.Bytes(tag, b[:])
}

// String appends a UTF-8 string.
func (e *Encoder) String(tag uint8, s string) *Encoder {
	if !utf8.ValidString(s) {
		if e.err == nil {
			e.err = ErrUTF8
		}
		return e
	}
	return e.Bytes(tag, []byte(s))
}

// List appends a list field: count:u32be || (len:u32be || item)*.
func (e *Encoder) List(tag uint8, items [][]byte) *Encoder {
	if len(items) > MaxListItems {
		if e.err == nil {
			e.err = ErrTooLarge
		}
		return e
	}
	size := 4
	for _, it := range items {
		size += 4 + len(it)
		if size > MaxMessageSize {
			if e.err == nil {
				e.err = ErrTooLarge
			}
			return e
		}
	}
	out := make([]byte, 0, size)
	out = binary.BigEndian.AppendUint32(out, uint32(len(items))) //nolint:gosec // bounded by MaxListItems
	for _, it := range items {
		out = binary.BigEndian.AppendUint32(out, uint32(len(it))) //nolint:gosec // bounded by MaxMessageSize
		out = append(out, it...)
	}
	return e.Bytes(tag, out)
}

// Finish returns the encoded message or the first error encountered.
func (e *Encoder) Finish() ([]byte, error) {
	if e.err != nil {
		return nil, e.err
	}
	return e.buf, nil
}

// Message is a strictly decoded FCE message. Returned slices alias the input
// buffer and must be treated as read-only.
type Message struct {
	fields map[uint8][]byte
}

// Decode parses b. allowed lists every tag the schema defines; any other tag
// is rejected so unsigned data can never ride along inside a signed body.
func Decode(b []byte, allowed ...uint8) (*Message, error) {
	if len(b) > MaxMessageSize {
		return nil, ErrTooLarge
	}
	var allow [256]bool
	for _, t := range allowed {
		allow[t] = true
	}
	m := &Message{fields: make(map[uint8][]byte, len(allowed))}
	last := -1
	for off := 0; off < len(b); {
		if len(b)-off < headerSize {
			return nil, ErrTruncated
		}
		tag := b[off]
		n := int(binary.BigEndian.Uint32(b[off+1 : off+headerSize]))
		off += headerSize
		if n < 0 || n > len(b)-off { // n < 0 only on 32-bit platforms
			return nil, ErrTruncated
		}
		if int(tag) <= last {
			return nil, ErrOrder
		}
		if !allow[tag] {
			return nil, fmt.Errorf("%w: %d", ErrUnknownTag, tag)
		}
		last = int(tag)
		m.fields[tag] = b[off : off+n]
		off += n
	}
	return m, nil
}

// Has reports whether tag is present.
func (m *Message) Has(tag uint8) bool {
	_, ok := m.fields[tag]
	return ok
}

// Bytes returns a required field.
func (m *Message) Bytes(tag uint8) ([]byte, error) {
	v, ok := m.fields[tag]
	if !ok {
		return nil, fmt.Errorf("%w: %d", ErrMissingTag, tag)
	}
	return v, nil
}

// BytesRange returns a required field whose length is within [minLen, maxLen].
func (m *Message) BytesRange(tag uint8, minLen, maxLen int) ([]byte, error) {
	v, err := m.Bytes(tag)
	if err != nil {
		return nil, err
	}
	if len(v) < minLen || len(v) > maxLen {
		return nil, fmt.Errorf("%w: tag %d", ErrBadLength, tag)
	}
	return v, nil
}

// Fixed returns a required field of exactly n bytes.
func (m *Message) Fixed(tag uint8, n int) ([]byte, error) {
	return m.BytesRange(tag, n, n)
}

// Uint64 returns a required 8-byte big-endian integer (<= MaxInt64).
func (m *Message) Uint64(tag uint8) (uint64, error) {
	v, err := m.Fixed(tag, 8)
	if err != nil {
		return 0, err
	}
	u := binary.BigEndian.Uint64(v)
	if u > math.MaxInt64 {
		return 0, ErrRange
	}
	return u, nil
}

// String returns a required UTF-8 string of at most maxLen bytes.
func (m *Message) String(tag uint8, maxLen int) (string, error) {
	v, err := m.BytesRange(tag, 0, maxLen)
	if err != nil {
		return "", err
	}
	if !utf8.Valid(v) {
		return "", ErrUTF8
	}
	return string(v), nil
}

// List returns a required list field.
func (m *Message) List(tag uint8) ([][]byte, error) {
	v, err := m.Bytes(tag)
	if err != nil {
		return nil, err
	}
	if len(v) < 4 {
		return nil, ErrTruncated
	}
	count := int(binary.BigEndian.Uint32(v[:4]))
	if count < 0 || count > MaxListItems {
		return nil, ErrTooLarge
	}
	items := make([][]byte, 0, count)
	off := 4
	for range count {
		if len(v)-off < 4 {
			return nil, ErrTruncated
		}
		n := int(binary.BigEndian.Uint32(v[off : off+4]))
		off += 4
		if n < 0 || n > len(v)-off {
			return nil, ErrTruncated
		}
		items = append(items, v[off:off+n])
		off += n
	}
	if off != len(v) {
		return nil, ErrBadLength
	}
	return items, nil
}

// SigningInput returns ASCII(domain) || 0x00 || body — the exact bytes that
// are hashed and signed. Domain separation stops a signature made for one
// structure from ever validating as another.
func SigningInput(domain string, body []byte) []byte {
	out := make([]byte, 0, len(domain)+1+len(body))
	out = append(out, domain...)
	out = append(out, 0x00)
	return append(out, body...)
}
