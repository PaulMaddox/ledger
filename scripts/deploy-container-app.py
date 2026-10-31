#!/usr/bin/env python3
"""Deploy the demo with downtime, never overlapping revision writers."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resource-group", default="ledger")
    parser.add_argument("--app", default="ledger-web")
    parser.add_argument("--environment", default="ledger-env")
    parser.add_argument("--pull-identity", default="ledger-image-pull")
    parser.add_argument("--registry", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,62}", args.revision):
        parser.error("--revision must be a lowercase revision suffix")
    if not re.fullmatch(r"[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}", args.tag):
        parser.error("--tag must be a container image tag")
    az = shutil.which("az")
    if az is None:
        parser.error("Azure CLI is required")

    def command(*parts):
        environment = {**os.environ, "PYTHONUTF8": "1"}
        result = subprocess.run(
            [az, *parts, "--only-show-errors", "--output", "json"],
            check=True, capture_output=True, text=True, encoding="utf-8",
            env=environment,
        )
        return json.loads(result.stdout) if result.stdout.strip() else None

    common = ("--resource-group", args.resource_group, "--name", args.app)
    app = command("containerapp", "show", *common)
    environment = command("containerapp", "env", "show",
                          "-g", args.resource_group, "-n", args.environment)
    registry = command("acr", "show", "-n", args.registry)
    identity = command("identity", "show",
                       "-g", args.resource_group, "-n", args.pull_identity)
    if any(resource["location"].replace(" ", "").lower() != "swedencentral"
           for resource in (app, environment, registry, identity)):
        raise RuntimeError("Demo resources must remain in Sweden Central")
    if app["properties"]["managedEnvironmentId"].lower() != environment["id"].lower():
        raise RuntimeError("Application belongs to a different environment")
    host = app["properties"]["configuration"]["ingress"]["fqdn"]
    if not re.fullmatch(r"[a-z0-9.-]+", host):
        raise RuntimeError("Azure returned an invalid application hostname")
    revisions = command("containerapp", "revision", "list", *common, "--all")
    if any(revision["name"] == f"{args.app}--{args.revision}" for revision in revisions):
        raise RuntimeError("Revision already exists; use a new suffix")
    storage = command("containerapp", "env", "storage", "show",
                      "-g", args.resource_group, "-n", args.environment,
                      "--storage-name", "ledger-data")
    if storage["properties"]["azureFile"]["accessMode"] != "ReadWrite":
        raise RuntimeError("Persistent ledger-data storage must be writable")
    command("acr", "repository", "show", "--name", args.registry,
            "--image", f"ledger-web:{args.tag}")

    values = {
        "PULL_IDENTITY_ID": identity["id"],
        "ENVIRONMENT_ID": environment["id"],
        "LOGIN_SERVER": registry["loginServer"],
        "IMAGE": f'{registry["loginServer"]}/ledger-web:{args.tag}',
        "REVISION_SUFFIX": args.revision,
        "PUBLIC_HOST": host,
    }
    template = Path(__file__).resolve().parents[1] / "deploy" / "container-app.yaml"
    content = template.read_text(encoding="utf-8")
    for name, value in values.items():
        content = content.replace("${" + name + "}", value)
    if "${" in content:
        raise RuntimeError("Unresolved deployment placeholder")

    # Single revision mode rolls out before stopping the old writer.
    command("containerapp", "revision", "set-mode", *common, "--mode", "multiple")
    active = [revision for revision in revisions if revision["properties"]["active"]]
    for revision in active:
        command("containerapp", "revision", "deactivate", *common,
                "--revision", revision["name"])
    deadline = time.monotonic() + 300
    while True:
        remaining = [
            replica
            for revision in revisions
            for replica in command("containerapp", "replica", "list", *common,
                                   "--revision", revision["name"])
        ]
        if not remaining:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError("Old replicas did not stop; refusing to start another writer")
        time.sleep(5)

    with tempfile.TemporaryDirectory(prefix="ledger-deploy-") as directory:
        manifest = Path(directory) / "container-app.yaml"
        manifest.write_text(content, encoding="utf-8")
        command("containerapp", "update", *common, "--yaml", str(manifest))

    deadline = time.monotonic() + 600
    url = f"https://{host}/"
    while True:
        active = [
            revision for revision in command("containerapp", "revision", "list", *common)
            if revision["properties"]["active"]
        ]
        if len(active) != 1 or active[0]["name"] != f"{args.app}--{args.revision}":
            raise RuntimeError("Expected exactly the new revision to be active")
        try:
            with urllib.request.urlopen(url, timeout=20) as response:
                if response.status == 200 and b"<!doctype html" in response.read().lower():
                    print(f"Public synthetic-money demo: {url}", flush=True)
                    summary = os.environ.get("GITHUB_STEP_SUMMARY")
                    if summary:
                        with open(summary, "a", encoding="utf-8") as output:
                            output.write(f"### Public demo: {url}\n\n"
                                         "Unauthenticated synthetic money; not a production wallet.\n")
                    return
                print(f"Waiting for the console HTML at {url}", flush=True)
        except (OSError, urllib.error.URLError) as error:
            print(f"Waiting for verified HTTPS: {error}", flush=True)
        if time.monotonic() >= deadline:
            raise RuntimeError("New revision did not serve verified HTTPS within ten minutes")
        time.sleep(10)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.stderr or str(error)) from error
