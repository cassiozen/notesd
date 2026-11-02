package main

import "syscall"

const overlayfsMagic = 0x794c7630

// onContainerLayer reports whether dir is stored in a container's own
// writable layer rather than on a volume: the root filesystem is overlayfs
// (Docker, and the platforms that run its images) and dir is on the same
// device. Such files vanish when the container is replaced, as every
// redeploy on a hosting platform does. A host install never has an overlay
// root, so it never warns there.
func onContainerLayer(dir string) bool {
	var root syscall.Statfs_t
	if syscall.Statfs("/", &root) != nil || int64(root.Type) != overlayfsMagic {
		return false
	}
	var rootSt, dirSt syscall.Stat_t
	if syscall.Stat("/", &rootSt) != nil || syscall.Stat(dir, &dirSt) != nil {
		return false
	}
	return rootSt.Dev == dirSt.Dev
}
