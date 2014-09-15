/*
   Copyright (c) 2014, The Linux Foundation. All Rights Reserved.
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.
    * Neither the name of The Linux Foundation nor the names of its
      contributors may be used to endorse or promote products derived
      from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package com.android.mms.ui;


import java.util.ArrayList;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.Telephony.Mms;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.mms.data.WorkingMessage;
import com.android.mms.MmsApp;
import com.android.mms.MmsConfig;
import com.android.mms.model.LayoutModel;
import com.android.mms.model.RegionModel;
import com.android.mms.model.SlideModel;
import com.android.mms.model.SlideshowModel;
import com.android.mms.R;
import com.android.mms.util.AddressUtils;

import com.android.mms.transaction.MessagingNotification;
import com.google.android.mms.MmsException;
import com.google.android.mms.pdu.EncodedStringValue;
import com.google.android.mms.pdu.MultimediaMessagePdu;
import com.google.android.mms.pdu.PduPersister;
import com.google.android.mms.pdu.SendReq;
import com.google.android.mms.util.SqliteWrapper;

import static com.android.mms.ui.MessageListAdapter.COLUMN_ID;
import static com.android.mms.ui.MessageListAdapter.COLUMN_MMS_READ;

public class MobilePaperShowActivity extends Activity {
    private static final String TAG = "MobilePaperShowActivity";
    private static final int MENU_SLIDESHOW = 1;
    private static final int MENU_CALL = 2;
    private static final int MENU_REPLY = 3;
    private static final int MENU_FORWARD = 4;
    private static final int MENU_DELETE = 5;
    private static final int MENU_DETAIL = 6;

    // If the finger move over 100px, we don't think it's for click.
    private static final int CLICK_LIMIT = 100;

    private int mMailboxId = -1;

    private FrameLayout mSlideView;
    private SlideshowModel mSlideModel;
    private SlideshowPresenter mPresenter;
    private LinearLayout mRootView;
    private Uri mUri;
    private Uri mTempMmsUri;
    private long mTempThreadId;
    private ScaleGestureDetector mScaleDetector;
    private ScrollView mScrollViewPort;
    private String mSubject;
    private Cursor mCursor;

    private boolean mLock = false;
    private static final int OPERATE_DEL_SINGLE_OVER = 1;
    private static final int DELETE_MESSAGE_TOKEN = 6701;
    private static final int QUERY_MESSAGE_TOKEN = 6702;
    private BackgroundQueryHandler mAsyncQueryHandler;
    private static final String[] MMS_LOCK_PROJECTION = {
        Mms._ID,
        Mms.LOCKED
    };

    private float mFontSizeForSave = MessageUtils.FONT_SIZE_DEFAULT;
    private Handler mHandler;
    private ArrayList<TextView> mSlidePaperItemTextViews;
    private boolean mOnScale;

    private Runnable mStopScaleRunnable = new Runnable() {
        @Override
        public void run() {
            /* Delay the execution to ensure scroll and zoom no conflict */
            mOnScale = false;
        }
    };

    private class MyScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            mFontSizeForSave = MessageUtils.onFontSizeScale(mSlidePaperItemTextViews,
                    detector.getScaleFactor(), mFontSizeForSave);
            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            mHandler.removeCallbacks(mStopScaleRunnable);
            mOnScale = true;
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            mHandler.postDelayed(mStopScaleRunnable, MessageUtils.DELAY_TIME);
        }
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mHandler = new Handler();

        setContentView(R.layout.mobile_paper_view);
        mRootView = (LinearLayout)findViewById(R.id.view_root);
        mSlideView = (FrameLayout)findViewById(R.id.view_scroll);
        mScaleDetector = new ScaleGestureDetector(this, new MyScaleListener());
        mAsyncQueryHandler = new BackgroundQueryHandler(getContentResolver());

        handleIntent();
        ActionBar actionBar = getActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
    }

    private void handleIntent() {
        Intent intent = getIntent();
        Uri msg = intent.getData();

        // Cancel failed notification.
        MessageUtils.cancelFailedToDeliverNotification(intent, this);
        MessageUtils.cancelFailedDownloadNotification(intent, this);

        mMailboxId = getMmsMessageBoxID(this, msg);
        mUri = msg;
        MultimediaMessagePdu msgPdu;

        try {
            mSlideModel = SlideshowModel.createFromMessageUri(this, msg);
            //add 11.4.27 for add a slide when preview && slide is 0
            if (0==mSlideModel.size()) {
                SlideModel slModel=new SlideModel(mSlideModel);
                mSlideModel.add(slModel);
            }

            msgPdu = (MultimediaMessagePdu) PduPersister.getPduPersister(this).load(msg);
            mSubject = "";
            if (msgPdu != null) {
                EncodedStringValue subject = msgPdu.getSubject();
                if (subject != null) {
                    String subStr = subject.getString();
                    mSubject = subStr;
                    setTitle(subStr);
                } else {
                    setTitle("");
                }
            } else {
                setTitle("");
            }

        } catch (MmsException e) {
            Log.e(TAG, "Cannot present the slide show.", e);
            Toast.makeText( this, R.string.cannot_play, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String mailboxUri = "content://mms-sms/mailbox/" + mMailboxId;
        mAsyncQueryHandler.startQuery(QUERY_MESSAGE_TOKEN, 0,
                Uri.parse(mailboxUri),
                MessageListAdapter.MAILBOX_PROJECTION,
                "pdu._id= " + msg.getPathSegments().get(0),
                null, "normalized_date DESC");
    }

    @Override
    protected void onStop() {
        super.onStop();
        MessageUtils.saveTextFontSize(this, mFontSizeForSave);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        mRootView.removeAllViews();
        handleIntent();
        invalidateOptionsMenu();
    }

    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getPointerCount() > 1) {
            mScaleDetector.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    private void drawRootView(){
        if (mSlidePaperItemTextViews == null) {
            mSlidePaperItemTextViews = new ArrayList<TextView>();
        } else {
            mSlidePaperItemTextViews.clear();
        }
        LayoutInflater mInflater = LayoutInflater.from(this);
        for(int index = 0; index < mSlideModel.size();index++) {
            SlideListItemView view = (SlideListItemView) mInflater
                    .inflate(R.layout.mobile_paper_item,null);
            view.setLayoutModel(mSlideModel.getLayout().getLayoutType());
            mPresenter = (SlideshowPresenter)PresenterFactory
                    .getPresenter("SlideshowPresenter",
                     this, (SlideViewInterface)view, mSlideModel);
            TextView contentText = view.getContentText();
            contentText.setTextIsSelectable(true);
            mPresenter.presentSlide((SlideViewInterface)view, mSlideModel.get(index));
            contentText.setTextSize(TypedValue.COMPLEX_UNIT_PX, MessageUtils.getTextFontSize(this));
            contentText.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    MessageUtils.onMessageContentClick(MobilePaperShowActivity.this, (TextView)v);
                }
            });
            TextView text = (TextView) view.findViewById(R.id.slide_number_text);
            text.setFocusable(false);
            text.setFocusableInTouchMode(false);
            text.setText(getString(R.string.slide_number, index + 1));
            mRootView.addView(view);
            mSlidePaperItemTextViews.add(contentText);
        }

        if (mScrollViewPort == null) {
            mScrollViewPort = new ScrollView(this) {
                private int currentX;
                private int currentY;
                private int move;

                @Override
                public boolean onTouchEvent(MotionEvent ev) {
                    mScaleDetector.onTouchEvent(ev);
                    final int action = ev.getAction();
                    switch (action) {
                        case MotionEvent.ACTION_DOWN: {
                            currentX = (int) ev.getRawX();
                            currentY = (int) ev.getRawY();
                            break;
                        }
                        case MotionEvent.ACTION_MOVE: {
                            int x2 = (int) ev.getRawX();
                            int y2 = (int) ev.getRawY();
                            /* To ensure that no conflict between zoom and scroll */
                            if (!mOnScale) {
                                mScrollViewPort.scrollBy(currentX - x2 , currentY - y2);
                            }
                            currentX = x2;
                            currentY = y2;
                            break;
                        }
                    }
                    return true;
                }

                @Override
                public boolean onInterceptTouchEvent(MotionEvent ev) {
                    mScaleDetector.onTouchEvent(ev);
                    final int action = ev.getAction();
                    switch (action) {
                        case MotionEvent.ACTION_DOWN: {
                            currentX = (int) ev.getRawX();
                            currentY = (int) ev.getRawY();
                            move = 0;
                            break;
                        }
                        case MotionEvent.ACTION_MOVE: {
                            int x2 = (int) ev.getRawX();
                            int y2 = (int) ev.getRawY();
                            /* To ensure that no conflict between zoom and scroll */
                            if (!mOnScale) {
                                mScrollViewPort.scrollBy(currentX - x2 , currentY - y2);
                            }
                            move += Math.abs(currentY - y2);
                            currentX = x2;
                            currentY = y2;
                            break;
                        }
                    }
                    return move > CLICK_LIMIT;
                }
            };

            mSlideView.removeAllViews();
            mScrollViewPort.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
            mScrollViewPort.addView(mRootView, new FrameLayout
                    .LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));
            mSlideView.addView(mScrollViewPort);
        }
    }

    private void redrawPaper() {
        mRootView.removeAllViews();
        drawRootView();
    }

    private boolean isAllowForwardMessage() {
        int messageSize = mSlideModel.getTotalMessageSize();
        int forwardStrSize = getString(R.string.forward_prefix).getBytes().length;
        int subjectSize =  mSubject == null ? 0 : mSubject.getBytes().length;
        int totalSize = messageSize + forwardStrSize + subjectSize;
        return totalSize <= (MmsConfig.getMaxMessageSize() - SlideshowModel.SLIDESHOW_SLOP);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.clear();
        if (Mms.MESSAGE_BOX_INBOX == mMailboxId) {
            menu.add(0, MENU_REPLY, 0, R.string.menu_reply);
            menu.add(0, MENU_CALL, 0, R.string.menu_call);
        }
        if (Mms.MESSAGE_BOX_DRAFTS != mMailboxId) {
            menu.add(0, MENU_DELETE, 0, R.string.menu_delete_msg);
            if (mCursor != null) {
                menu.add(0, MENU_DETAIL, 0, R.string.view_message_details);
            }
        }
        menu.add(0, MENU_FORWARD, 0, R.string.menu_forward);
        menu.add(0, MENU_SLIDESHOW, 0, R.string.view_slideshow);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()){
            case MENU_SLIDESHOW:
                Intent intent = getIntent();
                Uri msg = intent.getData();
                viewMmsMessageAttachmentSliderShow(this,msg,null,null,
                        intent.getStringArrayListExtra("sms_id_list"),
                        intent.getBooleanExtra("mms_report", false));
                break;
            case MENU_REPLY: {
                replyMessage(this, AddressUtils.getFrom(this, mUri));
                finish();
                break;
            }
            case MENU_CALL:
                call();
                break;
            case MENU_FORWARD:
                if (!isAllowForwardMessage()) {
                    Toast.makeText(MobilePaperShowActivity.this,
                            R.string.forward_size_over, Toast.LENGTH_SHORT).show();
                    return false;
                }

                new AsyncDialog(this).runAsync(new Runnable() {
                    @Override
                    public void run() {
                        SendReq sendReq = new SendReq();
                        String subject = getString(R.string.forward_prefix);
                        if (!TextUtils.isEmpty(mSubject)) {
                            subject += mSubject;
                        }
                        sendReq.setSubject(new EncodedStringValue(subject));
                        sendReq.setBody(mSlideModel.makeCopy());
                        mTempMmsUri = null;
                        try {
                            PduPersister persister =
                                    PduPersister.getPduPersister(MobilePaperShowActivity.this);
                            mTempMmsUri = persister.persist(sendReq, Mms.Draft.CONTENT_URI, true,
                                    MessagingPreferenceActivity
                                            .getIsGroupMmsEnabled(MobilePaperShowActivity.this),
                                    null);
                            mTempThreadId = MessagingNotification.getThreadId(
                                    MobilePaperShowActivity.this, mTempMmsUri);
                        } catch (MmsException e) {
                            Log.e(TAG, "Failed to forward message: " + mTempMmsUri);
                            Toast.makeText(MobilePaperShowActivity.this,
                                    R.string.cannot_save_message, Toast.LENGTH_SHORT).show();
                        }

                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        Intent intentForward = new Intent(MobilePaperShowActivity.this,
                                ComposeMessageActivity.class);
                        intentForward.putExtra("exit_on_sent", true);
                        intentForward.putExtra("forwarded_message", true);
                        String subject = getString(R.string.forward_prefix);
                        if (!TextUtils.isEmpty(mSubject)) {
                            subject += mSubject;
                        }
                        intentForward.putExtra("subject", subject);
                        intentForward.putExtra("msg_uri", mTempMmsUri);
                        if (mTempThreadId > 0) {
                            intentForward.putExtra(Mms.THREAD_ID, mTempThreadId);
                        }
                        MobilePaperShowActivity.this.startActivity(intentForward);
                    }
                }, R.string.building_slideshow_title);
                break;
            case MENU_DELETE:
                mLock = isLockMessage();
                DeleteMessageListener l = new DeleteMessageListener();
                confirmDeleteDialog(l, mLock);
                break;
            case android.R.id.home:
                finish();
                break;
            case MENU_DETAIL:
                showMessageDetails();
                break;
            default:
                break;
        }
        return true;
    }

    private boolean showMessageDetails() {
        int mediaSize = mSlideModel.getTotalMessageSize();
        String messageDetails = MessageUtils.getMessageDetails(
                MobilePaperShowActivity.this, mCursor, mediaSize);
        new AlertDialog.Builder(MobilePaperShowActivity.this)
                .setTitle(R.string.message_details_title)
                .setMessage(messageDetails)
                .setCancelable(true)
                .show();
        return true;
    }

    private boolean isLockMessage() {
        boolean locked = false;

        Cursor c = SqliteWrapper.query(this, getContentResolver(), mUri,
                MMS_LOCK_PROJECTION, null, null, null);

        try {
            if (c != null && c.moveToFirst()) {
                locked = c.getInt(1) != 0;
            }
        } finally {
            if (c != null) c.close();
        }
        return locked;
    }

    private class DeleteMessageListener implements OnClickListener {
        @Override
        public void onClick(DialogInterface dialog, int whichButton) {
            dialog.dismiss();

            new AsyncTask<Void, Void, Void>() {
                protected Void doInBackground(Void... none) {
                    mAsyncQueryHandler.startDelete(DELETE_MESSAGE_TOKEN, null, mUri,
                            mLock ? null : "locked=0", null);
                    return null;
                }
            }.execute();
        }
    }

    private void confirmDeleteDialog(OnClickListener listener, boolean locked) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(true);
        builder.setMessage(locked ? R.string.confirm_delete_locked_message
                : R.string.confirm_delete_message);
        builder.setPositiveButton(R.string.delete, listener);
        builder.setNegativeButton(R.string.no, null);
        builder.show();
    }

    private Handler mUiHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case OPERATE_DEL_SINGLE_OVER:
                    int result = msg.arg1;
                    if (result > 0) {
                        Toast.makeText(MobilePaperShowActivity.this,
                                R.string.operate_success, Toast.LENGTH_SHORT)
                                .show();
                    } else {
                        Toast.makeText(MobilePaperShowActivity.this,
                                R.string.operate_failure, Toast.LENGTH_SHORT)
                                .show();
                    }
                    finish();
                default:
                    break;
            }
        }
    };

    private final class BackgroundQueryHandler extends AsyncQueryHandler {
        public BackgroundQueryHandler(ContentResolver contentResolver) {
            super(contentResolver);
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            if (cursor != null) {
                if (mCursor != null) {
                    mCursor.close();
                }
                mCursor = cursor;
                mCursor.moveToFirst();
                if (mCursor.getInt(COLUMN_MMS_READ) == 0) {
                    MessageUtils.markAsRead(MobilePaperShowActivity.this,
                        ContentUris.withAppendedId(Mms.CONTENT_URI, mCursor.getInt(COLUMN_ID)));
                }
            }
            drawRootView();
            invalidateOptionsMenu();
        }

        @Override
        protected void onDeleteComplete(int token, Object cookie, int result) {
            switch (token) {
                case DELETE_MESSAGE_TOKEN:
                    WorkingMessage.removeThumbnailsFromCache(mSlideModel);
                    MmsApp.getApplication().getPduLoaderManager()
                            .removePdu(mUri);

                    Message msg = Message.obtain();
                    msg.what = OPERATE_DEL_SINGLE_OVER;
                    msg.arg1 = result;
                    mUiHandler.sendMessage(msg);
                    break;
            }
        }
    }

    private void replyMessage(Context context, String number) {
        Intent intent = new Intent(context, ComposeMessageActivity.class);
        intent.putExtra("address", number);
        intent.putExtra("msg_reply", true);
        intent.putExtra("reply_message", true);
        intent.putExtra("exit_on_sent", true);
        context.startActivity(intent);
    }

    private void call() {
        String msgFromTo = null;
        if (mMailboxId == Mms.MESSAGE_BOX_INBOX) {
            msgFromTo = AddressUtils.getFrom(this, mUri);
        }
        if (msgFromTo == null) {
            return;
        }

        if (MessageUtils.isMultiSimEnabledMms()) {
            if (MessageUtils.getActivatedIccCardCount() > 1) {
                showCallSelectDialog(msgFromTo);
            } else {
                if (MessageUtils.isIccCardActivated(MessageUtils.SUB1)) {
                    MessageUtils.dialRecipient(this, msgFromTo, MessageUtils.SUB1);
                } else if (MessageUtils.isIccCardActivated(MessageUtils.SUB2)) {
                    MessageUtils.dialRecipient(this, msgFromTo, MessageUtils.SUB2);
                }
            }
        } else {
            MessageUtils.dialRecipient(this, msgFromTo, MessageUtils.SUB_INVALID);
        }
    }

    private void showCallSelectDialog(final String msgFromTo) {
        String[] items = new String[MessageUtils.getActivatedIccCardCount()];
        for (int i = 0; i < items.length; i++) {
            items[i] = MessageUtils.getMultiSimName(this, i);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.menu_call));
        builder.setCancelable(true);
        builder.setItems(items, new DialogInterface.OnClickListener() {
            public final void onClick(DialogInterface dialog, int which) {
                if (which == 0) {
                    new Thread(new Runnable() {
                        public void run() {
                            MessageUtils.dialRecipient(MobilePaperShowActivity.this, msgFromTo,
                                    MessageUtils.SUB1);
                        }
                    }).start();
                } else {
                    new Thread(new Runnable() {
                        public void run() {
                            MessageUtils.dialRecipient(MobilePaperShowActivity.this, msgFromTo,
                                    MessageUtils.SUB2);
                        }
                    }).start();
                }
                dialog.dismiss();
            }
        });
        builder.show();
    }

    private int getMmsMessageBoxID(Context context, Uri uri) {
        Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(),
                uri, new String[] {Mms.MESSAGE_BOX}, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    return cursor.getInt(0);
                }
            } finally {
                cursor.close();
            }
        }
        return -1;
    }

    public static void viewMmsMessageAttachmentSliderShow(Context context,
            Uri msgUri, SlideshowModel slideshow, PduPersister persister,
            ArrayList<String> allIdList,boolean report) {

        boolean isSimple = (slideshow == null) ? false : slideshow.isSimple();
        if (isSimple || msgUri == null) {
            MessageUtils.viewSimpleSlideshow(context, slideshow);
        } else {
            Intent intent = new Intent(context, SlideshowActivity.class);
            intent.setData(msgUri);
            intent.putExtra("mms_report", report);
            intent.putStringArrayListExtra("sms_id_list", allIdList);
            context.startActivity(intent);
        }
    }
}
