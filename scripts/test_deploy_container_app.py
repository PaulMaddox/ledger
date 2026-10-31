"""Dependency-free tests for the real deployment script's writer handover."""

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location(
    "deploy_container_app", Path(__file__).with_name("deploy-container-app.py"))
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.updated = False
        self.replicas = []
        self.region = "swedencentral"
        self.fail_update = False
        self.missing_image = False
        self.extra_active = False
        self.revision = "old"

    def command(self, command, **kwargs):
        self.calls.append(command)
        parts = command[1:4]
        resource = {"id": "/environment", "location": self.region}
        if parts == ["containerapp", "show", "--resource-group"]:
            result = {**resource, "properties": {
                "managedEnvironmentId": "/environment",
                "configuration": {"ingress": {"fqdn": "ledger.example.com"}}}}
        elif parts == ["containerapp", "env", "show"]:
            result = resource
        elif command[1:3] == ["acr", "show"]:
            result = {**resource, "loginServer": "registry.azurecr.io"}
        elif command[1:3] == ["identity", "show"]:
            result = resource
        elif parts == ["containerapp", "env", "storage"]:
            result = {"properties": {"azureFile": {"accessMode": "ReadWrite"}}}
        elif parts == ["containerapp", "revision", "list"]:
            result = [{"name": f"ledger-web--{self.revision}",
                       "properties": {"active": True}}]
            if self.updated and self.extra_active:
                result.append({"name": "ledger-web--unexpected",
                               "properties": {"active": True}})
        elif parts == ["acr", "repository", "show"]:
            if self.missing_image:
                raise subprocess.CalledProcessError(1, command, stderr="image not found")
            result = {}
        elif parts == ["containerapp", "replica", "list"]:
            result = self.replicas
        elif command[1:3] == ["containerapp", "update"]:
            if self.fail_update:
                raise subprocess.CalledProcessError(1, command, stderr="deployment failed")
            self.updated = True
            self.revision = "new"
            result = {}
        else:
            result = {}
        return subprocess.CompletedProcess(command, 0, json.dumps(result), "")

    def run_deploy(self):
        response = MagicMock()
        response.__enter__.return_value.status = 200
        response.__enter__.return_value.read.return_value = b"<!doctype html><html></html>"
        with patch.object(sys, "argv", [
            "deploy", "--app", "ledger-web", "--registry", "registry",
            "--tag", "image", "--revision", "new"
        ]), patch.object(deploy.shutil, "which", return_value="az"), \
                patch.object(deploy.subprocess, "run", side_effect=self.command), \
                patch.object(deploy.urllib.request, "urlopen", return_value=response), \
                patch.object(deploy.time, "sleep"), \
                patch.object(deploy.os, "environ", {}):
            deploy.main()

    def test_stops_and_drains_before_updating(self):
        self.run_deploy()
        actions = [command[1:4] for command in self.calls]
        stop = actions.index(["containerapp", "revision", "deactivate"])
        drain = actions.index(["containerapp", "replica", "list"])
        update = next(i for i, command in enumerate(self.calls)
                      if command[1:3] == ["containerapp", "update"])
        self.assertLess(stop, drain)
        self.assertLess(drain, update)
        self.assertTrue(all("--subscription" in command for command in self.calls))

    def test_remaining_replica_refuses_new_writer(self):
        self.replicas = [{"name": "old-replica"}]
        with patch.object(deploy.time, "monotonic", side_effect=[0, 301]):
            with self.assertRaisesRegex(RuntimeError, "refusing to start another writer"):
                self.run_deploy()
        self.assertFalse(self.updated)

    def test_wrong_region_stops_before_deactivation(self):
        self.region = "eastus"
        with self.assertRaisesRegex(RuntimeError, "Sweden Central"):
            self.run_deploy()
        self.assertFalse(any("deactivate" in command for command in self.calls))

    def test_azure_display_region_name_is_accepted(self):
        self.region = "Sweden Central"
        self.run_deploy()
        self.assertTrue(self.updated)

    def test_duplicate_revision_stops_before_deactivation(self):
        self.revision = "new"
        with self.assertRaisesRegex(RuntimeError, "already exists"):
            self.run_deploy()
        self.assertFalse(any("deactivate" in command for command in self.calls))

    def test_failed_update_does_not_reactivate_old_writer(self):
        self.fail_update = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_deploy()
        self.assertFalse(any("activate" in command for command in self.calls))

    def test_missing_image_stops_before_deactivation(self):
        self.missing_image = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_deploy()
        self.assertFalse(any("deactivate" in command for command in self.calls))

    def test_competing_active_revision_is_not_success(self):
        self.extra_active = True
        with self.assertRaisesRegex(RuntimeError, "exactly the new revision"):
            self.run_deploy()


if __name__ == "__main__":
    unittest.main()
