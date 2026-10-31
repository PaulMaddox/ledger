# Public Azure Container Apps demo

Deploys the web console, not the CLI demo, as **ledger-demo** in environment
**ledger-private-env**, resource group **ledger**, **Sweden Central**. The subscription is
`9600a075-454a-4e6b-82d4-58bef3e21b4a`. No AKS cluster, ingress controller or Caddy
sidecar is required. The existing AKS cluster is not modified or deleted.

**Anyone on the internet can use every persona, move synthetic money, run the
simulator, damage the main store with Chaos Monkey, and permanently clear demo
history with Administrator reset. Never put real accounts, private customer data
or production keys here.** Managed HTTPS enables browser cryptographic verification,
not authentication. Container Apps, storage and registry usage incur Azure charges.

## One-time Azure setup

Run the following as an Azure administrator in Bash or Azure Cloud Shell. Choose
globally unique registry and storage account names. Reuse existing resources rather
than recreating them or moving persisted data.

```bash
SUBSCRIPTION=9600a075-454a-4e6b-82d4-58bef3e21b4a
ACR_NAME=paulmaddoxledger
STORAGE_ACCOUNT=pmledgeraca9600
az account set --subscription "$SUBSCRIPTION"
az provider register -n Microsoft.App --wait
az provider register -n Microsoft.Storage --wait

az acr create -g ledger -n "$ACR_NAME" --sku Basic --location swedencentral
az storage account create -g ledger -n "$STORAGE_ACCOUNT" --location swedencentral \
  --sku Standard_LRS --kind StorageV2 --min-tls-version TLS1_2 \
  --https-only true --allow-blob-public-access false --public-network-access Disabled
az storage share-rm create -g ledger --storage-account "$STORAGE_ACCOUNT" \
  --name ledger-demo-data --quota 32 --enabled-protocols SMB

az network vnet create -g ledger -n ledger-app-vnet --location swedencentral \
  --address-prefixes 10.80.0.0/16 --subnet-name container-apps --subnet-prefixes 10.80.0.0/23
az network vnet subnet update -g ledger --vnet-name ledger-app-vnet \
  -n container-apps --delegations Microsoft.App/environments
VNET_ID=$(az network vnet show -g ledger -n ledger-app-vnet --query id -o tsv)
SUBNET_ID=$(az network vnet subnet show -g ledger --vnet-name ledger-app-vnet \
  -n container-apps --query id -o tsv)
az network vnet subnet create -g ledger --vnet-name ledger-app-vnet \
  -n private-endpoints --address-prefixes 10.80.2.0/27 --private-endpoint-network-policies Disabled
STORAGE_ID=$(az storage account show -g ledger -n "$STORAGE_ACCOUNT" --query id -o tsv)
az network private-endpoint create -g ledger -n ledger-files-pe --location swedencentral \
  --vnet-name ledger-app-vnet --subnet private-endpoints \
  --private-connection-resource-id "$STORAGE_ID" --group-id file --connection-name ledger-files
DNS_ID=$(az network private-dns zone create -g ledger \
  -n privatelink.file.core.windows.net --query id -o tsv)
az network private-dns link vnet create -g ledger \
  --zone-name privatelink.file.core.windows.net -n ledger-app-vnet \
  --virtual-network "$VNET_ID" --registration-enabled false
az network private-endpoint dns-zone-group create -g ledger \
  --endpoint-name ledger-files-pe -n default --private-dns-zone "$DNS_ID" --zone-name files
az containerapp env create -g ledger -n ledger-private-env --location swedencentral \
  --infrastructure-subnet-resource-id "$SUBNET_ID" --logs-destination none

# Keep the key in the environment's storage definition, never GitHub or a file.
# Do not enable shell tracing around these commands.
STORAGE_KEY=$(az storage account keys list -g ledger -n "$STORAGE_ACCOUNT" \
  --query '[0].value' -o tsv)
az containerapp env storage set -g ledger -n ledger-private-env --storage-name ledger-demo-data \
  --account-name "$STORAGE_ACCOUNT" --azure-file-share-name ledger-demo-data \
  --azure-file-account-key "$STORAGE_KEY" --access-mode ReadWrite -o none
unset STORAGE_KEY

PULL_IDENTITY_ID=$(az identity create -g ledger -n ledger-image-pull \
  --location swedencentral --query id -o tsv)
PULL_PRINCIPAL=$(az identity show -g ledger -n ledger-image-pull \
  --query principalId -o tsv)
ACR_ID=$(az acr show -n "$ACR_NAME" --query id -o tsv)
az role assignment create --assignee-object-id "$PULL_PRINCIPAL" \
  --assignee-principal-type ServicePrincipal --role AcrPull --scope "$ACR_ID"
```

Registry roles assume the default `LegacyRegistryPermissions` mode, not ABAC
repository roles. The registry administrator password stays disabled. Allow time
for role propagation before starting the application.

Storage public networking stays disabled. The private endpoint must be approved,
and the VNet DNS link must be complete before mounting SMB. Choose non-overlapping
subnet ranges when integrating with another network. A Container Apps environment's
VNet cannot be added after creation; use a new environment rather than trying to
modify a non-VNet environment in place.

In this subscription, Azure reported that VNet environment creation also required
the `Microsoft.Network/AllowBringYourOwnPublicIpAddress` feature. Register it only
if Azure returns that specific provisioning error, wait for `Registered`, and
retry the same environment operation. This deployment does not supply a public IP:

```bash
az feature register --namespace Microsoft.Network --name AllowBringYourOwnPublicIpAddress
az feature show --namespace Microsoft.Network --name AllowBringYourOwnPublicIpAddress \
  --query properties.state -o tsv
```

Build the image locally with Docker or remotely with ACR Tasks:

```bash
TAG=$(git rev-parse HEAD)
az acr build -r "$ACR_NAME" -t "ledger-web:$TAG" --platform linux --timeout 3600 .
```

This uploads only `.dockerignore`-allowed source files, never local journal data or
signing keys. The Dockerfile sets the wrapper's executable permission inside Linux
so Windows build contexts work. Use a distinct tag for an uncommitted build.
The image build runs the Kotlin tests twice and the CLI audit before publishing.
The regulatory storm retains every balance observation. Its test JVM has a 4 GiB
heap so slower cloud builds do not exhaust memory while preserving all evidence;
this is test-only and does not increase the web container's memory allocation.

Bootstrap the app to obtain Azure's assigned hostname. This first revision has no
persistent volume and allows only localhost requests; it is not the public demo.
Create a temporary YAML file using the values resolved above:

```bash
ENVIRONMENT_ID=$(az containerapp env show -g ledger -n ledger-private-env --query id -o tsv)
LOGIN_SERVER=$(az acr show -n "$ACR_NAME" --query loginServer -o tsv)
cat > bootstrap.yaml <<EOF
location: swedencentral
identity:
  type: UserAssigned
  userAssignedIdentities:
    "$PULL_IDENTITY_ID": {}
properties:
  managedEnvironmentId: "$ENVIRONMENT_ID"
  configuration:
    activeRevisionsMode: Multiple
    ingress:
      external: true
      allowInsecure: false
      targetPort: 8080
      transport: http
    registries:
      - server: "$LOGIN_SERVER"
        identity: "$PULL_IDENTITY_ID"
  template:
    containers:
      - name: ledger
        image: "$LOGIN_SERVER/ledger-web:$TAG"
        args: ["--host", "0.0.0.0", "--port", "8080"]
        resources:
          cpu: 1.0
          memory: 2Gi
    scale:
      minReplicas: 1
      maxReplicas: 1
EOF
az containerapp create -g ledger -n ledger-demo --yaml bootstrap.yaml -o none
rm bootstrap.yaml
python3 scripts/deploy-container-app.py --registry "$ACR_NAME" \
  --tag "$TAG" --revision "initial-$(date +%s)"
```

On Windows, use `python` in place of `python3` and create the bootstrap YAML with an
editor or a PowerShell here-string. The deployment script supports Windows and
Linux. The script pins every Azure request to `--subscription`, using
`AZURE_SUBSCRIPTION_ID` or the documented subscription by default, so another
CLI session changing the default subscription cannot redirect deployment.
Set `$env:PYTHONUTF8 = '1'` before streaming ACR build logs in PowerShell:
the demo's Unicode audit output otherwise breaks Azure CLI's legacy Windows
encoding. The script resolves Azure resource IDs and the actual app hostname, verifies the
region and persistent storage, drains old revisions, applies
`deploy/container-app.yaml`, and requires verified public HTTPS before success.

## GitHub Actions and Azure OIDC

Create a separate user-assigned managed identity for deployment. It authenticates
with GitHub's short-lived OIDC token; there is no stored Azure client secret.

```bash
CLIENT_ID=$(az identity create -g ledger -n ledger-github-deploy \
  --location swedencentral --query clientId -o tsv)
PRINCIPAL=$(az identity show -g ledger -n ledger-github-deploy --query principalId -o tsv)
TENANT_ID=$(az account show --query tenantId -o tsv)
APP_ID=$(az containerapp show -g ledger -n ledger-demo --query id -o tsv)
az identity federated-credential create -g ledger \
  --identity-name ledger-github-deploy --name github-azure-demo \
  --issuer https://token.actions.githubusercontent.com \
  --subject repo:PaulMaddox/ledger:environment:azure-demo \
  --audiences api://AzureADTokenExchange
az role assignment create --assignee-object-id "$PRINCIPAL" \
  --assignee-principal-type ServicePrincipal --role Contributor --scope "$APP_ID"
az role assignment create --assignee-object-id "$PRINCIPAL" \
  --assignee-principal-type ServicePrincipal --role "Container Apps Operator" \
  --scope "$ENVIRONMENT_ID"
az role assignment create --assignee-object-id "$PRINCIPAL" \
  --assignee-principal-type ServicePrincipal --role "Managed Identity Operator" \
  --scope "$PULL_IDENTITY_ID"
az role assignment create --assignee-object-id "$PRINCIPAL" \
  --assignee-principal-type ServicePrincipal --role Reader --scope "$PULL_IDENTITY_ID"
az role assignment create --assignee-object-id "$PRINCIPAL" \
  --assignee-principal-type ServicePrincipal --role AcrPush --scope "$ACR_ID"
az role assignment create --assignee-object-id "$PRINCIPAL" \
  --assignee-principal-type ServicePrincipal --role Reader --scope "$ACR_ID"
```

The deployment identity cannot create storage, retrieve its account keys, assign
roles or manage AKS. Contributor is scoped to this one app, not the resource group.
Container Apps Operator is scoped only to the environment: it permits reading
storage definitions and joining that environment, not changing its storage.
Only the administrator sets or rotates storage keys.

In GitHub **Settings > Environments**, create `azure-demo` and restrict deployment
branches to **main**. This is essential because the federated credential trusts
the environment rather than a branch. Set these non-secret environment variables:

| Variable | Value |
|---|---|
| `AZURE_CLIENT_ID` | Deployment identity client ID |
| `AZURE_TENANT_ID` | Azure directory ID |
| `AZURE_SUBSCRIPTION_ID` | Subscription above |
| `ACR_NAME` | Registry name without `.azurecr.io` |

```bash
gh variable set AZURE_CLIENT_ID --repo PaulMaddox/ledger --env azure-demo --body "$CLIENT_ID"
gh variable set AZURE_TENANT_ID --repo PaulMaddox/ledger --env azure-demo --body "$TENANT_ID"
gh variable set AZURE_SUBSCRIPTION_ID --repo PaulMaddox/ledger --env azure-demo --body "$SUBSCRIPTION"
gh variable set ACR_NAME --repo PaulMaddox/ledger --env azure-demo --body "$ACR_NAME"
```

After merging, **Deploy web app to Container Apps** runs on pushes to main or with
**Run workflow** on main. It checks the guide, browser regressions and deployment
script tests, builds and tests the image, then deploys. Serialized runs are not
cancelled midway through draining. A failure leaves the app stopped or the failed
revision available for diagnosis; it never starts a competing writer or deletes
history to manufacture success.

Container Apps supplies an `azurecontainerapps.io` hostname and trusted TLS
automatically. No Azure DNS label, certificate email or certificate state volume
is needed. Query the actual URL:

```bash
az containerapp show -g ledger -n ledger-demo \
  --query properties.configuration.ingress.fqdn -o tsv
```

## Storage, updates and recovery

The container runs as UID/GID 10001. `deploy/container-app.yaml` mounts the SMB share
at `/data` with matching ownership and restrictive file permissions. Ledger
journals, fixture state and the **demo** signing key live under `/data/ledger`.
Storage uses local redundancy and Azure-managed encryption at rest in Sweden
Central; SMB is encrypted in transit. No out-of-country replication is configured.
The environment's `ledger-demo-data` storage definition points to a fresh
`ledger-demo-data` share, not the failed old app's `ledger-data` share.
Account-key mounts require administrator rotation in the environment definition;
no key is stored in source, workflow variables or the container image.

Azure Files is networked shared storage, not the former AKS single-node Azure disk.
Keep byte-range locking enabled: **never use `nobrl`**, never mount the same share
in another writer app, and never scale beyond one replica. Journal fsync and the
directory lock are unchanged. Successful startup and replay demonstrate the demo's
filesystem compatibility, not certification of production durability or failover
under arbitrary SMB/network failures.

Multiple revision mode is intentional: single revision mode starts the new revision
before stopping the old one. `scripts/deploy-container-app.py` instead deactivates
every active revision and waits until all old replicas disappear before applying
the new manifest. Min/max replicas are both one. Updates have deliberate downtime;
replay must finish before readiness. Do not use portal rolling updates, traffic
splits, independent deployments or replica scaling to bypass this procedure.

The share survives app revisions and restarts. Deleting the share or storage
account destroys retained data; no deployment command does that. Protect storage,
monitor quota and design in-country backups before relying on retained history.
Administrator reset remains an explicitly destructive demo action.

For diagnostics without exporting files or private keys:

```bash
az containerapp revision list -g ledger -n ledger-demo -o table
az containerapp replica list -g ledger -n ledger-demo --revision <revision>
az containerapp logs show -g ledger -n ledger-demo --type system --tail 50
az containerapp logs show -g ledger -n ledger-demo --container ledger --tail 50
```

Log Analytics is disabled for this demo environment; streaming logs are available.
A failed HTTPS or replay check is a failed deployment. Fix the reported issue;
never weaken verification, clear storage or repeatedly restart until it looks green.
Rollback means deploying a compatible earlier image through the same drain script,
with a new revision suffix, never reverting journal history.

Screenshots are unchanged because deployment does not change the rendered console.
The previously created AKS cluster can still incur charges; deleting it is a
separate owner decision, not part of this deployment.
