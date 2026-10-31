targetScope = 'resourceGroup'

@description('Image already built and verified in the registry. Initial setup only.')
param imageTag string
param registryName string
param environmentName string = 'ledger-private-env'
param appName string = 'ledger-demo'

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' existing = {
  name: registryName
}

resource environment 'Microsoft.App/managedEnvironments@2025-07-01' existing = {
  name: environmentName
}

resource pullIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' existing = {
  name: 'ledger-image-pull'
}

resource deployIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' existing = {
  name: 'ledger-github-deploy'
}

// Azure allocates the hostname before the persistent revision can pin its origin.
resource app 'Microsoft.App/containerApps@2025-07-01' = {
  name: appName
  location: 'swedencentral'
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: { '${pullIdentity.id}': {} }
  }
  properties: {
    managedEnvironmentId: environment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Multiple'
      ingress: {
        external: true
        allowInsecure: false
        targetPort: 8080
        transport: 'http'
      }
      registries: [{
        server: registry.properties.loginServer
        identity: pullIdentity.id
      }]
    }
    template: {
      containers: [{
        name: 'ledger'
        image: '${registry.properties.loginServer}/ledger-web:${imageTag}'
        args: ['--host', '0.0.0.0', '--port', '8080']
        resources: { cpu: 1, memory: '2Gi' }
      }]
      scale: { minReplicas: 1, maxReplicas: 1 }
    }
  }
}

var contributorRole = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', 'b24988ac-6180-42a0-ab88-20f7382dd24c')

resource deployAppRole 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(app.id, deployIdentity.id, contributorRole)
  scope: app
  properties: {
    principalId: deployIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: contributorRole
  }
}

output publicHost string = app.properties.configuration.ingress.fqdn
