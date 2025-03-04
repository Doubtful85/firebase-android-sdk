// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.appdistribution.impl;

import android.content.Context;
import android.content.pm.PackageManager;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import com.google.android.gms.common.util.AndroidUtilsLight;
import com.google.android.gms.common.util.Hex;
import com.google.firebase.FirebaseApp;
import com.google.firebase.appdistribution.FirebaseAppDistributionException;
import com.google.firebase.appdistribution.FirebaseAppDistributionException.Status;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONException;
import org.json.JSONObject;

/** Client that makes FIS-authenticated GET and POST requests to the App Distribution Tester API. */
class TesterApiHttpClient {

  @VisibleForTesting static final String APP_TESTERS_HOST = "firebaseapptesters.googleapis.com";
  private static final String REQUEST_METHOD_GET = "GET";
  private static final String REQUEST_METHOD_POST = "POST";
  private static final String CONTENT_TYPE_HEADER_KEY = "Content-Type";
  private static final String JSON_CONTENT_TYPE = "application/json";
  private static final String CONTENT_ENCODING_HEADER_KEY = "Content-Encoding";
  private static final String GZIP_CONTENT_ENCODING = "gzip";
  private static final String API_KEY_HEADER = "x-goog-api-key";
  private static final String INSTALLATION_AUTH_HEADER = "X-Goog-Firebase-Installations-Auth";
  private static final String X_ANDROID_PACKAGE_HEADER_KEY = "X-Android-Package";
  private static final String X_ANDROID_CERT_HEADER_KEY = "X-Android-Cert";
  // Format of "X-Client-Version": "{ClientId}/{ClientVersion}"
  private static final String X_CLIENT_VERSION_HEADER_KEY = "X-Client-Version";

  private static final String TAG = "TesterApiClient:";
  private static final int DEFAULT_BUFFER_SIZE = 8192;

  private final FirebaseApp firebaseApp;
  private final HttpsUrlConnectionFactory httpsUrlConnectionFactory;

  TesterApiHttpClient(@NonNull FirebaseApp firebaseApp) {
    this(firebaseApp, new HttpsUrlConnectionFactory());
  }

  @VisibleForTesting
  TesterApiHttpClient(
      @NonNull FirebaseApp firebaseApp,
      @NonNull HttpsUrlConnectionFactory httpsUrlConnectionFactory) {
    this.firebaseApp = firebaseApp;
    this.httpsUrlConnectionFactory = httpsUrlConnectionFactory;
  }

  /**
   * Make a GET request to the tester API at the given path using a FIS token for auth.
   *
   * @return the response body
   */
  JSONObject makeGetRequest(String tag, String path, String token)
      throws FirebaseAppDistributionException {
    HttpsURLConnection connection = null;
    try {
      connection = openHttpsUrlConnection(getTesterApiUrl(path), token);
      return readResponse(tag, connection);
    } catch (IOException e) {
      throw getException(tag, ErrorMessages.NETWORK_ERROR, Status.NETWORK_FAILURE, e);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  /**
   * Make a POST request to the tester API at the given path using a FIS token for auth.
   *
   * @return the response body
   */
  JSONObject makePostRequest(String tag, String path, String token, String requestBody)
      throws FirebaseAppDistributionException {
    HttpsURLConnection connection = null;
    try {
      connection = openHttpsUrlConnection(getTesterApiUrl(path), token);
      connection.setDoOutput(true);
      connection.setRequestMethod(REQUEST_METHOD_POST);
      connection.addRequestProperty(CONTENT_TYPE_HEADER_KEY, JSON_CONTENT_TYPE);
      connection.addRequestProperty(CONTENT_ENCODING_HEADER_KEY, GZIP_CONTENT_ENCODING);
      connection.getOutputStream();
      GZIPOutputStream gzipOutputStream = new GZIPOutputStream(connection.getOutputStream());
      try {
        gzipOutputStream.write(requestBody.getBytes("UTF-8"));
      } catch (IOException e) {
        throw getException(tag, "Error compressing network request body", Status.UNKNOWN, e);
      } finally {
        gzipOutputStream.close();
      }
      return readResponse(tag, connection);
    } catch (IOException e) {
      throw getException(tag, ErrorMessages.NETWORK_ERROR, Status.NETWORK_FAILURE, e);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private static String getTesterApiUrl(String path) {
    return String.format("https://%s/%s", APP_TESTERS_HOST, path);
  }

  private static JSONObject readResponse(String tag, HttpsURLConnection connection)
      throws IOException, FirebaseAppDistributionException {
    int responseCode = connection.getResponseCode();
    String responseBody = readResponseBody(connection);
    LogWrapper.getInstance().v(tag, String.format("Response (%d): %s", responseCode, responseBody));
    if (!isResponseSuccess(responseCode)) {
      throw getExceptionForHttpResponse(tag, responseCode);
    }
    return parseJson(tag, responseBody);
  }

  private static JSONObject parseJson(String tag, String json)
      throws FirebaseAppDistributionException {
    try {
      return new JSONObject(json);
    } catch (JSONException e) {
      throw getException(tag, ErrorMessages.JSON_PARSING_ERROR, Status.UNKNOWN, e);
    }
  }

  private static String readResponseBody(HttpsURLConnection connection) throws IOException {
    boolean isSuccess = isResponseSuccess(connection.getResponseCode());
    try (InputStream inputStream =
        isSuccess ? connection.getInputStream() : connection.getErrorStream()) {
      if (inputStream == null && !isSuccess) {
        // If the server returns a response with an error code and no response body, getErrorStream
        // returns null. We return an empty string to reflect the empty body.
        return "";
      }
      return convertInputStreamToString(new BufferedInputStream(inputStream));
    }
  }

  private static boolean isResponseSuccess(int responseCode) {
    return responseCode >= 200 && responseCode < 300;
  }

  private HttpsURLConnection openHttpsUrlConnection(String url, String authToken)
      throws IOException {
    LogWrapper.getInstance().v("Opening connection to " + url);
    Context context = firebaseApp.getApplicationContext();
    HttpsURLConnection httpsURLConnection;
    httpsURLConnection = httpsUrlConnectionFactory.openConnection(url);
    httpsURLConnection.setRequestMethod(REQUEST_METHOD_GET);
    httpsURLConnection.setRequestProperty(API_KEY_HEADER, firebaseApp.getOptions().getApiKey());
    httpsURLConnection.setRequestProperty(INSTALLATION_AUTH_HEADER, authToken);
    httpsURLConnection.addRequestProperty(X_ANDROID_PACKAGE_HEADER_KEY, context.getPackageName());
    httpsURLConnection.addRequestProperty(
        X_ANDROID_CERT_HEADER_KEY, getFingerprintHashForPackage());
    httpsURLConnection.addRequestProperty(
        X_CLIENT_VERSION_HEADER_KEY, String.format("android-sdk/%s", BuildConfig.VERSION_NAME));
    return httpsURLConnection;
  }

  private static FirebaseAppDistributionException getExceptionForHttpResponse(
      String tag, int responseCode) {
    switch (responseCode) {
      case 400:
        return getException(tag, "Bad request", Status.UNKNOWN);
      case 401:
        return getException(tag, ErrorMessages.AUTHENTICATION_ERROR, Status.AUTHENTICATION_FAILURE);
      case 403:
        return getException(tag, ErrorMessages.AUTHORIZATION_ERROR, Status.AUTHENTICATION_FAILURE);
      case 404:
        // TODO(lkellogg): Change this to a different status once 404s no longer indicate missing
        //  access (the backend should return 403s for those cases, including when the resource
        //  doesn't exist but the tester doesn't have the access to see that information)
        return getException(tag, ErrorMessages.NOT_FOUND_ERROR, Status.AUTHENTICATION_FAILURE);
      case 408:
      case 504:
        return getException(tag, ErrorMessages.TIMEOUT_ERROR, Status.NETWORK_FAILURE);
      default:
        return getException(tag, "Received error status: " + responseCode, Status.UNKNOWN);
    }
  }

  private static FirebaseAppDistributionException getException(
      String tag, String message, Status status) {
    return new FirebaseAppDistributionException(tagMessage(tag, message), status);
  }

  private static FirebaseAppDistributionException getException(
      String tag, String message, Status status, Throwable t) {
    return new FirebaseAppDistributionException(tagMessage(tag, message), status, t);
  }

  private static String tagMessage(String tag, String message) {
    return String.format("%s: %s", tag, message);
  }

  private static String convertInputStreamToString(InputStream is) throws IOException {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
    int length;
    while ((length = is.read(buffer)) != -1) {
      result.write(buffer, 0, length);
    }
    return result.toString();
  }

  /** Gets the Android package's SHA-1 fingerprint. */
  private String getFingerprintHashForPackage() {
    Context context = firebaseApp.getApplicationContext();
    byte[] hash;

    try {
      hash = AndroidUtilsLight.getPackageCertificateHashBytes(context, context.getPackageName());

      if (hash == null) {
        LogWrapper.getInstance()
            .e(
                TAG
                    + "Could not get fingerprint hash for X-Android-Cert header. Package is not signed: "
                    + context.getPackageName());
        return null;
      } else {
        return Hex.bytesToStringUppercase(hash, /* zeroTerminated= */ false);
      }
    } catch (PackageManager.NameNotFoundException e) {
      LogWrapper.getInstance()
          .e(
              TAG
                  + "Could not get fingerprint hash for X-Android-Cert header. No such package: "
                  + context.getPackageName(),
              e);
      return null;
    }
  }
}
