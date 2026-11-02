// Package llm talks to an OpenAI-compatible chat completions API: the
// server-wide provider settings, the HTTP client, and the probe that checks
// a model can call tools.
package llm

import (
	"context"
	"database/sql"
	"errors"
	"net/url"
	"strconv"
	"strings"

	"notes/server/internal/database"
)

const (
	keyBaseURL = "ai_base_url"
	keyAPIKey  = "ai_api_key"
	keyModel   = "ai_model"
	keyCap     = "ai_daily_token_cap"
)

var allKeys = []any{keyBaseURL, keyAPIKey, keyModel, keyCap}

// DefaultDailyTokenCap bounds what one account can spend in a day when the
// admin hasn't set a cap: enough for hundreds of small runs, small enough
// that a device replaying a backlog can't run up a large bill.
const DefaultDailyTokenCap = 1_000_000

const maxDailyTokenCap = 10_000_000_000

// ErrNotConfigured means no provider has been saved.
var ErrNotConfigured = errors.New("AI is not set up")

// InputError reports an invalid setting.
type InputError struct{ Field, Message string }

func (e *InputError) Error() string { return e.Field + ": " + e.Message }

// Settings is what calling the provider needs. The key never leaves the
// server; clients see KeyHint.
type Settings struct {
	BaseURL       string // without a trailing slash; requests go to BaseURL + "/chat/completions"
	APIKey        string
	Model         string
	DailyTokenCap int64 // tokens per account per day
}

// KeyHint is the key's last four characters, enough to tell keys apart.
func (s Settings) KeyHint() string {
	if len(s.APIKey) < 4 {
		return ""
	}
	return s.APIKey[len(s.APIKey)-4:]
}

// Input is settings as an admin enters them. An empty APIKey keeps the saved
// one; a zero DailyTokenCap means the default.
type Input struct {
	BaseURL       string `json:"baseUrl"`
	APIKey        string `json:"apiKey"`
	Model         string `json:"model"`
	DailyTokenCap int64  `json:"dailyTokenCap"`
}

// Load returns the saved settings, or ErrNotConfigured.
func Load(ctx context.Context, db *database.DB) (Settings, error) {
	s, err := load(ctx, db.R)
	if err != nil {
		return Settings{}, err
	}
	if s.BaseURL == "" || s.APIKey == "" || s.Model == "" {
		return Settings{}, ErrNotConfigured
	}
	return s, nil
}

type querier interface {
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
}

func load(ctx context.Context, q querier) (Settings, error) {
	s := Settings{DailyTokenCap: DefaultDailyTokenCap}
	rows, err := q.QueryContext(ctx, `SELECT key, value FROM settings WHERE key IN (?, ?, ?, ?)`, allKeys...)
	if err != nil {
		return s, err
	}
	defer rows.Close()
	for rows.Next() {
		var k, v string
		if err := rows.Scan(&k, &v); err != nil {
			return s, err
		}
		switch k {
		case keyBaseURL:
			s.BaseURL = v
		case keyAPIKey:
			s.APIKey = v
		case keyModel:
			s.Model = v
		case keyCap:
			if n, err := strconv.ParseInt(v, 10, 64); err == nil && n > 0 {
				s.DailyTokenCap = n
			}
		}
	}
	return s, rows.Err()
}

// Resolve validates in and fills in the saved key when in has none, without
// saving anything: what Test runs with, before the admin commits to it.
func Resolve(ctx context.Context, db *database.DB, in Input) (Settings, error) {
	s, err := validate(in)
	if err != nil {
		return Settings{}, err
	}
	if s.APIKey == "" {
		saved, err := load(ctx, db.R)
		if err != nil {
			return Settings{}, err
		}
		if saved.APIKey == "" {
			return Settings{}, &InputError{Field: "apiKey", Message: "enter the provider's API key"}
		}
		s.APIKey = saved.APIKey
	}
	return s, nil
}

// Save stores in. The first save must include a key.
func Save(ctx context.Context, db *database.DB, in Input) error {
	s, err := validate(in)
	if err != nil {
		return err
	}
	return db.WriteTx(ctx, func(tx *sql.Tx) error {
		if s.APIKey == "" {
			var n int
			if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM settings WHERE key = ? AND value != ''`, keyAPIKey).Scan(&n); err != nil {
				return err
			}
			if n == 0 {
				return &InputError{Field: "apiKey", Message: "enter the provider's API key"}
			}
		} else if err := put(ctx, tx, keyAPIKey, s.APIKey); err != nil {
			return err
		}
		if err := put(ctx, tx, keyBaseURL, s.BaseURL); err != nil {
			return err
		}
		if err := put(ctx, tx, keyModel, s.Model); err != nil {
			return err
		}
		return put(ctx, tx, keyCap, strconv.FormatInt(s.DailyTokenCap, 10))
	})
}

// Clear removes every AI setting.
func Clear(ctx context.Context, db *database.DB) error {
	return db.WriteTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `DELETE FROM settings WHERE key IN (?, ?, ?, ?)`, allKeys...)
		return err
	})
}

func validate(in Input) (Settings, error) {
	s := Settings{
		BaseURL:       strings.TrimRight(strings.TrimSpace(in.BaseURL), "/"),
		APIKey:        strings.TrimSpace(in.APIKey),
		Model:         strings.TrimSpace(in.Model),
		DailyTokenCap: in.DailyTokenCap,
	}
	u, err := url.Parse(s.BaseURL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return s, &InputError{Field: "baseUrl", Message: "enter the API's address, like https://api.openai.com/v1"}
	}
	// Pasting the endpoint itself is the likeliest mistake.
	s.BaseURL = strings.TrimSuffix(s.BaseURL, "/chat/completions")
	if strings.ContainsAny(s.APIKey, " \t\r\n") {
		return s, &InputError{Field: "apiKey", Message: "an API key has no spaces"}
	}
	if s.Model == "" || len(s.Model) > 200 || strings.ContainsAny(s.Model, "\r\n") {
		return s, &InputError{Field: "model", Message: "enter the model's name, like gpt-4.1-mini"}
	}
	switch {
	case s.DailyTokenCap == 0:
		s.DailyTokenCap = DefaultDailyTokenCap
	case s.DailyTokenCap < 0 || s.DailyTokenCap > maxDailyTokenCap:
		return s, &InputError{Field: "dailyTokenCap", Message: "enter a number of tokens up to 10 billion"}
	}
	return s, nil
}

func put(ctx context.Context, tx *sql.Tx, k, v string) error {
	_, err := tx.ExecContext(ctx,
		`INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value`, k, v)
	return err
}
