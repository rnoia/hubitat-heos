/**
 *  https://raw.githubusercontent.com/dcmeglio/hubitat-heos/master/apps/Denon_HEOS_Integration.groovy
 *
 *  Denon HEOS Integration
 *
 *  Copyright 2020 Dominick Meglio
 *
 * Revision History
 * v 2020.01.02 - Initial Release
 *
 */

definition(
    name: "Denon HEOS Integration",
    namespace: "dcm.heos",
    author: "Dominick Meglio",
    description: "Connects your HEOS speakers to Hubitat",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png")


preferences {
	page(name: "prefAccount", title: "HEOS")
	page(name: "prefListDevices", title: "HEOS")
}

def prefAccount() {
	state.totalTries = 5
	subscribe(location, "ssdpTerm.urn:schemas-denon-com:device:ACT-Denon:1", ssdpHandler)
	discoverHeosDevices()
	
	return dynamicPage(name: "prefAccount", title: "HEOS Account Information", nextPage:"prefListDevices", uninstall:false, install: false) {
		section(""){
			input("heosUsername", "text", title: "HEOS Username", description: "HEOS Account Username")
			input("heosPassword", "password", title: "HEOS Password", description: "HEOS Account Password")
            input("debugOutput", "bool", title: "Enable debug logging?", defaultValue: true, displayDuringSetup: false, required: false)
		}
	}
}

def prefListDevices() {
	verifyHeosDevices()
	discoverHeosDevices()
	return dynamicPage(name: "prefListDevices", title: "Devices", install: true, uninstall: true, refreshInterval: 15) {
		section("Devices") {
			paragraph "Discovering devices. This may take a couple of minutes. If your devices aren't yet shown, please wait and the screen will refresh every 15 seconds as devices are discovered."
			input(name: "speakers", type: "enum", title: "Speakers", required:true, multiple:true, options:getFriendlyDevices())
		}
	}
}

def getFriendlyDevices() {
	def friendlyDevices = [:]

	state.devices.each {
		if (it.value.name != null)
			friendlyDevices[it.key] = it.value.name
	}
	return friendlyDevices
}

def getDevices() {
    if (!state.devices) {
        state.devices = [:]
    }
    return state.devices
}

def discoverHeosDevices() {
	state.totalTries--
	if (state.totalTries > 0) {
		logDebug "Discovering HEOS devices"
		sendHubCommand(new hubitat.device.HubAction("lan discovery urn:schemas-denon-com:device:ACT-Denon:1", hubitat.device.Protocol.LAN))
	}
	else
		logDebug "Finished discovery"
}

def ssdpHandler(evt) {
    def description = evt.description

	def parsedEvent = parseLanMessage(description)
	def port = hubitat.helper.HexUtils.hexStringToInt(parsedEvent.deviceAddress)
	def ip = hubitat.helper.HexUtils.hexStringToIntArray(parsedEvent.networkAddress)
	def path = parsedEvent.ssdpPath
	def ipString = "${ip[0]}.${ip[1]}.${ip[2]}.${ip[3]}"
	
	parsedEvent << ["port": port ]
	parsedEvent << ["ip": ipString ]
	parsedEvent << ["path": path ]

    def devices = getDevices()
    if (!devices."${ipString}") {
        devices << ["${ipString}": parsedEvent]
    }
}

void verifyHeosDevices() {
    def devices = getDevices().findAll { it?.value?.verified != true }

    devices.each {
		def params = [
			uri: "http://${it.value.ip}:${it.value.port}",
			requestContentType: 'application/xml',
			contentType: 'application/xml',
			path: it.value.path
		]
		def ip = it.value.ip
		httpGet(params) { resp ->
			logDebug "verifying ${ip}"
			verifyDeviceCallback(resp, ip)
		}
    }
}

def verifyDeviceCallback(response, ip) {
	def devices = getDevices()
	def name = response.data.device[0].friendlyName.text()

	def device = devices.find { it?.key == ip }

	if (device) {
		device.value << [name: name, verified: true]
	}
}

def distributeMessage(command, payload) {
	logDebug "received message from master: ${payload}"
	
	if (command == "player/get_players") {
		for (deviceDetails in payload) {
			for (device in getChildDevices())
			{
				def deviceIp = device.getDataValue("ip")
				
				if (deviceIp == deviceDetails.ip) {
					device.setPlayerId(deviceDetails.pid)
				}
			}
		}
	}
	else if (command == "event/player_state_changed") {
		def device = findDeviceByPid(payload.pid)
		
		device.sendEvent(name:"status", value: payload.state)
	}
	else if (command == "event/player_volume_changed") {
		def device = findDeviceByPid(payload.pid)

		if (payload.mute == "on") 
			device.sendEvent(name: "mute", value: "muted")
		else if (payload.mute == "off")
			device.sendEvent(name: "mute", value: "unmuted")
		device.sendEvent(name:"volume", value: payload.level.toInteger())
	}
	else if (command == "player/get_now_playing_media") {
		def device = findDeviceByPid(payload.pid)
		
		device.sendEvent(name: "trackDescription", value: "${payload.song} by ${payload.artist} from ${payload.album}")
		device.sendEvent(name: "trackData", value: groovy.json.JsonOutput.toJson(payload))
	}
}

def sendHeosMessage(msg) {
	for (device in getChildDevices())
	{
		def isMaster = device.getDataValue("master")
		if (isMaster == "true") {
			return device.sendHeosMessage(msg)
		}
	}
}

def findDeviceByPid(payloadPid) {
	for (device in getChildDevices())
	{
		def devicePid = device.getDataValue("pid")
		
		if (devicePid == payloadPid)
			return device
	}
	return null
}

def getUsername()
{
	return heosUsername
}

def getPassword()
{
	return heosPassword
}

def installed() {
	logDebug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	logDebug "Updated with settings: ${settings}"
    unschedule()
	unsubscribe()
	initialize()
}

def uninstalled() {
	logDebug "Uninstalled app"
    unschedule()
	unsubscribe()
	for (device in getChildDevices())
	{
		deleteChildDevice(device.deviceNetworkId)
	}	
}

def initialize() {
	logDebug "initializing"

	cleanupChildDevices()
	createChildDevices()
}


def createChildDevices() {
	def i = 0
	for (speaker in speakers) {
		def device = getChildDevice("heos:" + state.devices[speaker].mac)
		if (!device)
            device = addChildDevice("dcm.heos", "Denon HEOS Speaker", "heos:" + state.devices[speaker].mac, 1234, ["name": state.devices[speaker].name, isComponent: false])
		device.setMaster(i == 0)
		device.setDeviceDetails(state.devices[speaker].ip, 1255)
		device.initialize()
		i++
	}
}

def cleanupChildDevices()
{
	for (device in getChildDevices())
	{
		def deviceId = device.deviceNetworkId.replace("heos:","")
		
		def deviceFound = false
		for (speaker in speakers)
		{
			if (state.devices[speaker].mac == deviceId)
			{
				deviceFound = true
				break
			}
		}
				
		if (deviceFound == true)
			continue
			
		deleteChildDevice(device.deviceNetworkId)
	}
}

def notifyMasterRemoved(deviceRemoved) 
{
	for (device in getChildDevices())
	{
		if (device != deviceRemoved)
		{
			device.setMaster(true)
			device.initialize()
			break
		}
	}
}

def logDebug(msg) {
    if (settings?.debugOutput) {
		log.debug msg
	}
}