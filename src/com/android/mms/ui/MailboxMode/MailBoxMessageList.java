/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
 * Copyright (C) 2012 The Android Open Source Project.
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

import java.util.ArrayList;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.ActivityNotFoundException;
import android.content.AsyncQueryHandler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SqliteWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Sms;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.android.mms.data.Contact;
import com.android.mms.LogTag;
import com.android.mms.R;
import com.android.mms.ui.MessageListAdapter;
import com.android.mms.ui.MessageUtils;
import com.google.android.mms.pdu.PduHeaders;

import static com.android.mms.ui.MessageListAdapter.MAILBOX_PROJECTION;
import static com.android.mms.ui.MessageListAdapter.COLUMN_MSG_TYPE;
import static com.android.mms.ui.MessageListAdapter.COLUMN_ID;
import static com.android.mms.ui.MessageListAdapter.COLUMN_THREAD_ID;
import static com.android.mms.ui.MessageListAdapter.COLUMN_SMS_ADDRESS;
import static com.android.mms.ui.MessageListAdapter.COLUMN_SMS_BODY;
import static com.android.mms.ui.MessageListAdapter.COLUMN_SUB_ID;
import static com.android.mms.ui.MessageListAdapter.COLUMN_SMS_DATE;
import static com.android.mms.ui.MessageListAdapter.COLUMN_SMS_READ;
import static com.android.mms.ui.MessageListAdapter.COLUMN_SMS_TYPE;
import static com.android.mms.ui.MessageListAdapter.COLUMN_SMS_STATUS;
import static com.android.mms.ui.MessageListAdapter.COLUMN_SMS_DATE_SENT;
import static com.android.mms.ui.MessageListAdapter.COLUMN_MMS_READ;
import static com.android.mms.ui.MessageListAdapter.COLUMN_MMS_MESSAGE_BOX;
import static com.android.mms.ui.MessageListAdapter.COLUMN_MMS_DELIVERY_REPORT;
import static com.android.mms.ui.MessageListAdapter.COLUMN_SMS_LOCKED;
import static com.android.mms.ui.MessageListAdapter.COLUMN_MMS_LOCKED;

/**
 * This activity provides a list view of MailBox-Mode.
 */
public class MailBoxMessageList extends ListActivity implements
        MailBoxMessageListAdapter.OnListContentChangedListener{
    private static final String TAG = "MailBoxMessageList";
    private static final String MAILBOX_URI = "content://mms-sms/mailbox/";
    private static final int MESSAGE_LIST_QUERY_TOKEN = 9001;

    // IDs of the spinner items for the box type.
    private static final int TYPE_INBOX = 1;
    private static final int TYPE_SENTBOX = 2;
    private static final int TYPE_DRAFTBOX = 3;
    private static final int TYPE_OUTBOX = 4;
    // IDs of the spinner items for the slot type in DSDS
    private static final int TYPE_ALL_SLOT = 0;
    private static final int TYPE_SLOT_ONE = 1;
    private static final int TYPE_SLOT_TWO = 2;

    private static final String NONE_SELECTED = "0";

    private boolean mIsPause = false;
    private boolean mHasQueryOver = true;
    private int mQueryBoxType = TYPE_INBOX;
    private int mQuerySlotType = TYPE_ALL_SLOT;
    private BoxMsgListQueryHandler mQueryHandler;
    private String mSmsWhereDelete = "";
    private String mMmsWhereDelete = "";
    private boolean mHasLockedMessage = false;

    private MailBoxMessageListAdapter mListAdapter = null;
    private Cursor mCursor;
    private final Object mCursorLock = new Object();
    private ListView mListView;
    private TextView mCountTextView;
    private View mSpinners;
    private Spinner mBoxSpinner = null;
    private Spinner mSlotSpinner = null;
    private ModeCallback mModeCallback = null;
    // mark whether comes into MultiChoiceMode or not.
    private boolean mMultiChoiceMode = false;
    private MenuItem mSearchItem;
    private SearchView mSearchView;
    private CharSequence mQueryText;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mQueryHandler = new BoxMsgListQueryHandler(getContentResolver());
        setContentView(R.layout.mailbox_list_screen);
        mSpinners = (View) findViewById(R.id.spinners);
        mBoxSpinner = (Spinner) findViewById(R.id.box_spinner);
        mSlotSpinner = (Spinner) findViewById(R.id.slot_spinner);
        initSpinner();

        mListView = getListView();
        getListView().setItemsCanFocus(true);
        mModeCallback = new ModeCallback();
        mListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        mListView.setMultiChoiceModeListener(mModeCallback);

        mListAdapter = new MailBoxMessageListAdapter(MailBoxMessageList.this,
                MailBoxMessageList.this, null);
        setListAdapter(mListAdapter);
        View emptyView = (View) findViewById(R.id.emptyview);
        mListView.setEmptyView(emptyView);

        ActionBar actionBar = getActionBar();
        actionBar.setDisplayHomeAsUpEnabled(false);
        setupActionBar();
    }

    @Override
    public boolean onSearchRequested() {
        // if comes into multiChoiceMode,do not continue to enter search mode ;
        if (mSearchItem != null && !mMultiChoiceMode) {
            mSearchItem.expandActionView();
        }
        return true;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // We override this method to avoid restarting the entire
        // activity when the keyboard is opened (declared in
        // AndroidManifest.xml). Because the only translatable text
        // in this activity is "New Message", which has the full width
        // of phone to work with, localization shouldn't be a problem:
        // no abbreviated alternate words should be needed even in
        // 'wide' languages like German or Russian.
        closeContextMenu();
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        if (!mHasQueryOver) {
            return;
        }
        Cursor c = (Cursor) l.getAdapter().getItem(position);
        if (c == null) {
            return;
        }

        try {
            boolean isDraft = "sms".equals(c.getString(COLUMN_MSG_TYPE))
                    && (c.getInt(COLUMN_SMS_TYPE) == Sms.MESSAGE_TYPE_DRAFT);
            isDraft |= "mms".equals(c.getString(COLUMN_MSG_TYPE))
                    && (c.getInt(MessageListAdapter.COLUMN_MMS_MESSAGE_BOX)
                            == Mms.MESSAGE_BOX_DRAFTS);

            if (isDraft) {
                Intent intent = new Intent(this, ComposeMessageActivity.class);
                intent.putExtra("thread_id", c.getLong(COLUMN_THREAD_ID));
                startActivity(intent);
                return;
            } else if ("sms".equals(c.getString(COLUMN_MSG_TYPE))) {
                showSmsMessageContent(c);
            } else {
                MessageUtils.viewMmsMessageAttachment(MailBoxMessageList.this,
                        ContentUris.withAppendedId(Mms.CONTENT_URI, c.getInt(COLUMN_ID)), null,
                        new AsyncDialog(MailBoxMessageList.this));
            }
        } finally {
            c.close();
        }
    }


    private void showSmsMessageContent(Cursor c) {
        if (c == null) {
            return;
        }

        Intent i = new Intent(this, MailBoxMessageContent.class);

        String addr = c.getString(COLUMN_SMS_ADDRESS);
        Long date = c.getLong(COLUMN_SMS_DATE);
        String dateStr = MessageUtils.formatTimeStampString(this, date, true);
        String msgUriStr = "content://" + c.getString(COLUMN_MSG_TYPE)
                + "/" + c.getString(COLUMN_ID);
        int smsType = c.getInt(COLUMN_SMS_TYPE);

        if (smsType == Sms.MESSAGE_TYPE_INBOX) {
            i.putExtra("sms_fromtolabel", getString(R.string.from_label));
            i.putExtra("sms_sendlabel", getString(R.string.received_label));
        } else {
            i.putExtra("sms_fromtolabel", getString(R.string.to_address_label));
            i.putExtra("sms_sendlabel", getString(R.string.sent_label));
        }
        i.putExtra("sms_datelongformat", date);
        i.putExtra("sms_datesentlongformat", c.getLong(COLUMN_SMS_DATE_SENT));
        i.putExtra("sms_body", c.getString(COLUMN_SMS_BODY));
        i.putExtra("sms_fromto", addr);
        i.putExtra("sms_displayname", Contact.get(addr, true).getName());
        i.putExtra("sms_date", dateStr);
        i.putExtra("msg_uri", Uri.parse(msgUriStr));
        i.putExtra("sms_threadid", c.getLong(COLUMN_THREAD_ID));
        i.putExtra("sms_status", c.getInt(COLUMN_SMS_STATUS));
        i.putExtra("sms_read", c.getInt(COLUMN_SMS_READ));
        i.putExtra("mailboxId", smsType);
        i.putExtra("sms_id", c.getInt(COLUMN_ID));
        i.putExtra("sms_uri_str", msgUriStr);
        i.putExtra("sms_on_uim", false);
        i.putExtra("sms_type", smsType);
        i.putExtra("sms_locked", c.getInt(COLUMN_SMS_LOCKED));
        i.putExtra("sms_subid", c.getInt(COLUMN_SUB_ID));
        i.putExtra("sms_select_text", true);
        startActivity(i);
    }

    private ArrayList<String> getAllMsgId() {
        ArrayList<String> ids = new ArrayList<String>();
        int size = mListAdapter.getCount();
        for (int j = 0; j < size; j++) {
            Cursor c = (Cursor) mListAdapter.getItem(j);
            if (c != null) {
                ids.add(getUriStrByCursor(c));
            }
        }
        return ids;
    }

    private String getUriStrByCursor(Cursor c) {
        if (c == null) {
            return "";
        }
        String msgid = c.getString(COLUMN_ID);
        String msgtype = c.getString(COLUMN_MSG_TYPE);
        String uriString = "content://" + msgtype + "/" + msgid;

        return uriString;
    }

    @Override
    public void onResume() {
        super.onResume();
        mIsPause = false;
        startAsyncQuery();
        getListView().invalidateViews();
    }

    @Override
    public void onPause() {
        super.onPause();
        mIsPause = true;
    }

    private void initSpinner() {
        mBoxSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int oldQueryType = mQueryBoxType;
                // position 0-3 means box: inbox, sent, draft, outbox
                mQueryBoxType = position + 1;
                if(mQueryBoxType>TYPE_OUTBOX)
                    mQueryBoxType=TYPE_OUTBOX;
                if (oldQueryType != mQueryBoxType) {
                    unCheckAll();
                    startAsyncQuery();
                    getListView().invalidateViews();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0) {
                // do nothing
            }
        });

        if (MessageUtils.isMultiSimEnabledMms()) {
            mSlotSpinner.setPrompt(getResources().getString(R.string.slot_type_select));
            ArrayAdapter<CharSequence> slotAdapter = ArrayAdapter.createFromResource(this,
                    R.array.slot_type, android.R.layout.simple_spinner_item);
            slotAdapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item);
            mSlotSpinner.setAdapter(slotAdapter);
            mSlotSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent,
                                           View view, int position, long id) {
                    // position 0-2 means slotType: slot_all, slot_one, slot_two
                    int oldQuerySlotType = mQuerySlotType;
                    if (position > TYPE_SLOT_TWO)
                        position = TYPE_ALL_SLOT;
                    mQuerySlotType = position;
                    if (oldQuerySlotType != mQuerySlotType) {
                        unCheckAll();
                        startAsyncQuery();
                        getListView().invalidateViews();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> arg0) {
                    // do nothing
                }
            });
        } else {
            mSlotSpinner.setVisibility(View.GONE);
        }

    }

    private void startAsyncQuery() {
        try {
            synchronized (mCursorLock) {
                setProgressBarIndeterminateVisibility(true);
                // FIXME: I have to pass the mQueryToken as cookie since the
                // AsyncQueryHandler.onQueryComplete() method doesn't provide
                // the same token as what I input here.
                mHasQueryOver = false;
                String selStr = null;
                if (mQuerySlotType == TYPE_SLOT_ONE) {
                    selStr = "sub_id = " + MessageUtils.SUB1;
                } else if (mQuerySlotType == TYPE_SLOT_TWO) {
                    selStr = "sub_id = " + MessageUtils.SUB2;
                }
                String mailboxUri = MAILBOX_URI + mQueryBoxType;
                if (LogTag.VERBOSE || Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
                    Log.d(TAG, "startAsyncQuery : mailboxUri = " + mailboxUri);
                }

                mQueryHandler.startQuery(MESSAGE_LIST_QUERY_TOKEN, 0, Uri.parse(mailboxUri),
                        MAILBOX_PROJECTION, selStr, null, "normalized_date DESC");
            }
        } catch (SQLiteException e) {
            mHasQueryOver = true;
            SqliteWrapper.checkSQLiteException(this, e);
            mListView.setVisibility(View.VISIBLE);
        }
    }

    private final class BoxMsgListQueryHandler extends AsyncQueryHandler {
        public BoxMsgListQueryHandler(ContentResolver contentResolver) {
            super(contentResolver);
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            synchronized (mCursorLock) {
                if (cursor != null) {
                    if (mCursor != null) {
                        mCursor.close();
                    }
                    mCursor = cursor;
                    mListAdapter.changeCursor(mCursor);
                    if (cursor.getCount() > 0) {
                        mCountTextView.setVisibility(View.VISIBLE);
                        if (mQueryBoxType == TYPE_INBOX) {
                            int count = 0;
                            while (cursor.moveToNext()) {
                                if (cursor.getInt(COLUMN_SMS_READ) == 0
                                        || cursor.getInt(COLUMN_MMS_READ) == 0) {
                                    count++;
                                }
                            }
                            mCountTextView.setText("" + count + "/" + cursor.getCount());
                        } else {
                            mCountTextView.setText("" + cursor.getCount());
                        }
                    } else {
                        mCountTextView.setVisibility(View.INVISIBLE);
                    }
                } else {
                    if (LogTag.VERBOSE || Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
                        Log.e(TAG, "Cannot init the cursor for the thread list.");
                    }
                    finish();
                }
            }
            setProgressBarIndeterminateVisibility(false);
            mHasQueryOver = true;
        }

    }

    SearchView.OnQueryTextListener mQueryTextListener = new SearchView.OnQueryTextListener() {
        @Override
        public boolean onQueryTextSubmit(String query) {
            Intent intent = new Intent();
            intent.setClass(MailBoxMessageList.this, SearchActivity.class);
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
        getMenuInflater().inflate(R.menu.conversation_list_menu, menu);
        mSearchItem = menu.findItem(R.id.search);
        mSearchView = (SearchView) mSearchItem.getActionView();
        if (mSearchView != null) {
            mSearchView.setOnQueryTextListener(mQueryTextListener);
            mSearchView.setQueryHint(getString(R.string.search_hint));
            mSearchView.setIconifiedByDefault(true);
            SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);

            if (searchManager != null) {
                SearchableInfo info = searchManager.getSearchableInfo(getComponentName());
                mSearchView.setSearchableInfo(info);
            }
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(R.id.action_delete_all);
        if (item != null) {
            item.setVisible(false);
        }

        // if mQueryText is not null,so restore it.
        if (mQueryText != null && mSearchView != null) {
            mSearchView.setQuery(mQueryText, false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_compose_new:
                startActivity(ComposeMessageActivity.createIntent(this, 0));
                break;
            case R.id.action_settings:
                Intent intent = new Intent(this, MessagingPreferenceActivity.class);
                startActivityIfNeeded(intent, -1);
                break;
            case R.id.action_change_mode:
                Intent modeIntent = new Intent(this, ConversationList.class);
                startActivityIfNeeded(modeIntent, -1);
                finish();
                break;
            case R.id.action_memory_status:
                MessageUtils.showMemoryStatusDialog(this);
                break;
            case R.id.action_debug_dump:
                LogTag.dumpInternalTables(this);
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
            default:
                return true;
        }
        return true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mHasLockedMessage = false;
        mIsPause = true;
        if (mCursor != null) {
            mCursor.close();
        }
        if (mListAdapter != null) {
            mListAdapter.changeCursor(null);
        }
    }

    private void confirmDeleteMessages() {
        calcuteSelect();
        DeleteMessagesListener l = new DeleteMessagesListener();
        confirmDeleteDialog(l, mHasLockedMessage);
    }

    private class DeleteMessagesListener implements OnClickListener {
        private boolean mDeleteLockedMessages;

        public void setDeleteLockedMessage(boolean deleteLockedMessage) {
            mDeleteLockedMessages = deleteLockedMessage;
        }

        @Override
        public void onClick(DialogInterface dialog, int whichButton) {
            deleteMessages(mDeleteLockedMessages);
        }
    }

    private void confirmDeleteDialog(final DeleteMessagesListener listener, boolean locked) {
        View contents = View.inflate(this, R.layout.delete_thread_dialog_view, null);
        TextView msg = (TextView) contents.findViewById(R.id.message);
        msg.setText(getString(R.string.confirm_delete_selected_messages));
        final CheckBox checkbox = (CheckBox) contents.findViewById(R.id.delete_locked);
        checkbox.setChecked(false);
        if (!mHasLockedMessage) {
            checkbox.setVisibility(View.GONE);
        } else {
            listener.setDeleteLockedMessage(checkbox.isChecked());
            checkbox.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    listener.setDeleteLockedMessage(checkbox.isChecked());
                }
            });
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.confirm_dialog_title);
        builder.setIconAttribute(android.R.attr.alertDialogIcon);
        builder.setCancelable(true);
        builder.setView(contents);
        builder.setPositiveButton(R.string.yes, listener);
        builder.setNegativeButton(R.string.no, null);
        builder.show();
    }

    private void deleteMessages(boolean deleteLocked) {
        String whereClause;
        String smsWhereDelete = mSmsWhereDelete;
        String mmsWhereDelete = mMmsWhereDelete;

        if (!TextUtils.isEmpty(mSmsWhereDelete)) {
            smsWhereDelete = smsWhereDelete.substring(0, smsWhereDelete.length() - 1);
            smsWhereDelete = "_id in (" + smsWhereDelete + ")";
            if (!deleteLocked) {
                whereClause = smsWhereDelete == null ? " locked=0 " : smsWhereDelete
                        + " AND locked=0 ";
            } else {
                whereClause = smsWhereDelete;
            }

            if (!TextUtils.isEmpty(whereClause)) {
                int delSmsCount = SqliteWrapper.delete(this, getContentResolver(),
                        Uri.parse("content://sms"), whereClause, null);
                if (delSmsCount > 0) {
                    Toast.makeText(MailBoxMessageList.this, getString(R.string.operate_success),
                            Toast.LENGTH_LONG).show();
                }
            }
        }

        if (!TextUtils.isEmpty(mmsWhereDelete)) {
            mmsWhereDelete = mmsWhereDelete.substring(0, mmsWhereDelete.length() - 1);
            mmsWhereDelete = "_id in (" + mmsWhereDelete + ")";
            if (!deleteLocked) {
                whereClause = mmsWhereDelete == null ? " locked=0 " : mmsWhereDelete
                        + " AND locked=0 ";
            } else {
                whereClause = mmsWhereDelete;
            }

            if (!TextUtils.isEmpty(whereClause)) {
                int delMmsCount = SqliteWrapper.delete(this, getContentResolver(),
                        Uri.parse("content://mms"), whereClause, null);
                if (delMmsCount > 0) {
                    Toast.makeText(MailBoxMessageList.this, getString(R.string.operate_success),
                            Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void calcuteSelect() {
        int count = mListAdapter.getCount();
        SparseBooleanArray booleanArray = mListView.getCheckedItemPositions();
        int size = booleanArray.size();

        if (count == 0 || size == 0) {
            return;
        }
        String smsWhereDelete = "";
        String mmsWhereDelete = "";
        boolean hasLocked = false;

        for (int j = 0; j < size; j++) {
            int position = booleanArray.keyAt(j);

            if (!mListView.isItemChecked(position)) {
                continue;
            }
            Cursor c = (Cursor) mListAdapter.getItem(position);
            if (c == null) {
                return;
            }

            String msgtype = "sms";
            try {
                msgtype = c.getString(COLUMN_MSG_TYPE);
            } catch (Exception ex) {
                continue;
            }
            if (msgtype.equals("sms")) {
                String msgId = c.getString(COLUMN_ID);
                int lockValue = c.getInt(COLUMN_SMS_LOCKED);
                if (lockValue == 1) {
                    hasLocked = true;
                }
                smsWhereDelete += msgId + ",";
            } else if (msgtype.equals("mms")) {
                int lockValue = c.getInt(COLUMN_MMS_LOCKED);
                if (lockValue == 1) {
                    hasLocked = true;
                }
                String msgId = c.getString(COLUMN_ID);
                mmsWhereDelete += msgId + ",";
            }
        }
        mSmsWhereDelete = smsWhereDelete;
        mMmsWhereDelete = mmsWhereDelete;
        mHasLockedMessage = hasLocked;
    }

    public void onListContentChanged() {
        if (!mIsPause) {
            startAsyncQuery();
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

    private void setupActionBar() {
        ActionBar actionBar = getActionBar();

        ViewGroup v = (ViewGroup) LayoutInflater.from(this).inflate(
                R.layout.mailbox_list_actionbar, null);
        actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM, ActionBar.DISPLAY_SHOW_CUSTOM);
        actionBar.setCustomView(v, new ActionBar.LayoutParams(ActionBar.LayoutParams.WRAP_CONTENT,
                ActionBar.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.RIGHT));

        mCountTextView = (TextView) v.findViewById(R.id.message_count);
    }

    private class ModeCallback implements ListView.MultiChoiceModeListener {
        private View mMultiSelectActionBarView;
        private TextView mSelectedConvCount;
        private ImageView mSelectedAll;
        //used in MultiChoiceMode
        private boolean mHasSelectAll = false;

        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            // comes into MultiChoiceMode
            mMultiChoiceMode = true;
            mSpinners.setVisibility(View.GONE);
            MenuInflater inflater = getMenuInflater();
            inflater.inflate(R.menu.conversation_multi_select_menu, menu);

            if (mMultiSelectActionBarView == null) {
                mMultiSelectActionBarView = (ViewGroup) LayoutInflater
                        .from(MailBoxMessageList.this).inflate(
                                R.layout.conversation_list_multi_select_actionbar, null);

                mSelectedConvCount = (TextView) mMultiSelectActionBarView
                        .findViewById(R.id.selected_conv_count);
            }

            if (mSelectedConvCount != null) {
                mSelectedConvCount.setText(NONE_SELECTED);
            }

            mode.setCustomView(mMultiSelectActionBarView);
            ((TextView) mMultiSelectActionBarView.findViewById(R.id.title))
                    .setText(R.string.select_messages);

            mSelectedAll = (ImageView)mMultiSelectActionBarView.findViewById(R.id.selecte_all);
            mSelectedAll.setImageResource(R.drawable.ic_menu_select_all);
            mSelectedAll.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if(mHasSelectAll) {
                            mHasSelectAll = false;
                            unCheckAll();
                            mSelectedAll.setImageResource(R.drawable.ic_menu_select_all);
                        } else {
                            mHasSelectAll = true;
                            checkAll();
                            mSelectedAll.setImageResource(R.drawable.ic_menu_unselect_all);
                        }
                    }
                });

            return true;
        }

        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            if (mMultiSelectActionBarView == null) {
                ViewGroup v = (ViewGroup) LayoutInflater.from(MailBoxMessageList.this).inflate(
                        R.layout.conversation_list_multi_select_actionbar, null);
                mode.setCustomView(v);
                mSelectedConvCount = (TextView) v.findViewById(R.id.selected_conv_count);
            }
            return true;
        }

        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            ListView listView = getListView();
            final int checkedCount = listView.getCheckedItemCount();
            switch (item.getItemId()) {
                case R.id.delete:
                    confirmDeleteMessages();
                    break;
                default:
                    break;
            }
            mode.finish();
            return true;
        }

        public void onDestroyActionMode(ActionMode mode) {
            // leave MultiChoiceMode
            mMultiChoiceMode = false;
            getListView().clearChoices();
            mListAdapter.notifyDataSetChanged();
            mSpinners.setVisibility(View.VISIBLE);
        }

        public void onItemCheckedStateChanged(ActionMode mode, int position, long id,
                boolean checked) {
            ListView listView = getListView();
            int checkedCount = listView.getCheckedItemCount();
            mSelectedConvCount.setText(Integer.toString(checkedCount));
            mListAdapter.updateItemBackgroud(position);
        }
    }
}
