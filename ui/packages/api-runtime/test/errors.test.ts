import { describe, expect, it } from "vitest";
import { fieldErrors, isErrorEnvelope, toServiceError } from "../src/errors.ts";
import { applyServiceError, type FormErrorTarget } from "../src/form-errors.ts";
import { hasPermission, permissionMatches } from "../src/permissions.ts";

const response = (status: number): Response => new Response(null, { status: status === 204 ? 204 : status });

describe("error envelope", () => {
  it("recognises the envelope shape only", () => {
    expect(isErrorEnvelope({ error: "X", message: "m" })).toBe(true);
    expect(isErrorEnvelope({ error: "X", message: "m", details: { a: "b" } })).toBe(true);
    expect(isErrorEnvelope({ error: "X", message: "m", details: { a: 1 } })).toBe(false);
    expect(isErrorEnvelope({ error: "X" })).toBe(false);
    expect(isErrorEnvelope("<html>")).toBe(false);
    expect(isErrorEnvelope(null)).toBe(false);
  });

  it("normalises envelopes, foreign bodies and network failures", () => {
    expect(toServiceError({ error: "ORDER_NOT_OPEN", message: "the order is not open" }, response(409))).toEqual({
      status: 409,
      error: "ORDER_NOT_OPEN",
      message: "the order is not open",
      details: {},
    });
    expect(toServiceError("<html>Bad gateway</html>", response(502))).toMatchObject({ status: 502, error: "HTTP_502" });
    expect(toServiceError(new TypeError("Failed to fetch"), undefined)).toMatchObject({ status: 0, error: "NETWORK" });
  });

  it("reads a 400's details as field errors (`CODE: message`)", () => {
    const error = toServiceError(
      { error: "VALIDATION", message: "request is invalid", details: { sku: "PATTERN: must match ^[A-Z]", "lines[2].quantity": "free text" } },
      response(400),
    );
    expect(fieldErrors(error)).toEqual([
      { name: "sku", code: "PATTERN", message: "must match ^[A-Z]" },
      { name: "lines[2].quantity", code: "INVALID", message: "free text" },
    ]);
    expect(fieldErrors(toServiceError({ error: "CONFLICT", message: "m", details: { sku: "X: y" } }, response(409)))).toEqual([]);
  });
});

describe("applyServiceError", () => {
  function fakeForm(fields: readonly string[]): FormErrorTarget & { errors: { name?: string; message: string }[] } {
    const errors: { name?: string; message: string }[] = [];
    const form = {
      errors,
      setErrors(errors: { name?: string; message: string }[]) {
        // UForm keeps only errors for fields it shows (and unnamed ones).
        form.errors = errors.filter((e) => e.name === undefined || fields.includes(e.name));
      },
      getErrors: () => form.errors,
    };
    return form;
  }

  it("puts field errors on the fields and returns no form message when all were shown", () => {
    const form = fakeForm(["sku", "quantity"]);
    const result = applyServiceError(
      form,
      toServiceError({ error: "VALIDATION", message: "request is invalid", details: { sku: "PATTERN: must match" } }, response(400)),
    );
    expect(form.errors).toEqual([{ name: "sku", message: "must match" }]);
    expect(result).toEqual({ fields: [{ name: "sku", code: "PATTERN", message: "must match" }], message: undefined });
  });

  it("keeps a field error the form does not show, in the form message", () => {
    const form = fakeForm(["sku"]);
    const result = applyServiceError(
      form,
      toServiceError(
        { error: "VALIDATION", message: "request is invalid", details: { sku: "PATTERN: bad", unitPriceCents: "RANGE: too big" } },
        response(400),
      ),
    );
    expect(result.message).toBe("request is invalid: unitPriceCents: too big");
  });

  it("returns the message of a business rule for the form's error region", () => {
    const result = applyServiceError(fakeForm(["sku"]), toServiceError({ error: "ORDER_NOT_OPEN", message: "the order is not open" }, response(409)));
    expect(result).toEqual({ fields: [], message: "the order is not open" });
  });
});

describe("permissions (the platform's rule)", () => {
  it("matches segment by segment with *, same segment count", () => {
    expect(permissionMatches("orders:order:manage", "orders:order:manage")).toBe(true);
    expect(permissionMatches("orders:*:manage", "orders:order:manage")).toBe(true);
    expect(permissionMatches("*:*:*", "orders:order:manage")).toBe(true);
    expect(permissionMatches("orders:*", "orders:order:manage")).toBe(false);
    expect(permissionMatches("orders:order:read", "orders:order:manage")).toBe(false);
    expect(hasPermission(["a:b", "orders:*:manage"], "orders:order:manage")).toBe(true);
    expect(hasPermission([], "orders:order:manage")).toBe(false);
  });
});
