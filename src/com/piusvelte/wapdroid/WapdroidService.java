/*
 * Wapdroid - Android Location based Wifi Manager
 * Copyright (C) 2009 Bryan Emmanuel
 * 
 * This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.

 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *  
 *  Bryan Emmanuel piusvelte@gmail.com
 */

package com.piusvelte.wapdroid;

import java.util.List;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.CellLocation;
import android.telephony.NeighboringCellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;

public class WapdroidService extends Service {
	private static int NOTIFY_ID = 1;
	public static final String WAKE_SERVICE = "com.piusvelte.wapdroid.WAKE_SERVICE";
	private WapdroidDbAdapter mDbHelper;
	private NotificationManager mNotificationManager;
	private TelephonyManager mTeleManager;
	private String mSsid = "",
	mBssid = "",
	mOperator = "";
	private List<NeighboringCellInfo> mNeighboringCells;
	private WifiManager mWifiManager;
	private int mCid = WapdroidDbAdapter.UNKNOWN_CID,
	mLac = WapdroidDbAdapter.UNKNOWN_CID,
	mRssi = WapdroidDbAdapter.UNKNOWN_RSSI,
	mWifiState,
	mInterval,
	mBatteryLimit = 0,
	mLastBattPerc;
	private boolean mWifiIsEnabled,
	mNotify,
	mVibrate,
	mLed,
	mRingtone,
	mEnableWifi = true;
	private AlarmManager mAlarmMgr;
	private PendingIntent mPendingIntent;
	private IWapdroidUI mWapdroidUI;
	private boolean mControlWifi = true;
	private static final String TAG = "Wapdroid";
	private BroadcastReceiver mScreenReceiver, mNetworkReceiver, mWifiReceiver, mBatteryReceiver;
	private PhoneStateListener mPhoneListener;

	class ScreenReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
				Log.v(TAG,"ACTION_SCREEN_ON");
				mAlarmMgr.cancel(mPendingIntent);
				ManageWakeLocks.release();
				context.startService(new Intent(context, WapdroidService.class));
			}
			else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
				Log.v(TAG, "ACTION_SCREEN_OFF");
				mControlWifi = true;
				if (mInterval > 0) mAlarmMgr.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + mInterval, mPendingIntent);
			}
		}
	}
	
	class NetworkReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
				NetworkInfo i = (NetworkInfo) intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
				if (i.isConnected() ^ (mSsid != null)) {
					// a connection was gained or lost
					Log.v(TAG,"NETWORK_STATE_CHANGED_ACTION");
					if (!ManageWakeLocks.hasLock()) {
						Log.v(TAG,"grab a lock");
						mAlarmMgr.cancel(mPendingIntent);
						ManageWakeLocks.acquire(context);
						context.startService(new Intent(context, WapdroidService.class));
					}
					networkStateChanged();
					// only keep the wakelock if connected
					if (!i.isConnected()) release();
				}
			}
		}		
	}
	
	class WifiReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
				Log.v(TAG,"WIFI_STATE_CHANGED_ACTION");
				// if wifi is toggling, then it was probably caused by wapdroid, don't wait for another cell change
				//acquire();
				// ignore unknown
				if (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, 4) != WifiManager.WIFI_STATE_UNKNOWN) {
					mWifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, 4);
					wifiStateChanged(mWifiState == WifiManager.WIFI_STATE_ENABLED);
				}
			}
		}		
	}
	
	class BatteryReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(Intent.ACTION_BATTERY_CHANGED)) {
				Log.v(TAG,"ACTION_BATTERY_CHANGED");
				int currectBattPerc = Math.round(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
				Log.v(TAG,"battery:"+Integer.toString(currectBattPerc));
				// check if the threshold was crossed
				if ((currectBattPerc < mBatteryLimit) && (mLastBattPerc >= mBatteryLimit)) {
					setWifiState(false);
					if (mPhoneListener != null) {
						mTeleManager.listen(mPhoneListener, PhoneStateListener.LISTEN_NONE);
						mPhoneListener = null;
					}
				}
				else if ((currectBattPerc >= mBatteryLimit) && (mLastBattPerc < mBatteryLimit) && (mPhoneListener == null)) {
					mPhoneListener = new PhoneListener();
					mTeleManager.listen(mPhoneListener, (PhoneStateListener.LISTEN_CELL_LOCATION | PhoneStateListener.LISTEN_SIGNAL_STRENGTH| PhoneStateListener.LISTEN_SIGNAL_STRENGTHS));
				}
				mLastBattPerc = currectBattPerc;
				if (mWapdroidUI != null) {
					try {
						mWapdroidUI.setBattery(mLastBattPerc);
					}
					catch (RemoteException e) {}
				}
			}
		}
	}

	private final IWapdroidService.Stub mWapdroidService = new IWapdroidService.Stub() {
		public void updatePreferences(int interval, boolean notify,
				boolean vibrate, boolean led, boolean ringtone, boolean batteryOverride, int batteryPercentage)
		throws RemoteException {
			mInterval = interval;
			if (mNotify && !notify) {
				mNotificationManager.cancel(NOTIFY_ID);
				mNotificationManager = null;
			}
			else if (!mNotify && notify) {
				mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
				CharSequence contentTitle = getString(mWifiIsEnabled ? R.string.label_enabled : R.string.label_disabled);
				Notification notification = new Notification((mWifiIsEnabled ? R.drawable.scanning : R.drawable.status), contentTitle, System.currentTimeMillis());
				PendingIntent contentIntent = PendingIntent.getActivity(getBaseContext(), 0, new Intent(getBaseContext(), WapdroidService.class), 0);
				notification.setLatestEventInfo(getBaseContext(), contentTitle, getString(R.string.app_name), contentIntent);
				mNotificationManager.notify(NOTIFY_ID, notification);
			}
			mNotify = notify;
			mVibrate = vibrate;
			mLed = led;
			mRingtone = ringtone;// override && limit == 0, !override && limit > 0
			int limit = batteryOverride ? batteryPercentage : 0;
			if (limit != mBatteryLimit) batteryLimitChanged(limit);
		}
		
		public void setCallback(IBinder mWapdroidUIBinder)
		throws RemoteException {
			if (mWapdroidUIBinder != null) {
				mControlWifi = true;
				if (ManageWakeLocks.hasLock()) ManageWakeLocks.release();
				mWapdroidUI = IWapdroidUI.Stub.asInterface(mWapdroidUIBinder);
				if (mWapdroidUI != null) {
					// register battery receiver for ui, if not already registered
					if (mBatteryReceiver == null) {
						Log.v(TAG,"register battery receiver for UI");
						mBatteryReceiver = new BatteryReceiver();
						IntentFilter f = new IntentFilter();
						f.addAction(Intent.ACTION_BATTERY_CHANGED);
						registerReceiver(mBatteryReceiver, f);
					}
					// listen to phone changes if a low battery condition caused this to stop
					if (mPhoneListener == null) {
						mPhoneListener = new PhoneListener();
						mTeleManager.listen(mPhoneListener, (PhoneStateListener.LISTEN_CELL_LOCATION | PhoneStateListener.LISTEN_SIGNAL_STRENGTH| PhoneStateListener.LISTEN_SIGNAL_STRENGTHS));
					}
					try {
						mWapdroidUI.setOperator(mOperator);
						mWapdroidUI.setCellInfo(mCid, mLac);
						mWapdroidUI.setWifiInfo(mWifiState, mSsid, mBssid);
						mWapdroidUI.setSignalStrength(mRssi);
						mWapdroidUI.setCells(cellsQuery());
						mWapdroidUI.setBattery(mLastBattPerc);
						mWapdroidUI.inRange(mEnableWifi);
					}
					catch (RemoteException e) {}
				}
				else {
					// stop any receivers or listeners that were starting just for ui
					if ((mBatteryReceiver != null) && (mBatteryLimit == 0)) {
						Log.v(TAG,"unregister battery receiver for UI");
						unregisterReceiver(mBatteryReceiver);
						mBatteryReceiver = null;
					}
					if ((mLastBattPerc < mBatteryLimit) && (mPhoneListener != null)) {
						mTeleManager.listen(mPhoneListener, PhoneStateListener.LISTEN_NONE);
						mPhoneListener = null;
					}
				}
			}
		}
		public void suspendWifiControl() throws RemoteException {
			mControlWifi = false;
		}
	};
	
	class PhoneListener extends PhoneStateListener {
		public void onCellLocationChanged(CellLocation location) {
			Log.v(TAG,"onCellLocationChanged");
			getCellInfo(location);
		}
		public void onSignalStrengthChanged(int asu) {
			Log.v(TAG,"onSignalStrengthChanged");
			if (mTeleManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
				if (asu != WapdroidDbAdapter.UNKNOWN_RSSI) mRssi = 2 * asu - 113;
				signalStrengthChanged();
			}
			else release();
		}
		public void onSignalStrengthsChanged(SignalStrength signalStrength) {
			Log.v(TAG,"onSignalStrengthsChanged");
			if (mTeleManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
				if (signalStrength.getGsmSignalStrength() != WapdroidDbAdapter.UNKNOWN_RSSI) {
					mRssi = 2 * signalStrength.getGsmSignalStrength() - 113;
					signalStrengthChanged();
				}
			}
			else if (mTeleManager.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
				mRssi = signalStrength.getCdmaDbm() < signalStrength.getEvdoDbm() ?
						signalStrength.getCdmaDbm()
						: signalStrength.getEvdoDbm();
						signalStrengthChanged();
			}
			else release();
		}
	};

	@Override
	public IBinder onBind(Intent intent) {
		mAlarmMgr.cancel(mPendingIntent);
		ManageWakeLocks.release();
		return mWapdroidService;
	}

	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		init();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		super.onStart(intent, startId);
		init();
		return START_STICKY;
	}

	private void init() {
		/*
		 * started on boot, wake, screen_on, ui, settings
		 * boot and wake will wakelock and should set the alarm,
		 * others should release the lock and cancel the alarm
		 */
		// setting the wifi state is done in onCreate also, but it's need here for running in the background
		Log.v(TAG,"initializing the service");
		//the receivers should handle this
		//mWifiState = mWifiManager.getWifiState();
		//wifiStateChanged(mWifiState == WifiManager.WIFI_STATE_ENABLED);
		getCellInfo(mTeleManager.getCellLocation());
	}

	@Override
	public void onCreate() {
		super.onCreate();
		/*
		 * only register the receiver on intents that are relevant
		 * listen to network when: wifi is enabled
		 * listen to wifi when: screenon
		 * listen to battery when: disabling on battery level, UI is in foreground
		 */
		mScreenReceiver = new ScreenReceiver();
		IntentFilter f = new IntentFilter();
		f.addAction(Intent.ACTION_SCREEN_OFF);
		f.addAction(Intent.ACTION_SCREEN_ON);
		registerReceiver(mScreenReceiver, f);
		Intent i = new Intent(this, BootReceiver.class);
		i.setAction(WAKE_SERVICE);
		mPendingIntent = PendingIntent.getBroadcast(this, 0, i, 0);
		SharedPreferences prefs = (SharedPreferences) getSharedPreferences(getString(R.string.key_preferences), WapdroidService.MODE_PRIVATE);
		// initialize preferences, updated by UI
		mInterval = Integer.parseInt((String) prefs.getString(getString(R.string.key_interval), "30000"));
		mNotify = prefs.getBoolean(getString(R.string.key_notify), false);
		if (mNotify) mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		mVibrate = prefs.getBoolean(getString(R.string.key_vibrate), false);
		mLed = prefs.getBoolean(getString(R.string.key_led), false);
		mRingtone = prefs.getBoolean(getString(R.string.key_ringtone), false);
		batteryLimitChanged(prefs.getBoolean(getString(R.string.key_battery_override), false) ? Integer.parseInt((String) prefs.getString(getString(R.string.key_battery_percentage), "30")) : 0);
		prefs = null;
		mDbHelper = new WapdroidDbAdapter(this);
		mTeleManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
		mPhoneListener = new PhoneListener();
		mTeleManager.listen(mPhoneListener, (PhoneStateListener.LISTEN_CELL_LOCATION | PhoneStateListener.LISTEN_SIGNAL_STRENGTH| PhoneStateListener.LISTEN_SIGNAL_STRENGTHS));
		mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		mAlarmMgr = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
		mWifiState = mWifiManager.getWifiState();
		wifiStateChanged(mWifiState == WifiManager.WIFI_STATE_ENABLED);
		networkStateChanged();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (mScreenReceiver != null) {
			unregisterReceiver(mScreenReceiver);
			mScreenReceiver = null;
		}
		if (mWifiReceiver != null) {
			unregisterReceiver(mWifiReceiver);
			mWifiReceiver = null;
		}
		if (mNetworkReceiver != null) {
			unregisterReceiver(mNetworkReceiver);
			mNetworkReceiver = null;
		}
		if (mBatteryReceiver != null) {
			unregisterReceiver(mBatteryReceiver);
			mBatteryReceiver = null;
		}
		if (mPhoneListener != null) {
			mTeleManager.listen(mPhoneListener, PhoneStateListener.LISTEN_NONE);
			mPhoneListener = null;
		}
		if (mNotify && (mNotificationManager != null)) mNotificationManager.cancel(NOTIFY_ID);
	}

	private void batteryLimitChanged(int limit) {
		mBatteryLimit = limit;
		if (mBatteryLimit > 0) {
			if (mBatteryReceiver == null) {
				Log.v(TAG,"register battery receiver");
				mBatteryReceiver = new BatteryReceiver();
				IntentFilter f = new IntentFilter();
				f.addAction(Intent.ACTION_BATTERY_CHANGED);
				registerReceiver(mBatteryReceiver, f);
			}
		}
		else if (mBatteryReceiver != null){
			Log.v(TAG,"unregister battery receiver");
			unregisterReceiver(mBatteryReceiver);
			mBatteryReceiver = null;
		}
	}

	private void release() {
		if (ManageWakeLocks.hasLock()) {
			if (mInterval > 0) mAlarmMgr.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + mInterval, mPendingIntent);
			ManageWakeLocks.release();
		}
	}

	private String cellsQuery() {
		String cells = "(" + WapdroidDbAdapter.CELLS_CID + "=" + Integer.toString(mCid)
		+ " and (" + WapdroidDbAdapter.LOCATIONS_LAC + "=" + Integer.toString(mLac) + " or " + WapdroidDbAdapter.CELLS_LOCATION + "=" + WapdroidDbAdapter.UNKNOWN_CID + ")"
		+ " and (" + Integer.toString(mRssi) + "=" + WapdroidDbAdapter.UNKNOWN_RSSI + " or (((" + WapdroidDbAdapter.PAIRS_RSSI_MIN + "=" + WapdroidDbAdapter.UNKNOWN_RSSI + ") or (" + WapdroidDbAdapter.PAIRS_RSSI_MIN + "<=" + Integer.toString(mRssi) + ")) and ((" + WapdroidDbAdapter.PAIRS_RSSI_MAX + "=" + WapdroidDbAdapter.UNKNOWN_RSSI + ") or (" + WapdroidDbAdapter.PAIRS_RSSI_MAX + ">=" + Integer.toString(mRssi) + ")))))";
		if (!mNeighboringCells.isEmpty()) {
			for (NeighboringCellInfo n : mNeighboringCells) {
				int rssi = (n.getRssi() != WapdroidDbAdapter.UNKNOWN_RSSI) && (mTeleManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) ? 2 * n.getRssi() - 113 : n.getRssi();
				cells += " or (" + WapdroidDbAdapter.CELLS_CID + "=" + Integer.toString(n.getCid())
				+ " and (" + WapdroidDbAdapter.LOCATIONS_LAC + "=" + Integer.toString(n.getLac()) + " or " + WapdroidDbAdapter.CELLS_LOCATION + "=" + WapdroidDbAdapter.UNKNOWN_CID + ")"
				+ " and (" + Integer.toString(rssi) + "=" + WapdroidDbAdapter.UNKNOWN_RSSI + " or (((" + WapdroidDbAdapter.PAIRS_RSSI_MIN + "=" + WapdroidDbAdapter.UNKNOWN_RSSI + ") or (" + WapdroidDbAdapter.PAIRS_RSSI_MIN + "<=" + Integer.toString(rssi) + ")) and ((" + WapdroidDbAdapter.PAIRS_RSSI_MAX + "=" + WapdroidDbAdapter.UNKNOWN_RSSI + ") or (" + WapdroidDbAdapter.PAIRS_RSSI_MAX + ">=" + Integer.toString(rssi) + ")))))";
			}
		}
		return cells;
	}

	private void getCellInfo(CellLocation location) {
		mRssi = WapdroidDbAdapter.UNKNOWN_RSSI;
		mNeighboringCells = mTeleManager.getNeighboringCellInfo();
		if (mOperator == "") mOperator = mTeleManager.getNetworkOperator();
		if (mTeleManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
			mCid = ((GsmCellLocation) location).getCid() > 0 ? ((GsmCellLocation) location).getCid() : WapdroidDbAdapter.UNKNOWN_CID;
			mLac = ((GsmCellLocation) location).getLac() > 0 ? ((GsmCellLocation) location).getLac() : WapdroidDbAdapter.UNKNOWN_CID;
		}
		else if (mTeleManager.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
			// check the phone type, cdma is not available before API 2.0, so use a wrapper
			try {
				CdmaCellLocation cdma = new CdmaCellLocation(location);
				mCid = cdma.getBaseStationId() > 0 ? cdma.getBaseStationId() : WapdroidDbAdapter.UNKNOWN_CID;
				mLac = cdma.getNetworkId() > 0 ? cdma.getNetworkId() : WapdroidDbAdapter.UNKNOWN_CID;
			}
			catch (Throwable t) {
				mCid = WapdroidDbAdapter.UNKNOWN_CID;
				mLac = WapdroidDbAdapter.UNKNOWN_CID;
			}
		}
		if (mCid != WapdroidDbAdapter.UNKNOWN_CID) {
			signalStrengthChanged();
			if (mWapdroidUI != null) {
				try {
					mWapdroidUI.setOperator(mOperator);
					mWapdroidUI.setCellInfo(mCid, mLac);
					mWapdroidUI.setSignalStrength(mRssi);
					mWapdroidUI.setCells(cellsQuery());
				}
				catch (RemoteException e) {}
			}
		}
		else mTeleManager.listen(mPhoneListener, (PhoneStateListener.LISTEN_CELL_LOCATION | PhoneStateListener.LISTEN_SIGNAL_STRENGTH| PhoneStateListener.LISTEN_SIGNAL_STRENGTHS));
	}

	private void signalStrengthChanged() {
		Log.v(TAG,"signalStrengthChanged");
		if (mWapdroidUI != null) {
			try {
				mWapdroidUI.setSignalStrength(mRssi);
			}
			catch (RemoteException e) {}
		}
		// allow unknown mRssi, since signalStrengthChanged isn't reliable enough by itself
		if ((mCid != WapdroidDbAdapter.UNKNOWN_CID) && (mDbHelper != null)) {
			mDbHelper.open();
			mEnableWifi = mDbHelper.cellInRange(mCid, mLac, mRssi);
			if (mWapdroidUI != null) {
				try {
					mWapdroidUI.inRange(mEnableWifi);
				}
				catch (RemoteException e) {}
			}
			if (mWifiIsEnabled && (mSsid != null) && (mBssid != null)) updateRange();
			else if (mControlWifi && mEnableWifi) {
				for (NeighboringCellInfo n : mNeighboringCells) {
					int cid = n.getCid() > 0 ? n.getCid() : WapdroidDbAdapter.UNKNOWN_CID,
					lac = n.getLac() > 0 ? n.getLac() : WapdroidDbAdapter.UNKNOWN_CID,
					rssi = (n.getRssi() != WapdroidDbAdapter.UNKNOWN_RSSI) && (mTeleManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) ? 2 * n.getRssi() - 113 : n.getRssi();
					if (mEnableWifi && (cid != WapdroidDbAdapter.UNKNOWN_CID)) mEnableWifi = mDbHelper.cellInRange(cid, lac, rssi);
				}
			}
			if ((mEnableWifi && (mLastBattPerc >= mBatteryLimit) && !mWifiIsEnabled && (mWifiState != WifiManager.WIFI_STATE_ENABLING)) || (!mEnableWifi && mWifiIsEnabled)) {
				Log.v(TAG, "set wifi:"+mEnableWifi);
				setWifiState(mEnableWifi);
			}
			mDbHelper.close();
		}
		release();
	}

	private void updateRange() {
		int network = mDbHelper.updateNetworkRange(mSsid, mBssid, mCid, mLac, mRssi);
		for (NeighboringCellInfo n : mNeighboringCells) {
			int cid = n.getCid() > 0 ? n.getCid() : WapdroidDbAdapter.UNKNOWN_CID,
			lac = n.getLac() > 0 ? n.getLac() : WapdroidDbAdapter.UNKNOWN_CID,
			rssi = (n.getRssi() != WapdroidDbAdapter.UNKNOWN_RSSI) && (mTeleManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) ? 2 * n.getRssi() - 113 : n.getRssi();
			if (cid != WapdroidDbAdapter.UNKNOWN_CID) mDbHelper.createPair(cid, lac, network, rssi);
		}
	}

	private void setWifiState(boolean enable) {
		/*
		 *  when a low battery disabled occurs,
		 *  register the wifi receiver in case the network is connected at the time
		 */
		if (!enable && (mSsid != null)) {
			if (mWifiReceiver == null) {
				Log.v(TAG,"register wifi receiver");
				mWifiReceiver = new WifiReceiver();
				IntentFilter f = new IntentFilter();
				f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
				registerReceiver(mWifiReceiver, f);
			}			
		}
		mWifiManager.setWifiEnabled(enable);
	}
	
	private void networkStateChanged() {
		/*
		 * get network state
		 * when network connected, unregister wifi receiver
		 * when network disconnected, register wifi receiver
		 */
		mSsid = mWifiManager.getConnectionInfo().getSSID();
		mBssid = mWifiManager.getConnectionInfo().getBSSID();
		if (mSsid != null) {
			// connected
			if ((mSsid != null) && (mBssid != null) && (mCid != WapdroidDbAdapter.UNKNOWN_CID) && (mDbHelper != null)) {
				mDbHelper.open();
				updateRange();
				mDbHelper.close();
			}
			if (mWifiReceiver == null) {
				Log.v(TAG,"register wifi receiver");
				mWifiReceiver = new WifiReceiver();
				IntentFilter f = new IntentFilter();
				f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
				registerReceiver(mWifiReceiver, f);
			}
		}
		else {
			// lost connection
			if (mWifiReceiver != null) {
				Log.v(TAG,"unregister wifi receiver");
				unregisterReceiver(mWifiReceiver);
				mWifiReceiver = null;
			}
		}
		if (mWapdroidUI != null) {
			try {
				mWapdroidUI.setWifiInfo(mWifiState, mSsid, mBssid);
			}
			catch (RemoteException e) {}
		}
	}
	
	private void wifiStateChanged(boolean enabled) {
		/*
		 * get wifi state
		 * when wifi enabled, register network receiver
		 * when wifi not enabled, unregister network receiver
		 */
		if (enabled != mWifiIsEnabled) {
			Log.v(TAG,"wifi state changed");
			if (enabled) {
				if (mNetworkReceiver == null) {
					Log.v(TAG,"register network receiver");
					mNetworkReceiver = new NetworkReceiver();
					IntentFilter f = new IntentFilter();
					f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
					registerReceiver(mNetworkReceiver, f);
				}
			}
			else {
				if (mNetworkReceiver != null) {
					Log.v(TAG,"unregister network receiver");
					unregisterReceiver(mNetworkReceiver);
					mNetworkReceiver = null;
				}
			}
			// only notify when disabled or enabled
			if (mNotify && ((mWifiState == WifiManager.WIFI_STATE_DISABLED) || (mWifiState == WifiManager.WIFI_STATE_ENABLED))) {
				CharSequence contentTitle = getString(R.string.label_WIFI) + " " + getString(enabled ? R.string.label_enabled : R.string.label_disabled);
				Notification notification = new Notification((enabled ? R.drawable.statuson : R.drawable.scanning), contentTitle, System.currentTimeMillis());
				Intent i = new Intent(getBaseContext(), WapdroidService.class);
				PendingIntent contentIntent = PendingIntent.getActivity(getBaseContext(), 0, i, 0);
				notification.setLatestEventInfo(getBaseContext(), contentTitle, getString(R.string.app_name), contentIntent);
				// in low memory conditions, the wapdroid may be restarted, skip the audible notifications
				if ((mWifiIsEnabled == true) || (mWifiIsEnabled == false)) {
					if (mVibrate) notification.defaults |= Notification.DEFAULT_VIBRATE;
					if (mLed) notification.defaults |= Notification.DEFAULT_LIGHTS;
					if (mRingtone) notification.defaults |= Notification.DEFAULT_SOUND;
				}
				mNotificationManager.notify(NOTIFY_ID, notification);
			}
			mWifiIsEnabled = enabled;
		}
	}
}