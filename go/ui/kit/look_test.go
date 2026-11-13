package kit_test

import (
	"reflect"
	"testing"

	"github.com/flowcatalyst/framework/go/ui/kit"
	"github.com/flowcatalyst/framework/go/ui/look"
)

var _ look.Look = kit.Look{}

func TestFlashTypesAreTheSharedOnes(t *testing.T) {
	if reflect.TypeFor[kit.FlashMessageProps]() != reflect.TypeFor[look.Flash]() {
		t.Error("kit.FlashMessageProps is not look.Flash")
	}
	if reflect.TypeFor[kit.FlashKind]() != reflect.TypeFor[look.FlashKind]() {
		t.Error("kit.FlashKind is not look.FlashKind")
	}
}
