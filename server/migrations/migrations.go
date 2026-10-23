// Package migrations embeds the ordered SQL schema migrations: Notes for
// each user's notes database, System (in system/) for the system database
// of accounts, sessions and server-wide settings.
//
// Files are named NNNN_description.sql and applied in lexical order. The
// applied version is tracked in PRAGMA user_version, so a file must never be
// edited once released; add a new one instead.
package migrations

import (
	"embed"
	"io/fs"
)

//go:embed *.sql
var Notes embed.FS

//go:embed system/*.sql
var system embed.FS

// System is the system database's migrations.
var System = must(fs.Sub(system, "system"))

func must(f fs.FS, err error) fs.FS {
	if err != nil {
		panic(err)
	}
	return f
}
