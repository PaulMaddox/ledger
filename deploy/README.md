# Public AKS demo

Deploys the web console, not the CLI demo, to cluster **ledger**, resource group
**ledger**, in **Sweden Central**. Subscription from the supplied cluster screenshot:
`9600a075-454a-4e6b-82d4-58bef3e21b4a`. Wait until cluster provisioning is `Succeeded`.
The cluster uses Microsoft Entra authentication with Azure RBAC.

**Anyone on the internet can use every persona, move synthetic money, run the
simulator, damage the main store with Chaos Monkey, and permanently clear its history
with Administrator reset. Never put real accounts, private customer data or production keys here.**
HTTPS makes browser verification work; it does not add authentication or make this
a production wallet. Azure load balancer, disk, registry and compute incur charges.

## One-time setup: Azure portal, then GitHub

No manual Azure CLI setup, app registration, client secret, ingress controller or
certificate manager is required. [main.bicep](main.bicep) creates a Basic container
registry, a user-assigned managed identity, its GitHub OIDC trust, and all Azure role
assignments. It **references, but never updates or recreates, the existing AKS cluster**.
Registry and identity stay in Sweden Central. The registry name defaults to a
stable, globally unique name derived from the resource group.

Kubernetes workloads remain in [aks.yaml](aks.yaml): Bicep manages Azure resources,
while GitHub Actions applies the native Kubernetes manifests. The AKS controller
creates the public IP/DNS and Azure disk from the Service and PVC declarations.
There is no second infrastructure deployment system running inside the cluster.

### 1. Create the Kubernetes namespace in the AKS portal

Open **ledger > Kubernetes resources > Namespaces**, then use **Create** to create
`ledger-web` (or paste this into the portal's YAML editor):

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: ledger-web
```

This is the only manual cluster setup. Your administrator needs **Azure Kubernetes
Service RBAC Cluster Admin** on this cluster; Azure resource Owner alone does not grant
Kubernetes access. If needed, an administrator can assign that role to you in the
cluster's **Access control (IAM)** pane. Do not grant it to the deployment identity.
Bicep's existing namespace declaration constructs the Azure RBAC scope only; it does
not create a Kubernetes namespace or require AKS managed namespaces.

### 2. Deploy the Bicep infrastructure through the Azure portal

Download [azure.json](azure.json), the committed ARM template compiled from
`main.bicep`. In the Azure portal, search for **Deploy a custom template**, select
**Build your own template in the editor > Load file**, load `azure.json`, and save.

Select subscription `9600a075-454a-4e6b-82d4-58bef3e21b4a` and resource group
**ledger**. Leave **Cluster Name** as `ledger`, **Location** as `swedencentral`, and
**Github Repository** as `PaulMaddox/ledger`. Leave the generated **Registry Name**
unless you want to choose another globally unique name for a new dedicated registry.
Use **Review + create > Create**.

If GitHub uses immutable OIDC subjects, set **Github Repository** to the repository
prefix returned by `gh api repos/PaulMaddox/ledger/actions/oidc/customization/sub`
without the leading `repo:`. For this repository that is
`PaulMaddox@4678446/ledger@1440482868`. The complete trusted subject must match
GitHub's assertion exactly, including `:environment:azure-demo`; reapplying the
template with the older name-only value would break Azure login again.

The person deploying needs resource creation and role-assignment permissions in
resource group `ledger` (for example **Owner**, or **Contributor** plus **Role Based
Access Control Administrator** with permission to assign the template's roles).
No Microsoft Entra application-administrator permission is needed. Reapplying the
same template/parameters is safe; use incremental deployment, never complete mode.
If `ledger-github-deploy` already exists from another setup, inspect its existing
permissions before reusing it; this template does not remove older grants.

Wait for deployment success, then open the deployment's **Outputs**. The identity
gets registry push/read permissions and AKS cluster-user credentials, but Kubernetes
write access is restricted to `ledger-web`. AKS's existing **kubelet identity** gets
`AcrPull`; the cluster configuration is not changed with `--attach-acr`.

### 3. Configure the GitHub environment

In repository **Settings > Environments**, create `azure-demo`. Under **Deployment
branches and tags**, select only branch `main`; add required reviewers if desired.
**Do not skip this restriction:** Azure's OIDC trust is bound to the environment,
not the branch, so the workflow's branch guard alone is not a security boundary.

Copy these six deployment outputs into matching **environment variables** (not secrets):

| Variable | Value |
|---|---|
| `AZURE_CLIENT_ID` | Managed identity client ID |
| `AZURE_TENANT_ID` | Directory ID |
| `AZURE_SUBSCRIPTION_ID` | Subscription ID |
| `AKS_RESOURCE_GROUP` | `ledger` |
| `AKS_CLUSTER_NAME` | `ledger` |
| `ACR_NAME` | Registry name, without `.azurecr.io` |

Also add `AKS_DNS_LABEL` (a DNS label unique within Sweden Central, such as
`paulmaddox-ledger-demo`, without dots) and `TLS_EMAIL` (your real certificate
contact email). There are no Azure passwords to store or rotate.

Role propagation can take several minutes. Hosted GitHub runners must reach the AKS
API. The supplied cluster is not private; if API IP restrictions are added, use a
trusted self-hosted runner in the permitted network rather than opening administrative
access to the internet.

## Automatic deployments

Merge the deployment change, then open **Actions > Deploy web app to AKS**.
It runs after **CI** succeeds for a push to main, or manually with **Run workflow**
on main. Both paths require successful main-branch CI for the exact deployment
commit. The workflow checks out that SHA and uses it for both image tags, rather
than substituting whichever main commit is newest when CI completes.

CI runs the Kotlin suite twice, the CLI audit, guide checks and console JavaScript
tests. The deployment does not repeat them: the Docker build runs only
`./gradlew installDist --no-daemon`, then the workflow pushes the images, waits
for rollout and verified HTTPS, and prints the public URL in its summary:

`https://<AKS_DNS_LABEL>.swedencentral.cloudapp.azure.com/`

The Docker build includes the generated About guide from `src/main/resources`
and the PNGs from `docs/screenshots`, which Gradle packages into the application.
The About-page HTTP regression runs in CI with those same packaged resources.

The regulatory stress test retains every balance observation in lossless
run-length-compressed sequences per screen, including the count and order of
repeated reads. It checks every observed value against replayed ledger history
and stops its workers even after a failure. The test JVM has a 2 GiB maximum heap.
This is test-only evidence storage and cleanup, not a change to ledger rules.

Azure infrastructure is deployed once, not on every merge. A later infrastructure
change is made in Bicep and reapplied through the same portal flow. The GitHub
deployment identity cannot assign roles or modify the cluster. CI compiles Bicep,
checks that `azure.json` is current, and checks OIDC, permission scopes, persistent
storage and the single-writer Kubernetes configuration.

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

## Editing the deployment

Edit `deploy/main.bicep`, never the generated `deploy/azure.json`. With Bicep CLI
**v0.48.1**, regenerate the portal template and run the deployment checks:

```bash
bicep build deploy/main.bicep --outfile deploy/azure.json
python3 -m pip install -r scripts/requirements-deploy.txt
python3 scripts/check-deploy.py
```

PyYAML is deployment-test tooling only, not an application runtime dependency.
Commit both the Bicep source and generated template. Deployment-only changes do not
alter the console's rendered UI and do not need regenerated screenshots.
