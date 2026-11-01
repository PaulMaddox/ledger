# Deploy the web console

These instructions publish the demo to the existing **ledger** AKS cluster in
resource group **ledger**, **Sweden Central**. Wait for cluster provisioning to
show **Succeeded** before starting.

**This is a public, unauthenticated demo with synthetic money.** Anyone can use
every view, damage history with Chaos Monkey, and permanently clear demo storage.
Never use real accounts, private customer data or production keys. HTTPS enables
browser verification; it does not add authentication. Azure resources incur charges.

## 1. Create the namespace

In the Azure portal, open **ledger > Kubernetes resources > Namespaces > Create**
and create `ledger-web`. Alternatively, paste this into the portal's YAML editor:

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: ledger-web
```

You need **Azure Kubernetes Service RBAC Cluster Admin** on the cluster; Azure
resource Owner alone does not grant Kubernetes access. Ask an administrator for
access if needed. The deployment identity does not need this role.

## 2. Set up Azure resources

Download [azure.json](azure.json). In the Azure portal, open **Deploy a custom
template > Build your own template in the editor > Load file**, load it, and save.

Choose the subscription containing the cluster and resource group **ledger**:

| Parameter | Value |
|---|---|
| Cluster Name | `ledger` |
| Location | `swedencentral` |
| Github Repository | `PaulMaddox/ledger` (see OIDC note below) |
| Registry Name | Keep the generated default, or choose a globally unique name. |

**OIDC note:** if your repository uses immutable OIDC subjects, use its immutable
repository prefix instead of `PaulMaddox/ledger`. Check it with
`gh api repos/PaulMaddox/ledger/actions/oidc/customization/sub`; for this
repository the immutable prefix is `PaulMaddox@4678446/ledger@1440482868`.
The trusted subject must match GitHub's assertion exactly:
`repo:<prefix>:environment:azure-demo`.

Select **Review + create > Create**. You need resource-creation and role-assignment
permissions in the resource group, such as **Owner**. Use **incremental** deployment,
never complete mode. If `ledger-github-deploy` already exists, check its permissions
before reusing it; the template does not remove older grants.

The template creates the registry, deployment identity and permissions without
modifying the existing cluster. When it succeeds, open the deployment's **Outputs**.

## 3. Configure GitHub

Open **Settings > Environments** in the repository and create `azure-demo`.
Under **Deployment branches and tags**, allow **only `main`**. This restriction
is required because Azure trusts the environment, not the branch. Add required
reviewers if desired.

Add these **environment variables**, not secrets:

| Variable | Value |
|---|---|
| `AZURE_CLIENT_ID` | Deployment output of the same name |
| `AZURE_TENANT_ID` | Deployment output of the same name |
| `AZURE_SUBSCRIPTION_ID` | Deployment output of the same name |
| `AKS_RESOURCE_GROUP` | Deployment output (`ledger`) |
| `AKS_CLUSTER_NAME` | Deployment output (`ledger`) |
| `ACR_NAME` | Deployment output; no `.azurecr.io` suffix |
| `AKS_DNS_LABEL` | A DNS label unique in Sweden Central, e.g. `paulmaddox-ledger-demo`; no dots |
| `TLS_EMAIL` | Your certificate contact email |

There are no Azure passwords or client secrets to configure. Azure role
propagation may take several minutes. GitHub runners must be able to reach the
AKS API; for a private or IP-restricted cluster, use a runner in the permitted network.

## 4. Deploy

After **CI** succeeds for a push to `main`, **Deploy web app to AKS** runs
automatically. You can also open **Actions > Deploy web app to AKS > Run workflow**
on `main`; successful CI is required for that exact commit.

The workflow builds and deploys the CI-tested commit, waits for rollout and
working HTTPS, then reports the URL:

```text
https://<AKS_DNS_LABEL>.swedencentral.cloudapp.azure.com/
```

Allow time for DNS propagation and certificate issuance. Public inbound ports
**80 and 443** must be reachable. Azure infrastructure setup is needed only once;
later application deployments use the same resources.

## Keep the demo safe

- Keep **one replica** and the **Recreate** update strategy. Do not scale out or
  switch to rolling updates. Updates briefly interrupt service.
- The **32 GiB persistent disk** retains demo data and certificate state across
  deployments. Monitor capacity; deleting the namespace or PVC can delete the disk.
- Keep data and keys in-country. Do not export plaintext data or keys, or configure
  out-of-country backups.
- Keep `--public-origin` set to the exact public HTTPS origin. Preserve Host,
  Origin and Referer headers; do not rewrite them to bypass request checks.
- Demo history resets daily at **03:00 UAE time**. Simulator runs expire after
  **15 minutes**; services stop at a **12 GB** storage high-water mark until reset.

## Troubleshooting

Inspect failures without deleting retained data:

```bash
kubectl get pods,pvc,service -n ledger-web
kubectl get events -n ledger-web --sort-by=.lastTimestamp
kubectl logs deployment/ledger-web -n ledger-web -c https
kubectl logs deployment/ledger-web -n ledger-web -c ledger
```

Check permissions, image pulls, DNS and certificate logs. A failed HTTPS check
means deployment failed. An image rollback must never roll back or delete history;
check data compatibility first.

## Change the deployment

Edit [main.bicep](main.bicep), not the generated `azure.json`. With Bicep CLI
**v0.48.1**, rebuild and check from the repository root:

```bash
bicep build deploy/main.bicep --outfile deploy/azure.json
python3 -m pip install -r scripts/requirements-deploy.txt
python3 scripts/check-deploy.py
```

Commit both files and reapply the template through the portal for infrastructure
changes. Edit [aks.yaml](aks.yaml) for workload changes.
