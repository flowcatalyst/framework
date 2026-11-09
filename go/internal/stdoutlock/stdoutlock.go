// Package stdoutlock is the lock between code of this module that replaces os.Stdout for a while
// (jetgen, around go-jet's generator, which prints its progress) and code of this module that reads
// os.Stdout meanwhile (pgembed, through embedded-postgres's DefaultConfig). Without it the two race
// when they run in parallel goroutines, as sqlfw's parallel tests do.
package stdoutlock

import "sync"

var mu sync.Mutex

// Lock takes the lock: hold it while replacing os.Stdout, or while reading it in code that may run
// alongside a replacement.
func Lock() { mu.Lock() }

// Unlock releases it.
func Unlock() { mu.Unlock() }
