package ticketsweb

import (
	"io"
	"path/filepath"
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
)

func TestMain(m *testing.M) {
	ConfigDir = filepath.Join("..", "config")
	fwtest.Main(m)
}

// quiet is the output of a started application in a test.
func quiet() fw.Option { return fw.WithOutput(io.Discard, io.Discard) }
