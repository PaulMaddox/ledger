#!/usr/bin/env python3
"""Check the deployment's permissions, persistence and single-writer contract."""
import json
import os
import socket
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parent.parent
TEMPLATE = json.loads((ROOT / "deploy/azure.json").read_text())
MANIFESTS = list(yaml.safe_load_all((ROOT / "deploy/aks.yaml").read_text()))
WORKFLOW = yaml.safe_load((ROOT / ".github/workflows/deploy-aks.yml").read_text())
CI = yaml.safe_load((ROOT / ".github/workflows/ci.yml").read_text())


class DeploymentTests(unittest.TestCase):
    def test_deployment_uses_only_the_ci_verified_commit(self):
        trigger = WORKFLOW[True]["workflow_run"]
        self.assertEqual(trigger, {"workflows": ["CI"], "branches": ["main"], "types": ["completed"]})
        self.assertNotIn("push", WORKFLOW[True])
        self.assertIn("workflow_dispatch", WORKFLOW[True])
        job = WORKFLOW["jobs"]["deploy"]
        self.assertEqual(job["permissions"]["actions"], "read")
        self.assertEqual(job["env"]["DEPLOY_SHA"], "${{ github.event.workflow_run.head_sha || github.sha }}")
        self.assertEqual(job["steps"][0]["with"]["ref"], "${{ env.DEPLOY_SHA }}")
        gate = job["steps"][1]
        self.assertEqual(gate["env"]["GH_TOKEN"], "${{ github.token }}")
        for required in ('--workflow ci.yml', '--branch main --event push', '--commit "$DEPLOY_SHA"',
                         '--status success', 'if [ "$CI_RUNS" -eq 0 ]; then', 'exit 1'):
            self.assertIn(required, gate["run"])
        script = "\n".join(step.get("run", "") for step in job["steps"])
        self.assertIn("ledger-web:${DEPLOY_SHA}", script)
        self.assertIn("ledger-proxy:${DEPLOY_SHA}", script)
        self.assertNotIn("ledger-web:${GITHUB_SHA}", script)

    def test_tests_and_audit_run_in_ci_not_deployment(self):
        dockerfile = (ROOT / "Dockerfile").read_text()
        self.assertIn("RUN ./gradlew installDist --no-daemon", dockerfile)
        self.assertNotIn("./gradlew test", dockerfile)
        self.assertNotIn("./gradlew run", dockerfile)
        deploy_script = "\n".join(step.get("run", "") for step in WORKFLOW["jobs"]["deploy"]["steps"])
        for duplicate in ("check-guide.mjs", "check-web.mjs", "build-readme.py", "./gradlew test"):
            self.assertNotIn(duplicate, deploy_script)
        ci_script = "\n".join(step.get("run", "") for step in CI["jobs"]["tests"]["steps"])
        self.assertIn("./gradlew test --no-daemon", ci_script)
        self.assertIn("./gradlew test --no-daemon --rerun-tasks", ci_script)
        self.assertIn("./gradlew run --no-daemon", ci_script)
        guide_script = "\n".join(step.get("run", "") for step in CI["jobs"]["guide"]["steps"])
        self.assertIn("check-guide.mjs", guide_script)
        self.assertIn("check-web.mjs", guide_script)

    def test_container_build_includes_about_screenshots(self):
        dockerfile = (ROOT / "Dockerfile").read_text()
        ignore = (ROOT / ".dockerignore").read_text().splitlines()
        self.assertIn("COPY docs/screenshots/ docs/screenshots/", dockerfile)
        for rule in ("!docs/", "!docs/screenshots/", "!docs/screenshots/*.png"):
            self.assertIn(rule, ignore)
        self.assertTrue((ROOT / "docs/screenshots/simulator.png").is_file())
        self.assertTrue((ROOT / "src/main/resources/web/about.html").is_file())

    def test_bootstrap_does_not_modify_cluster_or_create_namespace(self):
        self.assertEqual(
            {r["type"] for r in TEMPLATE["resources"]},
            {
                "Microsoft.ContainerRegistry/registries",
                "Microsoft.ManagedIdentity/userAssignedIdentities",
                "Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials",
                "Microsoft.Authorization/roleAssignments",
            },
        )
        self.assertEqual(TEMPLATE["parameters"]["location"]["allowedValues"], ["swedencentral"])
        registry = next(r for r in TEMPLATE["resources"] if r["type"].endswith("/registries"))
        self.assertFalse(registry["properties"]["adminUserEnabled"])

    def test_oidc_matches_workflow_environment(self):
        trust = next(r for r in TEMPLATE["resources"] if r["type"].endswith("/federatedIdentityCredentials"))
        job = WORKFLOW["jobs"]["deploy"]
        self.assertEqual(job["environment"], "azure-demo")
        self.assertEqual(trust["properties"]["issuer"], "https://token.actions.githubusercontent.com")
        self.assertEqual(trust["properties"]["audiences"], ["api://AzureADTokenExchange"])
        self.assertEqual(
            trust["properties"]["subject"],
            "[format('repo:{0}:environment:azure-demo', parameters('githubRepository'))]",
        )
        self.assertEqual(job["permissions"]["id-token"], "write")
        self.assertEqual(job["if"], "github.ref == 'refs/heads/main' && "
                         "(github.event_name == 'workflow_dispatch' || "
                         "(github.event.workflow_run.event == 'push' && github.event.workflow_run.conclusion == 'success'))")
        # PyYAML's YAML 1.1 loader treats the unquoted GitHub key "on" as True.
        self.assertEqual(WORKFLOW[True]["workflow_run"]["branches"], ["main"])
        self.assertFalse(WORKFLOW["concurrency"]["cancel-in-progress"])
        for name in TEMPLATE["outputs"]:
            self.assertIn(f"vars.{name}", json.dumps(job))

    def test_only_namespace_scoped_kubernetes_write_permissions(self):
        assignments = [r for r in TEMPLATE["resources"] if r["type"].endswith("/roleAssignments")]
        roles = {r["properties"]["roleDefinitionId"].split("'")[-2]: r for r in assignments}
        self.assertEqual(set(roles), {
            "8311e382-0749-4cb8-b61a-304f252e45ec",  # AcrPush
            "acdd72a7-3385-48ef-bd42-f606fba81ae7",  # Reader
            "7f951dda-4ed3-4680-a7ca-43fe172d538d",  # AcrPull
            "4abbcc35-e782-43d8-92c5-2d3f1bd2253f",  # AKS Cluster User
            "a7ffa36f-339b-4b5c-8bdf-e2c188b2c0eb",  # AKS RBAC Writer
        })
        writer = roles["a7ffa36f-339b-4b5c-8bdf-e2c188b2c0eb"]
        self.assertEqual(
            writer["scope"],
            "[resourceId('Microsoft.ContainerService/managedClusters/namespaces', parameters('clusterName'), variables('namespaceName'))]",
        )
        pull = roles["7f951dda-4ed3-4680-a7ca-43fe172d538d"]
        self.assertIn("identityProfile.kubeletidentity.objectId", pull["properties"]["principalId"])
        for assignment in assignments:
            self.assertEqual(assignment["properties"]["principalType"], "ServicePrincipal")

    def test_one_writer_and_durable_storage(self):
        deployment = next(r for r in MANIFESTS if r["kind"] == "Deployment")
        self.assertEqual(deployment["spec"]["replicas"], 1)
        self.assertEqual(deployment["spec"]["strategy"]["type"], "Recreate")
        pod = deployment["spec"]["template"]["spec"]
        self.assertFalse(pod["automountServiceAccountToken"])
        ledger = next(c for c in pod["containers"] if c["name"] == "ledger")
        self.assertEqual(ledger["args"][ledger["args"].index("--data") + 1], "/data/ledger")
        self.assertEqual(
            ledger["args"][ledger["args"].index("--public-origin") + 1],
            "https://${PUBLIC_HOST}",
        )
        self.assertEqual(ledger["args"][ledger["args"].index("--host") + 1], "127.0.0.1")
        claim = next(r for r in MANIFESTS if r["kind"] == "PersistentVolumeClaim")
        self.assertEqual(claim["spec"]["accessModes"], ["ReadWriteOnce"])
        self.assertEqual(claim["spec"]["storageClassName"], "managed-csi")
        self.assertEqual(claim["spec"]["resources"]["requests"]["storage"], "32Gi")
        self.assertEqual(
            next(v for v in pod["volumes"] if v["name"] == "data")["persistentVolumeClaim"]["claimName"],
            claim["metadata"]["name"],
        )
        for resource in MANIFESTS:
            self.assertEqual(resource["metadata"]["namespace"], TEMPLATE["variables"]["namespaceName"])

    def test_only_proxy_is_public(self):
        service = next(r for r in MANIFESTS if r["kind"] == "Service")
        self.assertEqual(service["spec"]["type"], "LoadBalancer")
        self.assertEqual(
            [(p["port"], p["targetPort"]) for p in service["spec"]["ports"]],
            [(80, "http"), (443, "https")],
        )
        config = next(r for r in MANIFESTS if r["kind"] == "ConfigMap")
        self.assertIn("reverse_proxy 127.0.0.1:8080", config["data"]["Caddyfile"])

    def test_probes_detect_handler_starvation_not_just_an_open_socket(self):
        deployment = next(r for r in MANIFESTS if r["kind"] == "Deployment")
        containers = deployment["spec"]["template"]["spec"]["containers"]
        ledger = next(c for c in containers if c["name"] == "ledger")
        for name in ("startupProbe", "readinessProbe", "livenessProbe"):
            probe = ledger[name]
            command = probe["exec"]["command"]
            self.assertEqual(command[0], "curl")
            self.assertIn("--fail", command)
            self.assertEqual(command[-1], "http://127.0.0.1:8080/")
            self.assertEqual(command[command.index("--max-time") + 1], "3")
            self.assertGreater(probe["timeoutSeconds"], 3)
            self.assertNotIn("tcpSocket", probe)
        self.assertEqual(ledger["startupProbe"]["failureThreshold"], 120)
        self.assertEqual(ledger["livenessProbe"]["periodSeconds"], 10)
        self.assertEqual(ledger["livenessProbe"]["failureThreshold"], 6)
        proxy = next(c for c in containers if c["name"] == "https")
        self.assertEqual(proxy["livenessProbe"]["tcpSocket"]["port"], "https")
        self.assertIn("RUN curl --version > /dev/null", (ROOT / "Dockerfile").read_text())

    def test_http_probe_rejects_an_accepting_but_unresponsive_server(self):
        release = threading.Event()

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path == "/stalled":
                    release.wait(10)
                    return
                self.send_response(503 if self.path == "/failed" else 200)
                self.send_header("Content-Length", "0")
                self.end_headers()

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            with socket.create_connection(server.server_address, timeout=1):
                pass
            deployment = next(r for r in MANIFESTS if r["kind"] == "Deployment")
            ledger = next(c for c in deployment["spec"]["template"]["spec"]["containers"] if c["name"] == "ledger")
            command = list(ledger["livenessProbe"]["exec"]["command"])
            command[command.index("--output") + 1] = os.devnull
            for path, expected in (("/", 0), ("/stalled", 28), ("/failed", 22)):
                command[-1] = f"http://127.0.0.1:{server.server_port}{path}"
                result = subprocess.run(command, capture_output=True, text=True, timeout=5)
                self.assertEqual(result.returncode, expected, result.stderr)
        finally:
            release.set()
            server.shutdown()
            server.server_close()
            worker.join(5)


if __name__ == "__main__":
    unittest.main(verbosity=2)
