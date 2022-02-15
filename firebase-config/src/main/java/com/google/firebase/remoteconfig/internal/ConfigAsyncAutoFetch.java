
package com.google.firebase.remoteconfig.internal;

import android.os.AsyncTask;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.logging.Logger;

// Class extended from AsyncTask that will allow for async monitoring of Realtime RC HTTP/1.1 chunked stream.
public class ConfigAsyncAutoFetch extends AsyncTask<String, Void, Void> {
    private final HttpURLConnection httpURLConnection;
    private final ConfigFetchHandler configFetchHandler;
    private final Map<String, ConfigRealtimeHTTPClient.RealTimeEventListener> eventListeners;
    private static final Logger logger = Logger.getLogger("Real_Time_RC");
    private final ConfigRealtimeHTTPClient.RealTimeEventListener retryCallback;
    private static final String REALTIME_PONG_URL_STRING = "http://10.0.2.2:8080";

    // Pong HTTP components
    private URL realtimePongURL;
    private HttpURLConnection pongHttpURLConnection;

    public ConfigAsyncAutoFetch(HttpURLConnection httpURLConnection,
                                ConfigFetchHandler configFetchHandler,
                                Map<String, ConfigRealtimeHTTPClient.RealTimeEventListener> eventListeners,
                                ConfigRealtimeHTTPClient.RealTimeEventListener retryCallback) {
        this.httpURLConnection = httpURLConnection;
        this.configFetchHandler = configFetchHandler;
        this.eventListeners = eventListeners;
        this.retryCallback = retryCallback;

        try {
            this.realtimePongURL = new URL(this.REALTIME_PONG_URL_STRING);
        } catch (MalformedURLException ex) {
            logger.info("URL is malformed");
        }
    }


    @Override
    protected Void doInBackground(String... strings) {
        this.listenForNotifications();
        return null;
    }

    // Check connection and establish InputStream
    private void listenForNotifications() {
        if (this.httpURLConnection != null) {
            try {
                int responseCode = httpURLConnection.getResponseCode();
                if (responseCode == 200) {
                    InputStream inputStream = httpURLConnection.getInputStream();
                    handleNotifications(inputStream);
                    inputStream.close();
                } else {
                    logger.info("Can't open Realtime stream");
                    this.retryCallback.onEvent();
                }

            } catch (IOException ex) {
                logger.info("Error handling messages.");
                this.retryCallback.onEvent();
            }
        }
        logger.info("No more messages to receive.");
    }

    // Auto-fetch new config and execute callbacks on each new message
    private void handleNotifications(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader((new InputStreamReader(inputStream)));
        String message;
        while ((message = reader.readLine()) != null) {
            logger.info(message);
            if (message.contains("Ping")) {
                this.sendPong();
            } else {
                Task<ConfigFetchHandler.FetchResponse> fetchTask = this.configFetchHandler.fetch(0L);
                fetchTask.onSuccessTask((unusedFetchResponse) ->
                        {
                            logger.info("Finished Fetching new updates.");
                            // Execute callbacks for listeners.
                            for (ConfigRealtimeHTTPClient.RealTimeEventListener listener : eventListeners.values()) {
                                listener.onEvent();
                            }
                            return Tasks.forResult(null);
                        }
                );
            }
        }
        reader.close();
    }

    // Sends pong response for any ping received from the server.
    public void sendPong() {
        if (this.pongHttpURLConnection == null) {
            try {
                this.pongHttpURLConnection = (HttpURLConnection) this.realtimePongURL.openConnection();
                // TODO add headers
            } catch (Exception ex) {
                logger.info("Can't connect to pong endpoint due to " + ex.toString());
                // TODO integrate retry function with pong sender
            }
        }

        try {
            int responseCode = this.pongHttpURLConnection.getResponseCode();
            if (responseCode != 200) {
                logger.info("Request failed with code " + responseCode);
            }
        } catch (IOException ex) {
            logger.info("Can't get response code.");
        }
    }
}
