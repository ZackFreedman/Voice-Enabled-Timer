/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.voidstar.glass.voiceenabledtimer;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Calendar;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.google.android.glass.timeline.LiveCard;

/**
 * Service owning the LiveCard living in the timeline.
 */
public class TimerService extends Service {

	private static final String LIVE_CARD_ID = "timer";

	/**
	 * Binder giving access to the underlying {@code Timer}.
	 */
	public class TimerBinder extends Binder {
		public Timer getTimer() {
			return mTimerDrawer.getTimer();
		}
	}

	private final TimerBinder mBinder = new TimerBinder();

	private TimerDrawer mTimerDrawer;

	private LiveCard mLiveCard;

	@Override
	public void onCreate() {
		super.onCreate();
		mTimerDrawer = new TimerDrawer(this);
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		int initialHours = 0;
		int initialMinutes = 0;
		int initialSeconds = 0;
		boolean startsRunning = true;
		boolean startsLoudly = true;

		String voiceResults = intent.getExtras().getString("VoiceResults");

		if (voiceResults != null) {
			Log.d("Timer", "Started TimerService, voice results of " + voiceResults);
			String[] tokens = voiceResults.split("\\s+");
			int pointer = 0;

			// TODO find and add additional special cases

			while (pointer < tokens.length) {
				int candidate = -1;
				boolean andAHalf = false;

				try { candidate = Integer.parseInt(tokens[pointer]); }
				catch (NumberFormatException e) { // Special parsing
					if (pointer == 0 && tokens[pointer].equals("for")) {
						Log.d("Timer", "Ignoring a leading 'for' that made it into the voice payload");
					}
					else if (tokens[pointer].equalsIgnoreCase("one") || // Hey Google speech-to-text dudes: why does "one" parse as a word?
							tokens[pointer].equalsIgnoreCase("an") ||
							tokens[pointer].equalsIgnoreCase("a")) {
						candidate = 1;
						Log.d("Timer", "Parsed non-numeric variant of 'one'.");
					}
					else if (tokens[pointer].equalsIgnoreCase("1&a")) {
						candidate = 1;
						andAHalf = true;
						Log.d("Timer", "Parsed bizarre '1&a' speech token. Please complain to Google.");
					}
					else if (tokens[pointer].equalsIgnoreCase("paused") ||
							tokens[pointer].equalsIgnoreCase("pause")) {
						startsRunning = false;
						Log.d("Timer", "Timer starts paused");
					}
					else if (tokens[pointer].equalsIgnoreCase("half") &&
							tokens[pointer + 1].equalsIgnoreCase("an") &&
							tokens[pointer + 2].equalsIgnoreCase("hour")) {
						initialMinutes = 30;
						pointer += 2;
						Log.d("Timer", "Special 'half an X' case handled");
					}
					else if (tokens[pointer].equalsIgnoreCase("quietly")) {
						startsLoudly = false;
						Log.d("Timer", "Starting timer in the background");
					}
					else {
						Log.w("Timer", "Token " + tokens[pointer] + " not parsed");
					}
				}

				pointer++;

				if (candidate > -1 && candidate <= 60) {
					if (tokens[pointer].equalsIgnoreCase("and") &&
							tokens[pointer + 1].equalsIgnoreCase("a") &&
							tokens[pointer + 2].equalsIgnoreCase("half")) {
						andAHalf = true;
						pointer += 3;
						Log.d("Timer", "Handled 'X and a half' units");
					}

					if (tokens[pointer].equalsIgnoreCase("hour") ||
							tokens[pointer].equalsIgnoreCase("hours")) {
						initialHours = candidate;
						if (andAHalf) initialMinutes = 30;
						pointer++;
						Log.d("Timer", "Parsed out " + Integer.toString(candidate) + " hours");
					}
					else if (tokens[pointer].equalsIgnoreCase("minute") ||
							tokens[pointer].equalsIgnoreCase("minutes")) {
						initialMinutes = candidate;
						if (andAHalf) initialSeconds = 30;
						pointer++;
						Log.d("Timer", "Parsed out " + Integer.toString(candidate) + " minutes");
					}
					else if (tokens[pointer].equalsIgnoreCase("second") ||
							tokens[pointer].equalsIgnoreCase("seconds")) {
						initialSeconds = candidate;
						if (andAHalf) initialSeconds += 1; // Fakes rounding up for those smartasses who try and set a timer for one-and-a-half seconds
						pointer++;
						Log.d("Timer", "Parsed out " + Integer.toString(candidate) + " secs, lol");
					}
				}
			}

			long timerDurationMillis = 
					initialHours * 60 * 60 * 1000 +
					initialMinutes * 60 * 1000+
					initialSeconds * 1000;
			
			try {
				FileOutputStream fos = openFileOutput("timertombstone", Context.MODE_PRIVATE);
				ObjectOutputStream oos = new ObjectOutputStream(fos);
				TimerTombstone tombstone = new TimerTombstone(timerDurationMillis, startsLoudly ? 2 : 0);
				oos.writeObject(tombstone);
				oos.close();
				fos.close();
			} catch (FileNotFoundException e) {
				Log.d("Timer", "This error should never occur.");
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			mTimerDrawer.getTimer().setDurationMillis(timerDurationMillis);
		}
		
		if (intent.getBooleanExtra("regenerate", false)) {
			mTimerDrawer.getTimer().setDurationMillis(
					intent.getLongExtra("endmillis", 0) - 
					Calendar.getInstance().getTimeInMillis());
			
			startsRunning = true;
			
			startsLoudly = intent.getBooleanExtra("loudly", true);
			
			if (intent.getLongExtra("endmillis", 0) > Calendar.getInstance().getTimeInMillis()) {
				startsLoudly = true; // If the timer is expired and beeping, reveal the card so the user can kill it
			}
		}

		if (startsRunning) {
			mTimerDrawer.getTimer().start();
		}

		if (mLiveCard == null) {
			mLiveCard = new LiveCard(getBaseContext(), LIVE_CARD_ID);

			mLiveCard.setDirectRenderingEnabled(true).getSurfaceHolder().addCallback(mTimerDrawer);

			Intent menuIntent = new Intent(this, MenuActivity.class);
			mLiveCard.setAction(PendingIntent.getActivity(this, 0, menuIntent, 0));

			mLiveCard.attach(this);
			mLiveCard.publish(startsLoudly ? LiveCard.PublishMode.REVEAL : LiveCard.PublishMode.SILENT);
		} else {
			mLiveCard.navigate();
		}

		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		if (mLiveCard != null && mLiveCard.isPublished()) {
			mLiveCard.getSurfaceHolder().removeCallback(mTimerDrawer);
			mLiveCard.unpublish();
			mLiveCard = null;
			mTimerDrawer.getTimer().reset();
		}
		super.onDestroy();
	}
}
