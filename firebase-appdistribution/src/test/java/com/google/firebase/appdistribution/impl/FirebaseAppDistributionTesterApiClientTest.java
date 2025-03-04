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

import static com.google.common.truth.Truth.assertThat;
import static com.google.firebase.appdistribution.impl.TestUtils.assertTaskFailure;
import static com.google.firebase.appdistribution.impl.TestUtils.awaitAsyncOperations;
import static com.google.firebase.appdistribution.impl.TestUtils.readTestJSON;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

import androidx.test.core.app.ApplicationProvider;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.appdistribution.BinaryType;
import com.google.firebase.appdistribution.FirebaseAppDistributionException;
import com.google.firebase.appdistribution.FirebaseAppDistributionException.Status;
import com.google.firebase.inject.Provider;
import com.google.firebase.installations.FirebaseInstallationsApi;
import com.google.firebase.installations.InstallationTokenResult;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class FirebaseAppDistributionTesterApiClientTest {

  private static final String TEST_API_KEY = "AIzaSyabcdefghijklmnopqrstuvwxyz1234567";
  private static final String TEST_APP_ID_1 = "1:123456789:android:abcdef";
  private static final String TEST_PROJECT_ID = "project-id";
  private static final String TEST_PROJECT_NUMBER = "123456789";
  private static final String TEST_AUTH_TOKEN = "fad.auth.token";
  private static final String TEST_FID_1 = "cccccccccccccccccccccc";
  private static final String RELEASE_NAME =
      "projects/123456789/installations/cccccccccccccccccccccc/releases/release-id";
  private static final String FEEDBACK_NAME =
      "projects/123456789/installations/cccccccccccccccccccccc/releases/release-id/feedback/feedback-id";
  private static final String FEEDBACK_TEXT = "The feedback";
  private static final String APK_HASH = "apk-hash";
  private static final String IAS_ARTIFACT_ID = "ias-artifact-id";
  private static final String RELEASES_PATH =
      "v1alpha/devices/-/testerApps/1:123456789:android:abcdef/installations/cccccccccccccccccccccc/releases";
  private static final String FIND_RELEASE_USING_APK_PATH =
      "v1alpha/projects/123456789/installations/cccccccccccccccccccccc/releases:find?apkHash=apk-hash";
  private static final String FIND_RELEASE_USING_IAS_PATH =
      "v1alpha/projects/123456789/installations/cccccccccccccccccccccc/releases:find?iasArtifactId=ias-artifact-id";
  private static final String CREATE_FEEDBACK_PATH =
      "v1alpha/projects/123456789/installations/cccccccccccccccccccccc/releases/release-id/feedback";
  private static final String COMMIT_FEEDBACK_PATH =
      "v1alpha/projects/123456789/installations/cccccccccccccccccccccc/releases/release-id/feedback/feedback-id:commit";

  private FirebaseAppDistributionTesterApiClient firebaseAppDistributionTesterApiClient;
  @Mock private Provider<FirebaseInstallationsApi> mockFirebaseInstallationsProvider;
  @Mock private FirebaseInstallationsApi mockFirebaseInstallations;
  @Mock private InstallationTokenResult mockInstallationTokenResult;
  @Mock private TesterApiHttpClient mockTesterApiHttpClient;

  private ExecutorService testExecutor = Executors.newSingleThreadExecutor();

  @Before
  public void setup() throws Exception {
    MockitoAnnotations.initMocks(this);
    FirebaseApp.clearInstancesForTest();

    FirebaseApp firebaseApp =
        FirebaseApp.initializeApp(
            ApplicationProvider.getApplicationContext(),
            new FirebaseOptions.Builder()
                .setApplicationId(TEST_APP_ID_1)
                .setProjectId(TEST_PROJECT_ID)
                .setGcmSenderId(TEST_PROJECT_NUMBER)
                .setApiKey(TEST_API_KEY)
                .build());

    when(mockFirebaseInstallationsProvider.get()).thenReturn(mockFirebaseInstallations);
    when(mockFirebaseInstallations.getId()).thenReturn(Tasks.forResult(TEST_FID_1));
    when(mockFirebaseInstallations.getToken(false))
        .thenReturn(Tasks.forResult(mockInstallationTokenResult));
    when(mockInstallationTokenResult.getToken()).thenReturn(TEST_AUTH_TOKEN);

    firebaseAppDistributionTesterApiClient =
        new FirebaseAppDistributionTesterApiClient(
            testExecutor, firebaseApp, mockFirebaseInstallationsProvider, mockTesterApiHttpClient);
  }

  @Test
  public void fetchNewRelease_whenResponseSuccessfulForApk_returnsRelease() throws Exception {
    JSONObject releaseJson = readTestJSON("testApkReleaseResponse.json");
    when(mockTesterApiHttpClient.makeGetRequest(any(), eq(RELEASES_PATH), eq(TEST_AUTH_TOKEN)))
        .thenReturn(releaseJson);

    Task<AppDistributionReleaseInternal> releaseTask =
        firebaseAppDistributionTesterApiClient.fetchNewRelease();
    awaitAsyncOperations(testExecutor);

    assertThat(releaseTask.isSuccessful()).isTrue();
    AppDistributionReleaseInternal expectedRelease =
        AppDistributionReleaseInternal.builder()
            .setBinaryType(BinaryType.APK)
            .setBuildVersion("3")
            .setDisplayVersion("3.0")
            .setReleaseNotes("This is a test release.")
            .setDownloadUrl("http://test-url-apk")
            .setCodeHash("code-hash-apk-1")
            .setApkHash("apk-hash-1")
            .setIasArtifactId("")
            .build();
    assertThat(releaseTask.getResult()).isEqualTo(expectedRelease);
  }

  @Test
  public void fetchNewRelease_whenResponseSuccessfulForAab_returnsRelease() throws Exception {
    JSONObject releaseJson = readTestJSON("testAabReleaseResponse.json");
    when(mockTesterApiHttpClient.makeGetRequest(any(), eq(RELEASES_PATH), eq(TEST_AUTH_TOKEN)))
        .thenReturn(releaseJson);

    Task<AppDistributionReleaseInternal> releaseTask =
        firebaseAppDistributionTesterApiClient.fetchNewRelease();
    awaitAsyncOperations(testExecutor);

    assertThat(releaseTask.isSuccessful()).isTrue();
    AppDistributionReleaseInternal expectedRelease =
        AppDistributionReleaseInternal.builder()
            .setBinaryType(BinaryType.AAB)
            .setBuildVersion("3")
            .setDisplayVersion("3.0")
            .setReleaseNotes("This is a test release.")
            .setDownloadUrl("http://test-url-aab")
            .setCodeHash("")
            .setApkHash("")
            .setIasArtifactId("ias-artifact-id-1")
            .build();
    assertThat(releaseTask.getResult()).isEqualTo(expectedRelease);
  }

  @Test
  public void fetchNewRelease_getFidError_throwsError() {
    Exception expectedException = new Exception("test ex");
    when(mockFirebaseInstallations.getId()).thenReturn(Tasks.forException(expectedException));

    Task<AppDistributionReleaseInternal> releaseTask =
        firebaseAppDistributionTesterApiClient.fetchNewRelease();

    assertTaskFailure(releaseTask, Status.UNKNOWN, "test ex", expectedException);
  }

  @Test
  public void fetchNewRelease_getFisAuthTokenError_throwsError() {
    Exception expectedException = new Exception("test ex");
    when(mockFirebaseInstallations.getToken(false))
        .thenReturn(Tasks.forException(expectedException));

    Task<AppDistributionReleaseInternal> releaseTask =
        firebaseAppDistributionTesterApiClient.fetchNewRelease();

    assertTaskFailure(releaseTask, Status.UNKNOWN, "test ex", expectedException);
  }

  @Test
  public void fetchNewRelease_whenClientThrowsException_failsTask() throws Exception {
    when(mockTesterApiHttpClient.makeGetRequest(any(), eq(RELEASES_PATH), eq(TEST_AUTH_TOKEN)))
        .thenThrow(new FirebaseAppDistributionException("test ex", Status.UNKNOWN));

    Task<AppDistributionReleaseInternal> releaseTask =
        firebaseAppDistributionTesterApiClient.fetchNewRelease();
    awaitAsyncOperations(testExecutor);

    assertTaskFailure(releaseTask, Status.UNKNOWN, "test ex");
  }

  @Test
  public void fetchNewRelease_whenNoReleases_returnsNull() throws Exception {
    JSONObject releaseJson = readTestJSON("testNoReleasesResponse.json");
    when(mockTesterApiHttpClient.makeGetRequest(any(), eq(RELEASES_PATH), eq(TEST_AUTH_TOKEN)))
        .thenReturn(releaseJson);

    Task<AppDistributionReleaseInternal> releaseTask =
        firebaseAppDistributionTesterApiClient.fetchNewRelease();
    awaitAsyncOperations(testExecutor);

    assertThat(releaseTask.getResult()).isNull();
  }

  @Test
  public void findReleaseUsingApkHash_whenResponseSuccessful_returnsReleaseName() throws Exception {
    JSONObject releaseJson = new JSONObject(String.format("{\"release\":\"%s\"}", RELEASE_NAME));
    when(mockTesterApiHttpClient.makeGetRequest(
            any(), eq(FIND_RELEASE_USING_APK_PATH), eq(TEST_AUTH_TOKEN)))
        .thenReturn(releaseJson);

    Task<String> task = firebaseAppDistributionTesterApiClient.findReleaseUsingApkHash(APK_HASH);
    awaitAsyncOperations(testExecutor);

    assertThat(task.isSuccessful()).isTrue();
    assertThat(task.getResult()).isEqualTo(RELEASE_NAME);
  }

  @Test
  public void findReleaseUsingApkHash_getFidError_throwsError() {
    Exception expectedException = new Exception("test ex");
    when(mockFirebaseInstallations.getId()).thenReturn(Tasks.forException(expectedException));

    Task<String> task = firebaseAppDistributionTesterApiClient.findReleaseUsingApkHash(APK_HASH);

    assertTaskFailure(task, Status.UNKNOWN, "test ex", expectedException);
  }

  @Test
  public void findReleaseUsingApkHash_getFisAuthTokenError_throwsError() {
    Exception expectedException = new Exception("test ex");
    when(mockFirebaseInstallations.getToken(false))
        .thenReturn(Tasks.forException(expectedException));

    Task<String> task = firebaseAppDistributionTesterApiClient.findReleaseUsingApkHash(APK_HASH);

    assertTaskFailure(task, Status.UNKNOWN, "test ex", expectedException);
  }

  @Test
  public void findReleaseUsingApkHash_whenClientThrowsException_failsTask() throws Exception {
    when(mockTesterApiHttpClient.makeGetRequest(
            any(), eq(FIND_RELEASE_USING_APK_PATH), eq(TEST_AUTH_TOKEN)))
        .thenThrow(new FirebaseAppDistributionException("test ex", Status.UNKNOWN));

    Task<String> task = firebaseAppDistributionTesterApiClient.findReleaseUsingApkHash(APK_HASH);
    awaitAsyncOperations(testExecutor);

    assertTaskFailure(task, Status.UNKNOWN, "test ex");
  }

  @Test
  public void findReleaseUsingIasArtifactId_whenResponseSuccessful_returnsReleaseName()
      throws Exception {
    JSONObject releaseJson = new JSONObject(String.format("{\"release\":\"%s\"}", RELEASE_NAME));
    when(mockTesterApiHttpClient.makeGetRequest(
            any(), eq(FIND_RELEASE_USING_IAS_PATH), eq(TEST_AUTH_TOKEN)))
        .thenReturn(releaseJson);

    Task<String> task =
        firebaseAppDistributionTesterApiClient.findReleaseUsingIasArtifactId(IAS_ARTIFACT_ID);
    awaitAsyncOperations(testExecutor);

    assertThat(task.isSuccessful()).isTrue();
    assertThat(task.getResult()).isEqualTo(RELEASE_NAME);
  }

  @Test
  public void findReleaseUsingIasArtifactId_getFidError_throwsError() {
    Exception expectedException = new Exception("test ex");
    when(mockFirebaseInstallations.getId()).thenReturn(Tasks.forException(expectedException));

    Task<String> task =
        firebaseAppDistributionTesterApiClient.findReleaseUsingIasArtifactId(IAS_ARTIFACT_ID);

    assertTaskFailure(task, Status.UNKNOWN, "test ex", expectedException);
  }

  @Test
  public void findReleaseUsingIasArtifactId_getFisAuthTokenError_throwsError() {
    Exception expectedException = new Exception("test ex");
    when(mockFirebaseInstallations.getToken(false))
        .thenReturn(Tasks.forException(expectedException));

    Task<String> task =
        firebaseAppDistributionTesterApiClient.findReleaseUsingIasArtifactId(IAS_ARTIFACT_ID);

    assertTaskFailure(task, Status.UNKNOWN, "test ex", expectedException);
  }

  @Test
  public void findReleaseUsingIasArtifactId_whenClientThrowsException_failsTask() throws Exception {
    when(mockTesterApiHttpClient.makeGetRequest(
            any(), eq(FIND_RELEASE_USING_IAS_PATH), eq(TEST_AUTH_TOKEN)))
        .thenThrow(new FirebaseAppDistributionException("test ex", Status.UNKNOWN));

    Task<String> task =
        firebaseAppDistributionTesterApiClient.findReleaseUsingIasArtifactId(IAS_ARTIFACT_ID);
    awaitAsyncOperations(testExecutor);

    assertTaskFailure(task, Status.UNKNOWN, "test ex");
  }

  @Test
  public void createFeedback_whenResponseSuccessful_returnsFeedbackName() throws Exception {
    String postBody = String.format("{\"text\":\"%s\"}", FEEDBACK_TEXT);
    when(mockTesterApiHttpClient.makePostRequest(
            any(), eq(CREATE_FEEDBACK_PATH), eq(TEST_AUTH_TOKEN), eq(postBody)))
        .thenReturn(buildFeedbackJson());

    Task<String> task =
        firebaseAppDistributionTesterApiClient.createFeedback(RELEASE_NAME, FEEDBACK_TEXT);
    awaitAsyncOperations(testExecutor);

    assertThat(task.isSuccessful()).isTrue();
    assertThat(task.getResult()).isEqualTo(FEEDBACK_NAME);
  }

  @Test
  public void createFeedback_getFidError_throwsError() {
    Exception expectedException = new Exception("test ex");
    when(mockFirebaseInstallations.getId()).thenReturn(Tasks.forException(expectedException));

    Task<String> task =
        firebaseAppDistributionTesterApiClient.createFeedback(RELEASE_NAME, FEEDBACK_TEXT);

    assertTaskFailure(task, Status.UNKNOWN, "test ex", expectedException);
  }

  @Test
  public void createFeedback_getFisAuthTokenError_throwsError() {
    Exception expectedException = new Exception("test ex");
    when(mockFirebaseInstallations.getToken(false))
        .thenReturn(Tasks.forException(expectedException));

    Task<String> task =
        firebaseAppDistributionTesterApiClient.createFeedback(RELEASE_NAME, FEEDBACK_TEXT);

    assertTaskFailure(task, Status.UNKNOWN, "test ex", expectedException);
  }

  @Test
  public void createFeedback_whenClientThrowsException_failsTask() throws Exception {
    String postBody = String.format("{\"text\":\"%s\"}", FEEDBACK_TEXT);
    when(mockTesterApiHttpClient.makePostRequest(
            any(), eq(CREATE_FEEDBACK_PATH), eq(TEST_AUTH_TOKEN), eq(postBody)))
        .thenThrow(new FirebaseAppDistributionException("test ex", Status.UNKNOWN));

    Task<String> task =
        firebaseAppDistributionTesterApiClient.createFeedback(RELEASE_NAME, FEEDBACK_TEXT);
    awaitAsyncOperations(testExecutor);

    assertTaskFailure(task, Status.UNKNOWN, "test ex");
  }

  @Test
  public void commitFeedback_whenResponseSuccessful_returnsFeedbackName() throws Exception {
    when(mockTesterApiHttpClient.makePostRequest(
            any(), eq(COMMIT_FEEDBACK_PATH), eq(TEST_AUTH_TOKEN), eq("")))
        .thenReturn(buildFeedbackJson());

    Task<Void> task = firebaseAppDistributionTesterApiClient.commitFeedback(FEEDBACK_NAME);
    awaitAsyncOperations(testExecutor);

    assertThat(task.isSuccessful()).isTrue();
  }

  @Test
  public void commitFeedback_getFidError_throwsError() {
    Exception expectedException = new Exception("test ex");
    when(mockFirebaseInstallations.getId()).thenReturn(Tasks.forException(expectedException));

    Task<Void> task = firebaseAppDistributionTesterApiClient.commitFeedback(FEEDBACK_NAME);

    assertTaskFailure(task, Status.UNKNOWN, "test ex", expectedException);
  }

  @Test
  public void commitFeedback_getFisAuthTokenError_throwsError() {
    Exception expectedException = new Exception("test ex");
    when(mockFirebaseInstallations.getToken(false))
        .thenReturn(Tasks.forException(expectedException));

    Task<Void> task = firebaseAppDistributionTesterApiClient.commitFeedback(FEEDBACK_NAME);

    assertTaskFailure(task, Status.UNKNOWN, "test ex", expectedException);
  }

  @Test
  public void commitFeedback_whenClientThrowsException_failsTask() throws Exception {
    when(mockTesterApiHttpClient.makePostRequest(
            any(), eq(COMMIT_FEEDBACK_PATH), eq(TEST_AUTH_TOKEN), eq("")))
        .thenThrow(new FirebaseAppDistributionException("test ex", Status.UNKNOWN));

    Task<Void> task = firebaseAppDistributionTesterApiClient.commitFeedback(FEEDBACK_NAME);
    awaitAsyncOperations(testExecutor);

    assertTaskFailure(task, Status.UNKNOWN, "test ex");
  }

  private JSONObject buildFeedbackJson() throws JSONException {
    return new JSONObject(
        String.format("{\"name\":\"%s\",\"text\":\"%s\"}", FEEDBACK_NAME, FEEDBACK_TEXT));
  }
}
