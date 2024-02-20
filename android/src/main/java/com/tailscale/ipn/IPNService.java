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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class IPNService extends VpnService {

	public static final AtomicBoolean onlyUseWifi = new AtomicBoolean(true);
	public static final String ACTION_CONNECT = "com.tailscale.ipn.CONNECT";
	public static final String ACTION_DISCONNECT = "com.tailscale.ipn.DISCONNECT";

	@Override public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
			((App)getApplicationContext()).autoConnect = false;
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

	public boolean changeVpnMode() {
		boolean setV = !onlyUseWifi.get();
		onlyUseWifi.set(setV);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, App.NOTIFY_CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_notification)
				.setContentTitle("网络模式")
				.setContentText(setV ? "只用WIFI" : "不只用WIFI")
				.setContentIntent(configIntent())
				.setAutoCancel(true)
				.setOnlyAlertOnce(false)
				.setPriority(NotificationCompat.PRIORITY_DEFAULT);
		NotificationManagerCompat.from(this)
				.notify(App.NOTIFY_NOTIFICATION_ID, builder.build());
		return setV;
	}

	private Network[] getWifiNetworkOrElse() {
		ConnectivityManager connectivityManager = ((App)getApplicationContext()).connectivityManager;
		Network[] networks = connectivityManager.getAllNetworks();
		List<Network> wifis = new ArrayList<>();
		List<Network> others = new ArrayList<>();
		for (Network network : networks) {
			NetworkInfo networkInfo = connectivityManager.getNetworkInfo(network);
			if (networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
				wifis.add(network);
			} else {
				others.add(network);
			}
		}
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, App.NOTIFY_CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_notification)
				.setContentTitle("使用模式")
				.setContentIntent(configIntent())
				.setAutoCancel(true)
				.setOnlyAlertOnce(false)
				.setPriority(NotificationCompat.PRIORITY_DEFAULT);
		String time = SimpleDateFormat.getDateInstance().format(new Date());
		if (onlyUseWifi.get()) {
			NotificationManagerCompat.from(this)
					.notify(App.NOTIFY_NOTIFICATION_ID, builder.setContentText(time + "  WIFI:"+wifis.size()).build());
		} else {
			NotificationManagerCompat.from(this)
					.notify(App.NOTIFY_NOTIFICATION_ID, builder.setContentText(time + "  WIFI:"+wifis.size() + "  Others:" + others.size()).build());
			wifis.addAll(others);
		}
		return wifis.toArray(new Network[0]);
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
			.setContentTitle(title)
			.setContentText(message)
			.setContentIntent(configIntent())
			.setPriority(NotificationCompat.PRIORITY_LOW);

		startForeground(App.STATUS_NOTIFICATION_ID, builder.build());
	}

	private native void connect();
	private native void disconnect();

	public native void directConnect();
}
