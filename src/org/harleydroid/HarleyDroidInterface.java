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

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.concurrent.TimeoutException;
//import java.util.UUID;

import android.bluetooth.BluetoothDevice;
import android.util.Log;

public class HarleyDroidInterface implements J1850Interface
{
	private static final boolean D = false;
	private static final String TAG = HarleyDroidInterface.class.getSimpleName();

	//private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	private static final int ATMA_TIMEOUT = 10000;
	private static final int MAX_ERRORS = 100;

	private HarleyDroidService mHarleyDroidService;
	private HarleyData mHD;
	private ConnectThread mConnectThread;
	private PollThread mPollThread;
	private SendThread mSendThread;
	private BluetoothDevice mDevice;
	private NonBlockingBluetoothSocket mSock = null;

	public HarleyDroidInterface(HarleyDroidService harleyDroidService, BluetoothDevice device) {
		mHarleyDroidService = harleyDroidService;
		mDevice = device;
	}

	public void connect(HarleyData hd) {
		if (D) Log.d(TAG, "connect");

		mHD = hd;
		if (mConnectThread != null)
			mConnectThread.cancel();
		mConnectThread = new ConnectThread();
		mConnectThread.start();
	}

	public void disconnect() {
		if (D) Log.d(TAG, "disconnect");

		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}
		if (mPollThread != null) {
			mPollThread.cancel();
			mPollThread = null;
		}
		if (mSendThread != null) {
			mSendThread.cancel();
			mSendThread = null;
		}
		if (mSock != null) {
			mSock.close();
			mSock = null;
		}
	}

	public void startSend(String type[], String ta[], String sa[],
			 			  String command[], String expect[],
			 			  int timeout[], int delay) {
		if (D) Log.d(TAG, "send: " + type + "-" + ta + "-" +
					 sa + "-" + command + "-" + expect);

		if (mPollThread != null) {
			mPollThread.cancel();
			mPollThread = null;
		}
		if (mSendThread != null) {
			mSendThread.cancel();
		}
		mSendThread = new SendThread(type, ta, sa, command, expect, timeout, delay);
		mSendThread.start();
	}

	public void setSendData(String type[], String ta[], String sa[],
							String command[], String expect[],
							int timeout[], int delay) {
		if (D) Log.d(TAG, "setSendData");

		if (mSendThread != null)
			mSendThread.setData(type, ta, sa, command, expect, timeout, delay);
	}

	public void startPoll() {
		if (D) Log.d(TAG, "startPoll");

		if (mSendThread != null) {
			mSendThread.cancel();
			mSendThread = null;
		}
		if (mPollThread != null) {
			mPollThread.cancel();
		}
		mPollThread = new PollThread();
		mPollThread.start();
	}

	static byte[] myGetBytes(String s, int start, int end) {
		byte[] result = new byte[end - start];
		for (int i = start; i < end; i++) {
			result[i - start] = (byte) s.charAt(i);
		}
		return result;
	}

	static byte[] myGetBytes(String s) {
		return myGetBytes(s, 0, s.length());
	}

	private class ConnectThread extends Thread {

		public void run() {

			setName("HarleyDroidInterface: ConnectThread");

			try {
				mSock = new NonBlockingBluetoothSocket();
				mSock.connect(mDevice);
			} catch (IOException e1) {
				Log.e(TAG, "connect() socket failed", e1);
				mSock.close();
				mSock = null;
				mHarleyDroidService.disconnected(HarleyDroid.STATUS_ERROR);
				return;
			}

			mHarleyDroidService.connected();
		}

		public void cancel() {
		}
	}

	private class PollThread extends Thread {
		private boolean stop = false;
		private int lastms = 0;
		public void run() {
			int errors = 0, idxJ;

			setName("HarleyDroidInterface: PollThread");
			mHarleyDroidService.startedPoll();

			while (!stop) {
				String line;

				try {
					line = mSock.readLine(ATMA_TIMEOUT);
				} catch (TimeoutException e1) {
					if (!stop)
						mHarleyDroidService.disconnected(HarleyDroid.STATUS_NODATA);
					if (mSock != null) {
						mSock.close();
						mSock = null;
					}
					return;
				}

				//mHD.setRaw(line.getBytes());
				// strip off timestamp
				idxJ = line.indexOf('J');
				if (idxJ != -1) {
					mHD.setRaw(line.getBytes());
					if (J1850.parse(myGetBytes(line, idxJ + 1, line.length()), mHD))
						errors = 0;
					else
						++errors;
				}
				else {
					idxJ = line.indexOf('$');
					byte nmea[] = line.getBytes();
					if (idxJ != -1 && nmea[idxJ+1] == 'G' && ( nmea[idxJ+2] == 'P' || nmea[idxJ+2] == 'N' || nmea[idxJ+2] == 'L' ) ) {
							/*
231$GPGGA,013119.231,4228.7613,N,08314.6181,W,0,00,0.0,188.9,M,0.0,M,,0000*73
231$GPRMC,013119.231,V,4228.7613,N,08314.6181,W,000.0,000.0,120712,,,N*67
          time       lat  MMmm s lon   MMmm s f sa dop alt   u geo   u (dgps)
831$GPGGA,013409.831,4228.6631,N,08314.5990,W,1,06,1.9,212.0,M,-34.2,M,,0000*69
---012345678901234567890123456789012456789012345678901234567890123456789012345
831$GPRMC,013409.831,A,4228.6631,N,08314.5990,W,011.5,100.0,120712,,,A*7
          time       f lat         lon          spdkt trk   date  (mag)
256$GPGSA,A,3, 10,25,02,12,17,20,04,,,,,, 2.6,1.6,2.0*34
            3d                            PdopHdopVdop
256$GPGSV,3,1,12, 04,75,033,41, 02,57,259,33, 10,52,149,42, 12,44,302,37 *7F
256$GPGSV,3,2,12, 17,38,101,32, 05,13,194,29, 20,09,040,14, 25,09,323,26 *75
256$GPGSV,3,3,12, 09,09,248,12, 23,08,067,23, 27,06,240,00, 28,06,158, *7B
*/
						try {
							int j = idxJ+1;
							int i = 0;
							while( nmea[j] != '*' ) {
								i = i ^ nmea[j];
								j++;
							}
							j++; // skip *
							i = i & 255;
							int k = Integer.valueOf(line.substring(j,j+2), 16).intValue();
							if( i != k ) {
								String kerr = Integer.toHexString(k);
								mHD.setRaw(kerr.getBytes());
								++errors;	
							}
							else
								errors = 0;
							String mS = "000";
							if( nmea[idxJ+3] == 'G' && nmea[idxJ+4] == 'S' ) {
								NumberFormat formatter = new DecimalFormat("000");
								mS = formatter.format(lastms + 1); // +1 will keep GGA and RMC together, this just after, improbably 999 as lastms
							}
							else {
								mS = line.substring(14,17);
								lastms = Integer.parseInt(mS);
							}
							mS += line;
							mHD.setRaw(mS.getBytes());
							//mHD.setRaw(line.getBytes());
						}					
						catch (ArrayIndexOutOfBoundsException e) {
							++errors;
						}
					}
					else {				
						idxJ = line.indexOf('=');
						if( idxJ == 3 ) // PPS mark
							mHD.setRaw(line.getBytes());							
						else {
							String out = "???" + line; // neither proper J or $GP message
							mHD.setRaw(out.getBytes());							
							++errors;
						}
					}
				}
				if (errors > MAX_ERRORS) {
					mSock.close();
					mSock = null;
					mHarleyDroidService.disconnected(HarleyDroid.STATUS_TOOMANYERRORS);
					return;
				}
			}
		}

		public void cancel() {
			stop = true;
		}
	}

	private class SendThread extends Thread {
		private boolean stop = false;
		private boolean newData = false;
		private String mType[], mTA[], mSA[], mCommand[], mExpect[];
		private int mTimeout[];
		private String mNewType[], mNewTA[], mNewSA[], mNewCommand[], mNewExpect[];
		private int mNewTimeout[];
		private int mDelay, mNewDelay;

		public SendThread(String type[], String ta[], String sa[], String command[], String expect[], int timeout[], int delay) {
			setName("HarleyDroidInterface: SendThread");
			mType = type;
			mTA = ta;
			mSA = sa;
			mCommand = command;
			mExpect = expect;
			mTimeout = timeout;
			mDelay = delay;
		}

		public void setData(String type[], String ta[], String sa[], String command[], String expect[], int timeout[], int delay) {
			synchronized (this) {
				mNewType = type;
				mNewTA = ta;
				mNewSA = sa;
				mNewCommand = command;
				mNewExpect = expect;
				mNewTimeout = timeout;
				mNewDelay = delay;
				newData = true;
				this.interrupt();
			}
		}

		public void run() {
			int errors = 0;
			String recv;
			int idxJ;

			mHarleyDroidService.startedSend();

			while (!stop) {

				synchronized (this) {
					if (newData) {
						mType = mNewType;
						mTA = mNewTA;
						mSA = mNewSA;
						mCommand = mNewCommand;
						mExpect = mNewExpect;
						mTimeout = mNewTimeout;
						mDelay = mNewDelay;
						newData = false;
					}
				}

				for (int i = 0; !stop && !newData && i < mCommand.length; i++) {

					byte[] data = new byte[3 + mCommand[i].length() / 2];
					data[0] = (byte)Integer.parseInt(mType[i], 16);
					data[1] = (byte)Integer.parseInt(mTA[i], 16);
					data[2] = (byte)Integer.parseInt(mSA[i], 16);
					for (int j = 0; j < mCommand[i].length() / 2; j++)
						data[j + 3] = (byte)Integer.parseInt(mCommand[i].substring(2 * j, 2 * j + 2), 16);

					String command = mCommand[i] + String.format("%02X", ((int)~J1850.crc(data)) & 0xff);

					if (D) Log.d(TAG, "send: " + mType[i] + "-" + mTA[i] + "-" +
							 mSA[i] + "-" + command + "-" + mExpect[i]);

					try {
						recv = mSock.chat(mType[i] + mTA[i] + mSA[i] + command, mExpect[i], mTimeout[i]);

						if (stop || newData)
							break;

						// split into lines and strip off timestamp
						String lines[] = recv.split("\n");
						for (int j = 0; j < lines.length; ++j) {
							idxJ = lines[j].indexOf('J');
							if (idxJ != -1)
								J1850.parse(myGetBytes(lines[j], idxJ + 1, lines[j].length()), mHD);
						}
						errors = 0;
					} catch (IOException e) {
						mHarleyDroidService.disconnected(HarleyDroid.STATUS_ERROR);
						mSock.close();
						mSock = null;
						return;
					} catch (TimeoutException e) {
						++errors;
						if (errors > MAX_ERRORS) {
							mHarleyDroidService.disconnected(HarleyDroid.STATUS_TOOMANYERRORS);
							if (mSock != null) {
								mSock.close();
								mSock = null;
							}
							return;
						}
					}

					if (!stop && !newData) {
						try {
							Thread.sleep(mTimeout[i]);
						} catch (InterruptedException e) {
						}
					}
				}

				if (!stop && !newData) {
					try {
						Thread.sleep(mDelay);
					} catch (InterruptedException e) {
					}
				}
			}
		}

		public void cancel() {
			stop = true;
		}
	}
}
