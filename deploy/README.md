# Public AKS demo

Deploys the web console, not the CLI demo, to cluster **ledger**, resource group
**ledger**, in **Sweden Central**. Subscription from the supplied cluster screenshot:
`9600a075-454a-4e6b-82d4-58bef3e21b4a`. Wait until cluster provisioning is `Succeeded`.
The cluster uses Microsoft Entra authentication with Azure RBAC.

**Anyone on the internet can use every persona, move synthetic money and run the
simulator. Never put real accounts, private customer data or production keys here.**
HTTPS makes browser verification work; it does not add authentication or make this
a production wallet. Azure load balancer, disk, registry and compute incur charges.

## One-time Azure setup

Run these commands as an Azure administrator in Azure Cloud Shell (Bash), with `gh`
available for the GitHub steps. Choose a globally unique registry name, a DNS label
unique within Sweden Central, and your real certificate contact email.

```bash
SUBSCRIPTION=9600a075-454a-4e6b-82d4-58bef3e21b4a
ACR_NAME=youruniqueregistry
DNS_LABEL=your-unique-ledger-demo
TLS_EMAIL=you@example.com
az account set --subscription "$SUBSCRIPTION"
AKS_ID=$(az aks show -g ledger -n ledger --query id -o tsv)
TENANT_ID=$(az account show --query tenantId -o tsv)

az acr create -g ledger -n "$ACR_NAME" --sku Basic --location swedencentral
ACR_ID=$(az acr show -n "$ACR_NAME" --query id -o tsv)
# The default registry permission mode uses AcrPull/AcrPush, not ABAC repository roles.
az aks update -g ledger -n ledger --attach-acr "$ACR_ID"

CLIENT_ID=$(az ad app create --display-name ledger-github-deploy --query appId -o tsv)
az ad sp create --id "$CLIENT_ID"
az ad app federated-credential create --id "$CLIENT_ID" --parameters '{
  "name": "github-azure-demo",
  "issuer": "https://token.actions.githubusercontent.com",
  "subject": "repo:PaulMaddox/ledger:environment:azure-demo",
  "audiences": ["api://AzureADTokenExchange"]
}'
az role assignment create --assignee "$CLIENT_ID" --role AcrPush --scope "$ACR_ID"
az role assignment create --assignee "$CLIENT_ID" --role Reader --scope "$ACR_ID"
az role assignment create --assignee "$CLIENT_ID" \
  --role "Azure Kubernetes Service Cluster User Role" --scope "$AKS_ID"
az role assignment create --assignee "$CLIENT_ID" \
  --role "Azure Kubernetes Service RBAC Writer" --scope "$AKS_ID/namespaces/ledger-web"
```

As a cluster administrator, connect to the cluster using the portal's **Connect**
instructions (install `kubelogin` if needed), then create the namespace once:

```bash
az aks get-credentials -g ledger -n ledger
kubelogin convert-kubeconfig -l azurecli
kubectl create namespace ledger-web
```

Your administrator identity needs AKS Azure RBAC Cluster Admin to create namespaces.
The deployment identity deliberately cannot create them. Role propagation can take
several minutes. Hosted GitHub runners must be able to reach the AKS API; if it is
private or IP-restricted, use a trusted self-hosted runner inside the permitted
network rather than opening administrative access to the internet.

## GitHub setup

In **Settings > Environments**, create `azure-demo`. Restrict deployment branches
to `main`; add required reviewers if desired. This branch restriction matters
because the federated identity trusts the environment, not an individual branch.
Set these **environment variables** (none is an Azure password):

| Variable | Value |
|---|---|
| `AZURE_CLIENT_ID` | Application ID (`CLIENT_ID` above) |
| `AZURE_TENANT_ID` | Directory ID (`TENANT_ID` above) |
| `AZURE_SUBSCRIPTION_ID` | Subscription ID above |
| `ACR_NAME` | Registry name, without `.azurecr.io` |
| `AKS_DNS_LABEL` | Chosen DNS label, without dots |
| `TLS_EMAIL` | Real email for certificate expiry/contact notices |

Alternatively, after creating the environment and its branch restrictions, use:

```bash
gh variable set AZURE_CLIENT_ID --repo PaulMaddox/ledger --env azure-demo --body "$CLIENT_ID"
gh variable set AZURE_TENANT_ID --repo PaulMaddox/ledger --env azure-demo --body "$TENANT_ID"
gh variable set AZURE_SUBSCRIPTION_ID --repo PaulMaddox/ledger --env azure-demo --body "$SUBSCRIPTION"
gh variable set ACR_NAME --repo PaulMaddox/ledger --env azure-demo --body "$ACR_NAME"
gh variable set AKS_DNS_LABEL --repo PaulMaddox/ledger --env azure-demo --body "$DNS_LABEL"
gh variable set TLS_EMAIL --repo PaulMaddox/ledger --env azure-demo --body "$TLS_EMAIL"
```

Merge the deployment change, then open **Actions > Deploy web app to AKS**.
It runs on every push to main, or manually with **Run workflow** on main. The image
build runs all Kotlin tests twice and the CLI audit before publishing. The workflow
also checks the guide and console JavaScript, waits for rollout and verified HTTPS,
then prints the public URL in its summary:

`https://<AKS_DNS_LABEL>.swedencentral.cloudapp.azure.com/`

The regulatory stress test retains all balance observations as evidence; the test
JVM has a 2 GiB maximum heap so container builds do not exhaust Gradle's default
512 MiB heap. This is test-only configuration, not a change to ledger rules.

The public LoadBalancer service exposes only Caddy on ports 80 and 443. Azure assigns
its public IP and DNS record; Caddy obtains and renews a publicly trusted certificate,
using port 80 for ACME validation and redirecting browsers to HTTPS. No ingress
controller or separately installed certificate manager is required. Issuance needs
public DNS propagation and unrestricted inbound 80/443. Certificates and ACME account
state persist on the same disk, avoiding reissuance on every restart.

## Storage and operation

`deploy/aks.yaml` uses one replica with `Recreate` updates and a 32 GiB
`managed-csi` Azure disk. Do not scale replicas or switch to rolling updates: this
is a single-writer ledger, not a distributed database. The process also holds the
journal lock. Updates have downtime while the old pod stops and the disk attaches.
Allow time for replay at startup; large histories may require a longer startup
probe. Disk capacity is fixed; monitor usage and explicitly expand it when needed.

Ledger files, fixture state and the **demo** signing key persist under `/data/ledger`;
Caddy stores certificate state under `/data/caddy`. Neither is built into the image
or uploaded as a workflow artifact. `managed-csi` keeps the disk in Sweden Central
with Azure-managed encryption at rest; this adds no cross-region data replication.
Do not configure out-of-country backups or export plaintext data/keys.
The PVC survives deployments, but deleting the namespace/PVC can delete the disk:
protect it and plan in-country backups before relying on retained history.

The application binds only to pod loopback. Caddy preserves browser request headers,
and `--public-origin` pins the exact HTTPS hostname and port. Do not rewrite Origin
or Referer to bypass checks. Local HTTP remains available for diagnostics.
Both containers run non-root with read-only root filesystems and no service-account
token. The Kubernetes disk is writable only through their mounted data directories.
The workflow builds a separate Caddy image target that removes its binary's upstream
privileged-port capability, since the pod uses high ports and drops all capabilities.

For local container checks (fresh temporary demo data):

```bash
docker build -t ledger-web .
docker run --rm -p 127.0.0.1:8080:8080 ledger-web \
  --host 0.0.0.0 --port 8080
```

If deployment fails, inspect without deleting retained data:

```bash
kubectl get pods,pvc,service -n ledger-web
kubectl get events -n ledger-web --sort-by=.lastTimestamp
kubectl logs deployment/ledger-web -n ledger-web -c https
kubectl logs deployment/ledger-web -n ledger-web -c ledger
```

A failed HTTPS check is a failed deployment, not success. Fix DNS, certificate,
permissions, registry pulls or networking before redeploying. Reverting an image
must never revert or delete ledger history; review data compatibility before rollback.
