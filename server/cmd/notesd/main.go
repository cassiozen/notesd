// Command notesd is the self-hosted notes backend: the JSON API, the
// sync endpoint, full-text search and the embedded web app, in one process
// backed by SQLite: system.db for accounts, sessions and server-wide
// settings, and one users/<id>.db per user for their notes.
//
// Usage:
//
//	notesd [serve] [flags]          run the server (default)
//	notesd create-owner --username NAME
//	                                create the first account, an admin
//	notesd add-user --username NAME [--admin]
//	notesd list-users
//	notesd remove-user --username NAME
//	                                delete an account; its notes stay on disk
//	notesd reset-password [--username NAME]
//	notesd reindex [--username NAME]
//	                                rebuild the search index
//	notesd check [--username NAME]  run an integrity check
//	notesd gc-images [--username NAME] [--dry-run]
//	                                delete images no note has shown for 30 days
//	notesd export [--username NAME] --out DIR [--include-locked]
//	                                write a user's notes to DIR as Markdown
//	notesd version
//
// Commands that take --username act on every user without it, except
// reset-password and export, which need it once there is more than one.
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
	"notes/server/internal/search"
	"notes/server/internal/userdata"
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
	dataDir    string
	listen     string
	publicURL  string
	logLevel   string
	logFormat  string
	resendURL  string
	username   string
	admin      bool
	out        string
	locked     bool
	dryRun     bool
	selfUpdate bool
}

func run(args []string) error {
	cmd := "serve"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		cmd, args = args[0], args[1:]
	}

	var o options
	fs := flag.NewFlagSet("notesd "+cmd, flag.ContinueOnError)
	fs.StringVar(&o.dataDir, "data-dir", env("NOTESD_DATA_DIR", "./data"), "directory holding system.db and users/")
	usernameFlag := func(usage string) { fs.StringVar(&o.username, "username", "", usage) }
	switch cmd {
	case "serve":
		fs.StringVar(&o.listen, "listen", env("NOTESD_LISTEN", "127.0.0.1:8080"), "address to listen on")
		fs.StringVar(&o.publicURL, "public-url", env("NOTESD_PUBLIC_URL", ""), "externally visible URL, e.g. https://notes.example.com (enables Secure cookies when https)")
		fs.StringVar(&o.logLevel, "log-level", env("NOTESD_LOG_LEVEL", "info"), "debug, info, warn or error")
		fs.StringVar(&o.logFormat, "log-format", env("NOTESD_LOG_FORMAT", "text"), "text or json")
		fs.StringVar(&o.resendURL, "resend-url", env("NOTESD_RESEND_URL", ""), "Resend API base URL for emailing notes (default https://api.resend.com)")
		fs.BoolVar(&o.selfUpdate, "self-update", env("NOTESD_SELF_UPDATE", "") == "true", "let admins update from Settings; needs the notesd-update service (docs/operations.md)")
	case "create-owner", "remove-user":
		usernameFlag("username")
	case "add-user":
		usernameFlag("username")
		fs.BoolVar(&o.admin, "admin", false, "let this user change server-wide settings, such as email")
	case "reset-password":
		usernameFlag("whose password to reset (needed once there is more than one user)")
	case "export":
		usernameFlag("whose notes to export (needed once there is more than one user)")
		fs.StringVar(&o.out, "out", "", "directory to write the Markdown files to (new or empty)")
		fs.BoolVar(&o.locked, "include-locked", false, "decrypt locked notes with the lock password and export them as plain text")
	case "gc-images":
		usernameFlag("only this user (default every user)")
		fs.BoolVar(&o.dryRun, "dry-run", false, "only report what would be deleted")
	case "reindex", "check":
		usernameFlag("only this user (default every user)")
	case "list-users":
	case "version":
		fmt.Println("notesd", version)
		return nil
	case "help", "-h", "--help":
		fmt.Fprintln(os.Stderr, "commands: "+commands)
		return nil
	default:
		return fmt.Errorf("unknown command %q (commands: %s)", cmd, commands)
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
	if (cmd == "create-owner" || cmd == "add-user" || cmd == "remove-user") && o.username == "" {
		return errors.New("--username is required")
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	systemPath := filepath.Join(o.dataDir, "system.db")
	system, err := database.OpenSystem(ctx, systemPath)
	if err != nil {
		return fmt.Errorf("open database %s: %w", systemPath, err)
	}
	defer system.Close()
	if err := userdata.Upgrade(ctx, o.dataDir, system); err != nil {
		return fmt.Errorf("upgrade data directory: %w", err)
	}
	authSvc := auth.NewService(system)

	switch cmd {
	case "serve":
		users := userdata.NewRegistry(o.dataDir)
		defer users.Close()
		return serve(ctx, system, users, authSvc, o)
	case "create-owner":
		pw, err := readPassword()
		if err != nil {
			return err
		}
		if err := authSvc.CreateOwner(ctx, o.username, pw); err != nil {
			return err
		}
		fmt.Println("owner account created")
	case "add-user":
		pw, err := readPassword()
		if err != nil {
			return err
		}
		u, err := authSvc.AddUser(ctx, o.username, pw, o.admin)
		if err != nil {
			return err
		}
		role := "user"
		if u.IsAdmin {
			role = "admin"
		}
		fmt.Printf("%s %s created; they can sign in now\n", role, u.Username)
	case "list-users":
		list, err := authSvc.ListUsers(ctx)
		if err != nil {
			return err
		}
		for _, u := range list {
			role := ""
			if u.IsAdmin {
				role = "admin"
			}
			fmt.Printf("%-24s %-6s created %s\n", u.Username, role, time.UnixMilli(u.CreatedAt).Format(time.DateOnly))
		}
	case "remove-user":
		u, err := authSvc.RemoveUser(ctx, o.username)
		if err != nil {
			return err
		}
		// export finds accounts by name, so it can't reach this one any more.
		fmt.Printf("%s removed and signed out. Their notes are still in %s; delete it once you have a copy (export before remove-user, or back up the file).\n",
			u.Username, userdata.Path(o.dataDir, u.ID))
	case "reset-password":
		u, err := oneUser(ctx, authSvc, o.username)
		if err != nil {
			return err
		}
		pw, err := readPassword()
		if err != nil {
			return err
		}
		if err := authSvc.ResetPassword(ctx, u.Username, pw); err != nil {
			return err
		}
		fmt.Printf("password of %s updated; all their sessions signed out\n", u.Username)
	case "export":
		u, err := oneUser(ctx, authSvc, o.username)
		if err != nil {
			return err
		}
		return withUserDB(ctx, o.dataDir, u, func(db *database.DB) error { return export(ctx, db, o) })
	case "reindex", "check", "gc-images":
		if cmd == "check" {
			if err := checkDB(ctx, system, nil); err != nil {
				return fmt.Errorf("%s: %w", systemPath, err)
			}
		}
		list, err := someUsers(ctx, authSvc, o.username)
		if err != nil {
			return err
		}
		for _, u := range list {
			err := withUserDB(ctx, o.dataDir, u, func(db *database.DB) error {
				switch cmd {
				case "reindex":
					n, err := search.Rebuild(ctx, db)
					if err != nil {
						return err
					}
					fmt.Printf("%s: search index rebuilt (%d notes)\n", u.Username, n)
				case "check":
					if err := checkDB(ctx, db, images.NewStore(db)); err != nil {
						return err
					}
					fmt.Printf("%s: ok\n", u.Username)
				case "gc-images":
					n, size, err := images.NewStore(db).Collect(ctx, time.Now().Add(-images.Grace), o.dryRun)
					if err != nil {
						return err
					}
					verb := "deleted"
					if o.dryRun {
						verb = "would delete"
					}
					fmt.Printf("%s: %s %d images (%s) that no note has shown for 30 days\n", u.Username, verb, n, humanBytes(size))
				}
				return nil
			})
			if err != nil {
				return fmt.Errorf("%s: %w", u.Username, err)
			}
		}
	}
	return nil
}

const commands = "serve, create-owner, add-user, list-users, remove-user, reset-password, reindex, check, gc-images, export, version"

// someUsers is the named user, or every user.
func someUsers(ctx context.Context, a *auth.Service, username string) ([]auth.User, error) {
	if username == "" {
		return a.ListUsers(ctx)
	}
	u, err := a.UserByName(ctx, username)
	return []auth.User{u}, err
}

// oneUser is the named user, or the only one.
func oneUser(ctx context.Context, a *auth.Service, username string) (auth.User, error) {
	if username != "" {
		return a.UserByName(ctx, username)
	}
	list, err := a.ListUsers(ctx)
	switch {
	case err != nil:
		return auth.User{}, err
	case len(list) == 0:
		return auth.User{}, errors.New("there are no accounts yet")
	case len(list) > 1:
		return auth.User{}, fmt.Errorf("--username is required: there are %d accounts (see notesd list-users)", len(list))
	}
	return list[0], nil
}

// withUserDB opens a user's notes database for a command. Unlike the
// server, it doesn't rebuild derived data first: check and export must
// leave the database as they found it.
func withUserDB(ctx context.Context, dataDir string, u auth.User, fn func(*database.DB) error) error {
	path := userdata.Path(dataDir, u.ID)
	db, err := database.Open(ctx, path)
	if err != nil {
		return fmt.Errorf("open database %s: %w", path, err)
	}
	defer db.Close()
	return fn(db)
}

// checkDB runs SQLite's integrity check and, for a notes database, checks
// its images.
func checkDB(ctx context.Context, db *database.DB, store *images.Store) error {
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
	if store == nil {
		return nil
	}
	bad, err := store.Check(ctx)
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
	missing, err := store.Missing(ctx)
	if err != nil {
		return err
	}
	for _, id := range missing {
		fmt.Fprintln(os.Stderr, "warning: image "+id+" is shown by a note but not stored")
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

func serve(ctx context.Context, system *database.DB, users *userdata.Registry, authSvc *auth.Service, o options) error {
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

	hasOwner, err := ownerFromEnv(ctx, authSvc, log)
	if err != nil {
		return err
	}
	// Opened now, so a migration that fails shows at startup, not at sign-in.
	list, err := authSvc.ListUsers(ctx)
	if err != nil {
		return err
	}
	for _, u := range list {
		if _, err := users.Open(ctx, u.ID); err != nil {
			return fmt.Errorf("%s: %w", u.Username, err)
		}
	}
	cfg := api.Config{PublicURL: public, ResendURL: o.resendURL, Version: version}
	if o.selfUpdate {
		cfg.UpdateRequest = filepath.Join(o.dataDir, "update-request")
	}
	srv := &http.Server{
		Handler:           api.New(system, users, authSvc, log, cfg).Handler(),
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
		"data_dir", o.dataDir, "users", len(list), "schema_version", database.LatestVersion())

	errc := make(chan error, 1)
	go func() { errc <- srv.Serve(ln) }()
	go collectImages(ctx, authSvc, users, log)

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
	has, err := a.HasUsers(ctx)
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

// collectImages deletes images no note has shown for images.Grace, from
// every user's database, until ctx ends (§7.6).
func collectImages(ctx context.Context, a *auth.Service, users *userdata.Registry, log *slog.Logger) {
	t := time.NewTimer(collectFirst)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		list, err := a.ListUsers(ctx)
		if err != nil && ctx.Err() == nil {
			log.Error("delete unused images", "err", err)
		}
		for _, u := range list {
			d, err := users.Open(ctx, u.ID)
			if err != nil {
				log.Error("delete unused images", "username", u.Username, "err", err)
				continue
			}
			n, size, err := d.Images.Collect(ctx, time.Now().Add(-images.Grace), false)
			switch {
			case err != nil && ctx.Err() == nil:
				log.Error("delete unused images", "username", u.Username, "err", err)
			case n > 0:
				log.Info("deleted unused images", "username", u.Username, "count", n, "bytes", size)
			}
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
