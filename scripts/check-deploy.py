#!/usr/bin/env python3
"""Check the deployment's permissions, persistence and single-writer contract."""
import json
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parent.parent
TEMPLATE = json.loads((ROOT / "deploy/azure.json").read_text())
MANIFESTS = list(yaml.safe_load_all((ROOT / "deploy/aks.yaml").read_text()))
WORKFLOW = yaml.safe_load((ROOT / ".github/workflows/deploy-aks.yml").read_text())


class DeploymentTests(unittest.TestCase):
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
        self.assertEqual(job["if"], "github.ref == 'refs/heads/main'")
        # PyYAML's YAML 1.1 loader treats the unquoted GitHub key "on" as True.
        self.assertEqual(WORKFLOW[True]["push"]["branches"], ["main"])
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
