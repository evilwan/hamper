/* Hamper.java - Burpsuite extension for recording websocket messages
 *
 * Copyright (c) 2022 Eddy Vanlerberghe.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of Eddy Vanlerberghe shall not be used to endorse or promote
 *    products derived from this software without specific prior written
 *    permission.
 *
 * THIS SOFTWARE IS PROVIDED BY EDDY VANLERBERGHE ''AS IS'' AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL EDDY VANLERBERGHE BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * 
 *****************************************************************************/
package evilwan;

import burp.api.montoya.*;
import burp.api.montoya.websocket.*;
import burp.api.montoya.logging.*;
import burp.api.montoya.http.message.requests.*;
import burp.api.montoya.core.*;
import burp.api.montoya.extension.*;
import burp.api.montoya.utilities.*;

import java.text.SimpleDateFormat;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.awt.*;
import javax.swing.*;

/**
 * Burpsuite extension for recording websocket messages
 * <p>
 * Websocket communication can be tested at: https://www.piesocket.com/websocket-tester
 */
public class Hamper implements BurpExtension, WebSocketCreationHandler, ExtensionUnloadingHandler {
    // {{ Class members }}

    /**
     * Name for this extension
     */
    private final static String EXTENSION_NAME = "Websock(et)s Hamper";
    /**
     * Name for default (initial) output file
     */
    private final static String DEFAULT_OUTFILE_NAME = "websocket-messages-" + (new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS")).format(new Date()) + ".dat";
    /**
     * Start directory for file save operations.
     * <p>
     * This variable is adjusted during the run of Burpsuite to keep track of the latest selected
     * directory.
     */
    private static File _curdir = new File(System.getProperty("user.home"));
    /**
     * Full path of current output file
     */
    private static String _outfilename = _curdir.getAbsolutePath() + '/' + DEFAULT_OUTFILE_NAME;
    /**
     * Last selected start directory for file save operations.
     * <p>
     * This variable is used to keep track of newly selected ouput paths before commiting them
     * with the save button.
     */
    private static File _tmpcurdir = _curdir;
    /**
     * Last selected full path of current output file
     * <p>
     * This variable is used to keep track of newly selected ouput paths before commiting them
     * with the save button.
     */
    private static String _tmpoutfilename = _outfilename;
    /**
     * Index for websocket identification.
     * <p>
     * Each new websocket will get a unique sequence number in the output file to
     * keep track of every websocket instance, even when re-opened to same URL.
     * <p>
     * Note that this value may or may not be the same as the identifiers shown in the Burpsuite
     * GUI: so far that piece of information does not seem to be available via the Montoya API.
     */
    private static int _count = 0;

    // {{ Instance members }}
    /**
     * Reference to Montoya API
     */
    private MontoyaApi _api;
    /**
     * Used to produce logs in Burpsuite environment
     */
    private Logging _l;
    /**
     * Utilities reference
     */
    private Utilities _utils;
    /**
     * File in which websocket messages are recorded.
     */
    private FileWriter _f;
    /**
     * Some dummy object to use for I/O synchronization.
     * <p>
     * <code>_f</code> cannot be used when <code>null</code> so we introduce this dummy
     * semaphore non-null object
     */
    private String _syncobject = "SYNC!";
    /**
     * Queue for messages waiting to be written to file
     * <p>
     * In order to keep up with tracking bursts of websocket messages, it is important to
     * not block Burpsuite on writing individual websocket messages to file. To that end,
     * we use an in-memory queue as buffer between Burpsuite code processing intercepted
     * messages and the code that will actually write that data to the output file.
     * <p>
     * Also, every websocket will have an assigned listener object, possibly running in its
     * own thread, so we need a mechanism to collect output from all those threads into a single
     * queue for writing the data to one single file (only one output file is open at any given
     * time) Using a locking queue, every client can safely append entries to the queue where
     * they will be picked up later by the consuming thread,
     */
    private LinkedBlockingQueue<String> _q;
    /**
     * Thread for dumping queued messages to file.
     * <p>
     * Note that this thread will synchronize on the dummy semaphore so that the main GUI
     * code can close and replace the output file safely.
     */
    private QueueDumper _qd;
    /**
     * Registration for self as websocket event handler
     */
    private Registration _wsreg;
    /**
     * Need to save websocket messages?
     */
    private boolean _save_on;
    /**
     * Need to save websocket ID?
     */
    private boolean _save_id;
    /**
     * Need to save direction?
     */
    private boolean _save_direction;
    /**
     * String to use for client to server direction
     * <p>
     * Be careful with ">" characters in case of XML output...
     */
    private String _direction_cs;
    /**
     * String to use for server to client direction
     * <p>
     * Be careful with ">" characters in case of XML output...
     */
    private String _direction_sc;
    /**
     * Need to save websocket URL?
     */
    private boolean _save_url;
    /**
     * Need to save websocket message timestamp?
     */
    private boolean _save_time;
    /**
     * Format to save date/time of websocket message
     */
    private String _wsmsg_date_time_fmt;
    /**
     * Need to save websocket message data?
     */
    private boolean _save_data;
    /**
     * Need to save in XML format?
     */
    private boolean _save_xml;
    /**
     * Need to save CSV data format?
     */
    private boolean _save_csv;
    /**
     * Need to save JSON data format?
     */
    private boolean _save_json;
    /**
     * Need to save raw data format?
     */
    private boolean _save_raw;
    /**
     * Need to base64 encode binary data before saving?
     */
    private boolean _save_b64;
    /**
     * Field separator in case of non-XML output
     */
    private String _sep_fld;
    /**
     * Line terminator in case of non-XML output
     */
    private String _sep_nl;
    /**
     * Use CDATA around message data in XML?
     */
    private boolean _save_cdata;
    /**
     * GUI component for client to server indicator
     */
    private JTextField _txt_direction_cs;
    /**
     * GUI component for server to client indicator
     */
    private JTextField _txt_direction_sc;
    /**
     * GUI component for message save active switch
     */
    private JCheckBox _ckbx_save_on;
    /**
     * GUI component for websocket ID saving switch
     */
    private JCheckBox _ckbx_save_id;
    /**
     * GUI component for message direction saving switch
     */
    private JCheckBox _ckbx_save_direction;
    /**
     * GUI component for websocket URL saving switch
     */
    private JCheckBox _ckbx_save_url;
    /**
     * GUI component for message timestamp saving switch
     */
    private JCheckBox _ckbx_save_time;
    /**
     * GUI component for saved timestamp format
     */
    private JTextField _txt_wsmsg_date_time_fmt;
    /**
     * GUI component for message payload saving switch
     */
    private JCheckBox _ckbx_save_data;
    /**
     * GUI component for selection of XNL output
     */
    private JRadioButton _radio_save_xml;
    /**
     * GUI component for selection of raw output
     */
    private JRadioButton _radio_save_raw;
    /**
     * GUI component for selection of CSV output
     */
    private JRadioButton _radio_save_csv;
    /**
     * GUI component for selection of JSON output
     */
    private JRadioButton _radio_save_json;
    /**
     * GUI component for payload base-64 encoding switch
     */
    private JCheckBox _ckbx_save_b64;
    /**
     * GUI component for using XML CDATA construct on payload switch
     */
    private JCheckBox _ckbx_save_cdata;
    /**
     * GUI component holding the full path to the output file
     */
    private JTextField _txt_outfil;

    // {{ Class methods }}
    /**
     * Return a stacktrace for the specified throwable.
     * <p>
     * Note that the input is a <code>Throwable</code>, so at a
     * lower level than <code>Exception</code>. This allows to capture
     * and process runtime exceptions as well.
     * <p>
     * This method is purely for debugging and diagnostic purposes.
     *
     * @param t throwable for which to generate a stack trace
     * @return multiline string containing the stacktrace where the
     * <code>Throwable</code> occurred
     */
    public static String getStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }
    /**
     * Convert a string to escaped CSV version.
     * <p>
     * Quick and dirty hack to prevent the need for external dependencies in the extension Jar.
     * For now, only double quote characters will be escaped.
     *
     * @param s text to encode into a safe, quoted, CSV string
     * @return quoted CSV representation of the input text
     */
    public static String toCSV(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for(int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if(c == '"') {
                //
                // Prepend by escape char (double quote)
                //
                sb.append('"');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }
    /**
     * Convert a string to escaped JSON version.
     * <p>
     * Quick and dirty hack to prevent the need for external dependencies in the extension Jar.
     *
     * @param s text to encode into a safe, quoted, JSON string
     * @return quoted JSON representation of the input text
     */
    public static String toJSON(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for(int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if(c == '"') {
                sb.append("\\\"");
            } else if(c == '/') {
                sb.append("\\/");
            } else if(c == '\\') {
                sb.append("\\\\");
            } else if(c == '\n') {
                sb.append("\\n");
            } else if(c == '\t') {
                sb.append("\\t");
            } else if(c == '\b') {
                sb.append("\\b");
            } else if(c == '\f') {
                sb.append("\\f");
            } else if(c == '\r') {
                sb.append("\\r");
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // {{ Constructors }}


    // {{ Instance methods }}
   @Override
   /**
    * Initialization method called by Burpsuite Montoya on loading of the extension.
    */
    public void initialize(MontoyaApi api) {
       //
       // Store for possible future use.
       //
        _api = api;
        //
        // Show some ID...
        //
        api.extension().setName(EXTENSION_NAME);
        //
        // Make sure we cleanup the output file in case of unload of the extension.
        // Some output formats require that the logical data structure is terminated
        // (notably XML and JSON)
        //
        api.extension().registerUnloadingHandler​(this);
        //
        // Store frequently used shortcuts to interesting API properties
        //
        _utils = api.utilities();
        _l = api.logging();
        //
        // Give some sign of life
        //
        _l.logToOutput("Greetings from the websocket hamper.");
        //
        // Initialize instance properties: these will define if and how websocket data
        // will be saved.
        //
        _save_on = true;
        _save_id = true;
        _save_direction = true;
        _direction_cs = "C-S";
        _direction_sc = "S-C";
        _save_url = true;
        _save_time = true;
        _wsmsg_date_time_fmt = "yyyy-MM-dd_HH-mm-ss-SSS";
        _save_data = true;
        _save_xml = true;
        _save_raw = false;
        _save_b64 = true;
        _sep_fld = ",";
        _sep_nl = "\n";
        _save_cdata = true;
        //
        // Add tab to Burpsuite interface
        //
        api.userInterface().registerSuiteTab("Hamper", new HamperSuiteTab());
        //
        // Create output file and keep it open for duration of Burpsuite run
        //
        try {
            openOutputFile(_outfilename, false, false, false, _save_xml, _save_csv, _save_json);
        } catch(Exception e) {
            _l.logToOutput("Failed to create to websockets storage file " + _outfilename);
            _l.logToOutput("caught: " + e + "\n" + Hamper.getStackTrace(e));
            _l.raiseErrorEvent("Failed to create to websockets storage file " + _outfilename);
            // throw an exception that will appear in our error stream
            //
            // (comment above shamelessly copied from Portswigger example code on Github)
            //
            throw new RuntimeException("%s -- caught %s".format(EXTENSION_NAME, e.toString()));
        }
        //
        // Create queue for buffering strings to write to the file
        //
        _q = new LinkedBlockingQueue<String>();
        _qd = new QueueDumper(_q);
        _qd.start();
        //
        // Register self as websocket event handler
        //
        _wsreg = api.websockets().registerWebSocketCreationHandler​(this);
    }

    @Override
    /**
     * Called by Burp when this extension is unloaded
     */
    public void extensionUnloaded() {
        _l.logToOutput("So long and thanks for all the fish...");
        //
        // Close logical data structure in output file, then close it.
        //
        synchronized(_syncobject) {
            try {
                closeOutputFile(_save_xml, _save_csv, _save_json);
            } catch(Exception e) {
                _l.logToOutput("Failed to close to websockets storage file " + _outfilename);
                _l.logToOutput("caught: " + e + "\n" + Hamper.getStackTrace(e));
                _l.raiseErrorEvent("Failed to close to websockets storage file " + _outfilename);
                // throw an exception that will appear in our error stream
                throw new RuntimeException("%s -- caught %s".format(EXTENSION_NAME, e.toString()));
            }
        }
    }
    /**
     * Open and initialize a new output file.
     * <p>
     * Current file (if any) will be properly terminated first.
     *
     * @param filnam name of new file to open/create
     * @param oldxml <code>true</code> if currently opened file is in XML format
     * @param oldcsv <code>true</code> if currently opened file is in CSV format
     * @param oldjson <code>true</code> if currently opened file is in JSON format
     * @param newxml <code>true</code> if new to be opened file is in XML format
     * @param newcsv <code>true</code> if new to be opened file is in CSV format
     * @param newjson <code>true</code> if new to be opened file is in JSON format
     */
    public void openOutputFile(String filnam, boolean oldxml, boolean oldcsv, boolean oldjson,
                               boolean newxml, boolean newcsv, boolean newjson) throws Exception {
        //
        // This code has to handle multi-threading carefully: at least one thread can be trying
        // to dump data from its input queue into the current output file.
        //
        // The plan of attack is:
        //
        // 1. create and initialize new output file (not synchronized with other threads)
        // 2. close current file (synchronized with other threads)
        // 3. mark new output file as "current" (still synchronized with other threads)
        // 4. if something goes wrong, keep the current output file open and bump the exception
        //    to the calling code, thus indicating something fishy happened
        //
        // First attempt to create new output file while still keeping the old file open
        // Bail out if creation fails (that is: do not catch any exception: leave that to caller)
        //
        FileWriter f = new FileWriter(filnam);
        //
        // Check if we need to initiate logical structures open in output file
        // (NOP for CSV files)
        //
        // Bail in case of exception (nothing to clean up regarding current output file)
        //
        try {
            if(newxml) {
                f.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                f.write("<wsmessages>\n");
            } else if(newjson) {
                f.write("[\n");
            }
        } catch(Exception e0) {
            try {
                //
                // Terminate new file in case of write issues
                //
                f.close();
            } catch(Exception e3) {
                //
                // NOP: not much we can do here
                //
                _l.logToOutput("Failed to initialize websockets storage file " + filnam);
                _l.logToOutput("caught: " + e3 + "\n" + Hamper.getStackTrace(e3));
            }
            //
            // Kick exception to caller
            //
            throw e0;
        }
        //
        // Only now is it safe to terminate the previous output file and initialize the new one
        //
        synchronized(_syncobject) {
            try {
                //
                // Close current file if necessary
                //
                closeOutputFile(oldxml, oldcsv, oldjson);
                //
                // Switch to new output file
                //
                _f = f;
            } catch(Exception e) {
                _l.logToOutput("Failed to close old websockets storage file " + _outfilename);
                _l.logToOutput("caught: " + e + "\n" + Hamper.getStackTrace(e));
                _l.raiseErrorEvent("Failed to close old websockets storage file " + _outfilename);
                //
                // Close new output file in case of errors
                //
                try {
                    f.close();
                } catch(Exception e2) {
                    //
                    // NOP: not much we can do here
                    //
                    _l.logToOutput("Failed to close new websockets storage file ");
                    _l.logToOutput("caught: " + e2 + "\n" + Hamper.getStackTrace(e2));
                }
                //
                // Re-throw exception to caller
                //
                throw e;
            }
        }
    }
    /**
     * Close output file
     * <p>
     * Note that this method is called from within a synchronized sections in <code>openOutpFile()</code>
     * and in the extension unload method,
     * and should therefore not be called from elsewhere, hence the <code>private</code> qualifier.
     *
     * @param oldxml <code>true</code> if currently opened file is in XML format
     * @param oldcsv <code>true</code> if currently opened file is in CSV format
     * @param oldjson <code>true</code> if currently opened file is in JSON format
     */
    private void closeOutputFile(boolean oldxml, boolean oldcsv, boolean oldjson) throws Exception {
        try {
            if(_f != null) {
                //
                // We currently have an output file open (not the case immediately after loading
                // the extension)
                //
                // Check if we need to close logical structures open in output file
                // (NOP for CSV files)
                //
                if(oldxml) {
                    _f.write("</wsmessages>\n");
                } else if(oldjson) {
                    _f.write("]\n");
                }
                //
                // Ok, current datastructure is properly terminated in the current file,
                // so now it is safe to actually close that file.
                //
                _f.close();
            }
            _f = null;
        } catch(Exception e) {
            _l.logToOutput("Failed to close websockets storage file " + _outfilename);
            _l.logToOutput("caught: " + e + "\n" + Hamper.getStackTrace(e));
            _l.raiseErrorEvent("Failed to close websockets storage file " + _outfilename);
            //
            // Re-throw exception to caller
            //
            throw e;
        }
    }
    /**
     * Write one single string (line) to the current output file.
     * <p>
     * @param line string to write to the output file: this text contains all format specific encoding
     */
    public void writeToOutputFile(String line) {
        synchronized(_syncobject) {
            try {
                if(_f != null) {
                    _f.write(line);
                    //
                    // Force a flush so that the data is safe if we are unceremoniously kicked out
                    //
                    _f.flush();
                } else {
                    _l.raiseErrorEvent("Failed to write to websockets storage file: " + line);
                }
            } catch(Exception e) {
                _l.logToOutput("Failed to write to websockets storage file " + _outfilename);
                _l.logToOutput("caught: " + e + "\n" + Hamper.getStackTrace(e));
                _l.raiseErrorEvent("Failed to write to websockets storage file " + _outfilename);
                // throw an exception that will appear in our error stream
                //
                // (comment above shamelessly copied from Portswigger example code on Github)
                //
                throw new RuntimeException("%s -- caught %s".format(EXTENSION_NAME, e.toString()));
            }
        }
    }
    @Override
    /**
     * Called when a new websocket is opened.
     * <p>
     * Currently we only create a new insance of <code>WSMessageHandler</code> that will process
     * messages intercepted on the new socket.
     * <p>
     * TODO: check if we need to intercept socket closure as well (by removing the registration?)
     */
    public void handleWebSocketCreated​(WebSocket ws, HttpRequest upgrade_request,
                                       ToolSource tool_source) {
        //
        // Register self as event handler for activity on the websocket
        //
        ws.registerHandler​(new WSMessageHandler(ws, upgrade_request, tool_source));
    }
    /**
     * Private helper class to handle websocket messages exchanged over one single websocket.
     * <p>
     * Each instance of this class represents one single websocket connection.
     */
    private class WSMessageHandler implements WebSocketHandler {
        /**
         * ID for websocket
         * <p>
         * Note that this ID can be different from the ID field in the Burp GUI.
         */
        private int _id;
        /**
         * Burp websocket message
         */
        private WebSocket _ws;
        /**
         * Request that opened the websocket
         */
        private HttpRequest _upgrade_request;
        /**
         * Burpsuite tool creating the websocket
         */
        private ToolSource _tool_source;
        /**
         * Websocket URL
         */
        private String _url;
        /**
         * Create new websocket message handler.
         */
        public WSMessageHandler(WebSocket ws, HttpRequest upgrade_request,
                                       ToolSource tool_source) {
            this._id = ++_count;
            this._ws = ws;
            this._upgrade_request = upgrade_request;
            this._tool_source = tool_source;
            this._url = upgrade_request.httpService().toString();
        }
        @Override
        /**
         * Handle intercepted message containing binary data
         */
        public WebSocketBinaryMessage handleBinaryMessage​(ByteArray bar, Direction direction) {
            try {
                if(_save_on) {
                    _q.add(formatBinary(bar, direction));
                }
            } catch(Throwable e) {
                _l.logToOutput("Failed to process text message");
                _l.logToOutput("caught: " + e + "\n" + Hamper.getStackTrace(e));
                _l.raiseErrorEvent("Failed to process text message");
            }
            //
            // Pass unchanged message back to Burp for further processing
            //
            return WebSocketBinaryMessage.continueWithBinaryMessage​(bar);
        }
        @Override
        /**
         * Handle intercepted message containing string data
         */
        public WebSocketTextMessage handleTextMessage​(String msg, Direction direction) {
            try {
                if(_save_on) {
                    _q.add(formatText(msg, direction));
                }
            } catch(Throwable e) {
                _l.logToOutput("Failed to process text message");
                _l.logToOutput("caught: " + e + "\n" + Hamper.getStackTrace(e));
                _l.raiseErrorEvent("Failed to process text message");
            }
            //
            // Pass unchanged message back to Burp for further processing
            //
            return WebSocketTextMessage.continueWithTextMessage​(msg);
        }
        /**
         * Format string websocket message for output.
         */
        private String formatText(String msg, Direction direction) {
            String o =  null;
            if(_save_xml) {
                o = formatTextToXML(msg, direction);
            } else if(_save_csv) {
                o = formatTextToCSV(msg, direction);
            } else if(_save_json) {
                o = formatTextToJSON(msg, direction);
            }
            return o;
        }
        /**
         * Format string websocket message for XML output.
         */
        private String formatTextToXML(String msg, Direction direction) {
            StringBuilder sb = new StringBuilder();
            sb.append("<wsmessage>");
            if(_save_id) {
                sb.append("<id>");
                sb.append(_id);
                sb.append("</id>");
            }
            if(_save_direction) {
                sb.append("<direction>");
                if(direction == Direction.CLIENT_TO_SERVER) {
                    sb.append(_direction_cs);
                } else {
                    sb.append(_direction_sc);
                }
                sb.append("</direction>");
            }
            if(_save_url) {
                sb.append("<url>");
                sb.append(_url);
                sb.append("</url>");
            }
            if(_save_time) {
                sb.append("<time>");
                sb.append(new SimpleDateFormat(_wsmsg_date_time_fmt).format(new Date()));
                sb.append("</time>");
            }
            if(_save_data) {
                sb.append("<data>");
                if(_save_cdata) {
                    sb.append("<![CDATA[");
                }
                sb.append(msg);
                if(_save_cdata) {
                    sb.append("]]>");
                }
                sb.append("</data>");
            }
            sb.append("</wsmessage>");
            return sb.toString();
        }
        /**
         * Format string websocket message for CSV output.
         */
        private String formatTextToCSV(String msg, Direction direction) {
            StringBuilder sb = new StringBuilder();
            if(_save_id) {
                sb.append(_id);
                sb.append(',');
            }
            if(_save_direction) {
                if(direction == Direction.CLIENT_TO_SERVER) {
                    sb.append(Hamper.toCSV(_direction_cs));
                } else {
                    sb.append(Hamper.toCSV(_direction_sc));
                }
                sb.append(',');
            }
            if(_save_url) {
                sb.append(Hamper.toCSV(_url));
                sb.append(',');
            }
            if(_save_time) {
                sb.append(Hamper.toCSV(new SimpleDateFormat(_wsmsg_date_time_fmt).format(new Date())));
                sb.append(',');
            }
            if(_save_data) {
                sb.append(Hamper.toCSV(msg));
                sb.append(',');
            }
            return sb.toString();
        }
        /**
         * Format string websocket message for output.
         */
        private String formatTextToJSON(String msg, Direction direction) {
            StringBuilder json = new StringBuilder();
            boolean prepend_comma = false;
            json.append('{');
            if(_save_id) {
                if(prepend_comma) {
                    json.append(',');
                }
                json.append("\"id\":");
                json.append(_id);
                prepend_comma = true;
            }
            if(_save_direction) {
                if(prepend_comma) {
                    json.append(',');
                }
                json.append("\"direction\":");
                if(direction == Direction.CLIENT_TO_SERVER) {
                    json.append(Hamper.toJSON(_direction_cs));
                } else {
                        json.append(Hamper.toJSON(_direction_sc));
                }
                prepend_comma = true;
            }
            if(_save_url) {
                if(prepend_comma) {
                    json.append(',');
                }
                json.append("\"url\":");
                json.append(Hamper.toJSON(_url));
                prepend_comma = true;
            }
            if(_save_time) {
                if(prepend_comma) {
                    json.append(',');
                }
                json.append("\"time\":");
                json.append(Hamper.toJSON(new SimpleDateFormat(_wsmsg_date_time_fmt).format(new Date())));
                prepend_comma = true;
            }
            if(_save_data) {
                if(prepend_comma) {
                    json.append(',');
                }
                json.append("\"data\":");
                json.append(Hamper.toJSON(msg));
            }
            json.append('}');
            return json.toString() + ',';
        }
        /**
         * Format binary websocket message for output.
         */
        private String formatBinary(ByteArray bar, Direction direction) {
            if(_save_xml) {
                return formatBinaryToXML(bar, direction);
            } else if(_save_csv) {
                return formatBinaryToCSV(bar, direction);
            } else if(_save_json) {
                return formatBinaryToJSON(bar, direction);
            }
            return null;
        }
        /**
         * Format binary websocket message for XML output.
         */
        private String formatBinaryToXML(ByteArray bar, Direction direction) {
            StringBuilder sb = new StringBuilder();
            sb.append("<wsmessage>");
            if(_save_id) {
                sb.append("<id>");
                sb.append(_id);
                sb.append("</id>");
            }
            if(_save_direction) {
                sb.append("<direction>");
                if(direction == Direction.CLIENT_TO_SERVER) {
                    sb.append(_direction_cs);
                } else {
                    sb.append(_direction_sc);
                }
                sb.append("</direction>");
            }
            if(_save_url) {
                sb.append("<url>");
                sb.append(_url);
                sb.append("</url>");
            }
            if(_save_time) {
                sb.append("<time>");
                sb.append(new SimpleDateFormat(_wsmsg_date_time_fmt).format(new Date()));
                sb.append("</time>");
            }
            if(_save_data) {
                if(_save_b64) {
                    sb.append("<data fmt=\"base64\">");
                    if(_save_cdata) {
                        sb.append("<![CDATA[");
                    }
                    sb.append(_utils.base64Utils().getEncoder().encodeToString(bar.getBytes()));
                    if(_save_cdata) {
                        sb.append("]]>");
                    }
                    sb.append("</data>");
                } else {
                    sb.append("<data>");
                    if(_save_cdata) {
                        sb.append("<![CDATA[");
                    }
                    sb.append(new String(bar.getBytes()));
                    if(_save_cdata) {
                        sb.append("]]>");
                    }
                    sb.append("</data>");
                }
            }
            sb.append("</wsmessage>");
            return sb.toString();
        }
        /**
         * Format binary websocket message for CSV output.
         */
        private String formatBinaryToCSV(ByteArray bar, Direction direction) {
            StringBuilder sb = new StringBuilder();
            if(_save_id) {
                sb.append(_id);
                sb.append(',');
            }
            if(_save_direction) {
                if(direction == Direction.CLIENT_TO_SERVER) {
                    sb.append(Hamper.toCSV(_direction_cs));
                } else {
                    sb.append(Hamper.toCSV(_direction_sc));
                }
                sb.append(',');
            }
            if(_save_url) {
                sb.append(Hamper.toCSV(_url));
                sb.append(',');
            }
            if(_save_time) {
                sb.append(Hamper.toCSV(new SimpleDateFormat(_wsmsg_date_time_fmt).format(new Date())));
                sb.append(',');
            }
            if(_save_data) {
                if(_save_b64) {
                    sb.append(Hamper.toCSV(_utils.base64Utils().getEncoder().encodeToString(bar.getBytes())));
                } else {
                    sb.append(Hamper.toCSV(new String(bar.getBytes())));
                }
                sb.append(',');
            }
            return sb.toString();
        }
        /**
         * Format string websocket message for JSON output.
         */
        private String formatBinaryToJSON(ByteArray bar, Direction direction) {
            StringBuilder json = new StringBuilder();
            boolean prepend_comma = false;
            json.append('{');
            if(_save_id) {
                if(prepend_comma) {
                    json.append(',');
                }
                json.append("\"id\":");
                json.append(_id);
                prepend_comma = true;
            }
            if(_save_direction) {
                if(prepend_comma) {
                    json.append(',');
                }
                json.append("\"direction\":");
                if(direction == Direction.CLIENT_TO_SERVER) {
                    json.append(Hamper.toJSON(_direction_cs));
                } else {
                        json.append(Hamper.toJSON(_direction_sc));
                }
                prepend_comma = true;
            }
            if(_save_url) {
                if(prepend_comma) {
                    json.append(',');
                }
                json.append("\"url\":");
                json.append(Hamper.toJSON(_url));
                prepend_comma = true;
            }
            if(_save_time) {
                if(prepend_comma) {
                    json.append(',');
                }
                json.append("\"time\":");
                json.append(Hamper.toJSON(new SimpleDateFormat(_wsmsg_date_time_fmt).format(new Date())));
                prepend_comma = true;
            }
            if(_save_data) {
                if(prepend_comma) {
                    json.append(',');
                }
                json.append("\"data\":");
                if(_save_b64) {
                    json.append(Hamper.toJSON(_utils.base64Utils().getEncoder().encodeToString(bar.getBytes())));
                } else {
                    json.append(Hamper.toJSON(new String(bar.getBytes())));
                }
            }
            json.append('}');
            return json.toString() + ',';
        }
    }
    /**
     * Dump queue contents on buffered writer.
     * <p>
     * This class serves as a memory buffer between multiple, high frequence, threads
     * wanting to dump data into one single output file. By keeping the data in a memory
     * queue, client threads are not blocked for relative long periods of time while data is
     * being flushed to disk.
     */
    private class QueueDumper extends Thread {
        // {{ Class members }}

        // {{ Instance members }}
        /**
         * Memory queue with locking mechanism in place allowing for multiple client threads.
         */
        protected LinkedBlockingQueue<String> _in;

        // {{ Class methods }}

        // {{ Constructors }}

        /**
         * Public constructor.
         */
        public QueueDumper(LinkedBlockingQueue<String> que) {
            _in = que;
        }

        // {{ Instance methods }}
        /**
         * This is where the action is for this class: take items from the queue and dump in the
         * output file.
         */
        public void run() {
            String line;
            try {
                while(true) {
                    line = _in.take() + '\n';
                    writeToOutputFile(line);
                }
            } catch(Exception e) {
                //
                // NOP for now
                //
                _l.logToOutput("Failed to write to websockets storage file");
                _l.logToOutput("caught: " + e + "\n" + Hamper.getStackTrace(e));
                _l.raiseErrorEvent("~~~ QueueDumper.run() -- caught: " + e + "\n" +
                                   Hamper.getStackTrace(e));
                // throw an exception that will appear in our error stream
                //
                // (comment above shamelessly copied from Portswigger example code on Github)
                //
                throw new RuntimeException("~~~ QueueDumper.run() -- caught: " + e);
            }
        }
    }
    /**
     * Helper class implementing the Burp extension specific tab in the GUI.
     */
    private class HamperSuiteTab extends JPanel {
        /**
         * Create the content for this extension's tab in the GUI and assign apropriate listeners.
         */
        public HamperSuiteTab() {
            super();
            JPanel tab = new JPanel();
            tab.setLayout(new BoxLayout(tab, BoxLayout.Y_AXIS));
            tab.add(new JLabel("Hamper: where all your used (web)socks go..."));
            tab.add(new JLabel(" "));
            //
            // Create Swing components corresponding to internal state properties
            //
            _ckbx_save_on = new JCheckBox("Save websocket messages?", _save_on);
            _txt_direction_cs = new JTextField(_direction_cs, 10);
            _txt_direction_sc = new JTextField(_direction_sc, 10);
            _ckbx_save_id = new JCheckBox("Save websocket ID?", _save_id);
            _ckbx_save_direction = new JCheckBox("Save message direction?", _save_direction);
            _ckbx_save_url = new JCheckBox("Save URL?", _save_url);
            _ckbx_save_time = new JCheckBox("Save time?", _save_time);
            _txt_wsmsg_date_time_fmt = new JTextField(_wsmsg_date_time_fmt, 30);
            _ckbx_save_data = new JCheckBox("Save message body?", _save_data);
            _ckbx_save_b64 = new JCheckBox("Base64 encode payload?", _save_b64);
            _ckbx_save_cdata = new JCheckBox("Use XML CDATA for payload?", _save_cdata);
            _txt_outfil = new JTextField(_outfilename, 40);
            _txt_outfil.setEditable(false);	// will be set from filechooser output

            _radio_save_xml = new JRadioButton("XML", _save_xml);
            _radio_save_csv = new JRadioButton("CSV", _save_csv);
            _radio_save_json = new JRadioButton("JSON", _save_json);
            _radio_save_raw = new JRadioButton("Raw", _save_raw);
            //
            // Now populate GUI panel with above created components
            //
            JPanel tmppane = new JPanel();
            tmppane.setLayout(new BoxLayout(tmppane, BoxLayout.Y_AXIS));
            tmppane.add(_ckbx_save_on);
            tmppane.add(_ckbx_save_id);
            tmppane.add(_ckbx_save_url);
            tmppane.add(_ckbx_save_direction);
            tmppane.add(_ckbx_save_time);
            tmppane.add(_ckbx_save_data);
            tmppane.add(_ckbx_save_b64);
            tmppane.add(_ckbx_save_cdata);
            tab.add(tmppane);
        
            tmppane = new JPanel();
            tmppane.add(new JLabel("Message client->server:"));
            tmppane.add(_txt_direction_cs);
            tab.add(tmppane);

            tmppane = new JPanel();
            tmppane.add(new JLabel("Message server->client:"));
            tmppane.add(_txt_direction_sc);
            tab.add(tmppane);

            tmppane = new JPanel();
            tmppane.add(new JLabel("Date/Time format string:"));
            tmppane.add(_txt_wsmsg_date_time_fmt);
            tab.add(tmppane);

            ButtonGroup group = new ButtonGroup();
            group.add(_radio_save_xml);
            group.add(_radio_save_csv);
            group.add(_radio_save_json);
            group.add(_radio_save_raw);
        
            tmppane = new JPanel();
            tmppane.add(new JLabel("Output format:"));
            JPanel radiopane = new JPanel();
            radiopane.setLayout(new BoxLayout(radiopane, BoxLayout.Y_AXIS));
            radiopane.add(_radio_save_xml);
            radiopane.add(_radio_save_csv);
            radiopane.add(_radio_save_json);
            radiopane.add(_radio_save_raw);
            tmppane.add(radiopane);
            tab.add(tmppane);

            tmppane = new JPanel();
            tmppane.add(new JLabel("Outfile:"));
            tmppane.add(_txt_outfil);
            JButton btn_selfil = new JButton("Change");
            btn_selfil.addActionListener(e -> {
                    //
                    // Change output file button has been clicked: get name for new output file and
                    // create that file.
                    //
                    // Be careful to leave the original output file in place in case creation of the
                    // new file fails.
                    //
                    JFileChooser chooser = new JFileChooser();
                    chooser.setCurrentDirectory(_tmpcurdir);
                    chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                    chooser.setAcceptAllFileFilterUsed(false);
                    int returnVal = chooser.showOpenDialog(this);
                    if(returnVal == JFileChooser.APPROVE_OPTION) {
                        _tmpoutfilename = chooser.getCurrentDirectory().getAbsolutePath() + "/" +
                            chooser.getSelectedFile().getName();
                        _txt_outfil.setText(_tmpoutfilename);
                        _tmpcurdir = (new File(_tmpoutfilename)).getAbsoluteFile().getParentFile();
                    }
                });
            tmppane.add(btn_selfil);
            tab.add(tmppane);

            JButton btn_save = new JButton("Save");
            btn_save.addActionListener(e -> {
                    //
                    // Save options button has been clicked: store new values from GUI components
                    // into corresponding internal variables. But first grab current values into
                    // temporary variables so that we can rollback in case initialization of the new
                    // values fails (notably when starting a new output file)
                    //
                    boolean save_on = _ckbx_save_on.isSelected();
                    String direction_cs = _txt_direction_cs.getText();
                    String direction_sc = _txt_direction_sc.getText();
                    boolean save_id = _ckbx_save_id.isSelected();
                    boolean save_direction = _ckbx_save_direction.isSelected();
                    boolean save_url = _ckbx_save_url.isSelected();
                    boolean save_time = _ckbx_save_time.isSelected();
                    String wsmsg_date_time_fmt = _txt_wsmsg_date_time_fmt.getText();
                    boolean save_data = _ckbx_save_data.isSelected();
                    boolean save_b64 = _ckbx_save_b64.isSelected();
                    boolean save_cdata = _ckbx_save_cdata.isSelected();

                    boolean save_xml = _radio_save_xml.isSelected();
                    boolean save_csv = _radio_save_csv.isSelected();
                    boolean save_json = _radio_save_json.isSelected();
                    boolean save_raw = _radio_save_raw.isSelected();
                    //
                    // Attenpt to open output file if new file was selected
                    //
                    if(!_outfilename.equals(_tmpoutfilename)) {
                        try {
                            openOutputFile(_tmpoutfilename, _save_xml, _save_csv, _save_json,
                                           save_xml, save_csv, save_json);
                        } catch(Exception e1) {
                            _l.logToOutput("Failed to close websockets storage file " + _tmpoutfilename);
                            _l.logToOutput("caught: " + e1 + "\n" + Hamper.getStackTrace(e1));
                            _l.raiseErrorEvent("Failed to close websockets storage file " +
                                               _tmpoutfilename);
                            //
                            // In case of problems: reset visible GUI components to their internal
                            // property values
                            //
                            resetGUIValues();
                            return;
                        }
                    }
                    //
                    // All is well that ends well: now store new settings
                    //
                    _save_on = save_on;
                    _direction_cs = direction_cs;
                    _direction_sc = direction_sc;
                    _save_id = save_id;
                    _save_direction = save_direction;
                    _save_url = save_url;
                    _save_time = save_time;
                    _wsmsg_date_time_fmt = wsmsg_date_time_fmt;
                    _save_data = save_data;
                    _save_b64 = save_b64;
                    _save_cdata = save_cdata;

                    _save_xml = save_xml;
                    _save_csv = save_csv;
                    _save_json = save_json;
                    _save_raw = save_raw;
                    _curdir = _tmpcurdir;
                    _outfilename = _tmpoutfilename;
                });


            JButton btn_cancel = new JButton("Cancel");
            btn_cancel.addActionListener(e -> {
                    //
                    // Cancel button has been clicked: restore old (current) values in the GUI
                    // components to reflect their actual current state.
                    //
                    resetGUIValues();
                });

            tmppane = new JPanel();
            tmppane.add(btn_save);
            tmppane.add(btn_cancel);
            tab.add(tmppane);
            add(tab);
        }
        /**
         * Reset GUI values from their corresponding property values
         */
        public void resetGUIValues() {
            _ckbx_save_on.setSelected(_save_on);
            _txt_direction_cs.setText(_direction_cs);
            _txt_direction_sc.setText(_direction_sc);
            _ckbx_save_id.setSelected(_save_id);
            _ckbx_save_direction.setSelected(_save_direction);
            _ckbx_save_url.setSelected(_save_url);
            _ckbx_save_time.setSelected(_save_time);
            _txt_wsmsg_date_time_fmt.setText(_wsmsg_date_time_fmt);
            _ckbx_save_data.setSelected(_save_data);
            _ckbx_save_b64.setSelected(_save_b64);
            _ckbx_save_cdata.setSelected(_save_cdata);

            _radio_save_xml.setSelected(_save_xml);
            _radio_save_csv.setSelected(_save_csv);
            _radio_save_json.setSelected(_save_json);
            _radio_save_raw.setSelected(_save_raw);
            _tmpcurdir = _curdir;
            _tmpoutfilename = _outfilename;
        }
    }
}
