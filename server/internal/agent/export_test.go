package agent

import (
	"context"
	"time"
)

// RunNext runs an account's next due run now, on the caller's goroutine,
// and reports whether there was one. Test-only: beside a running worker it
// would break one run at a time per account.
func (rn *Runner) RunNext(ctx context.Context, userID int64) (bool, error) {
	acct, err := rn.open(ctx, userID)
	if err != nil {
		return false, err
	}
	return rn.step(ctx, userID, acct), nil
}

// RecoverRuns puts back the runs a stopped server left running; workers do
// this when they start.
func (s *Store) RecoverRuns(ctx context.Context) error { return s.recoverRuns(ctx) }

// SetStartRetry shortens startRetry until the test ends.
func SetStartRetry(t interface{ Cleanup(func()) }, d time.Duration) {
	old := startRetry
	startRetry = d
	t.Cleanup(func() { startRetry = old })
}
