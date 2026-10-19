// Command notesd is the self-hosted notes backend: the JSON API, the
// sync endpoint, full-text search and the embedded web app, in one process
// backed by one SQLite database.
//
// Usage:
//
//	notesd [serve] [flags]          run the server (default)
//	notesd create-owner --username NAME
//	notesd reset-password
//	notesd reindex                  rebuild the search index
//	notesd check                    run an integrity check
//	notesd gc-images [--dry-run]    delete images no note has shown for 30 days
//	notesd export --out DIR [--include-locked]
//	                                write every note to DIR as Markdown
//	notesd version
//
// Flags may also be set through environment variables: NOTESD_DATA_DIR,
// NOTESD_LISTEN, NOTESD_PUBLIC_URL, NOTESD_LOG_LEVEL, NOTESD_LOG_FORMAT,
// NOTESD_RESEND_URL. serve creates the owner account from NOTESD_OWNER_USERNAME and
// NOTESD_OWNER_PASSWORD when there is none yet; otherwise the first person to
// open the web app creates it. Commands that take a password read it from
// NOTESD_PASSWORD or, failing that, from the first line of standard input.
// export --include-locked asks for the lock password at the terminal, every time.
package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"notes/server/internal/api"
	"notes/server/internal/auth"
	"notes/server/internal/database"
	"notes/server/internal/images"
	"notes/server/internal/locks"
	"notes/server/internal/notes"
	"notes/server/internal/search"
)

// version is set at build time: -ldflags "-X main.version=v1.2.3".
var version = "dev"

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "notesd:", err)
		os.Exit(1)
	}
}

type options struct {
	dataDir   string
	listen    string
	publicURL string
	logLevel  string
	logFormat string
	resendURL string
	username  string
	out       string
	locked    bool
	dryRun    bool
}

func run(args []string) error {
	cmd := "serve"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		cmd, args = args[0], args[1:]
	}

	var o options
	fs := flag.NewFlagSet("notesd "+cmd, flag.ContinueOnError)
	fs.StringVar(&o.dataDir, "data-dir", env("NOTESD_DATA_DIR", "./data"), "directory holding notes.db")
	switch cmd {
	case "serve":
		fs.StringVar(&o.listen, "listen", env("NOTESD_LISTEN", "127.0.0.1:8080"), "address to listen on")
		fs.StringVar(&o.publicURL, "public-url", env("NOTESD_PUBLIC_URL", ""), "externally visible URL, e.g. https://notes.example.com (enables Secure cookies when https)")
		fs.StringVar(&o.logLevel, "log-level", env("NOTESD_LOG_LEVEL", "info"), "debug, info, warn or error")
		fs.StringVar(&o.logFormat, "log-format", env("NOTESD_LOG_FORMAT", "text"), "text or json")
		fs.StringVar(&o.resendURL, "resend-url", env("NOTESD_RESEND_URL", ""), "Resend API base URL for emailing notes (default https://api.resend.com)")
	case "create-owner":
		fs.StringVar(&o.username, "username", "", "owner username")
	case "export":
		fs.StringVar(&o.out, "out", "", "directory to write the Markdown files to (new or empty)")
		fs.BoolVar(&o.locked, "include-locked", false, "decrypt locked notes with the lock password and export them as plain text")
	case "gc-images":
		fs.BoolVar(&o.dryRun, "dry-run", false, "only report what would be deleted")
	case "reset-password", "reindex", "check":
	case "version":
		fmt.Println("notesd", version)
		return nil
	case "help", "-h", "--help":
		fmt.Fprintln(os.Stderr, "commands: serve, create-owner, reset-password, reindex, check, gc-images, export, version")
		return nil
	default:
		return fmt.Errorf("unknown command %q (commands: serve, create-owner, reset-password, reindex, check, gc-images, export, version)", cmd)
	}
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return nil
		}
		return err
	}
	if cmd == "export" && o.out == "" {
		return errors.New("--out is required")
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	dbPath := filepath.Join(o.dataDir, "notes.db")
	db, err := database.Open(ctx, dbPath)
	if err != nil {
		return fmt.Errorf("open database %s: %w", dbPath, err)
	}
	defer db.Close()

	// check and export must leave the database untouched.
	if cmd != "check" && cmd != "export" {
		if _, err := search.RebuildIfNeeded(ctx, db); err != nil {
			return fmt.Errorf("rebuild search index: %w", err)
		}
	}

	switch cmd {
	case "serve":
		return serve(ctx, db, o)
	case "create-owner":
		if o.username == "" {
			return errors.New("--username is required")
		}
		pw, err := readPassword()
		if err != nil {
			return err
		}
		if err := auth.NewService(db).CreateOwner(ctx, o.username, pw); err != nil {
			return err
		}
		fmt.Println("owner account created")
	case "reset-password":
		pw, err := readPassword()
		if err != nil {
			return err
		}
		if err := auth.NewService(db).ResetPassword(ctx, pw); err != nil {
			return err
		}
		fmt.Println("password updated; all sessions signed out")
	case "reindex":
		n, err := search.Rebuild(ctx, db)
		if err != nil {
			return err
		}
		fmt.Printf("search index rebuilt (%d notes)\n", n)
	case "check":
		problems, err := db.IntegrityCheck(ctx)
		if err != nil {
			return err
		}
		if len(problems) > 0 {
			for _, p := range problems {
				fmt.Fprintln(os.Stderr, p)
			}
			return fmt.Errorf("integrity check found %d problem(s); restore from a backup — the database has not been modified", len(problems))
		}
		bad, err := images.NewStore(db).Check(ctx)
		if err != nil {
			return err
		}
		if len(bad) > 0 {
			for _, id := range bad {
				fmt.Fprintln(os.Stderr, "image "+id+": bytes don't match its hash")
			}
			return fmt.Errorf("%d image(s) are damaged; restore from a backup — the database has not been modified", len(bad))
		}
		// Not damage: a copy from a long-offline device can show an image
		// already deleted, or a client never uploaded one.
		missing, err := images.NewStore(db).Missing(ctx)
		if err != nil {
			return err
		}
		for _, id := range missing {
			fmt.Fprintln(os.Stderr, "warning: image "+id+" is shown by a note but not stored")
		}
		fmt.Println("ok")
	case "gc-images":
		n, size, err := images.NewStore(db).Collect(ctx, time.Now().Add(-images.Grace), o.dryRun)
		if err != nil {
			return err
		}
		verb := "deleted"
		if o.dryRun {
			verb = "would delete"
		}
		fmt.Printf("%s %d images (%s) that no note has shown for 30 days\n", verb, n, humanBytes(size))
	case "export":
		return export(ctx, db, o)
	}
	return nil
}

func export(ctx context.Context, db *database.DB, o options) error {
	// Before any prompt: a refused folder shouldn't cost the password.
	if err := checkExportDir(o.out); err != nil {
		return err
	}
	all, err := liveNotes(ctx, db)
	if err != nil {
		return err
	}
	var key []byte
	if o.locked {
		locked := 0
		for _, n := range all {
			if locks.IsLocked(n.Content) {
				locked++
			}
		}
		if locked > 0 {
			pw, err := askLockPassword(o.out, locked, os.Stdin, os.Stderr)
			if err != nil {
				return err
			}
			if key, err = lockKey(ctx, db, pw); err != nil {
				return err
			}
		}
	}
	folders, err := spaceFolders(ctx, db)
	if err != nil {
		return err
	}
	store := images.NewStore(db)
	res, err := writeExport(o.out, all, folders, key, func(id string) (string, []byte, error) {
		img, data, err := store.Get(ctx, id)
		return img.ContentType, data, err
	})
	if err != nil {
		return err
	}
	fmt.Printf("exported %d notes and %d images to %s\n", res.Written, res.Images, o.out)
	if res.Locked > 0 {
		fmt.Printf("skipped %d locked notes; --include-locked exports them with the lock password\n", res.Locked)
	}
	if res.Unreadable > 0 {
		fmt.Printf("skipped %d locked notes that the lock password doesn't open (locked under an earlier keyring, or damaged)\n", res.Unreadable)
	}
	if res.MissingImages > 0 {
		fmt.Printf("left %d images as notesd:image/ references: not on the server, or encrypted and not opened\n", res.MissingImages)
	}
	return nil
}

func serve(ctx context.Context, db *database.DB, o options) error {
	log, err := newLogger(o.logLevel, o.logFormat)
	if err != nil {
		return err
	}
	slog.SetDefault(log)

	var public *url.URL
	if o.publicURL != "" {
		public, err = url.Parse(o.publicURL)
		if err != nil || (public.Scheme != "http" && public.Scheme != "https") || public.Host == "" {
			return fmt.Errorf("--public-url must be an absolute http(s) URL, got %q", o.publicURL)
		}
	}

	authSvc := auth.NewService(db)
	hasOwner, err := ownerFromEnv(ctx, authSvc, log)
	if err != nil {
		return err
	}
	srv := &http.Server{
		Handler:           api.New(db, notes.NewStore(db), authSvc, log, api.Config{PublicURL: public, ResendURL: o.resendURL}).Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    64 << 10,
		ErrorLog:          slog.NewLogLogger(log.Handler(), slog.LevelWarn),
	}
	ln, err := net.Listen("tcp", o.listen)
	if err != nil {
		return err
	}
	// Before "started": test harnesses (and people) read the log up to the listen address.
	if !hasOwner {
		log.Warn("no owner account yet: open url and create it before anyone else does, "+
			"or set NOTESD_OWNER_USERNAME and NOTESD_OWNER_PASSWORD",
			"url", appURL(public, ln.Addr()))
	}
	log.Info("notesd started", "version", version, "listen", ln.Addr().String(),
		"database", db.Path, "schema_version", database.LatestVersion())

	errc := make(chan error, 1)
	go func() { errc <- srv.Serve(ln) }()
	go collectImages(ctx, images.NewStore(db), log)

	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}
	log.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	// In-flight requests finish (and their transactions commit or roll back)
	// before the database is closed by the caller.
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Warn("forced shutdown", "error", err)
	}
	return nil
}

// ownerFromEnv creates the owner from NOTESD_OWNER_USERNAME and
// NOTESD_OWNER_PASSWORD when there is none yet. Setting both keeps first-run
// setup from ever being open to the network; with neither, serve starts with
// setup open. Once an owner exists they are ignored: changing them later
// changes nothing (reset-password does). It reports whether an owner exists.
func ownerFromEnv(ctx context.Context, a *auth.Service, log *slog.Logger) (bool, error) {
	has, err := a.HasOwner(ctx)
	if err != nil || has {
		return has, err
	}
	username, password := os.Getenv("NOTESD_OWNER_USERNAME"), os.Getenv("NOTESD_OWNER_PASSWORD")
	switch {
	case username == "" && password == "":
		return false, nil
	case username == "" || password == "":
		return false, errors.New("set both NOTESD_OWNER_USERNAME and NOTESD_OWNER_PASSWORD, or neither")
	}
	if err := a.CreateOwner(ctx, username, password); err != nil {
		return false, fmt.Errorf("create owner from NOTESD_OWNER_USERNAME: %w", err)
	}
	log.Info("owner account created from NOTESD_OWNER_USERNAME", "username", username)
	return true, nil
}

// appURL is where the web app is reached, for the first-run log line.
func appURL(public *url.URL, addr net.Addr) string {
	base := url.URL{Scheme: "http", Host: addr.String(), Path: "/"}
	if public != nil {
		base = url.URL{Scheme: public.Scheme, Host: public.Host, Path: strings.TrimSuffix(public.Path, "/") + "/"}
	} else if tcp, ok := addr.(*net.TCPAddr); ok && tcp.IP.IsUnspecified() {
		base.Host = net.JoinHostPort("localhost", fmt.Sprint(tcp.Port))
	}
	return base.String()
}

func newLogger(level, format string) (*slog.Logger, error) {
	var lv slog.Level
	if err := lv.UnmarshalText([]byte(level)); err != nil {
		return nil, fmt.Errorf("invalid log level %q", level)
	}
	opts := &slog.HandlerOptions{Level: lv}
	switch format {
	case "text":
		return slog.New(slog.NewTextHandler(os.Stderr, opts)), nil
	case "json":
		return slog.New(slog.NewJSONHandler(os.Stderr, opts)), nil
	}
	return nil, fmt.Errorf("invalid log format %q", format)
}

func readPassword() (string, error) {
	if pw, ok := os.LookupEnv("NOTESD_PASSWORD"); ok {
		return pw, nil
	}
	fmt.Fprint(os.Stderr, "password (input is not hidden; prefer NOTESD_PASSWORD or piping): ")
	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil && !(errors.Is(err, io.EOF) && line != "") {
		return "", fmt.Errorf("read password: %w", err)
	}
	return strings.TrimRight(line, "\r\n"), nil
}

func env(key, def string) string {
	if v, ok := os.LookupEnv(key); ok && v != "" {
		return v
	}
	return def
}

// Image collection runs a while after start, so a restart loop doesn't
// repeat it, then every few hours. Deleting is cheap; there's no hurry.
const (
	collectFirst = 5 * time.Minute
	collectEvery = 6 * time.Hour
)

// collectImages deletes images no note has shown for images.Grace until ctx ends (§7.6).
func collectImages(ctx context.Context, store *images.Store, log *slog.Logger) {
	t := time.NewTimer(collectFirst)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		n, size, err := store.Collect(ctx, time.Now().Add(-images.Grace), false)
		switch {
		case err != nil && ctx.Err() == nil:
			log.Error("delete unused images", "err", err)
		case n > 0:
			log.Info("deleted unused images", "count", n, "bytes", size)
		}
		t.Reset(collectEvery)
	}
}

func humanBytes(n int64) string {
	switch {
	case n >= 1<<20:
		return fmt.Sprintf("%.1f MiB", float64(n)/(1<<20))
	case n >= 1<<10:
		return fmt.Sprintf("%.1f KiB", float64(n)/(1<<10))
	}
	return fmt.Sprintf("%d bytes", n)
}
