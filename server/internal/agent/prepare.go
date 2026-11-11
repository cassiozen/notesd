package agent

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"slices"
	"sync"
	"time"

	"notes/server/internal/llm"
	"notes/server/internal/notes"
	"notes/server/internal/search"
)

// "New note opened" runs on an empty note, so it can run before the note exists (§7.11). The result
// stays in memory until a new note opens with it; only its tokens reach the database before that.

// How long a result stands (never past midnight: the run was told the day), and how long one
// thrown away or expired can still be used, for a new note already asked for as it went.
var (
	AheadFor   = 30 * time.Minute
	aheadGrace = time.Minute
)

// aheadNoteID stands for the note a run done ahead is about, which doesn't exist yet.
const aheadNoteID = "new-note"

// failNotAhead: a change to another note, or a new one, can't wait in memory. The rule runs live instead.
const failNotAhead = "not_ahead"

var errNotAhead = &stopError{finished{Status: StatusSkipped, Failure: failNotAhead}}

// ErrPreparedGone refuses a result done ahead that's no longer there to use: the editor makes the run live instead.
var ErrPreparedGone = errors.New("no such result done ahead")

// Prepared is a "new note opened" run done ahead: what the new note opens with.
type Prepared struct {
	ID       string    `json:"id"` // the run's id, once used
	SpaceID  string    `json:"spaceId"`
	Title    string    `json:"title"`
	Content  string    `json:"content"`
	Overlays []Overlay `json:"overlays"` // their noteId empty, for the note they'll be on
	// ExpiresAt is when an editor should stop using it, in Unix milliseconds.
	ExpiresAt int64 `json:"expiresAt"`

	by                 int64
	model, message     string
	toolCalls          int
	prompt, completion int64
}

// PreparedMessage hands an editor a result done ahead (the agent socket's "prepared").
func PreparedMessage(p Prepared) any {
	return struct {
		Type string `json:"type"`
		Prepared
	}{"prepared", p}
}

// PreparedVoidMessage tells editors a result done ahead, or one going, is no longer to be used ("prepared.void").
func PreparedVoidMessage(spaceID, id string) any {
	return map[string]string{"type": "prepared.void", "spaceId": spaceID, "id": id}
}

// Change is something that may make a result done ahead stale.
type Change struct {
	// ConfigOf is a space whose agent was set up again, or removed.
	ConfigOf string
	// Note is a note written.
	Note *notes.Event
}

// OnChange has fn hear of agent setups saved and notes written, for results done ahead. It's
// called inside a note write's transaction: fn must not block.
func (s *Store) OnChange(fn func(Change)) { s.onChange = fn }

func (s *Store) changed(c Change) {
	if s.onChange != nil {
		s.onChange(c)
	}
}

// aheadRun is what a run done ahead did, read by Changed while it's still going.
type aheadRun struct {
	mu       sync.Mutex
	reads    map[string]bool // notes it read, by id
	broad    bool            // it searched or listed notes: any note may have mattered
	overlays []Overlay
}

// All take a nil receiver, for a run that isn't ahead.

func (a *aheadRun) noteRead(id string) {
	if a == nil {
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	a.reads[id] = true
}

func (a *aheadRun) broaden() {
	if a == nil {
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	a.broad = true
}

func (a *aheadRun) show(list []Overlay) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.overlays = append(a.overlays, list...)
}

// staleBy reports whether a write to a note in the run's space changes what the run went by.
func (a *aheadRun) staleBy(ev *notes.Event, refs map[string]bool) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.broad || a.reads[ev.NoteID] || refs[ev.After.Title] {
		return true
	}
	var before *notes.Note
	if ev.Before != nil {
		before = ev.Before
		if refs[before.Title] {
			return true
		}
	}
	// Tags and their counts go with every run.
	return !slices.Equal(tagKeys(before), tagKeys(&ev.After))
}

func tagKeys(n *notes.Note) []string {
	if n == nil || n.DeletedAt != nil {
		return nil
	}
	var keys []string
	for _, t := range search.Tags(n.Title, n.Content) {
		keys = append(keys, t.Key)
	}
	slices.Sort(keys)
	return keys
}

// aheadEditor is the editor of a run done ahead: its own note is an empty one only it has, and
// any other is read from the account's editors as usual.
type aheadEditor struct {
	inner Editors
	mu    sync.Mutex
	note  EditorNote
}

func (e *aheadEditor) Read(ctx context.Context, noteID, prefer string) (EditorNote, error) {
	if noteID == aheadNoteID {
		e.mu.Lock()
		defer e.mu.Unlock()
		return e.note, nil
	}
	if e.inner == nil {
		return EditorNote{}, ErrNotOpen
	}
	return e.inner.Read(ctx, noteID, prefer)
}

func (e *aheadEditor) Apply(_ context.Context, noteID, _ string, c EditorChange) (EditorApplied, error) {
	// Never another note: a run done ahead stops before changing one.
	if noteID != aheadNoteID {
		return EditorApplied{}, ErrNotOpen
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	now := Fields{e.note.Title, e.note.Content}
	after, ok := c.MadeTo(now, e.note.Cursor)
	if !ok {
		return EditorApplied{}, ErrEditorChanged
	}
	e.note.Title, e.note.Content = after.Title, after.Content
	return EditorApplied{Before: now, Shows: after}, nil
}

// aheadKey is whose result it is: one per space and person, whichever of their tabs asked.
type aheadKey struct {
	home  Home
	space string
	user  int64
}

// aheadSlot is one result done ahead: going, ready, or thrown away but still usable for aheadGrace.
type aheadSlot struct {
	id     string
	key    aheadKey
	cancel context.CancelFunc
	run    *aheadRun
	refs   map[string]bool // the space's reference notes' titles, as the run read them
	ready  *Prepared
	// usableUntil, once it's thrown away or expired.
	usableUntil time.Time
}

// ahead is the runner's results done ahead.
type ahead struct {
	mu      sync.Mutex
	slots   map[string]*aheadSlot // by id, the thrown away ones too while usable
	current map[aheadKey]*aheadSlot
	// cannot: rules found to change other notes, by their setup's time. Live until set up again.
	cannot map[aheadKey]int64
}

// Prepare has a space's "new note opened" run done ahead for userID, unless one is going or ready;
// a ready one is told again, for a tab that hasn't got it.
func (rn *Runner) Prepare(home Home, spaceID string, userID int64) {
	rn.mu.Lock()
	defer rn.mu.Unlock()
	if rn.ctx == nil || rn.ctx.Err() != nil {
		return
	}
	s, ctx := rn.newAhead(rn.ctx, aheadKey{home, spaceID, userID})
	if s == nil {
		return
	}
	rn.wg.Add(1)
	go func() {
		defer rn.wg.Done()
		rn.runAhead(ctx, s)
	}()
}

// newAhead starts a slot for k, or tells k's user of the one ready; nil if one is going or ready.
func (rn *Runner) newAhead(ctx context.Context, k aheadKey) (*aheadSlot, context.Context) {
	a := &rn.ahead
	a.mu.Lock()
	now := rn.now()
	rn.sweepAhead(now)
	if s := a.current[k]; s != nil {
		var ready *Prepared
		if s.ready != nil {
			p := *s.ready
			ready = &p
		}
		a.mu.Unlock()
		if ready != nil {
			rn.tell(k.user, PreparedMessage(*ready))
		}
		return nil, nil
	}
	runCtx, cancel := context.WithCancel(ctx)
	s := &aheadSlot{id: newRunID(now.UnixMilli()), key: k, cancel: cancel, run: &aheadRun{reads: map[string]bool{}}}
	a.slots[s.id] = s
	a.current[k] = s
	a.mu.Unlock()
	return s, runCtx
}

// sweepAhead drops what's past use, and retires what expired. a.mu held.
func (rn *Runner) sweepAhead(now time.Time) {
	a := &rn.ahead
	for id, s := range a.slots {
		switch {
		case !s.usableUntil.IsZero() && now.After(s.usableUntil):
			delete(a.slots, id)
		case s.ready != nil && s.usableUntil.IsZero() && now.UnixMilli() >= s.ready.ExpiresAt:
			s.usableUntil = time.UnixMilli(s.ready.ExpiresAt).Add(aheadGrace)
			if a.current[s.key] == s {
				delete(a.current, s.key)
			}
		}
	}
}

func (rn *Runner) runAhead(ctx context.Context, s *aheadSlot) {
	// Its context only: the slot stays, ready, for Changed to find.
	defer s.cancel()
	drop := func() {
		rn.ahead.mu.Lock()
		defer rn.ahead.mu.Unlock()
		if rn.ahead.slots[s.id] == s {
			delete(rn.ahead.slots, s.id)
		}
		if rn.ahead.current[s.key] == s {
			delete(rn.ahead.current, s.key)
		}
	}
	k := s.key
	acct, st, c, loc, ok := rn.aheadReady(ctx, k)
	if !ok {
		drop()
		return
	}
	rn.ahead.mu.Lock()
	refs := map[string]bool{}
	for _, t := range c.ReferenceNotes {
		refs[t] = true
	}
	s.refs = refs
	rn.ahead.mu.Unlock()

	// The new note's editor, as it opens: empty, with the caret in the title.
	ed := &aheadEditor{inner: acct.Editors, note: EditorNote{SpaceID: k.space}}
	acct.Editors, acct.OnRun, acct.OnOverlays = ed, nil, nil
	started := rn.now()
	r := queuedRun{ID: s.id, CreatedAt: started.UnixMilli(), Trigger: NoteCreated, SpaceID: k.space, NoteID: aheadNoteID,
		Event: Event{Trigger: NoteCreated, NoteID: aheadNoteID, By: k.user}, Attempts: 1}
	f := rn.attempt(ctx, acct, st, r, started.In(loc), s.run)
	if f.Status != StatusDone {
		if f.Failure == failNotAhead {
			rn.ahead.mu.Lock()
			rn.ahead.cannot[k] = c.UpdatedAt
			rn.ahead.mu.Unlock()
		}
		if ctx.Err() == nil {
			rn.log.Info("agent run ahead", "space", k.space, "status", f.Status, "failure", f.Failure, "message", f.Message)
		}
		drop()
		return
	}
	note, _ := ed.Read(ctx, aheadNoteID, "")
	s.run.mu.Lock()
	overlays := slices.Clone(s.run.overlays)
	s.run.mu.Unlock()
	for i := range overlays {
		overlays[i].NoteID = ""
	}
	if overlays == nil {
		overlays = []Overlay{}
	}
	expires := min(started.Add(AheadFor).UnixMilli(), nextMidnight(started.In(loc)).UnixMilli())
	p := Prepared{ID: s.id, SpaceID: k.space, Title: note.Title, Content: note.Content, Overlays: overlays, ExpiresAt: expires,
		by: k.user, model: f.Model, message: truncate(f.Message, maxMessage), toolCalls: f.ToolCalls,
		prompt: f.PromptTokens, completion: f.CompletionTokens}
	rn.ahead.mu.Lock()
	// Thrown away while it ran: nobody hears of it.
	if rn.ahead.current[k] != s || ctx.Err() != nil {
		rn.ahead.mu.Unlock()
		return
	}
	s.ready = &p
	rn.ahead.mu.Unlock()
	rn.log.Info("agent run ahead", "run", s.id, "space", k.space, "tokens", f.PromptTokens+f.CompletionTokens,
		"took", rn.now().Sub(started))
	rn.tell(k.user, PreparedMessage(p))
}

// aheadReady is what a run done ahead needs, and whether there should be one.
func (rn *Runner) aheadReady(ctx context.Context, k aheadKey) (Account, llm.Settings, Config, *time.Location, bool) {
	var none llm.Settings
	if ok, err := rn.exists(ctx, k.home); err != nil || !ok {
		return Account{}, none, Config{}, nil, false
	}
	st, err := rn.settings(ctx)
	if err != nil {
		return Account{}, none, Config{}, nil, false
	}
	acct, err := rn.open(ctx, k.home)
	if err != nil {
		return Account{}, none, Config{}, nil, false
	}
	c, err := acct.Agent.Config(ctx, k.space)
	if err != nil || !c.Acts(NoteCreated) {
		return Account{}, none, Config{}, nil, false
	}
	rn.ahead.mu.Lock()
	at, cannot := rn.ahead.cannot[k]
	rn.ahead.mu.Unlock()
	if cannot && at == c.UpdatedAt {
		return Account{}, none, Config{}, nil, false
	}
	loc, err := acct.Zone(ctx, k.user)
	if err != nil {
		return Account{}, none, Config{}, nil, false
	}
	return acct, st, c, loc, true
}

func nextMidnight(t time.Time) time.Time {
	y, m, d := t.Date()
	return time.Date(y, m, d+1, 0, 0, 0, 0, t.Location())
}

// UsePrepared records a result done ahead as the run of the new note that opened with it, and tells
// the user's editors. ErrPreparedGone when it isn't there, or isn't this user's for this space.
func (rn *Runner) UsePrepared(ctx context.Context, home Home, id, noteID, spaceID string, userID int64) error {
	a := &rn.ahead
	a.mu.Lock()
	now := rn.now()
	rn.sweepAhead(now)
	s := a.slots[id]
	if s == nil || s.ready == nil || s.key != (aheadKey{home, spaceID, userID}) {
		a.mu.Unlock()
		return ErrPreparedGone
	}
	delete(a.slots, id)
	if a.current[s.key] == s {
		delete(a.current, s.key)
	}
	a.mu.Unlock()
	p := *s.ready
	// Other tabs drop it; the one that used it asks for the next.
	rn.tell(userID, PreparedVoidMessage(spaceID, id))

	acct, err := rn.open(ctx, home)
	if err != nil {
		return err
	}
	outcome, err := acct.Agent.recordPrepared(ctx, p, noteID)
	if err != nil {
		return err
	}
	acct.notify(RunUpdate{RunID: p.ID, NoteID: noteID, Trigger: NoteCreated, Status: StatusDone, Outcome: outcome})
	if len(p.Overlays) > 0 && acct.OnOverlays != nil {
		unlock := acct.Agent.LockOverlays()
		defer unlock()
		list, err := acct.Agent.OpenOverlays(ctx, noteID)
		if err != nil {
			return err
		}
		acct.OnOverlays(noteID, list)
	}
	return nil
}

// Changed throws away the results done ahead in home that c may have made stale, and tells
// their editors. It never blocks.
func (rn *Runner) Changed(home Home, c Change) {
	a := &rn.ahead
	a.mu.Lock()
	var void []*aheadSlot
	for k, s := range a.current {
		if k.home != home || !s.staleBy(c) {
			continue
		}
		delete(a.current, k)
		s.cancel()
		// Going, it's told too: the editor that asked waits for it, and asks again.
		void = append(void, s)
		if s.ready == nil {
			delete(a.slots, s.id)
			continue
		}
		s.usableUntil = rn.now().Add(aheadGrace)
	}
	if c.ConfigOf != "" {
		for k := range a.cannot {
			if k.home == home && k.space == c.ConfigOf {
				delete(a.cannot, k)
			}
		}
	}
	a.mu.Unlock()
	if len(void) == 0 {
		return
	}
	go func() {
		for _, s := range void {
			rn.tell(s.key.user, PreparedVoidMessage(s.key.space, s.id))
		}
	}()
}

// DropAhead throws away every result done ahead: the AI provider changed. It never blocks.
func (rn *Runner) DropAhead() {
	a := &rn.ahead
	a.mu.Lock()
	var void []*aheadSlot
	for k, s := range a.current {
		delete(a.current, k)
		s.cancel()
		void = append(void, s)
	}
	clear(a.slots)
	a.mu.Unlock()
	go func() {
		for _, s := range void {
			rn.tell(s.key.user, PreparedVoidMessage(s.key.space, s.id))
		}
	}()
}

// staleBy reports whether c changes what the slot's run went by. rn.ahead.mu held.
func (s *aheadSlot) staleBy(c Change) bool {
	if c.ConfigOf != "" {
		return c.ConfigOf == s.key.space
	}
	ev := c.Note
	if ev == nil || ev.Unchanged {
		return false
	}
	in := ev.After.SpaceID == s.key.space || (ev.Before != nil && ev.Before.SpaceID == s.key.space)
	if !in {
		return false
	}
	// Still being set up: what it went by isn't known yet, so anything might.
	if s.refs == nil {
		return true
	}
	return s.run.staleBy(ev, s.refs)
}

func (rn *Runner) tell(userID int64, msg any) {
	if rn.tellFn != nil {
		rn.tellFn(userID, msg)
	}
}

// recordPrepared writes a result done ahead down as noteID's run, finished now: its change as
// made in the note's editor (its first save links to it, §7.11), and its overlays.
func (s *Store) recordPrepared(ctx context.Context, p Prepared, noteID string) (outcome string, err error) {
	now := s.now().UnixMilli()
	outcome = OutcomeNoChange
	changed := p.Title != "" || p.Content != ""
	if changed {
		outcome = OutcomeEdited
	}
	ev, err := json.Marshal(Event{Trigger: NoteCreated, NoteID: noteID, By: p.by})
	if err != nil {
		return "", err
	}
	err = s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		space, err := requestSpace(ctx, tx, RunRequest{NoteID: noteID, SpaceID: p.SpaceID})
		if err != nil {
			return err
		}
		if space != p.SpaceID {
			return ErrNoteGone
		}
		c, err := configIn(ctx, tx, space)
		switch {
		case err != nil:
			return err
		case !c.Acts(NoteCreated):
			return ErrTriggerOff
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO agent_runs (id, status, trigger, space_id, note_id, priority, event_json,
				created_at, started_at, finished_at, attempts, model, tool_calls, prompt_tokens, completion_tokens, outcome, message)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)`,
			p.ID, StatusDone, string(NoteCreated), space, noteID, editorPriority, string(ev), now, now, now,
			p.model, p.toolCalls, p.prompt, p.completion, outcome, p.message); err != nil {
			return err
		}
		if changed {
			if err := recordEditorEditTx(ctx, tx, p.ID, noteID, nil, Fields{}, Fields{p.Title, p.Content}, now); err != nil {
				return err
			}
			// Saved already, ahead of this: that save was the change.
			if _, err := tx.ExecContext(ctx, `UPDATE agent_run_edits SET after_revision =
				(SELECT revision FROM notes WHERE id = ? AND title = ? AND content = ? AND deleted_at IS NULL)
				WHERE run_id = ?`, noteID, p.Title, p.Content, p.ID); err != nil {
				return err
			}
		}
		return insertOverlaysTx(ctx, tx, p.ID, noteID, p.Overlays, now)
	})
	return outcome, err
}
