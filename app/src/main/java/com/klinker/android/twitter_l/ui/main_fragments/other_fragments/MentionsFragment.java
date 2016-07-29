package com.klinker.android.twitter_l.ui.main_fragments.other_fragments;
/*
 * Copyright 2014 Luke Klinker
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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import com.klinker.android.twitter_l.R;
import com.klinker.android.twitter_l.adapters.TimeLineCursorAdapter;
import com.klinker.android.twitter_l.data.sq_lite.MentionsDataSource;
import com.klinker.android.twitter_l.services.MentionsRefreshService;
import com.klinker.android.twitter_l.services.SecondMentionsRefreshService;
import com.klinker.android.twitter_l.ui.drawer_activities.DrawerActivity;
import com.klinker.android.twitter_l.ui.main_fragments.MainFragment;
import com.klinker.android.twitter_l.utils.Utils;

import java.util.Date;
import java.util.List;

import twitter4j.Paging;
import twitter4j.Twitter;
import twitter4j.TwitterException;

public class MentionsFragment extends MainFragment {

    public static final int MENTIONS_REFRESH_ID = 127;

    public int unread = 0;

    public BroadcastReceiver refreshMentions = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            getCursorAdapter(false);
        }
    };

    public Twitter getTwitter() {
        return Utils.getTwitter(context, DrawerActivity.settings);
    }

    @Override
    public void onRefreshStarted() {
        new AsyncTask<Void, Void, Cursor>() {

            private boolean update;
            private int numberNew;

            @Override
            protected void onPreExecute() {
                DrawerActivity.canSwitch = false;
            }

            @Override
            protected Cursor doInBackground(Void... params) {
                try {
                    twitter = getTwitter();

                    long[] lastId = MentionsDataSource.getInstance(context).getLastIds(currentAccount);

                    Paging paging;
                    paging = new Paging(1, 200);
                    if (lastId[0] > 0) {
                        paging.setSinceId(lastId[0]);
                    }

                    List<twitter4j.Status> statuses = twitter.getMentionsTimeline(paging);

                    if (statuses.size() != 0) {
                        update = true;
                        numberNew = statuses.size();
                    } else {
                        update = false;
                        numberNew = 0;
                    }

                    MentionsDataSource dataSource = MentionsDataSource.getInstance(context);

                    try {
                        dataSource.markAllRead(settings.currentAccount);
                    } catch (Throwable e) {

                    }

                    numberNew = dataSource.insertTweets(statuses, currentAccount);
                    unread = numberNew;

                } catch (TwitterException e) {
                    // Error in updating status
                    Log.d("Twitter Update Error", e.getMessage());
                }

                AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

                long now = new Date().getTime();
                long alarm = now + DrawerActivity.settings.mentionsRefresh;

                PendingIntent pendingIntent = PendingIntent.getService(context, MENTIONS_REFRESH_ID, new Intent(context, MentionsRefreshService.class), 0);

                if (DrawerActivity.settings.mentionsRefresh != 0)
                    am.setRepeating(AlarmManager.RTC_WAKEUP, alarm, DrawerActivity.settings.mentionsRefresh, pendingIntent);
                else
                    am.cancel(pendingIntent);

                if (DrawerActivity.settings.syncSecondMentions) {
                    syncSecondMentions();
                }

                return MentionsDataSource.getInstance(context).getCursor(currentAccount);
            }

            @Override
            protected void onPostExecute(Cursor cursor) {

                Cursor c = null;
                try {
                    c = cursorAdapter.getCursor();
                } catch (Exception e) {

                }

                stopCurrentVideos();
                cursorAdapter = setAdapter(cursor);
                attachCursor();

                try {
                    if (update) {
                        CharSequence text = numberNew == 1 ?  numberNew + " " + getResources().getString(R.string.new_mention) :  numberNew + " " + getResources().getString(R.string.new_mentions);
                        overrideSnackbarSetting = true;
                        showToastBar(text + "", jumpToTop, 400, true, toTopListener);
                        int size = mActionBarSize + (DrawerActivity.translucent ? DrawerActivity.statusBarHeight : 0);
                        try {
                            if (!settings.topDown) {
                                listView.setSelectionFromTop(numberNew + listView.getHeaderViewsCount() -
                                                //(getResources().getBoolean(R.bool.isTablet) ? 1 : 0) -
                                                (settings.jumpingWorkaround ? 1 : 0),
                                        size);
                            }
                        } catch (Exception e) {
                            // not attached
                        }
                    } else {
                        CharSequence text = getResources().getString(R.string.no_new_mentions);
                        showToastBar(text + "", allRead, 400, true, toTopListener);
                    }
                } catch (Exception e) {
                    // user closed the app before it was done
                }

                refreshLayout.setRefreshing(false);

                DrawerActivity.canSwitch = true;

                try {
                    c.close();
                } catch (Exception e) {

                }
            }
        }.execute();
    }

    public void syncSecondMentions() {
        // refresh the second account
        context.startService(new Intent(context, SecondMentionsRefreshService.class));
    }

    public TimeLineCursorAdapter setAdapter(Cursor c) {
        return new TimeLineCursorAdapter(context, c, false, MentionsFragment.this);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (sharedPrefs.getBoolean("refresh_me_mentions", false)) {
            getCursorAdapter(false);

            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    sharedPrefs.edit().putBoolean("refresh_me_mentions", false).apply();
                }
            },1000);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.klinker.android.twitter.REFRESH_MENTIONS");
        filter.addAction("com.klinker.android.twitter.NEW_MENTION");
        context.registerReceiver(refreshMentions, filter);
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onStop() {
        try {
            MentionsDataSource.getInstance(context).markAllRead(currentAccount);
        } catch (Exception e) {

        }
        super.onStop();
    }

    public void getCursorAdapter(boolean showSpinner) {
        if (showSpinner) {
            try {
                spinner.setVisibility(View.VISIBLE);
                listView.setVisibility(View.GONE);
            } catch (Exception e) { }
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                final Cursor cursor;
                try {
                    cursor = MentionsDataSource.getInstance(context).getCursor(currentAccount);
                } catch (Exception e) {
                    MentionsDataSource.dataSource = null;
                    getCursorAdapter(true);
                    return;
                }

                try {
                    Log.v("talon_databases", "mentions cursor size: " + cursor.getCount());
                } catch (Exception e) {
                    MentionsDataSource.dataSource = null;
                    getCursorAdapter(true);
                    return;
                }

                context.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {

                        if (!isAdded()) {
                            return;
                        }

                        Cursor c = null;
                        if (cursorAdapter != null) {
                            c = cursorAdapter.getCursor();
                        }

                        stopCurrentVideos();
                        if (cursorAdapter != null) {
                            TimeLineCursorAdapter cursorAdapter = new TimeLineCursorAdapter(context, cursor, false, MentionsFragment.this);
                            cursorAdapter.setQuotedTweets(MentionsFragment.this.cursorAdapter.getQuotedTweets());
                            MentionsFragment.this.cursorAdapter = cursorAdapter;
                        } else {
                            cursorAdapter = new TimeLineCursorAdapter(context, cursor, false, MentionsFragment.this);
                        }

                        try {
                            spinner.setVisibility(View.GONE);
                            listView.setVisibility(View.VISIBLE);
                        } catch (Exception e) { }

                        attachCursor();

                        if (c != null) {
                            try {
                                c.close();
                            } catch (Exception e) {

                            }
                        }
                    }
                });
            }
        }).start();
    }

    @Override
    public void onPause() {

        int mUnread = listView.getFirstVisiblePosition();

        if (unread > 0) {
            MentionsDataSource.getInstance(context).markMultipleRead(mUnread, currentAccount);

            unread = mUnread;
        }

        context.unregisterReceiver(refreshMentions);

        super.onPause();
    }


    public void attachCursor() {
        try {
            applyAdapter();
        } catch (Exception e) {

        }

        int newTweets;

        try {
            newTweets = MentionsDataSource.getInstance(context).getUnreadCount(currentAccount);
        } catch (Exception e) {
            newTweets = 0;
        }

        if (newTweets > 0) {
            unread = newTweets;
            int size = mActionBarSize + (DrawerActivity.translucent ? DrawerActivity.statusBarHeight : 0);
            try {
                if (!settings.topDown) {
                    listView.setSelectionFromTop(newTweets + listView.getHeaderViewsCount() -
                                    //(getResources().getBoolean(R.bool.isTablet) ? 1 : 0) -
                                    (settings.jumpingWorkaround ? 1 : 0),
                            size);
                }
            } catch (Exception e) {
                // not attached
            }
        }
    }

}