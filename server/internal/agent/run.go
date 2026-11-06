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
	// Editors are the account's open note editors; nil reads and writes
	// only the store.
	Editors Editors
	// OnRun hears of each run starting and ending, for the user's editors.
	OnRun func(RunUpdate)
}

// RunUpdate is a run starting or ending.
type RunUpdate struct {
	RunID   string  `json:"runId"`
	NoteID  string  `json:"noteId"`
	Trigger Trigger `json:"trigger"`
	Status  string  `json:"status"`
	Failure string  `json:"failure,omitempty"`
	Outcome string  `json:"outcome,omitempty"`
}

func (a Account) notify(u RunUpdate) {
	if a.OnRun != nil {
		a.OnRun(u)
	}
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
	// inEditor holds the notes read from an editor; their writes go back to it.
	inEditor map[string]*editorRead
	ops      int    // writes so far, for their operation ids
	day      string // in the account's time zone, for usage
	// budget is the tokens this attempt may use: what's left of MaxRunTokens,
	// or less when that's all the day has left (dailyLimited).
	budget       int64
	dailyLimited bool
}

// execute runs r to its end and records how it went.
func (rn *Runner) execute(ctx context.Context, acct Account, st llm.Settings, r queuedRun) {
	began := time.Now()
	waited := rn.now().Sub(time.UnixMilli(r.CreatedAt))
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
	u := RunUpdate{RunID: r.ID, NoteID: r.NoteID, Trigger: r.Trigger, Status: f.Status, Failure: f.Failure, Outcome: f.Outcome}
	if !f.RetryAt.IsZero() {
		u.Status, u.Failure = StatusQueued, ""
	}
	acct.notify(u)
	t := f.timing
	rn.log.Info("agent run", "run", r.ID, "trigger", r.Trigger, "status", f.Status, "failure", f.Failure,
		"tools", f.ToolCalls, "tokens", f.PromptTokens+f.CompletionTokens,
		"waited", waited, "prep", t.prep, "modelCalls", t.calls, "model", t.model, "toolTime", t.tools, "total", time.Since(began))
}

// attempt checks the run still makes sense, then talks to the model.
func (rn *Runner) attempt(ctx context.Context, acct Account, st llm.Settings, r queuedRun, now time.Time) finished {
	began := time.Now()
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

	// An editor trigger's note is read from its editor below.
	var note *notes.Note
	if r.Event.Title == nil && !r.Trigger.InEditor() {
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
	rs := &runState{acct: acct, run: r, reads: map[string]notes.Note{}, inEditor: map[string]*editorRead{}, day: day,
		budget: min(left, today), dailyLimited: today <= left}
	switch {
	case r.Trigger.InEditor():
		n, err := rs.readEditor(runCtx, r.NoteID)
		if u := unusable(n, err, r.SpaceID, false); u.Status != "" {
			return u
		}
		note = &n
	case note != nil:
		// As the user sees it, if an editor has it open.
		n, err := rs.read(runCtx, note.ID)
		var stop *stopError
		switch {
		case errors.As(err, &stop):
			return stop.f
		case err != nil:
			n = *note
			rs.reads[n.ID] = n
		}
		note = &n
	}
	var cursor *int
	if note != nil && rs.inEditor[note.ID] != nil {
		cursor = rs.inEditor[note.ID].cursor
	}
	if rc, err := rs.gather(runCtx, c); err != nil {
		if runCtx.Err() == nil {
			return failed(FailInterrupted, err)
		}
		// Else the deadline or a shutdown, told apart below.
	} else {
		messages := []llm.Message{
			{Role: "system", Content: systemPrompt(c, r.Trigger)},
			{Role: "user", Content: eventMessage(r, now, note, cursor, rc, spaceName)},
		}
		f.timing.prep = time.Since(began)
		rn.converse(runCtx, rs, st, messages, &f)
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
	case errors.Is(err, errEditorClosed):
		return skipped(FailNoteClosed, "The note was closed "+when+".")
	case errors.Is(err, errLockedInEditor) && started:
		return skipped(FailNoteLocked, "The note was locked "+when+".")
	case errors.Is(err, errLockedInEditor):
		return skipped(FailNoteLocked, "The note is locked. Locked notes are never sent.")
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
func (rn *Runner) converse(ctx context.Context, rs *runState, st llm.Settings, messages []llm.Message, f *finished) {
	for {
		// Locked since the run began (its event may carry a copy of the
		// note): nothing more of it is sent. Deleted or moved, for a run
		// whose trigger wasn't that: nothing more is done with it.
		n, err := rs.acct.Notes.Get(ctx, rs.run.NoteID)
		switch {
		case err == nil && locks.IsLocked(n.Content):
			f.Status, f.Failure, f.Message = StatusFailed, FailNoteLocked, "The note was locked, so the agent stopped."
			// Not tokens: a compatible provider may leave usage out. A
			// later attempt follows one that called the model.
			if !f.sent && rs.run.Attempts < 2 {
				f.Status, f.Message = StatusSkipped, "The note is locked. Locked notes are never sent."
			}
			return
		case err != nil && ctx.Err() != nil:
			// The deadline or a shutdown; the caller says which.
			return
		case rs.run.Event.Title == nil && unusable(n, err, rs.run.SpaceID, true).Status != "":
			u := unusable(n, err, rs.run.SpaceID, true)
			f.Status, f.Failure, f.Message = u.Status, u.Failure, u.Message
			return
		case err != nil && !errors.Is(err, notes.ErrNotFound):
			f.Status, f.Failure, f.Message = StatusFailed, FailInterrupted, err.Error()
			return
		}
		f.sent = true
		called := time.Now()
		res, err := rn.chat(ctx, st, llm.ChatRequest{Messages: messages, Tools: tools})
		f.timing.calls++
		f.timing.model += time.Since(called)
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
			return
		}
		f.Model = res.Model
		// A provider's negative count would hand tokens back to the caps.
		prompt, completion := max(res.Usage.PromptTokens, 0), max(res.Usage.CompletionTokens, 0)
		f.PromptTokens += prompt
		f.CompletionTokens += completion
		// Recorded even if the run's time ran out as the answer came back: they were spent.
		saved, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
		err = rs.acct.Agent.addUsage(saved, rs.run.ID, rs.day, res.Model, prompt, completion)
		cancel()
		if err != nil {
			interrupted(ctx, f, err)
			return
		}
		if ctx.Err() != nil {
			return // the deadline or a shutdown, told apart by the caller
		}
		if len(res.Message.ToolCalls) == 0 {
			f.Status, f.Message = StatusDone, strings.TrimSpace(res.Message.Content)
			return
		}
		// At the budget, not only past it: the tools would lead to another call.
		if f.PromptTokens+f.CompletionTokens >= rs.budget {
			f.Status, f.Failure = StatusFailed, FailTokenCap
			f.Message = fmt.Sprintf("The run used more than %d tokens.", MaxRunTokens)
			if rs.dailyLimited {
				f.Failure, f.Message = FailDailyCap, "The account reached its tokens for today during the run."
			}
			return
		}
		messages = append(messages, llm.Message{Role: "assistant", Content: res.Message.Content, ToolCalls: res.Message.ToolCalls})
		// The model's summary, when it said this reply holds its last change.
		summary, refused := "", false
		for _, tc := range res.Message.ToolCalls {
			f.ToolCalls++
			if f.ToolCalls > MaxToolCalls-rs.run.PriorToolCalls {
				f.Status, f.Failure = StatusFailed, FailToolCap
				f.Message = fmt.Sprintf("The run made more than %d tool calls.", MaxToolCalls)
				return
			}
			ran := time.Now()
			out, err := rs.call(ctx, tc)
			f.timing.tools += time.Since(ran)
			if d := doneWith(tc); d != "" {
				summary = d
			}
			var te *toolError
			var stop *stopError
			switch {
			case errors.As(err, &stop):
				f.Status, f.Failure, f.Message = stop.f.Status, stop.f.Failure, stop.f.Message
				return
			case errors.As(err, &te):
				refused = true
				out = toolErrorResult(te)
			case errors.Is(err, errEditorGone):
				f.Status, f.Failure, f.Message = StatusFailed, FailEditorGone, "The note's editor went away while the agent was changing it."
				return
			case err != nil:
				interrupted(ctx, f, err)
				return
			}
			messages = append(messages, llm.Message{Role: "tool", ToolCallID: tc.ID, Content: out})
		}
		// Saves a model call just to say so. A refused call needs the model
		// to hear why, so the run goes on.
		if summary != "" && !refused {
			f.Status, f.Message = StatusDone, summary
			return
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
