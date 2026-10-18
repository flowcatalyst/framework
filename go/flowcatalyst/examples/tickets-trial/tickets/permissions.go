package tickets

import (
	"slices"

	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
)

// Application is the application code, as on the platform.
const Application = "tickets-trial"

// The permissions of the application, in the platform's four-segment form
// (application:context:aggregate:action). The API checks them on its operations, the use cases
// check them again (a use case also runs from a receiver, where no permission is held), the
// definition sync sends them in roles, and the SPA gates its screens on them.
var (
	// Read lets a caller read tickets and the SLA policy.
	Read = fw.MustPermission("tickets-trial:support:ticket:read")
	// OpenTicket lets a caller open a ticket.
	OpenTicket = fw.MustPermission("tickets-trial:support:ticket:open")
	// Work lets a caller work tickets: assign them and move them along their status machine.
	Work = fw.MustPermission("tickets-trial:support:ticket:work")
	// Comment lets a caller comment on a ticket (a requester only on their own).
	Comment = fw.MustPermission("tickets-trial:support:ticket:comment")
	// SLAManage lets a caller change the client's SLA policy.
	SLAManage = fw.MustPermission("tickets-trial:support:sla:manage")
)

// AdminOverview is who may read the queue overview (the Java TicketsAdminModule.VIEW).
var AdminOverview = fw.MustPermission("tickets-trial:admin:tickets:view")

// RoleDef is a role and what it grants.
type RoleDef struct {
	// Name is the name without the application prefix.
	Name        string
	Description string
	Permissions []fw.Permission
}

// PlatformName is the name on the platform, which is also what the person's sign-in carries.
func (r RoleDef) PlatformName() string { return Application + ":" + r.Name }

// The roles, written once: the definition sync sends them to the platform (where a role is named
// tickets-trial:<name>) and the sign-in maps the roles a person holds to the permissions here, so
// the two cannot drift apart (friction F9, which Java solves the same way).
var (
	// Requester opens tickets and comments on their own.
	Requester = RoleDef{"requester", "Opens tickets and follows them.", []fw.Permission{Read, OpenTicket, Comment}}
	// Agent works the queue.
	Agent = RoleDef{"agent", "Works tickets: assigns them and moves them along.", []fw.Permission{Read, OpenTicket, Work, Comment}}
	// Lead is an agent who also sets the client's SLA.
	Lead = RoleDef{"lead", "An agent who also sets the client's SLA policy.", []fw.Permission{Read, OpenTicket, Work, Comment, SLAManage}}
)

// Roles is every role. The administrator's role is the framework's own (admin).
var Roles = []RoleDef{Requester, Agent, Lead}

// RoleTable is the permissions each platform role name grants, for the sign-in's permission mapping,
// with the administrator's wildcard for the admin area (admin contract section 3).
func RoleTable() map[string][]string {
	out := map[string][]string{}
	for _, r := range Roles {
		perms := make([]string, len(r.Permissions))
		for i, p := range r.Permissions {
			perms[i] = p.String()
		}
		out[r.PlatformName()] = perms
	}
	out[Application+":admin"] = []string{Application + ":admin:*:*"}
	return out
}

// roleDefinition is the role as the definition sync takes it, permissions sorted.
func roleDefinition(r RoleDef) flowcatalyst.Role {
	perms := make([]string, len(r.Permissions))
	for i, p := range r.Permissions {
		perms[i] = p.String()
	}
	slices.Sort(perms)
	return flowcatalyst.NewRole(r.Name, perms...).WithDescription(r.Description)
}
