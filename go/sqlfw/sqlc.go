package sqlfw

import (
	"context"
	"errors"
	"fmt"
	"os/exec"
	"strings"
)

const sqlcInstall = "go install github.com/sqlc-dev/sqlc/cmd/sqlc@" + SqlcVersion

// requireSqlc finds sqlc on PATH, failing with the install instruction when it is missing, and
// warns (without failing) when its version is not [SqlcVersion].
func (t *tool) requireSqlc(ctx context.Context) error {
	bin, err := exec.LookPath("sqlc")
	if err != nil {
		return fmt.Errorf("sqlc is not on PATH; install it with: %s", sqlcInstall)
	}
	t.sqlcBin = bin
	out, err := exec.CommandContext(ctx, bin, "version").Output() //nolint:gosec // bin is the sqlc found on PATH
	if err != nil {
		return fmt.Errorf("sqlc version: %w", err)
	}
	if v := strings.TrimSpace(string(out)); v != SqlcVersion {
		fmt.Fprintf(t.err, "warning: sqlc is %s, the framework is tested with %s (%s)\n", v, SqlcVersion, sqlcInstall)
	}
	return nil
}

// runSqlc runs "sqlc <sub> -f <config>" in the application directory and returns its combined
// output; extraEnv is added to the process's environment.
func (t *tool) runSqlc(ctx context.Context, extraEnv []string, sub string) (string, error) {
	if t.sqlcBin == "" {
		return "", errors.New("sqlc was not located")
	}
	cmd := exec.CommandContext(ctx, t.sqlcBin, sub, "-f", t.sqlcConfig) //nolint:gosec // the sqlc found on PATH, with the application's own configuration
	cmd.Dir = t.dir
	cmd.Env = append(cmd.Environ(), extraEnv...)
	out, err := cmd.CombinedOutput()
	return string(out), err
}
