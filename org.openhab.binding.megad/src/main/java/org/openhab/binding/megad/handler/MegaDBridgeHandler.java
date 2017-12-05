package org.openhab.binding.megad.handler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.megad.MegaDBindingConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link MegaDBridgeHandler} is responsible for bridge.
 *
 * This is server for incoming connections from megad
 *
 * @author Petr Shatsillo - Initial contribution
 */

public class MegaDBridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(MegaDBridgeHandler.class);

    private final boolean isConnect = false;
    private int port;
    private ScheduledFuture<?> pollingJob;
    Socket socket = null;
    private ServerSocket serverSocket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean isRunning = true;
    private final int refreshInterval = 300;
    MegaDHandler megaDHandler;

    public MegaDBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // TODO Auto-generated method stub

    }

    @Override
    public void updateStatus(ThingStatus status) {
        super.updateStatus(status);
        updateThingHandlersStatus(status);

    }

    public void updateStatus() {
        if (isConnect) {
            updateStatus(ThingStatus.ONLINE);
        } else {
            updateStatus(ThingStatus.OFFLINE);
        }

    }

    private final Map<String, MegaDHandler> thingHandlerMap = new HashMap<String, MegaDHandler>();

    public void registerMegadThingListener(MegaDHandler thingHandler) {
        if (thingHandler == null) {
            throw new IllegalArgumentException("It's not allowed to pass a null ThingHandler.");
        } else {

            String thingID = thingHandler.getThing().getConfiguration().get("hostname").toString() + "."
                    + thingHandler.getThing().getConfiguration().get("port").toString();

            if (thingHandlerMap.get(thingID) != null) {
                thingHandlerMap.remove(thingID);
            }
            logger.debug("thingHandler for thing: '{}'", thingID);
            if (thingHandlerMap.get(thingID) == null) {
                thingHandlerMap.put(thingID, thingHandler);
                logger.debug("register thingHandler for thing: {}", thingHandler);
                updateThingHandlerStatus(thingHandler, this.getStatus());
                if (thingID.equals("localhost.")) {
                    updateThingHandlerStatus(thingHandler, ThingStatus.OFFLINE);
                }
                // sendSocketData("get " + thingID);
            } else {
                logger.debug("thingHandler for thing: '{}' already registerd", thingID);
                updateThingHandlerStatus(thingHandler, this.getStatus());
            }

        }
    }

    public void unregisterThingListener(MegaDHandler thingHandler) {
        if (thingHandler != null) {
            String thingID = thingHandler.getThing().getConfiguration().get("hostname").toString() + "."
                    + thingHandler.getThing().getConfiguration().get("port").toString();
            if (thingHandlerMap.remove(thingID) == null) {
                logger.debug("thingHandler for thing: {} not registered", thingID);
            } else {
                updateThingHandlerStatus(thingHandler, ThingStatus.OFFLINE);
            }
        }

    }

    private void updateThingHandlerStatus(MegaDHandler thingHandler, ThingStatus status) {
        thingHandler.updateStatus(status);
    }

    private void updateThingHandlersStatus(ThingStatus status) {
        for (Map.Entry<String, MegaDHandler> entry : thingHandlerMap.entrySet()) {
            updateThingHandlerStatus(entry.getValue(), status);
        }
    }

    public ThingStatus getStatus() {
        return getThing().getStatus();
    }

    @Override
    public void initialize() {
        logger.debug("Initializing Megad bridge handler {}", this.toString());

        MegaDBindingConfiguration configuration = getConfigAs(MegaDBindingConfiguration.class);
        port = configuration.port;

        // updateStatus(ThingStatus.ONLINE);

        startBackgroundService();
    }

    private void startBackgroundService() {
        logger.debug("Starting background service...");
        if (pollingJob == null || pollingJob.isCancelled()) {
            pollingJob = scheduler.scheduleAtFixedRate(pollingRunnable, 0, refreshInterval, TimeUnit.SECONDS);
        }
    }

    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            logger.debug("Polling job called");
            try {
                serverSocket = new ServerSocket(port);
                logger.debug("MegaD Server open port {}", port);
                isRunning = true;
                updateStatus(ThingStatus.ONLINE);
            } catch (IOException e) {
                logger.error("ERROR! Cannot open port: {}", e.getMessage());
                updateStatus(ThingStatus.OFFLINE);
            }

            while (isRunning) {
                try {
                    socket = serverSocket != null ? serverSocket.accept() : null;
                } catch (IOException e) {
                    logger.error("ERROR in bridge. Incoming server has error: {}", e.getMessage());
                }
                if (!serverSocket.isClosed()) {
                    new Thread(startHttpSocket());
                }
            }
        }

    };

    protected Runnable startHttpSocket() {
        try {
            this.inputStream = socket.getInputStream();
            this.outputStream = socket.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }

        readInput();
        writeResponse();
        return null;
    }

    private Map<String, String> splitQuery(String url) throws UnsupportedEncodingException {
        if ((!url.contains("GET")) || (!url.contains("?"))) {
            return null;
        }
        Map<String, String> query_pairs = new LinkedHashMap<String, String>();
        String query = url.split(" ")[1].replace("/?", "").trim();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            if (pair.length() == 0) {
                continue;
            }

            int idx = pair.indexOf("=");
            query_pairs.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                    URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));

        }
        return query_pairs;
    }

    private String generateThingID(String port) {
        String hostAddress = this.socket.getInetAddress().getHostAddress();
        if (hostAddress.equals("0:0:0:0:0:0:0:1")) {
            hostAddress = "localhost";
        }
        String thingID = hostAddress + "." + port;
        return thingID;
    }

    private void readInput() {
        BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
        while (isRunning) {
            String incomingUrl;
            // String[] getCommands;
            // String thingID, hostAddress;
            try {
                incomingUrl = br.readLine();
                if (incomingUrl == null || incomingUrl.trim().length() == 0) {
                    break;
                }

                logger.debug("Incoming from Megad({}): {}", this.socket.getInetAddress().getHostAddress(), incomingUrl);
                Map<String, String> queryPairs = splitQuery(incomingUrl);

                if (queryPairs == null) {
                    logger.error("Invalid format for incoming string: {}", incomingUrl);
                    break;
                }

                // String[] CommandParse = incomingUrl.split("[/ ]");
                // String command = CommandParse[2];
                // getCommands = command.split("[?&>=]");

                for (String key : queryPairs.keySet()) {
                    logger.debug("Query pairs {} = {}", key, queryPairs.get(key));
                }

                if (queryPairs.containsKey("all")) {
                    // TODO: Переписать all
                    logger.debug("Srv-loop incoming param: {}", queryPairs.get("all"));

                    /*
                     * String[] parsedStatus = queryPairs.get("all").split("[;]");
                     * for (int i = 0; parsedStatus.length > i; i++) {
                     * String[] mode = parsedStatus[i].split("[/]");
                     * if (mode[0].contains("ON")) {
                     * thingID = generateThingID(String.valueOf(i));
                     * megaDHandler = thingHandlerMap.get(thingID);
                     * if (megaDHandler != null) {
                     * logger.debug("Updating: {} Value is: {}", thingID, parsedStatus[i]);
                     * megaDHandler.updateValues("hostAddress", mode, OnOffType.ON);
                     * }
                     * } else if (mode[0].contains("OFF")) {
                     * thingID = generateThingID(String.valueOf(i));
                     * megaDHandler = thingHandlerMap.get(thingID);
                     * if (megaDHandler != null) {
                     * logger.debug("Updating: {} Value is: {}", thingID, mode);
                     * megaDHandler.updateValues("hostAddress", mode, OnOffType.OFF);
                     * }
                     * } else {
                     * logger.debug("Not a switch");
                     * }
                     * }
                     */
                } else {
                    if (!queryPairs.containsKey("pt")) {
                        logger.error("Port in incoming url not found {}", incomingUrl);
                        break;
                    }
                    String thingID = generateThingID(queryPairs.get("pt"));
                    megaDHandler = thingHandlerMap.get(thingID);
                    if (megaDHandler != null) {
                        megaDHandler.updateValues(queryPairs);
                    }
                }

            } catch (IOException e) {
                logger.error(e.getLocalizedMessage());
            }
        }
    }

    private void writeResponse() {
        String result = "HTTP/1.1 200 OK\r\n" + "Content-Type: text/html\r\n" + "Content-Length: " + 0 + "\r\n"
                + "Connection: close\r\n\r\n";
        try {
            outputStream.write(result.getBytes());
            inputStream.close();
            outputStream.close();
        } catch (IOException e) {
            logger.error(e.getLocalizedMessage());
        }
    }

    @Override
    public void dispose() {
        logger.debug("Dispose Megad bridge handler {}", this.toString());

        if (pollingJob != null && !pollingJob.isCancelled()) {
            pollingJob.cancel(true);
            pollingJob = null;
        }
        isRunning = false;
        try {
            serverSocket.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        updateStatus(ThingStatus.OFFLINE); // Set all State to offline
    }
}
