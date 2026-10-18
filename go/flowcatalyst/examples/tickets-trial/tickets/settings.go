package tickets

import (
	"fmt"

	"github.com/flowcatalyst/framework/go/clientsettings"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/domain"
)

// MinutesMax is the most minutes a priority may be given: 30 days.
const MinutesMax = 43_200

// SlaMinutes is a client's SLA: minutes an agent has to resolve a ticket, by priority. A change
// applies to tickets opened afterwards: a ticket's deadline is fixed when it is opened. The JSON
// keys are the Java record's components, so a Go service reads the rows a Java service wrote.
type SlaMinutes struct {
	Low    int32 `json:"low"`
	Normal int32 `json:"normal"`
	High   int32 `json:"high"`
	Urgent int32 `json:"urgent"`
}

// MinutesFor returns the minutes to resolve a ticket of a priority.
func (m SlaMinutes) MinutesFor(p domain.Priority) int32 {
	switch p {
	case domain.Low:
		return m.Low
	case domain.Normal:
		return m.Normal
	case domain.High:
		return m.High
	case domain.Urgent:
		return m.Urgent
	}
	return m.Normal
}

// DefaultSLA is what a client with no SLA of its own gets.
var DefaultSLA = SlaMinutes{Low: 2880, Normal: 1440, High: 480, Urgent: 60}

// Problem is why a set of minutes is refused.
type Problem struct {
	Code    string
	Message string
}

// Check returns the first thing wrong with an SLA: a time outside 1 to [MinutesMax], or a more
// urgent priority given more time; false when it is acceptable.
func (m SlaMinutes) Check() (Problem, bool) {
	for _, minutes := range []int32{m.Low, m.Normal, m.High, m.Urgent} {
		if minutes < 1 || minutes > MinutesMax {
			return Problem{"MINUTES_INVALID", fmt.Sprintf("Each time is 1 to %d minutes.", MinutesMax)}, true
		}
	}
	if m.Urgent > m.High || m.High > m.Normal || m.Normal > m.Low {
		return Problem{"POLICY_NOT_ORDERED", "A more urgent ticket cannot be given more time."}, true
	}
	return Problem{}, false
}

// SLA is the client's SLA (client-settings contract): defined here, in code; the values are rows of
// fw_client_settings under the key tickets.sla.
var SLA = mustSLA()

func mustSLA() clientsettings.Setting[SlaMinutes] {
	s, err := clientsettings.NewSetting("tickets.sla", DefaultSLA)
	if err != nil {
		panic(err)
	}
	s, err = s.WithRule(func(m SlaMinutes) string {
		if p, bad := m.Check(); bad {
			return p.Message
		}
		return ""
	})
	if err != nil {
		panic(err)
	}
	return s
}
