package agent

import (
	"context"
	"errors"
	"log/slog"
	"strconv"
	"sync"
	"time"

	"notes/server/internal/llm"
)

// Home is a notes file whose agents one worker runs: an account's own, or a
// shared space's (§7.9). Each has its own queue and writing sessions.
type Home string

// UserHome is an account's own file.
func UserHome(id int64) Home { return Home("user:" + strconv.FormatInt(id, 10)) }

// SpaceHome is a shared space's file.
func SpaceHome(id string) Home { return Home("space:" + id) }

// RunnerConfig is what a Runner needs from the server.
type RunnerConfig struct {
	// Homes lists every file: each account's and each shared space's.
	Homes func(ctx context.Context) ([]Home, error)
	// Exists reports whether a file's account or shared space is still there.
	Exists func(ctx context.Context, home Home) (bool, error)
	// Open returns a file's data.
	Open func(ctx context.Context, home Home) (Account, error)
	// Settings returns the AI provider's settings, or llm.ErrNotConfigured.
	Settings func(ctx context.Context) (llm.Settings, error)
	Provider Provider
	Log      *slog.Logger
	// Now is the clock; time.Now when nil.
	Now func() time.Time
	// Tell sends a message to every editor of an account, for results done ahead. Must not block.
	Tell func(userID int64, msg any)
}

// MaxConcurrentCalls bounds calls to the provider across accounts.
const MaxConcurrentCalls = 4

// idleCheck is how often a worker looks again at a run it couldn't start:
// for AI coming back, or an error clearing.
var idleCheck = time.Minute

// Runner runs every file's queued agent runs, one at a time per note.
// Each file has two slots: one runs any run, one after another; the
// other starts each run an open editor asked for at once, beside the rest,
// so someone in a note never waits behind others.
type Runner struct {
	homes    func(ctx context.Context) ([]Home, error)
	exists   func(ctx context.Context, home Home) (bool, error)
	open     func(ctx context.Context, home Home) (Account, error)
	settings func(ctx context.Context) (llm.Settings, error)
	provider Provider
	log      *slog.Logger
	now      func() time.Time
	calls    chan struct{}

	mu      sync.Mutex
	ctx     context.Context // set by Run
	workers map[Home]*worker
	wg      sync.WaitGroup

	ahead  ahead
	tellFn func(userID int64, msg any)
}

// NewRunner returns a runner; Run starts it.
func NewRunner(c RunnerConfig) *Runner {
	r := &Runner{
		homes: c.Homes, exists: c.Exists, open: c.Open, settings: c.Settings, provider: c.Provider, log: c.Log, now: c.Now,
		calls: make(chan struct{}, MaxConcurrentCalls), workers: map[Home]*worker{}, tellFn: c.Tell,
		ahead: ahead{slots: map[string]*aheadSlot{}, current: map[aheadKey]*aheadSlot{}, cannot: map[aheadKey]string{}},
	}
	if r.now == nil {
		r.now = time.Now
	}
	if r.log == nil {
		r.log = slog.Default()
	}
	return r
}

// Run starts a worker for every file and runs until ctx ends, then
// waits for the runs in progress to be recorded.
func (rn *Runner) Run(ctx context.Context) {
	rn.mu.Lock()
	rn.ctx = ctx
	rn.mu.Unlock()
	// Until every file has a worker, queued runs wait for something new to wake theirs.
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

// worker is a file's two slots' wake-ups.
type worker struct{ any, editor chan struct{} }

// Wake has a file's worker look at its queue, starting the worker if
// there's none yet. It never blocks.
func (rn *Runner) Wake(home Home) {
	rn.mu.Lock()
	defer rn.mu.Unlock()
	if rn.ctx == nil || rn.ctx.Err() != nil {
		return
	}
	w, ok := rn.workers[home]
	if !ok {
		w = &worker{any: make(chan struct{}, 1), editor: make(chan struct{}, 1)}
		rn.workers[home] = w
		rn.wg.Add(1)
		go func() {
			defer rn.wg.Done()
			rn.work(rn.ctx, home, w)
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
	homes := make([]Home, 0, len(rn.workers))
	for h := range rn.workers {
		homes = append(homes, h)
	}
	rn.mu.Unlock()
	for _, h := range homes {
		rn.Wake(h)
	}
}

func (rn *Runner) work(ctx context.Context, home Home, w *worker) {
	acct, err := rn.open(ctx, home)
	if err != nil {
		rn.log.Error("agent runner: open", "home", home, "err", err)
		rn.mu.Lock()
		delete(rn.workers, home)
		rn.mu.Unlock()
		return
	}
	// Until this succeeds, the runs a stopped server left running would stay so.
	for {
		err := acct.Agent.recoverRuns(ctx)
		if err == nil {
			break
		}
		if ctx.Err() != nil {
			return
		}
		rn.log.Error("agent runner: recover runs", "home", home, "err", err)
		t := time.NewTimer(idleCheck)
		select {
		case <-ctx.Done():
			t.Stop()
			return
		case <-t.C:
		}
	}
	rn.wg.Add(1)
	go func() {
		defer rn.wg.Done()
		rn.slot(ctx, home, acct, true, w.editor)
	}()
	rn.slot(ctx, home, acct, false, w.any)
}

// slot runs a file's runs one after another; editorOnly, it starts
// each one an open editor asked for, without waiting for it to end.
func (rn *Runner) slot(ctx context.Context, home Home, acct Account, editorOnly bool, wake <-chan struct{}) {
	for ctx.Err() == nil {
		if rn.step(ctx, home, acct, editorOnly) {
			continue
		}
		// With nothing queued and no one writing, only a wake brings work.
		var t *time.Timer
		var timer <-chan time.Time
		if due, err := acct.Agent.nextDue(ctx); err != nil || !due.IsZero() {
			wait := due.Sub(rn.now())
			if err != nil || wait <= 0 {
				wait = idleCheck
			}
			t = time.NewTimer(wait)
			timer = t.C
		}
		select {
		case <-ctx.Done():
		case <-wake:
		case <-timer:
		}
		if t != nil {
			t.Stop()
		}
	}
}

// step ends the writing sessions gone idle (the slot for any run only),
// then runs the next due run if AI is set up and there is one, and reports
// whether it did.
func (rn *Runner) step(ctx context.Context, home Home, acct Account, editorOnly bool) bool {
	// remove-user runs beside the server and keeps the account's data, queue
	// included: none of it may reach the provider after.
	if ok, err := rn.exists(ctx, home); err != nil || !ok {
		if err != nil && ctx.Err() == nil {
			rn.log.Error("agent runner: home", "home", home, "err", err)
		}
		return false
	}
	// Before AI is checked: while it's paused, the "done" runs still queue.
	if !editorOnly {
		if _, err := acct.Agent.CloseIdleSessions(ctx); err != nil && ctx.Err() == nil {
			rn.log.Error("agent runner: end writing sessions", "home", home, "err", err)
		}
	}
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
			rn.log.Error("agent runner: claim", "home", home, "err", err)
		}
		return false
	}
	if !ok {
		return false
	}
	run := func() {
		acct.notify(RunUpdate{RunID: r.ID, NoteID: r.NoteID, Trigger: r.Trigger, Status: StatusRunning})
		rn.execute(ctx, acct, st, r)
		// The other slot may have passed over a run on this note.
		rn.Wake(home)
	}
	if !editorOnly {
		run()
		return true
	}
	// Someone is in the note, waiting: each note's run starts at once, beside
	// the others' (claim takes one per note at a time).
	rn.wg.Add(1)
	go func() {
		defer rn.wg.Done()
		run()
	}()
	return true
}

// startRetry is how soon the runner tries again to find the accounts, when
// it couldn't at startup.
var startRetry = 30 * time.Second

// startWorkers wakes every file's worker, and reports whether it could
// list them.
func (rn *Runner) startWorkers(ctx context.Context) bool {
	homes, err := rn.homes(ctx)
	if err != nil {
		if ctx.Err() == nil {
			rn.log.Error("agent runner: homes", "err", err)
		}
		return false
	}
	for _, h := range homes {
		rn.Wake(h)
	}
	return true
}

func (rn *Runner) purge(ctx context.Context) {
	homes, err := rn.homes(ctx)
	if err != nil {
		return
	}
	for _, h := range homes {
		acct, err := rn.open(ctx, h)
		if err == nil {
			err = acct.Agent.Purge(ctx)
		}
		if err != nil && ctx.Err() == nil {
			rn.log.Error("agent runner: purge", "home", h, "err", err)
		}
	}
}
