package com.chatai.an.ai.integration.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.chatai.an.domain.model.IntegrationStatus;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Inspects real Android network capabilities and verifies live HTTP connectivity.
 */
public class InternetAccessManager {

    public static class NetworkHealth {
        public final IntegrationStatus status;
        public final String transportLabel;
        public final String detail;
        public final long checkedAt;

        public NetworkHealth(IntegrationStatus status, String transportLabel, String detail, long checkedAt) {
            this.status = status;
            this.transportLabel = transportLabel;
            this.detail = detail;
            this.checkedAt = checkedAt;
        }
    }

    private final Context context;
    private final OkHttpClient client;
    private final MutableLiveData<NetworkHealth> healthLiveData = new MutableLiveData<>();

    public InternetAccessManager(Context context, OkHttpClient client) {
        this.context = context.getApplicationContext();
        this.client = client;
        refreshStatus();
    }

    public LiveData<NetworkHealth> observeHealth() {
        return healthLiveData;
    }

    public NetworkHealth refreshStatus() {
        NetworkHealth current = inspectSystemNetwork();
        healthLiveData.postValue(current);
        return current;
    }

    public NetworkHealth inspectSystemNetwork() {
        long now = System.currentTimeMillis();
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return new NetworkHealth(IntegrationStatus.UNAVAILABLE, "None", "ConnectivityManager unavailable", now);
            }
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return new NetworkHealth(IntegrationStatus.NOT_CONNECTED, "Offline", "No active network interface", now);
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return new NetworkHealth(IntegrationStatus.NOT_CONNECTED, "No Internet", "Network lacks INTERNET capability", now);
            }
            String transport = "Connected";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transport = "Wi-Fi";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transport = "Cellular";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transport = "Ethernet";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transport = "VPN";

            boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            IntegrationStatus status = validated ? IntegrationStatus.ACTIVE : IntegrationStatus.CONNECTED;
            String detail = validated
                    ? transport + " • Internet validated"
                    : transport + " • Connected (awaiting validation)";
            return new NetworkHealth(status, transport, detail, now);
        } catch (Exception e) {
            return new NetworkHealth(IntegrationStatus.ERROR, "Error", e.getMessage(), now);
        }
    }

    public NetworkHealth probeConnectivitySync() {
        NetworkHealth base = inspectSystemNetwork();
        if (base.status == IntegrationStatus.NOT_CONNECTED || base.status == IntegrationStatus.UNAVAILABLE) {
            healthLiveData.postValue(base);
            return base;
        }
        long start = System.currentTimeMillis();
        Request req = new Request.Builder()
                .url("https://api.github.com/zen")
                .header("User-Agent", "ChatAI-Android/1.0")
                .get()
                .build();
        try (Response res = client.newCall(req).execute()) {
            long rtt = System.currentTimeMillis() - start;
            if (res.isSuccessful()) {
                NetworkHealth ok = new NetworkHealth(
                        IntegrationStatus.ACTIVE,
                        base.transportLabel,
                        base.transportLabel + " • Verified HTTP " + res.code() + " (" + rtt + " ms)",
                        System.currentTimeMillis()
                );
                healthLiveData.postValue(ok);
                return ok;
            }
            NetworkHealth err = new NetworkHealth(
                    IntegrationStatus.ERROR,
                    base.transportLabel,
                    "Probe returned HTTP " + res.code(),
                    System.currentTimeMillis()
            );
            healthLiveData.postValue(err);
            return err;
        } catch (Exception e) {
            NetworkHealth err = new NetworkHealth(
                    IntegrationStatus.ERROR,
                    base.transportLabel,
                    "Probe failed: " + e.getMessage(),
                    System.currentTimeMillis()
            );
            healthLiveData.postValue(err);
            return err;
        }
    }
}
