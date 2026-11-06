package agent

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"notes/server/internal/llm"
	"notes/server/internal/locks"
	"notes/server/internal/notes"
)

// Limits on one run.
var (
	MaxToolCalls  = 20
	MaxRunTokens  = int64(150_000)
	RunTimeout    = 5 * time.Minute
	CallTimeout   = 90 * time.Second
	RetryProvider = 30 * time.Second
)

// Account is what the runner needs of one account's data.
type Account struct {
	Notes *notes.Store
	Agent *Store
	// SpaceName names a space for the agent, by id.
	SpaceName func(ctx context.Context, id string) string
}

// Provider is the model: llm.Client, or a fake in tests.
type Provider interface {
	Chat(ctx context.Context, s llm.Settings, req llm.ChatRequest) (*llm.ChatResponse, error)
}

// runState is one run in progress.
type runState struct {
	acct  Account
	run   queuedRun
	reads map[string]notes.Note // as last read or written in this run
	ops   int                   // writes so far, for their operation ids
	day   string                // in the account's time zone, for usage
	// budget is the tokens this attempt may use: what's left of MaxRunTokens,
	// or less when that's all the day has left (dailyLimited).
	budget       int64
	dailyLimited bool
}

// execute runs r to its end and records how it went.
func (rn *Runner) execute(ctx context.Context, acct Account, st llm.Settings, r queuedRun) {
	var f finished
	// Without the account's time zone, the day's usage would go to the wrong day.
	if settings, err := acct.Agent.Settings(ctx); err != nil {
		f = failed(FailInterrupted, err)
	} else {
		f = rn.attempt(ctx, acct, st, r, rn.now().In(settings.Location()))
	}
	if ctx.Err() != nil && f.Status == "" {
		f = finished{Status: StatusFailed, Failure: FailInterrupted, Message: "The server stopped during the run."}
	}
	if f.Status == StatusDone {
		edits, creates, err := acct.Agent.edited(ctx, r.ID)
		if err != nil {
			rn.log.Error("agent run: edits", "run", r.ID, "err", err)
		}
		switch {
		case edits > 0:
			f.Outcome = OutcomeEdited
		case creates > 0:
			f.Outcome = OutcomeCreated
		default:
			f.Outcome = OutcomeNoChange
		}
	}
	// Recorded even as the server stops.
	done, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
	defer cancel()
	if err := acct.Agent.finish(done, r.ID, f); err != nil {
		rn.log.Error("agent run: record", "run", r.ID, "err", err)
	}
	rn.log.Info("agent run", "run", r.ID, "trigger", r.Trigger, "status", f.Status, "failure", f.Failure,
		"tools", f.ToolCalls, "tokens", f.PromptTokens+f.CompletionTokens)
}

// attempt checks the run still makes sense, then talks to the model.
func (rn *Runner) attempt(ctx context.Context, acct Account, st llm.Settings, r queuedRun, now time.Time) finished {
	day := now.Format(time.DateOnly)
	if _, ok := triggerNames[r.Trigger]; !ok {
		return skipped(FailUnknownEvent, "This server doesn't know the trigger "+string(r.Trigger)+".")
	}
	c, err := acct.Agent.Config(ctx, r.SpaceID)
	if errors.Is(err, ErrSpaceNotFound) || (err == nil && !c.Acts(r.Trigger)) {
		return skipped(FailAgentOff, "The space's agent was turned off, or no longer acts on this.")
	}
	if err != nil {
		return failed(FailInterrupted, err)
	}

	var note *notes.Note
	if r.Event.Title == nil {
		n, err := acct.Notes.Get(ctx, r.NoteID)
		if f := unusable(n, err, r.SpaceID, false); f.Status != "" {
			return f
		}
		note = &n
	}

	used, err := acct.Agent.usedOn(ctx, day)
	if err != nil {
		return failed(FailInterrupted, err)
	}
	if used >= st.DailyTokenCap {
		return skipped(FailDailyCap, fmt.Sprintf("The account used its %d tokens for today.", st.DailyTokenCap))
	}
	left, today := MaxRunTokens-r.PriorTokens, st.DailyTokenCap-used
	// Spent before a crash: another call would only go over.
	if left <= 0 {
		return finished{Status: StatusFailed, Failure: FailTokenCap, Message: fmt.Sprintf("The run used its %d tokens.", MaxRunTokens)}
	}

	runCtx, cancel := context.WithTimeout(ctx, RunTimeout)
	defer cancel()
	spaceName := func(id string) string { return acct.SpaceName(ctx, id) }
	var f finished
	for start := 0; start < 2; start++ {
		rs := &runState{acct: acct, run: r, reads: map[string]notes.Note{}, ops: start * 1000, day: day,
			budget: min(left, today), dailyLimited: today <= left}
		if note != nil {
			rs.reads[note.ID] = *note
		}
		messages := []llm.Message{
			{Role: "system", Content: systemPrompt(c, r.Trigger, now)},
			{Role: "user", Content: eventMessage(r, note, spaceName)},
		}
		err := rn.converse(runCtx, rs, st, messages, &f)
		if !errors.Is(err, errRestart) {
			break
		}
		if start == 1 {
			f.Status, f.Failure = StatusFailed, FailNoteChanged
			f.Message = "The note kept changing while the agent worked on it, so it gave up."
			break
		}
		// Start over from the note as it is now.
		if note != nil {
			n, err := acct.Notes.Get(ctx, r.NoteID)
			if u := unusable(n, err, r.SpaceID, true); u.Status != "" {
				// f keeps what the first try spent.
				f.Status, f.Failure, f.Message = u.Status, u.Failure, u.Message
				return f
			}
			note = &n
		}
	}
	if errors.Is(runCtx.Err(), context.DeadlineExceeded) && f.Status == "" {
		f.Status, f.Failure, f.Message = StatusFailed, FailTimeout, fmt.Sprintf("The run took longer than %s.", RunTimeout)
	}
	if f.Status == StatusFailed && f.Failure == FailProvider && r.Attempts < 2 {
		if edits, creates, err := acct.Agent.edited(ctx, r.ID); err == nil && edits+creates == 0 {
			f.RetryAt = rn.now().Add(RetryProvider)
		}
	}
	return f
}

// interrupted fails f for err, unless the run's deadline or a shutdown
// caused it: the caller tells those apart.
func interrupted(ctx context.Context, f *finished, err error) {
	if ctx.Err() == nil {
		f.Status, f.Failure, f.Message = StatusFailed, FailInterrupted, err.Error()
	}
}

func failed(why string, err error) finished {
	return finished{Status: StatusFailed, Failure: why, Message: err.Error()}
}

func skipped(why, msg string) finished {
	return finished{Status: StatusSkipped, Failure: why, Message: msg}
}

// unusable is why a run can't go on with its note as read, before the model
// is first called or after the note changed under it; no Status if it can.
func unusable(n notes.Note, err error, spaceID string, started bool) finished {
	when := "before the agent got to it"
	if started {
		when = "while the agent worked on it"
	}
	switch {
	case errors.Is(err, notes.ErrNotFound) || (err == nil && n.DeletedAt != nil):
		return skipped(FailNoteGone, "The note was deleted "+when+".")
	case err != nil:
		return failed(FailInterrupted, err)
	case n.SpaceID != spaceID:
		return skipped(FailNoteMoved, "The note was moved to another space "+when+".")
	case locks.IsLocked(n.Content) && started:
		return skipped(FailNoteLocked, "The note was locked "+when+".")
	case locks.IsLocked(n.Content):
		return skipped(FailNoteLocked, "The note is locked. Locked notes are never sent.")
	}
	return finished{}
}

// converse talks to the model until it stops calling tools, filling in f.
// It returns errRestart when a note changed under a write.
func (rn *Runner) converse(ctx context.Context, rs *runState, st llm.Settings, messages []llm.Message, f *finished) error {
	for {
		res, err := rn.chat(ctx, st, llm.ChatRequest{Messages: messages, Tools: tools})
		if err != nil {
			var pe *llm.ProviderError
			switch {
			case errors.As(err, &pe):
				f.Status, f.Failure, f.Message = StatusFailed, FailProvider, pe.Message
			case ctx.Err() != nil:
				// The deadline or a shutdown; the caller says which.
			default:
				f.Status, f.Failure, f.Message = StatusFailed, FailProvider, err.Error()
			}
			return err
		}
		f.Model = res.Model
		f.PromptTokens += res.Usage.PromptTokens
		f.CompletionTokens += res.Usage.CompletionTokens
		if err := rs.acct.Agent.addUsage(ctx, rs.run.ID, rs.day, res.Model, res.Usage.PromptTokens, res.Usage.CompletionTokens); err != nil {
			interrupted(ctx, f, err)
			return err
		}
		if f.PromptTokens+f.CompletionTokens > rs.budget {
			f.Status, f.Failure = StatusFailed, FailTokenCap
			f.Message = fmt.Sprintf("The run used more than %d tokens.", MaxRunTokens)
			if rs.dailyLimited {
				f.Failure, f.Message = FailDailyCap, "The account reached its tokens for today during the run."
			}
			return nil
		}
		if len(res.Message.ToolCalls) == 0 {
			f.Status, f.Message = StatusDone, strings.TrimSpace(res.Message.Content)
			return nil
		}
		messages = append(messages, llm.Message{Role: "assistant", Content: res.Message.Content, ToolCalls: res.Message.ToolCalls})
		for _, tc := range res.Message.ToolCalls {
			f.ToolCalls++
			if f.ToolCalls > MaxToolCalls-rs.run.PriorToolCalls {
				f.Status, f.Failure = StatusFailed, FailToolCap
				f.Message = fmt.Sprintf("The run made more than %d tool calls.", MaxToolCalls)
				return nil
			}
			out, err := rs.call(ctx, tc)
			var te *toolError
			switch {
			case errors.Is(err, errRestart):
				return err
			case errors.As(err, &te):
				out = jsonString(map[string]string{"error": te.msg})
			case err != nil:
				interrupted(ctx, f, err)
				return err
			}
			messages = append(messages, llm.Message{Role: "tool", ToolCallID: tc.ID, Content: out})
		}
	}
}

// chat is one call to the model, bounded by CallTimeout and by how many
// calls the server makes at once.
func (rn *Runner) chat(ctx context.Context, st llm.Settings, req llm.ChatRequest) (*llm.ChatResponse, error) {
	select {
	case rn.calls <- struct{}{}:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	defer func() { <-rn.calls }()
	callCtx, cancel := context.WithTimeout(ctx, CallTimeout)
	defer cancel()
	return rn.provider.Chat(callCtx, st, req)
}
