//go:build !linux

package main

// onContainerLayer: containers run notesd on Linux only.
func onContainerLayer(string) bool { return false }
