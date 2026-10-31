targetScope = 'resourceGroup'

@description('Globally unique Premium registry name, in legacy registry permissions mode.')
param registryName string

@description('Globally unique storage account name.')
param storageAccountName string

param githubRepository string = 'PaulMaddox/ledger'
param environmentName string = 'ledger-private-env'
param vnetName string = 'ledger-app-vnet'

var location = 'swedencentral'
var readerRole = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'acdd72a7-3385-48ef-bd42-f606fba81ae7')
var acrPullRole = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '7f951dda-4ed3-4680-a7ca-43fe172d538d')
var acrPushRole = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '8311e382-0749-4cb8-b61a-304f252e45ec')
var identityOperatorRole = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'f1a07417-d97a-45cb-824c-7a7467783830')
var environmentOperatorRole = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'f3bd1b5c-91fa-40e7-afe7-0c11d331232c')

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: registryName
  location: location
  sku: { name: 'Premium' }
  properties: {
    adminUserEnabled: false
    publicNetworkAccess: 'Enabled'
  }
}

resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: storageAccountName
  location: location
  kind: 'StorageV2'
  sku: { name: 'Standard_LRS' }
  properties: {
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    allowBlobPublicAccess: false
    allowSharedKeyAccess: false
    publicNetworkAccess: 'Disabled'
  }
}

resource fileService 'Microsoft.Storage/storageAccounts/fileServices@2023-05-01' = {
  parent: storage
  name: 'default'
}

resource share 'Microsoft.Storage/storageAccounts/fileServices/shares@2023-05-01' = {
  parent: fileService
  name: 'ledger-demo-data'
  properties: {
    enabledProtocols: 'SMB'
    shareQuota: 32
  }
}

resource vnet 'Microsoft.Network/virtualNetworks@2024-05-01' = {
  name: vnetName
  location: location
  properties: {
    addressSpace: { addressPrefixes: ['10.80.0.0/16'] }
    subnets: [
      {
        name: 'container-apps'
        properties: {
          addressPrefix: '10.80.0.0/23'
          defaultOutboundAccess: false
          delegations: [{
            name: 'container-apps'
            properties: { serviceName: 'Microsoft.App/environments' }
          }]
        }
      }
      {
        name: 'private-endpoints'
        properties: {
          addressPrefix: '10.80.2.0/27'
          privateEndpointNetworkPolicies: 'Disabled'
        }
      }
    ]
  }
}

resource filesEndpoint 'Microsoft.Network/privateEndpoints@2024-05-01' = {
  name: 'ledger-files-pe'
  location: location
  properties: {
    subnet: { id: '${vnet.id}/subnets/private-endpoints' }
    privateLinkServiceConnections: [{
      name: 'ledger-files'
      properties: {
        privateLinkServiceId: storage.id
        groupIds: ['file']
      }
    }]
    ipConfigurations: [{
      name: 'files'
      properties: {
        groupId: 'file'
        memberName: 'file'
        privateIPAddress: '10.80.2.4'
      }
    }]
  }
}

resource registryEndpoint 'Microsoft.Network/privateEndpoints@2024-05-01' = {
  name: 'ledger-acr-pe'
  location: location
  properties: {
    subnet: { id: '${vnet.id}/subnets/private-endpoints' }
    privateLinkServiceConnections: [{
      name: 'ledger-registry'
      properties: {
        privateLinkServiceId: registry.id
        groupIds: ['registry']
      }
    }]
    ipConfigurations: [
      {
        name: 'regional-data'
        properties: {
          groupId: 'registry'
          memberName: 'registry_data_swedencentral'
          privateIPAddress: '10.80.2.5'
        }
      }
      {
        name: 'login'
        properties: {
          groupId: 'registry'
          memberName: 'registry'
          privateIPAddress: '10.80.2.6'
        }
      }
    ]
  }
}

resource filesDns 'Microsoft.Network/privateDnsZones@2024-06-01' = {
  name: 'privatelink.file.core.windows.net'
  location: 'global'
}

resource registryDns 'Microsoft.Network/privateDnsZones@2024-06-01' = {
  name: 'privatelink.azurecr.io'
  location: 'global'
}

resource filesLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2024-06-01' = {
  parent: filesDns
  name: vnetName
  location: 'global'
  properties: {
    virtualNetwork: { id: vnet.id }
    registrationEnabled: false
  }
}

resource registryLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2024-06-01' = {
  parent: registryDns
  name: vnetName
  location: 'global'
  properties: {
    virtualNetwork: { id: vnet.id }
    registrationEnabled: false
  }
}

resource filesZoneGroup 'Microsoft.Network/privateEndpoints/privateDnsZoneGroups@2024-05-01' = {
  parent: filesEndpoint
  name: 'default'
  properties: {
    privateDnsZoneConfigs: [{ name: 'files', properties: { privateDnsZoneId: filesDns.id } }]
  }
}

resource registryZoneGroup 'Microsoft.Network/privateEndpoints/privateDnsZoneGroups@2024-05-01' = {
  parent: registryEndpoint
  name: 'default'
  properties: {
    privateDnsZoneConfigs: [{ name: 'registry', properties: { privateDnsZoneId: registryDns.id } }]
  }
}

module egress 'egress.bicep' = {
  name: 'ledger-egress'
  params: {
    vnetName: vnetName
    filesPrivateIp: '10.80.2.4'
    registryPrivateIps: ['10.80.2.5', '10.80.2.6']
  }
  dependsOn: [vnet]
}

resource environment 'Microsoft.App/managedEnvironments@2025-07-01' = {
  name: environmentName
  location: location
  properties: {
    vnetConfiguration: {
      infrastructureSubnetId: egress.outputs.subnetId
      internal: false
    }
    publicNetworkAccess: 'Enabled'
    appLogsConfiguration: { destination: null }
    workloadProfiles: [{ name: 'Consumption', workloadProfileType: 'Consumption' }]
  }
  dependsOn: [filesLink, registryLink, filesZoneGroup, registryZoneGroup]
}

resource mount 'Microsoft.App/managedEnvironments/storages@2025-07-01' = {
  parent: environment
  name: 'ledger-demo-data'
  properties: {
    azureFile: {
      accountName: storage.name
      accountKey: storage.listKeys().keys[0].value
      shareName: share.name
      accessMode: 'ReadWrite'
    }
  }
}

resource pullIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'ledger-image-pull'
  location: location
}

resource deployIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'ledger-github-deploy'
  location: location
}

resource federation 'Microsoft.ManagedIdentity/userAssignedIdentities/federatedIdentityCredentials@2023-01-31' = {
  parent: deployIdentity
  name: 'github-azure-demo'
  properties: {
    issuer: 'https://token.actions.githubusercontent.com'
    subject: 'repo:${githubRepository}:environment:azure-demo'
    audiences: ['api://AzureADTokenExchange']
  }
}

resource pullRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(registry.id, pullIdentity.id, acrPullRole)
  scope: registry
  properties: {
    principalId: pullIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: acrPullRole
  }
}

resource deployRegistryRoles 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for role in [readerRole, acrPushRole]: {
  name: guid(registry.id, deployIdentity.id, role)
  scope: registry
  properties: {
    principalId: deployIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: role
  }
}]

resource deployIdentityRoles 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for role in [readerRole, identityOperatorRole]: {
  name: guid(pullIdentity.id, deployIdentity.id, role)
  scope: pullIdentity
  properties: {
    principalId: deployIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: role
  }
}]

resource deployEnvironmentRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(environment.id, deployIdentity.id, environmentOperatorRole)
  scope: environment
  properties: {
    principalId: deployIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: environmentOperatorRole
  }
}

output registryLoginServer string = registry.properties.loginServer
output environmentId string = environment.id
output pullIdentityId string = pullIdentity.id
output deployClientId string = deployIdentity.properties.clientId
output tenantId string = tenant().tenantId
output subscriptionId string = subscription().subscriptionId
