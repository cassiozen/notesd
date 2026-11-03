package agent

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"

	"notes/server/internal/llm"
)

// RunnerConfig is what a Runner needs from the server.
type RunnerConfig struct {
	// Accounts lists every account's id.
	Accounts func(ctx context.Context) ([]int64, error)
	// Open returns an account's data.
	Open func(ctx context.Context, userID int64) (Account, error)
	// Settings returns the AI provider's settings, or llm.ErrNotConfigured.
	Settings func(ctx context.Context) (llm.Settings, error)
	Provider Provider
	Log      *slog.Logger
	// Now is the clock; time.Now when nil.
	Now func() time.Time
}

// MaxConcurrentCalls bounds calls to the provider across accounts.
const MaxConcurrentCalls = 4

// idleCheck is how often a worker looks at its queue with nothing to wake
// it: for retries, and for AI coming back.
var idleCheck = time.Minute

// Runner runs every account's queued agent runs, one at a time per note.
// Each account has two slots: one for any run, and one only for runs an
// open editor asked for, so those never wait behind the others.
type Runner struct {
	accounts func(ctx context.Context) ([]int64, error)
	open     func(ctx context.Context, userID int64) (Account, error)
	settings func(ctx context.Context) (llm.Settings, error)
	provider Provider
	log      *slog.Logger
	now      func() time.Time
	calls    chan struct{}

	mu      sync.Mutex
	ctx     context.Context // set by Run
	workers map[int64]*worker
	wg      sync.WaitGroup
}

// NewRunner returns a runner; Run starts it.
func NewRunner(c RunnerConfig) *Runner {
	r := &Runner{
		accounts: c.Accounts, open: c.Open, settings: c.Settings, provider: c.Provider, log: c.Log, now: c.Now,
		calls: make(chan struct{}, MaxConcurrentCalls), workers: map[int64]*worker{},
	}
	if r.now == nil {
		r.now = time.Now
	}
	if r.log == nil {
		r.log = slog.Default()
	}
	return r
}

// Run starts a worker for every account and runs until ctx ends, then
// waits for the runs in progress to be recorded.
func (rn *Runner) Run(ctx context.Context) {
	rn.mu.Lock()
	rn.ctx = ctx
	rn.mu.Unlock()
	// Until every account has a worker, queued runs wait for a note write to wake theirs.
	started := rn.startWorkers(ctx)
	retry := time.NewTicker(startRetry)
	defer retry.Stop()
	purge := time.NewTicker(time.Hour)
	defer purge.Stop()
	for {
		select {
		case <-ctx.Done():
			rn.wg.Wait()
			return
		case <-retry.C:
			if !started {
				started = rn.startWorkers(ctx)
			}
		case <-purge.C:
			rn.purge(ctx)
		}
	}
}

// worker is an account's two slots' wake-ups.
type worker struct{ any, editor chan struct{} }

// Wake has an account's worker look at its queue, starting the worker if
// there's none yet. It never blocks.
func (rn *Runner) Wake(userID int64) {
	rn.mu.Lock()
	defer rn.mu.Unlock()
	if rn.ctx == nil || rn.ctx.Err() != nil {
		return
	}
	w, ok := rn.workers[userID]
	if !ok {
		w = &worker{any: make(chan struct{}, 1), editor: make(chan struct{}, 1)}
		rn.workers[userID] = w
		rn.wg.Add(1)
		go func() {
			defer rn.wg.Done()
			rn.work(rn.ctx, userID, w)
		}()
	}
	for _, c := range []chan struct{}{w.any, w.editor} {
		select {
		case c <- struct{}{}:
		default:
		}
	}
}

// WakeAll wakes every worker: AI was set up or changed.
func (rn *Runner) WakeAll() {
	rn.mu.Lock()
	ids := make([]int64, 0, len(rn.workers))
	for id := range rn.workers {
		ids = append(ids, id)
	}
	rn.mu.Unlock()
	for _, id := range ids {
		rn.Wake(id)
	}
}

func (rn *Runner) work(ctx context.Context, userID int64, w *worker) {
	acct, err := rn.open(ctx, userID)
	if err != nil {
		rn.log.Error("agent runner: open account", "user", userID, "err", err)
		rn.mu.Lock()
		delete(rn.workers, userID)
		rn.mu.Unlock()
		return
	}
	if err := acct.Agent.recoverRuns(ctx); err != nil {
		rn.log.Error("agent runner: recover runs", "user", userID, "err", err)
	}
	rn.wg.Add(1)
	go func() {
		defer rn.wg.Done()
		rn.slot(ctx, userID, acct, true, w.editor)
	}()
	rn.slot(ctx, userID, acct, false, w.any)
}

// slot runs an account's runs one after another; editorOnly, only the
// ones an open editor asked for.
func (rn *Runner) slot(ctx context.Context, userID int64, acct Account, editorOnly bool, wake <-chan struct{}) {
	for ctx.Err() == nil {
		if rn.step(ctx, userID, acct, editorOnly) {
			continue
		}
		wait := idleCheck
		if due, err := acct.Agent.nextDue(ctx); err == nil && !due.IsZero() {
			wait = min(wait, max(due.Sub(rn.now()), 0))
		}
		t := time.NewTimer(wait)
		select {
		case <-ctx.Done():
		case <-wake:
		case <-t.C:
		}
		t.Stop()
	}
}

// step runs the next due run, if AI is set up and there is one, and
// reports whether it did.
func (rn *Runner) step(ctx context.Context, userID int64, acct Account, editorOnly bool) bool {
	st, err := rn.settings(ctx)
	if errors.Is(err, llm.ErrNotConfigured) {
		return false // paused: the queue waits for AI to come back
	}
	if err != nil {
		rn.log.Error("agent runner: AI settings", "err", err)
		return false
	}
	r, ok, err := acct.Agent.claim(ctx, editorOnly)
	if err != nil {
		if ctx.Err() == nil {
			rn.log.Error("agent runner: claim", "user", userID, "err", err)
		}
		return false
	}
	if !ok {
		return false
	}
	acct.notify(RunUpdate{RunID: r.ID, NoteID: r.NoteID, Trigger: r.Trigger, Status: StatusRunning})
	rn.execute(ctx, acct, st, r)
	// The other slot may have passed over a run on this note.
	rn.Wake(userID)
	return true
}

// startRetry is how soon the runner tries again to find the accounts, when
// it couldn't at startup.
var startRetry = 30 * time.Second

// startWorkers wakes every account's worker, and reports whether it could
// list them.
func (rn *Runner) startWorkers(ctx context.Context) bool {
	ids, err := rn.accounts(ctx)
	if err != nil {
		if ctx.Err() == nil {
			rn.log.Error("agent runner: accounts", "err", err)
		}
		return false
	}
	for _, id := range ids {
		rn.Wake(id)
	}
	return true
}

func (rn *Runner) purge(ctx context.Context) {
	ids, err := rn.accounts(ctx)
	if err != nil {
		return
	}
	for _, id := range ids {
		acct, err := rn.open(ctx, id)
		if err == nil {
			err = acct.Agent.Purge(ctx)
		}
		if err != nil && ctx.Err() == nil {
			rn.log.Error("agent runner: purge", "user", id, "err", err)
		}
	}
}
