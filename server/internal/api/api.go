// Package api is the HTTP layer: routing, authentication, CSRF protection,
// request decoding and error mapping for the /api/v1 JSON API (§9), plus the
// health endpoints and the embedded web app.
package api

import (
	"compress/gzip"
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"notes/server/internal/agent"
	"notes/server/internal/auth"
	"notes/server/internal/clientip"
	"notes/server/internal/database"
	"notes/server/internal/editors"
	"notes/server/internal/llm"
	"notes/server/internal/mail"
	"notes/server/internal/notes"
	"notes/server/internal/publish"
	"notes/server/internal/sharing"
	"notes/server/internal/userdata"
	"notes/server/internal/web"
)

// Ahead is the agent runner's side of results done ahead (agent.Runner).
type Ahead interface {
	Prepare(home agent.Home, spaceID string, userID int64)
	UsePrepared(ctx context.Context, home agent.Home, id, noteID, spaceID string, userID int64) error
}

// Config holds HTTP-layer settings.
type Config struct {
	// ResendURL overrides Resend's API base URL (tests).
	ResendURL string
	// SocketPing overrides how often the agent socket pings (tests).
	SocketPing time.Duration
	// OnAIChanged is called after the AI settings are saved or removed, so
	// agents waiting for them resume or pause.
	OnAIChanged func()
	// Ahead does spaces' "new note opened" runs ahead, for the agent socket; nil does none.
	Ahead Ahead
	// Editors are the connected note editors, shared with the agent runner;
	// nil gets a hub of the server's own.
	Editors *editors.Hub
	// Version is the server's build version (v1.2.3), sent on every
	// response; empty is a development build, sent as "dev".
	Version string
	// UpdateRequest is the file an admin's update request leaves for the
	// notesd-update service; empty when the server can't update itself.
	UpdateRequest string
	// ReleasesURL overrides where releases are listed (tests).
	ReleasesURL string
	// DataDir holds the notes files and, in public/, the published spaces' sites (§7.10).
	DataDir string
	// Publish paces re-rendering published sites; zero values are the defaults.
	Publish publish.Config
	// ClientIPHeader is the header a proxy sets to the client's address;
	// empty reads X-Forwarded-For.
	ClientIPHeader string
	// MinClient is the oldest app version, X.Y.Z, this server syncs with,
	// by platform (android, ios, web). Raised only when a server change
	// would break older apps; empty means every version is accepted.
	MinClient map[string]string
}

// Server serves the API and web app.
type Server struct {
	system   *database.DB // users, sessions and server-wide settings
	users    *userdata.Registry
	auth     *auth.Service
	limiter  *auth.Limiter
	mailer   *mail.Client
	ai       *llm.Client
	log      *slog.Logger
	cfg      Config
	releases *releases
	sharing  *sharing.Service
	publish  *publish.Service
	editors  *editors.Hub
}

// New returns a server over the system database and users' notes databases.
func New(system *database.DB, users *userdata.Registry, authSvc *auth.Service, log *slog.Logger, cfg Config) *Server {
	share := sharing.New(system, users)
	share.Install()
	users.OnOpen(func(userID int64, d *userdata.Data) {
		d.Spaces.AddTrait("agent", agent.Trait(agentConfigsFor(userID, d, share, users),
			func(ctx context.Context) (llm.Settings, error) { return llm.Load(ctx, system) }))
	}, nil)
	pcfg := cfg.Publish
	pcfg.DataDir, pcfg.Log = cfg.DataDir, log
	pub := publish.New(system, users, share, pcfg)
	pub.Install()
	authSvc.BeforeRemove(RemoveAccount)
	hub := cfg.Editors
	if hub == nil {
		hub = editors.NewHub()
	}
	return &Server{
		system: system, users: users, auth: authSvc, log: log, cfg: cfg,
		limiter:  auth.NewLimiter(10, 15*time.Minute),
		mailer:   mail.NewClient(cfg.ResendURL),
		ai:       llm.NewClient(),
		releases: newReleases(cfg.ReleasesURL),
		sharing:  share,
		publish:  pub,
		editors:  hub,
	}
}

// RemoveAccount is what removing an account changes elsewhere in system.db: the shared spaces it owns
// pass to a member, and its own spaces' sites come down.
func RemoveAccount(ctx context.Context, tx *sql.Tx, userID int64) error {
	if err := sharing.HandOff(ctx, tx, userID); err != nil {
		return err
	}
	return publish.RemoveAccount(ctx, tx, userID)
}

// Publish is the server's published spaces: Run keeps their sites up to date.
func (s *Server) Publish() *publish.Service { return s.publish }

// Recover finishes or undoes moves of notes between files that a stop cut
// short (§7.9). Run it before serving.
func (s *Server) Recover(ctx context.Context) error { return s.sharing.Recover(ctx) }

// Sharing is the server's shared spaces, for hooks that follow them.
func (s *Server) Sharing() *sharing.Service { return s.sharing }

// Handler returns the root HTTP handler.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", s.healthz)
	mux.HandleFunc("GET /readyz", s.readyz)

	// Public auth endpoints.
	mux.HandleFunc("GET /api/v1/auth/status", s.authStatus)
	mux.HandleFunc("POST /api/v1/auth/setup", s.setup)
	mux.HandleFunc("POST /api/v1/auth/login", s.login)
	mux.HandleFunc("POST /api/v1/auth/refresh", s.refresh)
	mux.HandleFunc("POST /api/v1/auth/setup-link", s.peekSetupLink)
	mux.HandleFunc("POST /api/v1/auth/setup-link/use", s.useSetupLink)

	// Authenticated endpoints.
	mux.Handle("POST /api/v1/auth/logout", s.authed(s.logout))
	mux.Handle("GET /api/v1/auth/session", s.authed(s.currentSession))
	mux.Handle("POST /api/v1/auth/password", s.authed(s.changePassword))
	mux.Handle("GET /api/v1/auth/sessions", s.authed(s.listSessions))
	mux.Handle("DELETE /api/v1/auth/sessions/{id}", s.authed(s.revokeSession))

	mux.Handle("GET /api/v1/notes", gzipped(s.authed(s.bySpace(s.listNotes))))
	mux.Handle("POST /api/v1/notes", s.authed(s.createNote))
	mux.Handle("GET /api/v1/notes/{id}", s.authed(s.getNote))
	mux.Handle("PUT /api/v1/notes/{id}", s.authed(s.updateNote))
	mux.Handle("DELETE /api/v1/notes/{id}", s.authed(s.deleteNote))
	mux.Handle("POST /api/v1/notes/{id}/email", s.authed(s.atNote(s.emailNote)))

	mux.Handle("GET /api/v1/update", s.authed(s.updateStatus))
	mux.Handle("POST /api/v1/update", s.authed(adminOnly(s.requestUpdate)))

	mux.Handle("GET /api/v1/users", s.authed(adminOnly(s.listUsers)))
	mux.Handle("POST /api/v1/users", s.authed(adminOnly(s.inviteUser)))
	mux.Handle("POST /api/v1/users/{id}/link", s.authed(adminOnly(s.newSetupLink)))
	mux.Handle("PATCH /api/v1/users/{id}", s.authed(adminOnly(s.updateUser)))
	mux.Handle("DELETE /api/v1/users/{id}", s.authed(adminOnly(s.removeUser)))
	mux.Handle("GET /api/v1/users/{id}/avatar", s.authed(s.getAvatar))

	mux.Handle("PATCH /api/v1/profile", s.authed(s.updateProfile))
	mux.Handle("PUT /api/v1/profile/avatar", s.authed(s.putAvatar))
	mux.Handle("DELETE /api/v1/profile/avatar", s.authed(s.deleteAvatar))

	mux.Handle("GET /api/v1/settings/email", s.authed(s.emailSettings))
	mux.Handle("PUT /api/v1/settings/email", s.authed(adminOnly(s.saveEmailSettings)))
	mux.Handle("DELETE /api/v1/settings/email", s.authed(adminOnly(s.deleteEmailSettings)))
	mux.Handle("GET /api/v1/settings/account", s.authed(s.accountSettings))
	mux.Handle("PUT /api/v1/settings/account", s.authed(s.saveAccountSettings))
	mux.Handle("GET /api/v1/agent/spaces", s.authed(s.agentConfigs))
	mux.Handle("GET /api/v1/agent/spaces/{id}", s.authed(s.agentConfig))
	mux.Handle("PUT /api/v1/agent/spaces/{id}", s.authed(s.putAgentConfig))
	mux.Handle("DELETE /api/v1/agent/spaces/{id}", s.authed(s.deleteAgentConfig))
	mux.Handle("GET /api/v1/agent/runs", s.authed(s.agentRuns))
	mux.Handle("GET /api/v1/agent/usage", s.authed(s.agentUsage))
	mux.Handle("GET /api/v1/agent/runs/{id}", s.authed(s.agentRun))
	mux.Handle("POST /api/v1/agent/runs/{id}/undo", s.authed(s.undoAgentRun))
	mux.Handle("GET /api/v1/agent/socket", s.authed(s.agentSocket))
	mux.Handle("GET /api/v1/settings/ai", s.authed(s.aiSettings))
	mux.Handle("PUT /api/v1/settings/ai", s.authed(adminOnly(s.saveAISettings)))
	mux.Handle("DELETE /api/v1/settings/ai", s.authed(adminOnly(s.deleteAISettings)))
	mux.Handle("POST /api/v1/settings/ai/test", s.authed(adminOnly(s.testAISettings)))
	mux.Handle("POST /api/v1/settings/ai/models", s.authed(adminOnly(s.listAIModels)))

	mux.Handle("GET /api/v1/locks", s.authed(s.listLockKeys))
	mux.Handle("DELETE /api/v1/locks", s.authed(s.resetLocks))
	mux.Handle("PUT /api/v1/locks/keys/{id}", s.authed(s.putLockKey))
	mux.Handle("DELETE /api/v1/locks/keys/{id}", s.authed(s.deleteLockKey))

	mux.Handle("GET /api/v1/spaces", s.authed(s.listSpaces))
	mux.Handle("PUT /api/v1/spaces/{id}", s.authed(s.putSpace))
	mux.Handle("DELETE /api/v1/spaces/{id}", s.authed(s.deleteSpace))
	mux.Handle("POST /api/v1/spaces/{id}/invites", s.authed(s.invite))
	mux.Handle("DELETE /api/v1/spaces/{id}/invites/{invite}", s.authed(s.cancelInvite))
	mux.Handle("DELETE /api/v1/spaces/{id}/members/{user}", s.authed(s.removeMember))
	mux.Handle("PUT /api/v1/spaces/{id}/owner", s.authed(s.transferSpace))
	mux.Handle("GET /api/v1/invites", s.authed(s.listInvites))
	mux.Handle("POST /api/v1/invites/{invite}/accept", s.authed(s.acceptInvite))
	mux.Handle("POST /api/v1/invites/{invite}/decline", s.authed(s.declineInvite))
	mux.Handle("GET /api/v1/people", s.authed(s.listPeople))
	mux.Handle("GET /api/v1/spaces/{id}/publish", s.authed(s.getPublish))
	mux.Handle("PUT /api/v1/spaces/{id}/publish", s.authed(s.putPublish))
	mux.Handle("DELETE /api/v1/spaces/{id}/publish", s.authed(s.deletePublish))

	// A shared space's change feed (§7.9): each file has its own sequence, so its own cursor. Every
	// other request is at the account's address, and the server finds the file.
	mux.Handle("GET /api/v1/spaces/{space}/sync", gzipped(s.authed(s.inSpace(s.sync))))

	mux.Handle("PUT /api/v1/images/{id}", s.authed(s.putImage))
	mux.Handle("GET /api/v1/images/{id}", s.authed(s.getImage))

	mux.Handle("GET /api/v1/sync", gzipped(s.authed(s.sync)))
	mux.Handle("GET /api/v1/search", gzipped(s.authed(s.bySpace(s.search))))
	mux.Handle("GET /api/v1/filters", gzipped(s.authed(s.bySpace(s.listFilters))))

	// Not not_found: a client treats that as a missing note or image, which
	// an older server lacking the endpoint isn't.
	mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, http.StatusNotFound, "no_such_endpoint", "no such endpoint")
	})
	app := web.Handler()
	mux.Handle("/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if publish.IsPublic(r) {
			s.publish.ServeHTTP(w, r)
			return
		}
		app.ServeHTTP(w, r)
	}))

	return clientip.Middleware(s.cfg.ClientIPHeader,
		s.serverHeader(s.recoverer(s.logRequests(s.publish.ServeDomain(s.checkClient(mux))))))
}

// serverHeader names this server's version on every response, so the web
// app can tell it was updated underneath it.
func (s *Server) serverHeader(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Notesd-Server", s.version())
		next.ServeHTTP(w, r)
	})
}

// ---- health ----

func (s *Server) healthz(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *Server) readyz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.system.Ping(ctx); err != nil {
		s.log.Warn("readiness check failed", "error", err)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "unavailable"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
}

// ---- authentication middleware ----

type ctxKey int

const (
	sessionKey ctxKey = iota
	userKey
	spaceKey
)

// inSpace has a handler behind authed serve a shared space's file instead of
// the account's, once the account is found to be a member.
func (s *Server) inSpace(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		space := r.PathValue("space")
		d, release, err := s.sharing.Open(r.Context(), sessionFrom(r.Context()).UserID, space)
		if err != nil {
			s.writeErr(w, r, err)
			return
		}
		defer release()
		ctx := context.WithValue(context.WithValue(r.Context(), userKey, d), spaceKey, space)
		h(w, r.WithContext(ctx))
	}
}

// bySpace serves a request naming a shared space the account is in (?space=) from that space's file.
func (s *Server) bySpace(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		space := r.URL.Query().Get("space")
		if space == "" {
			h(w, r)
			return
		}
		f, release, err := s.sharing.ForSpace(r.Context(), sessionFrom(r.Context()).UserID, space)
		if err != nil {
			s.writeErr(w, r, err)
			return
		}
		defer release()
		h(w, r.WithContext(inFile(r.Context(), f)))
	}
}

// atNote serves a request for a note from the file it's in.
func (s *Server) atNote(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		f, _, release, err := s.sharing.Locate(r.Context(), sessionFrom(r.Context()).UserID, r.PathValue("id"))
		if errors.Is(err, notes.ErrNotFound) {
			h(w, r)
			return
		} else if err != nil {
			s.writeErr(w, r, err)
			return
		}
		defer release()
		h(w, r.WithContext(inFile(r.Context(), f)))
	}
}

func inFile(ctx context.Context, f sharing.File) context.Context {
	return context.WithValue(context.WithValue(ctx, userKey, f.Data), spaceKey, f.SpaceID)
}

// spaceFrom is the shared space whose file a request is served from; "" for
// the account's own.
func spaceFrom(ctx context.Context) string {
	id, _ := ctx.Value(spaceKey).(string)
	return id
}

func sessionFrom(ctx context.Context) auth.Session {
	sess, _ := ctx.Value(sessionKey).(auth.Session)
	return sess
}

// userFrom is the signed-in user's notes database. Every handler behind
// authed reads and writes that user's data through it, and nothing else.
func userFrom(ctx context.Context) *userdata.Data {
	d, _ := ctx.Value(userKey).(*userdata.Data)
	return d
}

// noteTimeZone keeps the device's time zone for the account if it has none
// yet. Best effort: a request never fails over the time zone.
func (s *Server) noteTimeZone(r *http.Request, data *userdata.Data) {
	tz := r.Header.Get("X-Notesd-Time-Zone")
	if tz == "" {
		return
	}
	if err := data.Agent.NoteDeviceTimeZone(r.Context(), tz); err != nil {
		s.log.WarnContext(r.Context(), "record time zone", "error", err)
	}
}

// authed requires a valid session, and opens the session's user's data. Devices authenticate with
// "Authorization: Bearer <access token>". Browsers use the session cookie
// and must send X-CSRF-Token on every non-GET request; a cross-origin
// Origin header is rejected as well.
func (s *Server) authed(h http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var sess auth.Session
		var err error
		if token, ok := bearerToken(r); ok {
			sess, err = s.auth.Authenticate(r.Context(), token, auth.KindDevice)
		} else if tokens := sessionCookies(r); len(tokens) > 0 {
			var token string
			for _, token = range tokens {
				if sess, err = s.auth.Authenticate(r.Context(), token, auth.KindWeb); err == nil {
					break
				}
			}
			if err == nil && sess.Renewed {
				setSessionCookie(w, r, token, time.UnixMilli(sess.ExpiresAt))
			}
			if err == nil && !safeMethod(r.Method) {
				if !sameOrigin(r) {
					writeError(w, http.StatusForbidden, "csrf_failed", "cross-origin request rejected")
					return
				}
				if !auth.ConstantTimeEqual(r.Header.Get("X-CSRF-Token"), sess.CSRFToken) {
					writeError(w, http.StatusForbidden, "csrf_failed", "missing or invalid X-CSRF-Token header")
					return
				}
			}
		} else {
			err = auth.ErrUnauthenticated
		}
		if err != nil {
			s.writeErr(w, r, err)
			return
		}
		data, err := s.users.Open(r.Context(), sess.UserID)
		if err != nil {
			s.writeErr(w, r, err)
			return
		}
		s.noteTimeZone(r, data)
		ctx := context.WithValue(context.WithValue(r.Context(), sessionKey, sess), userKey, data)
		h(w, r.WithContext(ctx))
	})
}

// adminOnly limits a handler behind authed to admins: server-wide settings
// and accounts concern every user.
func adminOnly(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !sessionFrom(r.Context()).IsAdmin {
			writeError(w, http.StatusForbidden, "admin_only", "only an admin can do this")
			return
		}
		h(w, r)
	}
}

func bearerToken(r *http.Request) (string, bool) {
	h := r.Header.Get("Authorization")
	scheme, token, ok := strings.Cut(h, " ")
	if !ok || !strings.EqualFold(scheme, "Bearer") {
		return "", false
	}
	return strings.TrimSpace(token), true
}

func safeMethod(m string) bool {
	return m == http.MethodGet || m == http.MethodHead || m == http.MethodOptions
}

// sameOrigin accepts requests without an Origin header (non-browser clients
// and some same-origin requests) and those whose Origin host matches the
// request host, or X-Forwarded-Host for proxies that rewrite Host. Trusting
// that header is safe here: a cross-site page can't add it to a victim's
// request without a CORS preflight, which this server never grants.
func sameOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	u, err := url.Parse(origin)
	if err != nil || u.Host == "" {
		return false
	}
	if strings.EqualFold(u.Host, r.Host) {
		return true
	}
	fwd, _, _ := strings.Cut(r.Header.Get("X-Forwarded-Host"), ",")
	fwd = strings.TrimSpace(fwd)
	return fwd != "" && strings.EqualFold(u.Host, fwd)
}

// secureRequest reports whether the browser reached this server over HTTPS,
// directly or through a proxy. A client faking X-Forwarded-Proto only gets a
// Secure cookie its own browser won't keep over plain HTTP.
func secureRequest(r *http.Request) bool {
	if r.TLS != nil {
		return true
	}
	proto, _, _ := strings.Cut(r.Header.Get("X-Forwarded-Proto"), ",")
	return strings.EqualFold(strings.TrimSpace(proto), "https")
}

// Over HTTPS the cookie is Secure with the __Host- prefix, which pins it to
// this exact host and path "/".
const (
	secureCookieName = "__Host-notes_session"
	plainCookieName  = "notes_session"
)

func cookieName(r *http.Request) string {
	if secureRequest(r) {
		return secureCookieName
	}
	return plainCookieName
}

// sessionCookies returns the tokens under either name, each to be tried: a
// browser can hold both when the forwarded scheme changes between requests
// (a proxy that stopped sending it), and the one under the other name may be
// stale, such as a session signed out since. The name this request would set
// comes first: when both are valid, it's the newer sign-in, whose CSRF token
// the web app holds.
func sessionCookies(r *http.Request) []string {
	names := []string{secureCookieName, plainCookieName}
	if !secureRequest(r) {
		names[0], names[1] = names[1], names[0]
	}
	var tokens []string
	for _, name := range names {
		if c, err := r.Cookie(name); err == nil && c.Value != "" {
			tokens = append(tokens, c.Value)
		}
	}
	return tokens
}

// ---- logging and recovery ----

type statusWriter struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (w *statusWriter) WriteHeader(code int) {
	if w.status == 0 {
		w.status = code
	}
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	n, err := w.ResponseWriter.Write(b)
	w.bytes += n
	return n, err
}

func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

// logRequests logs one line per API request. Query strings are omitted
// because they contain search terms.
func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w}
		next.ServeHTTP(sw, r)
		if !strings.HasPrefix(r.URL.Path, "/api/") {
			return
		}
		level := slog.LevelInfo
		if sw.status >= 500 {
			level = slog.LevelError
		}
		client := "-"
		if platform, version, ok := requestClient(r); ok {
			client = platform + "/" + version
		}
		s.log.Log(r.Context(), level, "request",
			"method", r.Method, "path", r.URL.Path, "status", sw.status,
			"bytes", sw.bytes, "duration_ms", time.Since(start).Milliseconds(), "client", client)
	})
}

func (s *Server) recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if p := recover(); p != nil {
				if p == http.ErrAbortHandler { //nolint:errorlint // recover() value, never wrapped
					panic(p)
				}
				s.log.Error("panic serving request", "path", r.URL.Path, "panic", p, "stack", string(debug.Stack()))
				writeError(w, http.StatusInternalServerError, "internal_error", "internal server error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

// ---- compression ----

var gzipPool = sync.Pool{New: func() any {
	zw, _ := gzip.NewWriterLevel(nil, gzip.BestSpeed)
	return zw
}}

type gzipWriter struct {
	http.ResponseWriter
	zw *gzip.Writer
}

func (g *gzipWriter) WriteHeader(code int) {
	g.Header().Del("Content-Length")
	g.ResponseWriter.WriteHeader(code)
}

func (g *gzipWriter) Write(b []byte) (int, error) { return g.zw.Write(b) }

// gzipped compresses responses for clients that accept gzip. It is applied
// to endpoints whose responses can be large (sync pages, lists, search).
func gzipped(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Add("Vary", "Accept-Encoding")
		if !acceptsGzip(r.Header.Get("Accept-Encoding")) {
			next.ServeHTTP(w, r)
			return
		}
		zw := gzipPool.Get().(*gzip.Writer)
		zw.Reset(w)
		defer func() {
			_ = zw.Close()
			gzipPool.Put(zw)
		}()
		w.Header().Set("Content-Encoding", "gzip")
		next.ServeHTTP(&gzipWriter{ResponseWriter: w, zw: zw}, r)
	})
}

func acceptsGzip(h string) bool {
	for _, part := range strings.Split(h, ",") {
		enc, params, _ := strings.Cut(strings.TrimSpace(part), ";")
		if !strings.EqualFold(strings.TrimSpace(enc), "gzip") {
			continue
		}
		for _, p := range strings.Split(params, ";") {
			k, v, _ := strings.Cut(strings.TrimSpace(p), "=")
			if strings.EqualFold(k, "q") {
				q, err := strconv.ParseFloat(v, 64)
				return err == nil && q > 0
			}
		}
		return true
	}
	return false
}
