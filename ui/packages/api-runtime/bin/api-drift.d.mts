export function snapshot(paths: string[], cwd: string): Map<string, string>;
export function diffSnapshots(before: Map<string, string>, after: Map<string, string>): string[];
export function gitAvailable(cwd: string): boolean;
export function checkDrift(options: { paths: string[]; run?: string; git?: boolean; cwd?: string }): {
  ok: boolean;
  report: string;
};
export function main(args: string[]): number;
