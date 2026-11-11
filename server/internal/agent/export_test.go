package agent

import (
	"context"
	"time"
)

// RunNext runs account userID's next due run now, on the caller's goroutine,
// and reports whether there was one. Test-only: beside a running worker it
// would break one run at a time per note.
func (rn *Runner) RunNext(ctx context.Context, userID int64) (bool, error) {
	home := UserHome(userID)
	acct, err := rn.open(ctx, home)
	if err != nil {
		return false, err
	}
	return rn.step(ctx, home, acct, false), nil
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

// DiffLines is Diff with nothing folded.
func DiffLines(before, after string) []DiffLine { return diffLines(before, after) }

// SystemPrompt is what a run of trigger t is told first.
func SystemPrompt(c Config, t Trigger) string { return systemPrompt(c, t) }

// PrepareNow is Prepare on the caller's goroutine, for a runner that isn't running.
func (rn *Runner) PrepareNow(ctx context.Context, home Home, spaceID string, userID int64) {
	if s, runCtx := rn.newAhead(ctx, aheadKey{home, spaceID, userID}); s != nil {
		rn.runAhead(runCtx, s)
	}
}

// PreparedFor is the result done ahead ready for a space and user, if there's one.
func (rn *Runner) PreparedFor(home Home, spaceID string, userID int64) (Prepared, bool) {
	rn.ahead.mu.Lock()
	defer rn.ahead.mu.Unlock()
	s := rn.ahead.current[aheadKey{home, spaceID, userID}]
	if s == nil || s.ready == nil {
		return Prepared{}, false
	}
	return *s.ready, true
}
