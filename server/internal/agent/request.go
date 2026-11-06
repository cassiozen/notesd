package agent

import (
	"context"
	"database/sql"
	"errors"
	"regexp"
	"sync"
	"time"

	"notes/server/internal/locks"
)

// Why an editor's request for a run was refused.
var (
	ErrAgentOff   = errors.New("the space's agent is off")
	ErrTriggerOff = errors.New("the space's agent doesn't act on this")
	ErrNoteLocked = errors.New("the note is locked")
	ErrNoteGone   = errors.New("no such note")
)

// A draft's id is queued before the store has checked it.
var noteIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

// RunRequest is an editor asking for a run: a new note opened, a note
// started being edited, or the menu command.
type RunRequest struct {
	Trigger Trigger
	NoteID  string
	// SpaceID is the note's space as the editor shows it.
	SpaceID      string
	ConnectionID string
	// Note is what the editor showed as it asked, if it said: the run
	// starts from it rather than asking the editor again.
	Note *EditorNote
}

// RequestRun queues a run an editor asked for, ahead of the others. Asked
// again before it starts, it's the same run.
func (s *Store) RequestRun(ctx context.Context, req RunRequest) (string, error) {
	if !req.Trigger.InEditor() {
		return "", &InputError{Field: "trigger", Message: "must be noteCreated, editStarted or menu"}
	}
	if !noteIDPattern.MatchString(req.NoteID) {
		return "", &InputError{Field: "noteId", Message: "must be 1-64 characters of [A-Za-z0-9_-]"}
	}
	var id string
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		space, err := requestSpace(ctx, tx, req)
		if err != nil {
			return err
		}
		c, err := configIn(ctx, tx, space)
		switch {
		case err != nil:
			return err
		case !c.Enabled:
			return ErrAgentOff
		case !c.Acts(req.Trigger):
			return ErrTriggerOff
		}
		err = tx.QueryRowContext(ctx, `SELECT id FROM agent_runs WHERE note_id = ? AND trigger = ? AND status = 'queued'`,
			req.NoteID, string(req.Trigger)).Scan(&id)
		if !errors.Is(err, sql.ErrNoRows) {
			if err == nil && req.Note != nil {
				s.snapshots.keep(id, *req.Note, s.now()) // asked again before it started: the newer one
			}
			return err
		}
		if id, err = queue(ctx, tx, space, Event{Trigger: req.Trigger, NoteID: req.NoteID, ConnectionID: req.ConnectionID},
			editorPriority, s.now().UnixMilli()); err != nil {
			return err
		}
		// Before the runner can take it: scheduled wakes it.
		if req.Note != nil {
			s.snapshots.keep(id, *req.Note, s.now())
		}
		s.scheduled()
		return nil
	})
	return id, err
}

// snapshotFresh is how long what an editor showed as it asked stands for
// what it shows: past it, the user has likely typed on, and the run asks
// the editor again.
const snapshotFresh = 5 * time.Second

// snapshots are what editors showed as they asked for runs, by run, kept
// in memory until each run starts: a server restarted since asks again.
type snapshots struct {
	mu sync.Mutex
	m  map[string]snapshot
}

type snapshot struct {
	note EditorNote
	at   time.Time
}

func (s *snapshots) keep(runID string, n EditorNote, now time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.m == nil {
		s.m = map[string]snapshot{}
	}
	for id, sn := range s.m {
		if now.Sub(sn.at) > snapshotFresh {
			delete(s.m, id)
		}
	}
	s.m[runID] = snapshot{n, now}
}

// take returns run's snapshot, if it's fresh, and forgets it.
func (s *snapshots) take(runID string, now time.Time) (EditorNote, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	sn, ok := s.m[runID]
	delete(s.m, runID)
	return sn.note, ok && now.Sub(sn.at) <= snapshotFresh
}

// requestSpace is the space the editor says the note is in: the store may
// not have the note yet, or not its latest move. A copy the store has
// deleted or locked refuses; with no space named, the store's is used.
func requestSpace(ctx context.Context, tx *sql.Tx, req RunRequest) (string, error) {
	var space, content string
	var deleted sql.NullInt64
	err := tx.QueryRowContext(ctx, `SELECT space_id, content, deleted_at FROM notes WHERE id = ?`, req.NoteID).
		Scan(&space, &content, &deleted)
	switch {
	case err != nil && !errors.Is(err, sql.ErrNoRows):
		return "", err
	case err == nil && deleted.Valid:
		return "", ErrNoteGone
	case err == nil && locks.IsLocked(content):
		return "", ErrNoteLocked
	case req.SpaceID != "":
		return req.SpaceID, nil
	case err == nil:
		return space, nil
	}
	return "", ErrNoteGone
}
