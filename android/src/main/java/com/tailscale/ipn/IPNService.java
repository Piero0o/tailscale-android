// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.ConnectivityManager;
import android.os.Build;
import android.system.OsConstants;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.*;

public class IPNService extends VpnService {
	public static final String ACTION_CONNECT = "com.tailscale.ipn.CONNECT";
	public static final String ACTION_DISCONNECT = "com.tailscale.ipn.DISCONNECT";

	@Override public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
			close();
			return START_NOT_STICKY;
		}
		connect();
		App app = ((App)getApplicationContext());
		if (app.vpnReady && app.autoConnect) {
			directConnect();
		}
		return START_STICKY;
	}

	private void close() {
		stopForeground(true);
		disconnect();
	}

	private ConnectivityManager getConnectivityManager() {
		return (ConnectivityManager) getApplicationContext()
				.getSystemService(Context.CONNECTIVITY_SERVICE);
	}

	private String[] titleMsg = new String[2];

	private Network[] getWifiNetworkOrElse() {
		ConnectivityManager connectivityManager = getConnectivityManager();
		Network[] networks = connectivityManager.getAllNetworks();
		List<Network> wifis = new LinkedList<>();
		List<String> wifiInfos = new LinkedList<>();
		List<String> otherInfos = new LinkedList<>();
		for (Network network : networks) {
			NetworkInfo networkInfo = connectivityManager.getNetworkInfo(network);
			if (networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
				if (networkInfo)
				wifis.add(network);
				wifiInfos.add(networkInfo.toString());
			} else {
				otherInfos.add(networkInfo.toString());
			}
		}
		if (!wifis.isEmpty()) {
			titleMsg[0] = "WIFI网络";
			titleMsg[1] = printNet(wifiInfos);
			return wifis.toArray(new Network[0]);
		} else if (networks.length > 0) {
			titleMsg[0] = "流量网络";
			titleMsg[1] = printNet(otherInfos);
			return networks;
		} else {
			titleMsg[0] = "所有网络";
			titleMsg[1]	= "无网络";
			return null;
		}
	}

	private String printNet(List<String> networks) {
		List<String> list = new ArrayList<>();
		for (int i = 0; i < networks.size(); i++) {
			list.add((i + 1) + "." + networks.get(i));
		}
		return String.join("\n", list);
	}

	@Override public void onDestroy() {
		close();
		super.onDestroy();
	}

	@Override public void onRevoke() {
		close();
		super.onRevoke();
	}

	private PendingIntent configIntent() {
		return PendingIntent.getActivity(this, 0, new Intent(this, IPNActivity.class),
			PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
	}

	private void disallowApp(VpnService.Builder b, String name) {
		try {
			b.addDisallowedApplication(name);
		} catch (PackageManager.NameNotFoundException e) {
			return;
		}
	}

	protected VpnService.Builder newBuilder() {
		VpnService.Builder b = new VpnService.Builder()
			.setConfigureIntent(configIntent())
			.allowFamily(OsConstants.AF_INET)
			.allowFamily(OsConstants.AF_INET6);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
			b.setMetered(false); // Inherit the metered status from the underlying networks.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
			b.setUnderlyingNetworks(getWifiNetworkOrElse()); // Use all available networks.

		// RCS/Jibe https://github.com/tailscale/tailscale/issues/2322
		this.disallowApp(b, "com.google.android.apps.messaging");

		// Stadia https://github.com/tailscale/tailscale/issues/3460
		this.disallowApp(b, "com.google.stadia.android");

		// Android Auto https://github.com/tailscale/tailscale/issues/3828
		this.disallowApp(b, "com.google.android.projection.gearhead");

		// GoPro https://github.com/tailscale/tailscale/issues/2554
		this.disallowApp(b, "com.gopro.smarty");

		// Sonos https://github.com/tailscale/tailscale/issues/2548
		this.disallowApp(b, "com.sonos.acr");
		this.disallowApp(b, "com.sonos.acr2");

		// Google Chromecast https://github.com/tailscale/tailscale/issues/3636
		this.disallowApp(b, "com.google.android.apps.chromecast.app");

		return b;
	}

	public void notify(String title, String message) {
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, App.NOTIFY_CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_notification)
			.setContentTitle(title)
			.setContentText(message)
			.setContentIntent(configIntent())
			.setAutoCancel(true)
			.setOnlyAlertOnce(true)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT);

		NotificationManagerCompat nm = NotificationManagerCompat.from(this);
		nm.notify(App.NOTIFY_NOTIFICATION_ID, builder.build());
	}

	public void updateStatusNotification(String title, String message) {
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, App.STATUS_CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_notification)
			.setContentTitle(title + ("Connected".equals(title) ? "  " + titleMsg[0] : ""))
			.setContentText(message == null || message.isEmpty() ? titleMsg[1] : message)
			.setContentIntent(configIntent())
			.setPriority(NotificationCompat.PRIORITY_LOW);

		startForeground(App.STATUS_NOTIFICATION_ID, builder.build());
	}

	private native void connect();
	private native void disconnect();

	public native void directConnect();
}
