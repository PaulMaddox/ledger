# Public Azure Container Apps demo

Deploys the web console as **ledger-demo** in **ledger-private-env**, in resource
group **ledger**, **Sweden Central**, subscription
`9600a075-454a-4e6b-82d4-58bef3e21b4a`. No AKS cluster, ingress controller or Caddy
sidecar is required. The existing AKS cluster is not modified or deleted.

**Anyone on the internet can use every persona, move synthetic money, run the
simulator, damage the main store with Chaos Monkey, and permanently clear demo
history with Administrator reset. Never put real accounts, private customer data
or production keys here.** Managed HTTPS enables browser cryptographic verification,
not authentication. Container Apps, Premium ACR, storage, NAT and its outbound IP
incur Azure charges.

## One-time Azure setup with Bicep

`infra.bicep` creates the registry, storage/share, VNet, private endpoints and DNS,
NAT/NSG policy, workload-profile environment, storage mount, managed identities,
scoped roles and GitHub OIDC federation. `bootstrap.bicep` creates the initial
app and its app-scoped deployment permission after the image is built.

These are **initial-setup templates, not rolling deployment commands**. Use a fresh
resource group for a new installation, or retain the already provisioned `ledger`
resources and skip setup. Do not apply `infra.bicep` to an operating environment:
its VNet initialization precedes the subnet's NAT/NSG attachment. Existing
CLI-created role assignments also have different identifiers and must be reconciled
before adopting Bicep; do not delete permissions or infrastructure blindly.
Never rerun `bootstrap.bicep` over a persistent app. All image updates, including
the first persistent revision, use the drain script below.

Run as an Azure administrator with permission to create resources and assign
roles. The following commands are for Bash or Azure Cloud Shell:

```bash
SUBSCRIPTION=9600a075-454a-4e6b-82d4-58bef3e21b4a
ACR_NAME=paulmaddoxledger
STORAGE_ACCOUNT=pmledgeraca9600

az provider register --subscription "$SUBSCRIPTION" -n Microsoft.App --wait
az provider register --subscription "$SUBSCRIPTION" -n Microsoft.Network --wait
az provider register --subscription "$SUBSCRIPTION" -n Microsoft.Storage --wait
az provider register --subscription "$SUBSCRIPTION" -n Microsoft.ContainerRegistry --wait
az provider register --subscription "$SUBSCRIPTION" -n Microsoft.ManagedIdentity --wait
az group create --subscription "$SUBSCRIPTION" -n ledger --location swedencentral
az deployment group create --subscription "$SUBSCRIPTION" -g ledger -n ledger-infra \
  --template-file deploy/infra.bicep \
  --parameters registryName="$ACR_NAME" storageAccountName="$STORAGE_ACCOUNT"

TAG=$(git rev-parse HEAD)
az acr build --subscription "$SUBSCRIPTION" -r "$ACR_NAME" \
  -t "ledger-web:$TAG" --platform linux --timeout 3600 .
az deployment group create --subscription "$SUBSCRIPTION" -g ledger -n ledger-bootstrap \
  --template-file deploy/bootstrap.bicep \
  --parameters registryName="$ACR_NAME" imageTag="$TAG"
python3 scripts/deploy-container-app.py --subscription "$SUBSCRIPTION" \
  --registry "$ACR_NAME" --tag "$TAG" --revision "initial-$(date +%s)"
```

Choose globally unique registry and storage names for a fresh installation. The
templates deliberately keep the region in Sweden Central and use the dedicated,
non-overlapping `10.80.0.0/16` VNet, with app subnet `10.80.0.0/23` and endpoint
subnet `10.80.2.0/27`. Endpoint addresses are fixed at `.4` (Files), `.5` (regional
registry data), and `.6` (registry login), matching the narrow NSG rules. This
network is not a template for an unrelated VNet with additional configuration.
A non-VNet environment cannot be converted in place; create a new environment.

Registry roles assume legacy registry permissions, not ABAC repository roles.
The registry administrator password stays disabled. Allow time for role
propagation before starting the app. Storage keys are resolved by ARM into the
environment storage definition; no key is output, placed in source or given to
GitHub. Only an administrator sets or rotates storage keys.

In this subscription, Azure reported that environment creation also required the
`Microsoft.Network/AllowBringYourOwnPublicIpAddress` feature. Register it **only
if Azure returns that specific error**, wait for `Registered`, then refresh the
provider before retrying the failed environment operation:

```bash
az feature register --subscription "$SUBSCRIPTION" --namespace Microsoft.Network \
  --name AllowBringYourOwnPublicIpAddress
az feature show --subscription "$SUBSCRIPTION" --namespace Microsoft.Network \
  --name AllowBringYourOwnPublicIpAddress --query properties.state -o tsv
# After the state above is Registered:
az provider register --subscription "$SUBSCRIPTION" -n Microsoft.Network --wait
```

Environment ingress does not use a user-supplied public IP. The separate
outbound-only NAT does have a Standard public IP.

ACR Tasks uploads only `.dockerignore`-allowed source, never local journals or
signing keys. The Dockerfile fixes Linux wrapper permissions for Windows build
contexts, runs the Kotlin tests twice and the CLI audit before publishing.
Use a distinct tag for an uncommitted build. The regulatory storm retains all
balance observations and uses a 4 GiB **test-only** heap; the web container still
has 2 GiB.

Bootstrap has no persistent volume and permits only localhost requests. It obtains
Azure's real hostname, not a usable public demo. The script checks the environment,
region, image, hostname and isolated `ledger-demo-data` share before stopping any
writer, then drains revisions, applies `container-app.yaml`, and requires verified
public HTTPS. A bootstrap without an allocated hostname is a failed setup.

On Windows, use `python` instead of `python3`, Windows path separators, and an
explicit tag/revision instead of Bash substitutions. The script supports both
platforms and pins every Azure request to the given subscription, or
`AZURE_SUBSCRIPTION_ID` / the documented subscription by default. Set
`$env:PYTHONUTF8 = '1'` before streaming ACR logs in PowerShell; Unicode demo
output otherwise breaks Azure CLI's legacy Windows encoding.

## Public ingress, private dependencies, restricted egress

Storage public networking is disabled. Both private endpoints must be approved,
with private DNS zones linked to the app VNet before SMB mounts/image pulls.
ACR needs Premium for Private Link. Its public endpoint remains authenticated and
enabled for GitHub-hosted image builds, but is not allowed by the app NSG.
Disabling ACR public access requires a trusted VNet-connected build runner.

`ledger-platform-egress` provides explicit NAT through the outbound-only Standard
IP `ledger-platform-egress-ip`. It accepts no unsolicited inbound traffic.
Container Apps supplies public HTTPS separately. No custom route table is needed:
system routes handle public destinations and private endpoints.

`ledger-app-egress` allows these outbound connections only:

| Destination | Ports | Purpose |
|---|---|---|
| App subnet `10.80.0.0/23` | Any | Required intra-environment communication |
| Registry private IPs | TCP 443 | Registry login and regional image downloads |
| Azure Files private IP | TCP 445 | Persistent SMB storage |
| Azure DNS `168.63.129.16` | TCP/UDP 53 | DNS resolution |
| `MicrosoftContainerRegistry` | TCP 443 | Microsoft system images |
| `AzureFrontDoor.FirstParty` | TCP 443 | Microsoft system-image CDN dependency |
| `AzureActiveDirectory` | TCP 443 | Managed identity authentication |

A priority-4096 rule denies every other outbound destination and port, overriding
default NSG allowances. There is no broad `Internet`, `AzureCloud`, public ACR
or Azure Monitor exception; this environment does not use Azure Monitor.
Never broaden egress just to make a failed deployment appear healthy.

This is **deny-by-default egress, not absolute exfiltration prevention**. A
compromised container can abuse permitted services or DNS. Service tags cover
multiple endpoints, and the subnet policy cannot distinguish app traffic from
platform traffic. The image-pull identity has `AcrPull`, not push/storage access.

For the already provisioned network, `egress.bicep` updates only NAT, NSG and their
app-subnet attachment; it does not recreate the VNet or update app revisions:

```bash
az deployment group create --subscription "$SUBSCRIPTION" -g ledger -n ledger-egress \
  --template-file deploy/egress.bicep \
  --parameters filesPrivateIp=10.80.2.4 'registryPrivateIps=["10.80.2.5","10.80.2.6"]'
```

Verify addresses against the actual private endpoints before applying it. For
PowerShell, put the array in a standard ARM parameters JSON file and pass
`--parameters @<file>` to avoid native-command JSON quoting differences.

## GitHub Actions and Azure OIDC

Bicep creates `ledger-github-deploy` and a federated credential for
`repo:PaulMaddox/ledger:environment:azure-demo`, issuer
`https://token.actions.githubusercontent.com`, audience `api://AzureADTokenExchange`.
There is no stored Azure client secret.

The deployment identity has Contributor on **this app only**, Container Apps
Operator on **this environment only**, AcrPush/Reader on the registry, and Managed
Identity Operator/Reader on the pull identity. It cannot create storage, retrieve
account keys, assign roles or manage AKS. Environment Operator includes the
required read/join permission without permission to change storage definitions.

In GitHub **Settings > Environments**, create `azure-demo` and restrict deployment
branches to **main**. This is essential: the federated credential trusts the
environment, not the branch. Set these non-secret environment variables:

| Variable | Value |
|---|---|
| `AZURE_CLIENT_ID` | `ledger-github-deploy` client ID |
| `AZURE_TENANT_ID` | Azure directory ID |
| `AZURE_SUBSCRIPTION_ID` | Subscription above |
| `ACR_NAME` | Registry name without `.azurecr.io` |

```bash
CLIENT_ID=$(az identity show --subscription "$SUBSCRIPTION" -g ledger \
  -n ledger-github-deploy --query clientId -o tsv)
TENANT_ID=$(az account show --subscription "$SUBSCRIPTION" --query tenantId -o tsv)
gh variable set AZURE_CLIENT_ID --repo PaulMaddox/ledger --env azure-demo --body "$CLIENT_ID"
gh variable set AZURE_TENANT_ID --repo PaulMaddox/ledger --env azure-demo --body "$TENANT_ID"
gh variable set AZURE_SUBSCRIPTION_ID --repo PaulMaddox/ledger --env azure-demo --body "$SUBSCRIPTION"
gh variable set ACR_NAME --repo PaulMaddox/ledger --env azure-demo --body "$ACR_NAME"
```

After merging, **Deploy web app to Container Apps** runs on main pushes or manually
on main. It compiles Bicep, checks the guide/browser/deployment tests, builds and
tests the image, then runs the drain script. Infrastructure is not reapplied by
image deployments. Serialized runs are not cancelled midway through draining.
A failure leaves the app stopped or the failed revision available for diagnosis,
never starts a competing writer, and never deletes history to manufacture success.

Container Apps supplies a trusted `azurecontainerapps.io` HTTPS hostname. No DNS
label, certificate email or certificate state volume is needed:

```bash
az containerapp show --subscription "$SUBSCRIPTION" -g ledger -n ledger-demo \
  --query properties.configuration.ingress.fqdn -o tsv
```

## Storage, updates and recovery

The container runs as UID/GID 10001. `container-app.yaml` mounts SMB at `/data`
with matching ownership and restrictive permissions. Journals, fixture state and
the **demo** signing key live under `/data/ledger`. Storage is locally redundant
and Azure-encrypted at rest in Sweden Central; SMB is encrypted in transit.
No out-of-country replication is configured. The fresh `ledger-demo-data` share
is isolated from the old failed app's `ledger-data` share.

Azure Files is networked shared storage, not an AKS single-node disk. **Never use
`nobrl`**, mount the share in another writer app, or scale beyond one replica.
Journal fsync and directory locking remain unchanged. Successful startup/replay
demonstrates demo compatibility, not production durability or failover certification
under arbitrary SMB/network failures.

Multiple revision mode is intentional: single revision mode starts the replacement
before stopping the old writer. The drain script deactivates every active revision,
waits for **all** old replicas to disappear, then deploys the new revision.
Min/max replicas are one. Updates have deliberate downtime, and replay must finish
before readiness. Do not use portal rolling updates, traffic splits, independent
deployments or scaling to bypass this procedure.

The share survives revisions/restarts. Deleting it or its storage account destroys
retained data; no deployment command does that. Protect storage, monitor quota and
design in-country backups before relying on retained history. Administrator reset
remains an explicitly destructive demo action.

For diagnostics without exporting journals or private keys:

```bash
az containerapp revision list --subscription "$SUBSCRIPTION" -g ledger -n ledger-demo -o table
az containerapp replica list --subscription "$SUBSCRIPTION" -g ledger -n ledger-demo --revision <revision>
az containerapp logs show --subscription "$SUBSCRIPTION" -g ledger -n ledger-demo --type system --tail 50
az containerapp logs show --subscription "$SUBSCRIPTION" -g ledger -n ledger-demo --container ledger --tail 50
az containerapp env logs show --subscription "$SUBSCRIPTION" -g ledger -n ledger-private-env --tail 50
```

Log Analytics is disabled; streaming logs are available, although app logs may not
exist before Azure provisions a revision. Capture the ARM deployment's operation
error in that case. A failed HTTPS/replay check is a failed deployment. Fix the
cause, never weaken verification, clear storage or restart repeatedly until green.
Rollback uses a compatible earlier image through the same drain script, with a
new revision suffix; it does not revert journal history.

Screenshots are unchanged because deployment does not change the rendered console.
The old AKS cluster can still incur charges; its deletion is a separate owner
decision, not part of this deployment.
