// The shared admin data pages' port to the application (docs/contracts/admin.md section 13): the wire
// views of the framework's admin operations, the calls the pages make, and the application's choice of
// them. The package holds no generated client. The application passes the operations of its own
// generated client (`import * as sdk from "./api/orders/sdk.gen.ts"`), and TypeScript checks at that one
// place that each generated function takes and answers what the pages expect.
//
// The views are structural copies of the Java and Go frameworks' admin components (the committed
// OpenAPI documents are the authority); a generated type is assignable to them.
import { z } from "zod";
import { inject, type InjectionKey } from "vue";
import type { AdminAboutView } from "./types.ts";

/** The paged answer of every list (`Page<T>`). */
export interface AdminPageView<T> {
  readonly items: T[];
  readonly nextCursor?: string;
}

export interface AdminEventTypeView {
  readonly id: string;
  readonly code: string;
  readonly name: string;
  readonly description?: string | undefined;
  readonly subdomain: string;
  readonly aggregate: string;
  readonly event: string;
  readonly status: "CURRENT" | "ARCHIVED";
  readonly versionCount: number;
}

export interface AdminSpecVersionView {
  readonly version: string;
  readonly status: string;
  readonly schema?: string | undefined;
}

export interface AdminEventTypeDetailView {
  readonly eventType: AdminEventTypeView;
  readonly versions: AdminSpecVersionView[];
}

export interface AdminRoleView {
  readonly id: string;
  readonly name: string;
  readonly displayName: string;
  readonly description?: string | undefined;
  readonly source: string;
  readonly clientManaged: boolean;
  readonly permissionCount: number;
}

export interface AdminRoleDetailView {
  readonly role: AdminRoleView;
  readonly permissions: string[];
}

export interface AdminUserView {
  readonly id: string;
  readonly email: string;
  readonly name: string;
  readonly active: boolean;
  readonly clientId: string;
  readonly idpType: string;
  readonly roles: string[];
  readonly invitation?: string | undefined;
  readonly lastLoginAt?: string | undefined;
  readonly createdAt: string;
}

export interface AdminInvitationView {
  readonly userId: string;
  readonly email: string;
  readonly name: string;
  readonly clientId: string;
  readonly roles: string[];
  readonly status: "PENDING" | "EXPIRED";
  readonly createdAt: string;
  readonly expiresAt?: string | undefined;
}

export interface AdminInviteBody {
  readonly email: string;
  readonly name: string;
  readonly clientId: string;
  readonly roles: string[];
  readonly sendInvitation?: boolean;
  readonly returnInviteLink?: boolean;
}

export interface AdminInviteResultView {
  readonly id: string;
  readonly invitationSent: boolean;
  readonly inviteLink?: string | undefined;
}

export interface AdminResendResultView {
  readonly expiresAt?: string | undefined;
  readonly inviteLink?: string | undefined;
}

export interface AdminEventView {
  readonly id: string;
  readonly type: string;
  readonly source: string;
  readonly subject?: string | undefined;
  readonly time: string;
  readonly messageGroup?: string | undefined;
  readonly correlationId?: string | undefined;
  readonly clientId?: string | undefined;
}

export interface AdminDispatchJobView {
  readonly id: string;
  readonly eventId?: string | undefined;
  readonly subscriptionId?: string | undefined;
  readonly clientId?: string | undefined;
  readonly code: string;
  readonly status: string;
  readonly kind: string;
  readonly targetUrl: string;
  readonly mode: string;
  readonly messageGroup?: string | undefined;
  readonly descriptor?: string | undefined;
  readonly createdAt: string;
  readonly lastAttemptAt?: string | undefined;
  readonly completedAt?: string | undefined;
  readonly attemptCount: number;
}

export interface AdminContextEntryView {
  readonly key: string;
  readonly value: string;
}

export interface AdminEventDetailView {
  readonly event: AdminEventView;
  readonly data?: string | undefined;
  readonly dataTruncated: boolean;
  readonly context: AdminContextEntryView[];
  readonly causationId?: string | undefined;
  readonly dispatchJobs: AdminDispatchJobView[];
}

export interface AdminAttemptView {
  readonly attemptNumber: number;
  readonly attemptedAt: string;
  readonly completedAt?: string | undefined;
  readonly durationMillis?: number | undefined;
  readonly responseCode?: number | undefined;
  readonly responseBody?: string | undefined;
  readonly responseBodyTruncated: boolean;
  readonly success: boolean;
  readonly errorMessage?: string | undefined;
  readonly errorType?: string | undefined;
}

export interface AdminDispatchJobDetailView {
  readonly job: AdminDispatchJobView;
  readonly protocol?: string | undefined;
  readonly timeoutSeconds?: number | undefined;
  readonly maxRetries?: number | undefined;
  readonly retryStrategy?: string | undefined;
  readonly lastError?: string | undefined;
  readonly attempts: AdminAttemptView[];
}

export interface AdminConfigSettingView {
  readonly key: string;
  readonly module: string;
  readonly type: string;
  readonly secret: boolean;
  readonly required: boolean;
  readonly state: "SET" | "DEFAULT" | "UNSET";
  readonly source: string;
  readonly value?: string | undefined;
  readonly withheld: boolean;
  readonly description?: string | undefined;
}

export interface AdminConfigView {
  readonly environment: string;
  readonly total: number;
  readonly set: number;
  readonly defaulted: number;
  readonly unset: number;
  readonly settings: AdminConfigSettingView[];
  readonly health: { readonly ready: boolean; readonly checks: readonly { readonly name: string; readonly status: "UP" | "DOWN" | "TIMED_OUT"; readonly critical: boolean }[] };
}

/** A property the platform holds for the application (`ConfigItem`); a secret's `value` is always `***`. */
export interface AdminPlatformConfigItemView {
  readonly section: string;
  readonly property: string;
  readonly clientId?: string | undefined;
  readonly valueType: "PLAIN" | "SECRET";
  readonly value: string;
  readonly description?: string | undefined;
  readonly updatedAt: string;
}

export interface AdminPlatformConfigListView {
  readonly items: AdminPlatformConfigItemView[];
}

export interface AdminPlatformConfigSetBody {
  readonly value: string;
  readonly valueType: "PLAIN" | "SECRET";
  readonly description?: string | undefined;
}

/** The one answer that carries a secret's plaintext (`RevealedValue`). */
export interface AdminPlatformConfigRevealedView {
  readonly section: string;
  readonly property: string;
  readonly clientId?: string | undefined;
  readonly value: string;
  readonly updatedAt: string;
}

/** What a generated call answers (client-fetch without `throwOnError`): data, or an error and the response. */
export interface AdminCallResult<T> {
  readonly data?: T | undefined;
  readonly error?: unknown;
  readonly response?: Response | undefined;
}

/** A generated operation as the pages call it. */
export type AdminCall<Options, T> = (options: Options) => PromiseLike<AdminCallResult<T>>;

interface Paged {
  readonly query?: { readonly limit?: number; readonly after?: string };
}

/**
 * The calls of the framework's admin operations, by operation id (the names of the generated functions).
 * Every member is optional: an application mounts the sections its server has. A page whose call is
 * missing says so on the page (and in the console) instead of failing silently.
 */
export interface AdminDataApi {
  readonly getAdminAbout?: AdminCall<undefined, AdminAboutView>;
  readonly listAdminConfig?: AdminCall<
    { readonly query?: { readonly state?: "SET" | "DEFAULT" | "UNSET"; readonly q?: string } },
    AdminConfigView
  >;
  readonly listAdminEventTypes?: AdminCall<
    Paged & { readonly query?: { readonly status?: "CURRENT" | "ARCHIVED"; readonly q?: string } },
    AdminPageView<AdminEventTypeView>
  >;
  readonly getAdminEventType?: AdminCall<{ readonly path: { readonly id: string } }, AdminEventTypeDetailView>;
  readonly listAdminRoles?: AdminCall<Paged & { readonly query?: { readonly q?: string } }, AdminPageView<AdminRoleView>>;
  readonly getAdminRole?: AdminCall<{ readonly path: { readonly name: string } }, AdminRoleDetailView>;
  readonly listAdminUsers?: AdminCall<
    Paged & { readonly query?: { readonly active?: boolean; readonly q?: string } },
    AdminPageView<AdminUserView>
  >;
  readonly getAdminUser?: AdminCall<{ readonly path: { readonly id: string } }, AdminUserView>;
  readonly inviteAdminUser?: AdminCall<{ readonly body: AdminInviteBody }, AdminInviteResultView>;
  readonly listAdminInvitations?: AdminCall<
    Paged & { readonly query?: { readonly status?: "PENDING" | "EXPIRED" } },
    AdminPageView<AdminInvitationView>
  >;
  readonly resendAdminInvitation?: AdminCall<
    { readonly path: { readonly id: string }; readonly body: { readonly returnInviteLink?: boolean } },
    AdminResendResultView
  >;
  readonly revokeAdminInvitation?: (options: { readonly path: { readonly id: string } }) => PromiseLike<{ readonly error?: unknown; readonly response?: Response }>;
  readonly listAdminEvents?: AdminCall<
    Paged & {
      readonly query?: {
        readonly type?: string;
        readonly correlationId?: string;
        readonly since?: string;
      };
    },
    AdminPageView<AdminEventView>
  >;
  readonly getAdminEvent?: AdminCall<{ readonly path: { readonly id: string } }, AdminEventDetailView>;
  readonly listAdminDispatchJobs?: AdminCall<
    Paged & {
      readonly query?: {
        readonly status?: string;
        readonly code?: string;
        readonly since?: string;
      };
    },
    AdminPageView<AdminDispatchJobView>
  >;
  readonly getAdminDispatchJob?: AdminCall<{ readonly path: { readonly id: string } }, AdminDispatchJobDetailView>;
  readonly listAdminPlatformConfig?: AdminCall<
    { readonly query?: { readonly section?: string; readonly clientId?: string } },
    AdminPlatformConfigListView
  >;
  readonly setAdminPlatformConfig?: AdminCall<
    {
      readonly path: { readonly section: string; readonly property: string };
      readonly query?: { readonly clientId?: string };
      readonly body: AdminPlatformConfigSetBody;
    },
    AdminPlatformConfigItemView
  >;
  readonly deleteAdminPlatformConfig?: (options: {
    readonly path: { readonly section: string; readonly property: string };
    readonly query?: { readonly clientId?: string };
  }) => PromiseLike<{ readonly error?: unknown; readonly response?: Response }>;
  readonly revealAdminPlatformConfig?: AdminCall<
    { readonly path: { readonly section: string; readonly property: string }; readonly query?: { readonly clientId?: string } },
    AdminPlatformConfigRevealedView
  >;
}

/** What the plugin option `admin` holds. */
export interface AdminDataOptions {
  /** The application code of the server's admin module (`orders-demo`): it prefixes every admin permission. */
  readonly application: string;
  /** The operations of the application's generated client. */
  readonly api: AdminDataApi;
  /** Rows per page of the lists (default 25; the server clamps to 1 to 200). */
  readonly pageSize?: number;
}

export interface AdminData {
  readonly application: string;
  readonly api: AdminDataApi;
  readonly pageSize: number;
  readonly permissions: AdminPermissions;
}

export const ADMIN_DATA_KEY: InjectionKey<AdminData> = Symbol("fw-admin-data");

/** Builds what the pages inject from the plugin option. */
export function adminData(options: AdminDataOptions): AdminData {
  return {
    application: options.application,
    api: options.api,
    pageSize: options.pageSize ?? 25,
    permissions: adminPermissions(options.application),
  };
}

/** The admin data pages' context; fails loudly when the application did not install it. */
export function useAdminData(): AdminData {
  const data = inject(ADMIN_DATA_KEY, undefined);
  if (data === undefined) {
    throw new Error("the admin data pages need the plugin option: app.use(flowcatalystUi, { api, admin: { application, api: sdk } })");
  }
  return data;
}

/**
 * The call a page makes, or an error that names the missing operation. A page the application mounts
 * without its operation in `api` is a wiring defect, so it throws (the page shows the message).
 */
export function requireCall<K extends keyof AdminDataApi>(data: AdminData, name: K): NonNullable<AdminDataApi[K]> {
  const call = data.api[name];
  if (call === undefined) {
    throw new Error(`the admin page needs the generated operation ${name}, which the application did not pass in admin.api`);
  }
  return call;
}

/** The permissions of the framework's admin sections for an application (admin contract section 3). */
export interface AdminPermissions {
  readonly console: string;
  readonly eventTypesView: string;
  readonly rolesView: string;
  readonly usersView: string;
  readonly usersInvite: string;
  readonly usersResend: string;
  readonly usersRevoke: string;
  readonly activityView: string;
  readonly configView: string;
  readonly platformConfigView: string;
  readonly platformConfigManage: string;
  readonly platformConfigReveal: string;
  /** Name any client in the platform config section; without it a user acts only for the session's own client. */
  readonly platformConfigAllClients: string;
}

export function adminPermissions(application: string): AdminPermissions {
  const p = (section: string, action: string): string => `${application}:admin:${section}:${action}`;
  return {
    console: p("console", "view"),
    eventTypesView: p("event-types", "view"),
    rolesView: p("roles", "view"),
    usersView: p("users", "view"),
    usersInvite: p("users", "invite"),
    usersResend: p("users", "resend"),
    usersRevoke: p("users", "revoke"),
    activityView: p("activity", "view"),
    configView: p("config", "view"),
    platformConfigView: p("platform-config", "view"),
    platformConfigManage: p("platform-config", "manage"),
    platformConfigReveal: p("platform-config", "reveal"),
    platformConfigAllClients: p("platform-config", "all-clients"),
  };
}

/** The invite form's rules: the constants of the server's `InviteBody` (admin contract section 12.1). */
export const inviteBodySchema = z.object({
  email: z.string().min(1).max(254).regex(/^(?:[^@\s]+@[^@\s]+\.[^@\s]+)$/),
  name: z.string().min(1).max(200).regex(/\S/),
  clientId: z.string().min(1).max(100).regex(/\S/),
  roles: z.array(z.string().min(1).max(100).regex(/\S/)).min(1).max(50),
  sendInvitation: z.boolean().optional(),
  returnInviteLink: z.boolean().optional(),
});

/** The platform's mask for a secret: never a value, so it is never sent as one (the platform refuses it: `SECRET_VALUE_MASKED`). */
export const PLATFORM_SECRET_MASK = "***";

/**
 * The add/edit form's rules: the constants of the server's `SetBody` and path names (platform config contract
 * section 4). A property name is exactly a secret name (`SecretRef.isValidName`: `[a-z0-9]+(-[a-z0-9]+)*`, at most
 * 100 characters), so every property the `platform` secrets driver reads can be managed here.
 */
export const platformConfigFormSchema = z
  .object({
    section: z.string().regex(/^[a-z][a-z0-9-]{0,63}$/, "Lower-case letters, digits and hyphens, starting with a letter"),
    property: z
      .string()
      .max(100, "At most 100 characters")
      .regex(/^[a-z0-9]+(-[a-z0-9]+)*$/, "Lower-case letters and digits, in groups joined by single hyphens"),
    clientId: z.string().max(100),
    valueType: z.enum(["PLAIN", "SECRET"]),
    value: z.string().min(1, "A value is required").max(65536).regex(/\S/, "A value is required"),
    description: z.string().max(500),
  })
  .refine((form) => form.valueType !== "SECRET" || form.value.trim() !== PLATFORM_SECRET_MASK, {
    path: ["value"],
    message: "*** is how the platform hides a secret, not a value: enter the secret itself",
  });
