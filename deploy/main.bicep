targetScope = 'resourceGroup'

@description('Existing AKS cluster in this resource group. The cluster is never modified.')
param clusterName string = 'ledger'

@description('Globally unique name for a new, dedicated container registry.')
@minLength(5)
@maxLength(50)
param registryName string = 'ledger${uniqueString(resourceGroup().id)}'

@description('GitHub repository trusted to deploy through the azure-demo environment.')
param githubRepository string = 'PaulMaddox/ledger'

@description('Keep demo resources in the same in-country region as the existing cluster.')
@allowed([
  'swedencentral'
])
param location string = 'swedencentral'

var namespaceName = 'ledger-web'
var identityName = 'ledger-github-deploy'

resource cluster 'Microsoft.ContainerService/managedClusters@2024-10-01' existing = {
  name: clusterName
}

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: registryName
  location: location
  sku: {
    name: 'Basic'
  }
  properties: {
    adminUserEnabled: false
    publicNetworkAccess: 'Enabled'
  }
}

resource deployIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: identityName
  location: location
}

resource githubTrust 'Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials@2023-01-31' = {
  parent: deployIdentity
  name: 'github-azure-demo'
  properties: {
    issuer: 'https://token.actions.githubusercontent.com'
    subject: 'repo:${githubRepository}:environment:azure-demo'
    audiences: [
      'api://AzureADTokenExchange'
    ]
  }
}

resource imagePush 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(registry.id, deployIdentity.id, 'AcrPush')
  scope: registry
  properties: {
    principalId: deployIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '8311e382-0749-4cb8-b61a-304f252e45ec')
  }
}

resource registryRead 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(registry.id, deployIdentity.id, 'Reader')
  scope: registry
  properties: {
    principalId: deployIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'acdd72a7-3385-48ef-bd42-f606fba81ae7')
  }
}

resource imagePull 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(registry.id, cluster.id, 'AcrPull')
  scope: registry
  properties: {
    principalId: cluster.properties.identityProfile.kubeletidentity.objectId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '7f951dda-4ed3-4680-a7ca-43fe172d538d')
  }
}

resource clusterCredentials 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(cluster.id, deployIdentity.id, 'ClusterUser')
  scope: cluster
  properties: {
    principalId: deployIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '4abbcc35-e782-43d8-92c5-2d3f1bd2253f')
  }
}

// This existing resource only constructs the namespace RBAC scope; ARM does not
// create the Kubernetes namespace. Create it once in the AKS portal before deploying.
#disable-next-line BCP081
resource namespace 'Microsoft.ContainerService/managedClusters/namespaces@2025-05-01' existing = {
  parent: cluster
  name: namespaceName
}

resource namespaceWriter 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(cluster.id, namespaceName, deployIdentity.id, 'NamespaceWriter')
  scope: namespace
  properties: {
    principalId: deployIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'a7ffa36f-339b-4b5c-8bdf-e2c188b2c0eb')
  }
}

output AZURE_CLIENT_ID string = deployIdentity.properties.clientId
output AZURE_TENANT_ID string = tenant().tenantId
output AZURE_SUBSCRIPTION_ID string = subscription().subscriptionId
output AKS_RESOURCE_GROUP string = resourceGroup().name
output AKS_CLUSTER_NAME string = cluster.name
output ACR_NAME string = registry.name
