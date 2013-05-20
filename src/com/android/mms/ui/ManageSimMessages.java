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

import com.android.mms.R;
import com.android.mms.data.Contact;
import com.android.mms.LogTag;
import com.android.mms.transaction.MessagingNotification;
import com.android.mms.util.Recycler;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.AsyncQueryHandler;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SqliteWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.Telephony.Sms;
import android.telephony.SmsManager;
import android.telephony.MSimSmsManager;
import android.telephony.MSimTelephonyManager;
import android.text.TextUtils;
import android.text.SpannableString;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ImageView;
import com.android.mms.R;
import android.widget.Toast;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.TelephonyIntents;

import java.util.ArrayList;

/**
 * Displays a list of the SMS messages stored on the ICC.
 */
public class ManageSimMessages extends Activity
        implements View.OnCreateContextMenuListener {
    private static final Uri ICC_URI = Uri.parse("content://sms/icc");
    private static final Uri ICC1_URI = Uri.parse("content://sms/icc1");
    private static final Uri ICC2_URI = Uri.parse("content://sms/icc2");
    private static final String TAG = "ManageSimMessages";
    private static final int MENU_COPY_TO_PHONE_MEMORY = 0;
    private static final int MENU_DELETE_FROM_SIM = 1;
    private static final int MENU_VIEW = 2;
    private static final int MENU_REPLY = 3;
    private static final int MENU_FORWARD = 4;
    private static final int MENU_CALL_BACK = 5;
    private static final int MENU_ADD_ADDRESS_TO_CONTACTS = 6;
    private static final int MENU_SEND_EMAIL = 7;
    private static final int OPTION_MENU_DELETE_ALL = 0;
    private static final int OPTION_MENU_STORAGE = 1;
    private static final int OPTION_MENU_MUTIL_SELECT = 2;
    private static final int SUB_INVALID = -1;
    private static final int SUB1 = 0;
    private static final int SUB2 = 1;
    private static final int SHOW_LIST = 0;
    private static final int SHOW_EMPTY = 1;
    private static final int SHOW_BUSY = 2;   
    private static final int SHOW_TOAST = 1;
    
    private int mState;
    private Uri mIccUri;
    private int mSubscription; // add for DSDS
    private ContentResolver mContentResolver;
    private Cursor mCursor = null;
    private ListView mSimList;
    private TextView mMessage;
    private MessageListAdapter mListAdapter = null;
    private AsyncQueryHandler mQueryHandler = null;
    private ProgressDialog mProgressDialog = null;
    private boolean mIsDeleteAll = false;
    ArrayList<String> mSelectedUris = new ArrayList<String>();

    public static final int SIM_FULL_NOTIFICATION_ID = 234;

    // The flag of contacts need update again.
    private boolean mIsNeedUpdateContacts = false;

    private final ContentObserver simChangeObserver =
            new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfUpdate) {
            refreshMessageList();
        }
    };

    // Define this ContentObserver for update the ListView
    // when Contacts information be changed
    private final ContentObserver mContactsChangedObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfUpdate) {
            mIsNeedUpdateContacts = updateContacts();
            
        }
    };

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mSubscription = getIntent().getIntExtra(MSimConstants.SUBSCRIPTION_KEY, SUB_INVALID);
        mIccUri = MessageUtils.getIccUriBySubscription(mSubscription);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);

        mContentResolver = getContentResolver();
        mQueryHandler = new QueryHandler(mContentResolver, this);
        setContentView(R.layout.sim_list);
        mSimList = (ListView) findViewById(R.id.messages);
        mMessage = (TextView) findViewById(R.id.empty_message);

        ActionBar actionBar = getActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);

        init();
        registerSimChangeObserver();
        registerReceiver(mIccStateChangedReceiver, new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED));
        registerReceiver(mIccStateChangedReceiver, new IntentFilter(MessageUtils.ACTION_SIM_STATE_CHANGED0));
        registerReceiver(mIccStateChangedReceiver, new IntentFilter(MessageUtils.ACTION_SIM_STATE_CHANGED1));

    }

    @Override
    protected void onNewIntent(Intent intent) {
        setIntent(intent);
        ///init();   
        MessagingNotification.cancelNotification(getApplicationContext(),
                SIM_FULL_NOTIFICATION_ID);        
        updateState(SHOW_BUSY);
        startQuery();
    }

    private void init() {
        updateState(SHOW_BUSY);
        if(MessageUtils.sIsIccLoaded)
        {
            startQuery();
        }
    }


    /**
     * A wrapper of a broadcast receiver which provides network connectivity information
     * for all kinds of network: wifi, mobile, etc.
     */
    private final BroadcastReceiver mIccStateChangedReceiver = new BroadcastReceiver(){
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action)) {        
                int subscription = intent.getIntExtra(MessageUtils.SUB_KEY, -1);
                String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                Log.d(TAG, "mIccStateChangedReceiver: Handling incoming intent = " 
                    + intent + ", stateExtra = " + stateExtra); 
                
                if(!MessageUtils.isMultiSimEnabledMms())
                {
                    subscription = MessageUtils.SUB_INVALID;
                }
                
                if (IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(stateExtra) && subscription == mSubscription) {
                    startQuery();
                }
            }
            else if (MessageUtils.ACTION_SIM_STATE_CHANGED0.equals(action)) {        
                int subscription = intent.getIntExtra(MessageUtils.SUB_KEY, -1);
                String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                Log.d(TAG, "mIccStateChangedReceiver: Handling incoming intent = " 
                    + intent + ", stateExtra = " + stateExtra); 
                             
                if (IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(stateExtra) && subscription == mSubscription) {
                    startQuery();
                }
            }
            else if (MessageUtils.ACTION_SIM_STATE_CHANGED1.equals(action)) {        
                int subscription = intent.getIntExtra(MessageUtils.SUB_KEY, -1);
                String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                Log.d(TAG, "mIccStateChangedReceiver: Handling incoming intent = " 
                    + intent + ", stateExtra = " + stateExtra);
                
                if (IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(stateExtra) && subscription == mSubscription) {
                    startQuery();
                }
            }
        }
    };
    
    private void setMessageRead(Context context, String indexString)
    {
        Log.d(TAG, "setMessageRead : mSubscription=" + mSubscription + ",indexString = " + indexString);

        ContentValues values = new ContentValues(1);
        values.put("status_on_icc", MessageUtils.STATUS_ON_SIM_READ);
        SqliteWrapper.update(context, getContentResolver(),
            Uri.parse(MessageUtils.getIccUriBySubscription(mSubscription).toString()+"/"+indexString),
            values, null, null);
    }  

    private void setMessageRead(Context context)
    {
        Log.d(TAG, "setMessageRead : mSubscription = " + mSubscription);
        
        ContentValues values = new ContentValues(1);
        values.put("status_on_icc", MessageUtils.STATUS_ON_SIM_READ);
        SqliteWrapper.update(context, getContentResolver(),
            MessageUtils.getIccUriBySubscription(mSubscription),
            values, null, null);
    }  
    
    private Handler uihandler = new Handler()
    {
        @Override
        public void handleMessage(Message msg)
        {
            switch (msg.what)
            {
                case SHOW_TOAST:
                {                
                    String toastStr = (String) msg.obj;
                    Toast.makeText(ManageSimMessages.this, toastStr, 
                                    Toast.LENGTH_LONG).show();

                    if (mProgressDialog != null)
                    {
                        mProgressDialog.dismiss();
                    }

                    break; 
                }
                
                default:
                    break;
            }
        }
    };
    
    private class QueryHandler extends AsyncQueryHandler {
        private final ManageSimMessages mParent;

        public QueryHandler(
                ContentResolver contentResolver, ManageSimMessages parent) {
            super(contentResolver);
            mParent = parent;
        }

        @Override
        protected void onQueryComplete(
                int token, Object cookie, Cursor cursor) {
            mCursor = cursor;

            if (mCursor != null) {
                if (!mCursor.moveToFirst()) {
                    // Let user know the SIM is empty
                    updateState(SHOW_EMPTY);
                } else if (mListAdapter == null) {
                    // Note that the MessageListAdapter doesn't support auto-requeries. If we
                    // want to respond to changes we'd need to add a line like:
                    //   mListAdapter.setOnDataSetChangedListener(mDataSetChangedListener);
                    // See ComposeMessageActivity for an example.
                    mListAdapter = new MessageListAdapter(
                            mParent, mCursor, mSimList, false, null, true);
                    mSimList.setAdapter(mListAdapter);
                    mSimList.setOnCreateContextMenuListener(mParent);
                    mSimList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                        @Override
                        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                            if (view != null) {
                                ((MessageListItem) view).onMessageListItemClick();
                            }
                        }
                    });
                    updateState(SHOW_LIST);
                } else {
                    mListAdapter.changeCursor(mCursor);
                    updateState(SHOW_LIST);
                }
                                           
                //startManagingCursor(mCursor);
            } else {
                // Let user know the SIM is empty
                updateState(SHOW_EMPTY);
            }
            // Show option menu when query complete.
            invalidateOptionsMenu();
        }
    }

    private void startQuery() {
        try {
            mQueryHandler.startQuery(0, null, mIccUri, null, null, null, null);
        } catch (SQLiteException e) {
            SqliteWrapper.checkSQLiteException(this, e);
        }
    }

    private void refreshMessageList() {
        updateState(SHOW_BUSY);
        /*
        if (mCursor != null) {
            stopManagingCursor(mCursor);
            mCursor.close();
        }
        */
        startQuery();
    }

    @Override
    public void onCreateContextMenu(
            ContextMenu menu, View v,
            ContextMenu.ContextMenuInfo menuInfo) {
        if (mCursor == null || mCursor.getPosition() < 0) {
            return;
        }

        if(MessageUtils.isMultiSimEnabledMms())
        {
            menu.add(0, MENU_COPY_TO_PHONE_MEMORY, 0,
                     R.string.menu_copy_to);
        }
        else
        {
            menu.add(0, MENU_COPY_TO_PHONE_MEMORY, 0,
                     R.string.sim_copy_to_phone_memory);
        }

        menu.add(0, MENU_DELETE_FROM_SIM, 0, R.string.sim_delete);

        Cursor cursor = mListAdapter.getCursor();
        AdapterView.AdapterContextMenuInfo menuinfo = (AdapterView.AdapterContextMenuInfo) menuInfo;
        cursor = (Cursor)mListAdapter.getItem((int) menuinfo.position);

        if (isIncomingMessage(cursor)) {
            String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
            if(address != null)
            {
                Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", address, null));
                menu.add(0, MENU_REPLY, 0, R.string.menu_reply).setIntent(intent);
            }
        }
        menu.add(0, MENU_FORWARD, 0, R.string.menu_forward);
        //addCallAndContactMenuItems(menu, cursor);

        // TODO: Enable this once viewMessage is written.
        // menu.add(0, MENU_VIEW, 0, R.string.sim_view);
    }

    // refs to ComposeMessageActivity.java
    private final void addCallAndContactMenuItems(
            ContextMenu menu, Cursor cursor) {
        String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
        String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
        // Add all possible links in the address & message
        StringBuilder textToSpannify = new StringBuilder();
        if (address != null) {
            textToSpannify.append(address + ": ");
        }
        textToSpannify.append(body);

        SpannableString msg = new SpannableString(textToSpannify.toString());
        Linkify.addLinks(msg, Linkify.ALL);
        ArrayList<String> uris =
            MessageUtils.extractUris(msg.getSpans(0, msg.length(), URLSpan.class));

        while (uris.size() > 0) {
            String uriString = uris.remove(0);
            // Remove any dupes so they don't get added to the menu multiple times
            while (uris.contains(uriString)) {
                uris.remove(uriString);
            }

            int sep = uriString.indexOf(":");
            String prefix = null;
            if (sep >= 0) {
                prefix = uriString.substring(0, sep);
                uriString = uriString.substring(sep + 1);
            }
            boolean addToContacts = false;
            if ("mailto".equalsIgnoreCase(prefix))  {
                String sendEmailString = getString(
                        R.string.menu_send_email).replace("%s", uriString);
                Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("mailto:" + uriString));
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                menu.add(0, MENU_SEND_EMAIL, 0, sendEmailString)
                    .setIntent(intent);
                addToContacts = !haveEmailContact(uriString);
            } else if ("tel".equalsIgnoreCase(prefix)) {
                String callBackString = getString(
                        R.string.menu_call_back).replace("%s", uriString);
                Intent intent = new Intent(Intent.ACTION_CALL,
                        Uri.parse("tel:" + uriString));
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                menu.add(0, MENU_CALL_BACK, 0, callBackString)
                    .setIntent(intent);
                addToContacts = !isNumberInContacts(uriString);
            }
            if (addToContacts) {
                Intent intent = ConversationList.createAddContactIntent(uriString);
                String addContactString = getString(
                        R.string.menu_add_address_to_contacts).replace("%s", uriString);
                menu.add(0, MENU_ADD_ADDRESS_TO_CONTACTS, 0, addContactString)
                    .setIntent(intent);
            }
        }
    }

    private boolean haveEmailContact(String emailAddress) {
        Cursor cursor = SqliteWrapper.query(this, getContentResolver(),
                Uri.withAppendedPath(Email.CONTENT_LOOKUP_URI, Uri.encode(emailAddress)),
                new String[] { Contacts.DISPLAY_NAME }, null, null, null);

        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(0);
                    if (!TextUtils.isEmpty(name)) {
                        return true;
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return false;
    }

    private boolean isNumberInContacts(String phoneNumber) {
        return Contact.get(phoneNumber, false).existsInDatabase();
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info;
        try {
             info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        } catch (ClassCastException exception) {
            Log.e(TAG, "Bad menuInfo.", exception);
            return false;
        }

        final Cursor cursor = (Cursor) mListAdapter.getItem(info.position);

        switch (item.getItemId()) {
            case MENU_COPY_TO_PHONE_MEMORY:
                if(MessageUtils.isMultiSimEnabledMms())
                {
                    if(MessageUtils.getActivatedIccCardCount() > 1)
                    {
                        showCopySelectDialog(cursor);
                    }
                    else
                    {
                        copyToPhoneMemory(cursor);                       
                    }                  
                }
                else
                {
                    copyToPhoneMemory(cursor);
                }
                return true;
            case MENU_DELETE_FROM_SIM:
                confirmDeleteDialog(new OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        updateState(SHOW_BUSY);
                        deleteFromSim(cursor);
                        dialog.dismiss();
                        MessageUtils.checkIsPhoneMessageFull(ManageSimMessages.this);
                    }
                }, R.string.confirm_delete_SIM_message);
                return true;
            case MENU_FORWARD: {
                String smsBody = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                forwardMessage(smsBody);
                return true;
            }
            case MENU_VIEW:
                viewMessage(cursor);
                return true;
        }
        return super.onContextItemSelected(item);
    }

    private void showCopySelectDialog(final Cursor cursor){
        String targetCard = mSubscription == SUB1 ? MessageUtils.getMultiSimName(this, SUB2) : MessageUtils.getMultiSimName(this, SUB1);
        String[] items = new String[] {getString(R.string.view_setting_phone), targetCard};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.menu_copy_to));
        builder.setCancelable(true);
        builder.setItems(items, new DialogInterface.OnClickListener()
        {
            public final void onClick(DialogInterface dialog, int which)
            {
                if (which == 0)
                {
                     copyToPhoneMemory(cursor);
                }
                else
                {
                     copyToCard(cursor, mSubscription == SUB1 ? SUB2 : SUB1);
                }
                dialog.dismiss();
            }
        });
        builder.show();    
    }

    private void copyToCard(Cursor cursor, final int subscription)
    {
        final String address = cursor.getString(
                cursor.getColumnIndexOrThrow("address"));
        final String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
        final Long date = cursor.getLong(cursor.getColumnIndexOrThrow("date"));
        final int boxId = cursor.getInt(cursor.getColumnIndexOrThrow("type"));
        
        SmsManager smsManager = SmsManager.getDefault();
        final ArrayList<String> messages = smsManager.divideMessage(body);
        {
            final int messageCount = messages.size();

            new Thread(new Runnable() {
                public void run() {
                    Message msg = Message.obtain();
                    msg.what = SHOW_TOAST;
                    msg.obj = getString(R.string.operate_success);

                    for (int j = 0; j < messageCount; j++)
                    {
                        String content = messages.get(j);
                        if (TextUtils.isEmpty(content))
                        {
                            if (LogTag.VERBOSE || Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
                                Log.e(TAG, "copyToCard : Copy error for empty content!");
                            }
                            continue;
                        }
                        ContentValues values = new ContentValues(6);
                        values.put(Sms.DATE, date);
                        values.put(Sms.BODY, content);
                        values.put(Sms.TYPE, boxId);
                        values.put(Sms.ADDRESS, address);
                        values.put(Sms.READ, MessageUtils.MESSAGE_READ);
                        values.put(Sms.SUB_ID, subscription);  // -1 for MessageUtils.SUB_INVALID , 0 for MessageUtils.SUB1, 1 for MessageUtils.SUB2                 
                        Uri uriStr = MessageUtils.getIccUriBySubscription(subscription);
                        
                        Uri retUri = SqliteWrapper.insert(ManageSimMessages.this, getContentResolver(),
                                                          uriStr, values);
                        if (uriStr != null && retUri != null) {
                            Log.e(TAG, "copyToCard : uriStr = " + uriStr.toString() 
                                + ", retUri = " + retUri.toString());
                        }
                        
                        if (retUri == null)
                        {
                            msg.obj = getString(R.string.operate_failure);
                            break;
                        }
                        else if (MessageUtils.COPY_SUCCESS_FULL.equals(retUri.toString()))
                        {
                            msg.obj = getString(R.string.copy_success_full);
                            break;
                        }
                        else if (MessageUtils.COPY_FAILURE_FULL.equals(retUri.toString()))
                        {
                            msg.obj = getString(R.string.copy_failure_full);
                            break;
                        }
                    }
                          
                    uihandler.sendMessage(msg);
                }
            }).start();                  
        }
    }
    
    private void forwardMessage(String smsBody) {
        Intent intent = new Intent(this, ComposeMessageActivity.class);

        intent.putExtra("exit_on_sent", true);
        intent.putExtra("forwarded_message", true);

        intent.putExtra("sms_body", smsBody);
        intent.setClassName(this, "com.android.mms.ui.ForwardMessageActivity");
        startActivity(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();
        MessagingNotification.setCurrentlyDisplayedCardList(false);
        
        if (mCursor != null && !mCursor.isClosed()) {
            mCursor.close();
        }
    }

    @Override
    public void onDestroy() {
        mContentResolver.unregisterContentObserver(simChangeObserver);
        mContentResolver.unregisterContentObserver(mContactsChangedObserver);
        unregisterReceiver(mIccStateChangedReceiver);

        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mIsDeleteAll) {
            mIsDeleteAll = false;
        } else {
            super.onBackPressed();
        }
    }

    private void registerSimChangeObserver() {
        mContentResolver.registerContentObserver(
                mIccUri, true, simChangeObserver);
        mContentResolver.registerContentObserver(Contacts.CONTENT_URI, true,
                mContactsChangedObserver);
    }

    private void copyToPhoneMemory(Cursor cursor) {
        if(MessageUtils.isSmsMessageJustFull(this))
        {
            Toast.makeText(ManageSimMessages.this, R.string.exceed_message_size_limitation, 
                    Toast.LENGTH_LONG).show();
            return;
        }
                           
        String address = cursor.getString(
                cursor.getColumnIndexOrThrow("address"));
        String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
        Long date = cursor.getLong(cursor.getColumnIndexOrThrow("date"));
        int sub_id = cursor.getInt(cursor.getColumnIndexOrThrow("sub_id"));

        try {
            // The uri which is saved in native database
            Uri uri = null;
            if (isIncomingMessage(cursor)) {
                uri = Sms.Inbox.addMessage(mContentResolver, address, body, null, date, true /* read */, sub_id);
            } else {
                uri = Sms.Sent.addMessage(mContentResolver, address, body, null, date, sub_id);
            }

            // if native uri is exists, saved success and toast copy success
            // if native uri is null, saved fail and toast copy fail
            if (uri != null) {
                Toast.makeText(this, R.string.operate_success, Toast.LENGTH_SHORT).show();
                //Update the notification for text message memory may not be full, add for cmcc test
                MessageUtils.checkIsPhoneMessageFull(ManageSimMessages.this);
            } else {
                Toast.makeText(this, R.string.operate_failure, Toast.LENGTH_SHORT).show();
            }

            // Make sure this thread isn't over the limits in message count
            Recycler.getSmsRecycler().deleteOldMessages(this);
        } catch (SQLiteException e) {
            SqliteWrapper.checkSQLiteException(this, e);
        }
    }

    private boolean isIncomingMessage(Cursor cursor) {
        int messageStatus = cursor.getInt(
                cursor.getColumnIndexOrThrow("status"));
            
        return (messageStatus == SmsManager.STATUS_ON_ICC_READ) ||
               (messageStatus == SmsManager.STATUS_ON_ICC_UNREAD);
    }

    private void deleteFromSim(Cursor cursor) {
        String messageIndexString =
                cursor.getString(cursor.getColumnIndexOrThrow("index_on_icc"));
        Uri simUri = mIccUri.buildUpon().appendPath(messageIndexString).build();

        SqliteWrapper.delete(this, mContentResolver, simUri, null, null);
    }

    private void deleteAllFromSim() {
        mContentResolver.unregisterContentObserver(simChangeObserver);

        for (String uri : mSelectedUris)
        { 
            if (!mIsDeleteAll) {
                break;
            }
            SqliteWrapper.delete(this, mContentResolver, Uri.parse(uri), null, null);
        }
                
        Message msg = Message.obtain();
        msg.what = SHOW_TOAST;
        msg.obj = getString(R.string.operate_success);   
        uihandler.sendMessage(msg);
        
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                refreshMessageList();
                registerSimChangeObserver();
                MessageUtils.checkIsPhoneMessageFull(ManageSimMessages.this);
            }
        });
                
        mIsDeleteAll = false;
    }

    private String getUriStrByCursor(Cursor cursor)
    {
        String messageIndexString =
                cursor.getString(cursor.getColumnIndexOrThrow("index_on_icc"));
        Uri simUri = mIccUri.buildUpon().appendPath(messageIndexString).build();

        return simUri.toString();
    }

    private void calcuteSelect()
    {
        mIsDeleteAll = true;
        Cursor cursor = (Cursor) mListAdapter.getCursor();
         
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                int count = cursor.getCount();
     
                for (int i = 0; i < count; ++i) {
                    // Protection for cursor closed by others
                    if (!mIsDeleteAll || cursor.isClosed()) {
                        break;
                    }
                    cursor.moveToPosition(i);
                    mSelectedUris.add(getUriStrByCursor(cursor)); 
                }
            }
        }
    }
    

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();

        if ((null != mCursor) && (!mCursor.isClosed()) && (mCursor.getCount() > 0) && mState == SHOW_LIST) {
            menu.add(0, OPTION_MENU_DELETE_ALL, 0, R.string.menu_delete_messages).setIcon(
                    android.R.drawable.ic_menu_delete);

            // add for cmcc 
            menu.add(0, OPTION_MENU_STORAGE, 0, R.string.sim_capacity).setIcon(
                        android.R.drawable.ic_menu_delete);
            // add for cmcc 
            menu.add(0, OPTION_MENU_MUTIL_SELECT, 0, R.string.batch_operate).setIcon(
                        android.R.drawable.ic_menu_delete);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case OPTION_MENU_DELETE_ALL:
                calcuteSelect();
                confirmDeleteDialog(new OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        //updateState(SHOW_BUSY);
                        mProgressDialog = new ProgressDialog(ManageSimMessages.this);
                        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                        mProgressDialog.setCancelable(false);
                        mProgressDialog.setTitle(R.string.deleting_title);
                        mProgressDialog.setMessage(getString(R.string.please_wait));
                        mProgressDialog.show();
                        new Thread() {
                            @Override
                            public void run() {
                                deleteAllFromSim();
                            }
                        }.start();
                        dialog.dismiss();
                    }
                }, R.string.confirm_delete_all_SIM_messages);
                break;
            case OPTION_MENU_STORAGE:
                int total = -1;
                if(MessageUtils.isMultiSimEnabledMms())
                {
                    total = MSimSmsManager.getDefault().getSmsCapCountOnIcc(mSubscription);
                }
                else
                {
                    total = SmsManager.getDefault().getSmsCapCountOnIcc();
                }

                String message = null;
                if(total >= 0)
                {
                    message = getString(R.string.icc_sms_used) + Integer.toString(mCursor.getCount())
                        + "\n" + getString(R.string.icc_sms_total) + Integer.toString(total);
                }
                else
                {
                    message = getString(R.string.get_icc_sms_capacity_failed);
                }

                new AlertDialog.Builder(ManageSimMessages.this)
                             //.setIconAttribute(android.R.attr.alertDialogIcon)
                            .setTitle(R.string.show_icc_sms_capacity)
                            .setMessage(message)
                            .setCancelable(true)
                            .show();
                break;
            case OPTION_MENU_MUTIL_SELECT:
                if(MessageUtils.isMultiSimEnabledMms())
                {
                    Intent intent = new Intent(this, ManageSimMessagesMultiSelect.class);
                    intent.putExtra(MessageUtils.SUB_KEY, mSubscription);
                    startActivity(intent);
                }
                else
                {
                    startActivity(new Intent(this, ManageSimMessagesMultiSelect.class));
                }

                break;
            case android.R.id.home:
                // The user clicked on the Messaging icon in the action bar. Take them back from
                // wherever they came from
                finish();
                break;
        }

        return true;
    }
  
    private void confirmDeleteDialog(OnClickListener listener, int messageId) {
        // the alert icon shoud has black triangle and white exclamation mark in white background.
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.confirm_dialog_title);
        builder.setIconAttribute(android.R.attr.alertDialogIcon);
        builder.setCancelable(true);
        builder.setPositiveButton(R.string.yes, listener);
        builder.setNegativeButton(R.string.no, null);
        builder.setMessage(messageId);

        builder.show();
    }

    /**
      * Return the slot type according to subscription
      */
    public String getSlotStringBySubscription(int subscription) {
        switch (subscription) {
            case MSimConstants.SUB1:
                return getString(R.string.type_slot1);
            case MSimConstants.SUB2:
                return getString(R.string.type_slot2);
            default:
                return getString(R.string.sim_card);
        }
    }
        
    private void updateState(int state) {
        if (mState == state) {
            return;
        }

        mState = state;
        switch (state) {
            case SHOW_LIST:
                mSimList.setVisibility(View.VISIBLE);
                mMessage.setVisibility(View.GONE);  
                setTitle(getString(R.string.sim_manage_messages_title, getSlotStringBySubscription(mSubscription)));
                setProgressBarIndeterminateVisibility(false);
                mSimList.requestFocus();
                break;
            case SHOW_EMPTY:
                mSimList.setVisibility(View.GONE);
                mMessage.setVisibility(View.VISIBLE);
                setTitle(getString(R.string.sim_manage_messages_title, getSlotStringBySubscription(mSubscription)));
                setProgressBarIndeterminateVisibility(false);
                break;
            case SHOW_BUSY:
                mSimList.setVisibility(View.GONE);
                mMessage.setVisibility(View.GONE);
                setTitle(getString(R.string.refreshing));
                setProgressBarIndeterminateVisibility(true);
                break;
            default:
                Log.e(TAG, "Invalid State");
        }
    }

    private void viewMessage(Cursor cursor) {
        // TODO: Add this.
    }

    /**
     * update contact icon, the method be used after add or delete a contact
     * from ManagerSimMessages
     */
    private boolean updateContacts() {
        
        int count = mSimList.getCount();
        int number = 0;

        for (int i = 0; i < count; i++) {
            MessageListItem item = (MessageListItem) mSimList.getChildAt(i);

            // if the item doesn't show at the interface, it will be null.
            if (item != null) {
                boolean isSelf = Sms.isOutgoingFolder(item.getMessageItem().mBoxId);
                String addr = isSelf ? null : item.getMessageItem().mAddress;
                item.updateAvatarView(this, addr, false);
            } else {
                number++;
            }
        }

        // if (number == count), that didn't update the Contacts, should update Contacts again at onStart()
        return number == count;
    }

    @Override
    protected void onStart() {
        super.onStart();
        MessagingNotification.setCurrentlyDisplayedCardList(true);
        MessagingNotification.cancelNotification(getApplicationContext(),
                SIM_FULL_NOTIFICATION_ID);
        MessagingNotification.cancelNotification(getApplicationContext(),
                MessagingNotification.getNotificationIDBySubscription(mSubscription));
        setMessageRead(this);
        
        // if updateContacts call before this method, it will doesn't work well,
        // need updateContacts again.
        if (mIsNeedUpdateContacts) {
            mIsNeedUpdateContacts = updateContacts();
        }
    }
}

