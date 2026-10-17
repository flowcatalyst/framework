<script setup lang="ts">
// The record drawer of /tickets/:id (useDrawerRoute: deep link, Back closes, the list's query kept).
// An agent assigns the ticket and moves it along its status machine from here: the buttons offered are
// the ones the machine allows from the ticket's status (the server decides again), Resolve and Reopen
// ask for a note in a form dialog, Close asks first. Anyone with the comment permission comments.
import { toServiceError, type ServiceError } from "@flowcatalyst/api-runtime";
import {
  FwDetailField,
  FwDetailList,
  FwFormDialog,
  FwFormSection,
  FwLocalTime,
  FwRecordDrawer,
  FwStatusBadge,
  useConfirm,
  useDrawerRoute,
  useFlash,
  usePermissions,
} from "@flowcatalyst/ui";
import UAlert from "@nuxt/ui/components/Alert.vue";
import UBadge from "@nuxt/ui/components/Badge.vue";
import UButton from "@nuxt/ui/components/Button.vue";
import UFormField from "@nuxt/ui/components/FormField.vue";
import UInput from "@nuxt/ui/components/Input.vue";
import UTextarea from "@nuxt/ui/components/Textarea.vue";
import { computed, inject, reactive, shallowRef, watch } from "vue";
import { assignTicket, commentOnTicket, getTicket, transitionTicket } from "../api/tickets/sdk.gen.ts";
import type { CommentBody, TicketView, TransitionBody } from "../api/tickets/types.gen.ts";
import { zCommentBody, zTransitionBody } from "../api/tickets/zod.gen.ts";
import { z } from "zod";
import { COMMENT, WORK } from "../pages.ts";
import { PRIORITY_COLORS, REFRESH_TICKETS, STATUS_COLORS } from "./tickets.ts";

const refreshList = inject(REFRESH_TICKETS, () => Promise.resolve());
const flash = useFlash();
const permissions = usePermissions();
const canWork = computed(() => permissions.can(WORK));
const canComment = computed(() => permissions.can(COMMENT));
const me = computed(() => permissions.user.value?.principal.id);
const confirm = useConfirm();
const drawer = useDrawerRoute({ listPath: "/tickets" });

const ticket = shallowRef<TicketView | undefined>(undefined);
const loadError = shallowRef<string | undefined>(undefined);

async function load(id: string): Promise<void> {
  const { data, error, response } = await getTicket({ path: { ticketId: id } });
  if (data === undefined) {
    ticket.value = undefined;
    loadError.value = toServiceError(error, response).message;
    return;
  }
  loadError.value = undefined;
  ticket.value = data;
}

watch(
  drawer.id,
  (id) => {
    if (id !== undefined) {
      ticket.value = undefined;
      void load(id);
    }
  },
  { immediate: true },
);

const title = computed(() => ticket.value?.id ?? drawer.id.value ?? "Ticket");
const status = computed(() => ticket.value?.status);

/** What the status machine allows from here (TicketStatus.after on the server decides again). */
const canStart = computed(() => status.value === "OPEN");
const canResolve = computed(() => status.value === "IN_PROGRESS");
const canReopen = computed(() => status.value === "RESOLVED");
const canClose = computed(() => status.value === "RESOLVED");
const canAssign = computed(() => status.value === "OPEN" || status.value === "IN_PROGRESS");
const canWriteComment = computed(() => canComment.value && status.value !== "CLOSED");

/** Applies a changed ticket: the drawer shows it and the list behind it refreshes. */
async function applied(next: TicketView, message: string): Promise<void> {
  ticket.value = next;
  flash.success(message);
  await refreshList();
}

/** A write that found the ticket changed (409) shows the server's words and reads it again. */
function failed(error: unknown, response: Response | undefined, id: string): void {
  const failure = toServiceError(error, response);
  flash.error(failure.message);
  if (failure.status === 409) {
    void load(id);
    void refreshList();
  }
}

// Assign.
const assignee = shallowRef("");
async function assign(who: string): Promise<void> {
  const current = ticket.value;
  if (current === undefined || who.trim() === "") {
    return;
  }
  const { data, error, response } = await assignTicket({ path: { ticketId: current.id }, body: { assigneeId: who.trim() } });
  if (data === undefined) {
    failed(error, response, current.id);
    return;
  }
  assignee.value = "";
  await applied(data, `${current.id} assigned to ${who.trim()}.`);
}

// Start, close: one call each (close asks first).
async function move(action: "START" | "CLOSE", done: string): Promise<void> {
  const current = ticket.value;
  if (current === undefined) {
    return;
  }
  if (action === "CLOSE") {
    const confirmed = await confirm({
      title: `Close ${current.id}?`,
      description: "A closed ticket cannot change.",
      confirmLabel: "Close ticket",
      cancelLabel: "Keep ticket",
      color: "error",
    });
    if (!confirmed) {
      return;
    }
  }
  const { data, error, response } = await transitionTicket({ path: { ticketId: current.id }, body: { action } });
  if (data === undefined) {
    failed(error, response, current.id);
    return;
  }
  await applied(data, done);
}

// Resolve and reopen: the note is part of the action. The generated schema has `note` optional (start
// and close take none); the dialog needs it, so it narrows the generated schema instead of repeating
// it. (FwFormDialog cannot take the generated one: its optional `note` is `string | undefined` in the
// schema and `note?: string` in the generated type, which exactOptionalPropertyTypes tells apart.)
const zNoteBody = zTransitionBody.extend({ note: z.string().min(1) });
type NoteBody = z.output<typeof zNoteBody>;
const noting = shallowRef<"RESOLVE" | "REOPEN" | undefined>(undefined);
const noteOpen = computed({
  get: () => noting.value !== undefined,
  set: (next: boolean) => {
    if (!next) {
      noting.value = undefined;
    }
  },
});
const noteDraft = reactive<{ action: TransitionBody["action"]; note: string }>({ action: "RESOLVE", note: "" });
function startNote(action: "RESOLVE" | "REOPEN"): void {
  Object.assign(noteDraft, { action, note: "" });
  noting.value = action;
}
async function saveNote(body: NoteBody): Promise<ServiceError | undefined> {
  const current = ticket.value;
  if (current === undefined) {
    return undefined;
  }
  const { data, error, response } = await transitionTicket({ path: { ticketId: current.id }, body });
  if (data === undefined) {
    return toServiceError(error, response);
  }
  await applied(data, body.action === "RESOLVE" ? `${current.id} resolved.` : `${current.id} reopened.`);
  return undefined;
}

// Comment.
const commenting = shallowRef(false);
const commentDraft = reactive<CommentBody>({ body: "" });
function startComment(): void {
  commentDraft.body = "";
  commenting.value = true;
}
async function saveComment(body: CommentBody): Promise<ServiceError | undefined> {
  const current = ticket.value;
  if (current === undefined) {
    return undefined;
  }
  const { data, error, response } = await commentOnTicket({ path: { ticketId: current.id }, body });
  if (data === undefined) {
    return toServiceError(error, response);
  }
  await applied(data, "Comment posted.");
  return undefined;
}
</script>

<template>
  <FwRecordDrawer :open="true" :title="title" :subtitle="ticket?.subject ?? ''" @close="drawer.close">
    <template #extras>
      <FwStatusBadge v-if="ticket !== undefined" :status="ticket.status" :colors="STATUS_COLORS" />
      <UBadge v-if="ticket?.overdue === true" label="Overdue" color="error" variant="outline" data-testid="drawer-overdue" />
    </template>
    <p v-if="loadError !== undefined" class="text-sm text-error" role="alert" data-testid="drawer-error">{{ loadError }}</p>
    <template v-if="ticket !== undefined">
      <FwFormSection title="Ticket">
        <FwDetailList>
          <FwDetailField label="Priority"><FwStatusBadge :status="ticket.priority" :colors="PRIORITY_COLORS" /></FwDetailField>
          <FwDetailField label="Opened by" :value="ticket.requesterId" />
          <FwDetailField label="Assignee" :value="ticket.assigneeId ?? 'Nobody yet'" />
          <FwDetailField label="Opened"><FwLocalTime :at="ticket.openedAt" /></FwDetailField>
          <FwDetailField label="Due"><FwLocalTime :at="ticket.dueAt" /></FwDetailField>
          <FwDetailField v-if="ticket.resolution !== undefined" label="Resolution" :value="ticket.resolution" />
          <FwDetailField label="Version" :value="ticket.version" />
        </FwDetailList>
        <p class="mt-3 whitespace-pre-wrap text-sm" data-testid="ticket-description">{{ ticket.description }}</p>
      </FwFormSection>
      <FwFormSection v-if="canWork" title="Work" description="Give the ticket to someone and move it along.">
        <div v-if="canAssign" class="flex items-end gap-2">
          <UFormField label="Assign to" class="flex-1">
            <UInput v-model="assignee" class="w-full" placeholder="Principal id" data-testid="assignee" />
          </UFormField>
          <UButton label="Assign" :disabled="assignee.trim() === ''" data-testid="assign" @click="assign(assignee)" />
          <UButton v-if="me !== undefined" label="Assign to me" color="neutral" variant="outline" data-testid="assign-me" @click="assign(me)" />
        </div>
        <div class="mt-3 flex flex-wrap gap-2">
          <UButton v-if="canStart" label="Start work" icon="i-lucide-play" data-testid="start" @click="move('START', `${ticket.id}: work started.`)" />
          <UButton v-if="canResolve" label="Resolve…" icon="i-lucide-check" data-testid="resolve" @click="startNote('RESOLVE')" />
          <UButton v-if="canReopen" label="Reopen…" color="neutral" variant="outline" icon="i-lucide-rotate-ccw" data-testid="reopen" @click="startNote('REOPEN')" />
          <UButton v-if="canClose" label="Close ticket…" color="error" variant="outline" icon="i-lucide-lock" data-testid="close" @click="move('CLOSE', `${ticket.id} closed.`)" />
        </div>
        <UAlert v-if="status === 'CLOSED'" class="mt-3" color="neutral" variant="subtle" title="Closed" description="A closed ticket cannot change." />
      </FwFormSection>
      <FwFormSection title="Comments">
        <template v-if="canWriteComment" #actions>
          <UButton label="Add comment" icon="i-lucide-message-square-plus" size="sm" data-testid="add-comment" @click="startComment" />
        </template>
        <ul class="space-y-3" data-testid="comments">
          <li v-for="c in ticket.comments" :key="c.commentId" class="border-t border-default pt-2 first:border-t-0 first:pt-0">
            <p class="text-xs text-muted">{{ c.authorId }} · <FwLocalTime :at="c.postedAt" /></p>
            <p class="whitespace-pre-wrap text-sm">{{ c.body }}</p>
          </li>
        </ul>
        <p v-if="ticket.comments.length === 0" class="text-sm text-muted">No comments yet.</p>
      </FwFormSection>
    </template>
    <template #footer>
      <UButton label="Close" color="neutral" variant="ghost" @click="drawer.close" />
    </template>
  </FwRecordDrawer>
  <FwFormDialog
    v-model:open="noteOpen"
    :title="noting === 'REOPEN' ? 'Reopen the ticket' : 'Resolve the ticket'"
    :description="noting === 'REOPEN' ? 'Say why it is open again.' : 'Say how it was resolved.'"
    :submit-label="noting === 'REOPEN' ? 'Reopen' : 'Resolve'"
    :schema="zNoteBody"
    :state="noteDraft"
    :submit="saveNote"
  >
    <UFormField :label="noting === 'REOPEN' ? 'Reason' : 'Resolution'" name="note">
      <UTextarea v-model="noteDraft.note" class="w-full" :rows="3" data-testid="note" />
    </UFormField>
  </FwFormDialog>
  <FwFormDialog v-model:open="commenting" title="Add a comment" submit-label="Post comment" :schema="zCommentBody" :state="commentDraft" :submit="saveComment">
    <UFormField label="Comment" name="body" required>
      <UTextarea v-model="commentDraft.body" class="w-full" :rows="4" data-testid="comment-body" />
    </UFormField>
  </FwFormDialog>
</template>
