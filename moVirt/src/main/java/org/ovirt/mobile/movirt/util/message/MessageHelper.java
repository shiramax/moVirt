package org.ovirt.mobile.movirt.util.message;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.androidannotations.annotations.Background;
import org.androidannotations.annotations.Bean;
import org.androidannotations.annotations.EBean;
import org.androidannotations.annotations.RootContext;
import org.androidannotations.annotations.UiThread;
import org.ovirt.mobile.movirt.Broadcasts;
import org.ovirt.mobile.movirt.R;
import org.ovirt.mobile.movirt.auth.properties.manager.AccountPropertiesManager;
import org.ovirt.mobile.movirt.auth.properties.property.CertHandlingStrategy;
import org.ovirt.mobile.movirt.model.ConnectionInfo;
import org.ovirt.mobile.movirt.provider.ProviderFacade;
import org.ovirt.mobile.movirt.rest.dto.ErrorBody;
import org.ovirt.mobile.movirt.ui.MainActivity_;
import org.ovirt.mobile.movirt.util.NotificationHelper;
import org.ovirt.mobile.movirt.util.ObjectUtils;
import org.ovirt.mobile.movirt.util.preferences.SharedPreferencesHelper;
import org.springframework.web.client.HttpClientErrorException;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;

@EBean(scope = EBean.Scope.Singleton)
public class MessageHelper {
    private static final String TAG = MessageHelper.class.getSimpleName();

    private ObjectMapper mapper = new ObjectMapper();

    @RootContext
    Context context;

    @Bean
    ProviderFacade provider;

    @Bean
    NotificationHelper notificationHelper;

    @Bean
    AccountPropertiesManager propertiesManager;

    @Bean
    SharedPreferencesHelper sharedPreferencesHelper;

    @UiThread(propagation = UiThread.Propagation.REUSE)
    public void showToast(String msg) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
    }

    public String createMessage(HttpClientErrorException ex) {
        String result = "";
        try {
            String responseBody = ex.getResponseBodyAsString();

            if (!TextUtils.isEmpty(responseBody)) {
                result = ex.getMessage() + ": " + responseBody;

                ErrorBody errorBody = mapper.readValue(ex.getResponseBodyAsByteArray(), ErrorBody.class);
                if (errorBody.fault != null) {
                    result = ex.getMessage() + " " + errorBody.fault.reason + " " + errorBody.fault.detail;
                } else {
                    ErrorBody.Fault fault = mapper.readValue(ex.getResponseBodyAsByteArray(), ErrorBody.Fault.class);
                    if (fault != null) {
                        result = ex.getMessage() + " " + fault.reason + " " + fault.detail;
                    }
                }
            }
        } catch (Exception f) {
            result = f.getMessage();
        }

        if (TextUtils.isEmpty(result)) {
            result = ex.getMessage();
        }

        return result;
    }

    public void showError(String message) {
        showError(new Message(message));
    }

    public void showError(Throwable message) {
        showError(new Message(ObjectUtils.throwableToString(message)));
    }

    public void showError(ErrorType type, Throwable message) {
        showError(new Message(type, ObjectUtils.throwableToString(message)));
    }

    public void showError(ErrorType type, String message) {
        showError(new Message(type, message));
    }

    public void showError(ErrorType type, String message, String header) {
        showError(new Message(type, message, header));
    }

    public void showError(ErrorType type, Throwable message, String header) {
        showError(new Message(type, ObjectUtils.throwableToString(message), header));
    }

    /**
     * @see Message for default values to understand behaviour of overloaded methods
     * broadcasts error and logs it
     */
    @Background
    public void showError(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("null message");
        }
        ErrorType errorType = message.getType();
        String header = message.getHeader();
        String detail = message.getDetail();
        Integer logPriority = message.getLogPriority();

        String finalMessage;

        switch (message.getType()) {
            case REST_MINOR:
            case REST_MAJOR:
                finalMessage = context.getString(R.string.rest_request_failed, getConnectionDetails() + detail);
                break;
            default:
                finalMessage = header == null ? detail :
                        context.getString(R.string.message_detailed_info, header, detail);
                break;
        }

        if (logPriority == null) {
            logPriority = errorType.getDefaultLogPriority();
        }

        showError(errorType, logPriority, finalMessage);
    }

    @Background
    public void resetMinorErrors() {
        updateConnectionInfo(true);
    }

    private void showError(ErrorType errorType, Integer logPriority, String msg) {
        Log.println(logPriority, TAG, msg);
        boolean failedRepeatedly = false;

        if (errorType.isConnectionType()) {
            ConnectionInfo connectionInfo = updateConnectionInfo(false, errorType.isNotifiable(), msg);
            failedRepeatedly = connectionInfo.getState() == ConnectionInfo.State.FAILED_REPEATEDLY;
        }

        Intent intent = new Intent(Broadcasts.ERROR_MESSAGE);
        intent.putExtra(Broadcasts.Extras.ERROR_REASON, msg);
        intent.putExtra(Broadcasts.Extras.REPEATED_MINOR_ERROR, failedRepeatedly && errorType.isMinorType());
        context.sendBroadcast(intent);
    }

    private String getConnectionDetails() {
        String token = propertiesManager.peekAuthToken();
        if (token == null) {
            token = context.getString(R.string.rest_error_detail_token_missing);
        }

        StringBuilder certificate = new StringBuilder();
        String apiUrl = propertiesManager.getApiUrl();
        if (apiUrl != null) {
            try {
                URL url = new URL(apiUrl);
                CertHandlingStrategy certHandlingStrategy = propertiesManager.getCertHandlingStrategy();

                if (url.getProtocol().equalsIgnoreCase("https")) {
                    certificate.append("\n").append(context.getString(R.string.rest_error_detail_certificate_strategy,
                            certHandlingStrategy.toString()));
                }
                if (certHandlingStrategy == CertHandlingStrategy.TRUST_CUSTOM) {
                    boolean hasCert = propertiesManager.getCertificateChain().length > 0;
                    certificate.append("\n\t")
                            .append(context.getString(hasCert ? R.string.rest_error_detail_certificate_stored :
                                    R.string.rest_error_detail_certificate_missing));
                }
            } catch (MalformedURLException e) {
                apiUrl = context.getString(R.string.rest_error_detail_malformed_url, e.getMessage());
            }
        } else {
            apiUrl = context.getString(R.string.rest_error_detail_missing_url);
        }
        return context.getString(R.string.rest_error_details,
                apiUrl, propertiesManager.getUsername(), token, certificate.toString());
    }

    private ConnectionInfo updateConnectionInfo(boolean success) {
        return updateConnectionInfo(success, false, null);
    }

    private ConnectionInfo updateConnectionInfo(boolean success, boolean notifiable, String description) {
        ConnectionInfo connectionInfo;
        ConnectionInfo.State state;
        boolean prevFailed = false;
        boolean configured = sharedPreferencesHelper.isConnectionNotificationEnabled();
        Collection<ConnectionInfo> connectionInfos = provider.query(ConnectionInfo.class).all();
        int size = connectionInfos.size();

        if (size != 0) {
            connectionInfo = connectionInfos.iterator().next();
            ConnectionInfo.State lastState = connectionInfo.getState();
            if (lastState == ConnectionInfo.State.FAILED || lastState == ConnectionInfo.State.FAILED_REPEATEDLY) {
                prevFailed = true;
            }
        } else {
            connectionInfo = new ConnectionInfo();
        }

        if (!success) {
            state = prevFailed ? ConnectionInfo.State.FAILED_REPEATEDLY : ConnectionInfo.State.FAILED;
        } else {
            state = ConnectionInfo.State.OK;
            description = null;
        }

        connectionInfo.setDescription(description);
        connectionInfo.updateWithCurrentTime(state);

        //update in DB
        if (size != 0) {
            provider.batch().update(connectionInfo).apply();
        } else {
            provider.batch().insert(connectionInfo).apply();
        }

        //show Notification
        if (notifiable && !success && !prevFailed && configured) {
            Intent resultIntent = new Intent(context, MainActivity_.class);
            resultIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent resultPendingIntent =
                    PendingIntent.getActivity(
                            context,
                            0,
                            resultIntent,
                            0
                    );
            notificationHelper.showConnectionNotification(
                    context, resultPendingIntent, connectionInfo);
        }

        return connectionInfo;
    }
}
