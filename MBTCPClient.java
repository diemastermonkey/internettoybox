/* 20160115 Monkeybox chatbot prototype 
 * @diemastermonkey
 *
 * IMPORTANT NOTE: Unnecessarily logs-into chat as user - not needed! Just let it wander
 * around as "monkeybox.username" or something.
 *
 */

import java.io.*; 
import java.net.*; 
//
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
// import gnu.io.CommPortIdentifier; 
// import gnu.io.SerialPort;
// import gnu.io.SerialPortEvent; 
// import gnu.io.SerialPortEventListener; 
import java.util.Enumeration;

// Simple configuraiton object
class MB_Configuration {

	// Config properties
	String serial_port_names = "COM4";  // Default to single unit
	String sConfigRaw  = "";  	// All config data as glob

	/*	Slurp up config file, skip blanks and # comments
	*/
	void fn_Config_Read (String sArgFile) {
	    try {
		System.out.println ("Config: Reading from " + sArgFile);
		FileReader oReader = new FileReader (sArgFile); // Ware the hardwire
		BufferedReader oBuffer = new BufferedReader (oReader);
		String sBuff = oBuffer.readLine();  // Priming input
		while (sBuff != null) {
			if (sBuff.indexOf("#") != 0 && sBuff.indexOf(" ") != 0) {  
  				sConfigRaw += sBuff;
  			}
  			sBuff = oBuffer.readLine();  	// Next line
		}	// End while
	    } catch (Exception e) {
		System.out.println ("ERROR : Could not process " + sArgFile);
	    }		// End try
	}      		// End method

	// Get the value of a token from the raw config block
	// token must begin line in form "token=value"
	String fn_Config_Get (String sArgToken) {
		String sValue = null;
		int iStartPos = sConfigRaw.indexOf(sArgToken + "=", 0);
		if (iStartPos != -1) {  			
			// ie 'from just after delim
			// Ware hardwired line ending char
			sValue = sConfigRaw.substring (
				iStartPos + sArgToken.length() + 1,	
				sConfigRaw.indexOf(";",  iStartPos)
			);    			// End long assignment 
		}      	// End if/else  

		// Censor tokens with "auth" in them
		if (sArgToken.indexOf ("auth") == -1) {
		    System.out.println ("Config: " + sArgToken + " is " + sValue);
		} else {
		    System.out.println ("Config: " + sArgToken + " is (censored).");
		}
		return (sValue);    // Retval may be null
	}      	// End method
};  		// End configuration class

/*	Toy class encapsulates a conduit, via the bridge, to something
	that can receive instructions. It should be used for 'final endpoints',
	like a servo, but can also be an entire unit (i.e. NXT).
*/
class Toy {
	boolean bValid = false;
	String  sPrevious = null;		// last command
	String  sType = null;
	int  iUnit = -1;			// For future multiple units
	int  iPort = -1;			// port # on NXTs, pin # on Arduinos
	boolean bDigital = true;		// assume digital signals
	boolean bRead = false;			// For later 'read value' commands
	int  iValue = -1;			// digital will be forced to 0 or 1
	
	// Parse (arg) string as a command, into properties
	// Or return null if invalid
	boolean fnParseCommand (String sArgCmd) {
		// b1p13d001  b9p08a077
		// n1p02a127  n9p03r001 	// Future 'r' read flag reserved
		int iLen = sArgCmd.length();
		if (iLen < 7) {  // Min cmd len 7 char ie b1p13d1
  			System.out.println ("Cmd : Less than min length, skipped.");
  			return (false);
		}

		try {
			sType = sArgCmd.substring(0, 1);
			// It better be right now
			iUnit = Integer.valueOf(sArgCmd.substring(1, 2));
  			// Char 2 ('p') ignored
			iPort = Integer.valueOf(sArgCmd.substring(3, 5));
			// 2 digits NOW MANDATORY
			if (sArgCmd.charAt(5) != 'd') {
  				bDigital = false;
  			}

			// Remainder of string treated as single int
			iValue = Integer.valueOf (sArgCmd.substring (6, iLen));
			System.out.println ("Cmd : Type " + sType +
				" Unit " + iUnit +
		  		" Port " + iPort +
		  		" Digital " + bDigital +
 		 		" Value " + iValue);
		} catch (Exception e) {
			System.out.println ("Cmd : Parsing error.");
			return (false);
		}

		bValid = true;
		return (true);
	}			// End method

};  // End class ToyCommand
	

/* Main Application Class */	
class MBTCPClient {  
    public static void main(String argv[]) throws Exception  {
	// Minimal config data
	MB_Configuration oConfig = new MB_Configuration();

	System.out.println (
		"Toybridge 0.2x by Die, Master Monkey"
		+ " - (C) 2016 Monkey Robotics"
		+ "\nToybridge initializing..."
	);
		oConfig.fn_Config_Read ("toybridge.cfg"); 

	String sClientInput;
	String sServerOutput;
	String sChatServer = oConfig.fn_Config_Get ("irc_server_host");
	int iChatPort   = Integer.valueOf(oConfig.fn_Config_Get ("irc_server_port"));
	String sUserChannel= oConfig.fn_Config_Get ("irc_server_channel");
	String sUserName   = oConfig.fn_Config_Get ("login_name");
	String sUserAuth   = oConfig.fn_Config_Get ("login_auth");
	String sClientName = oConfig.fn_Config_Get ("irc_server_greeting");
	String sNXTPort  = oConfig.fn_Config_Get ("bridge_nxt"); // NXT Tests
	Boolean bBridgePeering = false;  		 // Fast 'is enabled'
	Boolean bExecuting = false;  			 // True while busy
	String sSender;
	String[] sArrayCommands;			 // New compound commands
		
	// Array of serial devices i.e. Arduinos, NXTs via BlueTooth, etc
	// Beware the hardwired max of 10!
	int iValidSerial = 0;  	// Convenient count of confirmed USB devices

	// Dev version hardwired for one Arduino and one NXT max
	Toy oToybox = new Toy();			// Object handles parsing only so far
	Toy oLego   = new Toy();

	// Some properties are config props, some not - normalize this

	// Set fast global about bridge peering (no * means enabled)
	if (oConfig.fn_Config_Get ("bridge_peers").indexOf("*") == -1) {
		bBridgePeering = true;
	}

	// Set special Unix system property (for Linux/Raspberry Pi) if configured
	if (oConfig.fn_Config_Get ("bridge_unix_property") != null) {
		System.out.println ("Config: Applying serial_unix_props " 
			+ oConfig.fn_Config_Get ("bridge_unix_property") + " = "
			+ oConfig.fn_Config_Get ("bridge_unix_value"));

		System.setProperty (
			oConfig.fn_Config_Get ("bridge_unix_property"), 
			oConfig.fn_Config_Get ("bridge_unix_value")
		);
	}	// End if
		
		
	/* IRC Client Setup
	*/

	// Buffered reader for user input via stdio
	BufferedReader inFromUser = new BufferedReader (
		new InputStreamReader(System.in)
	);  

	// Local client socket
	Socket oClientSocket = new Socket (sChatServer, iChatPort);

	DataOutputStream oTCPOut = new DataOutputStream (
		oClientSocket.getOutputStream()
	);

	BufferedReader inFromServer 
		= new BufferedReader (new InputStreamReader (
		oClientSocket.getInputStream())
	);  

	// Log-in/join - TO DO: Check login state before continuing ;)
	oTCPOut.writeBytes ("PASS " + sUserAuth + "\r\n");	// Yes this first
	oTCPOut.writeBytes ("NICK " + sUserName + "\r\n");
	oTCPOut.flush();    	// Superfluous?
	oTCPOut.writeBytes ("JOIN #" + sUserName + "\r\n");	// Join user's channel
	oTCPOut.flush();    	// Superfluous?		

	// Server colloqual hello ;)
	oTCPOut.writeBytes ("PRIVMSG #" + sUserChannel + " :" + sClientName + "\r\n");
	oTCPOut.flush();    	// Superfluous?

	System.out.println("Chat : The bridge is connected to chat!");  

	// Chat poll loop		
	while (oClientSocket.isClosed() == false) {
		sServerOutput = inFromServer.readLine();
		// Get newest line from server
		// WARE possible array oob?
  		if (sServerOutput.startsWith("PING ")) {  // Handle pings
			oTCPOut.writeBytes (              // Verify w/RFC
				"PONG " + 
				sServerOutput.substring (
  					sServerOutput.indexOf ("PING ") + 1,
  					sServerOutput.length()
  				)
  				+  "\r\n"        	// May be superfluous
  			);				// End writebytes

			System.out.println (
				"Chat : Replied to a ping "
				+ sServerOutput
			);
		}	// End if
		
		// (New) Forward (minimally validated) monkeybox cmds to serial
		if (sServerOutput.contains(";")) {

			sSender = "none";
			int iBang = sServerOutput.indexOf("!");
			if (iBang != -1 && iBang != 0) {
				sSender = sServerOutput.substring (1, iBang);
			}

		// WARN: Break on JUST COLON??
		String sCmdRaw = sServerOutput.substring(
			sServerOutput.lastIndexOf(":") + 1,
			sServerOutput.length()
		);
  
		System.out.println("Sender : " + sSender);
		System.out.println("Message: " + sCmdRaw);

		/* New: Skip commands not from configured "briege peers", if enabled */
		if (bBridgePeering == true) {
  			bExecuting = false;
  			// To do: put bridge peers in global for faster lookup
  			if (-1 != 
			  oConfig.fn_Config_Get("bridge_peers").indexOf(sSender)) {
  				// Sender not in peer list, ditch em
  				System.out.println("Chat : Accepting cmd from peer " + sSender);
  				bExecuting = true;
  			} else {
  				System.out.println("Chat : Ignored non-peer " + sSender);
  			}	// End if else
  		} // End if bridge peering

		/* New: Multi-command handling, delimited by semicolons, i.e.
		 	b1p13d1;b1p07a45;n1p01a32 
		*/
		sArrayCommands =  sCmdRaw.split(";");		// Ware hardwired delimiter
		System.out.println ("Box : Cmd list of length " + sArrayCommands.length);

		for (String sCommand : sArrayCommands) {
		  	System.out.println ("Box : Processing " + sCommand + "...");

			// Traditional Arduino command
  			if (bExecuting == true && sCommand.lastIndexOf("b") == 0) {
				// Min validate
				if (oToybox.fnParseCommand (sCommand) != true) {
  					System.out.println ("Box : Ignored invalid command " + sCommand);
  				} else {
					//
		  			// If valid, written DIRECTLY to Arduino as-is
					//	(neutered)
					//
				}  			// End else
  			}    // End if Arduino

		  	// Crudely handle NXT commands 
		  	// oConfig.fn_NXT_Test (oNXTPort, 0x00, iCmdArg) ;		// A (0x00)
		  	// oConfig.fn_NXT_Test (oNXTPort, 1, oLego.iValue);		// B (0x01)		
		  	// oConfig.fn_NXT_Test (oNXTPort, 0x02, oLego.iValue);	// C (0x02)

		  	if (bExecuting == true && sCommand.lastIndexOf("n") == 0) {
		  		if (oLego.fnParseCommand (sCommand) != true) {
  					System.out.println ("NXT : Ignored invalid command " + sCommand);
  				} else {
  					System.out.println ("NXT : Handling cmd? " + sCommand
					+ ", Value " + oLego.iValue + " port " + oLego.iPort);

  					// DEV KLUDGE: Treat special "PORT 99" as "PORTS 0x01 and 0x02"
  					if (oLego.iPort == 99) {
						System.out.println ("NXT : DEV - Handling 'B and C' command");
						// oConfig.fn_NXT_Test (oNXTPort, 0x01, oLego.iValue);
						// Engine B
						// oConfig.fn_NXT_Test (oNXTPort, 0x02, oLego.iValue);
						// C
					} else {
						// Normal handling
						// oConfig.fn_NXT_Test (oNXTPort, oLego.iPort, oLego.iValue);
					}	// End if lego port 99
				}		// End if cmd parsed true
			}  // End if NXT		
		}  	// End for (each command in list;list)

	}  		// End if 'contains'

	// Test output // System.out.println("Chat : " + sServerOutput);    	
	System.out.flush();	// Probably superfluous'

    }    			// End while
		
    // Close client session
    // oClientSocket.close();  			// Disused in dev, hit Ctrl+C
  }  // end main
	
}  // end app class
