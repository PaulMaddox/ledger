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

CI runs the full Kotlin suite once, including race and stress tests, plus audits
and guide checks. Deployment packages the tested commit without repeating them.

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
- Ledger health probes require a successful loopback HTTP response within **three
  seconds**, not just TCP acceptance. Startup and liveness use `/health/live`,
  which runs on the normal HTTP executor but bypasses API admission and service
  locks. Readiness uses `/`, so saturated request slots remove the pod from service
  without restarting it. Six liveness failures, ten seconds apart, restart the
  container with its existing disk. Startup allows ten minutes for replay; the
  proxy has independent TCP listener checks. The web runtime image explicitly
  installs `curl` for these probes; do not assume it comes with the JRE base image.
  Daily retention still applies.
- Keep data and keys in-country. Do not export plaintext data or keys, or configure
  out-of-country backups.
- Keep `--public-origin` set to the exact public HTTPS origin. Preserve Host,
  Origin and Referer headers; do not rewrite them to bypass request checks.
- Demo history resets daily at **03:00 UAE time**. Simulator runs expire after
  **15 minutes**; services stop at a **12 GB** storage high-water mark until reset.
- The ledger container requests **4 GiB** and permits **6 GiB** of memory.
  `JAVA_TOOL_OPTIONS=-Xmx4g` gives the JVM a **4 GiB heap**, leaving **2 GiB**
  within the limit for native memory, thread stacks and filesystem cache.
  The local Gradle `web` task uses the same heap maximum; leave additional host
  memory for Gradle and the operating system. Ensure the AKS node has room for
  the larger request plus the HTTPS sidecar before deploying.
  This is capacity headroom, not a target live-set size. History and derived
  indexes are on disk, with bounded caches and a shared run-filter budget;
  balances and open bets still occupy heap. The disk high-water mark is not a
  heap guard. Independent audits retain only
  historical hashes, exact account state and idempotency keys, rather than a
  second full decoded journal; audit and snapshot-tamper requests run one at a
  time per app. Large reports and tamper copies still need extra working memory.
- The fixture scheduler runs independently of the simulator, keeping 20 fictional
  multi-sport markets live. Operator health shows its last tick and any pause reason.
  Active definitions are in `demo-fixtures.properties`; completed definitions append
  to the checksum-framed `demo-fixtures.archive`. Preserve both for restart and reports.
  An incomplete archive suffix is retained as `demo-fixtures.archive.torn`; a corrupt
  complete record stops recovery rather than silently discarding metadata. Only
  unsigned active-catalogue replacement retries transient Windows access denials;
  ledger journal writes never retry.
  An uncertain archive append blocks further catalogue writes until restart validates
  the archive. Surplus legacy markets remain unmanaged and available for manual
  operator settlement without losing their bets; the simulator only bets on managed markets.

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

`Java heap space` followed by `LedgerFailedException` means the writer has
failed-stopped, not that settlement may safely retry writes. Record entry count,
heap used/committed/maximum, after-GC occupancy and collection age, GC time and
container memory from Administrator telemetry. **Used** includes objects waiting
for collection. **Committed** is capacity held by the JVM, not live data or total
container memory; it can remain high after objects are collected. **After GC**
sums heap pools in the latest completed collector snapshot (excluding non-heap
pools). Its age and collector name matter: a young collection can leave old-generation
garbage, so this is not necessarily the full live set. Missing or unsupported
measurements show as unavailable, not zero. Telemetry never triggers GC or changes
JVM settings. JVM uptime and collection age are displayed in days/hours/minutes/seconds.
A restart must replay and verify the existing disk and needs working memory for
that replay. Do not delete journals or signing keys to recover. The owner-confirmed
Administrator reset remains a demo-only option.

Before the on-disk history migration, resident entries shared repeated strings
through a ledger-scoped pool capped at 65,536 strings of at most 256 characters.
Only recovery and the single writer
use it; unique business keys and reference values are not pooled. Canonical bytes
and replay rules are unchanged. Player histories use primitive sequence chunks.
The read model retains complete placement/settlement references but caches decoded
views only for open bets; historical views reconstruct from committed entries.
Customer history queries copy only their requested tail and report the complete
count from the same indexed prefix. Reports aggregate fixtures from compact entry
references instead of retaining another decoded bet graph and grouped lists;
their drill-down sequences use primitive snapshots. These reduce retention and
allocation, not the growth of the authoritative ledger. All reports still use a
single signed prefix and exact amounts, including cross-period settlements.

A historical local JDK 27 comparison of that resident implementation using 300,000
deterministic deposit/place/loss entries across 500 players reduced post-GC
ledger/read-model heap from 479.6 MiB to
349.8 MiB (27.1%) with these optimizations. Report allocation fell 35.2%; the
chain head, Merkle root and serialized financial/customer/fixture report sections
were identical. Savings depend on the workload and do not make history bounded.

If TCP connects but HTTP stalls, capture a JVM virtual-thread dump before restarting.
Bet registration uses a parking-safe `ReentrantLock`: queue admission can wait for
ledger capacity, so it must not hold `ConcurrentHashMap`'s update lock while waiting.

For Azure CLI diagnostics across directories, select the cluster's tenant with
`kubelogin convert-kubeconfig -l azurecli --tenant-id <cluster-tenant-id>`.
Check existing roles before requesting new access; never enable local admin accounts
as an authentication workaround.

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
