# eval-ghidra

A tiny Python client that evaluates a snippet against a **live
[GhidraMCP](https://github.com/bethington/ghidra-mcp) server**.

GhidraMCP exposes a few hundred tools over HTTP. `eval-ghidra` fetches the live
`/mcp/schema`, generates a Python callable for every tool, and runs your snippet
in a namespace where they already exist — so a tool call becomes a one-liner
instead of a hand-written JSON request.

## Requirements

- Ghidra with the GhidraMCP plugin running, `/mcp/schema` reachable.
- Python 3.9+ (standard library only).

## Install

```bash
curl -fsSLO https://raw.githubusercontent.com/taowen/eval-ghidra/main/eval-ghidra.py
chmod +x eval-ghidra.py
```

## Usage

```bash
# the live tool list, grouped by category
./eval-ghidra.py --help

# one tool: parameters, flags and a ready-to-paste example
./eval-ghidra.py --help rename_function

# a single call
./eval-ghidra.py 'get_function_by_address(address="0x1cdb538")'

# a multi-line snippet: the last expression is printed
./eval-ghidra.py '
r = call("search_functions_enhanced", name_pattern="Swapchain")
print("matches:", r["total"])
[x["name"] for x in r["results"]]
'
```

A single expression is printed as-is; dicts and lists are printed as JSON.
Anything else needs an explicit `print`, or assign `result = ...`.

## Addresses are ELF RVAs

Ghidra works with virtual addresses. This client keeps the schema in **ELF RVAs**
and adds the image base for the fields named `address`, `function_address`,
`start_address` and `end_address` — and for any parameter the schema marks
`param_type=address` — so you can paste an RVA straight from a disassembly
listing:

```
address="0x1cdb538"   ->  sent as 0x2cdb538 when the image base is 0x100000
```

`rva(x)` applies the same conversion when you need it explicitly. Names, strings
and byte patterns are never converted.

## Environment

| Variable | Meaning | Default |
| --- | --- | --- |
| `GHIDRA_MCP_URL` | GhidraMCP base URL, e.g. `http://127.0.0.1:8089` | Windows: `http://127.0.0.1:8089`; elsewhere the Unix socket is used |
| `GHIDRA_SOCK` | GhidraMCP Unix socket path | newest `$XDG_RUNTIME_DIR/ghidra-mcp/ghidra-*.sock` |
| `GHIDRA_PROGRAM` | Program pinned to every tool that accepts `program` | unset — the server's active program is used |
| `GHIDRA_IMAGE_BASE` | Image base added by the RVA conversion | `0x100000` |

Set `GHIDRA_PROGRAM` to pin a program for a whole session, or pass
`program="..."` to a single call. `GHIDRA_IMAGE_BASE` defaults to `0x100000`,
the image base Ghidra uses for most ELF shared objects; set it if yours differs.

## Behaviour worth knowing

- `run_script_inline` and several comment getters answer with plain text instead
  of the JSON envelope. That text is returned verbatim, but an inline script
  that ends in `=== SCRIPT EXECUTION ERROR ===` raises, so a failed run cannot be
  mistaken for output.
- Java scripts get a long client timeout (at least 1800 s) because Ghidra runs
  them on the Swing thread. `run_script_inline` has no `timeout_seconds`
  parameter of its own.
- A transport timeout does not stop Ghidra. Check the server state before
  retrying.
- Failed or partial results (`success: false`, `failed > 0`,
  `variables_failed > 0`, `status: error|rejected|failed`) raise instead of
  returning a half-applied result.
- Unknown or missing parameters stop the snippet before anything is sent, so a
  typo cannot silently hit the wrong tool.

## Tutorial and scripts

This repository also carries a tutorial that uses `eval-ghidra` to teach how to
turn unreadable decompiler output into trustworthy C:

- [`TUTORIAL.md`](TUTORIAL.md) — why decompiler output is hard to read (the root
  causes) and how to fix it, chapter by chapter.
- [`tutorial/`](tutorial/) — per-chapter notes, each following
  *phenomenon → classify the root cause → find evidence → correct it*.
- [`ghidra_scripts/`](ghidra_scripts/) — refine/audit scripts for
  `run_ghidra_script`, with a [README](ghidra_scripts/README.md) that separates
  reusable scripts from project-specific examples.
- [`start-tutorial-ghidra.ps1`](start-tutorial-ghidra.ps1) — launch a second,
  independent Ghidra instance (own settings directory and port) for the tutorial.

## License

MIT — see [LICENSE](LICENSE).
