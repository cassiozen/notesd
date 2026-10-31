// Its own module so saver.ts can use it without importing spaces.ts, which
// would close an import cycle back through source.ts.

/** Every install has this space (architecture §7.7); it can be renamed, never deleted. */
export const DEFAULT_SPACE = 'default'
