/*
 * Copyright (C) 2007-2008 Esmertec AG.
 * Copyright (C) 2007-2008 The Android Open Source Project
 * Copyright (C) 2010-2012, The Linux Foundation. All rights reserved.
 * Not a Contribution, Apache license notifications and license are retained
 * for attribution purposes only
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

package com.android.mms.transaction;

import static android.content.Intent.ACTION_BOOT_COMPLETED;
import static android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION;

import java.util.Calendar;
import java.util.GregorianCalendar;

import android.app.Activity;
import android.app.Service;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SqliteWrapper;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.provider.Settings;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Sms.Inbox;
import android.provider.Telephony.Sms.Intents;
import android.provider.Telephony.Sms.Outbox;
import android.telephony.MSimSmsManager;
import android.telephony.ServiceState;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;
import android.database.sqlite.SQLiteException;
import com.android.mms.ui.MessageUtils;
import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.IccCardConstants;

import com.android.internal.telephony.TelephonyIntents;
import com.android.mms.LogTag;
import com.android.mms.R;
import com.android.mms.data.Contact;
import com.android.mms.data.Conversation;
import com.android.mms.ui.ClassZeroActivity;
import com.android.mms.util.Recycler;
import com.android.mms.util.SendingProgressTokenManager;
import com.android.mms.widget.MmsWidgetProvider;
import com.google.android.mms.MmsException;


/**
 * This service essentially plays the role of a "worker thread", allowing us to store
 * incoming messages to the database, update notifications, etc. without blocking the
 * main thread that SmsReceiver runs on.
 */
public class SmsReceiverService extends Service {
    private static final String TAG = "SmsReceiverService";
    private final String SUBSCRIPTION_KEY = "subscription";

    private ServiceHandler mServiceHandler;
    private Looper mServiceLooper;
    private boolean mSending;

    public static final String MESSAGE_SENT_ACTION =
        "com.android.mms.transaction.MESSAGE_SENT";

    // Indicates next message can be picked up and sent out.
    public static final String EXTRA_MESSAGE_SENT_SEND_NEXT ="SendNextMsg";

    public static final String ACTION_SEND_MESSAGE =
        "com.android.mms.transaction.SEND_MESSAGE";

    public static final String ICC_SMS_RECEIVED_ACTION =
        "com.android.mms.transaction.ICC_SMS_RECEIVED";

    // This must match the column IDs below.
    private static final String[] SEND_PROJECTION = new String[] {
        Sms._ID,        //0
        Sms.THREAD_ID,  //1
        Sms.ADDRESS,    //2
        Sms.BODY,       //3
        Sms.STATUS,     //4
        Sms.SUB_ID,     //5

    };

    public Handler mToastHandler = new Handler();
    private AsyncQueryHandler mQueryHandler = null;

    // This must match SEND_PROJECTION.
    private static final int SEND_COLUMN_ID         = 0;
    private static final int SEND_COLUMN_THREAD_ID  = 1;
    private static final int SEND_COLUMN_ADDRESS    = 2;
    private static final int SEND_COLUMN_BODY       = 3;
    private static final int SEND_COLUMN_STATUS     = 4;
    private static final int SEND_COLUMN_SUB_ID     = 5;

    static final int TOKEN_QUERY_ICC1  = 4097;
    static final int TOKEN_QUERY_ICC2  = 4098;
    static final int TOKEN_QUERY_ICC   = 4099;
    static final int TOKEN_DELETE_ICC1 = 4100;
    static final int TOKEN_DELETE_ICC2 = 4101;
    static final int TOKEN_DELETE_ICC  = 4102;

    private int mResultCode;

    @Override
    public void onCreate() {
        // Temporarily removed for this duplicate message track down.
//        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || LogTag.DEBUG_SEND) {
//            Log.v(TAG, "onCreate");
//        }

        // Start up the thread running the service.  Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block.
        HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mQueryHandler = new QueryHandler(getContentResolver());

        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Temporarily removed for this duplicate message track down.

        mResultCode = intent != null ? intent.getIntExtra("result", 0) : 0;

        if (mResultCode != 0) {
            Log.v(TAG, "onStart: #" + startId + " mResultCode: " + mResultCode +
                    " = " + translateResultCode(mResultCode));
        }

        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = startId;
        msg.obj = intent;
        mServiceHandler.sendMessage(msg);
        return Service.START_NOT_STICKY;
    }

    private static String translateResultCode(int resultCode) {
        switch (resultCode) {
            case Activity.RESULT_OK:
                return "Activity.RESULT_OK";
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                return "SmsManager.RESULT_ERROR_GENERIC_FAILURE";
            case SmsManager.RESULT_ERROR_RADIO_OFF:
                return "SmsManager.RESULT_ERROR_RADIO_OFF";
            case SmsManager.RESULT_ERROR_NULL_PDU:
                return "SmsManager.RESULT_ERROR_NULL_PDU";
            case SmsManager.RESULT_ERROR_NO_SERVICE:
                return "SmsManager.RESULT_ERROR_NO_SERVICE";
            case SmsManager.RESULT_ERROR_LIMIT_EXCEEDED:
                return "SmsManager.RESULT_ERROR_LIMIT_EXCEEDED";
            case SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE:
                return "SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE";
            default:
                return "Unknown error code";
        }
    }

    @Override
    public void onDestroy() {
        // Temporarily removed for this duplicate message track down.
//        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || LogTag.DEBUG_SEND) {
//            Log.v(TAG, "onDestroy");
//        }
        mServiceLooper.quit();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private class QueryHandler extends AsyncQueryHandler {
        public QueryHandler(ContentResolver cr) {
            super(cr);
        }

        @Override
        protected void onQueryComplete(int token, Object cookie,
                                       Cursor cursor)
        {
            Log.d(TAG, "onQueryComplete: token = " + token + ";cursor = " + cursor);
            if (cursor != null)
            {
                cursor.close();
            }
            switch(token) 
            {
                case TOKEN_QUERY_ICC1:
                    MessagingNotification.blockingUpdateNewMessageOnIccIndicator(SmsReceiverService.this, MessageUtils.SUB1);
                    //Update the notification for text message memory may not be full, add for cmcc test
                    MessageUtils.checkIsPhoneMessageFull(SmsReceiverService.this);
                    return;
                case TOKEN_QUERY_ICC2:
                    MessagingNotification.blockingUpdateNewMessageOnIccIndicator(SmsReceiverService.this, MessageUtils.SUB2);
                    //Update the notification for text message memory may not be full, add for cmcc test
                    MessageUtils.checkIsPhoneMessageFull(SmsReceiverService.this);
                    return;
                case TOKEN_QUERY_ICC:
                    MessagingNotification.blockingUpdateNewMessageOnIccIndicator(SmsReceiverService.this, MessageUtils.SUB_INVALID);
                    //Update the notification for text message memory may not be full, add for cmcc test
                    MessageUtils.checkIsPhoneMessageFull(SmsReceiverService.this);
                    return;
            }            
        }
        
        @Override
        protected void onDeleteComplete(int token, Object cookie, int result) 
        {
            return;
        }         
    }
    
    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        /**
         * Handle incoming transaction requests.
         * The incoming requests are initiated by the MMSC Server or by the MMS Client itself.
         */
        @Override
        public void handleMessage(Message msg) {
            int serviceId = msg.arg1;
            Intent intent = (Intent)msg.obj;

            if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                Log.v(TAG, "handleMessage serviceId: " + serviceId + " intent: " + intent);
            }
            if (intent != null) {
                String action = intent.getAction();

                int error = intent.getIntExtra("errorCode", 0);

                if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || LogTag.DEBUG_SEND) {
                    Log.v(TAG, "handleMessage action: " + action + " error: " + error);
                }

                if (MESSAGE_SENT_ACTION.equals(intent.getAction())) {
                    handleSmsSent(intent, error);
                } else if (SMS_RECEIVED_ACTION.equals(action)
                        || ICC_SMS_RECEIVED_ACTION.equals(action)) {
                    handleSmsReceived(intent, error);
                } else if (ACTION_BOOT_COMPLETED.equals(action)) {
                    handleBootCompleted();
                } else if (TelephonyIntents.ACTION_SERVICE_STATE_CHANGED.equals(action)) {
                    handleServiceStateChanged(intent);
                } else if (ACTION_SEND_MESSAGE.endsWith(action)) {
                    handleSendMessage(intent);
                } else if (TelephonyIntents.ACTION_SIM_STATE_CHANGED.equals(action)) {
                    int subscription = intent.getIntExtra(MessageUtils.SUB_KEY, MessageUtils.SUB_INVALID);
                    String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                    
                    Log.d(TAG, "ACTION_SIM_STATE_CHANGED : stateExtra= " + stateExtra + ",subscription= " + subscription);
                    if(!MessageUtils.isMultiSimEnabledMms())
                    {
                        subscription = MessageUtils.SUB_INVALID;
                    }
                    
                    if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
                        MessageUtils.setIsIccLoaded(false);
                        handleIccAbsent(subscription);
                    }
                    else if (IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(stateExtra)) {
                        MessageUtils.setIsIccLoaded(true);
                        queryIccSms(subscription);
                    }
                  }
            }
            // NOTE: We MUST not call stopSelf() directly, since we need to
            // make sure the wake lock acquired by AlertReceiver is released.
            SmsReceiver.finishStartingService(SmsReceiverService.this, serviceId);
        }
    }

    private void queryIccSms(int subscription)
    {
        Uri iccUri = MessageUtils.getIccUriBySubscription(subscription); 
        int tokenId = TOKEN_QUERY_ICC;
        if(MessageUtils.isMultiSimEnabledMms())
        {
            tokenId = subscription == MessageUtils.SUB1 ? TOKEN_QUERY_ICC1 : TOKEN_QUERY_ICC2;
        }
        
        try 
        {
            mQueryHandler.startQuery(tokenId, null, iccUri, null, null, null, null);
        } 
        catch (SQLiteException e) 
        {
            SqliteWrapper.checkSQLiteException(this, e);
        }
    }
    
    private void handleServiceStateChanged(Intent intent) {
        // If service just returned, start sending out the queued messages
        ServiceState serviceState = ServiceState.newFromBundle(intent.getExtras());
        int subscription = intent.getIntExtra(SUBSCRIPTION_KEY, 0);
        int prefSubscription = MSimSmsManager.getDefault().getPreferredSmsSubscription();
        // if service state is IN_SERVICE & current subscription is same as
        // preferred SMS subscription.i.e.as set under MultiSIM Settings,then
        // sendFirstQueuedMessage.
        if (serviceState.getState() == ServiceState.STATE_IN_SERVICE &&
            subscription == prefSubscription) {
            sendFirstQueuedMessage();
        }
    }

    private void handleSendMessage(Intent intent) {
        if (!mSending) {
            if (MessageUtils.isMultiSimEnabledMms()) {
                sendFirstQueuedMessage(intent.getIntExtra(SUBSCRIPTION_KEY, 0)); //Todo 
            } else {
                sendFirstQueuedMessage();
            }

        }
    }

    public synchronized void sendFirstQueuedMessage() {
        //sendFirstQueuedMessage(MSimSmsManager.getDefault().getPreferredSmsSubscription());
        sendFirstQueuedMessage(MessageUtils.SUB_INVALID);
    }

    public synchronized void sendFirstQueuedMessage(int subscription) {
        boolean success = true;
        // get all the queued messages from the database
        final Uri uri = Uri.parse("content://sms/queued");
        ContentResolver resolver = getContentResolver();
        String where = Sms.SUB_ID + "=" + subscription;
        Cursor c = SqliteWrapper.query(this, resolver, uri,
                        SEND_PROJECTION, where, null, "date ASC");  // date ASC so we send out in
                                                                    // same order the user tried
                                                                    // to send messages.
        Log.v(TAG, "sendFirstQueuedMessage = " + c.getCount());
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    String msgText = c.getString(SEND_COLUMN_BODY);
                    String address = c.getString(SEND_COLUMN_ADDRESS);
                    int threadId = c.getInt(SEND_COLUMN_THREAD_ID);
                    int status = c.getInt(SEND_COLUMN_STATUS);

                    int msgId = c.getInt(SEND_COLUMN_ID);
                    Uri msgUri = ContentUris.withAppendedId(Sms.CONTENT_URI, msgId);
                    SmsMessageSender sender;
                    sender = new SmsSingleRecipientSender(this,
                            address, msgText, threadId, status == Sms.STATUS_PENDING,
                            msgUri, subscription);

                    if (LogTag.DEBUG_SEND ||
                            LogTag.VERBOSE ||
                            Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                        Log.v(TAG, "sendFirstQueuedMessage " + msgUri +
                                ", address: " + address +
                                ", threadId: " + threadId);
                    }

                    try {
                        sender.sendMessage(SendingProgressTokenManager.NO_TOKEN);;
                        mSending = true;
                    } catch (MmsException e) {
                        Log.e(TAG, "sendFirstQueuedMessage: failed to send message " + msgUri
                                + ", caught ", e);
                        mSending = false;
                        messageFailedToSend(msgUri, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
                        success = false;
                        // Current message send was failed(have some
                        // exceptions), need send next one.
                        sendNextMessage(subscription);
                    }
                }
            } finally {
                c.close();
            }
        }
        if (success) {
            // We successfully sent all the messages in the queue. We don't need to
            // be notified of any service changes any longer.
            unRegisterForServiceStateChanges();
        }
    }

    private void handleSmsSent(Intent intent, int error) {
        Uri uri = intent.getData();
        mSending = false;
        boolean sendNextMsg = intent.getBooleanExtra(EXTRA_MESSAGE_SENT_SEND_NEXT, false);

        if (LogTag.DEBUG_SEND) {
            Log.v(TAG, "handleSmsSent uri: " + uri + " sendNextMsg: " + sendNextMsg +
                    " mResultCode: " + mResultCode +
                    " = " + translateResultCode(mResultCode) + " error: " + error);
        }

        if (mResultCode == Activity.RESULT_OK) {
            if (LogTag.DEBUG_SEND || Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE)) {
                Log.v(TAG, "handleSmsSent move message to sent folder uri: " + uri);
            }
            if (!Sms.moveMessageToFolder(this, uri, Sms.MESSAGE_TYPE_SENT, error)) {
                Log.e(TAG, "handleSmsSent: failed to move message " + uri + " to sent folder");
            }
            // Current message sent out, send next one if necessary.
            if (sendNextMsg) {
                sendNextMessage(intent.getIntExtra(SUBSCRIPTION_KEY, 0));
            }

            // Update the notification for failed messages since they may be deleted.
            MessagingNotification.nonBlockingUpdateSendFailedNotification(this);
        } else if ((mResultCode == SmsManager.RESULT_ERROR_RADIO_OFF) ||
                (mResultCode == SmsManager.RESULT_ERROR_NO_SERVICE) ||
                (mResultCode == 0 && isAirplaneMode()) /* add fo radio off long sms */) {
            if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || LogTag.DEBUG_SEND) {
                Log.v(TAG, "handleSmsSent: no service, queuing message w/ uri: " + uri);
            }
            // We got an error with no service or no radio. Register for state changes so
            // when the status of the connection/radio changes, we can try to send the
            // queued up messages.
            registerForServiceStateChanges();
            // We couldn't send the message, put in the queue to retry later.
            Sms.moveMessageToFolder(this, uri, Sms.MESSAGE_TYPE_QUEUED, error);
            mToastHandler.post(new Runnable() {
                public void run() {
                    Toast.makeText(SmsReceiverService.this, getString(R.string.message_queued),
                            Toast.LENGTH_SHORT).show();
                }
            });
        } else if (mResultCode == SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE) {
            mToastHandler.post(new Runnable() {
                public void run() {
                    Toast.makeText(SmsReceiverService.this, getString(R.string.fdn_check_failure),
                            Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            messageFailedToSend(uri, error);
            // Current message send failed, need send next one.
            sendNextMessage(intent.getIntExtra(SUBSCRIPTION_KEY, 0));
        }
    }

    private void messageFailedToSend(Uri uri, int error) {
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || LogTag.DEBUG_SEND) {
            Log.v(TAG, "messageFailedToSend msg failed uri: " + uri + " error: " + error);
        }
        Sms.moveMessageToFolder(this, uri, Sms.MESSAGE_TYPE_FAILED, error);
        MessagingNotification.notifySendFailed(getApplicationContext(), true);
    }

    /**
     * Send the next message on the message queue.
     *
     * @param subscription Indicate send the message from which slot.
     */
    private void sendNextMessage(int subscription) {
        if (MessageUtils.isMultiSimEnabledMms()) {
            sendFirstQueuedMessage(subscription);
        } else {
            sendFirstQueuedMessage();
        }

    }

    private void handleSmsReceived(Intent intent, int error) {
        SmsMessage[] msgs = Intents.getMessagesFromIntent(intent);
        String format = intent.getStringExtra("format");
        int indexOnIcc = intent.getIntExtra("index_on_icc", -1); 
        Uri messageUri = insertMessage(this, msgs, error, format, indexOnIcc);
        SmsMessage sms = msgs[0];
        int subscription = sms.getSubId();

        //if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || LogTag.DEBUG_SEND) 
            {
            Log.v(TAG, "handleSmsReceived" + (sms.isReplace() ? "(replace)" : "") +
                    " messageUri: " + messageUri +
                    ", address: " + sms.getOriginatingAddress() +
                    ", body: " + sms.getMessageBody()/**/);
        }

        
        MessageUtils.checkIsPhoneMessageFull(this);
        
        if(MessageUtils.isIccCardFull(this, subscription))
        {
            Intent fullintent = new Intent(Intents.SIM_FULL_ACTION);
            this.sendBroadcast(fullintent, "android.permission.RECEIVE_SMS");
            Log.d(TAG, "isIccCardFull : send broadcast of SIM_FULL_ACTION!");
        }
        
        if ((messageUri != null)&&(indexOnIcc<0)) {
            long threadId = MessagingNotification.getSmsThreadId(this, messageUri);
            // Called off of the UI thread so ok to block.
            Log.d(TAG, "handleSmsReceived messageUri: " + messageUri + " threadId: " + threadId);
            MessagingNotification.blockingUpdateNewMessageIndicator(this, threadId, false);
        } else if ((messageUri != null)&&(indexOnIcc>0)) {
            MessagingNotification.blockingUpdateNewMessageOnIccIndicator(this, subscription);
        }
        
    }

    private void handleBootCompleted() {
        // Some messages may get stuck in the outbox. At this point, they're probably irrelevant
        // to the user, so mark them as failed and notify the user, who can then decide whether to
        // resend them manually.
        int numMoved = moveOutboxMessagesToFailedBox();
        if (numMoved > 0) {
            MessagingNotification.notifySendFailed(getApplicationContext(), true);
        }

        // Send any queued messages that were waiting from before the reboot.
        if (MessageUtils.isMultiSimEnabledMms()) {
            sendFirstQueuedMessage(MSimConstants.SUB1);
            sendFirstQueuedMessage(MSimConstants.SUB2);
        } else {
            sendFirstQueuedMessage();
        }

        // Called off of the UI thread so ok to block.
        MessagingNotification.blockingUpdateNewMessageIndicator(
            this, MessagingNotification.THREAD_ALL, false);

        if(MessageUtils.isMultiSimEnabledMms())
        {
            handleIccAbsent(MessageUtils.SUB1);
            handleIccAbsent(MessageUtils.SUB2);
            MessageUtils.checkModifyPreStoreWhenBoot(this,MessageUtils.SUB1);
            MessageUtils.checkModifyPreStoreWhenBoot(this,MessageUtils.SUB2);
        }
        else
        {
            handleIccAbsent(MessageUtils.SUB_INVALID);
            MessageUtils.checkModifyPreStoreWhenBoot(this);
        }
        
    }

    private void handleIccAbsent(int subscription) 
    {    
        Log.d(TAG, "handleIccAbsent : subscription = " + subscription);
        Uri iccUri = MessageUtils.getIccUriBySubscription(subscription);          
        int tokenId = TOKEN_DELETE_ICC;
        if(MessageUtils.isMultiSimEnabledMms())
        {
            tokenId = subscription == MessageUtils.SUB1 ? TOKEN_DELETE_ICC1: TOKEN_DELETE_ICC2;
        }
        
        try 
        {
            mQueryHandler.startDelete(tokenId, null, iccUri, null, null);
        }
        catch (SQLiteException e) 
        {
            SqliteWrapper.checkSQLiteException(this, e);
        }  
        
        ContentResolver resolver = getContentResolver();        
        resolver.notifyChange(iccUri, null);
    }

    /**
     * Move all messages that are in the outbox to the failed state and set them to unread.
     * @return The number of messages that were actually moved
     */
    private int moveOutboxMessagesToFailedBox() {
        ContentValues values = new ContentValues(3);

        values.put(Sms.TYPE, Sms.MESSAGE_TYPE_FAILED);
        values.put(Sms.ERROR_CODE, SmsManager.RESULT_ERROR_GENERIC_FAILURE);
        values.put(Sms.READ, Integer.valueOf(0));

        int messageCount = SqliteWrapper.update(
                getApplicationContext(), getContentResolver(), Outbox.CONTENT_URI,
                values, "type = " + Sms.MESSAGE_TYPE_OUTBOX, null);
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || LogTag.DEBUG_SEND) {
            Log.v(TAG, "moveOutboxMessagesToFailedBox messageCount: " + messageCount);
        }
        return messageCount;
    }

    public static final String CLASS_ZERO_BODY_KEY = "CLASS_ZERO_BODY";

    // This must match the column IDs below.
    private final static String[] REPLACE_PROJECTION = new String[] {
        Sms._ID,
        Sms.ADDRESS,
        Sms.PROTOCOL
    };

    // This must match REPLACE_PROJECTION.
    private static final int REPLACE_COLUMN_ID = 0;

    /**
     * If the message is a class-zero message, display it immediately
     * and return null.  Otherwise, store it using the
     * <code>ContentResolver</code> and return the
     * <code>Uri</code> of the thread containing this message
     * so that we can use it for notification.
     */
    private Uri insertMessage(Context context, SmsMessage[] msgs, int error, String format, int indexOnIcc) {
        // Build the helper classes to parse the messages.
        SmsMessage sms = msgs[0];

        Log.d(TAG,"insertMessage() format is " + format + ", indexOnIcc = "+indexOnIcc);

        if (sms != null && sms.getMessageClass() == SmsMessage.MessageClass.CLASS_0 && indexOnIcc < 0) {
            displayClassZeroMessage(context, sms, format);
            return null;
        }
        else if (sms != null && sms.isReplace() && indexOnIcc < 0) {
            return replaceMessage(context, msgs, error, indexOnIcc);
        }
        else {
            return storeMessage(context, msgs, error, indexOnIcc);
        }
    }

    /**
     * This method is used if this is a "replace short message" SMS.
     * We find any existing message that matches the incoming
     * message's originating address and protocol identifier.  If
     * there is one, we replace its fields with those of the new
     * message.  Otherwise, we store the new message as usual.
     *
     * See TS 23.040 9.2.3.9.
     */
    private Uri replaceMessage(Context context, SmsMessage[] msgs, int error, int indexOnIcc) {
        SmsMessage sms = msgs[0];
        ContentValues values = extractContentValues(sms);
        values.put(Sms.ERROR_CODE, error);
        int pduCount = msgs.length;

        if (pduCount == 1) {
            // There is only one part, so grab the body directly.
            values.put(Inbox.BODY, replaceFormFeeds(sms.getDisplayMessageBody()));
        } else {
            // Build up the body from the parts.
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < pduCount; i++) {
                sms = msgs[i];
                if (sms.mWrappedSmsMessage != null) {
                    body.append(sms.getDisplayMessageBody());
                }
            }
            values.put(Inbox.BODY, replaceFormFeeds(body.toString()));
        }

        ContentResolver resolver = context.getContentResolver();
        String originatingAddress = sms.getOriginatingAddress();
        int protocolIdentifier = sms.getProtocolIdentifier();
        String selection;
        String[] selectionArgs;

        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || LogTag.DEBUG_SEND) {
            Log.v(TAG, " SmsReceiverService: replaceMessage:");
        }
        selection = Sms.ADDRESS + " = ? AND " +
                    Sms.PROTOCOL + " = ? AND " +
                    Sms.SUB_ID +  " = ? ";
        selectionArgs = new String[] {
                originatingAddress, Integer.toString(protocolIdentifier),
                Integer.toString(MessageUtils.isMultiSimEnabledMms() ? sms.getSubId() : MessageUtils.SUB_INVALID)
            };

        Cursor cursor = SqliteWrapper.query(context, resolver, Inbox.CONTENT_URI,
                            REPLACE_PROJECTION, selection, selectionArgs, null);

        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    long messageId = cursor.getLong(REPLACE_COLUMN_ID);
                    Uri messageUri = ContentUris.withAppendedId(
                            Sms.CONTENT_URI, messageId);

                    SqliteWrapper.update(context, resolver, messageUri,
                                        values, null, null);
                    return messageUri;
                }
            } finally {
                cursor.close();
            }
        }
        return storeMessage(context, msgs, error, indexOnIcc);
    }

    public static String replaceFormFeeds(String s) {
        // Some providers send formfeeds in their messages. Convert those formfeeds to newlines.
        return s == null ? "" : s.replace('\f', '\n');
    }

//    private static int count = 0;

    private Uri storeMessage(Context context, SmsMessage[] msgs, int error, int indexOnIcc) {
        SmsMessage sms = msgs[0];

      if (indexOnIcc > -1) {
          return storeMessageToIcc(indexOnIcc, context, sms);
      }

        // Store the message in the content provider.
        ContentValues values = extractContentValues(sms);
        values.put(Sms.ERROR_CODE, error);
        values.put(Sms.SUB_ID, MessageUtils.isMultiSimEnabledMms() ? sms.getSubId() : MessageUtils.SUB_INVALID);

        int pduCount = msgs.length;

        if (pduCount == 1) {
            // There is only one part, so grab the body directly.
            values.put(Inbox.BODY, replaceFormFeeds(sms.getDisplayMessageBody()));
        } else {
            // Build up the body from the parts.
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < pduCount; i++) {
                sms = msgs[i];
                if (sms.mWrappedSmsMessage != null) {
                    body.append(sms.getDisplayMessageBody());
                }
            }
            values.put(Inbox.BODY, replaceFormFeeds(body.toString()));
        }

        // Make sure we've got a thread id so after the insert we'll be able to delete
        // excess messages.
        Long threadId = values.getAsLong(Sms.THREAD_ID);
        String address = values.getAsString(Sms.ADDRESS);

        // Code for debugging and easy injection of short codes, non email addresses, etc.
        // See Contact.isAlphaNumber() for further comments and results.
//        switch (count++ % 8) {
//            case 0: address = "AB12"; break;
//            case 1: address = "12"; break;
//            case 2: address = "Jello123"; break;
//            case 3: address = "T-Mobile"; break;
//            case 4: address = "Mobile1"; break;
//            case 5: address = "Dogs77"; break;
//            case 6: address = "****1"; break;
//            case 7: address = "#4#5#6#"; break;
//        }

        if (!TextUtils.isEmpty(address)) {
            Contact cacheContact = Contact.get(address,true);
            if (cacheContact != null) {
                address = cacheContact.getNumber();
            }
        } else {
            address = getString(R.string.unknown_sender);
            values.put(Sms.ADDRESS, address);
        }

        if (((threadId == null) || (threadId == 0)) && (address != null)) {
            threadId = Conversation.getOrCreateThreadId(context, address);
            values.put(Sms.THREAD_ID, threadId);
        }

        ContentResolver resolver = context.getContentResolver();

        Uri insertedUri = SqliteWrapper.insert(context, resolver, Inbox.CONTENT_URI, values);

        // Now make sure we're not over the limit in stored messages
        Recycler.getSmsRecycler().deleteOldMessagesByThreadId(context, threadId);
        MmsWidgetProvider.notifyDatasetChanged(context);

        return insertedUri;
    }

    private Uri storeMessageToIcc(int index, Context context, SmsMessage sms)
    {    
       Log.d(TAG,"storeMessageToIcc() index = " + index); 
       int subId = MessageUtils.SUB_INVALID;
        if (index < 0 || sms == null){
            return null;
        }
        int statusOnIcc = SmsManager.STATUS_ON_ICC_UNREAD;
        Uri uriStr = MessageUtils.ICC_URI;
        if (MessageUtils.isMultiSimEnabledMms()){
            subId = sms.getSubId();
            if (MSimConstants.SUB2 == subId){
                uriStr = MessageUtils.ICC2_URI;
            } else {
                uriStr = MessageUtils.ICC1_URI;
                }
        }

        Uri iccMessageUri = ContentUris.withAppendedId(uriStr, index);
        Log.d(TAG, "storeMessageToIcc : iccMessageUri = " + iccMessageUri);
        String address = sms.getDisplayOriginatingAddress();
        ContentValues values = new ContentValues(16);
        values.put("service_center_address", sms.getServiceCenterAddress());
        values.put(Sms.ADDRESS, address);
        values.put("message_class", String.valueOf(sms.getMessageClass()));        
        String content = sms.getDisplayMessageBody();
        if (content == null){
            content = "";
        }
        
        values.put(Sms.BODY, content);
        values.put(Sms.DATE, sms.getTimestampMillis());
        values.put(Sms.STATUS, Sms.STATUS_NONE);
        values.put("index_on_icc", index);                
        values.put("is_status_report", -1);        
        values.put("transport_type", "sms");
        values.put(Sms.TYPE, Sms.MESSAGE_TYPE_INBOX);
        values.put("status_on_icc", statusOnIcc);
        values.put(Sms.SUB_ID, subId);  
        //values.put(Sms.READ, MessageUtils.MESSAGE_UNREAD);
        
        /*if (!TextUtils.isEmpty(address)){
            values.put(Sms.THREAD_ID, 
                Conversation.getOrCreateThreadId(context, address));
        }*/

        ContentResolver resolver = context.getContentResolver();        
        return SqliteWrapper.insert(context, resolver, iccMessageUri, values);
    }

    /**
     * Extract all the content values except the body from an SMS
     * message.
     */
    private ContentValues extractContentValues(SmsMessage sms) {
        // Store the message in the content provider.
        ContentValues values = new ContentValues();

        values.put(Inbox.ADDRESS, sms.getDisplayOriginatingAddress());

        // Use now for the timestamp to avoid confusion with clock
        // drift between the handset and the SMSC.
        // Check to make sure the system is giving us a non-bogus time.
        Calendar buildDate = new GregorianCalendar(2011, 8, 18);    // 18 Sep 2011
        Calendar nowDate = new GregorianCalendar();
        long now = System.currentTimeMillis();
        nowDate.setTimeInMillis(now);

        if (nowDate.before(buildDate)) {
            // It looks like our system clock isn't set yet because the current time right now
            // is before an arbitrary time we made this build. Instead of inserting a bogus
            // receive time in this case, use the timestamp of when the message was sent.
            now = sms.getTimestampMillis();
        }

        values.put(Inbox.DATE, new Long(now));
        values.put(Inbox.DATE_SENT, Long.valueOf(sms.getTimestampMillis()));
        values.put(Inbox.PROTOCOL, sms.getProtocolIdentifier());
        values.put(Inbox.READ, 0);
        values.put(Inbox.SEEN, 0);
        if (sms.getPseudoSubject().length() > 0) {
            values.put(Inbox.SUBJECT, sms.getPseudoSubject());
        }
        values.put(Inbox.REPLY_PATH_PRESENT, sms.isReplyPathPresent() ? 1 : 0);
        values.put(Inbox.SERVICE_CENTER, sms.getServiceCenterAddress());
        return values;
    }

    /**
     * Displays a class-zero message immediately in a pop-up window
     * with the number from where it received the Notification with
     * the body of the message
     *
     */
    private void displayClassZeroMessage(Context context, SmsMessage sms, String format) {
        // Using NEW_TASK here is necessary because we're calling
        // startActivity from outside an activity.
        Intent smsDialogIntent = new Intent(context, ClassZeroActivity.class)
                .putExtra("pdu", sms.getPdu())
                .putExtra("format", format)
                .putExtra(MSimConstants.SUBSCRIPTION_KEY, sms.getSubId())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                          | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);

        context.startActivity(smsDialogIntent);
    }

    private void registerForServiceStateChanges() {
        Context context = getApplicationContext();
        unRegisterForServiceStateChanges();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || LogTag.DEBUG_SEND) {
            Log.v(TAG, "registerForServiceStateChanges");
        }

        context.registerReceiver(SmsReceiver.getInstance(), intentFilter);
    }

    private void unRegisterForServiceStateChanges() {
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || LogTag.DEBUG_SEND) {
            Log.v(TAG, "unRegisterForServiceStateChanges");
        }
        try {
            Context context = getApplicationContext();
            context.unregisterReceiver(SmsReceiver.getInstance());
        } catch (IllegalArgumentException e) {
            // Allow un-matched register-unregister calls
        }
    }
    
    private boolean isAirplaneMode() {
        int isAirplaneMode = Settings.System.getInt(getApplicationContext().getContentResolver(),
               Settings.Global.AIRPLANE_MODE_ON, 0) ;
        Log.v(TAG, "isAirplaneMode = " + isAirplaneMode);
        return (isAirplaneMode == 1) ? true : false;
    }

}


