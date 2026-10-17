"""End to end check: drive the MCP server over stdio against a real engine jar and a local stub API.

Usage: python3 e2e.py <testlum-mcp.jar> <engine.jar>
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

server_jar, engine_jar = sys.argv[1], sys.argv[2]


class Stub(BaseHTTPRequestHandler):
    def do_GET(self):
        body = json.dumps({"id": 1, "name": "Leanne"}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


httpd = HTTPServer(("127.0.0.1", 0), Stub)
threading.Thread(target=httpd.serve_forever, daemon=True).start()
port = httpd.server_address[1]

env = dict(os.environ, TESTLUM_ENGINE_JAR=engine_jar, TESTLUM_MCP_IDLE_SECONDS="120")
env.pop("TESTLUM_XSD_DIR", None)
proc = subprocess.Popen(["java", "-jar", server_jar], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE, text=True, env=env)
_id = 0


def rpc(method, params=None):
    global _id
    _id += 1
    proc.stdin.write(json.dumps({"jsonrpc": "2.0", "id": _id, "method": method, "params": params or {}}) + "\n")
    proc.stdin.flush()
    return json.loads(proc.stdout.readline())["result"]


def call(name, **args):
    r = rpc("tools/call", {"name": name, "arguments": args})
    return r["isError"], r["content"][0]["text"]


def expect(cond, label, detail=""):
    print(("ok   " if cond else "FAIL ") + label)
    if not cond:
        print(detail)
        proc.kill()
        sys.exit(1)


init = rpc("initialize", {"protocolVersion": "2025-03-26"})
expect(init["serverInfo"]["name"] == "testlum-mcp", "initialize")
names = [t["name"] for t in rpc("tools/list")["tools"]]
expect(names == ["testlum_lookup", "testlum_scaffold", "testlum_check", "testlum_run", "testlum_write_test", "testlum_generate_variations"], "tools/list", names)

err, text = call("testlum_lookup")
expect(not err and "http" in text and "assert" in text, "lookup lists scenario commands", text)
err, text = call("testlum_lookup", path="http/get")
expect(not err and "endpoint" in text and "required" in text, "lookup http/get shows endpoint", text)
err, text = call("testlum_lookup", path="nonsense")
expect(err and "Children:" in text, "lookup unknown element names the children", text)

work = Path(tempfile.mkdtemp(prefix="testlum-mcp-e2e-"))
tests = work / "tests"
err, text = call("testlum_scaffold", dir=str(tests), apiUrl=f"http://127.0.0.1:{port}")
expect(not err and "Validated" in text, "scaffold validates", text)
err, text = call("testlum_scaffold", dir=str(tests))
expect("kept" in text and "created" not in text, "scaffold never overwrites", text)

sc = tests / "scenarios" / "get-user"
sc.mkdir()
(sc / "scenario.xml").write_text("""<?xml version="1.0" encoding="UTF-8"?>
<scenario xmlns="http://www.testlum.com/testing/model/scenario">
    <overview><description>Get user one from the stub</description><name>get-user</name></overview>
    <settings active="true"><tags>smoke</tags></settings>
    <http comment="Get user one from the stub" alias="DEFAULT">
        <get endpoint="/users/1"><response code="200" file="expected_1.json"/></get>
    </http>
</scenario>
""")
err, text = call("testlum_check", dir=str(tests))
expect(not err and text.startswith("FAIL") and "expected_1.json" in text, "check flags the missing expected file", text)
(sc / "expected_1.json").write_text('{"id": 1, "name": "Leanne"}')
err, text = call("testlum_check", dir=str(tests))
expect(text.startswith("PASS"), "check passes once the file exists", text)

err, text = call("testlum_run", dir=str(tests), timeoutSeconds=300, expectedScenarios=1)
expect(not err and text.startswith("exit 0") and "1 passed" in text and "PASS get-user" in text and "gate ok" in text, "run passes", text)

(sc / "expected_1.json").write_text('{"id": 1, "name": "Somebody Else"}')
err, text = call("testlum_run", dir=str(tests), timeoutSeconds=300)
expect(text.startswith("exit 1") and "1 failed" in text and "FAIL get-user" in text, "run reports the failure", text)

err, text = call("testlum_run", dir=str(tests), tags="nomatch", timeoutSeconds=300)
expect(text.startswith("exit ") and not (tests / ".mcp-run-global-config.xml").exists(), "tag run leaves no temp config", text)

proc.stdin.close()
code = proc.wait(timeout=20)
expect(code == 0, "server exits when the client closes stdin", code)
shutil.rmtree(work, ignore_errors=True)
httpd.shutdown()
print("all ok")
