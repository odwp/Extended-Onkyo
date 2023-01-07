 /*
    Copyright © 2020 Steve Vibert (@SteveV)

    Portions of this code are based on Mike Maxwell's onkyoIP device handler for SmartThings
    taken from this post: https://community.smartthings.com/t/itach-integration/25470/23
    
    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

    Onkyo eISCP Protocol Specifications Documents which include zone specific commands can 
    be found at: https://github.com/stevevib/Hubitat/Devices/OnkyoMultiZoneAVR/Docs/


    Version History:
    ================

    Date            Version             By                  Changes
    --------------------------------------------------------------------------------
    2020-12-14      0.9.201214.1        Steve Vibert        Initial Beta Release
    2021-03-20      0.9.210320.1        Steve Vibert        Fix: text logging settings being ignored
    2022-10-01      0.10.221001.0       Wayne Pirtle        Added: -functionaity to support controlling 
                                                                    Network Services
                                                                   -Autodiscovery of receivers and zones
     2023-01-02      0.11.220102.0       Wayne Pirtle        Added: Auto Discovery on installation
                                                                  


    WARNING!
        In addition to controlling basic receiver functionality, this driver also includes 
        the ability to set volume levels and other settings using raw eISCP commands. Some 
        commands such as volume level, allow you to enter a value from a given min/max value 
        range. Randomly trying these commands without fully understanding these values may 
        lead to unintended consequences that could damage your receiver and/or your speakers.

        Please make sure you read *and understand* the eISCP protocal documents before trying 
        a command to see what it does.   
*/

import groovy.transform.Field

metadata 
{
	definition (name: "Onkyo Multi-Zone AVR Parent", namespace: "SteveV", author: "Steve Vibert")
	{
		capability "Initialize"
		capability "Telnet"

        command "refresh"
        command "findZones"

    // New attributes to support Netowrk Services	
		attribute "Artist", "string";
		attribute "Album", "string";
		attribute "TrackName", "string";
		attribute "TrackInfo", "string";
		attribute "TrackTimeInfo", "string";
		attribute "PlayStatus", "string";
		attribute "RepeatStatus", "string";
		attribute "ShuffleStatus", "string";
		attribute "NetworkSource", "string";
		attribute "JacketArtURL", "string";
		
	}

    preferences 
	{   
		input name: "onkyoIP", type: "text", title: "Onkyo IP", required: true, displayDuringSetup: true
		input name: "eISCPPort", type: "number", title: "EISCP Port", defaultValue: 60128, required: true, displayDuringSetup: true
		input name: "eISCPTermination", type: "enum", options: [[1:"CR"],[2:"LF"],[3:"CRLF"],[4:"EOF"]], defaultValue:1, title: "EISCP Termination Option", required: true, displayDuringSetup: true, description: "Most receivers should work with CR termination"
		input name: "eISCPVolumeRange", type: "enum", options: [[50:"0-50 (0x00-0x32)"],[80:"0-80 (0x00-0x50)"],[100:"0-100 (0x00-0x64)"],[200:"0-100 Half Step (0x00-0xC8)"]],defaultValue:100, title: "Supported Volume Range", required: true, displayDuringSetup: true, description:"(see Onkyo EISCP Protocol doc for model specific values)"
        input name: "enabledReceiverZones", type: "enum", title: "Enabled Zones", required: true, multiple: true, options: [[1: "Main"],[2:"Zone 2"],[3:"Zone 3"],[4: "Zone 4"]]
 		input name: "textLogging",  type: "bool", title: "Enable description text logging ", required: true, defaultValue: true
        input name: "debugOutput", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}



//	Command Map Keys
@Field static Map zone1CmdPrefixes = ["Power":"PWR", "Mute":"AMT", "Volume":"MVL", "Input":"SLI", "Net Op":"NTC"]
@Field static Map zone2CmdPrefixes = ["Power":"ZPW", "Mute":"ZMT", "Volume":"ZVL", "Input":"SLZ", "Net Op":"NTZ"]
@Field static Map zone3CmdPrefixes = ["Power":"PW3", "Mute":"MT3", "Volume":"VL3", "Input":"SL3", "Net Op":"NT3"]
@Field static Map zone4CmdPrefixes = ["Power":"PW4", "Mute":"MT4", "Volume":"VL4", "Input":"SL4", "Net Op":"NT4"]
@Field static Map zoneCmdPrefixes = [:]

@Field static List zoneNames = ["N/A", "Main", "Zone 2", "Zone 3", "Zone 4" ]
@Field static Map zoneNumbers = ["Main":1, "Zone 2":2, "Zone 3":3, "Zone 4":4 ] 

//New Maps to support Network Services
@Field static Map playStates = ["P":"Play", "S":"Stop", "p":"Pause", "FF":"FFwd", "R":"FRew", "E":"EOF"]
@Field static Map repeatStates = ["-":"Off", "R":"All", "F":"Folder", "1":"Repeat 1", "x":"disabled"]
@Field static Map shuffleStates = ["-":"Off", "S":"All", "F":"Folder", "A":"Album", "x":"disabled"]
@Field static Map networkSources = ["00":"Music Server (DLNA)", "01":"My Favorite", "02":"vTuner", "03":"SiriusXM", "04":"Pandora", "05":"Rhapsody", "06":"Last.fm", "07":"Napster", "08":"Slacker", "09":"Mediafly", "0A":"Spotify", "0B":"AUPEO!", "0C":"radiko", "0D":"e-onkyo", "0E":"TuneIn", "0F":"MP3tunes", "10":"Simfy", "11":"Home Media", "12":"Deezer", "13":"iHeartRadio", "18":"Airplay", “1A”: "onkyo Music", “1B”:"TIDAL", “41”:"FireConnect", "44":"AirPlay 2", "F0": "USB/USB(Front)", "F1": "USB(Rear)", "F2":"Internet Radio", "F3":"NET", "F4":"Bluetooth"]

def getVersion()
{
    return "0.10.221001.0"
}

void parse(String description) 
{
    writeLogDebug("parse ${description}")
    handleReceiverResponse(description)
}

def installed()
{
	log.warn "Searching the network for an eISCP receiver..."
    findReceiver()
    log.warn "Begin initialization..."
    initialize()
    log.warn "Searching the zones supported by this receiver..."
    findZones()
    log.warn "${device.getName()} installed..."

}

void findReceiver ()
{
/* 
    This line finds the /24 base, aka class C subnet, of the hub the driver is running on.  
    example: the hub's IP is 10.10.7.25  the /24 base of the address is 10.10.7

*/
    log.debug "Hub IP location.hub: ${location.hub.localIP}"
    def classCSegment = location.hub.localIP.substring(0, location.hub.localIP.indexOf('.',location.hub.localIP.indexOf('.', location.hub.localIP.indexOf('.')+1) +1))
    log.debug "Class C segment: ${classCSegment}"
/*
    The ISCP auto detect protocol sends a UDP broadcast message to request devices to respond.
*/

    def broadcastIpPort = classCSegment.concat(".255:60128")
    
    log.debug "Broadcast address and port: ${broadcastIpPort}"
    
    String eISCPmessage = getEiscpMessage("ECNQSTN")
    eISCPmessage = eISCPmessage.replace("!1","!x")
    
    log.debug "getEiscpMessage:  ${eISCPmessage}"
    
    def broadcastMsg = new hubitat.device.HubAction(
        "${eISCPmessage}", 
        hubitat.device.Protocol.LAN, [
            type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
            destinationAddress: broadcastIpPort,
            ignoreResponse: false,
            parseWarning: true,
            timeout: 10,
		    callback: parseUDP
            ]
    )
    sendHubCommand(broadcastMsg)

    log.debug "UDP auto discovery message sent: ${broadcastMessage}"

/*  
    Give the receiver time to respond. We have to get the reciever's response, otherwise the 
    remaining processing will go ahead without the information it needs.

    This is a 10 second pause.
*/    
    pauseExecution(10000)
}

void parseUDP(message)
{

/*
    This is only used during the auto discovery process.  The auto discovery process send a 
    UDP broadcast message requesting ISCP devices to respond. This will only parse the message 
    from the first ISCP device to respond.  Hubitat will close the UDP port opened for this 
    message after it receives one response or times out.  This makes discovering more than 
    one ISCP reciever complicated and may require hadlng in an app, hence multi-device 
    discovery has not been implemented. 
*/
    String msgIndex
    String msgMac
    String msgIp
    String msgPort
    String msgType
    String msgPayload
    String remainingMsg
    String msgModel
    String msgRegion
    int colonIndex
    def messageMap = [:]

    log.debug "complete message --> ${message}"
/*
    Begin parsing the UDP packet wrapper.
*/
//  Find and skip the packet index.
    
    colonIndex = message.indexOf(":")
    msgIndex = message.substring(colonIndex+1, colonIndex+2)
    remainingMsg = message.substring(colonIndex+4)

// Find the receiver MAC address from the packet wrapper.
    
    colonIndex = remainingMsg.indexOf(":")
    msgMac = remainingMsg.substring(colonIndex+1, colonIndex+13)
    remainingMsg = remainingMsg.substring(colonIndex+14)

/*  
    Find the receiver IP address.  Having this and the eISCP TCP port makes 
    the auto installation possible.
*/
    colonIndex = remainingMsg.indexOf(":")
    msgIp = convertHexToIP(remainingMsg.substring(colonIndex+1, colonIndex+9))
    remainingMsg = remainingMsg.substring(colonIndex+10)
    state.onkyoIP = msgIp
    device.updateSetting("onkyoIP", state.onkyoIP)

/*  
    Find and skip the port the UDP packet came from.  It may be 
    different than the TCP port.
*/
    colonIndex = remainingMsg.indexOf(":")
//    msgPort = hubitat.helper.HexUtils.hexStringToInt(remainingMsg.substring(colonIndex+1, colonIndex+5))
    remainingMsg = remainingMsg.substring(colonIndex+6)

    //   Find and skip the packet message type.  It should always be LAN_TYPE_UDPCLIENT.
    colonIndex = remainingMsg.indexOf(":")
//    msgType = remainingMsg.substring(colonIndex+1, remainingMsg.indexOf(","))
    remainingMsg = remainingMsg.substring(remainingMsg.indexOf(",")+1)

//  Sparate out the message payload and decode it from hex string to an ASCII string.
    colonIndex = remainingMsg.indexOf(":")
    msgPayload = unhexify(remainingMsg.substring(colonIndex+1))
    
    String msgRemainder

/*
    Begin parsing the Auto Detect response message (payload) from the receiver.

    The response to the Auto Detect broadcast message returns the following information:
        receiver Model
        Port the receiver is listening on for eISCP commands
        Region this receiver was manufactured for
        receiver's MAC address

*/
    String[] remainderParts = msgPayload.split("/")
        
    //  Verify this is a response to the auto discovery message.
    
    if (remainderParts[0].contains("!1ECN"))

    //  Get the receiver model.
        state.onkyoModel = remainderParts[0].substring(18)

    //Get the eISCP TCP port the receiver is listening for commands on.
    
        state.eISCPPort = remainderParts[1]
        settings.eISCPPort = state.eISCPPort
/*
        Identify the region this receiver was built for.
        This will change some of the features available.
*/           
    switch (remainderParts[2])
        {
            case "DX":
                state.destinationArea = "North American model"
                break
            case "XX":
                state.destinationArea = "European or Asian model"
                break
            case "JJ":
                state.destinationArea = "Japanese model"
                break
        }
//  This is a second location for the reciever MCA address.    
    state.onkyoMAC = remainderParts[3].substring(0,12)
   
}

private String convertHexToIP(String hex) {
    
//This function converts an IP address in hex string notation to dotted decimal format.
    
    return [hubitat.helper.HexUtils.hexStringToInt(hex.substring(0,2)),hubitat.helper.HexUtils.hexStringToInt(hex.substring(2,4)),hubitat.helper.HexUtils.hexStringToInt(hex.substring(4,6)),hubitat.helper.HexUtils.hexStringToInt(hex.substring(6))].join(".")
}

private String unhexify(String hexStr) {

//This function converts a hex string to an ASCII string.    
    
  StringBuilder output = new StringBuilder("");

  for (int i = 0; i < hexStr.length(); i += 2) {
    String str = hexStr.substring(i, i + 2);
    output.append((char) Integer.parseInt(str, 16));
  }

  return output.toString();
}

def findZones()
{
/*
    Requesting the power state of the four possible zones 
    will automatically create any zones that respond.
*/
    writeLogInfo("Looking for Zone: Main.") 
    sendTelnetMsg(getEiscpMessage("PWRQSTN"))
    writeLogInfo("Looking for Zone: Zone2.") 
    sendTelnetMsg(getEiscpMessage("ZPWQSTN"))
    writeLogInfo("Looking for Zone: Zone3.")
    sendTelnetMsg(getEiscpMessage("PW3QSTN"))
    writeLogInfo("Looking for Zone: Zone4.") 
    sendTelnetMsg(getEiscpMessage("PW4QSTN"))
}

def handleReceiverResponse(String description) 
{
    String zoneName
    Integer zone
    def cmdMap
    def child
    
    writeLogDebug("handleReceiverResponse ${description}")
	if(!description.startsWith("ISCP"))
		return

	// Find the beginning of the actual response including the start character and Onkyo device
	// type character.  For receivers this will always be !1
	Integer pos = description.indexOf('!')

	if(pos == -1)
		return

	// Strip out the "!1" portion of the response -- we We're only interested in the actual command...
	String data = description.substring(pos + 2).trim()
	writeLogDebug("received ISCP response: ${data}")

    // Split the command into prefix and value parts. The command is always be 3 characters long..
    String cmdPre = data.substring(0, 3)
	String cmdVal = data.substring(3)

    writeLogDebug("parent: cmdPre = ${cmdPre} cmdVal = ${cmdVal}")

/*
        The following are commands for the Network/USB source. 
        Internet radio, USB, AirPlay, Pandora, media server (DLNA), etc 
        are all handled through this set of commands.  The system only 
        supports using one of these at a time accross all zones, hence the 
        management of this source is not handled at the Zone level, hence 
        it's inclusion in the parent driver.  The child driver instances 
        will reference this information from the parent when the zone is 
        set to the NET or USB source.

        zone = 0 is a reference to the Parent Device.  This keeps the routine 
        from sending "not supported by any zones" messages for the Network 
        Sources, since they are handled by the parent.
*/
	   
	switch(cmdPre)
	{
		case "NAT":
			//Artist Name Info
			sendEvent(name: "Artist", value: cmdVal)
			zone = 0
			zoneName = ""
			break
			
		case "NAL":
			//Album Name Info
			sendEvent(name: "Album", value: cmdVal)
			zone = 0
			zoneName = ""
			break
			
		case "NFI":
			//File Format Info
            String[] ffInfo;
            ffInfo = cmdVal.split("/")
       
/*
            This switch statement intentionally does not have "break" 
            statements at the end of each case.  The tests are also listed in 
            descending order to allow the commands that follow to be executed 
            as well without an additional decision (AKA: more complicated "if" 
            statement nesting.) 

            Sometimes only some of the format values are provided.  The case 
            statements are ordered in the least likely to be provided to the 
            most likely.
*/
			switch (ffInfo.size())
                {
                    case {it > 2}:
                        sendEvent(name: "BitRate", value: ffInfo[2])
                    case {it > 1}:
			            sendEvent(name: "SamplingFreq", value: ffInfo[1])
                    case {it > 0}: 
                        sendEvent(name: "Format", value: ffInfo[0])
                }

			zone = 0
			zoneName = ""
			break
			
		case "NJA":
			//Jacket Art
			sendEvent(name: "JacketArtURL", value: cmdVal.substring(2))
			zone = 0
			zoneName = ""
			break
        
		case "NLS":
            //List Info
/*
            This is a possible function to develop in the future.
*/
			zone = 0
			zoneName = ""
			break
			
		case "NMS":
			//Menu Status	
			sendEvent(name: "NetworkSource", value: networkSources[cmdVal.substring(7).toUpperCase()])
			state.NetworkSource = networkSources[cmdVal.substring(7)]
			zone = 0
			zoneName = ""
			break
			
		case "NST":
			//Play Status
			sendEvent(name: "PlayStatus", value: playStates[cmdVal.substring(0,1)])
			sendEvent(name: "RepeatStatus", value: repeatStates[cmdVal.substring(1,2)])
			sendEvent(name: "ShuffleStatus", value: shuffleStates[cmdVal.substring(2)])
			zone = 0
			zoneName = ""
			break
			
		case "NTC":
			//Operation Command
			zone = 0
			zoneName = ""
			break
			
		case "NTI":
			//Title Name
			sendEvent(name: "TrackName", value: cmdVal)
			zone = 0
			zoneName = ""
			break
			
		case "NTM":
			//Time Info
			sendEvent(name: "TrackTimeInfo", value: cmdVal)
			zone = 0
			zoneName = ""
			break
			
		case "NTR":
			//Track Info
			sendEvent(name: "TrackInfo", value: cmdVal)
			zone = 0
			zoneName = ""
			break
        
		case "UPD":
			//AVR firmware update status
            fwUpdStatus = cmdVal.substring(0)
        
            switch(fwUpdStatus)
            {
                case "FF":
                    sendEvent(name: "avrFirmware", value: "Current")
                    break
                case "00":
                    sendEvent(name: "avrFirmware", value: "Update Available")
                    break
                case "01":
                    sendEvent(name: "avrFirmware", value: "Update Available - Normal Notice")  
                //The ISCP documentation does not provide details on Normal vs Forced notice.
                    break
                case "02":
                    sendEvent(name: "avrFirmware", value: "Update Available - Force Notice");
                    break
            }
			
			zone = 0
			zoneName = ""
			break    
    }	

    if(zone != 0)
    {
    // Determine which zone the command belongs to...
        zoneName = getCommandZoneName(cmdPre)
        zone = zoneNumbers[zoneName] ?: -1 as Integer
    }
    
    if(zone == -1)
    {
        writeLogDebug("${cmdPre} not supported by any zones.  Skipping...")
        return;        
    }
    
    writeLogDebug("Forwarding command to ${zoneName} (${zone})")

    // Forward the command to the appropriate zone...
    if(zone > 0 && zoneName.length() > 0)
    {
        child = getChild(zoneName)

        if(child == null)
        {
/*
    If a zone response is received and the child zone hasn't been created, create it.
*/
    
    createChildDevice(zone, zoneName, "Onkyo Multi-Zone AVR Child Zone")

        }
        cmdMap = ["zone":zone, "data":data]
        child = getChild(zoneName)
        child.forwardResponse(cmdMap)
        
    }
}

def initialize()
{
    String ip = settings?.onkyoIP as String
	Integer port = settings?.eISCPPort as Integer
    if (ip == null)
    {
        log.debug "ip from setting is ${settings.onkyoIP}. using the ip from state variable ${state.onkyoIP}"

        ip = state.onkyoIP
    }
	writeLogDebug("ip: ${ip} port: ${port}")

	telnetConnect(ip, port, null, null)
    writeLogDebug("Opening telnet connection with ${ip}:${port}")

	zoneCmdMap = [1:zone1CmdMap, 2:zone2CmdMap, 3:zone3CmdMap, 4:zone4CmdMap]
    zoneCmdPrefixes = [1:zone1CmdPrefixes, 2:zone2CmdPrefixes, 3:zone3CmdPrefixes, 4:zone4CmdPrefixes]

    sendEvent(name: "Artist", value: " ")    
	sendEvent(name: "Album", value: " ")    
    sendEvent(name: "avrFirmware", value: " ")	
	sendEvent(name: "BitRate", value: " ");
	sendEvent(name: "Format", value: " ");
	sendEvent(name: "JacketArtURL", value: " ")    
	sendEvent(name: "NetworkSource", value: " ")    
	sendEvent(name: "PlayStatus", value: " ")    
	sendEvent(name: "RepeatStatus", value: " ")    
	sendEvent(name: "SamplingFreq", value: " ");
	sendEvent(name: "ShuffleStatus", value: " ")    
	sendEvent(name: "TrackInfo", value: " ")    
	sendEvent(name: "TrackName", value: " ")    
	sendEvent(name: "TrackTimeInfo", value: " ")    
    
    
    try 
    {
        childDevices.each { it ->
            it.initialize()
        }
    } 

    catch(e) 
    {
        log.error "initialize caused the following exception: ${e}"
    }
}

void telnetStatus(String message) 
{
	writeLogDebug("${device.getName()} telnetStatus ${message}")
}


def updated()
{
	writeLogInfo("updated...")
    state.version = getVersion()
    unschedule()

	// disable debug logs after 30 min
	if (debugOutput) 
		runIn(1800,logsOff)

    updateChildren()
    //device.updateSetting("enabledReceiverZones",[value:"false",type:"enum"])	
    
    initialize()
}

void refresh()
{
    writeLogDebug("refresh")
}

def logsOff() 
{
    log.warn "${device.getName()} debug logging disabled..."
    device.updateSetting("debugOutput",[value:"false",type:"bool"])	
}

private writeLogDebug(msg) 
{
    if (settings?.debugOutput || settings?.debugOutput == null)
        log.debug "$msg"
}

private writeLogInfo(msg)
{
    if (settings?.textLogging || settings?.textLogging == null)
        log.info "$msg"
}

def updateChildren()
{
    writeLogDebug("updateChildren...")

    try 
    {
        writeLogDebug("enabledReceiverZones: ${enabledReceiverZones}")

        enabledReceiverZones.each { it ->
        
            writeLogDebug("Checking if zone ${it} child exists...")
            Integer childZone = it as Integer
            String childName = zoneNames[childZone]
  
            // Get child device...
            def child = getChild(childName)

            // ...or create it if it doesn't exist
            if(child == null) 
            {
                if (logEnable) 
                    writeLogDebug("Child with id ${childName} does not exist.  Creating...")
                
                def childType = "Onkyo Multi-Zone AVR Child Zone"
                createChildDevice(childZone, childName, childType)
                child = getChild(childName)

                if(child != null)
                {
                    //writeLogDebug("Sending hello message to child...")
                    //child.fromParent ("Hello ${childName}")
                    writeLogDebug("Child with id ${childName} successfully created")
                }

                else
                {
                    writeLogDebug("Unable to create child with id ${childName}")
                }
            }

            else
                writeLogDebug("Found child with id ${childName}.")

        }

        childDevices.each{ it ->
            
            //def childDNI = it.deviceNetworkId.split("-")[-1]
            //writeLogDebug("Sending hello message to child ${childDNI}...")
            //it.fromParent ("Hello ${childDNI}")
        }
    }

    catch(e) 
    {
        log.error "Failed to find child without exception: ${e}"
    }    
}

private def getChild(String zoneName)
{
    //writeLogDebug("getChild with ${zoneName}")
    def child = null
    
    try 
    {
        childDevices.each { it ->
            
            //writeLogDebug("child: ${it.deviceNetworkId}")
            if(it.deviceNetworkId == "${device.deviceNetworkId}-${zoneName}")
            {
                child = it
            }
        }
        
        return child
    } 

    catch(e) 
    {
        log.error "getChild caused the following exception: ${e}"
        return null
    }
}

private void createChildDevice(Integer zone, String zoneName, String type) 
{
    writeLogInfo ("Attempting to create child with zoneName ${zoneName} of type ${type}")
    
    try 
    {
        state.creatingZone = zone
        def child = addChildDevice("${type}", "${device.deviceNetworkId}-${zoneName}",
            [label: "Onkyo AVR ${zoneName}",  isComponent: false, name: "${zoneName}"])
        
        writeLogInfo ("Child device with network id: ${device.deviceNetworkId}-${zoneName} successfully created.")

// Assign the zone number to the child.  The child will use the to filter responses from the AVR

        child.setZone(zone)
    } 

    catch(e) 
    {
        log.error "createChildDevice caused the following exception: ${e}"
    }
}

def childZoneInCreation()
{
    return state.creatingZone as Integer
}

def fromChild(String msg)
{
    writeLogDebug("Received message from child: ${msg}")
}

String getCommandZoneName(String cmdPrefix)
{
    if(zone1CmdPrefixes.containsValue(cmdPrefix))
    {
        writeLogDebug("${cmdPrefix} belongs to Main Zone")
        return zoneNames[1]
    }

    else if(zone2CmdPrefixes.containsValue(cmdPrefix))
    {
        writeLogDebug("${cmdPrefix} belongs to Zone 2")
        return zoneNames[2]
    }

    else if(zone3CmdPrefixes.containsValue(cmdPrefix))
    {
        writeLogDebug("${cmdPrefix} belongs to Zone 3")
        return zoneNames[3]
    }

    else if(zone4CmdPrefixes.containsValue(cmdPrefix))
    {
        writeLogDebug("${cmdPrefix} belongs to Zone 4")
        return zoneNames[4]        
    }

    else
        writeLogDebug("Unable to determine which zone ${cmdPrefix} is for")

}

def sendTelnetMsg(String msg) 
{
    writeLogDebug("Child called sendTelnetMsg with ${msg}")
//    return new hubitat.device.HubAction(msg, hubitat.device.Protocol.TELNET)
      sendHubCommand(new hubitat.device.HubAction(msg, hubitat.device.Protocol.TELNET))    
}
 
def getEiscpMessage(command)
{
    def sb = StringBuilder.newInstance()
    def eiscpDataSize = command.length() + 3   // eISCP data size
    def eiscpMsgSize = eiscpDataSize + 1 + 16  // size of the entire eISCP msg

    // Construct the entire message character by character. 
    //Each char is represented by a 2 digit hex value
    sb.append("ISCP")

    // the following are all in HEX representing one char

    // 4 char Big Endian Header
    sb.append((char)Integer.parseInt("00", 16))
    sb.append((char)Integer.parseInt("00", 16))
    sb.append((char)Integer.parseInt("00", 16))
    sb.append((char)Integer.parseInt("10", 16))

    // 4 char  Big Endian data size
    sb.append((char)Integer.parseInt("00", 16))
    sb.append((char)Integer.parseInt("00", 16))
    sb.append((char)Integer.parseInt("00", 16))
    sb.append((char)Integer.parseInt(Integer.toHexString(eiscpDataSize), 16))

    // eiscp_version = "01";
    sb.append((char)Integer.parseInt("01", 16))

    // 3 chars reserved = "00"+"00"+"00";
    sb.append((char)Integer.parseInt("00", 16))
    sb.append((char)Integer.parseInt("00", 16))
    sb.append((char)Integer.parseInt("00", 16))

    ////////////////////////////////////////
    // eISCP data
    ////////////////////////////////////////

    // Start Character
    sb.append("!")

    // eISCP data - unittype char '1' is receiver
    sb.append("1")

    // eISCP data - 3 char command and param    ie PWR01
    sb.append(command)

    // msg end - this can be a few different chars depending on your receiver
    
    //  [CR]	Carriage Return		ASCII Code 0x0D			
	//  [LF]	Line Feed			ASCII Code 0x0A			
	//  [EOF]	End of File			ASCII Code 0x1A			
	
    // The eISCP protocol lists 4 possible termination options; CR, LF, CRLF, and EOF. The two 
    // receivers models I own don't seem to be all that fussy and work with any of the listed 
    // options.  Regardless, all 4 options are included in the settings in case there are models
    // that require a specific termination character.

    switch(eISCPTermination as Integer)
    {
        case 1:    // CR
            sb.append((char)Integer.parseInt("0D", 16)) 
            writeLogDebug ("->CR")
            break

        case 2:   // LF
            sb.append((char)Integer.parseInt("0A", 16)) 
            writeLogDebug ("->LF")
            break

        case 3:   // CRLF
            sb.append((char)Integer.parseInt("0D", 16)) 
            sb.append((char)Integer.parseInt("0A", 16))
            writeLogDebug ("->CRLR")
            break

        case 4:   // EOF
            sb.append((char)Integer.parseInt("1A", 16)) 
            writeLogDebug ("->EOF")
            break

        default:
            sb.append((char)Integer.parseInt("0D", 16)) 
            writeLogDebug ("Defaulting to CR")
    }

    return sb.toString()
}

def Integer getEiscpVolumeMaxSetting()
{
    Integer maxIscpHexValue = settings?.eISCPVolumeRange?.toBigDecimal()
	writeLogDebug("settings?.eISCPVolumeRange: ${maxIscpHexValue}")

    return maxIscpHexValue
}

def getName()
{
    return device.getName()
}
