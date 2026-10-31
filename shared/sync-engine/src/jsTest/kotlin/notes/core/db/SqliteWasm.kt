@file:JsModule("@sqlite.org/sqlite-wasm")

package notes.core.db

import kotlin.js.Promise

/** sqlite-wasm's module factory (its default export); resolves to the `sqlite3` namespace. */
@JsName("default")
external fun sqlite3InitModule(): Promise<dynamic>
