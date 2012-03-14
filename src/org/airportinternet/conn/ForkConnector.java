package org.airportinternet.conn;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.airportinternet.Setting;

import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

public class ForkConnector extends Connector {
	private static final String IODINE_PATH =
			"/data/data/org.airportinternet/iodine";
	private static final String ROUTING_SCRIPT_PATH =
			"/data/data/org.airportinternet/routing.sh";

	private Handler mHandler = new Handler();

	private Setting s;
	private Process proc;
	private BufferedReader in;

	private List<String> cmdc;

	private Thread watchdog;
	/*
	 * We want to assign buffered readers and stuff only after watchdog 
	 * starts the process and starts monitoring it
	 */
	private Lock watchdogLock = new ReentrantLock();
	private Condition watchdogCond = watchdogLock.newCondition();
	
	/*
	 * Refresh UI every 100ms before "connected", every 1000ms when connected 
	 */
	private int refreshEvery = 100;
	
	/*
	 * Parameter to pass to routing script
	 * "<ip>" if direct communication to dns server was established
	 * "indirect" if not
	 */
	private String scriptParam = "indirect";
	
	@Override
	public void stop() {
		proc.destroy();
		try {
			watchdog.join();
		} catch (InterruptedException e) {
			Log.e("ForkConnector:stop",
					"Interrupted while waiting for watchdog");
			e.printStackTrace();
		}
	}
	
	@Override
	protected void start(Setting setting) {
		s = setting;

		cmdc = s.cmdarray();
		cmdc.add(0, IODINE_PATH);
		Log.d("CommandLine", cmdc.toString() + "; size: " + cmdc.size());
		
		watchdog = new Thread() {
			public void run() {
				Log.d("watchdog", "watchdog started");
				watchdogLock.lock();
				try {
					proc = new ProcessBuilder(cmdc).redirectErrorStream(
							true).start();
					in = new BufferedReader(new InputStreamReader(
							proc.getInputStream()));
				} catch (IOException e) {
					Toast.makeText(getApplicationContext(),
							"Failed to start iodine", Toast.LENGTH_SHORT);
					e.printStackTrace();
					sendLog("Failed to start iodine");
				}
				watchdogCond.signal();
				watchdogLock.unlock();

				try {
					proc.waitFor();
					running = false;
				} catch (InterruptedException e) {
					e.printStackTrace(); // shouldn't ever happen
				}
				
				Log.d("watchdog", "watchdog stopped");
			}
		};
		watchdog.start();
		connecting();
		
		watchdogLock.lock();
		try {
			watchdogCond.await();
		} catch (InterruptedException e) {
			// should never happen
			e.printStackTrace();
		} finally {
			watchdogLock.unlock();
		}
		mHandler.post(poller);
		running = true;
	}

	private Runnable poller = new Runnable() {
		public void run() {
			char buf[] = new char[1024];
			StringBuilder ret = new StringBuilder();
			int cnt = 0;
			
			try {
				while(in.ready())
					if((cnt = in.read(buf)) != -1) {
						ret.append(buf, 0, cnt);
					}
			} catch (IOException e) {
				ret.append("Read interrupted");
				e.printStackTrace();
			}
			if (ret.length() > 0) {
				sendLog(ret.toString());
			}
			if (!isConnected()) {
				int i;
				if (fullLog.lastIndexOf("setup complete, ") != -1)
					connected();
				
				if ((i = fullLog.lastIndexOf("raw traffic directly to "))!=-1) {
					i += "raw traffic directly to ".length();
					int ip_end;
					if ((ip_end = fullLog.substring(i).indexOf('\n')) != -1)
						scriptParam = fullLog.substring(i, i+ip_end);
					else
						scriptParam = fullLog.substring(i);
					// Minimally validate scriptParam. It should contain
					// only dots, numbers and letters
					if (!scriptParam.matches("[.\\w]+")) {
						sendLog("ERROR: TAMPERING. This is " +
								"supposed to be an ip address: " + scriptParam);
						scriptParam = "indirect"; // be incorrect, but safe 
					}
					Log.d("poller", "Direct communication with " + scriptParam);
					
				}
				
				if (isConnected()) {
					Process routing = null;
					// Ye, this is kinda dangerous
					// (man in the middle could exploit this ... brrr)
					String r = "su -c sh " + ROUTING_SCRIPT_PATH + 
							" " + scriptParam;
					try {
						//routing = Runtime.getRuntime().exec("su", params);
						routing = Runtime.getRuntime().exec(r);
					} catch (IOException e) {
						sendLog("su invocation failed\n");
						e.printStackTrace();
					}
					if (routing != null)
						try {
							routing.waitFor();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					if (routing == null || routing.exitValue() != 0) {
						sendLog("Routing configuration failed\n");
					}
					refreshEvery = 1000;
				}
			}
    		if (running) mHandler.postDelayed(this, refreshEvery);
    		else disconnected();
		}
	};
}
