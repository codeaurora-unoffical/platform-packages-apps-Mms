/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 *
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

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.database.sqlite.SqliteWrapper;
import android.graphics.drawable.Drawable;
import android.media.CamcorderProfile;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.StatFs;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Threads;
import android.telephony.MSimTelephonyManager;
import android.telephony.MSimSmsManager;
import android.telephony.TelephonyManager;
import android.telephony.MSimTelephonyManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.os.AsyncTask;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.text.style.URLSpan;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import com.android.internal.telephony.MSimConstants;
import com.android.mms.LogTag;
import com.android.mms.MmsApp;
import com.android.mms.MmsConfig;
import com.android.mms.R;
import com.android.mms.TempFileProvider;
import com.android.mms.data.WorkingMessage;
import com.android.mms.model.MediaModel;
import com.android.mms.model.SlideModel;
import com.android.mms.model.SlideshowModel;
import com.android.mms.model.VcardModel;
import com.android.mms.transaction.MessagingNotification;
import com.android.mms.transaction.MmsMessageSender;
import com.android.mms.util.AddressUtils;
import com.google.android.mms.ContentType;
import com.google.android.mms.MmsException;
import com.google.android.mms.pdu.CharacterSets;
import com.google.android.mms.pdu.EncodedStringValue;
import com.google.android.mms.pdu.MultimediaMessagePdu;
import com.google.android.mms.pdu.NotificationInd;
import com.google.android.mms.pdu.PduBody;
import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.pdu.PduPart;
import com.google.android.mms.pdu.PduPersister;
import com.google.android.mms.pdu.RetrieveConf;
import com.google.android.mms.pdu.SendReq;

/**
 * An utility class for managing messages.
 */
public class MessageUtils {
    interface ResizeImageResultCallback {
        void onResizeResult(PduPart part, boolean append);
    }
    private static final int TIMESTAMP_LENGTH = 7;  // See TS 23.040 9.2.3.11
    private static final int SELECT_SYSTEM = 0;
    private static final int SELECT_EXTERNAL = 1;

    // add the defination of subscription
    public static final int SUB_INVALID = -1;  //  for single card product
    public static final int SUB1 = 0;  // for DSDS product of slot one
    public static final int SUB2 = 1;  // for DSDS product of slot two
    public static final String SUB_KEY  = MSimConstants.SUBSCRIPTION_KEY; // subscription
    // add manage mode of multi select action
    public static final int INVALID_MODE= -1;
    public static final int FORWARD_MODE = 0;
    public static final int SIM_MESSAGE_MODE = 1;
    // add for getting the read status when copy messages to sim card
    public static final int MESSAGE_READ = 1;
    public static final int MESSAGE_SEEN = 1;
    // Received and read (TS 51.011 10.5.3)
    static public final int STATUS_ON_SIM_READ = 1;
    // add for obtaining icc uri when copying messages to card
    public static final Uri ICC_URI = Uri.parse("content://sms/icc");
    public static final Uri ICC1_URI = Uri.parse("content://sms/icc1");
    public static final Uri ICC2_URI = Uri.parse("content://sms/icc2");
    // add for getting result whether icc card is full or will full when copying to card
    public static final String COPY_SUCCESS_FULL = "content://sms/sim/full/success";
    public static final String COPY_FAILURE_FULL = "content://sms/sim/full/failure";

    private static final String TAG = LogTag.TAG;
    private static String sLocalNumber;
    private static String[] sNoSubjectStrings;

    private static final String VIEW_MODE_NAME = "current_view";
    // Ext action define as TelephonyIntents.ACTION_SIM_STATE_CHANGED + subID
    public static final String ACTION_SIM_STATE_CHANGED0 =
           "android.intent.action.SIM_STATE_CHANGED0";
    public static final String ACTION_SIM_STATE_CHANGED1 =
           "android.intent.action.SIM_STATE_CHANGED1";

    // distinguish view vcard from mms but not from contacts.
    public static final String VIEW_VCARD = "VIEW_VCARD_FROM_MMS";
    // add for obtain mms data path
    private static final String MMS_DATA_DATA_DIR = "/data/data";
    private static final String MMS_DATA_DIR = "/data/phonedata";
    // the remaining space , format as MB
    public static final int MIN_AVAILABLE_SPACE_MMS = 2;

    // add for query message count from iccsms table
    public static final Uri ICC_SMS_URI = Uri.parse("content://sms/iccsms");

    // Cache of both groups of space-separated ids to their full
    // comma-separated display names, as well as individual ids to
    // display names.
    // TODO: is it possible for canonical address ID keys to be
    // re-used?  SQLite does reuse IDs on NULL id_ insert, but does
    // anything ever delete from the mmssms.db canonical_addresses
    // table?  Nothing that I could find.
    private static final Map<String, String> sRecipientAddress =
            new ConcurrentHashMap<String, String>(20 /* initial capacity */);

    // When we pass a video record duration to the video recorder, use one of these values.
    private static final int[] sVideoDuration =
            new int[] {0, 5, 10, 15, 20, 30, 40, 50, 60, 90, 120};

    /**
     * MMS address parsing data structures
     */
    // allowable phone number separators
    private static final char[] NUMERIC_CHARS_SUGAR = {
        '-', '.', ',', '(', ')', ' ', '/', '\\', '*', '#', '+'
    };

    // Dialog item options for number
    private static final int DIALOG_ITEM_CALL         = 0;
    private static final int DIALOG_ITEM_SMS          = 1;
    private static final int DIALOG_ITEM_ADD_CONTACTS = 2;
    private static final int DIALOG_ITEM_VIDEOCALL    = 3;

    private static HashMap numericSugarMap = new HashMap (NUMERIC_CHARS_SUGAR.length);
    //for showing memory status dialog.
    private static AlertDialog memoryStatusDialog = null;

    public static String WAPPUSH = "Browser Information"; // Wap push key
    public static int WAP_PUSH_ADDRESS_INDEX =
            SystemProperties.getInt("persist.env.c.mms.addressindex", 1);

    public static final int ALL_RECIPIENTS_VALID   = 0;
    public static final int ALL_RECIPIENTS_INVALID = -1;
    // Indentify RECIPIENT editText is empty
    public static final int ALL_RECIPIENTS_EMPTY   = -2;

    public static boolean sIsIccLoaded  = false;

    // add for different search mode in SearchActivityExtend
    public static final int SEARCH_MODE_CONTENT = 0;
    public static final int SEARCH_MODE_NAME    = 1;
    public static final int SEARCH_MODE_NUMBER  = 2;
    // add for different match mode in classify search
    public static final int MATCH_BY_ADDRESS = 0;
    public static final int MATCH_BY_THREAD_ID = 1;

    public static final int STORE_TO_PHONE = 1;
    public static final int STORE_TO_ICC = 2;
    public static final int CARD_SUB1 = MSimConstants.SUB1;
    public static final int CARD_SUB2 = MSimConstants.SUB2;

    // Save the thread id for same recipient forward mms
    public static ArrayList<Long> sSameRecipientList = new ArrayList<Long>();

    // the max short message count
    public static int MAX_SMS_MESSAGE_COUNT =
            SystemProperties.getInt("persist.env.c.mms.maxcount", -1);
    // add for obtaining all short message count
    public static final Uri MAILBOX_SMS_MESSAGES_COUNT =
            Uri.parse("content://mms-sms/messagescount");

    // If set the special property, enable mms data even if mobile data is turned off.
    public static final boolean CAN_SETUP_MMS_DATA =
            SystemProperties.getBoolean("persist.env.mms.setupmmsdata", false);
    // support batch delete in message list view.
    public static final boolean SUPPORT_BATCH_DELETE = SystemProperties
            .getBoolean("persist.env.mms.batchdelete", true);

    private static final String[] WEB_SCHEMA =
                        new String[] { "http://", "https://", "rtsp://" };

    static {
        for (int i = 0; i < NUMERIC_CHARS_SUGAR.length; i++) {
            numericSugarMap.put(NUMERIC_CHARS_SUGAR[i], NUMERIC_CHARS_SUGAR[i]);
        }
    }


    private MessageUtils() {
        // Forbidden being instantiated.
    }

    /**
     * cleanseMmsSubject will take a subject that's says, "<Subject: no subject>", and return
     * a null string. Otherwise it will return the original subject string.
     * @param context a regular context so the function can grab string resources
     * @param subject the raw subject
     * @return
     */
    public static String cleanseMmsSubject(Context context, String subject) {
        if (TextUtils.isEmpty(subject)) {
            return subject;
        }
        if (sNoSubjectStrings == null) {
            sNoSubjectStrings =
                    context.getResources().getStringArray(R.array.empty_subject_strings);

        }
        final int len = sNoSubjectStrings.length;
        for (int i = 0; i < len; i++) {
            if (subject.equalsIgnoreCase(sNoSubjectStrings[i])) {
                return null;
            }
        }
        return subject;
    }

    public static String getMessageDetails(Context context, Cursor cursor, int size) {
        if (cursor == null) {
            return null;
        }

        if ("mms".equals(cursor.getString(MessageListAdapter.COLUMN_MSG_TYPE))) {
            int type = cursor.getInt(MessageListAdapter.COLUMN_MMS_MESSAGE_TYPE);
            switch (type) {
                case PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND:
                    return getNotificationIndDetails(context, cursor);
                case PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF:
                case PduHeaders.MESSAGE_TYPE_SEND_REQ:
                    return getMultimediaMessageDetails(context, cursor, size);
                default:
                    Log.w(TAG, "No details could be retrieved.");
                    return "";
            }
        } else {
            return getTextMessageDetails(context, cursor);
        }
    }

    private static String getNotificationIndDetails(Context context, Cursor cursor) {
        StringBuilder details = new StringBuilder();
        Resources res = context.getResources();

        long id = cursor.getLong(MessageListAdapter.COLUMN_ID);
        Uri uri = ContentUris.withAppendedId(Mms.CONTENT_URI, id);
        NotificationInd nInd;

        try {
            nInd = (NotificationInd) PduPersister.getPduPersister(
                    context).load(uri);
        } catch (MmsException e) {
            Log.e(TAG, "Failed to load the message: " + uri, e);
            return context.getResources().getString(R.string.cannot_get_details);
        }

        // Message Type: Mms Notification.
        details.append(res.getString(R.string.message_type_label));
        details.append(res.getString(R.string.multimedia_notification));

        // From: ***
        String from = extractEncStr(context, nInd.getFrom());
        details.append('\n');
        details.append(res.getString(R.string.from_label));
        details.append(!TextUtils.isEmpty(from)? from:
                                 res.getString(R.string.hidden_sender_address));

        // Date: ***
        details.append('\n');
        details.append(res.getString(
                                R.string.expire_on,
                                MessageUtils.formatTimeStampString(
                                        context, nInd.getExpiry() * 1000L, true)));

        // Subject: ***
        details.append('\n');
        details.append(res.getString(R.string.subject_label));

        EncodedStringValue subject = nInd.getSubject();
        if (subject != null) {
            details.append(subject.getString());
        }

        // Message class: Personal/Advertisement/Infomational/Auto
        details.append('\n');
        details.append(res.getString(R.string.message_class_label));
        details.append(new String(nInd.getMessageClass()));

        // Message size: *** KB
        details.append('\n');
        details.append(res.getString(R.string.message_size_label));
        details.append(String.valueOf((nInd.getMessageSize() + 1023) / 1024));
        details.append(context.getString(R.string.kilobyte));

        return details.toString();
    }

    private static String getMultimediaMessageDetails(
            Context context, Cursor cursor, int size) {
        int type = cursor.getInt(MessageListAdapter.COLUMN_MMS_MESSAGE_TYPE);
        if (type == PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND) {
            return getNotificationIndDetails(context, cursor);
        }

        StringBuilder details = new StringBuilder();
        Resources res = context.getResources();

        long id = cursor.getLong(MessageListAdapter.COLUMN_ID);
        Uri uri = ContentUris.withAppendedId(Mms.CONTENT_URI, id);
        MultimediaMessagePdu msg;

        try {
            msg = (MultimediaMessagePdu) PduPersister.getPduPersister(
                    context).load(uri);
        } catch (MmsException e) {
            Log.e(TAG, "Failed to load the message: " + uri, e);
            return context.getResources().getString(R.string.cannot_get_details);
        }

        // Message Type: Text message.
        details.append(res.getString(R.string.message_type_label));
        details.append(res.getString(R.string.multimedia_message));

        if (msg instanceof RetrieveConf) {
            // From: ***
            String from = extractEncStr(context, ((RetrieveConf) msg).getFrom());
            details.append('\n');
            details.append(res.getString(R.string.from_label));
            details.append(!TextUtils.isEmpty(from)? from:
                                  res.getString(R.string.hidden_sender_address));
        }

        // To: ***
        details.append('\n');
        details.append(res.getString(R.string.to_address_label));
        EncodedStringValue[] to = msg.getTo();
        if (to != null) {
            details.append(EncodedStringValue.concat(to));
        }
        else {
            Log.w(TAG, "recipient list is empty!");
        }


        // Bcc: ***
        if (msg instanceof SendReq) {
            EncodedStringValue[] values = ((SendReq) msg).getBcc();
            if ((values != null) && (values.length > 0)) {
                details.append('\n');
                details.append(res.getString(R.string.bcc_label));
                details.append(EncodedStringValue.concat(values));
            }
        }

        // Date: ***
        details.append('\n');
        int msgBox = cursor.getInt(MessageListAdapter.COLUMN_MMS_MESSAGE_BOX);
        if (msgBox == Mms.MESSAGE_BOX_DRAFTS) {
            details.append(res.getString(R.string.saved_label));
        } else if (msgBox == Mms.MESSAGE_BOX_INBOX) {
            details.append(res.getString(R.string.received_label));
        } else {
            details.append(res.getString(R.string.sent_label));
        }

        details.append(MessageUtils.formatTimeStampString(
                context, msg.getDate() * 1000L, true));

        // Subject: ***
        details.append('\n');
        details.append(res.getString(R.string.subject_label));

        EncodedStringValue subject = msg.getSubject();
        if (subject != null) {
            String subStr = subject.getString();
            // Message size should include size of subject.
            size += subStr.length();
            details.append(subStr);
        }

        // Priority: High/Normal/Low
        details.append('\n');
        details.append(res.getString(R.string.priority_label));
        details.append(getPriorityDescription(context, msg.getPriority()));

        // Message size: *** KB
        details.append('\n');
        details.append(res.getString(R.string.message_size_label));
        details.append((size - 1)/1024 + 1);
        details.append(" KB");

        return details.toString();
    }

    private static String getTextMessageDetails(Context context, Cursor cursor) {
        Log.d(TAG, "getTextMessageDetails");

        StringBuilder details = new StringBuilder();
        Resources res = context.getResources();

        // Message Type: Text message.
        details.append(res.getString(R.string.message_type_label));
        details.append(res.getString(R.string.text_message));

        // Address: ***
        details.append('\n');
        int smsType = cursor.getInt(MessageListAdapter.COLUMN_SMS_TYPE);
        if (Sms.isOutgoingFolder(smsType)) {
            details.append(res.getString(R.string.to_address_label));
        } else {
            details.append(res.getString(R.string.from_label));
        }

        if (cursor.getString(MessageListAdapter.COLUMN_SMS_ADDRESS).contains(WAPPUSH)) {
            String[] mAddresses = cursor.getString(
                    MessageListAdapter.COLUMN_SMS_ADDRESS).split(":");
            details.append(mAddresses[MessageUtils.WAP_PUSH_ADDRESS_INDEX]);
        } else {
            details.append(cursor.getString(MessageListAdapter.COLUMN_SMS_ADDRESS));
        }

        // Sent: ***
        if (smsType == Sms.MESSAGE_TYPE_INBOX) {
            long date_sent = cursor.getLong(MessageListAdapter.COLUMN_SMS_DATE_SENT);
            if (date_sent > 0) {
                details.append('\n');
                details.append(res.getString(R.string.sent_label));
                details.append(MessageUtils.formatTimeStampString(context, date_sent, true));
            }
        }

        // Received: ***
        details.append('\n');
        if (smsType == Sms.MESSAGE_TYPE_DRAFT) {
            details.append(res.getString(R.string.saved_label));
        } else if (smsType == Sms.MESSAGE_TYPE_INBOX) {
            details.append(res.getString(R.string.received_label));
        } else {
            details.append(res.getString(R.string.sent_label));
        }

        long date = cursor.getLong(MessageListAdapter.COLUMN_SMS_DATE);
        details.append(MessageUtils.formatTimeStampString(context, date, true));

        // Delivered: ***
        if (smsType == Sms.MESSAGE_TYPE_SENT) {
            // For sent messages with delivery reports, we stick the delivery time in the
            // date_sent column (see MessageStatusReceiver).
            long dateDelivered = cursor.getLong(MessageListAdapter.COLUMN_SMS_DATE_SENT);
            if (dateDelivered > 0) {
                details.append('\n');
                details.append(res.getString(R.string.delivered_label));
                details.append(MessageUtils.formatTimeStampString(context, dateDelivered, true));
            }
        }

        // Error code: ***
        int errorCode = cursor.getInt(MessageListAdapter.COLUMN_SMS_ERROR_CODE);
        if (errorCode != 0) {
            details.append('\n')
                .append(res.getString(R.string.error_code_label))
                .append(errorCode);
        }

        return details.toString();
    }

    static private String getPriorityDescription(Context context, int PriorityValue) {
        Resources res = context.getResources();
        switch(PriorityValue) {
            case PduHeaders.PRIORITY_HIGH:
                return res.getString(R.string.priority_high);
            case PduHeaders.PRIORITY_LOW:
                return res.getString(R.string.priority_low);
            case PduHeaders.PRIORITY_NORMAL:
            default:
                return res.getString(R.string.priority_normal);
        }
    }

    public static int getAttachmentType(SlideshowModel model) {
        if (model == null) {
            return MessageItem.ATTACHMENT_TYPE_NOT_LOADED;
        }

        int numberOfSlides = model.size();
        if (numberOfSlides > 1) {
            return WorkingMessage.SLIDESHOW;
        } else if (numberOfSlides == 1) {
            // Only one slide in the slide-show.
            SlideModel slide = model.get(0);
            if (slide.hasVideo()) {
                return WorkingMessage.VIDEO;
            }

            if (slide.hasAudio() && slide.hasImage()) {
                return WorkingMessage.SLIDESHOW;
            }

            if (slide.hasAudio()) {
                return WorkingMessage.AUDIO;
            }

            if (slide.hasImage()) {
                return WorkingMessage.IMAGE;
            }

            if (slide.hasVcard()) {
                return WorkingMessage.VCARD;
            }

            if (slide.hasText()) {
                return WorkingMessage.TEXT;
            }
        }

        return MessageItem.ATTACHMENT_TYPE_NOT_LOADED;
    }

    public static String formatTimeStampString(Context context, long when) {
        return formatTimeStampString(context, when, false);
    }

    public static String formatTimeStampString(Context context, long when, boolean fullFormat) {
        Time then = new Time();
        then.set(when);
        Time now = new Time();
        now.setToNow();

        // Basic settings for formatDateTime() we want for all cases.
        int format_flags = DateUtils.FORMAT_NO_NOON_MIDNIGHT |
                           DateUtils.FORMAT_ABBREV_ALL |
                           DateUtils.FORMAT_CAP_AMPM;

        // If the message is from a different year, show the date and year.
        if (then.year != now.year) {
            format_flags |= DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_DATE;
        } else if (then.yearDay != now.yearDay) {
            // If it is from a different day than today, show only the date.
            format_flags |= DateUtils.FORMAT_SHOW_DATE;
        } else {
            // Otherwise, if the message is from today, show the time.
            format_flags |= DateUtils.FORMAT_SHOW_TIME;
        }

        // If the caller has asked for full details, make sure to show the date
        // and time no matter what we've determined above (but still make showing
        // the year only happen if it is a different year from today).
        if (fullFormat) {
            format_flags |= (DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);
        }

        return DateUtils.formatDateTime(context, when, format_flags);
    }

    public static void selectAudio(final Activity activity, final int requestCode) {
        // Compare other phone's behavior, we are not only display the
        // RingtonePick to add, we could have other choices like external audio
        // and system audio. Allow the user to select a particular kind of data
        // and return it.
        String[] items = new String[2];
        items[SELECT_SYSTEM] = activity.getString(R.string.system_audio_item);
        items[SELECT_EXTERNAL] = activity.getString(R.string.external_audio_item);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity,
                android.R.layout.simple_list_item_1, android.R.id.text1, items);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        AlertDialog dialog = builder.setTitle(activity.getString(R.string.select_audio))
                .setAdapter(adapter, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent audioIntent = null;
                        switch (which) {
                            case SELECT_SYSTEM:
                                audioIntent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
                                audioIntent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false);
                                audioIntent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
                                audioIntent.putExtra(RingtoneManager.EXTRA_RINGTONE_INCLUDE_DRM, false);
                                audioIntent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE,
                                        activity.getString(R.string.select_audio));
                                audioIntent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false);
                                break;
                            case SELECT_EXTERNAL:
                                audioIntent = new Intent();
                                audioIntent.setAction(Intent.ACTION_PICK);
                                audioIntent.setData(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI);
                                break;
                        }
                        activity.startActivityForResult(audioIntent, requestCode);
                    }
                })
                .create();
        dialog.show();
    }

    public static void recordSound(Activity activity, int requestCode, long sizeLimit) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(ContentType.AUDIO_AMR);
        intent.setClassName("com.android.soundrecorder",
                "com.android.soundrecorder.SoundRecorder");
        intent.putExtra(android.provider.MediaStore.Audio.Media.EXTRA_MAX_BYTES, sizeLimit);
        activity.startActivityForResult(intent, requestCode);
    }

    public static void recordVideo(Activity activity, int requestCode, long sizeLimit) {
        // The video recorder can sometimes return a file that's larger than the max we
        // say we can handle. Try to handle that overshoot by specifying an 85% limit.
        sizeLimit *= .85F;

        int durationLimit = getVideoCaptureDurationLimit(sizeLimit);

        if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
            log("recordVideo: durationLimit: " + durationLimit +
                    " sizeLimit: " + sizeLimit);
        }

        Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0);
        intent.putExtra("android.intent.extra.sizeLimit", sizeLimit);
        intent.putExtra("android.intent.extra.durationLimit", durationLimit);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, TempFileProvider.SCRAP_CONTENT_URI);
        try {
            activity.startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "startActivity failed", e);
        }
    }

    public static void capturePicture(Activity activity, int requestCode) {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, TempFileProvider.SCRAP_CONTENT_URI);
        activity.startActivityForResult(intent, requestCode);
    }

    // Public for until tests
    public static int getVideoCaptureDurationLimit(long bytesAvailable) {
        CamcorderProfile camcorder = CamcorderProfile.get(CamcorderProfile.QUALITY_LOW);
        if (camcorder == null) {
            return 0;
        }
        bytesAvailable *= 8;        // convert to bits
        long seconds = bytesAvailable / (camcorder.audioBitRate + camcorder.videoBitRate);

        // Find the best match for one of the fixed durations
        for (int i = sVideoDuration.length - 1; i >= 0; i--) {
            if (seconds >= sVideoDuration[i]) {
                return sVideoDuration[i];
            }
        }
        return 0;
    }

    public static void selectVideo(Context context, int requestCode) {
        selectMediaByType(context, requestCode, ContentType.VIDEO_UNSPECIFIED, true);
    }

    public static void selectImage(Context context, int requestCode) {
        selectMediaByType(context, requestCode, ContentType.IMAGE_UNSPECIFIED, false);
    }

    private static void selectMediaByType(
            Context context, int requestCode, String contentType, boolean localFilesOnly) {
         if (context instanceof Activity) {

            Intent innerIntent = new Intent(Intent.ACTION_GET_CONTENT);

            innerIntent.setType(contentType);
            if (localFilesOnly) {
                innerIntent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            }

            Intent wrapperIntent = Intent.createChooser(innerIntent, null);

            ((Activity) context).startActivityForResult(wrapperIntent, requestCode);
        }
    }

    public static void viewSimpleSlideshow(Context context, SlideshowModel slideshow) {
        if (!slideshow.isSimple()) {
            throw new IllegalArgumentException(
                    "viewSimpleSlideshow() called on a non-simple slideshow");
        }
        SlideModel slide = slideshow.get(0);
        MediaModel mm = null;
        if (slide.hasImage()) {
            mm = slide.getImage();
        } else if (slide.hasVideo()) {
            mm = slide.getVideo();
        } else if (slide.hasVcard()) {
            mm = slide.getVcard();
            String lookupUri = ((VcardModel) mm).getLookupUri();

            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (!TextUtils.isEmpty(lookupUri) && lookupUri.contains("contacts")) {
                // if the uri is from the contact, we suggest to view the contact.
                intent.setData(Uri.parse(lookupUri));
            } else {
                // we need open the saved part.
                intent.setDataAndType(mm.getUri(), ContentType.TEXT_VCARD.toLowerCase());
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            // distinguish view vcard from mms or contacts.
            intent.putExtra(VIEW_VCARD, true);
            context.startActivity(intent);
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra("SingleItemOnly", true); // So we don't see "surrounding" images in Gallery

        String contentType;
        contentType = mm.getContentType();
        intent.setDataAndType(mm.getUri(), contentType);
        context.startActivity(intent);
    }

    public static void showErrorDialog(Activity activity,
            String title, String message) {
        if (activity.isFinishing()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);

        builder.setIcon(R.drawable.ic_sms_mms_not_delivered);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(android.R.string.ok, new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    dialog.dismiss();
                }
            }
        });
        builder.show();
    }

    /**
     * The quality parameter which is used to compress JPEG images.
     */
    public static final int IMAGE_COMPRESSION_QUALITY = 95;
    /**
     * The minimum quality parameter which is used to compress JPEG images.
     */
    public static final int MINIMUM_IMAGE_COMPRESSION_QUALITY = 50;

    /**
     * Message overhead that reduces the maximum image byte size.
     * 5000 is a realistic overhead number that allows for user to also include
     * a small MIDI file or a couple pages of text along with the picture.
     */
    public static final int MESSAGE_OVERHEAD = 5000;

    public static void resizeImageAsync(final Context context,
            final Uri imageUri, final Handler handler,
            final ResizeImageResultCallback cb,
            final boolean append) {

        // Show a progress toast if the resize hasn't finished
        // within one second.
        // Stash the runnable for showing it away so we can cancel
        // it later if the resize completes ahead of the deadline.
        final Runnable showProgress = new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, R.string.compressing, Toast.LENGTH_SHORT).show();
            }
        };
        // Schedule it for one second from now.
        handler.postDelayed(showProgress, 1000);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final PduPart part;
                try {
                    UriImage image = new UriImage(context, imageUri);
                    int widthLimit = MmsConfig.getMaxImageWidth();
                    int heightLimit = MmsConfig.getMaxImageHeight();
                    // In mms_config.xml, the max width has always been declared larger than the max
                    // height. Swap the width and height limits if necessary so we scale the picture
                    // as little as possible.
                    if (image.getHeight() > image.getWidth()) {
                        int temp = widthLimit;
                        widthLimit = heightLimit;
                        heightLimit = temp;
                    }

                    part = image.getResizedImageAsPart(
                        widthLimit,
                        heightLimit,
                        MmsConfig.getMaxMessageSize() - MESSAGE_OVERHEAD);
                } finally {
                    // Cancel pending show of the progress toast if necessary.
                    handler.removeCallbacks(showProgress);
                }

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onResizeResult(part, append);
                    }
                });
            }
        }, "MessageUtils.resizeImageAsync").start();
    }

    public static void showDiscardDraftConfirmDialog(Context context,
            OnClickListener listener, int validNum) {

        int msgId = R.string.discard_message_reason;
        if (ALL_RECIPIENTS_EMPTY != validNum) {
            msgId = validNum > ALL_RECIPIENTS_VALID ? R.string.discard_message_reason_some_invalid
                : R.string.discard_message_reason_all_invalid;
        }

        // the alert icon shoud has black triangle and white exclamation mark in white background.
        new AlertDialog.Builder(context)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(R.string.discard_message)
                .setMessage(msgId)
                .setPositiveButton(R.string.yes, listener)
                .setNegativeButton(R.string.no, null)
                .show();
    }

    public static String getLocalNumber() {
        if (null == sLocalNumber) {
            sLocalNumber = MmsApp.getApplication().getTelephonyManager().getLine1Number();
        }
        return sLocalNumber;
    }

    public static boolean isLocalNumber(String number) {
        if (number == null) {
            return false;
        }

        // we don't use Mms.isEmailAddress() because it is too strict for comparing addresses like
        // "foo+caf_=6505551212=tmomail.net@gmail.com", which is the 'from' address from a forwarded email
        // message from Gmail. We don't want to treat "foo+caf_=6505551212=tmomail.net@gmail.com" and
        // "6505551212" to be the same.
        if (number.indexOf('@') >= 0) {
            return false;
        }

        return PhoneNumberUtils.compare(number, getLocalNumber());
    }

    public static void handleReadReport(final Context context,
            final Collection<Long> threadIds,
            final int status,
            final Runnable callback) {
        StringBuilder selectionBuilder = new StringBuilder(Mms.MESSAGE_TYPE + " = "
                + PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF
                + " AND " + Mms.READ + " = 0"
                + " AND " + Mms.READ_REPORT + " = " + PduHeaders.VALUE_YES);

        if (threadIds != null) {
            String threadIdSelection = null;
            StringBuilder buf = new StringBuilder();
            int i = 0;

            for (long threadId : threadIds) {
                if (i > 0) {
                    buf.append(",");
                }
                buf.append(threadId);
                i++;
            }
            threadIdSelection = buf.toString();

            selectionBuilder.append(" AND " + Mms.THREAD_ID + " in (" + threadIdSelection + ")");
        }

        final Cursor c = SqliteWrapper.query(context, context.getContentResolver(),
                        Mms.Inbox.CONTENT_URI, new String[] {Mms._ID, Mms.MESSAGE_ID},
                        selectionBuilder.toString(), null, null);

        if (c == null) {
            return;
        }

        final Map<String, String> map = new HashMap<String, String>();
        try {
            if (c.getCount() == 0) {
                if (callback != null) {
                    callback.run();
                }
                return;
            }

            while (c.moveToNext()) {
                Uri uri = ContentUris.withAppendedId(Mms.CONTENT_URI, c.getLong(0));
                map.put(c.getString(1), AddressUtils.getFrom(context, uri));
            }
        } finally {
            c.close();
        }

        OnClickListener positiveListener = new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                for (final Map.Entry<String, String> entry : map.entrySet()) {
                    MmsMessageSender.sendReadRec(context, entry.getValue(),
                                                 entry.getKey(), status);
                }

                if (callback != null) {
                    callback.run();
                }
                dialog.dismiss();
            }
        };

        OnClickListener negativeListener = new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (callback != null) {
                    callback.run();
                }
                dialog.dismiss();
            }
        };

        OnCancelListener cancelListener = new OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                if (callback != null) {
                    callback.run();
                }
                dialog.dismiss();
            }
        };

        confirmReadReportDialog(context, positiveListener,
                                         negativeListener,
                                         cancelListener);
    }

    private static void confirmReadReportDialog(Context context,
            OnClickListener positiveListener, OnClickListener negativeListener,
            OnCancelListener cancelListener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setCancelable(true);
        builder.setTitle(R.string.confirm);
        builder.setMessage(R.string.message_send_read_report);
        builder.setPositiveButton(R.string.yes, positiveListener);
        builder.setNegativeButton(R.string.no, negativeListener);
        builder.setOnCancelListener(cancelListener);
        builder.show();
    }

    public static String extractEncStrFromCursor(Cursor cursor,
            int columnRawBytes, int columnCharset) {
        String rawBytes = cursor.getString(columnRawBytes);
        int charset = cursor.getInt(columnCharset);

        if (TextUtils.isEmpty(rawBytes)) {
            return "";
        } else if (charset == CharacterSets.ANY_CHARSET) {
            return rawBytes;
        } else {
            return new EncodedStringValue(charset, PduPersister.getBytes(rawBytes)).getString();
        }
    }

    private static String extractEncStr(Context context, EncodedStringValue value) {
        if (value != null) {
            return value.getString();
        } else {
            return "";
        }
    }

    public static ArrayList<String> extractUris(URLSpan[] spans) {
        int size = spans.length;
        ArrayList<String> accumulator = new ArrayList<String>();

        for (int i = 0; i < size; i++) {
            accumulator.add(spans[i].getURL());
        }
        return accumulator;
    }

    public static String getRecipientsByIds(Context context,
            String recipientIds, boolean allowQuery) {
        String value = sRecipientAddress.get(recipientIds);
        if (value != null) {
            return value;
        }
        if (!TextUtils.isEmpty(recipientIds)) {
            StringBuilder addressBuf = extractIdsToAddresses(context, recipientIds, allowQuery);
            if (addressBuf == null) {
                // temporary error? Don't memoize.
                return "";
            }
            value = addressBuf.toString();
        } else {
            value = "";
        }
        sRecipientAddress.put(recipientIds, value);
        return value;
    }

    private static StringBuilder extractIdsToAddresses(Context context, String recipients,
            boolean allowQuery) {
        StringBuilder addressBuf = new StringBuilder();
        String[] recipientIds = recipients.split(" ");
        boolean firstItem = true;
        for (String recipientId : recipientIds) {
            String value = sRecipientAddress.get(recipientId);

            if (value == null) {
                if (!allowQuery) {
                    // when allowQuery is false, if any value from
                    // sRecipientAddress.get() is null,
                    // return null for the whole thing. We don't want to stick
                    // partial result
                    // into sRecipientAddress for multiple recipient ids.
                    return null;
                }

                Uri uri = Uri.parse("content://mms-sms/canonical-address/" + recipientId);
                Cursor c = SqliteWrapper.query(context, context.getContentResolver(), uri, null,
                        null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            value = c.getString(0);
                            sRecipientAddress.put(recipientId, value);
                        }
                    } finally {
                        c.close();
                    }
                }
            }
            if (value == null) {
                continue;
            }
            if (firstItem) {
                firstItem = false;
            } else {
                addressBuf.append(";");
            }
            addressBuf.append(value);
        }

        return (addressBuf.length() == 0) ? null : addressBuf;
    }

    public static String getAddressByThreadId(Context context, long threadId) {
        String[] projection = new String[] {
                Threads.RECIPIENT_IDS
        };

        Uri.Builder builder = Threads.CONTENT_URI.buildUpon();
        builder.appendQueryParameter("simple", "true");
        Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(), builder.build(),
                projection, Threads._ID + "=" + threadId, null, null);

        if (cursor != null) {
            try {
                if ((cursor.getCount() == 1) && cursor.moveToFirst()) {
                    String address = getRecipientsByIds(context, cursor.getString(0),
                            true /* allow query*/);
                    if (!TextUtils.isEmpty(address)) {
                        return address;
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * Play/view the message attachments.
     * TOOD: We need to save the draft before launching another activity to view the attachments.
     *       This is hacky though since we will do saveDraft twice and slow down the UI.
     *       We should pass the slideshow in intent extra to the view activity instead of
     *       asking it to read attachments from database.
     * @param activity
     * @param msgUri the MMS message URI in database
     * @param slideshow the slideshow to save
     * @param persister the PDU persister for updating the database
     * @param sendReq the SendReq for updating the database
     */
    public static void viewMmsMessageAttachment(Activity activity, Uri msgUri,
            SlideshowModel slideshow, AsyncDialog asyncDialog) {
        viewMmsMessageAttachment(activity, msgUri, slideshow, 0, asyncDialog);
    }

    public static void viewMmsMessageAttachment(final Activity activity, final Uri msgUri,
            final SlideshowModel slideshow, final int requestCode, AsyncDialog asyncDialog) {
        boolean isSimple = (slideshow == null) ? false : slideshow.isSimple();
        if (isSimple) {
            // In attachment-editor mode, we only ever have one slide.
            MessageUtils.viewSimpleSlideshow(activity, slideshow);
        } else {
            // The user wants to view the slideshow. We have to persist the slideshow parts
            // in a background task. If the task takes longer than a half second, a progress dialog
            // is displayed. Once the PDU persisting is done, another runnable on the UI thread get
            // executed to start the SlideshowActivity.
            asyncDialog.runAsync(new Runnable() {
                @Override
                public void run() {
                    // If a slideshow was provided, save it to disk first.
                    if (slideshow != null) {
                        PduPersister persister = PduPersister.getPduPersister(activity);
                        try {
                            PduBody pb = slideshow.toPduBody();
                            persister.updateParts(msgUri, pb, null);
                            slideshow.sync(pb);
                        } catch (MmsException e) {
                            Log.e(TAG, "Unable to save message for preview");
                            return;
                        }
                    }
                }
            }, new Runnable() {
                @Override
                public void run() {
                    // Once the above background thread is complete, this runnable is run
                    // on the UI thread to launch the slideshow activity.
                    launchSlideshowActivity(activity, msgUri, requestCode);
                }
            }, R.string.building_slideshow_title);
        }
    }

    public static void launchSlideshowActivity(Context context, Uri msgUri, int requestCode) {
        // Launch the slideshow activity to play/view.
        Intent intent = new Intent(context, SlideshowActivity.class);
        intent.setData(msgUri);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (requestCode > 0 && context instanceof Activity) {
            ((Activity)context).startActivityForResult(intent, requestCode);
        } else {
            context.startActivity(intent);
        }

    }

    /**
     * Debugging
     */
    public static void writeHprofDataToFile(){
        String filename = Environment.getExternalStorageDirectory() + "/mms_oom_hprof_data";
        try {
            android.os.Debug.dumpHprofData(filename);
            Log.i(TAG, "##### written hprof data to " + filename);
        } catch (IOException ex) {
            Log.e(TAG, "writeHprofDataToFile: caught " + ex);
        }
    }

    // An alias (or commonly called "nickname") is:
    // Nickname must begin with a letter.
    // Only letters a-z, numbers 0-9, or . are allowed in Nickname field.
    public static boolean isAlias(String string) {
        if (!MmsConfig.isAliasEnabled()) {
            return false;
        }

        int len = string == null ? 0 : string.length();

        if (len < MmsConfig.getAliasMinChars() || len > MmsConfig.getAliasMaxChars()) {
            return false;
        }

        if (!Character.isLetter(string.charAt(0))) {    // Nickname begins with a letter
            return false;
        }
        for (int i = 1; i < len; i++) {
            char c = string.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.')) {
                return false;
            }
        }

        return true;
    }

    /**
     * Given a phone number, return the string without syntactic sugar, meaning parens,
     * spaces, slashes, dots, dashes, etc. If the input string contains non-numeric
     * non-punctuation characters, return null.
     */
    private static String parsePhoneNumberForMms(String address) {
        StringBuilder builder = new StringBuilder();
        int len = address.length();

        for (int i = 0; i < len; i++) {
            char c = address.charAt(i);

            // accept the first '+' in the address
            if (c == '+' && builder.length() == 0) {
                builder.append(c);
                continue;
            }

            if (Character.isDigit(c)) {
                builder.append(c);
                continue;
            }

            if (numericSugarMap.get(c) == null) {
                return null;
            }
        }
        return builder.toString();
    }

    public static Intent getVTCallIntent(String number) {
        Intent intent = new Intent("com.borqs.videocall.action.LaunchVideoCallScreen");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        intent.putExtra("IsCallOrAnswer", true); // true as a call, while false as answer
        intent.putExtra("LaunchMode", 1); // 1 as telephony, while 0 as socket
        intent.putExtra("call_number_key", number);
        return intent;
    }

    public static boolean isVTSupported() {
        return SystemProperties.getBoolean(
                "persist.radio.csvt.enabled"
        /*TelephonyProperties.PROPERTY_CSVT_ENABLED*/, false);
    }

    /**
     * Returns true if the address passed in is a valid MMS address.
     */
    public static boolean isValidMmsAddress(String address) {
        String retVal = parseMmsAddress(address);
        return (retVal != null && !retVal.equals(""));
    }

    /**
     * Returns true if the address passed in is a Browser wap push MMS address.
     */
    public static boolean isWapPushNumber(String address) {
        if(address.contains(WAPPUSH)){
            return true;
        }
        return false;
   }

    /**
     * parse the input address to be a valid MMS address.
     * - if the address is an email address, leave it as is.
     * - if the address can be parsed into a valid MMS phone number, return the parsed number.
     * - if the address is a compliant alias address, leave it as is.
     */
    public static String parseMmsAddress(String address) {
        // if it's a valid Email address, use that.
        if (Mms.isEmailAddress(address)) {
            return address;
        }

        // if we are able to parse the address to a MMS compliant phone number, take that.
        String retVal = parsePhoneNumberForMms(address);
        if (retVal != null) {
            return retVal;
        }

        // if it's an alias compliant address, use that.
        if (isAlias(address)) {
            return address;
        }

        // it's not a valid MMS address, return null
        return null;
    }

    public static void dialRecipient(Context context, String address, int subscription) {
        if (!Mms.isEmailAddress(address)) {
            Intent dialIntent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + address));
            if (isMultiSimEnabledMms()) {
                dialIntent.putExtra(SUB_KEY, subscription);
            }
            context.startActivity(dialIntent);
        }
    }

    /**
     * Return whether it has card in according slot -the input subscription is 0
     * or 1 -It is only used in DSDS
     */
    public static boolean hasIccCard(int subscription) {
        boolean hasCard = false;
        if (isMultiSimEnabledMms()) {
            MSimTelephonyManager msimTelephonyManager = MSimTelephonyManager.getDefault();
            hasCard = msimTelephonyManager.hasIccCard(subscription);
        } else {
            TelephonyManager telephonyManager = TelephonyManager.getDefault();
            if (subscription == telephonyManager.getDefaultSubscription()) {
                hasCard = telephonyManager.hasIccCard();
            }
        }
        return hasCard;
    }

    /**
     * Return whether it has card no matter in DSDS or not
     */
    public static boolean hasIccCard() {
        return TelephonyManager.getDefault().hasIccCard();
    }

    private static void log(String msg) {
        Log.d(TAG, "[MsgUtils] " + msg);
    }

    public static boolean isMailboxMode() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(MmsApp
                .getApplication());
        boolean ViewMode = sp.getBoolean(VIEW_MODE_NAME, false);
        return ViewMode;
    }

    public static void setMailboxMode(boolean mode) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(MmsApp
                .getApplication());
        sp.edit().putBoolean(VIEW_MODE_NAME, mode).commit();
    }

    /**
     * Return the sim name of subscription.
     */
    public static String getMultiSimName(Context context, int subscription) {
        if (subscription >= MSimTelephonyManager.getDefault().getPhoneCount() || subscription < 0) {
            return null;
        }
        String multiSimName = Settings.System.getString(context.getContentResolver(),
                Settings.System.MULTI_SIM_NAME[subscription]);
        if (multiSimName == null) {
            if (subscription == MSimConstants.SUB1) {
                return context.getString(R.string.slot1);
            } else if (subscription == MSimConstants.SUB2) {
                return context.getString(R.string.slot2);
            }
        }
        return multiSimName;
    }

    // TODO: should be better according to TS 24.008 10.5.4.7
    public static boolean isValidSimAddress(String address) {
        return PhoneNumberUtils.networkPortionToCalledPartyBCD(address) != null;
    }

    /**
     * Return the subscription id of special message.
     */
    public static int getSubIdFromMsgId(Context context, String msgId) {
        Log.i(TAG,"getSubIdFromMsgId() : msgId="+msgId);
        int subId = 0;
        Cursor c = null;
        try {
            String selection = Mms._ID +"="+msgId;
            c = context.getContentResolver().query(Mms.CONTENT_URI, new String[] {Mms.SUB_ID},
                                                 selection, null, "date DESC");
            if (c != null && c.moveToFirst()) {
                subId = c.getInt(0);
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return subId;
    }

    public static String getAddressByName(Context context, String name) {
        String resultAddr = "";
        Cursor c = null;
        Uri nameUri = null;
        if (TextUtils.isEmpty(name)) {
            return resultAddr;
        }
        // Replace the ' to avoid SQL injection.
        name = name.replace("'","''");

        try {
            c = context.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                    new String[] {ContactsContract.Data.RAW_CONTACT_ID},
                    ContactsContract.Data.MIMETYPE + " =? AND " + StructuredName.DISPLAY_NAME
                    + " like '%" + name + "%' ", new String[] {StructuredName.CONTENT_ITEM_TYPE},
                    null);

            if (c == null) {
                return resultAddr;
            }

            int i = 0;
            StringBuilder sb = new StringBuilder();
            while (c.moveToNext()) {
                long raw_contact_id = c.getLong(0);
                if (i++ > 0) {
                    sb.append(",");
                }
                sb.append(queryPhoneNumbersWithRaw(context, raw_contact_id));
            }

            resultAddr = sb.toString();
        } finally {
            if (c != null) {
                c.close();
            }
        }

        return resultAddr;
    }

    private static String queryPhoneNumbersWithRaw(Context context, long rawContactId) {
        Cursor c = null;
        String addrs = "";
        try {
            c = context.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[] {Phone.NUMBER}, Phone.RAW_CONTACT_ID + " = " + rawContactId,
                    null, null);

            if (c != null && c.moveToFirst()) {
                int i = 0;
                while (!c.isAfterLast()) {
                    String addrValue = c.getString(0);
                    if (!TextUtils.isEmpty(addrValue)) {
                        if (i == 0) {
                            addrs = addrValue;
                        } else {
                            addrs = addrs + "," + addrValue;
                        }
                        i++;
                    }
                    c.moveToNext();
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return addrs;
    }

    /**
     * Return the activated card number
     */
    public static int getActivatedIccCardCount() {
        MSimTelephonyManager tm = MSimTelephonyManager.getDefault();
        int phoneCount = tm.getPhoneCount();
        Log.d(TAG, "isIccCardActivated phoneCount " + phoneCount);
        int count = 0;
        for (int i = 0; i < phoneCount; i++) {
            Log.d(TAG, "isIccCardActivated subscription " + tm.getSimState(i));
            // Because the status of slot1/2 will return SIM_STATE_UNKNOWN under airplane mode.
            // So we add check about SIM_STATE_UNKNOWN.
            if ((tm.getSimState(i) != TelephonyManager.SIM_STATE_ABSENT)
                    // (tm.getSimState(i) != TelephonyManager.SIM_STATE_DEACTIVATED)
                    && (tm.getSimState(i) != TelephonyManager.SIM_STATE_UNKNOWN)) {
                count++;
            }
        }
        return count;
   }

    /**
     * Decide whether the current product  is DSDS in MMS
     */
    public static boolean isMultiSimEnabledMms() {
        return MSimTelephonyManager.getDefault().isMultiSimEnabled();
    }

    /**
     * Return whether the card is activated according to Subscription
     * used for DSDS
     */
    public static boolean isIccCardActivated(int subscription) {
        MSimTelephonyManager tm = MSimTelephonyManager.getDefault();
        Log.d(TAG, "isIccCardActivated subscription " + tm.getSimState(subscription));
        return (tm.getSimState(subscription) != TelephonyManager.SIM_STATE_ABSENT)
                    //&& (tm.getSimState(subscription) != TelephonyManager.SIM_STATE_DEACTIVATED)
                    && (tm.getSimState(subscription) != TelephonyManager.SIM_STATE_UNKNOWN);
    }

    /**
     * Return the icc uri according to subscription
     */
    public static Uri getIccUriBySubscription(int subscription) {
        switch (subscription) {
            case MSimConstants.SUB1:
                return ICC1_URI;
            case MSimConstants.SUB2:
                return ICC2_URI;
            default:
                return ICC_URI;
        }
    }

    public static void setIsIccLoaded(boolean isIccLoaded) {
        sIsIccLoaded = isIccLoaded;
    }
    /**
     * @return the SIM icon for the special subscription.
     */
    public static Drawable getMultiSimIcon(Context context, int subscription) {
        if (context == null) {
            // If the context is null, return 0 as no resource found.
            return null;
        }

        TypedArray icons = context.getResources().obtainTypedArray(
                com.android.internal.R.array.sim_icons);
        String simIconIndex = Settings.System.getString(context.getContentResolver(),
                Settings.System.PREFERRED_SIM_ICON_INDEX);
        if (TextUtils.isEmpty(simIconIndex)) {
            return icons.getDrawable(subscription);
        } else {
            String[] indexs = simIconIndex.split(",");
            if (subscription >= indexs.length) {
                return null;
            }
            return icons.getDrawable(Integer.parseInt(indexs[subscription]));
        }
    }

    public static long getStoreUnused() {
        File path = new File(MMS_DATA_DATA_DIR);
        StatFs stat = new StatFs(path.getPath());
        long blockSize = stat.getBlockSize();
        long availableBlocks = stat.getAvailableBlocks();
        return availableBlocks * blockSize;
    }

    /* Used for judge whether have memory for save mms */
    public static boolean isMmsMemoryFull() {
        boolean isMemoryFull = isPhoneMemoryFull();
        if (isMemoryFull) {
            Log.d(TAG, "isMmsMemoryFull : isMemoryFull = " + isMemoryFull);
            return true;
        }
        return false;
    }

    private static String getMmsDataDir() {
        File data_file = new File(MMS_DATA_DIR);
        if (data_file.exists()) {
            return MMS_DATA_DIR;
        }
        return MMS_DATA_DATA_DIR;
    }

    public static long getMmsUsed(Context mContext) {
        long dbSize = 0;
        String dbPath = "/data/data/com.android.providers.telephony/databases/mmssms.db";
        File dfFile = new File(dbPath);
        dbSize = dfFile.length();
        int mmsCount = 0;
        int smsCount = 0;
        long mmsfileSize = 0;
        Uri MMS_URI = Uri.parse("content://mms");
        Uri SMS_URI = Uri.parse("content://sms");
        Cursor cursor = SqliteWrapper.query(mContext, mContext.getContentResolver(), MMS_URI,
                new String[] {
                    "m_size"
                }, null, null, null);

        if (cursor != null) {
            try {
                mmsCount = cursor.getCount();
                if (mmsCount > 0) {
                    cursor.moveToPosition(-1);
                    while (cursor.moveToNext()) {
                        Log.d(TAG, "------------mmsfileSize = " + mmsfileSize);
                        mmsfileSize += (cursor.getInt(0) == 0 ? 50 * 1024 : cursor.getInt(0));
                    }
                } else {
                    return 0;
                }
            } finally {
                cursor.close();
            }
        }
        cursor = SqliteWrapper.query(mContext, mContext.getContentResolver(), SMS_URI,
                new String[] {
                    "_id"
                }, null, null, null);
        if (cursor != null) {
            try {
                smsCount = cursor.getCount();
            } finally {
                cursor.close();
            }
        }

        Log.v(TAG, "mmsUsed =" + mmsfileSize);
        long mmsMaxSize = dbSize;
        long mmsMinSize = mmsCount * 3 * 1024;
        long smsSize = smsCount * 1024;
        return (mmsfileSize < mmsMinSize ? mmsMinSize : mmsfileSize);
    }

    public static long getStoreAll() {
        File path = new File(getMmsDataDir());
        StatFs stat = new StatFs(path.getPath());
        long blockSize = stat.getBlockSize();
        long allBlocks = stat.getBlockCount();
        return allBlocks * blockSize;
    }

    public static long getStoreUsed() {
        return getStoreAll() - getStoreUnused();
    }

    public static boolean isPhoneMemoryFull() {
        long available = getStoreUnused();
        if (available < MIN_AVAILABLE_SPACE_MMS * 1024 * 1024) {
            return true;
        }
        return false;
    }

    public static String formatMemorySize(long size) {
        String suffix = null;
        String kbStr = null;
        boolean hasMb = false;
        DecimalFormat formatter = new DecimalFormat();

        // add KB or MB suffix if size is greater than 1K or 1M
        if (size >= 1024) {
            suffix = " KB";
            size /= 1024;
            kbStr = formatter.format(size);
            if (size >= 1024) {
                suffix = " MB";
                size /= 1024;
                hasMb = true;
            }
        }

        formatter.setGroupingSize(3);
        String result = formatter.format(size);

        if (suffix != null) {
            if (hasMb && kbStr != null) {
                result = result + suffix + " (" + kbStr + " KB)";
            } else {
                result = result + suffix;
            }
        }
        return result;
    }

    public static int getCurSmsPreferStore(Context context, int subscription) {
        SharedPreferences prefsms = PreferenceManager.getDefaultSharedPreferences(context);
        int preferStore = STORE_TO_PHONE;

        if (isMultiSimEnabledMms()) {
            if (subscription == SUB1) {
                preferStore = Integer.parseInt(prefsms.getString("pref_key_sms_store_card1", "1"));
            } else {
                preferStore = Integer.parseInt(prefsms.getString("pref_key_sms_store_card2", "1"));
            }
        } else {
            preferStore = Integer.parseInt(prefsms.getString("pref_key_sms_store", "1"));
        }

        return preferStore;
    }

    public static void handleIccFull(Context context, int subscription) {
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);

        Intent viewSimIntent = new Intent(context, ManageSimMessages.class);
        viewSimIntent.setAction(Intent.ACTION_VIEW);
        viewSimIntent.putExtra(MessageUtils.SUB_KEY, subscription);
        viewSimIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, viewSimIntent, 0);

        Notification notification = new Notification();
        notification.icon = R.drawable.stat_sys_no_sim;
        notification.tickerText = context.getString(R.string.sim_full_title);
        notification.defaults = Notification.DEFAULT_ALL;

        notification.setLatestEventInfo(context, context.getString(R.string.sim_full_title),
                context.getString(R.string.sim_full_body), pendingIntent);
        nm.notify(ManageSimMessages.SIM_FULL_NOTIFICATION_ID, notification);
    }

    /* check to see whether short message count is up to 2000 */
    public static void checkIsPhoneMessageFull(Context context) {
        boolean isPhoneMemoryFull = isPhoneMemoryFull();
        boolean isPhoneSmsCountFull = false;
        if (MAX_SMS_MESSAGE_COUNT != -1) {
            int msgCount = getSmsMessageCount(context);
            isPhoneSmsCountFull = msgCount >= MAX_SMS_MESSAGE_COUNT;
        }

        Log.d(TAG, "checkIsPhoneMessageFull : isPhoneMemoryFull = " + isPhoneMemoryFull
                + "isPhoneSmsCountFull = " + isPhoneSmsCountFull);

        if (isPhoneMemoryFull || isPhoneSmsCountFull) {
            MessagingNotification.updateSmsMessageFullIndicator(context, true);
        } else {
            MessagingNotification.updateSmsMessageFullIndicator(context, false);
        }
    }

    public static int getSmsMessageCount(Context context) {
        int msgCount = -1;

        Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(),
                MAILBOX_SMS_MESSAGES_COUNT, null, null, null, null);

        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    msgCount = cursor.getInt(0);
                } else {
                    Log.d(TAG, "getSmsMessageCount returned no rows!");
                }
            } finally {
                cursor.close();
            }
        }

        Log.d(TAG, "getSmsMessageCount : msgCount = " + msgCount);
        return msgCount;
    }

    public static boolean isWebUrl(String url) {
        for (String schema : WEB_SCHEMA) {
            if (url.startsWith(schema)) {
                return true;
            }
        }
        return false;
    }

    public static void removeDialogs() {
        if(memoryStatusDialog != null && memoryStatusDialog.isShowing()) {
            memoryStatusDialog.dismiss();
            memoryStatusDialog = null;
        }
    }
    public static void showMemoryStatusDialog(Context context) {
       new ShowDialog(context).execute();
    }
    public static void showNumberOptions(Context context, String number) {
        final Context localContext = context;
        final String extractNumber = number;
        final int numberOptions =
                (isVTSupported()) ? R.array.number_options_add_csvt : R.array.number_options;
        AlertDialog.Builder builder = new AlertDialog.Builder(localContext);
        builder.setTitle(number);
        builder.setCancelable(true);
        builder.setItems(numberOptions,
                new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case DIALOG_ITEM_CALL:
                        Intent dialIntent = new Intent(Intent.ACTION_CALL,
                                Uri.parse("tel:" + extractNumber));
                        localContext.startActivity(dialIntent);
                        break;
                    case DIALOG_ITEM_SMS:
                        Intent smsIntent = new Intent(Intent.ACTION_SENDTO,
                                Uri.parse("smsto:" + extractNumber));
                        localContext.startActivity(smsIntent);
                        break;
                    case DIALOG_ITEM_ADD_CONTACTS:
                        Intent intent = ConversationList
                                .createAddContactIntent(extractNumber);
                        localContext.startActivity(intent);
                        break;
                  case DIALOG_ITEM_VIDEOCALL:
                        Intent videocallIntent = new Intent(getVTCallIntent(extractNumber));
                        localContext.startActivity(videocallIntent);
                        break;
                    default:
                        break;
                }
                dialog.dismiss();
            }
        });
        builder.show();
    }

    private static class ShowDialog extends AsyncTask<String, Void, StringBuilder> {
        private Context mContext;
        public ShowDialog(Context context) {
            mContext = context;
        }

        @Override
        protected StringBuilder doInBackground(String... params) {
            StringBuilder memoryStatus = new StringBuilder();
            memoryStatus.append(mContext.getString(R.string.sms_phone_used));
            memoryStatus.append(" " + getSmsMessageCount(mContext) + "\n");
            memoryStatus.append(mContext.getString(R.string.sms_phone_capacity));
            memoryStatus.append(" " + MAX_SMS_MESSAGE_COUNT + "\n\n");
            memoryStatus.append(mContext.getString(R.string.mms_phone_used));
            memoryStatus.append(" " + formatMemorySize(getMmsUsed(mContext)) + "\n");
            memoryStatus.append(mContext.getString(R.string.mms_phone_capacity));
            memoryStatus.append(" " + formatMemorySize(getStoreAll()) + "\n");
            return memoryStatus;
        }
        @Override
        protected void onPostExecute(StringBuilder memoryStatus) {
            if(memoryStatus != null && !memoryStatus.toString().isEmpty()) {
                AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                builder.setTitle(R.string.memory_status_title);
                builder.setCancelable(true);
                builder.setPositiveButton(R.string.yes, null);
                builder.setMessage(memoryStatus);
                memoryStatusDialog = builder.create();
                memoryStatusDialog.show();
            }
        }
    }

    public static boolean isMobileDataDisabled(Context context) {
        ConnectivityManager mConnService = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        return !mConnService.getMobileDataEnabled();
    }
}
