/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.mms.ui;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.app.SearchManager;
import android.app.SearchableInfo;
import android.app.AlertDialog.Builder;
import android.content.ActivityNotFoundException;
import android.content.AsyncQueryHandler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SqliteWrapper;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.Telephony;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Threads;
import android.util.Log;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnCreateContextMenuListener;
import android.view.View.OnKeyListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.QuickContactBadge;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.mms.LogTag;
import com.android.mms.MmsConfig;
import com.android.mms.R;
import com.android.mms.rcs.RcsApiManager;
import com.android.mms.data.Contact;
import com.android.mms.data.ContactList;
import com.android.mms.data.Conversation;
import com.android.mms.data.Conversation.ConversationQueryHandler;
import com.android.mms.data.RecipientIdCache;
import com.android.mms.rcs.RcsUtils;
import com.android.mms.rcs.GroupChatManagerReceiver;
import com.android.mms.rcs.GroupChatManagerReceiver.GroupChatNotifyCallback;
import com.suntek.mway.rcs.client.aidl.constant.BroadcastConstants;
import com.android.mms.transaction.MessagingNotification;
import com.android.mms.transaction.SmsReceiverService;
import com.android.mms.transaction.SmsRejectedReceiver;
import com.android.mms.ui.PopupList;
import com.android.mms.ui.SelectionMenu;
import com.android.mms.util.DraftCache;
import com.android.mms.util.Recycler;
import com.android.mms.widget.MmsWidgetProvider;
import com.google.android.mms.pdu.PduHeaders;
import com.suntek.mway.rcs.client.api.util.ServiceDisconnectedException;
import com.suntek.mway.rcs.client.aidl.provider.model.ChatMessage;
import com.suntek.mway.rcs.client.aidl.provider.model.GroupChatModel;
import com.suntek.mway.rcs.client.api.im.impl.MessageApi;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * This activity provides a list view of existing conversations.
 */
public class ConversationList extends ListActivity implements DraftCache.OnDraftChangedListener {
    private static final String TAG = "ConversationList";
    private static final boolean DEBUG = false;
    private static final boolean DEBUGCLEANUP = true;

    private static final int THREAD_LIST_QUERY_TOKEN       = 1701;
    private static final int UNREAD_THREADS_QUERY_TOKEN    = 1702;
    public static final int DELETE_CONVERSATION_TOKEN      = 1801;
    public static final int HAVE_LOCKED_MESSAGES_TOKEN     = 1802;
    private static final int DELETE_OBSOLETE_THREADS_TOKEN = 1803;

    // IDs of the context menu items for the list of conversations.
    public static final int MENU_DELETE               = 0;
    public static final int MENU_VIEW                 = 1;
    public static final int MENU_VIEW_CONTACT         = 2;
    public static final int MENU_ADD_TO_CONTACTS      = 3;

    // Restore and backup All Message status
    public static final int RESTORE_ALL_MESSAGE_FAIL = -1;
    public static final int RESTORE_ALL_MESSAGE_START = 0;
    public static final int RESTORE_ALL_MESSAGE_SAVING = 1;
    public static final int RESTORE_ALL_MESSAGE_SUCCESS = 2;
    public static final int BACKUP_ALL_MESSAGE_FAIL = -1;
    public static final int BACKUP_ALL_MESSAGE_START = 0;
    public static final int BACKUP_ALL_MESSAGE_SAVING = 1;
    public static final int BACKUP_ALL_MESSAGE_SUCCESS = 2;

    // Backup and Restore messages
    private static final String BACKUP_ALL_MESSAGES  = "com.suntek.mway.rcs.BACKUP_ALL_MESSAGE";
    private static final String RESTORE_ALL_MESSAGES = "com.suntek.mway.rcs.RESTORE_ALL_MESSAGE";

    // Backup and Restore messages Dialog item
    public static final int ITME_BACKUP_ALL_MESSAGES = 0;
    public static final int ITME_RESTORE_ALL_MESSAGES  = 1;

    // Keys for extras.
    private static final String THREAD_ID = "thread_id";
    private static final String MESSAGE_ID = "_id";
    private static final String MESSAGE_TYPE = "type";

    private static final String[] MMS_MESSAGE_TYPE_PROJECTION =
            new String[] { Mms.MESSAGE_TYPE };

    private ThreadListQueryHandler mQueryHandler;
    private ConversationListAdapter mListAdapter;
    private SharedPreferences mPrefs;
    private Handler mHandler;
    private boolean mDoOnceAfterFirstQuery;
    private TextView mUnreadConvCount;
    private MenuItem mSearchItem;
    private SearchView mSearchView;
    private View mSmsPromoBannerView;
    private int mSavedFirstVisiblePosition = AdapterView.INVALID_POSITION;
    private int mSavedFirstItemOffset;
    private ProgressDialog mProgressDialog;
    private ProgressDialog mSaveOrBackProgressDialog = null;
    private View mPublicAccountItemView;
    private View mNotificationListItemView;

    // keys for extras and icicles
    private final static String LAST_LIST_POS = "last_list_pos";
    private final static String LAST_LIST_OFFSET = "last_list_offset";

    static private final String CHECKED_MESSAGE_LIMITS = "checked_message_limits";
    private final static int DELAY_TIME = 500;

    private static boolean mIsDeleteLockChecked = false;
    private static long mLastDeletedThread = -1;

    // Whether or not we are currently enabled for SMS. This field is updated in onResume to make
    // sure we notice if the user has changed the default SMS app.
    private boolean mIsSmsEnabled;
    private boolean mIsRcsEnabled;
    private Toast mComposeDisabledToast;
    private boolean mConvsertionSelect = false;
    private String[] mNumbers;
    private GroupChatModel mGroupChatModel;
    private final static String MULTI_SELECT_CONV = "select_conversation";
    private GroupChatManagerReceiver mGroupReceiver = new GroupChatManagerReceiver(
            new GroupChatNotifyCallback() {

                @Override
                public void onNewSubject(String groupId, String newSubject) {
                    if (mListAdapter != null) {
                        mListAdapter.notifyDataSetChanged();
                    }
                }

                @Override
                public void onMemberAliasChange(String groupId) {
                }

                @Override
                public void onDisband(String groupId) {
                }
            });

    private BroadcastReceiver backupAllMessageReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra("status", -1);
            int progress = intent.getIntExtra("progress", 0);
            int total = intent.getIntExtra("total", 0);
            switch (status) {
                case BACKUP_ALL_MESSAGE_START:
                    showProgressDialog(ConversationList.this, 0,
                            context.getString(R.string.message_is_begin), total);
                    if (mSaveOrBackProgressDialog != null
                            && !mSaveOrBackProgressDialog.isShowing()) {
                        mSaveOrBackProgressDialog.show();
                    }
                    break;
                case BACKUP_ALL_MESSAGE_SAVING:
                    if (total == 0) {
                        return;
                    }
                    showProgressDialog(ConversationList.this, progress,
                            context.getString(R.string.message_is_saving), total);
                    if (mSaveOrBackProgressDialog != null
                            && !mSaveOrBackProgressDialog.isShowing()) {
                        mSaveOrBackProgressDialog.show();
                    }
                    break;
                case BACKUP_ALL_MESSAGE_SUCCESS:
                    if (mSaveOrBackProgressDialog != null) {
                        mSaveOrBackProgressDialog.dismiss();
                        mSaveOrBackProgressDialog = null;
                    }
                    Toast.makeText(ConversationList.this, R.string.message_save_ok,
                            Toast.LENGTH_SHORT).show();
                    unregisterReceiver(backupAllMessageReceiver);
                    break;
                case BACKUP_ALL_MESSAGE_FAIL:
                    if (mSaveOrBackProgressDialog != null) {
                        mSaveOrBackProgressDialog.dismiss();
                        mSaveOrBackProgressDialog = null;
                    }
                    Toast.makeText(ConversationList.this, R.string.message_save_fail,
                            Toast.LENGTH_SHORT).show();
                    unregisterReceiver(backupAllMessageReceiver);
                    break;
                default:
                    break;
            }
        }
    };

    private BroadcastReceiver restoreAllMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra("status", -1);
            int progress = intent.getIntExtra("progress", 0);
            int total = intent.getIntExtra("total", 0);
            switch (status) {
                case RESTORE_ALL_MESSAGE_START:
                    showProgressDialog(context, 0,
                    context.getString(R.string.message_is_restore_begin), total);
                    if (mSaveOrBackProgressDialog != null
                            && !mSaveOrBackProgressDialog.isShowing()) {
                        mSaveOrBackProgressDialog.show();
                    }
                    break;
                case RESTORE_ALL_MESSAGE_SAVING:
                    if (total == 0) {
                        return;
                    }
                    showProgressDialog(context, progress,
                            context.getString(R.string.message_restoring), total);
                    if (mSaveOrBackProgressDialog != null
                            && !mSaveOrBackProgressDialog.isShowing()) {
                        mSaveOrBackProgressDialog.show();
                    }
                    break;
                case RESTORE_ALL_MESSAGE_SUCCESS:
                    List<ChatMessage> cMsgList = new ArrayList<ChatMessage>();
                    try {
                        String smsJsonMessageList = intent.getStringExtra("restoreSmsList");
                        Log.i("RCS_UI", "smsJsonMessageList->" + smsJsonMessageList);
                        JSONArray jsonObjs = new JSONArray(smsJsonMessageList);
                        for(int i = 0; i < jsonObjs.length(); i++){
                            JSONObject jsonObj = (JSONObject)jsonObjs.getJSONObject(i);
                            ChatMessage cMsg = new ChatMessage();
                            cMsg.setData(jsonObj.getString("body"));
                            cMsg.setIsRead(jsonObj.getInt("read"));
                            cMsg.setMsgType(jsonObj.getInt("type"));
                            cMsg.setTime(jsonObj.getLong("date"));
                            cMsg.setMsgState(jsonObj.getInt("status"));
                            cMsg.setContact(jsonObj.getString("address"));
                            cMsg.setThreadId(jsonObj.getLong("thread_id"));
                            cMsgList.add(cMsg);
                            Log.i("RCS_UI", "jsonObecj body ->" + jsonObj.getString("body"));
                        }
                        RcsUtils.rcsInsertMany(ConversationList.this, cMsgList);
                    } catch (Exception e) {
                        Log.e("RCS_UI",e.toString());
                    }

                    cMsgList.clear();
                    String rcsJsonMessageId = intent.getStringExtra("restoreRcsMsgIdList");
                    String rcsMessageId = rcsJsonMessageId.replaceAll("\"", "");
                    String[] messageId = rcsMessageId.split(",");
                    try {
                        MessageApi messageApi = RcsApiManager.getMessageApi();
                        int length = messageId.length;
                        for ( int i = 0; i < length; i++) {
                            if (i == 0) {
                                String messageIndex = messageId[0].substring(1);
                                if (length == 1) {
                                    messageIndex = messageIndex.substring(0,
                                            messageId[0].lastIndexOf("]") - 1);
                                }
                                ChatMessage indexMsg = messageApi.getMessageById(messageIndex);
                                if (indexMsg != null) {
                                    cMsgList.add(indexMsg);
                                }
                            } else if (i == length - 1) {
                                String messageEnd = messageId[i].substring(0,
                                        messageId[i].lastIndexOf("]"));
                                ChatMessage endMsg = messageApi.getMessageById(messageEnd);
                                if (endMsg != null) {
                                    cMsgList.add(endMsg);
                                }
                            } else {
                                ChatMessage cMsg = messageApi.getMessageById(messageId[i]);
                                if (cMsg != null) {
                                    cMsgList.add(cMsg);
                                }
                            }
                        }
                        RcsUtils.rcsInsertMany(ConversationList.this, cMsgList);
                    } catch (Exception e) {
                        Log.e("RCS_UI",e.toString());
                    }
                    if (mSaveOrBackProgressDialog != null) {
                        mSaveOrBackProgressDialog.dismiss();
                        mSaveOrBackProgressDialog = null;
                    }
                    unregisterReceiver(restoreAllMessageReceiver);
                    Toast.makeText(context, R.string.message_restore_ok,
                            Toast.LENGTH_SHORT).show();
                    break;
                case RESTORE_ALL_MESSAGE_FAIL:
                    if (mSaveOrBackProgressDialog != null){
                        mSaveOrBackProgressDialog.dismiss();
                        mSaveOrBackProgressDialog = null;
                    }
                    unregisterReceiver(restoreAllMessageReceiver);
                    Toast.makeText(context, R.string.message_restore_fail,
                            Toast.LENGTH_SHORT).show();
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Cache recipients information in a background thread in advance.
        RecipientIdCache.init(this);
        setContentView(R.layout.conversation_list_screen);
        mConvsertionSelect = getIntent().getBooleanExtra(MULTI_SELECT_CONV, false);
        Log.i("RCS_UI","mConvsertionSelect="+mConvsertionSelect);
        if (!handleIntent(getIntent())) {
            return;
        }

        mSmsPromoBannerView = findViewById(R.id.banner_sms_promo);

        mQueryHandler = new ThreadListQueryHandler(getContentResolver());

        ListView listView = getListView();
        listView.setOnCreateContextMenuListener(mConvListOnCreateContextMenuListener);
        listView.setOnKeyListener(mThreadListKeyListener);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        listView.setMultiChoiceModeListener(new ModeCallback());

        // RCS Features
        addPublicAccountEntranceIfAvailable();
        addNotificationListEntranceIfAvailable();

        // Tell the list view which view to display when the list is empty
        listView.setEmptyView(findViewById(R.id.empty));

        initListAdapter();

        setupActionBar();

        mProgressDialog = createProgressDialog();

        setTitle(R.string.app_label);

        mHandler = new Handler();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean checkedMessageLimits = mPrefs.getBoolean(CHECKED_MESSAGE_LIMITS, false);
        if (DEBUG) Log.v(TAG, "checkedMessageLimits: " + checkedMessageLimits);
        if (!checkedMessageLimits) {
            runOneTimeStorageLimitCheckForLegacyMessages();
        }

        if (savedInstanceState != null) {
            mSavedFirstVisiblePosition = savedInstanceState.getInt(LAST_LIST_POS,
                    AdapterView.INVALID_POSITION);
            mSavedFirstItemOffset = savedInstanceState.getInt(LAST_LIST_OFFSET, 0);
        } else {
            mSavedFirstVisiblePosition = AdapterView.INVALID_POSITION;
            mSavedFirstItemOffset = 0;
        }

        registerReceiver(mGroupReceiver, new IntentFilter(BroadcastConstants.UI_GROUP_MANAGE_NOTIFY));

    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putInt(LAST_LIST_POS, mSavedFirstVisiblePosition);
        outState.putInt(LAST_LIST_OFFSET, mSavedFirstItemOffset);
    }

    @Override
    public void onPause() {
        super.onPause();

        // Don't listen for changes while we're paused.
        mListAdapter.setOnContentChangedListener(null);

        // Remember where the list is scrolled to so we can restore the scroll position
        // when we come back to this activity and *after* we complete querying for the
        // conversations.
        ListView listView = getListView();
        mSavedFirstVisiblePosition = listView.getFirstVisiblePosition();
        View firstChild = listView.getChildAt(0);
        mSavedFirstItemOffset = (firstChild == null) ? 0 : firstChild.getTop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean isSmsEnabled = MmsConfig.isSmsEnabled(this);
        if (isSmsEnabled != mIsSmsEnabled) {
            mIsSmsEnabled = isSmsEnabled;
            invalidateOptionsMenu();
        }
        // Check if the RCS service is installed.
        mIsRcsEnabled = RcsApiManager.isRcsServiceInstalled();

        // Multi-select is used to delete conversations. It is disabled if we are not the sms app.
        ListView listView = getListView();
        if (mIsSmsEnabled) {
            listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        } else {
            listView.setChoiceMode(ListView.CHOICE_MODE_NONE);
        }

        // Show or hide the SMS promo banner
        if (mIsSmsEnabled || MmsConfig.isSmsPromoDismissed(this)) {
            mSmsPromoBannerView.setVisibility(View.GONE);
        } else {
            initSmsPromoBanner();
            mSmsPromoBannerView.setVisibility(View.VISIBLE);
        }

        mListAdapter.setOnContentChangedListener(mContentChangedListener);
    }

    private void addPublicAccountEntranceIfAvailable() {
        mPublicAccountItemView = LayoutInflater.from(this).inflate(
                R.layout.rcs_conversation_list_item_public_account, null);
        if (!RcsApiManager.isRcsServiceInstalled()
                || !RcsUtils.isPackageInstalled(this, RcsUtils.PUBLIC_ACCOUNT_PACKAGE_NAME)) {
            mPublicAccountItemView.setVisibility(View.GONE);
            return;
        }
        QuickContactBadge publicAccountPhotoView =
                (QuickContactBadge) mPublicAccountItemView.findViewById(R.id.avatar);
        publicAccountPhotoView.assignContactUri(null);
        getListView().addHeaderView(mPublicAccountItemView);
        mPublicAccountItemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(
                        "com.suntek.mway.rcs.publicaccount.ACTION_LUNCHER_RCS_CONVERSATION_LIST");
                startActivity(intent);
            }
        });
    }

    private void addNotificationListEntranceIfAvailable() {
        if (!RcsApiManager.isRcsServiceInstalled() ||
                !RcsUtils.isPackageInstalled(this, RcsUtils.NATIVE_UI_PACKAGE_NAME)) {
            return;
        }

        mNotificationListItemView = LayoutInflater.from(this).inflate(
                R.layout.rcs_conversation_list_item_notification_list, null);
        QuickContactBadge notificationListPhotoView =
                (QuickContactBadge) mNotificationListItemView.findViewById(R.id.avatar);
        notificationListPhotoView.assignContactUri(null);
        getListView().addHeaderView(mNotificationListItemView);
        mNotificationListItemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RcsUtils.startNotificationListActivity(ConversationList.this);
            }
        });
    }
    /**
     * Handle the intent passed by the caller.
     * @return True if the intent is cosumed at conversation mode.
     *         False if the intent is forwarded at folder mode.
     */
    private boolean handleIntent(Intent intent) {
        if (MessageUtils.isMailboxMode()) {
            if (intent.hasExtra(MESSAGE_ID) && intent.hasExtra(MESSAGE_TYPE)) {
                long msgId = intent.getLongExtra(MESSAGE_ID, -1);
                String msgType = intent.getStringExtra(MESSAGE_TYPE);
                if (msgId == -1) {
                    // Mms has multiple unread messages, go to in-box message list.
                    MessageUtils.showInboxMessageList(this);
                } else {
                    showMessageDetail(msgType, msgId);
                }
            } else {
                Intent modeIntent = new Intent(this, MailBoxMessageList.class);
                startActivityIfNeeded(modeIntent, -1);
            }
            finish();
            return false;
        } else {
            if (intent.hasExtra(THREAD_ID)) {
                long threadId = intent.getLongExtra(THREAD_ID, -1);
                if (threadId != -1) {
                    openThread(threadId);
                }
            }
            return true;
        }
    }

    private void showMessageDetail(String msgType, long msgId) {
        if ("sms".equals(msgType)) {
            MessageUtils.showSmsMessageContent(this, msgId);
        } else {
            Uri mmsUri = ContentUris.withAppendedId(Mms.Inbox.CONTENT_URI, msgId);
            if (getMMSMessageType(mmsUri) != PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND) {
                Intent intent = new Intent(this, MobilePaperShowActivity.class);
                intent.setData(mmsUri);
                intent.putExtra("unread", true);
                intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivityIfNeeded(intent, -1);
            } else {
                // The mms not downloaded, show the in-box message list.
                MessageUtils.showInboxMessageList(this);
            }
        }
    }

    private int getMMSMessageType(Uri uri) {
        ContentResolver contentResolver = getContentResolver();
        Cursor cursor = null;
        int type = PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND;
        cursor = contentResolver.query(uri, MMS_MESSAGE_TYPE_PROJECTION, null, null, null);
        if (cursor == null) {
            Log.e(TAG, "getMMSMessageType cursor is null ");
            return type;
        }
        try {
            if (cursor.moveToFirst()) {
                type = cursor.getInt(0);
                Log.d(TAG, "MMSMessageType = " + type);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            cursor.close();
        }
        return type;
    }

    private void setupActionBar() {
        ActionBar actionBar = getActionBar();

        ViewGroup v = (ViewGroup)LayoutInflater.from(this)
            .inflate(R.layout.conversation_list_actionbar, null);
        actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM,
                ActionBar.DISPLAY_SHOW_CUSTOM);
        actionBar.setCustomView(v,
                new ActionBar.LayoutParams(ActionBar.LayoutParams.WRAP_CONTENT,
                        ActionBar.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL | Gravity.RIGHT));

        mUnreadConvCount = (TextView)v.findViewById(R.id.unread_conv_count);
    }

    private final ConversationListAdapter.OnContentChangedListener mContentChangedListener =
        new ConversationListAdapter.OnContentChangedListener() {
        @Override
        public void onContentChanged(ConversationListAdapter adapter) {
            startAsyncQuery();
        }
    };

    private void resetSearchView() {
        mHandler.removeCallbacks(resetSearchRunnable);
        mHandler.postDelayed(resetSearchRunnable, DELAY_TIME);
    }

    private Runnable resetSearchRunnable = new Runnable() {
        @Override
        public void run() {
            // Only when user is searching, reset the SearchView.
            if (mSearchView != null && !mSearchView.isIconified()) {
                final CharSequence query = mSearchView.getQuery();
                invalidateOptionsMenu();
                mHandler.postDelayed(new Runnable() {

                    @Override
                    public void run() {
                        mSearchView.setQuery(query, false);
                    }
                }, DELAY_TIME);
            } else {
                invalidateOptionsMenu();
            }
        }
    };

    private void initListAdapter() {
        mListAdapter = new ConversationListAdapter(this, null);
        mListAdapter.setOnContentChangedListener(mContentChangedListener);
        setListAdapter(mListAdapter);
        getListView().setRecyclerListener(mListAdapter);
    }

    private void initSmsPromoBanner() {
        final PackageManager packageManager = getPackageManager();
        final String smsAppPackage = Telephony.Sms.getDefaultSmsPackage(this);

        // Get all the data we need about the default app to properly render the promo banner. We
        // try to show the icon and name of the user's selected SMS app and have the banner link
        // to that app. If we can't read that information for any reason we leave the fallback
        // text that links to Messaging settings where the user can change the default.
        Drawable smsAppIcon = null;
        ApplicationInfo smsAppInfo = null;
        try {
            smsAppIcon = packageManager.getApplicationIcon(smsAppPackage);
            smsAppInfo = packageManager.getApplicationInfo(smsAppPackage, 0);
        } catch (NameNotFoundException e) {
        }
        final Intent smsAppIntent = packageManager.getLaunchIntentForPackage(smsAppPackage);

        // If we got all the info we needed
        if (smsAppIcon != null && smsAppInfo != null && smsAppIntent != null) {
            ImageView defaultSmsAppIconImageView =
                    (ImageView)mSmsPromoBannerView.findViewById(R.id.banner_sms_default_app_icon);
            defaultSmsAppIconImageView.setImageDrawable(smsAppIcon);
            TextView smsPromoBannerTitle =
                    (TextView)mSmsPromoBannerView.findViewById(R.id.banner_sms_promo_title);
            String message = getResources().getString(R.string.banner_sms_promo_title_application,
                    smsAppInfo.loadLabel(packageManager));
            smsPromoBannerTitle.setText(message);

            mSmsPromoBannerView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(smsAppIntent);
                }
            });
        } else {
            // Otherwise the banner will be left alone and will launch settings
            mSmsPromoBannerView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Launch settings
                    Intent settingsIntent = new Intent(ConversationList.this,
                            MessagingPreferenceActivity.class);
                    startActivityIfNeeded(settingsIntent, -1);
                }
            });
        }
    }

    /**
     * Checks to see if the number of MMS and SMS messages are under the limits for the
     * recycler. If so, it will automatically turn on the recycler setting. If not, it
     * will prompt the user with a message and point them to the setting to manually
     * turn on the recycler.
     */
    public synchronized void runOneTimeStorageLimitCheckForLegacyMessages() {
        if (Recycler.isAutoDeleteEnabled(this)) {
            if (DEBUG) Log.v(TAG, "recycler is already turned on");
            // The recycler is already turned on. We don't need to check anything or warn
            // the user, just remember that we've made the check.
            markCheckedMessageLimit();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (Recycler.checkForThreadsOverLimit(ConversationList.this)) {
                    if (DEBUG) Log.v(TAG, "checkForThreadsOverLimit TRUE");
                    // Dang, one or more of the threads are over the limit. Show an activity
                    // that'll encourage the user to manually turn on the setting. Delay showing
                    // this activity until a couple of seconds after the conversation list appears.
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            Intent intent = new Intent(ConversationList.this,
                                    WarnOfStorageLimitsActivity.class);
                            startActivity(intent);
                        }
                    }, 2000);
                } else {
                    if (DEBUG) Log.v(TAG, "checkForThreadsOverLimit silently turning on recycler");
                    // No threads were over the limit. Turn on the recycler by default.
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            SharedPreferences.Editor editor = mPrefs.edit();
                            editor.putBoolean(MessagingPreferenceActivity.AUTO_DELETE, true);
                            editor.apply();
                        }
                    });
                }
                // Remember that we don't have to do the check anymore when starting MMS.
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        markCheckedMessageLimit();
                    }
                });
            }
        }, "ConversationList.runOneTimeStorageLimitCheckForLegacyMessages").start();
    }

    /**
     * Mark in preferences that we've checked the user's message limits. Once checked, we'll
     * never check them again, unless the user wipe-data or resets the device.
     */
    private void markCheckedMessageLimit() {
        if (DEBUG) Log.v(TAG, "markCheckedMessageLimit");
        SharedPreferences.Editor editor = mPrefs.edit();
        editor.putBoolean(CHECKED_MESSAGE_LIMITS, true);
        editor.apply();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!handleIntent(intent)) {
            return;
        }
        // Handle intents that occur after the activity has already been created.
        if (isFinishing()) {
            // Just for monkey test, "ConversationList" has been closed when
            // run "onNewIntent".
            return;
        }
        startAsyncQuery();
    }

    @Override
    protected void onStart() {
        super.onStart();

        MessagingNotification.cancelNotification(getApplicationContext(),
                SmsRejectedReceiver.SMS_REJECTED_NOTIFICATION_ID);

        DraftCache.getInstance().addOnDraftChangedListener(this);

        mDoOnceAfterFirstQuery = true;

        startAsyncQuery();

        // We used to refresh the DraftCache here, but
        // refreshing the DraftCache each time we go to the ConversationList seems overly
        // aggressive. We already update the DraftCache when leaving CMA in onStop() and
        // onNewIntent(), and when we delete threads or delete all in CMA or this activity.
        // I hope we don't have to do such a heavy operation each time we enter here.

        // we invalidate the contact cache here because we want to get updated presence
        // and any contact changes. We don't invalidate the cache by observing presence and contact
        // changes (since that's too untargeted), so as a tradeoff we do it here.
        // If we're in the middle of the app initialization where we're loading the conversation
        // threads, don't invalidate the cache because we're in the process of building it.
        // TODO: think of a better way to invalidate cache more surgically or based on actual
        // TODO: changes we care about
        if (!Conversation.loadingThreads()) {
            Contact.invalidateCache();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        DraftCache.getInstance().removeOnDraftChangedListener(this);

        // Simply setting the choice mode causes the previous choice mode to finish and we exit
        // multi-select mode (if we're in it) and remove all the selections.
        getListView().setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);

    }

    @Override
    protected void onDestroy() {
        if (mListAdapter != null) {
            mListAdapter.changeCursor(null);
        }
        super.onDestroy();

        Contact.clearListener();
        MessageUtils.removeDialogs();
        try {
            unregisterReceiver(mGroupReceiver);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDraftChanged(final long threadId, final boolean hasDraft) {
        // Run notifyDataSetChanged() on the main thread.
        mQueryHandler.post(new Runnable() {
            @Override
            public void run() {
                if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
                    log("onDraftChanged: threadId=" + threadId + ", hasDraft=" + hasDraft);
                }
                mListAdapter.notifyDataSetChanged();
            }
        });
    }

    private void startAsyncQuery() {
        try {
            ((TextView)(getListView().getEmptyView())).setText(R.string.loading_conversations);

            Conversation.startQueryForAll(mQueryHandler, THREAD_LIST_QUERY_TOKEN);
            Conversation.startQuery(mQueryHandler, UNREAD_THREADS_QUERY_TOKEN, Threads.READ + "=0");
        } catch (SQLiteException e) {
            SqliteWrapper.checkSQLiteException(this, e);
        }
    }

    SearchView.OnQueryTextListener mQueryTextListener = new SearchView.OnQueryTextListener() {
        @Override
        public boolean onQueryTextSubmit(String query) {
            Intent intent = new Intent();
            intent.setClass(ConversationList.this, SearchActivity.class);
            intent.putExtra(SearchManager.QUERY, query);
            startActivity(intent);
            mSearchItem.collapseActionView();
            return true;
        }

        @Override
        public boolean onQueryTextChange(String newText) {
            return false;
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        //In folder mode, it will jump to MailBoxMessageList,finish current
        //activity, no need create option menu.
        if (MessageUtils.isMailboxMode()) {
            return true;
        }
        if (mConvsertionSelect) {
            getMenuInflater().inflate(R.menu.conversation_multi_select_forward_menu, menu);
        } else {
            getMenuInflater().inflate(R.menu.conversation_list_menu, menu);
        }

        if (!getResources().getBoolean(R.bool.config_classify_search) && !mConvsertionSelect) {
            mSearchItem = menu.findItem(R.id.search);
            mSearchItem.setActionView(new SearchView(getApplicationContext()));
            mSearchView = (SearchView) mSearchItem.getActionView();
            mSearchView.setOnQueryTextListener(mQueryTextListener);
            mSearchView.setQueryHint(getString(R.string.search_hint));
            mSearchView.setIconifiedByDefault(true);
            SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);

            if (searchManager != null) {
                SearchableInfo info = searchManager.getSearchableInfo(this.getComponentName());
                mSearchView.setSearchableInfo(info);
            }
        }

        MenuItem cellBroadcastItem = menu.findItem(R.id.action_cell_broadcasts);
        if (cellBroadcastItem != null) {
            // Enable link to Cell broadcast activity depending on the value in config.xml.
            boolean isCellBroadcastAppLinkEnabled = this.getResources().getBoolean(
                    com.android.internal.R.bool.config_cellBroadcastAppLinks);
            try {
                if (isCellBroadcastAppLinkEnabled) {
                    PackageManager pm = getPackageManager();
                    if (pm.getApplicationEnabledSetting("com.android.cellbroadcastreceiver")
                            == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        isCellBroadcastAppLinkEnabled = false;  // CMAS app disabled
                    }
                }
            } catch (IllegalArgumentException ignored) {
                isCellBroadcastAppLinkEnabled = false;  // CMAS app not installed
            }
            if (!isCellBroadcastAppLinkEnabled) {
                cellBroadcastItem.setVisible(false);
            }
        }

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        //In folder mode, it will jump to MailBoxMessageList,finish current
        //activity, no need prepare option menu.
        if (MessageUtils.isMailboxMode()) {
            return true;
        }
        MenuItem item = menu.findItem(R.id.action_delete_all);
        if (item != null) {
            item.setVisible((mListAdapter.getCount() > 0) && mIsSmsEnabled);
        }
        item = menu.findItem(R.id.action_compose_new);
        if (item != null ){
            // Dim compose if SMS is disabled because it will not work (will show a toast)
            item.getIcon().setAlpha(mIsSmsEnabled ? 255 : 127);
        }

        if (!getResources().getBoolean(R.bool.config_mailbox_enable)) {
            item = menu.findItem(R.id.action_change_mode);
            if (item != null) {
                item.setVisible(false);
            }
            item = menu.findItem(R.id.action_memory_status);
            if (item != null) {
                item.setVisible(false);
            }
        }

        if (!LogTag.DEBUG_DUMP) {
            item = menu.findItem(R.id.action_debug_dump);
            if (item != null) {
                item.setVisible(false);
            }
        }
        return true;
    }

    @Override
    public boolean onSearchRequested() {
        if (getResources().getBoolean(R.bool.config_classify_search)) {
            // block search entirely (by simply returning false).
            return false;
        }

        if (mSearchItem != null) {
            mSearchItem.expandActionView();
        }
        return true;
    }

    private void selectComposeAction() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.menu_compose_new);
        builder.setItems(R.array.compose_message_items, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case 0:
                        createNewMessage();
                        break;
                    case 1:
                        createNewGroupChat();
                        break;
                }
            }
        });
        builder.setNegativeButton(R.string.no, null);
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.search:
                if (getResources().getBoolean(R.bool.config_classify_search)) {
                    Intent searchintent = new Intent(this, SearchActivityExtend.class);
                    startActivityIfNeeded(searchintent, -1);
                    break;
                }
                return true;
            case R.id.action_compose_new:
                if (mIsSmsEnabled) {
                    if (mIsRcsEnabled) {
                        selectComposeAction();
                    } else {
                        createNewMessage();
                    }
                } else {
                    // Display a toast letting the user know they can not compose.
                    if (mComposeDisabledToast == null) {
                        mComposeDisabledToast = Toast.makeText(this,
                                R.string.compose_disabled_toast, Toast.LENGTH_SHORT);
                    }
                    mComposeDisabledToast.show();
                }
                break;
            case R.id.action_delete_all:
                // The invalid threadId of -1 means all threads here.
                confirmDeleteThread(-1L, mQueryHandler);
                break;
            case R.id.action_settings:
                Intent intent = new Intent(this, MessagingPreferenceActivity.class);
                startActivityIfNeeded(intent, -1);
                break;
            case R.id.action_change_mode:
                Intent modeIntent = new Intent(this, MailBoxMessageList.class);
                MessageUtils.setMailboxMode(true);
                startActivityIfNeeded(modeIntent, -1);
                finish();
                break;
            case R.id.action_debug_dump:
                LogTag.dumpInternalTables(this);
                break;
            case R.id.action_memory_status:
                MessageUtils.showMemoryStatusDialog(this);
                break;
            case R.id.action_cell_broadcasts:
                Intent cellBroadcastIntent = new Intent(Intent.ACTION_MAIN);
                cellBroadcastIntent.setComponent(new ComponentName(
                        "com.android.cellbroadcastreceiver",
                        "com.android.cellbroadcastreceiver.CellBroadcastListActivity"));
                cellBroadcastIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(cellBroadcastIntent);
                } catch (ActivityNotFoundException ignored) {
                    Log.e(TAG, "ActivityNotFoundException for CellBroadcastListActivity");
                }
                return true;
            case R.id.my_favorited:
                Intent favouriteIntent = new Intent(this, FavouriteMessageList.class);
                favouriteIntent.putExtra("favorited", true);
                startActivityIfNeeded(favouriteIntent, -1);
                break;
            case R.id.saveorbackmessage:
                showSaveOrBackDialog(ConversationList.this);
                break;
            default:
                return true;
        }
        return false;
    }

    private void showSaveOrBackDialog(final Context context) {
        String[] items = new String[] {
                context.getString(R.string.message_save), context.getString(R.string.message_back)
        };
        AlertDialog.Builder builder = new Builder(context);
        builder.setTitle(getString(R.string.save_back_message));
        builder.setItems(items, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface arg0, int arg1) {
                switch (arg1) {
                    case ITME_BACKUP_ALL_MESSAGES:
                        new Thread(new Runnable() {
                            public void run() {
                                registerReceiver(backupAllMessageReceiver, new IntentFilter(
                                        BACKUP_ALL_MESSAGES));
                                try {
                                    RcsApiManager.getMessageApi().backupAllMessage();
                                } catch (ServiceDisconnectedException e) {
                                    e.printStackTrace();
                                }
                            }
                        }).start();
                        break;
                    case ITME_RESTORE_ALL_MESSAGES:
                        new Thread(new Runnable() {

                            @Override
                            public void run() {
                                registerReceiver(restoreAllMessageReceiver, new IntentFilter(
                                        RESTORE_ALL_MESSAGES));
                                try {
                                    RcsApiManager.getMessageApi().restoreAllMessage();
                                } catch (ServiceDisconnectedException e) {
                                    e.printStackTrace();
                                }

                            }
                        }).start();
                        break;
                    default:
                        break;
                }

            }
        });
        builder.create().show();
    }

    private void showProgressDialog(Context context, int progress, String title, int total) {
        if (mSaveOrBackProgressDialog == null) {
            mSaveOrBackProgressDialog = new ProgressDialog(context);
            mSaveOrBackProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            mSaveOrBackProgressDialog.setMessage(title);
            mSaveOrBackProgressDialog.setCancelable(false);
            mSaveOrBackProgressDialog.setCanceledOnTouchOutside(false);
            mSaveOrBackProgressDialog.setButton(
                    context.getResources().getString(R.string.cacel_restore_message),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                RcsApiManager.getMessageApi().cancelBackup();
                            } catch (Exception e) {
                                Log.e("RCS_UI", e.toString());
                            } finally {
                                try {
                                    unregisterReceiver(backupAllMessageReceiver);
                                } catch (Exception e2) {
                                    Log.e("RCS_UI", e2.toString());
                                }
                                try {
                                    unregisterReceiver(restoreAllMessageReceiver);
                                } catch (Exception e2) {
                                    Log.e("RCS_UI", e2.toString());
                                }
                            }
                        }
                    });

            mSaveOrBackProgressDialog.setMax(total);
            mSaveOrBackProgressDialog.setProgress(progress);
            mSaveOrBackProgressDialog.show();
        } else {
            mSaveOrBackProgressDialog.setMessage(title);
            mSaveOrBackProgressDialog.setMax(total);
            mSaveOrBackProgressDialog.setProgress(progress);
            mSaveOrBackProgressDialog.show();
        }
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        // Note: don't read the thread id data from the ConversationListItem view passed in.
        // It's unreliable to read the cached data stored in the view because the ListItem
        // can be recycled, and the same view could be assigned to a different position
        // if you click the list item fast enough. Instead, get the cursor at the position
        // clicked and load the data from the cursor.
        // (ConversationListAdapter extends CursorAdapter, so getItemAtPosition() should
        // return the cursor object, which is moved to the position passed in)
        Cursor cursor  = (Cursor) getListView().getItemAtPosition(position);
        Conversation conv = Conversation.from(this, cursor);
        long tid = conv.getThreadId();

        if (LogTag.VERBOSE) {
            Log.d(TAG, "onListItemClick: pos=" + position + ", view=" + v + ", tid=" + tid);
        }
        if (!mConvsertionSelect) {
            openThread(tid);
        }
    }

    private void createNewMessage() {
        startActivity(ComposeMessageActivity.createIntent(this, 0));
    }

    private void createNewGroupChat() {
        startActivity(ComposeMessageActivity.createGroupChatIntent(this, 0));
    }

    private void openThread(long threadId) {
        startActivity(ComposeMessageActivity.createIntent(this, threadId));
    }

    public static Intent createAddContactIntent(String address) {
        // address must be a single recipient
        Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
        intent.setType(Contacts.CONTENT_ITEM_TYPE);
        if (Mms.isEmailAddress(address)) {
            intent.putExtra(ContactsContract.Intents.Insert.EMAIL, address);
        } else {
            intent.putExtra(ContactsContract.Intents.Insert.PHONE, address);
            intent.putExtra(ContactsContract.Intents.Insert.PHONE_TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);

        return intent;
    }

    private final OnCreateContextMenuListener mConvListOnCreateContextMenuListener =
        new OnCreateContextMenuListener() {
        @Override
        public void onCreateContextMenu(ContextMenu menu, View v,
                ContextMenuInfo menuInfo) {
            Cursor cursor = mListAdapter.getCursor();
            if (cursor == null || cursor.getPosition() < 0) {
                return;
            }
            Conversation conv = Conversation.from(ConversationList.this, cursor);
            ContactList recipients = conv.getRecipients();
            menu.setHeaderTitle(recipients.formatNames(","));

            AdapterView.AdapterContextMenuInfo info =
                (AdapterView.AdapterContextMenuInfo) menuInfo;
            menu.add(0, MENU_VIEW, 0, R.string.menu_view);

            // Only show if there's a single recipient
            if (recipients.size() == 1) {
                // do we have this recipient in contacts?
                if (recipients.get(0).existsInDatabase()) {
                    menu.add(0, MENU_VIEW_CONTACT, 0, R.string.menu_view_contact);
                } else {
                    menu.add(0, MENU_ADD_TO_CONTACTS, 0, R.string.menu_add_to_contacts);
                }
            }
            if (mIsSmsEnabled) {
                menu.add(0, MENU_DELETE, 0, R.string.menu_delete);
            }
        }
    };

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        Cursor cursor = mListAdapter.getCursor();
        if (cursor != null && cursor.getPosition() >= 0) {
            Conversation conv = Conversation.from(ConversationList.this, cursor);
            long threadId = conv.getThreadId();
            switch (item.getItemId()) {
            case MENU_DELETE: {
                confirmDeleteThread(threadId, mQueryHandler);
                break;
            }
            case MENU_VIEW: {
                openThread(threadId);
                break;
            }
            case MENU_VIEW_CONTACT: {
                Contact contact = conv.getRecipients().get(0);
                Intent intent = new Intent(Intent.ACTION_VIEW, contact.getUri());
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                startActivity(intent);
                break;
            }
            case MENU_ADD_TO_CONTACTS: {
                String address = conv.getRecipients().get(0).getNumber();
                startActivity(createAddContactIntent(address));
                break;
            }
            default:
                break;
            }
        }
        return super.onContextItemSelected(item);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // We override this method to avoid restarting the entire
        // activity when the keyboard is opened (declared in
        // AndroidManifest.xml).  Because the only translatable text
        // in this activity is "New Message", which has the full width
        // of phone to work with, localization shouldn't be a problem:
        // no abbreviated alternate words should be needed even in
        // 'wide' languages like German or Russian.

        super.onConfigurationChanged(newConfig);
        if (DEBUG) Log.v(TAG, "onConfigurationChanged: " + newConfig);
    }

    /**
     * Start the process of putting up a dialog to confirm deleting a thread,
     * but first start a background query to see if any of the threads or thread
     * contain locked messages so we'll know how detailed of a UI to display.
     * @param threadId id of the thread to delete or -1 for all threads
     * @param handler query handler to do the background locked query
     */
    public static void confirmDeleteThread(long threadId, AsyncQueryHandler handler) {
        ArrayList<Long> threadIds = null;
        if (threadId != -1) {
            threadIds = new ArrayList<Long>();
            threadIds.add(threadId);
        }
        confirmDeleteThreads(threadIds, handler);
    }

    /**
     * Start the process of putting up a dialog to confirm deleting threads,
     * but first start a background query to see if any of the threads
     * contain locked messages so we'll know how detailed of a UI to display.
     * @param threadIds list of threadIds to delete or null for all threads
     * @param handler query handler to do the background locked query
     */
    public static void confirmDeleteThreads(Collection<Long> threadIds, AsyncQueryHandler handler) {
        Conversation.startQueryHaveLockedMessages(handler, threadIds,
                HAVE_LOCKED_MESSAGES_TOKEN);
    }

    /**
     * Build and show the proper delete thread dialog. The UI is slightly different
     * depending on whether there are locked messages in the thread(s) and whether we're
     * deleting single/multiple threads or all threads.
     * @param listener gets called when the delete button is pressed
     * @param threadIds the thread IDs to be deleted (pass null for all threads)
     * @param hasLockedMessages whether the thread(s) contain locked messages
     * @param context used to load the various UI elements
     */
    public static void confirmDeleteThreadDialog(final DeleteThreadListener listener,
            Collection<Long> threadIds,
            boolean hasLockedMessages,
            Context context) {
        View contents = View.inflate(context, R.layout.delete_thread_dialog_view, null);
        TextView msg = (TextView)contents.findViewById(R.id.message);

        if (threadIds == null) {
            msg.setText(R.string.confirm_delete_all_conversations);
        } else {
            // Show the number of threads getting deleted in the confirmation dialog.
            int cnt = threadIds.size();
            msg.setText(context.getResources().getQuantityString(
                R.plurals.confirm_delete_conversation, cnt, cnt));
        }

        final CheckBox checkbox = (CheckBox)contents.findViewById(R.id.delete_locked);
        if (!hasLockedMessages) {
            checkbox.setVisibility(View.GONE);
        } else {
            listener.setDeleteLockedMessage(checkbox.isChecked());
            checkbox.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.setDeleteLockedMessage(checkbox.isChecked());
                    mIsDeleteLockChecked = checkbox.isChecked();
                }
            });
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.confirm_dialog_title)
            .setIconAttribute(android.R.attr.alertDialogIcon)
            .setCancelable(true)
            .setPositiveButton(R.string.delete, listener)
            .setNegativeButton(R.string.no, null)
            .setView(contents)
            .show();
    }

    private final OnKeyListener mThreadListKeyListener = new OnKeyListener() {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DEL: {
                        long id = getListView().getSelectedItemId();
                        if (id > 0) {
                            confirmDeleteThread(id, mQueryHandler);
                        }
                        return true;
                    }
                }
            }
            return false;
        }
    };

    public static boolean getExitDialogueSign() {
        return mIsDeleteLockChecked;
    }

    public static void setExitDialogueSign() {
        mIsDeleteLockChecked = false;
    }

    public static class DeleteThreadListener implements OnClickListener {
        private final Collection<Long> mThreadIds;
        private final ConversationQueryHandler mHandler;
        private final Context mContext;
        private boolean mDeleteLockedMessages;
        private final Runnable mCallBack;

        public DeleteThreadListener(Collection<Long> threadIds, ConversationQueryHandler handler,
                Runnable callBack, Context context) {
            mThreadIds = threadIds;
            mHandler = handler;
            mContext = context;
            mCallBack = callBack;
        }

        public void setDeleteLockedMessage(boolean deleteLockedMessages) {
            mDeleteLockedMessages = deleteLockedMessages;
        }

        @Override
        public void onClick(DialogInterface dialog, final int whichButton) {
            MessageUtils.handleReadReport(mContext, mThreadIds,
                    PduHeaders.READ_STATUS__DELETED_WITHOUT_BEING_READ, new Runnable() {
                @Override
                public void run() {
                    int token = DELETE_CONVERSATION_TOKEN;
                    if (mCallBack != null) {
                        mCallBack.run();
                    }
                    if (mThreadIds == null) {
                        Conversation.startDeleteAll(mHandler, token, mDeleteLockedMessages);
                        DraftCache.getInstance().refresh();
                    } else {
                        int size = mThreadIds.size();
                        if(size > 0 && mCallBack != null) {
                            // Save the last thread id.
                            // And cancel deleting dialog after this thread been deleted.
                            mLastDeletedThread = (mThreadIds.toArray(new Long[size]))[size - 1];
                        }
                        Conversation.startDelete(mHandler, token, mDeleteLockedMessages,
                                mThreadIds);
                    }
                }
            });
            dialog.dismiss();
        }
    }

    private final Runnable mDeleteObsoleteThreadsRunnable = new Runnable() {
        @Override
        public void run() {
            if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
                LogTag.debug("mDeleteObsoleteThreadsRunnable getSavingDraft(): " +
                        DraftCache.getInstance().getSavingDraft());
            }
            if (DraftCache.getInstance().getSavingDraft()) {
                // We're still saving a draft. Try again in a second. We don't want to delete
                // any threads out from under the draft.
                if (DEBUGCLEANUP) {
                    LogTag.debug("mDeleteObsoleteThreadsRunnable saving draft, trying again");
                }
                mHandler.postDelayed(mDeleteObsoleteThreadsRunnable, 1000);
            } else if (SmsReceiverService.getSavingMessage()) {
                LogTag.debug("mDeleteObsoleteThreadsRunnable saving new message, trying again");
                mHandler.postDelayed(mDeleteObsoleteThreadsRunnable, 1000);
            } else {
                if (DEBUGCLEANUP) {
                    LogTag.debug("mDeleteObsoleteThreadsRunnable calling " +
                            "asyncDeleteObsoleteThreads");
                }
                Conversation.asyncDeleteObsoleteThreads(mQueryHandler,
                        DELETE_OBSOLETE_THREADS_TOKEN);
            }
        }
    };

    private final class ThreadListQueryHandler extends ConversationQueryHandler {
        public ThreadListQueryHandler(ContentResolver contentResolver) {
            super(contentResolver);
        }

        // Test code used for various scenarios where its desirable to insert a delay in
        // responding to query complete. To use, uncomment out the block below and then
        // comment out the @Override and onQueryComplete line.
//        @Override
//        protected void onQueryComplete(final int token, final Object cookie, final Cursor cursor) {
//            mHandler.postDelayed(new Runnable() {
//                public void run() {
//                    myonQueryComplete(token, cookie, cursor);
//                    }
//            }, 2000);
//        }
//
//        protected void myonQueryComplete(int token, Object cookie, Cursor cursor) {

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            switch (token) {
            case THREAD_LIST_QUERY_TOKEN:
                mListAdapter.changeCursor(cursor);

                if (mListAdapter.getCount() == 0) {
                    ((TextView)(getListView().getEmptyView())).setText(R.string.no_conversations);
                }

                if (mDoOnceAfterFirstQuery) {
                    mDoOnceAfterFirstQuery = false;
                    // Delay doing a couple of DB operations until we've initially queried the DB
                    // for the list of conversations to display. We don't want to slow down showing
                    // the initial UI.

                    // 1. Delete any obsolete threads. Obsolete threads are threads that aren't
                    // referenced by at least one message in the pdu or sms tables.
                    mHandler.post(mDeleteObsoleteThreadsRunnable);

                    // 2. Mark all the conversations as seen.
                    Conversation.markAllConversationsAsSeen(getApplicationContext());
                }
                if (mSavedFirstVisiblePosition != AdapterView.INVALID_POSITION) {
                    // Restore the list to its previous position.
                    getListView().setSelectionFromTop(mSavedFirstVisiblePosition,
                            mSavedFirstItemOffset);
                    mSavedFirstVisiblePosition = AdapterView.INVALID_POSITION;
                }
                resetSearchView();
                break;

            case UNREAD_THREADS_QUERY_TOKEN:
                int count = 0;
                if (cursor != null) {
                    count = cursor.getCount();
                    cursor.close();
                }
                mUnreadConvCount.setText(count > 0 ? Integer.toString(count) : null);
                break;

            case HAVE_LOCKED_MESSAGES_TOKEN:
                @SuppressWarnings("unchecked")
                Collection<Long> threadIds = (Collection<Long>)cookie;
                confirmDeleteThreadDialog(new DeleteThreadListener(threadIds, mQueryHandler,
                        mDeletingRunnable, ConversationList.this), threadIds,
                        cursor != null && cursor.getCount() > 0,
                        ConversationList.this);
                if (cursor != null) {
                    cursor.close();
                }
                break;

            default:
                Log.e(TAG, "onQueryComplete called with unknown token " + token);
            }
        }

        @Override
        protected void onDeleteComplete(int token, Object cookie, int result) {
            super.onDeleteComplete(token, cookie, result);
            switch (token) {
            case DELETE_CONVERSATION_TOKEN:
                long threadId = cookie != null ? (Long)cookie : -1;     // default to all threads
                if (threadId < 0 || threadId == mLastDeletedThread) {
                    mHandler.removeCallbacks(mShowProgressDialogRunnable);
                    if (mProgressDialog != null && mProgressDialog.isShowing()) {
                        mProgressDialog.dismiss();
                    }
                    mLastDeletedThread = -1;
                }

                if (threadId == -1) {
                    // Rebuild the contacts cache now that all threads and their associated unique
                    // recipients have been deleted.
                    Contact.init(ConversationList.this);
                } else {
                    // Remove any recipients referenced by this single thread from the
                    // contacts cache. It's possible for two or more threads to reference
                    // the same contact. That's ok if we remove it. We'll recreate that contact
                    // when we init all Conversations below.
                    Conversation conv = Conversation.get(ConversationList.this, threadId, false);
                    if (conv != null) {
                        ContactList recipients = conv.getRecipients();
                        for (Contact contact : recipients) {
                            contact.removeFromCache();
                        }
                    }
                }
                // Make sure the conversation cache reflects the threads in the DB.
                Conversation.init(ConversationList.this);

                // Update the notification for new messages since they
                // may be deleted.
                MessagingNotification.nonBlockingUpdateNewMessageIndicator(ConversationList.this,
                        MessagingNotification.THREAD_NONE, false);
                // Update the notification for failed messages since they
                // may be deleted.
                MessagingNotification.nonBlockingUpdateSendFailedNotification(ConversationList.this);

                // Make sure the list reflects the delete
                startAsyncQuery();

                MmsWidgetProvider.notifyDatasetChanged(getApplicationContext());
                break;

            case DELETE_OBSOLETE_THREADS_TOKEN:
                if (DEBUGCLEANUP) {
                    LogTag.debug("onQueryComplete finished DELETE_OBSOLETE_THREADS_TOKEN");
                }
                break;
            }
        }
    }

    public void checkAll() {
        int count = getListView().getCount();

        for (int i = 0; i < count; i++) {
            getListView().setItemChecked(i, true);
        }
        mListAdapter.notifyDataSetChanged();
    }

    public void unCheckAll() {
        int count = getListView().getCount();

        for (int i = 0; i < count; i++) {
            getListView().setItemChecked(i, false);
        }
        mListAdapter.notifyDataSetChanged();
    }

    private class ModeCallback implements ListView.MultiChoiceModeListener {
        private View mMultiSelectActionBarView;
        private TextView mSelectedConvCount;
        private ImageView mSelectedAll;
        private boolean mHasSelectAll = false;
        private HashSet<Long> mSelectedThreadIds;
        // build action bar with a spinner
        private SelectionMenu mSelectionMenu;

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            MenuInflater inflater = getMenuInflater();
            mSelectedThreadIds = new HashSet<Long>();
            if (mConvsertionSelect) {
                inflater.inflate(R.menu.conversation_multi_select_forward_menu, menu);
            } else {
                inflater.inflate(R.menu.conversation_multi_select_menu, menu);
            }

            if (mMultiSelectActionBarView == null) {
                mMultiSelectActionBarView = LayoutInflater.from(ConversationList.this).inflate(
                        R.layout.action_mode, null);
            }
            mode.setCustomView(mMultiSelectActionBarView);
            mSelectionMenu = new SelectionMenu(ConversationList.this,
                    (Button) mMultiSelectActionBarView.findViewById(R.id.selection_menu),
                    new PopupList.OnPopupItemClickListener() {
                        @Override
                        public boolean onPopupItemClick(int itemId) {
                            if (itemId == SelectionMenu.SELECT_OR_DESELECT) {
                                if (mHasSelectAll) {
                                    unCheckAll();
                                    mHasSelectAll = false;
                                } else {
                                    checkAll();
                                    mHasSelectAll = true;
                                }
                                mSelectionMenu.updateSelectAllMode(mHasSelectAll);
                            }
                            return true;
                        }
                    });
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            if (mMultiSelectActionBarView == null) {
                ViewGroup v = (ViewGroup)LayoutInflater.from(ConversationList.this)
                    .inflate(R.layout.conversation_list_multi_select_actionbar, null);
                mode.setCustomView(v);

                mSelectedConvCount = (TextView)v.findViewById(R.id.selected_conv_count);
            }
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.delete:
                    if (mSelectedThreadIds.size() > 0) {
                        confirmDeleteThreads(mSelectedThreadIds, mQueryHandler);
                    }
                    mode.finish();
                    break;
                case R.id.forward:
                    if (mSelectedThreadIds.size() > 0) {
                        Intent intent = new Intent();
                        intent.putExtra("selectThreadId", mSelectedThreadIds);
                        intent.putExtra("numbers", mNumbers);
                        if (mGroupChatModel != null) {
                            intent.putExtra("groupChatModel", mGroupChatModel);
                        }
                        setResult(RESULT_OK,intent);
                        finish();
                    }
                    break;

                default:
                    break;
            }
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            if (getListView().getAdapter() instanceof ConversationListAdapter) {
                 ConversationListAdapter adapter = (ConversationListAdapter)getListView().getAdapter();
                 adapter.setChecked(mSelectedThreadIds, false);
                 mSelectedThreadIds = null;
                 mSelectionMenu.dismiss();
             }
        }

        @Override
        public void onItemCheckedStateChanged(ActionMode mode,
                int position, long id, boolean checked) {
            ListView listView = getListView();
            if (position < listView.getHeaderViewsCount()) {
                return;
            }
            final int checkedCount = listView.getCheckedItemCount();

            mSelectionMenu.setTitle(getApplicationContext().getString(R.string.selected_count,
                    checkedCount));
            if (getListView().getCount() == checkedCount) {
                mHasSelectAll = true;
            } else {
                mHasSelectAll = false;
            }
            mSelectionMenu.updateSelectAllMode(mHasSelectAll);

            Cursor cursor  = (Cursor)listView.getItemAtPosition(position);
            Conversation conv = Conversation.from(ConversationList.this, cursor);
            conv.setIsChecked(checked);
            long threadId = conv.getThreadId();

            if (checked) {
                mSelectedThreadIds.add(threadId);
            } else {
                mSelectedThreadIds.remove(threadId);
            }
            mNumbers = conv.getRecipients().getNumbers();
            mGroupChatModel = conv.getGroupChat();
        }

    }

    private void log(String format, Object... args) {
        String s = String.format(format, args);
        Log.d(TAG, "[" + Thread.currentThread().getId() + "] " + s);
    }

    private ProgressDialog createProgressDialog() {
        ProgressDialog dialog = new ProgressDialog(this);
        dialog.setIndeterminate(true);
        dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);
        dialog.setMessage(getText(R.string.deleting_threads));
        return dialog;
    }

    private Runnable mDeletingRunnable = new Runnable() {
        @Override
        public void run() {
            mHandler.postDelayed(mShowProgressDialogRunnable, DELAY_TIME);
        }
    };

    private Runnable mShowProgressDialogRunnable = new Runnable() {
        @Override
        public void run() {
            if (mProgressDialog != null) {
                mProgressDialog.show();
            }
        }
    };
}
