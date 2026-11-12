import { strict as assert } from "node:assert";
import { afterEach, beforeEach, describe, it } from "vitest";
import { createUtf8Decoder, Utf8StreamDecoder } from "../src/core.ts";

const bytes = (...b: number[]) => new Uint8Array(b);
const enc = new TextEncoder();

function decodeAll(chunks: Uint8Array[]): string {
	const d = new Utf8StreamDecoder();
	return chunks.map((c) => d.decode(c)).join("");
}

describe("Utf8StreamDecoder (no TextDecoder)", () => {
	const saved = globalThis.TextDecoder;
	beforeEach(() => {
		delete (globalThis as { TextDecoder?: unknown }).TextDecoder;
	});
	afterEach(() => {
		globalThis.TextDecoder = saved;
	});

	it("is what createUtf8Decoder returns when TextDecoder is absent", () => {
		assert.ok(createUtf8Decoder() instanceof Utf8StreamDecoder);
	});

	it("decodes ASCII and whole multi-byte characters", () => {
		assert.equal(decodeAll([enc.encode("héllo € 😀 ok")]), "héllo € 😀 ok");
	});

	it("joins a 2-byte character split across chunks", () => {
		const b = enc.encode("é"); // C3 A9
		assert.equal(decodeAll([b.slice(0, 1), b.slice(1)]), "é");
	});

	it("joins a 3-byte character split 1+2 and 2+1", () => {
		const b = enc.encode("€");
		assert.equal(decodeAll([b.slice(0, 1), b.slice(1)]), "€");
		assert.equal(decodeAll([b.slice(0, 2), b.slice(2)]), "€");
	});

	it("joins a 4-byte emoji split 1+3 and 3+1", () => {
		const b = enc.encode("😀");
		assert.equal(b.length, 4);
		assert.equal(decodeAll([b.slice(0, 1), b.slice(1)]), "😀");
		assert.equal(decodeAll([b.slice(0, 3), b.slice(3)]), "😀");
	});

	it("joins a character delivered one byte at a time", () => {
		const b = enc.encode("a😀b");
		assert.equal(decodeAll([...b].map((x) => bytes(x))), "a😀b");
	});

	it("emits nothing for a held partial character", () => {
		const d = new Utf8StreamDecoder();
		assert.equal(d.decode(bytes(0xf0, 0x9f)), "");
		assert.equal(d.decode(bytes(0x98)), "");
		assert.equal(d.decode(bytes(0x80)), "😀");
	});

	it("turns a stray continuation byte into U+FFFD", () => {
		assert.equal(decodeAll([bytes(0x61, 0x80, 0x62)]), "a�b");
	});

	it("turns an invalid lead byte into U+FFFD", () => {
		assert.equal(decodeAll([bytes(0xff, 0x61)]), "�a");
		assert.equal(decodeAll([bytes(0xc0, 0xaf)]), "��");
	});

	it("turns a truncated sequence followed by ASCII into one U+FFFD and keeps the ASCII", () => {
		assert.equal(decodeAll([bytes(0xe2, 0x82, 0x61)]), "�a");
		// split across chunks, the same result
		assert.equal(decodeAll([bytes(0xe2, 0x82), bytes(0x61)]), "�a");
	});

	it("rejects surrogates and overlong forms like TextDecoder", () => {
		const td = new (saved as typeof TextDecoder)();
		for (const seq of [bytes(0xed, 0xa0, 0x80), bytes(0xe0, 0x80, 0x80), bytes(0xf4, 0x90, 0x80, 0x80), bytes(0xf0, 0x61)]) {
			assert.equal(decodeAll([seq]), td.decode(seq));
		}
	});
});
