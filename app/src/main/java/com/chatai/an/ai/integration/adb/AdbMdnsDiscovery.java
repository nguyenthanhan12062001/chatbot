package com.chatai.an.ai.integration.adb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import androidx.annotation.NonNull;

import com.chatai.an.core.AppLogger;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Discovers Android 11+ Wireless Debugging mDNS services:
 * - _adb-tls-pairing._tcp (Pairing code port)
 * - _adb-tls-connect._tcp (ADB TLS connect port)
 */
public final class AdbMdnsDiscovery {

    private static final String TAG = "AdbMdnsDiscovery";
    public static final String SERVICE_PAIRING = "_adb-tls-pairing._tcp.";
    public static final String SERVICE_CONNECT = "_adb-tls-connect._tcp.";

    public static final class ServiceEndpoint {
        public final String serviceName;
        public final String host;
        public final int port;

        public ServiceEndpoint(String serviceName, String host, int port) {
            this.serviceName = serviceName != null ? serviceName : "";
            this.host = host != null ? host : "";
            this.port = port;
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    public interface DiscoveryCallback {
        void onServiceFound(ServiceEndpoint endpoint);
        void onServiceLost(String serviceName);
        void onDiscoveryFailed(String error);
    }

    private final Context context;
    private final NsdManager nsdManager;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final java.util.Queue<NsdServiceInfo> pendingResolves = new java.util.ArrayDeque<>();
    private boolean resolvingLegacy = false;
    private NsdManager.DiscoveryListener activeListener;
    private WifiManager.MulticastLock multicastLock;

    public AdbMdnsDiscovery(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.nsdManager = (NsdManager) this.context.getSystemService(Context.NSD_SERVICE);
    }

    public synchronized void startDiscovery(@NonNull String serviceType, @NonNull DiscoveryCallback callback) {
        stopDiscovery();
        if (nsdManager == null) {
            callback.onDiscoveryFailed("NsdManager unavailable on this device.");
            return;
        }
        acquireMulticastLock();
        running.set(true);

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                AppLogger.d(TAG, "mDNS discovery started for " + regType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!running.get()) return;
                AppLogger.d(TAG, "mDNS service found: " + serviceInfo.getServiceName());
                enqueueResolve(serviceInfo, callback);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                callback.onServiceLost(serviceInfo.getServiceName());
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                AppLogger.d(TAG, "mDNS discovery stopped for " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                running.set(false);
                callback.onDiscoveryFailed("Start mDNS failed (code " + errorCode + ")");
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                running.set(false);
            }
        };

        this.activeListener = listener;
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Throwable t) {
            running.set(false);
            activeListener = null;
            releaseMulticastLock();
            callback.onDiscoveryFailed(t.getMessage() != null ? t.getMessage() : "mDNS start error");
        }
    }

    private void enqueueResolve(NsdServiceInfo serviceInfo, DiscoveryCallback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveService(serviceInfo, callback);
            return;
        }
        synchronized (pendingResolves) {
            pendingResolves.offer(serviceInfo);
            if (!resolvingLegacy) {
                resolvingLegacy = true;
                NsdServiceInfo next = pendingResolves.poll();
                if (next != null) {
                    resolveService(next, callback);
                } else {
                    resolvingLegacy = false;
                }
            }
        }
    }

    private void pumpNextResolve(DiscoveryCallback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return;
        NsdServiceInfo next;
        synchronized (pendingResolves) {
            if (!running.get()) {
                pendingResolves.clear();
                resolvingLegacy = false;
                return;
            }
            next = pendingResolves.poll();
            if (next == null) {
                resolvingLegacy = false;
                return;
            }
        }
        resolveService(next, callback);
    }

    @SuppressWarnings("deprecation")
    private void resolveService(NsdServiceInfo serviceInfo, DiscoveryCallback callback) {
        if (nsdManager == null || !running.get()) {
            pumpNextResolve(callback);
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nsdManager.resolveService(serviceInfo, Runnable::run, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        AppLogger.w(TAG, "mDNS resolve failed: " + errorCode);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        handleResolved(info, callback);
                    }
                });
            } else {
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        AppLogger.w(TAG, "mDNS resolve failed: " + errorCode);
                        pumpNextResolve(callback);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        try {
                            handleResolved(info, callback);
                        } finally {
                            pumpNextResolve(callback);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "mDNS resolve exception: " + t.getMessage());
            pumpNextResolve(callback);
        }
    }

    private void handleResolved(NsdServiceInfo info, DiscoveryCallback callback) {
        int port = info.getPort();
        String host = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            for (InetAddress addr : info.getHostAddresses()) {
                if (addr instanceof Inet4Address && !addr.isLinkLocalAddress()) {
                    host = addr.getHostAddress();
                    break;
                }
            }
            if (host == null) {
                for (InetAddress addr : info.getHostAddresses()) {
                    if (!addr.isLinkLocalAddress()) {
                        host = addr.getHostAddress();
                        break;
                    }
                }
            }
            if (host == null && !info.getHostAddresses().isEmpty()) {
                host = info.getHostAddresses().get(0).getHostAddress();
            }
        }
        if (host == null && info.getHost() != null) {
            host = info.getHost().getHostAddress();
        }
        if (host != null && !host.isEmpty() && port > 0) {
            callback.onServiceFound(new ServiceEndpoint(info.getServiceName(), host, port));
        }
    }

    public synchronized void stopDiscovery() {
        running.set(false);
        if (nsdManager != null && activeListener != null) {
            try {
                nsdManager.stopServiceDiscovery(activeListener);
            } catch (Throwable ignored) {
            }
            activeListener = null;
        }
        releaseMulticastLock();
    }

    /**
     * Blocking one-shot discovery helper for Wireless Debugging connect/pairing service.
     */
    public ServiceEndpoint discoverOnceSync(@NonNull String serviceType, long timeoutMs) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ServiceEndpoint> ref = new AtomicReference<>();
        startDiscovery(serviceType, new DiscoveryCallback() {
            @Override
            public void onServiceFound(ServiceEndpoint endpoint) {
                if (ref.compareAndSet(null, endpoint)) {
                    latch.countDown();
                }
            }

            @Override
            public void onServiceLost(String serviceName) {}

            @Override
            public void onDiscoveryFailed(String error) {
                latch.countDown();
            }
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            stopDiscovery();
        }
        return ref.get();
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("chatai_adb_mdns");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Throwable ignored) {
        }
        multicastLock = null;
    }
}
