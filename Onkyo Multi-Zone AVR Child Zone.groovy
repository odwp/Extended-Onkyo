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
    2021-03-24      0.9.210324.0        Steve Vibert        Fix: Lower case or single digit volume level command values cause ZZZN/A response (where ZZZ represents the zone command prefix)
    2022-10-01      0.10.221001.0       Wayne Pirtle        Added functionaity to support controlling 
                                                            Network Services
    2023-01-07      0.11.230107.0       Wayne Pirtle        Added auto discovery and child creation

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
	definition (name: "Onkyo Multi-Zone AVR Child Zone", namespace: "SteveV", author: "Steve Vibert")
	{
		capability "Initialize"
		capability "Switch"
		capability "AudioVolume"
		capability "Actuator"
		capability "MusicPlayer"
		capability "PushableButton"

		attribute "mediaSource", "STRING"
		attribute "mute", "string"
        attribute "input", "string"     
		attribute "volume", "number"
		attribute "mediaInputSource", "string"
        attribute "status", "string"
		attribute "level", "number"
//		attribute "trackData"
		attribute "trackDescription", "string"
		attribute "numberOfButtons", "number"
		attribute "pushed", "number"
		attribute "mediaSourceImageURL", "string"
		attribute "baseImageURL", "string"

        command "muteToggle"
		command "setInputSource", [[name:"Source Index*", type: "NUMBER", description: "Input ID by Index" ]]
		command "setInputSourceRaw", [[name:"Source Hex*", type: "STRING", description: "Input ID by HEX Value" ]]
        command "editCurrentInputName", [[name:"New name*", type: "STRING", description: "Display name for this input" ]]
		command "setInputLabelBaseURL", [[name:"Label base URL", type: "STRING", description: "This is the network location (folder) where the Input Source label image files are stored. Defaults to local hub file storage."]]
        command "reset", ["This resets the Input Source list to the default set."]
		command "sendRawDataCommand", ["See Onkyo EISCP Spec"]
	}

	preferences 
	{   
		input name: "textLogging",  type: "bool", title: "Enable description text logging ", required: true, defaultValue: true
        input name: "debugOutput", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

@Field static String PowerOn = "PowerOn"
@Field static String PowerOff = "PowerOff"
@Field static String PowerQuery = "PowerQuery"

@Field static String MuteOn = "MuteOn"
@Field static String MuteOff = "MuteOff"
@Field static String MuteToggle = "MuteToggle"
@Field static String MuteQuery = "MuteQuery"

@Field static String VolumeUp = "VolumeUp"
@Field static String VolumeDown = "VolumeDown"
@Field static String VolumeUp1 = "VolumeUp1"
@Field static String VolumeDown1 = "VolumeDown1"
@Field static String VolumeSet = "VolumeSet"
@Field static String VolumeQuery = "VolumeQuery"

@Field static String InputSet = "InputSet"
@Field static String InputUp = "InputUp"
@Field static String InputDown = "InputDown"
@Field static String InputQuery = "InputQuery"

@Field static String Play = "Play"
@Field static String Pause = "Pause"
@Field static String Stop = "Stop"
@Field static String TrkUp = "TrkUp"
@Field static String TrkDown = "TrkDown"

@Field static Map zone1CmdMap = [PowerOn:"PWR01", PowerOff:"PWR00", PowerQuery:"PWRQSTN", MuteOn:"AMT01", MuteOff:"AMT00", MuteToggle:"AMTTG", MuteQuery:"AMTQSTN", VolumeSet:"MVL", VolumeUp:"MVLUP", VolumeDown:"MVLDOWN", VolumeUp1:"MVLUP1", VolumeDown1:"MVLDOWN1", VolumeQuery:"MVLQSTN", InputSet:"SLI", InputUp:"SLIUP", InputDown:"SLIDOWN", InputQuery:"SLIQSTN", Play:"NTCPLAY", Pause:"NTCPAUSE", Stop:"NTCSTOP", TrkUp:"NTCTRUP", TrkDown:"NTCTRDN"]
@Field static Map zone2CmdMap = [PowerOn:"ZPW01", PowerOff:"ZPW00", PowerQuery:"ZPWQSTN", MuteOn:"ZMT01", MuteOff:"ZMT00", MuteToggle:"ZMTTG", MuteQuery:"ZMTQSTN", VolumeSet:"ZVL", VolumeUp:"ZVLUP", VolumeDown:"ZVLDOWN", VolumeUp1:"ZVLUP1", VolumeDown1:"ZVLDOWN1", VolumeQuery:"ZVLQSTN", InputSet:"SLZ", InputUp:"SLZUP", InputDown:"SLZDOWN", InputQuery:"SLZQSTN", Play:"NTZPLAY", Pause:"NTZPAUSE", Stop:"NTZSTOP", TrkUp:"NTZTRUP", TrkDown:"NTZTRDN"] 
@Field static Map zone3CmdMap = [PowerOn:"PW301", PowerOff:"PW300", PowerQuery:"PW3QSTN", MuteOn:"MT301", MuteOff:"MT300", MuteToggle:"MT3TG", MuteQuery:"MT3QSTN", VolumeSet:"VL3", VolumeUp:"VL3UP", VolumeDown:"VL3DOWN", VolumeUp1:"VL3UP1", VolumeDown1:"VL3DOWN1", VolumeQuery:"VL3QSTN", InputSet:"SL3", InputUp:"SL3UP", InputDown:"SL3DOWN", InputQuery:"SL3QSTN", Play:"NT3PLAY", Pause:"NT3PAUSE", Stop:"NT3STOP", TrkUp:"NT3TRUP", TrkDown:"NT3TRDN"]
@Field static Map zone4CmdMap = [PowerOn:"PW401", PowerOff:"PW400", PowerQuery:"PW4QSTN", MuteOn:"MT401", MuteOff:"MT400", MuteToggle:"MT4TG", MuteQuery:"MT4QSTN", VolumeSet:"VL4", VolumeUp:"VL4UP", VolumeDown:"VL4DOWN", VolumeUp1:"VL4UP1", VolumeDown1:"VL4DOWN1", VolumeQuery:"VL4QSTN", InputSet:"SL4", InputUp:"SL4UP", InputDown:"SL4DOWN", InputQuery:"SL4QSTN", Play:"NT4PLAY", Pause:"NT4PAUSE", Stop:"NT4STOP", TrkUp:"NT4TRUP", TrkDown:"NT4TRDN"]
@Field static Map zoneCmdMap = [:]

@Field static Map zone1CmdPrefixes = ["Power":"PWR", "Mute":"AMT", "Volume":"MVL", "Input":"SLI"]
@Field static Map zone2CmdPrefixes = ["Power":"ZPW", "Mute":"ZMT", "Volume":"ZVL", "Input":"SLZ"]
@Field static Map zone3CmdPrefixes = ["Power":"PW3", "Mute":"MT3", "Volume":"VL3", "Input":"SL3"]
@Field static Map zone4CmdPrefixes = ["Power":"PW4", "Mute":"MT4", "Volume":"VL4", "Input":"SL4"]
@Field static Map zoneCmdPrefixes = [:]

@Field static Map zoneNumbers = ["Main":1, "Zone 2":2, "Zone 3":3, "Zone 4":4 ] 


/* 
CHANGING CURRENT INPUT SOURCE AND NETWORK SERVICE

The button capability is the mechanism used on dashboards to change Input 
Sources and Network Services.  The button codes are shared accross both 
the input sources and network services. Buttons 1-29 are Input Sources, 
and 30+ are Network Services.  Buttons 23-29 are reserved for unimplemented 
and future inputs.



INPUT SOURCE AND NETWORK SERVICE MAPS

    (All comments about AVR functionality are based on a TX-NR595, 
	though they are  likely consistent accross most Onkyo AVRs.)
	
The source and service definitions listed below are based on ISCP v1.46.
This should cover all AVR models though 2020. 
	
The listed inputs are the sources that were available through the 2020 models. 
There are some addiional inputs that appear to have been added for 
future functionality.  Room has been left in the button sequence to 
allow these new inputs to be added.

Not all inputs are available on all receiver models.  Review your receiver 
documentation and the ISCP documentation to choose the corrent input codes.

TUNER NOTE: 

There is only ONE tuner in each receiver.  It can be tuned to AM OR FM, 
not both.  Example: If FM radio is being listened to on one zone, and AM 
is chosen for a different zone, both zones will be listening to AM radio.  

USB NOTE: 

The USB and Network Services use the same internal input.  Just like AM 
and FM radio cannot be listened to simultaneously, USB and Network 
Services cannot be listend to simultaneously.

*/

@Field static Map defaultInputSources = [1:[code:"01", name:"CBL/SAT", image:"cbl-sat.jpg"], 
								  2:[code:"02", name:"Game", image:"game.png"],  
								  3:[code:"03", name:"Aux", image:"aux-in.png"],  
								  4:[code:"05", name:"PC", image:"pc.png"],  
								  5:[code:"07", name:"Extra1", image:"null.png"],
								  6:[code:"08", name:"Extra2", image:"null.png"],
								  7:[code:"09", name:"Extra3", image:"null.png"],
								  8:[code:"10", name:"BD/DVD", image:"bd-dvd.png"],  
								  9:[code:"11", name:"Streaming Box", image:"stream.png"], 
								  10:[code:"12", name:"TV", image:"TV.png"],  
								  11:[code:"20", name:"TAPE", image:"null.png"],  
								  12:[code:"22", name:"Phono", image:"phono.png"],  
								  13:[code:"23", name:"CD", image:"cd.png"],  
								  14:[code:"24", name:"FM", image:"fm-radio.png"],  
								  15:[code:"25", name:"AM", image:"am-radio.png"],  
								  16:[code:"26", name:"Tuner", image:"tuner.png"],  
								  17:[code:"27", name:"Music Server", image:"null.png"],  
								  18:[code:"28", name:"Internet Radio", image:"null.png"],  
								  19:[code:"2A", name:"USB", image:"usb.png"], 
								  20:[code:"2B", name:"Network", image:"net.png"],  
								  21:[code:"2C", name:"USB (toggle)", image:"usb.png"],  
								  22:[code:"2E", name:"Bluetooth", image:"bluetooth.png"]
								 ]
@Field static Map currentSource = [:]

/*
These are the Network Services that *could* be on a reciever.  The actual 
network services available depend on the AVR being accessed.  Check your 
owner's manual for the pertinent services available on your AVR.  

*** Be aware that Airplay and Airplay2 are different services.  
Choose the one that applies to your receiver.  They are NOT interchangable.

*/

@Field static Map networkServices = [30:[code:"00", name:"Music Server (DLNA)", image:"dlna.png"],   
								     31:[code:"01", name:"Favorite", image:"star.png"], 
								     32:[code:"02", name:"vTuner", image:"USB-blue.png"], 
								     33:[code:"03", name:"SiriusXM", image:"siriusxm.png"], 
								     34:[code:"04", name:"Pandora", image:"pandora.png"], 
								     35:[code:"05", name:"Rhapsody", image:"rhapsody.png"], 
								     36:[code:"06", name:"Last.fm", image:"lastfm.png"], 
								     37:[code:"07", name:"Napster", image:"napster.png"], 
								     38:[code:"08", name:"Slacker", image:"slacker.png"], 
								     39:[code:"09", name:"Mediafly", image:"mediafly.png"], 
								     40:[code:"0A", name:"Spotify", image:"spotify.png"], 
								     41:[code:"0B", name:"AUPEO!", image:"aupeo.png"], 
								     42:[code:"0C", name:"Radiko", image:"radiko.png"], 
								     43:[code:"0D", name:"e-onkyo", image:"e-onkyo.png"], 
								     44:[code:"0E", name:"TuneIn", image:"tunein.png"], 
								     45:[code:"0F", name:"mp3tunes", image:"mp3.png"], 
								     46:[code:"10", name:"Simfy", image:"simfy.png"], 
								     47:[code:"11", name:"Home Media", image:"null.png"], 
								     48:[code:"12", name:"Deezer", image:"deezer.png"], 
								     49:[code:"13", name:"iHeartRadio", image:"iheart.png"], 
								     50:[code:"18", name:"Airplay", image:"airplay.png"], 
								     51:[code:"1A", name:"Onkyo Music", image:"onkyo-music.png"], 
								     52:[code:"1B", name:"TIDAL", image:"tidal.png"], 
								     53:[code:"1C", name:"Amazon Music", image:"amazonmusic.png"], 
								     54:[code:"41", name:"FireConnect", image:"fireconn.png"], 
								     55:[code:"F0", name:"USB", image:"usb.png"],
								     56:[code:"F1", name:"USB (Rear)", image:"usb.png"],
									 57:[code:"42", name:"Play-Fi", image:"playfi.png"],
									 58:[code:"40", name:"Chromecast built-in", image:"chromecast.png"], 
								     59:[code:"44", name:"Airplay2", image:"airplay.png"], 
								     60:[code:"45", name:"Alexa", image:"alexa.png"],   
									]

@Field static Map currentNetSvc = [:]


def getVersion()
{
    return "0.10.221001.0"
}

void parse(String description) 
{
    writeLogDebug("${state.zone.Zone} received ${description}")
}

def initialize()
{
    log.warn "${getFullDeviceName()} initialize..."

/*
    The parent is still setting up the child.  Pause to let the parent finish setting up the child. 
    
    If the pause isn't long enough the following error is thrown in the getCurrentZone() function.
    java.lang.NullPointerException: Cannot get property 'Zone' on null object on line 586 (method installed)

    This is a 2 second pause.
*/
//    pauseExecution(2000)

	zoneCmdMap = [1:zone1CmdMap, 2:zone2CmdMap, 3:zone3CmdMap, 4:zone4CmdMap]
    zoneCmdPrefixes = [1:zone1CmdPrefixes, 2:zone2CmdPrefixes, 3:zone3CmdPrefixes, 4:zone4CmdPrefixes]
    
    refresh()
    sendEvent(name: "numberOfButtons", value: 40)
}

def installed()
{
    log.warn "${getFullDeviceName()} installed..."
    
    state.inputSources = [:]
    state.currentInput =[:]

    state.zone = ["Zone":zoneNumbers[device.getName()]] 

	state.baseImageURL = "http://${location.hub.localIP}/local/" 
	state.inputSources = defaultInputSources
    updated()
}


def reset()
{
    log.warn "${getFullDeviceName()} reset..."

    // Clear the custom input source names...
    state.inputSources = defaultInputSources
    state.currentInput =[:]
}


def updated()
{
	writeLogInfo("${getFullDeviceName()} updated...")
    state.version = getVersion()
    unschedule()

	// disable debug logs after 30 min
	if (debugOutput) 
		runIn(1800,logsOff)

    initialize()
}

void refresh()
{
    writeLogInfo ("${getFullDeviceName()} refresh")

    // Get the current state for the zone...
    sendCommand( getCommand(PowerQuery) )
	sendCommand( getCommand(VolumeQuery) )
	sendCommand( getCommand(MuteQuery) )
	sendCommand( getCommand(InputQuery) )
}

def logsOff() 
{
    log.warn "${getFullDeviceName()} debug logging disabled..."
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

def setZone(Integer targetZone)
{
    state.zone = ["Zone":targetZone]
}

def fromParent(String msg)
{
    parent.fromChild("received ${msg}")
}

def on() 
{
    sendCommand(getCommand(PowerOn))
}

def off()
{
    sendCommand(getCommand(PowerOff))
}

def mute()
{
    sendCommand(getCommand(MuteOn))
}

def unmute()
{
    sendCommand(getCommand(MuteOff))
}

def muteToggle()
{
    sendCommand(getCommand(MuteToggle))
}

def play()
{

/*Network source Play. 
  The status of the Network source operation will be reported or 
  can be interrogated with the Network/USB "NST" command.*/

    log.warn "Play: ${getCommand(Play)}"
    sendCommand(getCommand(Play))
	sendEvent(name: "status", value: "playing")
  
}

def pause()
{

/*Network source Pause. 
  The status of the Network source operation will be reported or 
  can be interrogated with the Network/USB "NST" command.*/

    log.warn "Pause: ${getCommand(Pause)}"
    sendCommand(getCommand(Pause))
	sendEvent(name: "status", value: "paused")
  
}

def stop()
{

/*Network source Stop. 
  The status of the Network source operation will be reported or 
  can be interrogated with the Network/USB "NST" command.*/

    sendCommand(getCommand(Stop))
	sendEvent(name: "status", value: "stopped")
  
}

def nextTrack()
{

/*Network source Track Up. 
  The status of the Network source operation will be reported or 
  can be interrogated with the Network/USB "NST" command.*/

    sendCommand(getCommand(TrkUp))

}

def previousTrack()
{

/*Network source Track Down. 
  The status of the Network source operation will be reported or 
  can be interrogated with the Network/USB "NST" command.*/

    sendCommand(getCommand(TrkDown))

}

def push()
{
/*The push() function enables a button to be used on a dashboard to choose the 
  target source.  Buttons 1-15 are the sources.  Network is intentionally not in 
  the list of Sources. Buttons 16-40 are the Network services.  Selecting one of 
  these will change the source to Network also.*/
    
	
	if(button < 30)
	{
		//set currentSource map	}
		currentSource = inputSources.get(Button)
		currentNetSvc = [:]
    }
	else
	{
		//Set Source to Network
		currentSource = inputSources.get(20)
        //Get Network Service info
		currentNetSvc = networkServices(Button)
	}
	
	sendCommand("${getCommand(InputSet)}${currentSource.get(code)}") 
	
  
}

def setInputSource(val)
{
	Integer sourceNum = val as Integer

    if(state.inputSources == null)
        state.inputSources = [:]

    if(sourceNum < 0)
    {
        log.warn "${getFullDeviceName()} Invalid input source number entered: value cannot be less than 0"
        return
    }

    else if(sourceNum > state.inputSources.size())
    {
        log.warn "${getFullDeviceName()} Invalid input source number entered: value cannot be greater than state.inputSources count."
        return
    }

    String inputSourceHex = getInputSourceFromIndex(sourceNum)

    if(inputSourceHex == null)
        log.warn "${getFullDeviceName()} Unable to find Input Source for source index ${sourceNum}"

    else
 		setInputSourceRaw(inputSourceHex)
}

def setInputSourceRaw(String hexVal)
{
	if(hexVal.length() > 2)
	{
		log.error "Invalid value used for Set Input Source Raw.  Value should be a 2 digit hex number."
		return
	}

	Integer sourceValue = Integer.parseInt(hexVal, 16)
    hexVal = hexVal.toUpperCase()

	if(sourceValue > 255) // 0xFF: source input value is a max of 2 bytes long
		return

    String cmdPrefix = getCommand(InputSet)
	sendCommand(cmdPrefix + hexVal)
}

def setVolume(val)
{
	writeLogDebug( "setVolume(val): ${val}")

    Integer maxEiscpVolume = parent.getEiscpVolumeMaxSetting()

	Float fNewVolumeVal = (Float)maxEiscpVolume * (Float)val / 100.0
	Integer newVolumeVal = (Integer)fNewVolumeVal
	String hexVal = Integer.toHexString(newVolumeVal)

    // Some Onkyo models respond with ZZZN/A if the value porion of the volume command
    // isn't two characters long and/or includes lowercase values...

    // Make sure the value is two characters long...
    if(hexVal.length() == 1)
        hexVal = "0${hexVal}"

    // ...and all uppercase...
    hexVal = hexVal.toUpperCase()

    writeLogDebug ("newVolumeVal: ${newVolumeVal} = ${hexVal} hex")
    
    String cmdPrefix = getCommand(VolumeSet)
    String command = cmdPrefix + hexVal
	writeLogDebug("volume command: ${command}")

	sendCommand(command)

    // The dashboard volume slider can ger out of sync with the receiver's actual value
    // so we'll schedule a query command.  This will result in a response that will 
    // trigger a sendEvent call...
    runIn(5, refreshVolume)
}

def refreshVolume()
{
    String volQry = getCommand(VolumeQuery)
    writeLogDebug("VolumeQuery = ${volQry}")
    sendCommand(volQry)
}

def volumeUp()
{
    sendCommand(getCommand(VolumeUp))
}

def volumeDown()
{
    sendCommand(getCommand(VolumeDown))    
}


def sendRawDataCommand(String rawData)
{
	sendCommand(rawData)
}

def String getCommand(int zone, String onkyoCmd)
{
	return zoneCmdMap[zone][onkyoCmd]
}

def String getCommand(String onkyoCmd)
{
    Integer zone = getCurrentZone()
	return zoneCmdMap[zone][onkyoCmd]
}

def int getCurrentZone()
{
    return state.zone.Zone as Integer
}

def sendCommand(String command)
{
    log.warn "Command: ${command}"
    String cmd = parent.getEiscpMessage(command)
    log.warn "Sending message: ${cmd}"
    parent.sendTelnetMsg(cmd)
}

def forwardResponse(Map cmdMap)
{
	Integer targetZone = cmdMap.zone
	String command = cmdMap.data

    // ISCP commands consist of 2 parts: a 3 character command code such as PWR (power) or MVL (master volume level)
    // and a value of variable length. We'll split the command into separate parts... 
    String cmdPre = command.substring(0, 3)
	String cmdVal = command.substring(3)
	
    // Next, we'll check if the 3 character command prefix is meant for this zone...
    if(!isValidCommandPrefixForZone(targetZone, cmdPre))
	{
		writeLogDebug("Ignoring ${command} - not for this zone")
		return
	}

    // Some responses have a value of N/A if the command that initiated the response is invalid for
    // the zone's current state such as attempting to set the volume level if the zone is currently
    // powered off. Including out of range values with a command can also result in this response value.  
    // We'll ignore these...
	if(cmdVal == "N/A")
	{
		writeLogDebug("Received ${command} - ignoring...")
		return
	}

	boolean handled = true

	switch(command)
	{
        case zoneCmdMap[targetZone].PowerOn:
			sendEvent(name: "switch", value: "on")
			writeLogInfo("${getFullDeviceName()} power is on")
			break

        case zoneCmdMap[targetZone].PowerOff:
			sendEvent(name: "switch", value: "off")
			writeLogInfo("${getFullDeviceName()} power is off")
			break

		case zoneCmdMap[targetZone].MuteOn:
			sendEvent(name: "mute", value: "muted")
			writeLogInfo("${getFullDeviceName()} is muted")
			break

		case zoneCmdMap[targetZone].MuteOff:
			sendEvent(name: "mute", value: "unmuted")
			writeLogInfo("${getFullDeviceName()} is unmuted")
			break

		default:
			handled = false
	}

	if(!handled)
	{
        // Some responses have a value of N/A if the command that initiated the response is invalid for
        // the zone's current state such as attempting to set the volume level if the zone is currently
        // powered off. Including out of range values with a command can also result in this response value.  
        // We'll ignore these...
		if(cmdVal == "N/A")
			return

        else if(cmdVal == "QSTN")
            return

        // Check if the command is a "setter" command type such as Volume Set (ZVLNN) 
        //  or Input Set (SLZNN).  
		switch(cmdPre)
		{
			case zoneCmdPrefixes[targetZone].Volume:
				Integer vol = onkyoVolumeToVolumePercent(cmdVal)
			    writeLogInfo("${getFullDeviceName()} volume is ${vol}")
				sendEvent(name: "volume", value: vol)
				break

			case zoneCmdPrefixes[targetZone].Input:
                String sourceName = getSourceName(cmdVal)
			    writeLogInfo("${getFullDeviceName()} input source is ${sourceName}")
				sendEvent(name: "mediaInputSource", value: sourceName)
				break
		}
	}
}

def boolean isValidCommandPrefixForZone(int zone, String commandPrefix)
{
    return zoneCmdPrefixes[zone].containsValue(commandPrefix)
}

def getSourceName(String sourceHexVal)
{
    try
    {
        // state.inputSources maps an Onkyo input source hex value to a custom input index and name. For example:
        // state.inputSources[1:[code:"00", name:"HTPC"], 2:[code:"22", name: "Video Game"], 3:[code:"0A", name: "Blu-ray"]] 
        // This allows the user to select an input source by entering an index number such as 1, 2, or 3 instead of the
        // Onkyo input source hex values (00, 22, 0A, etc.).

        if(state.inputSources == null)
            state.inputSources = [:]

        Integer val = Integer.parseInt(sourceHexVal, 16)
        //writeLogDebug("sourceVal: ${sourceVal}  sourceHexVal: ${sourceHexVal}")

        boolean sourceNameExists = false
        String inputName = ""
        Integer inputNumber = 1
        //writeLogDebug("state.inputSources contains ${state.inputSources.size()} entries.")
        
        if(state.inputSources.size() > 0)
        {
            def sourceNameMap = state.inputSources.find { it.value.code == sourceHexVal }

            if(sourceNameMap != null)
            {
                inputName = sourceNameMap.value.name
                inputNumber = sourceNameMap.key as Integer

                sourceNameExists = true                
            }
        }

        if(!sourceNameExists)
        {
            inputNumber = (state.inputSources.size() as Integer) + 1
            inputName = "Input ${inputNumber}"
            writeLogDebug("creating default sourceName for this input: ${inputName}")

            def inputMap = ["code":sourceHexVal, "name": inputName]          
            state.inputSources.put((inputNumber), inputMap)
            writeLogDebug("state.inputSources contains ${state.inputSources.size()} entries.")
        }

		state.currentInput = [:]
		state.currentInput.index = inputNumber
		state.currentInput.name = inputName

        writeLogDebug("getSourceName::state.currentInput: ${state.currentInput}")
        return inputName
    }

    catch(ex)
    {
        writeLogDebug("getSourceName caused the following exception: ${ex}")
    }
}

def editCurrentInputName(String inputName)
{
    writeLogDebug("editCurrentInputName: ${inputName}")
    writeLogDebug("editCurrentInputName:state.currentInput: ${state.currentInput}")

    if(state.currentInput.size() == 0)
    {
        writeLogInfo("No Onkyo inputs have been selected yet.  Use your remote to switch to each available input.")
        return
    }

	Integer inputNumber = state.currentInput.index ?: -1

	if(inputNumber == -1)
	{
		writeLogDebug("inputNum == -1")
		return
	}

	writeLogDebug("inputNum ${inputNumber}")

	def map = state.inputSources[inputNumber.toString()]
	writeLogDebug("map = ${map}")

	writeLogDebug("curInputName = ${map.name}")
	map.name = inputName

   	state.currentInput = [:]
	state.currentInput.index = inputNumber
	state.currentInput.name = inputName

	sendEvent(name: "mediaInputSource", value: inputName)
}

def String getInputSourceFromIndex(Integer sourceIndex)
{
    writeLogDebug("Getting Onkyo source number for index ${sourceIndex}...")
    def sourceNameMap = state.inputSources[sourceIndex.toString()]

    if(sourceNameMap == null)
    {
        writeLogDebug("  Onkyo source number for this index could not be found.  Confirm that a custom source name has been created.")
        return null
    }

    else
    {
        String code = sourceNameMap.code
        writeLogDebug("  Found Onkyo source number ${code}")
        return code
    }
}

def onkyoVolumeToVolumePercent(String hexValue)
{
	writeLogDebug("onkyoVolumeToVolumePercent(String hexValue)  hexValue: ${hexValue} ")
	
	float hexVolumeValue = (float)Integer.parseInt(hexValue, 16)
	writeLogDebug("hexVolumeValue: ${hexVolumeValue}")
	
	float maxIscpHexValue = (float)parent.getEiscpVolumeMaxSetting()
	writeLogDebug("maxIscpHexValue: ${maxIscpHexValue}")

	if(hexVolumeValue > maxIscpHexValue)
		return 100

	Integer volPercent = (Integer)( ((hexVolumeValue / maxIscpHexValue) * 100.0 ) + 0.5)
	writeLogDebug("volPercent: ${volPercent}")

	if(volPercent > 100)
		volPercent = 100

	else if(volPercent < 0)
		volPercent = 0

	return volPercent
}

def String getFullDeviceName()
{
    return "${parent.getName()} - ${device.getName()}"
}
