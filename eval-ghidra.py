#!/usr/bin/env python3
"""Eval a Python snippet against live GhidraMCP tools.

  eval-ghidra.py --help
  eval-ghidra.py --help get_function_variables
  eval-ghidra.py 'get_function_by_address(address="0x1cdb538")'
"""
import ast, http.client, json, os, socket, sys
from collections import defaultdict
from pathlib import Path
from urllib.parse import urlencode, urlparse

IMAGE_BASE = int(os.environ.get("GHIDRA_IMAGE_BASE", "0x100000"), 0)
PROGRAM = os.environ.get("GHIDRA_PROGRAM", "")
ADDR_FIELDS = {
    "address",
    "function_address",
    "start_address",
    "end_address",
}


class UnixHTTP(http.client.HTTPConnection):
    def __init__(self, path, timeout=180):
        super().__init__("localhost", timeout=timeout)
        self.path = path

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self.path)


def find_sock():
    if os.environ.get("GHIDRA_SOCK"):
        return os.environ["GHIDRA_SOCK"]
    root = Path(os.environ.get("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}")) / "ghidra-mcp"
    socks = sorted(root.glob("ghidra-*.sock"))
    if not socks:
        raise SystemExit("no ghidra-mcp socket")
    return str(socks[-1])


SOCK = os.environ.get("GHIDRA_SOCK")
URL = None if SOCK else os.environ.get("GHIDRA_MCP_URL")
if not URL and not SOCK:
    if os.name == "nt":
        URL = "http://127.0.0.1:8089"
    else:
        SOCK = find_sock()


def check_result(data):
    if isinstance(data, dict):
        partial = any(isinstance(data.get(k), (int, float)) and data[k] > 0
                      for k in ("failed", "variables_failed"))
        if (data.get("error") or data.get("success") is False or partial
                or data.get("status") in ("error", "rejected", "failed")):
            raise SystemExit("Ghidra call failed: " + json.dumps(data, ensure_ascii=False))
    return data


def req(method, path, params=None, body=None, timeout=180):
    q = {k: str(v).lower() if isinstance(v, bool) else v
         for k, v in (params or {}).items() if v is not None}
    if q:
        path = f"{path}?{urlencode(q, doseq=True)}"
    headers, raw = {}, None
    if body is not None:
        raw = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if URL:
        endpoint = urlparse(URL)
        conn = http.client.HTTPConnection(
            endpoint.hostname, endpoint.port or 80, timeout=timeout
        )
        path = endpoint.path.rstrip("/") + path
    else:
        conn = UnixHTTP(SOCK, timeout=timeout)
    try:
        conn.request(method, path if path.startswith("/") else "/" + path, raw, headers)
        r = conn.getresponse()
        text = r.read().decode("utf-8", "replace")
        if r.status >= 400:
            raise SystemExit(f"{method} {path} HTTP {r.status}: {text}")
    finally:
        conn.close()
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        # run_script_inline and several comment getters answer with plain text
        # instead of the JSON envelope. Return it verbatim, but still surface a
        # failed inline execution so callers cannot mistake it for output.
        if "=== SCRIPT EXECUTION ERROR ===" in text:
            raise SystemExit(
                "Ghidra inline script failed:\n" + text.strip()[-4000:])
        return text
    data = check_result(data)
    return check_result(data.get("data", data) if isinstance(data, dict) else data)


SCHEMA = req("GET", "/mcp/schema")
TOOLS = {t["path"].strip("/"): t for t in SCHEMA["tools"]}


def rva(x):
    n = int(str(x), 16) if str(x).lower().startswith("0x") else int(x)
    return f"0x{n + IMAGE_BASE:x}"


def _is_addr(name, spec):
    return name in ADDR_FIELDS or spec.get("param_type") == "address"


def _value(name, value, spec):
    if _is_addr(name, spec):
        return rva(value)
    if name in ("disassembly_comments", "decompiler_comments") and isinstance(value, list):
        out = []
        for item in value:
            if isinstance(item, dict) and "address" in item:
                item = dict(item)
                item["address"] = rva(item["address"])
            out.append(item)
        return out
    if spec.get("type") == "string" and isinstance(value, (dict, list)):
        return json.dumps(value)
    return value


def call(tool_name, /, **kw):
    tool = TOOLS.get(tool_name)
    if tool is None:
        raise SystemExit(f"unknown tool {tool_name!r}; try --help {tool_name}")
    params, body = {}, {}
    fields = {p["name"]: p for p in tool.get("params") or []}
    unknown = kw.keys() - fields.keys()
    if unknown:
        raise SystemExit(f"{tool_name}: unknown parameter(s): {', '.join(sorted(unknown))}; try --help {tool_name}")
    if PROGRAM and "program" in fields:
        kw.setdefault("program", PROGRAM)
    missing = [k for k, spec in fields.items()
               if spec.get("required") and (k not in kw or kw[k] is None)]
    if missing:
        raise SystemExit(f"{tool_name}: missing required parameter(s): {', '.join(missing)}")
    for k, v in kw.items():
        spec = fields.get(k, {})
        v = _value(k, v, spec)
        (body if spec.get("source") == "body" else params)[k] = v
    timeout = 180
    if tool_name in ("run_ghidra_script", "run_script_inline"):
        timeout = max(1800, int(kw.get("timeout_seconds") or 0) + 30)
    return req(tool["method"], tool_name, params or None, body or None, timeout=timeout)


def run_snippet(code, ns):
    tree = ast.parse(code)
    if not tree.body:
        return None
    last = tree.body[-1]
    if len(tree.body) == 1 and isinstance(last, ast.Expr):
        return eval(code, ns)
    if isinstance(last, ast.Expr):
        exec(compile(ast.Module(tree.body[:-1], type_ignores=[]), "<snip>", "exec"), ns)
        return eval(compile(ast.Expression(last.value), "<snip>", "eval"), ns)
    exec(compile(tree, "<snip>", "exec"), ns)
    return ns.get("result")


def _example(tool):
    args = []
    for p in tool.get("params") or []:
        if p["name"] == "program" or not (p.get("required") or _is_addr(p["name"], p)):
            continue
        n = p["name"]
        if _is_addr(n, p):
            args.append(f'{n}="0x1cdb538"')
        elif p.get("type") == "boolean":
            args.append(f"{n}=True")
        elif p.get("type") == "integer":
            args.append(f"{n}=5")
        elif p.get("type") in ("json", "object"):
            args.append(f"{n}={{}}")
        elif p.get("type") == "array":
            args.append(f"{n}=[]")
        else:
            args.append(f'{n}="..."')
    name = tool["path"].strip("/")
    return f'{name}({", ".join(args)})'


def _flag(p):
    bits = [p.get("source") or "query", p.get("type") or ""]
    if p.get("required"):
        bits.append("required")
    if _is_addr(p["name"], p):
        bits.append("ELF RVA")
    if p["name"] == "program":
        bits.append(f"default {PROGRAM or '(active)'}")
    return "  ".join(x for x in bits if x)


def show_help(topic=None):
    ntools = SCHEMA.get("count") or len(SCHEMA["tools"])
    if not topic:
        print("eval-ghidra.py '<snippet>'")
        print("eval-ghidra.py --help <tool>")
        endpoint = URL or SOCK
        print(f"live /mcp/schema: {ntools} tools   program={PROGRAM or '(active)'}   endpoint={endpoint}")
        print()
        print("Addresses are ELF RVA. Fields named address / function_address /")
        print(f"start_address / end_address are +0x{IMAGE_BASE:x} even if the schema omits")
        print("param_type=address. Do not wrap those with rva().")
        print("Byte patterns and names are not addresses.")
        print("Helpers: call(tool_name, **kw), rva(x), json")
        print("A single expression, or the last expression of a multi-line snippet,")
        print("is printed. Otherwise assign result = ...")
        print("Dict/list values for string params are json.dumps'd.")
        print("Unknown/missing parameters and failed/partial results stop the snippet.")
        print("HTTP timeout: 180s; Java scripts at least 1800s, beyond timeout_seconds.")
        print("A transport timeout does not stop Ghidra; inspect its state before retrying.")
        print()
        by = defaultdict(list)
        for t in SCHEMA["tools"]:
            by[t.get("category") or "?"].append(t)
        for cat, items in sorted(by.items()):
            print(f"{cat} ({len(items)})")
            for t in items:
                print(f"  {t['method']:4} {t['path'].strip('/')}")
        return
    key = topic.lstrip("/")
    tool = TOOLS.get(key)
    if tool is None:
        hits = [n for n in TOOLS if key.lower() in n.lower()]
        print(f"unknown tool {key!r}", file=sys.stderr)
        if hits:
            print("similar:", ", ".join(hits[:12]), file=sys.stderr)
        raise SystemExit(1)
    print(f"{tool['method']} {tool['path'].strip('/')}")
    desc = (tool.get("description") or "").strip()
    if desc:
        print(desc)
    print()
    for p in tool.get("params") or []:
        print(f"  {p['name']:<22} {_flag(p)}")
        if p.get("description"):
            print(f"    {p['description']}")
    print()
    print("example:")
    print(f"  {_example(tool)}")


def main():
    if "--help" in sys.argv or "-h" in sys.argv:
        args = [a for a in sys.argv[1:] if a not in ("--help", "-h")]
        show_help(args[0] if args else None)
        return
    code = sys.argv[1] if len(sys.argv) > 1 else sys.stdin.read()
    ns = {"call": call, "rva": rva, "json": json, "result": None}
    ns.update((n, (lambda n=n: lambda **kw: call(n, **kw))()) for n in TOOLS if n.isidentifier())
    out = run_snippet(code, ns)
    if out is not None:
        print(json.dumps(out, indent=2, ensure_ascii=False) if isinstance(out, (dict, list)) else out)


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        sys.exit(0)
    except KeyboardInterrupt:
        sys.exit(130)
