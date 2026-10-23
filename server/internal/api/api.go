// Package api is the HTTP layer: routing, authentication, CSRF protection,
// request decoding and error mapping for the /api/v1 JSON API (§9), plus the
// health endpoints and the embedded web app.
package api

import (
	"compress/gzip"
	"context"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"notes/server/internal/auth"
	"notes/server/internal/database"
	"notes/server/internal/mail"
	"notes/server/internal/userdata"
	"notes/server/internal/web"
)

// Config holds HTTP-layer settings.
type Config struct {
	// PublicURL is the externally visible base URL, if known. An https URL
	// makes cookies Secure; its host is accepted as an Origin.
	PublicURL *url.URL
	// ResendURL overrides Resend's API base URL (tests).
	ResendURL string
}

// Server serves the API and web app.
type Server struct {
	system  *database.DB // users, sessions and server-wide settings
	users   *userdata.Registry
	auth    *auth.Service
	limiter *auth.Limiter
	mailer  *mail.Client
	log     *slog.Logger
	cfg     Config
}

// New returns a server over the system database and users' notes databases.
func New(system *database.DB, users *userdata.Registry, authSvc *auth.Service, log *slog.Logger, cfg Config) *Server {
	return &Server{
		system: system, users: users, auth: authSvc, log: log, cfg: cfg,
		limiter: auth.NewLimiter(10, 15*time.Minute),
		mailer:  mail.NewClient(cfg.ResendURL),
	}
}

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

	// Authenticated endpoints.
	mux.Handle("POST /api/v1/auth/logout", s.authed(s.logout))
	mux.Handle("GET /api/v1/auth/session", s.authed(s.currentSession))
	mux.Handle("POST /api/v1/auth/password", s.authed(s.changePassword))
	mux.Handle("GET /api/v1/auth/sessions", s.authed(s.listSessions))
	mux.Handle("DELETE /api/v1/auth/sessions/{id}", s.authed(s.revokeSession))

	mux.Handle("GET /api/v1/notes", gzipped(s.authed(s.listNotes)))
	mux.Handle("POST /api/v1/notes", s.authed(s.createNote))
	mux.Handle("GET /api/v1/notes/{id}", s.authed(s.getNote))
	mux.Handle("PUT /api/v1/notes/{id}", s.authed(s.updateNote))
	mux.Handle("DELETE /api/v1/notes/{id}", s.authed(s.deleteNote))
	mux.Handle("POST /api/v1/notes/{id}/email", s.authed(s.emailNote))

	mux.Handle("GET /api/v1/settings/email", s.authed(s.emailSettings))
	mux.Handle("PUT /api/v1/settings/email", s.authed(adminOnly(s.saveEmailSettings)))
	mux.Handle("DELETE /api/v1/settings/email", s.authed(adminOnly(s.deleteEmailSettings)))

	mux.Handle("GET /api/v1/locks", s.authed(s.listLockKeys))
	mux.Handle("DELETE /api/v1/locks", s.authed(s.resetLocks))
	mux.Handle("PUT /api/v1/locks/keys/{id}", s.authed(s.putLockKey))
	mux.Handle("DELETE /api/v1/locks/keys/{id}", s.authed(s.deleteLockKey))

	mux.Handle("GET /api/v1/spaces", s.authed(s.listSpaces))
	mux.Handle("PUT /api/v1/spaces/{id}", s.authed(s.putSpace))
	mux.Handle("DELETE /api/v1/spaces/{id}", s.authed(s.deleteSpace))

	mux.Handle("PUT /api/v1/images/{id}", s.authed(s.putImage))
	mux.Handle("GET /api/v1/images/{id}", s.authed(s.getImage))

	mux.Handle("GET /api/v1/sync", gzipped(s.authed(s.sync)))
	mux.Handle("GET /api/v1/search", gzipped(s.authed(s.search)))
	mux.Handle("GET /api/v1/filters", gzipped(s.authed(s.listFilters)))

	mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) {
		writeError(w, http.StatusNotFound, "not_found", "no such endpoint")
	})
	mux.Handle("/", web.Handler())

	return s.recoverer(s.logRequests(mux))
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
)

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
		} else if c, cerr := r.Cookie(s.cookieName()); cerr == nil {
			sess, err = s.auth.Authenticate(r.Context(), c.Value, auth.KindWeb)
			if err == nil && sess.Renewed {
				s.setSessionCookie(w, c.Value, time.UnixMilli(sess.ExpiresAt))
			}
			if err == nil && !safeMethod(r.Method) {
				if !s.sameOrigin(r) {
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
		ctx := context.WithValue(context.WithValue(r.Context(), sessionKey, sess), userKey, data)
		h(w, r.WithContext(ctx))
	})
}

// adminOnly limits a handler behind authed to admins: server-wide settings
// apply to every user.
func adminOnly(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !sessionFrom(r.Context()).IsAdmin {
			writeError(w, http.StatusForbidden, "admin_only", "only an admin can change this setting")
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
// request host or the configured public URL.
func (s *Server) sameOrigin(r *http.Request) bool {
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
	return s.cfg.PublicURL != nil && strings.EqualFold(u.Host, s.cfg.PublicURL.Host)
}

func (s *Server) secureCookies() bool {
	return s.cfg.PublicURL != nil && s.cfg.PublicURL.Scheme == "https"
}

// cookieName uses the __Host- prefix when cookies are Secure, which pins the
// cookie to this exact host and path "/".
func (s *Server) cookieName() string {
	if s.secureCookies() {
		return "__Host-notes_session"
	}
	return "notes_session"
}

func clientKey(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
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
		s.log.Log(r.Context(), level, "request",
			"method", r.Method, "path", r.URL.Path, "status", sw.status,
			"bytes", sw.bytes, "duration_ms", time.Since(start).Milliseconds())
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
