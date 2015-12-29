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

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.database.sqlite.SqliteWrapper;
import android.graphics.Bitmap;
import android.graphics.Paint.FontMetricsInt;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Browser;
import android.provider.ContactsContract.Profile;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Mms;
import android.telephony.PhoneNumberUtils;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineHeightSpan;
import android.text.style.StyleSpan;
import android.text.style.TextAppearanceSpan;
import android.text.style.URLSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Checkable;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.mms.LogTag;
import com.android.mms.MmsApp;
import com.android.mms.MmsConfig;
import com.android.mms.R;
import com.android.mms.data.Contact;
import com.android.mms.data.WorkingMessage;
import com.android.mms.model.LayoutModel;
import com.android.mms.model.SlideModel;
import com.android.mms.model.SlideshowModel;
import com.android.mms.rcs.RcsMessageOpenUtils;
import com.android.mms.rcs.RcsUtils;
import com.android.mms.rcs.RcsChatMessageUtils;
import com.android.mms.transaction.SmsReceiverService;
import com.android.mms.transaction.Transaction;
import com.android.mms.transaction.TransactionBundle;
import com.android.mms.transaction.TransactionService;
import com.android.mms.ui.WwwContextMenuActivity;
import com.android.mms.util.DownloadManager;
import com.android.mms.util.ItemLoadedCallback;
import com.android.mms.util.ThumbnailManager.ImageLoaded;

import com.google.android.mms.ContentType;
import com.google.android.mms.MmsException;
import com.google.android.mms.pdu.NotificationInd;
import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.pdu.PduPersister;

import com.suntek.rcs.ui.common.mms.RcsFileTransferCache;
import com.suntek.mway.rcs.client.aidl.constant.Constants;
import com.suntek.mway.rcs.client.api.exception.ServiceDisconnectedException;
import com.suntek.mway.rcs.client.api.message.MessageApi;
import com.suntek.rcs.ui.common.mms.GeoLocation;
import com.suntek.rcs.ui.common.mms.GroupMemberPhotoCache;
import com.suntek.rcs.ui.common.mms.RcsContactsUtils;
import com.suntek.rcs.ui.common.mms.RcsMyProfileCache;
import com.suntek.rcs.ui.common.PropertyNode;
/**
 * This class provides view of a message in the messages list.
 */
public class MessageListItem extends LinearLayout implements
        SlideViewInterface, OnClickListener, Checkable {
    public static final String EXTRA_URLS = "com.android.mms.ExtraUrls";

    private static final String TAG = LogTag.TAG;
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_DONT_LOAD_IMAGES = false;
    // The message is from Browser
    private static final String BROWSER_ADDRESS = "Browser Information";
    private static final String CANCEL_URI = "canceluri";
    // transparent background
    private static final int ALPHA_TRANSPARENT = 0;
    private static final int KILOBYTE = 1024;

    static final int MSG_LIST_EDIT    = 1;
    static final int MSG_LIST_PLAY    = 2;
    static final int MSG_LIST_DETAILS = 3;

    private boolean mIsCheck = false;

    private View mMmsView;
    private ImageView mImageView;
    private ImageView mLockedIndicator;
    private ImageView mDeliveredIndicator;
    private ImageView mDetailsIndicator;
    private ImageView mSimIndicatorView;
    private ImageButton mSlideShowButton;
    private TextView mSimMessageAddress;
    private TextView mBodyTextView;
    private TextView mBodyButtomTextView;
    private TextView mBodyTopTextView;
    private Button mDownloadButton;
    private View mDownloading;
    private LinearLayout mMmsLayout;
    private CheckBox mChecked;
    private Handler mHandler;
    private MessageItem mMessageItem;
    private String mDefaultCountryIso;
    private TextView mDateView;
    public View mMessageBlock;
    private QuickContactDivot mAvatar;
    static private Drawable sDefaultContactImage;
    private Presenter mPresenter;
    private int mPosition;      // for debugging
    private ImageLoadedCallback mImageLoadedCallback;
    private boolean mMultiRecipients;
    private int mManageMode;

    /* Begin add for RCS */
    private static final int MEDIA_IS_DOWNING = 2;
    private boolean mRcsIsStopDown = false;
    private ImageView mVCardImageView;
    private static Drawable sRcsBurnFlagImage;
    private static Drawable sRcsBurnMessageHasBurnImage;
    private TextView downloadTextView;
    private TextView mNameView;
    private boolean mRcsShowMmsView = false;
    private long mRcsGroupId;
    private String mRcsContentType = "";
    /* End add for RCS */

    public MessageListItem(Context context) {
        super(context);
        Resources res = context.getResources();
        mDefaultCountryIso = MmsApp.getApplication().getCurrentCountryIso();

        if (sDefaultContactImage == null) {
            sDefaultContactImage = context.getResources().getDrawable(R.drawable.ic_contact_picture);
        }
        if (sRcsBurnFlagImage == null) {
            sRcsBurnFlagImage = res.getDrawable(R.drawable.rcs_burn_flag);
        }
        if (sRcsBurnMessageHasBurnImage == null) {
            sRcsBurnMessageHasBurnImage = res.getDrawable(
                    R.drawable.rcs_burnmessage_has_burn);
        }
    }

    public MessageListItem(Context context, AttributeSet attrs) {
        super(context, attrs);

        int color = mContext.getResources().getColor(R.color.timestamp_color);
        mColorSpan = new ForegroundColorSpan(color);
        mDefaultCountryIso = MmsApp.getApplication().getCurrentCountryIso();
        Resources res = context.getResources();
        if (sDefaultContactImage == null) {
            sDefaultContactImage = context.getResources().getDrawable(R.drawable.ic_contact_picture);
        }
        if (sRcsBurnFlagImage == null) {
            sRcsBurnFlagImage = res.getDrawable(R.drawable.rcs_burn_flag);
        }
        if (sRcsBurnMessageHasBurnImage == null) {
            sRcsBurnMessageHasBurnImage = res.getDrawable(
                    R.drawable.rcs_burnmessage_has_burn);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mBodyTopTextView = (TextView) findViewById(R.id.text_view_top);
        mBodyTopTextView.setVisibility(View.GONE);
        mBodyButtomTextView = (TextView) findViewById(R.id.text_view_buttom);
        mBodyButtomTextView.setVisibility(View.GONE);
        mDateView = (TextView) findViewById(R.id.date_view);
        mLockedIndicator = (ImageView) findViewById(R.id.locked_indicator);
        mDeliveredIndicator = (ImageView) findViewById(R.id.delivered_indicator);
        mDetailsIndicator = (ImageView) findViewById(R.id.details_indicator);
        mAvatar = (QuickContactDivot) findViewById(R.id.avatar);
        mSimIndicatorView = (ImageView) findViewById(R.id.sim_indicator_icon);
        mMessageBlock = findViewById(R.id.message_block);
        mSimMessageAddress = (TextView) findViewById(R.id.sim_message_address);
        mMmsLayout = (LinearLayout) findViewById(R.id.mms_layout_view_parent);
        mChecked = (CheckBox) findViewById(R.id.selected_check);
        mNameView = (TextView) findViewById(R.id.name_view);
    }

    // add for setting the background according to whether the item is selected
    public void markAsSelected(boolean selected) {
        if (selected) {
            if (mChecked != null) {
                mChecked.setChecked(selected);
            }
            mMessageBlock.getBackground().setAlpha(ALPHA_TRANSPARENT);
            mMmsLayout.setBackgroundResource(R.drawable.list_selected_holo_light);
        } else {
            if (mChecked != null) {
                mChecked.setChecked(selected);
            }
            mMessageBlock.setBackgroundResource(R.drawable.listitem_background);
            mMmsLayout.setBackgroundResource(R.drawable.listitem_background);
        }
    }

    private void updateBodyTextView() {
        if (mMessageItem.isMms() && mMessageItem.mLayoutType == LayoutModel.LAYOUT_TOP_TEXT) {
            mBodyButtomTextView.setVisibility(View.GONE);
            mBodyTextView = mBodyTopTextView;
        } else {
            mBodyTopTextView.setVisibility(View.GONE);
            mBodyTextView = mBodyButtomTextView;
        }
        if (!isRcsMessage()) {
            mBodyTextView.setVisibility(View.VISIBLE);
        }
    }

    public void bind(MessageItem msgItem, boolean convHasMultiRecipients, int position,
            long rcsGroupId) {
        mRcsShowMmsView = false;
        if (DEBUG) {
            Log.v(TAG, "bind for item: " + position + " old: " +
                   (mMessageItem != null ? mMessageItem.toString() : "NULL" ) +
                    " new " + msgItem.toString());
        }
        boolean sameItem = mMessageItem != null && mMessageItem.mMsgId == msgItem.mMsgId;
        mMessageItem = msgItem;

        updateBodyTextView();

        mPosition = position;
        mRcsGroupId = rcsGroupId;
        mMultiRecipients = convHasMultiRecipients;

        setLongClickable(false);
        setClickable(false);    // let the list view handle clicks on the item normally. When
                                // clickable is true, clicks bypass the listview and go straight
                                // to this listitem. We always want the listview to handle the
                                // clicks first.

        if (isRcsMessage()) {
            bindRcsMessage();
        }
        switch (msgItem.mMessageType) {
            case PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND:
                bindNotifInd();
                break;
            default:
                bindCommonMessage(sameItem);
                break;
        }

        customSIMSmsView();
    }

    public void unbind() {
        // Clear all references to the message item, which can contain attachments and other
        // memory-intensive objects
        if (mImageView != null) {
            // Because #setOnClickListener may have set the listener to an object that has the
            // message item in its closure.
            mImageView.setOnClickListener(null);
        }
        if (mSlideShowButton != null) {
            // Because #drawPlaybackButton sets the tag to mMessageItem
            mSlideShowButton.setTag(null);
        }
        // leave the presenter in case it's needed when rebound to a different MessageItem.
        if (mPresenter != null) {
            mPresenter.cancelBackgroundLoading();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        unbind();
        super.onDetachedFromWindow();
    }

    public MessageItem getMessageItem() {
        return mMessageItem;
    }

    public void setMsgListItemHandler(Handler handler) {
        mHandler = handler;
    }

    private int getFormatSize(int size) {
        return (size + KILOBYTE - 1) / KILOBYTE;
    }

    private void bindNotifInd() {
        showMmsView(false);

        if (mMessageItem.mMessageSize == 0
                && TextUtils.isEmpty(mMessageItem.mTimestamp)) {
            mMessageItem.setOnPduLoaded(new MessageItem.PduLoadedCallback() {
                public void onPduLoaded(MessageItem messageItem) {
                    if (DEBUG) {
                        Log.v(TAG, "PduLoadedCallback in MessageListItem for item: "
                                + mPosition + " " + (mMessageItem == null ? "NULL"
                                        : mMessageItem.toString())
                                + " passed in item: " + (messageItem == null ? "NULL"
                                        : messageItem.toString()));
                    }

                    if (messageItem != null
                            && mMessageItem != null
                            && messageItem.getMessageId() == mMessageItem.getMessageId()
                            && (mMessageItem.mMessageSize != 0 || !TextUtils
                                    .isEmpty(mMessageItem.mTimestamp))) {
                        bindNotifInd();
                    }
                }
            });
        } else {
            String msgSizeText = mContext.getString(R.string.message_size_label)
                    + String.valueOf(getFormatSize(mMessageItem.mMessageSize))
                    + mContext.getString(R.string.kilobyte);

            mBodyTextView.setText(formatMessage(mMessageItem, null,
                    mMessageItem.mSubId, mMessageItem.mSubject,
                    mMessageItem.mHighlight, mMessageItem.mTextContentType));

            mDateView.setText(buildTimestampLine(msgSizeText + " "
                    + mMessageItem.mTimestamp));
        }

        updateSimIndicatorView(mMessageItem.mSubId);

        switch (mMessageItem.getMmsDownloadStatus()) {
            case DownloadManager.STATE_PRE_DOWNLOADING:
            case DownloadManager.STATE_DOWNLOADING:
                showDownloadingAttachment();
                break;
            case DownloadManager.STATE_UNKNOWN:
            case DownloadManager.STATE_UNSTARTED:
                DownloadManager downloadManager = DownloadManager.getInstance();
                boolean autoDownload = downloadManager.isAuto();
                boolean dataSuspended = (MmsApp.getApplication().getTelephonyManager()
                        .getDataState() == TelephonyManager.DATA_SUSPENDED);

                // If we're going to automatically start downloading the mms attachment, then
                // don't bother showing the download button for an instant before the actual
                // download begins. Instead, show downloading as taking place.
                if (autoDownload && !dataSuspended) {
                    showDownloadingAttachment();
                    break;
                }
            case DownloadManager.STATE_TRANSIENT_FAILURE:
            case DownloadManager.STATE_PERMANENT_FAILURE:
            default:
                inflateDownloadControls();
                mDownloading.setVisibility(View.GONE);
                mDownloadButton.setVisibility(View.VISIBLE);
                mDownloadButton.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            NotificationInd nInd = (NotificationInd) PduPersister.getPduPersister(
                                    mContext).load(mMessageItem.mMessageUri);
                            Log.d(TAG, "Download notify Uri = " + mMessageItem.mMessageUri);
                            AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                            builder.setTitle(R.string.download);
                            builder.setCancelable(true);
                            // Judge notification weather is expired
                            if (nInd.getExpiry() < System.currentTimeMillis() / 1000L) {
                                // builder.setIcon(R.drawable.ic_dialog_alert_holo_light);
                                builder.setMessage(mContext
                                        .getString(R.string.service_message_not_found));
                                builder.show();
                                SqliteWrapper.delete(mContext, mContext.getContentResolver(),
                                        mMessageItem.mMessageUri, null, null);
                                return;
                            }
                            // Judge whether memory is full
                            else if (MessageUtils.isMmsMemoryFull()) {
                                builder.setMessage(mContext.getString(R.string.sms_full_body));
                                builder.show();
                                return;
                            }
                            // Judge whether message size is too large
                            else if ((int) nInd.getMessageSize() >
                                      MmsConfig.getMaxMessageSize()) {
                                builder.setMessage(mContext.getString(R.string.mms_too_large));
                                builder.show();
                                return;
                            }
                            // If mobile data is turned off, inform user start data and try again.
                            else if (MessageUtils.isMobileDataDisabled(mContext)) {
                                builder.setMessage(mContext.getString(R.string.inform_data_off));
                                builder.show();
                                return;
                            }
                        } catch (MmsException e) {
                            Log.e(TAG, e.getMessage(), e);
                            return;
                        }
                        mDownloadButton.setVisibility(View.GONE);
                        mDownloading.setVisibility(View.VISIBLE);
                        Intent intent = new Intent(mContext, TransactionService.class);
                        intent.putExtra(TransactionBundle.URI, mMessageItem.mMessageUri.toString());
                        intent.putExtra(TransactionBundle.TRANSACTION_TYPE,
                                Transaction.RETRIEVE_TRANSACTION);
                        intent.putExtra("sub_id", mMessageItem.mSubId);

                        mContext.startService(intent);

                        DownloadManager.getInstance().markState(
                                 mMessageItem.mMessageUri, DownloadManager.STATE_PRE_DOWNLOADING);
                    }
                });
                break;
        }

        if (mMessageItem.mLocked) {
            mLockedIndicator.setImageResource(R.drawable.ic_lock_message_sms);
            mLockedIndicator.setVisibility(View.VISIBLE);
        } else {
            mLockedIndicator.setVisibility(View.GONE); // Hide the indicators.
        }
        mDeliveredIndicator.setVisibility(View.GONE);
        mDetailsIndicator.setVisibility(View.GONE);
        updateAvatarView(mMessageItem.mAddress, false);
    }

    private void updateSimIndicatorView(int subscription) {
        if (MessageUtils.isMsimIccCardActive() && subscription >= 0) {
            Drawable mSimIndicatorIcon = MessageUtils.getMultiSimIcon(mContext,
                    subscription);
            mSimIndicatorView.setImageDrawable(mSimIndicatorIcon);
            mSimIndicatorView.setVisibility(View.VISIBLE);
        }
    }

    private String buildTimestampLine(String timestamp) {
        if (!mMultiRecipients || mMessageItem.isMe() || TextUtils.isEmpty(mMessageItem.mContact)) {
            // Never show "Me" for messages I sent.
            return timestamp;
        }
        // This is a group conversation, show the sender's name on the same line as the timestamp.
        return mContext.getString(R.string.message_timestamp_format, mMessageItem.mContact,
                timestamp);
    }

    private void showDownloadingAttachment() {
        inflateDownloadControls();
        mDownloading.setVisibility(View.VISIBLE);
        mDownloadButton.setVisibility(View.GONE);
    }

    private void updateAvatarView(String addr, boolean isSelf) {
        Drawable avatarDrawable;
        if (isSelf || !TextUtils.isEmpty(addr)) {
            Contact contact = isSelf ? Contact.getMe(false) : Contact.get(addr, false);
            avatarDrawable = contact.getAvatar(mContext, sDefaultContactImage);
            if (isSelf) {
                mAvatar.assignContactUri(Profile.CONTENT_URI);
                if (avatarDrawable.equals(sDefaultContactImage) && MmsConfig.isRcsEnabled()) {
                    Bitmap bitmap = RcsMyProfileCache.getInstance(mAvatar).getMyHeadPic();
                    if (bitmap != null) {
                        avatarDrawable = new BitmapDrawable(bitmap);
                    }
                }
            } else {
                if (contact.existsInDatabase()) {
                    mAvatar.assignContactUri(contact.getUri());
                } else if (MessageUtils.isWapPushNumber(contact.getNumber())) {
                    mAvatar.assignContactFromPhone(
                            MessageUtils.getWapPushNumber(contact.getNumber()), true);
                } else if (mRcsGroupId != RcsUtils.SMS_DEFAULT_RCS_GROUP_ID) {
                    GroupMemberPhotoCache.getInstance().loadGroupMemberPhoto(mRcsGroupId,
                            addr, mAvatar, sDefaultContactImage);
                    mAvatar.assignContactFromPhone(
                            MessageUtils.getWapPushNumber(contact.getNumber()), true);
                } else {
                    mAvatar.assignContactFromPhone(contact.getNumber(), true);
                }
            }
        } else {
            avatarDrawable = sDefaultContactImage;
        }
        mAvatar.setImageDrawable(avatarDrawable);
    }

    public TextView getBodyTextView() {
        return mBodyTextView;
    }

    private void bindCommonMessage(final boolean sameItem) {
        if (mDownloadButton != null) {
            mDownloadButton.setVisibility(View.GONE);
            mDownloading.setVisibility(View.GONE);
        }

        //layout type may be changed after reload pdu, so update textView here.
        if (mMessageItem.isMms() && mMessageItem.mLayoutType == LayoutModel.LAYOUT_TOP_TEXT) {
            mBodyTextView = mBodyTopTextView;
            mBodyButtomTextView.setVisibility(View.GONE);
        } else {
            mBodyTextView = mBodyButtomTextView;
            mBodyTopTextView.setVisibility(View.GONE);
        }
        if (!isRcsMessage()) {
            mBodyTextView.setVisibility(View.VISIBLE);
        }

        // Since the message text should be concatenated with the sender's
        // address(or name), I have to display it here instead of
        // displaying it by the Presenter.
        mBodyTextView.setTransformationMethod(HideReturnsTransformationMethod.getInstance());

        boolean haveLoadedPdu = mMessageItem.isSms() || mMessageItem.mSlideshow != null;
        // Here we're avoiding reseting the avatar to the empty avatar when we're rebinding
        // to the same item. This happens when there's a DB change which causes the message item
        // cache in the MessageListAdapter to get cleared. When an mms MessageItem is newly
        // created, it has no info in it except the message id. The info is eventually loaded
        // and bindCommonMessage is called again (see onPduLoaded below). When we haven't loaded
        // the pdu, we don't want to call updateAvatarView because it
        // will set the avatar to the generic avatar then when this method is called again
        // from onPduLoaded, it will reset to the real avatar. This test is to avoid that flash.
        if (!sameItem || haveLoadedPdu) {
            boolean isSelf = Sms.isOutgoingFolder(mMessageItem.mBoxId);
            String addr = isSelf ? null : mMessageItem.mAddress;
            updateAvatarView(addr, isSelf);
            //After pdu loaded, update the text view according to the slide-layout setting.
            updateBodyTextView();
        }

        // Add SIM sms address above body.
        if (isSimCardMessage()) {
            mSimMessageAddress.setVisibility(VISIBLE);
            SpannableStringBuilder buf = new SpannableStringBuilder();
            if (mMessageItem.mBoxId == Sms.MESSAGE_TYPE_INBOX) {
                buf.append(mContext.getString(R.string.from_label));
            } else {
                buf.append(mContext.getString(R.string.to_address_label));
            }
            buf.append(Contact.get(mMessageItem.mAddress, true).getName());
            mSimMessageAddress.setText(buf);
        }

        // Get and/or lazily set the formatted message from/on the
        // MessageItem.  Because the MessageItem instances come from a
        // cache (currently of size ~50), the hit rate on avoiding the
        // expensive formatMessage() call is very high.
        CharSequence formattedMessage = mMessageItem.getCachedFormattedMessage();
        if (formattedMessage == null) {
            formattedMessage = formatMessage(mMessageItem,
                                             mMessageItem.mBody,
                                             mMessageItem.mSubId,
                                             mMessageItem.mSubject,
                                             mMessageItem.mHighlight,
                                             mMessageItem.mTextContentType);
            mMessageItem.setCachedFormattedMessage(formattedMessage);
        }
        if (!sameItem || haveLoadedPdu) {
            if (MmsConfig.isRcsEnabled()
                    && mMessageItem.getRcsMsgType() != RcsUtils.RCS_MSG_TYPE_VCARD) {
                if (mMessageItem.getRcsMsgType() == RcsUtils.RCS_MSG_TYPE_CAIYUNFILE) {
                    mBodyTextView.setText(RcsUtils.getCaiYunFileBodyText(mContext, mMessageItem));
                } else if (mMessageItem.getRcsMsgType() == RcsUtils.RCS_MSG_TYPE_OTHER_FILE) {
                    mBodyTextView.setText(R.string.message_content_other_file);
                } else {
                    mBodyTextView.setText(formattedMessage);
                }
            } else {
                mBodyTextView.setText(formattedMessage);
            }
        }
        updateSimIndicatorView(mMessageItem.mSubId);
        // Debugging code to put the URI of the image attachment in the body of the list item.
        if (DEBUG) {
            String debugText = null;
            if (mMessageItem.mSlideshow == null) {
                debugText = "NULL slideshow";
            } else {
                SlideModel slide = mMessageItem.mSlideshow.get(0);
                if (slide == null) {
                    debugText = "NULL first slide";
                } else if (!slide.hasImage()) {
                    debugText = "Not an image";
                } else {
                    debugText = slide.getImage().getUri().toString();
                }
            }
            mBodyTextView.setText(mPosition + ": " + debugText);
        }

        // If we're in the process of sending a message (i.e. pending), then we show a "SENDING..."
        // string in place of the timestamp.
        if (!sameItem || haveLoadedPdu) {
            if (MmsConfig.isRcsEnabled()) {
                int rcsMsgType = mMessageItem.getRcsMsgType();
                if (rcsMsgType != RcsUtils.RCS_MSG_TYPE_IMAGE
                        && rcsMsgType != RcsUtils.RCS_MSG_TYPE_VIDEO
                        && rcsMsgType != RcsUtils.RCS_MSG_TYPE_CAIYUNFILE) {
                    mDateView.setText(buildTimestampLine(mMessageItem.isSending() ? mContext
                            .getResources().getString(R.string.sending_message)
                            : mMessageItem.mTimestamp));
                }
            } else {
                mDateView.setText(buildTimestampLine(mMessageItem.isSending() ? mContext
                        .getResources().getString(R.string.sending_message)
                        : mMessageItem.mTimestamp));
            }
        }
        if (isRcsMessage()) {
            bindCommonRcsMessage();
        }
        if (!mRcsShowMmsView) {
            if (mMessageItem.isSms()) {
                showMmsView(false);
                mMessageItem.setOnPduLoaded(null);
            } else {
            if (DEBUG) {
                Log.v(TAG, "bindCommonMessage for item: " + mPosition + " " +
                        mMessageItem.toString() +
                        " mMessageItem.mAttachmentType: " + mMessageItem.mAttachmentType +
                        " sameItem: " + sameItem);
            }
            if (mMessageItem.mAttachmentType != WorkingMessage.TEXT) {
                if (!sameItem) {
                    setImage(null, null);
                }
                setOnClickListener(mMessageItem);
                drawPlaybackButton(mMessageItem);
            } else {
                showMmsView(false);
            }
            if (mMessageItem.mSlideshow == null) {
                final int mCurrentAttachmentType = mMessageItem.mAttachmentType;
                mMessageItem.setOnPduLoaded(new MessageItem.PduLoadedCallback() {
                    public void onPduLoaded(MessageItem messageItem) {
                        if (DEBUG) {
                            Log.v(TAG, "PduLoadedCallback in MessageListItem for item: " + mPosition +
                                    " " + (mMessageItem == null ? "NULL" : mMessageItem.toString()) +
                                    " passed in item: " +
                                    (messageItem == null ? "NULL" : messageItem.toString()));
                        }
                        if (messageItem != null && mMessageItem != null &&
                                messageItem.getMessageId() == mMessageItem.getMessageId()) {
                            mMessageItem.setCachedFormattedMessage(null);
                            bindCommonMessage(
                                    mCurrentAttachmentType == messageItem.mAttachmentType);
                        }
                    }
                });
            } else {
                if (mPresenter == null) {
                    mPresenter = PresenterFactory.getPresenter(
                            "MmsThumbnailPresenter", mContext,
                            this, mMessageItem.mSlideshow);
                } else {
                    mPresenter.setModel(mMessageItem.mSlideshow);
                    mPresenter.setView(this);
                }
                if (mImageLoadedCallback == null) {
                    mImageLoadedCallback = new ImageLoadedCallback(this);
                } else {
                    mImageLoadedCallback.reset(this);
                }
                mPresenter.present(mImageLoadedCallback);
            }
        }
        }
        drawRightStatusIndicator(mMessageItem);

        requestLayout();
    }

    static private class ImageLoadedCallback implements ItemLoadedCallback<ImageLoaded> {
        private long mMessageId;
        private final MessageListItem mListItem;

        public ImageLoadedCallback(MessageListItem listItem) {
            mListItem = listItem;
            mMessageId = listItem.getMessageItem().getMessageId();
        }

        public void reset(MessageListItem listItem) {
            mMessageId = listItem.getMessageItem().getMessageId();
        }

        public void onItemLoaded(ImageLoaded imageLoaded, Throwable exception) {
            if (DEBUG_DONT_LOAD_IMAGES) {
                return;
            }
            // Make sure we're still pointing to the same message. The list item could have
            // been recycled.
            MessageItem msgItem = mListItem.mMessageItem;
            if (msgItem != null && msgItem.getMessageId() == mMessageId) {
                if (imageLoaded.mIsVideo) {
                    mListItem.setVideoThumbnail(null, imageLoaded.mBitmap);
                } else {
                    mListItem.setImage(null, imageLoaded.mBitmap);
                }
            }
        }
    }

    DialogInterface.OnClickListener mCancelLinstener = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, final int whichButton) {
            if (mDownloading.getVisibility() == View.VISIBLE) {
                Intent intent = new Intent(mContext, TransactionService.class);
                intent.putExtra(CANCEL_URI, mMessageItem.mMessageUri.toString());
                mContext.startService(intent);
                DownloadManager.getInstance().markState(mMessageItem.mMessageUri,
                        DownloadManager.STATE_TRANSIENT_FAILURE);
            }
        }
    };

    @Override
    public void startAudio() {
        // TODO Auto-generated method stub
    }

    @Override
    public void startVideo() {
        // TODO Auto-generated method stub
    }

    @Override
    public void setAudio(Uri audio, String name, Map<String, ?> extras) {
        // TODO Auto-generated method stub
    }

    @Override
    public void setImage(String name, Bitmap bitmap) {
        showMmsView(true);

        try {
            mImageView.setImageBitmap(bitmap);
            mImageView.setVisibility(VISIBLE);
        } catch (java.lang.OutOfMemoryError e) {
            Log.e(TAG, "setImage: out of memory: ", e);
        }
    }

    private void showMmsView(boolean visible) {
        if (mMmsView == null) {
            mMmsView = findViewById(R.id.mms_view);
            // if mMmsView is still null here, that mean the mms section hasn't been inflated

            if (visible && mMmsView == null) {
                //inflate the mms view_stub
                View mmsStub = findViewById(R.id.mms_layout_view_stub);
                mmsStub.setVisibility(View.VISIBLE);
                mMmsView = findViewById(R.id.mms_view);
            }
        }
        if (mMmsView != null) {
            if (mImageView == null) {
                mImageView = (ImageView) findViewById(R.id.image_view);
            }
            if (mSlideShowButton == null) {
                mSlideShowButton = (ImageButton) findViewById(R.id.play_slideshow_button);
            }
            if (MmsConfig.isRcsEnabled() && mVCardImageView == null) {
                mVCardImageView = (ImageView) findViewById(R.id.vcard_image_view);
            }
            mMmsView.setVisibility(visible ? View.VISIBLE : View.GONE);
            mImageView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void inflateDownloadControls() {
        if (mDownloadButton == null) {
            //inflate the download controls
            findViewById(R.id.mms_downloading_view_stub).setVisibility(VISIBLE);
            mDownloadButton = (Button) findViewById(R.id.btn_download_msg);
            if (getResources().getBoolean(R.bool.config_mms_cancelable)) {
                mDownloading = (Button) findViewById(R.id.btn_cancel_download);
                mDownloading.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                        builder.setTitle(R.string.cancel_downloading)
                                .setIconAttribute(android.R.attr.alertDialogIcon)
                                .setCancelable(true)
                                .setPositiveButton(R.string.yes, mCancelLinstener)
                                .setNegativeButton(R.string.no, null)
                                .setMessage(R.string.confirm_cancel_downloading)
                                .show();
                    }
                });
            } else {
                mDownloading = (TextView) findViewById(R.id.label_downloading);
            }
        }
    }


    private LineHeightSpan mSpan = new LineHeightSpan() {
        @Override
        public void chooseHeight(CharSequence text, int start,
                int end, int spanstartv, int v, FontMetricsInt fm) {
            fm.ascent -= 10;
        }
    };

    TextAppearanceSpan mTextSmallSpan =
        new TextAppearanceSpan(mContext, android.R.style.TextAppearance_Small);

    ForegroundColorSpan mColorSpan = null;  // set in ctor

    private CharSequence formatMessage(MessageItem msgItem, String body,
                                       int subId, String subject, Pattern highlight,
                                       String contentType) {
        SpannableStringBuilder buf = new SpannableStringBuilder();

        if (TelephonyManager.getDefault().getPhoneCount() > 1) {
            //SMS/MMS is operating on PhoneId which is 0, 1..
            //Sub ID will be 1, 2, ...
            SubscriptionInfo sir = SubscriptionManager.from(mContext)
                    .getActiveSubscriptionInfo(subId);
            String displayName =
                    (sir != null) ? sir.getDisplayName().toString() : "";

            Log.d(TAG, "subId: " + subId + " displayName " + displayName);
            buf.append(displayName);
            buf.append("\n");
        }

        boolean hasSubject = !TextUtils.isEmpty(subject);
        if (hasSubject) {
            buf.append(mContext.getResources().getString(R.string.inline_subject, subject));
        }

        if (!TextUtils.isEmpty(body)) {
            // Converts html to spannable if ContentType is "text/html".
            if (contentType != null && ContentType.TEXT_HTML.equals(contentType)) {
                buf.append("\n");
                buf.append(Html.fromHtml(body));
            } else {
                if (hasSubject) {
                    buf.append(" - ");
                }
                buf.append(body);
            }
        }

        if (highlight != null) {
            Matcher m = highlight.matcher(buf.toString());
            while (m.find()) {
                buf.setSpan(new StyleSpan(Typeface.BOLD), m.start(), m.end(), 0);
            }
        }
        return buf;
    }

    private boolean isSimCardMessage() {
        return mContext instanceof ManageSimMessages;
    }

    public void setManageSelectMode(int manageMode) {
        mManageMode = manageMode;
    }

    private void drawPlaybackButton(MessageItem msgItem) {
        switch (msgItem.mAttachmentType) {
            case WorkingMessage.SLIDESHOW:
            case WorkingMessage.AUDIO:
            case WorkingMessage.VIDEO:
                // Show the 'Play' button and bind message info on it.
                mSlideShowButton.setTag(msgItem);
                // Set call-back for the 'Play' button.
                mSlideShowButton.setOnClickListener(this);
                mSlideShowButton.setVisibility(View.VISIBLE);
                setLongClickable(false);
                break;
            default:
                mSlideShowButton.setVisibility(View.GONE);
                break;
        }
    }

    // OnClick Listener for the playback button
    @Override
    public void onClick(View v) {
        sendMessage(mMessageItem, MSG_LIST_PLAY);
    }

    private void sendMessage(MessageItem messageItem, int message) {
        if (mHandler != null) {
            Message msg = Message.obtain(mHandler, message);
            msg.obj = messageItem;
            msg.sendToTarget(); // See ComposeMessageActivity.mMessageListItemHandler.handleMessage
        }
    }

    public void onMessageListItemClick() {
        // If the message is a failed one, clicking it should reload it in the compose view,
        // regardless of whether it has links in it
        if (mMessageItem != null &&
                mMessageItem.isOutgoingMessage() &&
                mMessageItem.isFailedMessage() ) {
            //if message is rcsMessage except text,return.
            if (isRcsMessage() && mMessageItem.getRcsMsgType() != RcsUtils.RCS_MSG_TYPE_TEXT) {
                return;
            }
            // Assuming the current message is a failed one, reload it into the compose view so
            // the user can resend it.
            sendMessage(mMessageItem, MSG_LIST_EDIT);
            return;
        }

        // Check for links. If none, do nothing; if 1, open it; if >1, ask user to pick one
        final URLSpan[] spans = mBodyTextView.getUrls();
        if (spans.length == 0) {
            sendMessage(mMessageItem, MSG_LIST_DETAILS);
        } else {
            MessageUtils.onMessageContentClick(mContext, mBodyTextView);
        }
    }

    private void setOnClickListener(final MessageItem msgItem) {
        switch(msgItem.mAttachmentType) {
            case WorkingMessage.VCARD:
            case WorkingMessage.IMAGE:
            case WorkingMessage.VIDEO:
                mImageView.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sendMessage(msgItem, MSG_LIST_PLAY);
                    }
                });
                mImageView.setOnLongClickListener(new OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        return v.showContextMenu();
                    }
                });
                break;

            default:
                mImageView.setOnClickListener(null);
                break;
            }
    }

    private void drawRightStatusIndicator(MessageItem msgItem) {
        // Locked icon
        if (msgItem.mLocked) {
            mLockedIndicator.setImageResource(R.drawable.ic_lock_message_sms);
            mLockedIndicator.setVisibility(View.VISIBLE);
        } else {
            mLockedIndicator.setVisibility(View.GONE);
        }

        // Delivery icon - we can show a failed icon for both sms and mms, but for an actual
        // delivery, we only show the icon for sms. We don't have the information here in mms to
        // know whether the message has been delivered. For mms, msgItem.mDeliveryStatus set
        // to MessageItem.DeliveryStatus.RECEIVED simply means the setting requesting a
        // delivery report was turned on when the message was sent. Yes, it's confusing!
        if ((msgItem.isOutgoingMessage() && msgItem.isFailedMessage()) ||
                msgItem.mDeliveryStatus == MessageItem.DeliveryStatus.FAILED) {
            mDeliveredIndicator.setImageResource(R.drawable.ic_list_alert_sms_failed);
            mDeliveredIndicator.setVisibility(View.VISIBLE);
        } else if (msgItem.isSms() &&
                msgItem.mDeliveryStatus == MessageItem.DeliveryStatus.RECEIVED) {
            mDeliveredIndicator.setImageResource(R.drawable.ic_sms_mms_delivered);
            mDeliveredIndicator.setVisibility(View.VISIBLE);
        } else {
            mDeliveredIndicator.setVisibility(View.GONE);
        }

        // Message details icon - this icon is shown both for sms and mms messages. For mms,
        // we show the icon if the read report or delivery report setting was set when the
        // message was sent. Showing the icon tells the user there's more information
        // by selecting the "View report" menu.
        if (msgItem.mDeliveryStatus == MessageItem.DeliveryStatus.INFO
                || (msgItem.isMms() && !msgItem.isSending() &&
                        msgItem.mDeliveryStatus == MessageItem.DeliveryStatus.PENDING)) {
            mDetailsIndicator.setImageResource(R.drawable.ic_sms_mms_details);
            mDetailsIndicator.setVisibility(View.VISIBLE);
        } else if (msgItem.isMms() && !msgItem.isSending() &&
                msgItem.mDeliveryStatus == MessageItem.DeliveryStatus.RECEIVED) {
            mDetailsIndicator.setImageResource(R.drawable.ic_sms_mms_delivered);
            mDetailsIndicator.setVisibility(View.VISIBLE);
        } else if (msgItem.mReadReport) {
            mDetailsIndicator.setImageResource(R.drawable.ic_sms_mms_details);
            mDetailsIndicator.setVisibility(View.VISIBLE);
        } else {
            mDetailsIndicator.setVisibility(View.GONE);
        }
    }

    @Override
    public void setImageRegionFit(String fit) {
        // TODO Auto-generated method stub
    }

    @Override
    public void setImageVisibility(boolean visible) {
        // TODO Auto-generated method stub
    }

    @Override
    public void setText(String name, String text) {
        // TODO Auto-generated method stub
    }

    @Override
    public void setTextVisibility(boolean visible) {
        // TODO Auto-generated method stub
    }

    @Override
    public void setVideo(String name, Uri uri) {
    }

    @Override
    public void setVideoThumbnail(String name, Bitmap bitmap) {
        showMmsView(true);

        try {
            mImageView.setImageBitmap(bitmap);
            mImageView.setVisibility(VISIBLE);
        } catch (java.lang.OutOfMemoryError e) {
            Log.e(TAG, "setVideo: out of memory: ", e);
        }
    }

    @Override
    public void setVideoVisibility(boolean visible) {
        // TODO Auto-generated method stub
    }

    @Override
    public void stopAudio() {
        // TODO Auto-generated method stub
    }

    @Override
    public void stopVideo() {
        // TODO Auto-generated method stub
    }

    @Override
    public void reset() {
    }

    @Override
    public void setVisibility(boolean visible) {
        // TODO Auto-generated method stub
    }

    @Override
    public void pauseAudio() {
        // TODO Auto-generated method stub

    }

    @Override
    public void pauseVideo() {
        // TODO Auto-generated method stub

    }

    @Override
    public void seekAudio(int seekTo) {
        // TODO Auto-generated method stub

    }

    @Override
    public void seekVideo(int seekTo) {
        // TODO Auto-generated method stub

    }

    @Override
    public void setVcard(Uri lookupUri, String name) {
        showMmsView(true);

        try {
            mImageView.setImageResource(R.drawable.ic_attach_vcard);
            mImageView.setVisibility(VISIBLE);
        } catch (java.lang.OutOfMemoryError e) {
            // shouldn't be here.
            Log.e(TAG, "setVcard: out of memory: ", e);
        }
    }

    @Override
    public boolean isChecked() {
        return mIsCheck;
    }

    @Override
    public void setChecked(boolean arg0) {
        mIsCheck = arg0;
        if (mIsCheck) {
            mMessageBlock.getBackground().setAlpha(ALPHA_TRANSPARENT);
            mMmsLayout
                    .setBackgroundResource(R.drawable.list_selected_holo_light);
        } else {
            mMessageBlock.setBackgroundResource(R.drawable.listitem_background);
            mMmsLayout.setBackgroundResource(R.drawable.listitem_background);
        }
    }

    @Override
    public void toggle() {
    }

    protected void customSIMSmsView() {
        if (isSimCardMessage()) {
            // Hide delivery indicator for SIM message
            mDeliveredIndicator.setVisibility(GONE);
            // Hide date view because SIM message does not contain sent message
            // and CDMA received message.
            if (mMessageItem.isOutgoingMessage() || mMessageItem.mBoxId == Sms.MESSAGE_TYPE_SENT
                    || mMessageItem.isCdmaInboxMessage()) {
                mDateView.setVisibility(View.GONE);
            }
        }
    }

    public void setBodyTextSize(float size) {
        if (mBodyTextView != null
                && mBodyTextView.getVisibility() == View.VISIBLE) {
            mBodyTextView.setTextSize(size);
        }
    }

    /* Begin add for RCS */
    public boolean getRcsIsStopDown() {
        return mRcsIsStopDown;
    }

    public void setRcsIsStopDown(boolean rcsIsStopDown) {
            mRcsIsStopDown = rcsIsStopDown;
    }

    public String getRcsContentType() {
        return mRcsContentType;
    }

    public void setDateViewText(int resId) {
        mDateView.setText(resId);
    }

    private void updateGroupMemberDisplayName() {
        if (mNameView != null) {
            mNameView.setText(RcsContactsUtils.getGroupChatMemberDisplayName(getContext(),
                    mRcsGroupId, mMessageItem.mAddress));
            mNameView.setVisibility(View.VISIBLE);
        }
    }

    private void formatAndSetRcsCachedFormattedMessage(MessageItem messageItem, String body) {
        CharSequence formattedMessage = formatMessage(messageItem,
                body,
                messageItem.mSubId,
                messageItem.mSubject,
                messageItem.mHighlight,
                messageItem.mTextContentType);
        messageItem.setCachedFormattedMessage(formattedMessage);
    }

    private boolean isRcsMessage() {
        return mMessageItem.isRcsMessage();
    }

    private void bindRcsMessage() {
        if (mMessageItem.isGroupChatMessageWithoutNotification()) {
            updateGroupMemberDisplayName();
        }

        if (mMessageItem.isRcsBurnMessage()) {
            bindRcsBurnMessage();
        } else {
            bindRcsNotBurnMessage();
        }
    }

    private void bindRcsBurnMessage() {
        setLongClickable(true);
        setClickable(true);
        mRcsContentType = "";
        mRcsShowMmsView = true;
        showMmsView(true);
        if (mSlideShowButton != null) {
            mSlideShowButton.setVisibility(View.GONE);
        }
        if (mMessageItem.getRcsMsgState() == RcsUtils.MESSAGE_HAS_BURNED) {
            mImageView.setImageDrawable(sRcsBurnMessageHasBurnImage);
        } else {
            mImageView.setImageDrawable(sRcsBurnFlagImage);
        }
        mImageView.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View arg0) {
                if (mMessageItem.getRcsMsgState() == RcsUtils.MESSAGE_FAIL) {
                    RcsMessageOpenUtils.retransmisMessage(mMessageItem);
                } else {
                    RcsChatMessageUtils.startBurnMessageActivity(mContext, mMessageItem.mMsgId,
                            mMessageItem.getRcsMsgState());
                }
            }
        });
        mImageView.setOnLongClickListener(new OnLongClickListener() {

            @Override
            public boolean onLongClick(View v) {
                new AlertDialog.Builder(getContext()).setTitle(R.string.rcs_tip)
                        .setMessage(R.string.rcs_delete_burn_message)
                        .setCancelable(true)
                        .setPositiveButton(R.string.rcs_confirm,
                                new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    MessageApi.getInstance().deleteMessage(
                                            mMessageItem.getMessageId());
                                } catch (RemoteException e) {
                                    // TODO: handle exception
                                } catch (ServiceDisconnectedException e) {
                                }
                            }
                        }).setNegativeButton(R.string.rcs_cancel, null).show();
                return false;
            }
        });

        mBodyTextView.setVisibility(View.GONE);
    }

    private void bindRcsNotBurnMessage() {
        mRcsContentType = RcsUtils.getContentTypeForMessageItem(mMessageItem);
        switch (mMessageItem.getRcsMsgType()) {
            case RcsUtils.RCS_MSG_TYPE_VIDEO: {
                int videoDuration = 0;
                String body = mMessageItem.getMsgBody();
                if (body != null) {
                    videoDuration = Integer.valueOf(body);
                }
                double displayDuration = (double)(Math.round(videoDuration/10)/100.0);
                String msgBody = mMessageItem.getRcsMsgFileSize() / 1024 + "KB/ "
                        + displayDuration + "s";
                formatAndSetRcsCachedFormattedMessage(mMessageItem, msgBody);
                mBodyTextView.setVisibility(View.VISIBLE);
                break;
            }
            case RcsUtils.RCS_MSG_TYPE_MAP: {
                mBodyTextView.setVisibility(View.VISIBLE);
                break;
            }
            case RcsUtils.RCS_MSG_TYPE_IMAGE: {
                mBodyTextView.setVisibility(View.GONE);
                break;
            }
            case RcsUtils.RCS_MSG_TYPE_AUDIO: {
                mBodyTextView.setVisibility(View.VISIBLE);
                int audioDuration = 0;
                String body = mMessageItem.getMsgBody();
                if (body != null) {
                    audioDuration = Integer.valueOf(body);
                }
                double displayDuration = (double)(Math.round(audioDuration/10)/100.0);
                String dispayBody = displayDuration +
                        mContext.getString(R.string.time_length_unit);
                formatAndSetRcsCachedFormattedMessage(mMessageItem, dispayBody);
                break;
            }
            case RcsUtils.RCS_MSG_TYPE_PAID_EMO: {
                mBodyTextView.setVisibility(View.GONE);
                break;
            }
            case RcsUtils.RCS_MSG_TYPE_VCARD:{
                mBodyTextView.setVisibility(View.VISIBLE);
                mBodyTextView.setText(RcsUtils.disposeVcardMessage(
                        getContext(), mMessageItem.getRcsPath()));
                break;
            }
            case RcsUtils.RCS_MSG_TYPE_CAIYUNFILE:{
                mBodyTextView.setVisibility(View.VISIBLE);
                String msgBody = RcsUtils.getCaiYunFileBodyText(
                        getContext(), mMessageItem);
                mBodyTextView.setText(msgBody);
                break;
            }
            case RcsUtils.RCS_MSG_TYPE_OTHER_FILE: {
                mBodyTextView.setVisibility(View.VISIBLE);
                break;
            }
        }

        if (mMessageItem.getRcsMsgType() == RcsUtils.RCS_MSG_TYPE_TEXT) {
            mBodyTextView.setVisibility(View.VISIBLE);
            mRcsShowMmsView = false;
            showMmsView(false);
        } else {
            showMmsView(true);
            if (mSlideShowButton == null) {
                mSlideShowButton = (ImageButton) findViewById(R.id.play_slideshow_button);
            }
            if (mMessageItem.getRcsMsgType() == RcsUtils.RCS_MSG_TYPE_VCARD) {
                mImageView.setVisibility(View.GONE);
                mVCardImageView.setVisibility(View.VISIBLE);
                RcsUtils.setThumbnailForMessageItem(getContext(), mVCardImageView, mMessageItem);
            } else {
                RcsUtils.setThumbnailForMessageItem(getContext(), mImageView, mMessageItem);
            }
            if (mMessageItem.getRcsMsgType() == RcsUtils.RCS_MSG_TYPE_VIDEO) {
                mSlideShowButton.setVisibility(View.VISIBLE);
                mSlideShowButton.setFocusable(false);
                mSlideShowButton.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        RcsMessageOpenUtils.openRcsSlideShowMessage(MessageListItem.this);
                    }
                });
                mImageView.setOnClickListener(null);
                mVCardImageView.setOnClickListener(null);
            } else {
                mSlideShowButton.setVisibility(View.GONE);
                if (mMessageItem.getRcsMsgType() == RcsUtils.RCS_MSG_TYPE_VCARD) {
                    RcsMessageOpenUtils.setRcsImageViewClickListener(
                            mVCardImageView, MessageListItem.this);
                    mImageView.setOnClickListener(null);
                } else {
                    RcsMessageOpenUtils.setRcsImageViewClickListener(
                            mImageView, MessageListItem.this);
                    mVCardImageView.setOnClickListener(null);
                }
                mSlideShowButton.setOnClickListener(null);
            }
            mRcsShowMmsView = true;
        }
    }

    public ImageView getImageView(){
        return mImageView;
    }

    private void toast(int resId) {
        Toast.makeText(mContext, resId, Toast.LENGTH_LONG).show();
    }

    private void bindCommonRcsMessage() {
        if (mMessageItem.isMe()) {
            mDateView.setText(RcsUtils.getRcsMessageStatusText(this, mMessageItem));
        } else if (mMessageItem.isRcsBurnMessage()) {
            mDateView.setText(mMessageItem.getTimestamp());
        } else {
            int rcsMsgType = mMessageItem.getRcsMsgType();
            if (rcsMsgType == RcsUtils.RCS_MSG_TYPE_PAID_EMO) {
                if (mMessageItem.getMsgDownlaodState() == RcsUtils.RCS_IS_DOWNLOAD_FAIL) {
                    mDateView.setText(R.string.download_fail_please_download_again);
                } else {
                    mDateView.setText(mMessageItem.getTimestamp());
                }
            } else if ((rcsMsgType == RcsUtils.RCS_MSG_TYPE_IMAGE
                    || rcsMsgType == RcsUtils.RCS_MSG_TYPE_VIDEO
                    || rcsMsgType == RcsUtils.RCS_MSG_TYPE_CAIYUNFILE)) {
                if (mMessageItem.getMsgDownlaodState() == RcsUtils.RCS_IS_DOWNLOAD_FALSE
                        && !mMessageItem.isRcsBurnMessage()) {
                    mDateView.setText(R.string.message_download);
                } else if (RcsFileTransferCache.getInstance()
                        .hasFileTransferPercent(mMessageItem.getMessageId()) &&
                        mMessageItem.getMsgDownlaodState() == RcsUtils.RCS_IS_DOWNLOADING) {
                    Long percent = RcsFileTransferCache.getInstance()
                            .getFileTransferPercent(mMessageItem.getMessageId());
                    if (percent != null) {
                        if (!mMessageItem.isMe()) {
                            mDateView.setText(getContext().getString(
                                    R.string.downloading_percent, percent.intValue()));
                        } else {
                            mDateView.setText(getContext().getString(
                                    R.string.uploading_percent, percent.intValue()));
                        }
                    }
                } else if (mMessageItem.getMsgDownlaodState() == RcsUtils.RCS_IS_DOWNLOAD_PAUSE &&
                        !RcsUtils.isFileDownLoadoK(mMessageItem)) {
                    mDateView.setText(getContext().getString(R.string.stop_down_load));
                } else if (mMessageItem.getMsgDownlaodState() == RcsUtils.RCS_IS_DOWNLOAD_OK) {
                    mDateView.setText(buildTimestampLine(mMessageItem.isSending() ? mContext
                            .getResources().getString(R.string.sending_message)
                            : mMessageItem.getTimestamp()));
                } else if (mMessageItem.getMsgDownlaodState() == RcsUtils.RCS_IS_DOWNLOAD_FAIL
                        && RcsUtils.isFileDownBeginButNotEnd(mMessageItem.getRcsPath(),
                        mMessageItem.getRcsMsgFileSize())) {
                    mDateView.setText(R.string.download_fail_please_download_again);
                }
            }
        }
        if (mMessageItem.getRcsMsgState() == RcsUtils.MESSAGE_HAS_SENT_TO_SERVER
                && mMessageItem.getRcsMsgType() != Constants.MessageConstants.CONST_MESSAGE_TEXT) {
            Long percent = RcsFileTransferCache.getInstance()
                    .getFileTransferPercent(mMessageItem.getMessageId());
            if (percent != null) {
                if (!mMessageItem.isMe()) {
                    mDateView.setText(getContext().getString(R.string.downloading_percent,
                            percent.intValue()));
                } else {
                    mDateView.setText(getContext().getString(R.string.uploading_percent,
                            percent.intValue()));
                }
            }
        }
    }
    /* End add for RCS */

}
