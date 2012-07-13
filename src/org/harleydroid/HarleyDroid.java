//
// HarleyDroid: Harley Davidson J1850 Data Analyser for Android.
//
// Copyright (C) 2010-2012 Stelian Pop <stelian@popies.net>
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//

package org.harleydroid;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

public abstract class HarleyDroid extends Activity implements ServiceConnection, Eula.OnEulaAgreedTo
{
	private static final boolean D = false;
	private static final boolean DTRACE = false;
	private static final String TAG = HarleyDroid.class.getSimpleName();
	public static final boolean EMULATOR = false;

	// Message types sent from HarleyDroidService
	public static final int STATUS_CONNECTING = 1;
	public static final int STATUS_CONNECTED = 2;
	public static final int STATUS_ERROR = 3;
	public static final int STATUS_ERRORAT = 4;
	public static final int STATUS_NODATA = 5;
	public static final int STATUS_TOOMANYERRORS = 6;
	public static final int STATUS_AUTORECON = 7;

	private static final int REQUEST_ENABLE_BT = 2;

	protected SharedPreferences mPrefs;
	private String mInterfaceType = null;
	private BluetoothAdapter mBluetoothAdapter = null;
	protected String mBluetoothID = null;
	private boolean mAutoConnect = false;
	private boolean mAutoReconnect = false;
	private String mReconnectDelay;
	private boolean mLogging = false;
	private boolean mGPS = false;
	private boolean mLogRaw = false;
	private boolean mLogRawOnly = false;
	protected HarleyDroidService mService = null;
	protected boolean mUnitMetric = false;
	protected int mOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
	protected HarleyData mHD;

	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		if (D) Log.d(TAG, "onCreate()");
		super.onCreate(savedInstanceState);

		if (DTRACE) Debug.startMethodTracing("harleydroid");

		mPrefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());

		mAutoConnect = true;

		if (Eula.show(this, false))
			onEulaAgreedTo();
	}

	public void onEulaAgreedTo() {
		if (D) Log.d(TAG, "onEulaAgreedTo()");

		if (!EMULATOR) {
			mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
			if (mBluetoothAdapter == null) {
				Toast.makeText(this, R.string.toast_nobluetooth, Toast.LENGTH_LONG).show();
				finish();
				return;
			}
			if (!mBluetoothAdapter.isEnabled()) {
				Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
				startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
			}
		}
	}

	@Override
	public void onDestroy() {
		if (D) Log.d(TAG, "onDestroy()");
		super.onDestroy();

		if (DTRACE) Debug.stopMethodTracing();
	}

	@Override
	public void onStart() {
		if (D) Log.d(TAG, "onStart()");
		super.onStart();

		// get preferences which may have been changed
		mInterfaceType = mPrefs.getString("interfacetype", null);
		mBluetoothID = mPrefs.getString("bluetoothid", null);
		mAutoConnect = mAutoConnect && mPrefs.getBoolean("autoconnect", false);
		mAutoReconnect = mPrefs.getBoolean("autoreconnect", false);
		mReconnectDelay = mPrefs.getString("reconnectdelay", "30");
		if (mPrefs.getString("orientation", "auto").equals("auto"))
			mOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
		else if (mPrefs.getString("orientation", "auto").equals("portrait")) {
			mOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
		}
		else if (mPrefs.getString("orientation", "auto").equals("landscape")) {
			mOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
		}
		else if (mPrefs.getString("orientation", "auto").equals("reversePortrait")) {
			mOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
		}
		else if (mPrefs.getString("orientation", "auto").equals("reverseLandscape")) {
			mOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
		}
		this.setRequestedOrientation(mOrientation);
		mLogging = false;
		if (mPrefs.getBoolean("logging", false)) {
			if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()))
				Toast.makeText(this, R.string.toast_errorlogging, Toast.LENGTH_LONG).show();
			else
				mLogging = true;
		}
		mGPS = mPrefs.getBoolean("gps", false);
		mLogRaw = mPrefs.getBoolean("lograw", false);
		mLogRawOnly = mPrefs.getBoolean("lograwonly", false);
		if (mPrefs.getBoolean("screenon", false))
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		if (mPrefs.getString("unit", "metric").equals("metric"))
			mUnitMetric = true;
		else
			mUnitMetric = false;

		// bind to the service
		bindService(new Intent(this, HarleyDroidService.class), this, 0);

		if (mAutoConnect && mService == null) {
			mAutoConnect = false;
			startHDS();
		}
	}

	@Override
	public void onStop() {
		if (D) Log.d(TAG, "onStop()");
		super.onStop();

		unbindService(this);
		mService = null;
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (D) Log.d(TAG, "onActivityResult");
		super.onActivityResult(requestCode, resultCode, data);

		switch (requestCode) {
		case REQUEST_ENABLE_BT:
			if (resultCode != Activity.RESULT_OK) {
				Toast.makeText(this, R.string.toast_errorenablebluetooth, Toast.LENGTH_LONG).show();
				finish();
			}
		}
	}

	public void onServiceConnected(ComponentName name, IBinder service) {
		if (D) Log.d(TAG, "onServiceConnected()");

		mService = ((HarleyDroidService.HarleyDroidServiceBinder)service).getService();
		mService.setHandler(mHandler);
		mHD = mService.getHarleyData();

		if (!EMULATOR) {
			// should not happen because capture menu is disabled, but the
			// error was somehow reproduced by users.
			if (mBluetoothID == null)
				return;
			mService.setInterfaceType(mInterfaceType,
					mBluetoothAdapter.getRemoteDevice(mBluetoothID));
		}
		else
			mService.setInterfaceType(mInterfaceType, null);

		mService.setLogging(mLogging, mUnitMetric, mGPS, mLogRaw, mLogRawOnly);
		mService.setAutoReconnect(mAutoReconnect, Integer.parseInt(mReconnectDelay));
	}

	public void onServiceDisconnected(ComponentName name) {
		if (D) Log.d(TAG, "onServiceDisconnected()");

		unbindService(this);
		mService = null;
		// ugly, but we unbind() in onStop()...
		bindService(new Intent(this, HarleyDroidService.class), this, 0);
	}

	protected final Handler mHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			if (D) Log.d(TAG, "handleMessage " + msg.what);

			switch (msg.what) {
			case STATUS_CONNECTING:
				Toast.makeText(getApplicationContext(), R.string.toast_connecting, Toast.LENGTH_LONG).show();
				break;
			case STATUS_ERROR:
				Toast.makeText(getApplicationContext(), R.string.toast_errorconnecting, Toast.LENGTH_LONG).show();
				break;
			case STATUS_ERRORAT:
				Toast.makeText(getApplicationContext(), R.string.toast_errorat, Toast.LENGTH_LONG).show();
				break;
			case STATUS_CONNECTED:
				Toast.makeText(getApplicationContext(), R.string.toast_connected, Toast.LENGTH_LONG).show();
				break;
			case STATUS_NODATA:
				Toast.makeText(getApplicationContext(), R.string.toast_nodata, Toast.LENGTH_LONG).show();
				break;
			case STATUS_TOOMANYERRORS:
				Toast.makeText(getApplicationContext(), R.string.toast_toomanyerrors, Toast.LENGTH_LONG).show();
				break;
			case STATUS_AUTORECON:
				Toast.makeText(getApplicationContext(), String.format(getText(R.string.toast_autorecon).toString(), mReconnectDelay), Toast.LENGTH_LONG).show();
				break;
			}
		}
	};

	public void startHDS() {
		if (mService == null) {
			startService(new Intent(this, HarleyDroidService.class));
			bindService(new Intent(this, HarleyDroidService.class), this, 0);
		}
	}

	public void stopHDS() {
		if (mService != null) {
			mService.disconnect();
			mService = null;
		}
	}
}
