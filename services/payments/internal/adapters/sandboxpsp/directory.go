package sandboxpsp

import (
	"context"
	"strings"

	"github.com/fortress-upi/fortress/services/payments/internal/app"
)

// Fictional registered names for the sandbox directory.
var sandboxNames = map[string]string{
	"ananya.rao@okfortress": "Ananya Rao",
	"vsethi@fortress":       "Vikram Sethi",
	"meera.n@okbank":        "Meera Nair",
	"kabir@fortress":        "Kabir Das",
	"asha.store@okbank":     "Asha Kirana Store",
	"rohan.iyer@fortress":   "Rohan Iyer",
	"zara.k@okbank":         "Zara Khan",
}

// ValidateAddress implements app.Directory.
//
// Test hooks: addresses starting with "unknown" have no UPI account; any
// account@IFSC.ifsc.npci address resolves to a sandbox account holder.
func (s *PSP) ValidateAddress(_ context.Context, vpa string) (app.Payee, error) {
	key := strings.ToLower(vpa)
	if name, ok := sandboxNames[key]; ok {
		return app.Payee{VPA: vpa, Name: name}, nil
	}
	if strings.HasPrefix(key, "unknown") {
		return app.Payee{}, app.ErrNoUPIAccount
	}
	if strings.HasSuffix(key, ".ifsc.npci") {
		return app.Payee{VPA: vpa, Name: "Sandbox Account Holder"}, nil
	}
	return app.Payee{VPA: vpa, Name: nameFromHandle(vpa)}, nil
}

// ResolveMobile implements app.Directory. Numbers ending in 0000 have no UPI account.
func (s *PSP) ResolveMobile(_ context.Context, mobile string) (app.Payee, error) {
	if strings.HasSuffix(mobile, "0000") {
		return app.Payee{}, app.ErrNoUPIAccount
	}
	return app.Payee{VPA: mobile + "@fortress", Name: "Sandbox User " + mobile[len(mobile)-4:]}, nil
}

// Profile implements app.Directory with the fictional sandbox user.
func (s *PSP) Profile(_ context.Context, _ string) (app.Profile, error) {
	return app.Profile{Name: "Aarav Mehta", VPA: "aarav@fortress", Mobile: "+91 98765 43210"}, nil
}

// nameFromHandle turns "priya.s@bank" into "Priya S".
func nameFromHandle(vpa string) string {
	handle, _, _ := strings.Cut(vpa, "@")
	parts := strings.FieldsFunc(handle, func(r rune) bool { return r == '.' || r == '_' || r == '-' || (r >= '0' && r <= '9') })
	for i, p := range parts {
		parts[i] = strings.ToUpper(p[:1]) + strings.ToLower(p[1:])
	}
	if len(parts) == 0 {
		return "UPI User"
	}
	return strings.Join(parts, " ")
}
