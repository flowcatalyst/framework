package auth_test

import (
	"log/slog"
	"os"
)

func writeFile(path, text string) error { return os.WriteFile(path, []byte(text), 0o600) }

func discard() *slog.Logger { return slog.New(slog.DiscardHandler) }
