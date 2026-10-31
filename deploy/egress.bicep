targetScope = 'resourceGroup'

@description('Existing Sweden Central VNet containing the Container Apps subnet.')
param vnetName string = 'ledger-app-vnet'

@description('Private IPv4 address of the Azure Files endpoint.')
param filesPrivateIp string

@minLength(1)
@description('Private IPv4 addresses for both registry login and regional image data.')
param registryPrivateIps array

var location = 'swedencentral'
var appSubnetPrefix = '10.80.0.0/23'
var rules = [
  {
    name: 'AllowSubnetInternal'
    priority: 100
    protocol: '*'
    destination: appSubnetPrefix
    port: '*'
  }
  {
    name: 'AllowPrivateFiles'
    priority: 120
    protocol: 'Tcp'
    destination: filesPrivateIp
    port: '445'
  }
  {
    name: 'AllowAzureDNS'
    priority: 130
    protocol: '*'
    destination: '168.63.129.16'
    port: '53'
  }
  {
    name: 'AllowPlatformImages'
    priority: 140
    protocol: 'Tcp'
    destination: 'MicrosoftContainerRegistry'
    port: '443'
  }
  {
    name: 'AllowPlatformImageCDN'
    priority: 150
    protocol: 'Tcp'
    destination: 'AzureFrontDoor.FirstParty'
    port: '443'
  }
  {
    name: 'AllowManagedIdentity'
    priority: 160
    protocol: 'Tcp'
    destination: 'AzureActiveDirectory'
    port: '443'
  }
]

resource outboundIp 'Microsoft.Network/publicIPAddresses@2024-05-01' = {
  name: 'ledger-platform-egress-ip'
  location: location
  sku: { name: 'Standard' }
  properties: {
    publicIPAllocationMethod: 'Static'
  }
}

resource nat 'Microsoft.Network/natGateways@2024-05-01' = {
  name: 'ledger-platform-egress'
  location: location
  sku: { name: 'Standard' }
  properties: {
    idleTimeoutInMinutes: 4
    publicIpAddresses: [{ id: outboundIp.id }]
  }
}

resource egress 'Microsoft.Network/networkSecurityGroups@2024-05-01' = {
  name: 'ledger-app-egress'
  location: location
  properties: {
    securityRules: concat(allowRules, [
      {
        name: 'AllowPrivateRegistry'
        properties: {
          priority: 110
          direction: 'Outbound'
          access: 'Allow'
          protocol: 'Tcp'
          sourceAddressPrefix: appSubnetPrefix
          sourcePortRange: '*'
          destinationAddressPrefixes: registryPrivateIps
          destinationPortRange: '443'
        }
      }
      {
        name: 'DenyAllOtherOutbound'
        properties: {
          priority: 4096
          direction: 'Outbound'
          access: 'Deny'
          protocol: '*'
          sourceAddressPrefix: '*'
          sourcePortRange: '*'
          destinationAddressPrefix: '*'
          destinationPortRange: '*'
        }
      }
    ])
  }
}

var allowRules = [for rule in rules: {
  name: rule.name
  properties: {
    priority: rule.priority
    direction: 'Outbound'
    access: 'Allow'
    protocol: rule.protocol
    sourceAddressPrefix: appSubnetPrefix
    sourcePortRange: '*'
    destinationAddressPrefix: rule.destination
    destinationPortRange: rule.port
  }
}]

resource vnet 'Microsoft.Network/virtualNetworks@2024-05-01' existing = {
  name: vnetName
}

resource appSubnet 'Microsoft.Network/virtualNetworks/subnets@2024-05-01' = {
  parent: vnet
  name: 'container-apps'
  properties: {
    addressPrefix: appSubnetPrefix
    defaultOutboundAccess: false
    delegations: [{
      name: 'container-apps'
      properties: { serviceName: 'Microsoft.App/environments' }
    }]
    networkSecurityGroup: { id: egress.id }
    natGateway: { id: nat.id }
  }
}

output subnetId string = appSubnet.id
output networkSecurityGroupId string = egress.id
output natGatewayId string = nat.id
