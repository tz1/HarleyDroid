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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

public class HarleyDroidLogger implements HarleyDataDashboardListener, HarleyDataDiagnosticsListener, HarleyDataRawListener
{
	private static final boolean D = false;
	private static final String TAG = HarleyDroidLogger.class.getSimpleName();

	static final SimpleDateFormat TIMESTAMP_FORMAT =
		new SimpleDateFormat("yyyyMMddHHmmssSSS");
	static {
		TIMESTAMP_FORMAT.setTimeZone(TimeZone.getDefault());
	}

	private HarleyDroidGPS mGPS = null;
	private BufferedOutputStream mLog = null;
	private boolean mUnitMetric = false;
	private boolean mLogRaw = false;
	private boolean mLogRawOnly = false;
	
	public HarleyDroidLogger(Context context, boolean metric, boolean gps, boolean logRaw, boolean logRawOnly) {
		mUnitMetric = metric;
		mLogRaw = logRaw;
		mLogRawOnly = logRawOnly;
		if (gps)
			mGPS = new HarleyDroidGPS(context);
	}
	private FileOutputStream logfos;
	private File logFile;
	public void start() {
		if (D) Log.d(TAG, "start()");

		if (mGPS != null)
			mGPS.start();

		try {
			File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "/HarleyDroid");
			path.mkdirs();
			logFile = new File(path, "HD-" + TIMESTAMP_FORMAT.format(new Date()) + ".log.gz"); 
    		logfos = new FileOutputStream(logFile);
    		GZIPOutputStream zos = new GZIPOutputStream(logfos)
    		{
    		    {
    		        def.setLevel(Deflater.BEST_COMPRESSION);
    		    }
    		};
			mLog = new BufferedOutputStream(zos);
	
		} catch (IOException e) {
			Log.d(TAG, "Logfile open " + e);
		}
	}

	static byte[] myGetBytes(String s) {
		byte[] result = new byte[s.length()];
		for (int i = 0; i < s.length(); i++) {
			result[i] = (byte) s.charAt(i);
		}
		return result;
	}
	
	private int linesout = 0;
	public void write(String header, byte[] data) {
		if (D) Log.d(TAG, "write()");

		if (mLog != null) {
			try {
				if( !mLogRawOnly ) { // Log with GPS and the rest
					mLog.write(myGetBytes(TIMESTAMP_FORMAT.format(new Date())));
					mLog.write(',');
					mLog.write(myGetBytes(header));				
					if (data != null)
						mLog.write(data);
					mLog.write(',');
					if (mGPS != null)
						mLog.write(myGetBytes(mGPS.getLocation()));
					else {
						mLog.write(',');
						mLog.write(',');
					}
					mLog.write('\n');
				}
				else if (data != null) {
					mLog.write(data);
					mLog.write('\n');
				}
				if( linesout++ > 2000 ) {
					linesout = 0;					
					mLog.flush();
					logfos.flush();
					logfos.getFD().sync();
				}
			} catch (IOException e) {
			}
		}
	}

	public void write(String header, String data) {
		write(header, myGetBytes(data));
	}

	public void write(String bs) {
		write(bs, (byte[]) null);
	}

	public void stop() {
		if (D) Log.d(TAG, "stop()");

		if (mGPS != null) {
			mGPS.stop();
			mGPS = null;
		}
		if (mLog != null) {
			try {
				mLog.close();
			} catch (IOException e) {
			}
			mLog = null;
		}
	}

	public void onRPMChanged(int rpm) {
		if( mLogRawOnly )
			return;
		write("RPM," + rpm);
	}

	public void onSpeedImperialChanged(int speed) {
		if( mLogRawOnly )
			return;
		if (!mUnitMetric)
			write("SPD," + speed);
	}

	public void onSpeedMetricChanged(int speed) {
		if( mLogRawOnly )
			return;
		if (mUnitMetric)
			write("SPD," + speed);
	}

	public void onEngineTempImperialChanged(int engineTemp) {
		if( mLogRawOnly )
			return;
		if (!mUnitMetric)
			write("ETP," + engineTemp);
	}

	public void onEngineTempMetricChanged(int engineTemp) {
		if( mLogRawOnly )
			return;
		if (mUnitMetric)
			write("ETP," + engineTemp);
	}

	public void onFuelGaugeChanged(int full) {
		if( mLogRawOnly )
			return;
		write("FGE," + full);
	}

	public void onTurnSignalsChanged(int turnSignals) {
		if( mLogRawOnly )
			return;
		if ((turnSignals & 0x03) == 0x03)
			write("TRN,W");
		else if ((turnSignals & 0x01) == 0x01)
			write("TRN,R");
		else if ((turnSignals & 0x02) == 0x02)
			write("TRN,L");
		else
			write("TRN,");
	}

	public void onNeutralChanged(boolean neutral) {
		if( mLogRawOnly )
			return;
		write("NTR," + (neutral ? "1" : "0"));
	}

	public void onClutchChanged(boolean clutch) {
		if( mLogRawOnly )
			return;
		write("CLU," + (clutch ? "1" : "0"));
	}

	public void onGearChanged(int gear) {
		if( mLogRawOnly )
			return;
		write("GER," + gear);
	}

	public void onCheckEngineChanged(boolean checkEngine) {
		write("CHK," + (checkEngine ? "1" : "0"));
	}

	public void onOdometerImperialChanged(int odometer) {
		if( mLogRawOnly )
			return;
		if (!mUnitMetric)
			write("ODO," + odometer);
	}

	public void onOdometerMetricChanged(int odometer) {
		if( mLogRawOnly )
			return;
		if (mUnitMetric)
			write("ODO," + odometer);
	}

	public void onFuelImperialChanged(int fuel) {
		if( mLogRawOnly )
			return;
		if (!mUnitMetric)
			write("FUL," + fuel);
	}

	public void onFuelMetricChanged(int fuel) {
		if( mLogRawOnly )
			return;
		if (mUnitMetric)
			write("FUL," + fuel);
	}

	public void onVINChanged(String vin) {
		if( mLogRawOnly )
			return;
		write("VIN," + vin);
	}

	public void onECMPNChanged(String ecmPN) {
		if( mLogRawOnly )
			return;
		write("EPN," + ecmPN);
	}

	public void onECMCalIDChanged(String ecmCalID) {
		if( mLogRawOnly )
			return;
		write("ECI," + ecmCalID);
	}

	public void onECMSWLevelChanged(int ecmSWLevel) {
		if( mLogRawOnly )
			return;
		write("ESL," + ecmSWLevel);
	}

	// the following return the data buffer

	public void onHistoricDTCChanged(String[] dtc) {
		if( mLogRawOnly )
			return;
		String data = "";
		if (dtc.length > 0)
			data = dtc[0];
		for (int i = 1; i < dtc.length; i++)
			data += "," + dtc[i];
		write("DTH,", data);
	}

	public void onCurrentDTCChanged(String[] dtc) {
		if( mLogRawOnly )
			return;
		String data = "";
		if (dtc.length > 0)
			data = dtc[0];
		for (int i = 1; i < dtc.length; i++)
			data += "," + dtc[i];
		write("DTC,", data);
	}

	public void onBadCRCChanged(byte[] buffer) {
		if( mLogRawOnly )
			return;
		write("CRC,", buffer);
	}

	public void onUnknownChanged(byte[] buffer) {
		if( mLogRawOnly )
			return;
		write("UNK,", buffer);
	}

	public void onRawChanged(byte[] buffer) {
		if (mLogRawOnly || mLogRaw)
			write("RAW,", buffer);
	}
}
