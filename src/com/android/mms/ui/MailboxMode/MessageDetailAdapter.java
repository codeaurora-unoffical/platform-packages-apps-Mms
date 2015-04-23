/*
 * Copyright (c) 2014, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.mms.ui;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.NinePatch;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.net.Uri;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.Telephony.Sms;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.ViewPager;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import com.android.mms.R;
import com.android.mms.rcs.GeoLocation;
import com.android.mms.rcs.PropertyNode;
import com.android.mms.rcs.RcsApiManager;
import com.android.mms.rcs.RcsChatMessageUtils;
import com.android.mms.rcs.RcsEmojiStoreUtil;
import com.android.mms.rcs.RcsMessageOpenUtils;
import com.android.mms.rcs.RcsUtils;
import com.android.vcard.VCardParser;
import com.android.vcard.VCardParser_V21;
import com.android.mms.rcs.VNode;
import com.android.mms.rcs.VNodeBuilder;
import com.android.mms.ui.MessageItem;
import com.android.mms.ui.MessageListItem;
import com.android.mms.ui.MessageUtils;
import com.suntek.mway.rcs.client.api.mcloud.McloudFileApi;
import com.suntek.mway.rcs.client.aidl.provider.model.ChatMessage;
import com.suntek.mway.rcs.client.aidl.provider.model.CloudFileMessage;

public class MessageDetailAdapter extends PagerAdapter {

    private String LOG_TAG = "RCS_UI";
    private Context mContext;
    private Cursor mCursor;
    private LayoutInflater mInflater;
    private float mBodyFontSize;
    private ArrayList<TextView> mScaleTextList;
    private String mContentType = "";
    private int mMsgType = -1;
    private int mRcsId;

    public MessageDetailAdapter(Context context, Cursor cursor) {
        mContext = context;
        mCursor = cursor;
        mInflater = LayoutInflater.from(context);
        mBodyFontSize = MessageUtils.getTextFontSize(context);
    }

    @Override
    public Object instantiateItem(ViewGroup view, int position) {
        mCursor.moveToPosition(position);
        View content = mInflater.inflate(R.layout.message_detail_content, view, false);

        TextView bodyText = (TextView) content.findViewById(R.id.textViewBody);
        LinearLayout mLinearLayout = (LinearLayout)content.findViewById(R.id.other_type_layout);

        mMsgType = mCursor.getInt(mCursor.getColumnIndex("rcs_msg_type"));
        mRcsId = mCursor.getInt(mCursor.getColumnIndex("rcs_id"));
        if (mMsgType == RcsUtils.RCS_MSG_TYPE_TEXT) {
            initTextMsgView(bodyText);
        } else {
            bodyText.setVisibility(View.GONE);
            mLinearLayout.setVisibility(View.VISIBLE);
            ImageView imageView = (ImageView)mLinearLayout.findViewById(R.id.image_view);
            TextView textView = (TextView)mLinearLayout.findViewById(R.id.type_text_view);
            if (mMsgType != RcsUtils.RCS_MSG_TYPE_CAIYUNFILE) {
                imageView.setOnClickListener(mOnClickListener);
            }
            if (mMsgType == RcsUtils.RCS_MSG_TYPE_IMAGE) {
                initImageMsgView(mLinearLayout);
                showContentFileSize(textView);
                mContentType = "image/*";
            } else if (mMsgType == RcsUtils.RCS_MSG_TYPE_AUDIO) {
                imageView.setImageResource(R.drawable.rcs_voice);
                showContentFileSize(textView);
                mContentType = "audio/*";
            } else if (mMsgType == RcsUtils.RCS_MSG_TYPE_VIDEO) {
                String thumbPath = mCursor.getString(mCursor
                        .getColumnIndexOrThrow("rcs_thumb_path"));
                Bitmap bitmap = BitmapFactory.decodeFile(thumbPath);
                imageView.setImageBitmap(bitmap);
                showContentFileSize(textView);
                mContentType = "video/*";
            } else if (mMsgType == RcsUtils.RCS_MSG_TYPE_MAP) {
                imageView.setImageResource(R.drawable.rcs_map);
                textView.setText(getMapMsgBody());
                mContentType = "map/*";
            } else if (mMsgType == RcsUtils.RCS_MSG_TYPE_VCARD) {
                textView.setVisibility(View.GONE);
                initVcardMagView(mLinearLayout);
                mContentType = "text/x-vCard";
            } else if (mMsgType == RcsUtils.RCS_MSG_TYPE_PAID_EMO) {
                String messageBody = mCursor.getString(mCursor.getColumnIndex(Sms.BODY));
                String[] body = messageBody.split(",");
                RcsEmojiStoreUtil.getInstance().loadImageAsynById(imageView, body[0],
                        RcsEmojiStoreUtil.EMO_STATIC_FILE);
            } else if (mMsgType == RcsUtils.RCS_MSG_TYPE_CAIYUNFILE) {
                imageView.setImageResource(R.drawable.rcs_ic_cloud);
                ChatMessage msg = null;
                try {
                     msg = RcsApiManager.getMessageApi().getMessageById(String.valueOf(mRcsId));
                    final CloudFileMessage cMessage = msg.getCloudFileMessage();
                    final McloudFileApi api = RcsApiManager.getMcloudFileApi();
                    if (cMessage != null) {
                        textView.setText(cMessage.getFileName() + "(" + cMessage.getFileSize()
                                + "K )");
                       final boolean isFileDownload = RcsChatMessageUtils.isFileDownload(api.getLocalRootPath()
                                    + cMessage.getFileName(), cMessage.getFileSize());
                        imageView.setOnClickListener(new OnClickListener() {
                            
                            @Override
                            public void onClick(View arg0) {
                                try {
                                    if (isFileDownload) {
                                        String path = api.getLocalRootPath() + cMessage.getFileName();
                                        Intent intent2 = RcsUtils.OpenFile(path);
                                        mContext.startActivity(intent2);
                                    } else {
                                        Toast.makeText(mContext, R.string.not_download_cloudFile, Toast.LENGTH_SHORT)
                                        .show();
                                    }
                                } catch (Exception e) {
                                    Log.w(LOG_TAG,e);
                                }
                            }
                        });
                    }
                } catch (Exception e) {
                    if(e instanceof ActivityNotFoundException){
                        Toast.makeText(mContext, R.string.please_install_application,
                                Toast.LENGTH_LONG).show();
                        Log.w(LOG_TAG, e);
                    }
                }
            } else {
                bodyText.setVisibility(View.VISIBLE);
                mLinearLayout.setVisibility(View.GONE);
                initTextMsgView(bodyText);
            }
        }

        TextView detailsText = (TextView) content.findViewById(R.id.textViewDetails);
        detailsText.setText(MessageUtils.getTextMessageDetails(mContext, mCursor, true));
        view.addView(content);

        return content;
    }

    private void showContentFileSize(TextView textView){
        long fileSize = mCursor.getLong(mCursor.getColumnIndex("rcs_file_size"));
        if(fileSize > 1024){
            textView.setText(fileSize / 1024 + " KB");
        }else{
            textView.setText(fileSize + " B");
        }
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        ((ViewPager) container).removeView((View) object);
    }

    @Override
    public int getItemPosition(Object object) {
        return POSITION_NONE;
    }

    @Override
    public int getCount() {
        return mCursor != null ? mCursor.getCount() : 0;
    }

    @Override
    public boolean isViewFromObject(View view, Object object) {
        return view.equals(object);
    }

    @Override
    public void setPrimaryItem(View container, int position, Object object) {
        TextView currentBody = (TextView) container.findViewById(R.id.textViewBody);
        if (mScaleTextList.size() > 0) {
            mScaleTextList.clear();
        }
        mScaleTextList.add(currentBody);
    }

    public void setBodyFontSize(float currentFontSize) {
        mBodyFontSize = currentFontSize;
    }

    public void setScaleTextList(ArrayList<TextView> scaleTextList) {
        mScaleTextList = scaleTextList;
    }

    private void initTextMsgView(final TextView bodyText){
        bodyText.setText(mCursor.getString(mCursor.getColumnIndexOrThrow(Sms.BODY)));
        bodyText.setTextSize(TypedValue.COMPLEX_UNIT_PX, mBodyFontSize);
        bodyText.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
        bodyText.setTextIsSelectable(true);
        bodyText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MessageUtils.onMessageContentClick(mContext, bodyText);
            }
        });
    }

    private void initImageMsgView(LinearLayout linearLayout) {
        String thumbPath = mCursor.getString(mCursor.getColumnIndexOrThrow("rcs_thumb_path"));
        String filePath = mCursor.getString(mCursor.getColumnIndexOrThrow("rcs_path"));
        if (thumbPath != null && !new File(thumbPath).exists() && thumbPath.contains(".")) {
            thumbPath = thumbPath.substring(0, thumbPath.lastIndexOf("."));
        }
        if (filePath != null && !new File(filePath).exists() && filePath.contains(".")) {
            filePath = filePath.substring(0, filePath.lastIndexOf("."));
        }
        ImageView imageView = (ImageView)linearLayout.findViewById(R.id.image_view);
        Bitmap thumbPathBitmap = null;
        Bitmap filePathBitmap = null;
        if(!TextUtils.isEmpty(thumbPath)) {
            thumbPathBitmap = RcsUtils.decodeInSampleSizeBitmap(thumbPath);
        } else if (!TextUtils.isEmpty(filePath)) {
            filePathBitmap = RcsUtils.decodeInSampleSizeBitmap(filePath);
        }
        if (thumbPathBitmap != null) {
            imageView.setBackgroundDrawable(new BitmapDrawable(thumbPathBitmap));
        } else if (filePathBitmap != null) {
            imageView.setBackgroundDrawable(new BitmapDrawable(filePathBitmap));
        } else {
            imageView.setBackgroundResource(R.drawable.ic_attach_picture_holo_light);
        }
    }

    private void initVcardMagView(LinearLayout linearLayout){
        ImageView imageView = (ImageView)linearLayout.findViewById(R.id.image_view);
        int rcsId = mCursor.getInt(mCursor.getColumnIndexOrThrow("rcs_id"));
        String filePath = mCursor.getString(mCursor.getColumnIndexOrThrow("rcs_path"));
        String vcardFilePath = RcsUtils.getFilePath(rcsId, filePath);
        ArrayList<PropertyNode> propList = RcsMessageOpenUtils.openRcsVcardDetail(
                mContext, vcardFilePath);
        Bitmap bitmap = null;
        for (PropertyNode propertyNode : propList) {
            if ("PHOTO".equals(propertyNode.propName)) {
                if(propertyNode.propValue_bytes != null){
                    byte[] bytes = propertyNode.propValue_bytes;
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    bitmap = RcsUtils.decodeInSampleSizeBitmap(bitmap);
                    break;
                }
            }
        }
        if (bitmap != null) {
            imageView.setBackgroundDrawable(new BitmapDrawable(bitmap));
        } else {
            imageView.setBackgroundResource(R.drawable.ic_attach_vcard);
        }
    }

    private String getMapMsgBody(){
        String body = mCursor.getString(mCursor.getColumnIndexOrThrow(Sms.BODY));
        body = body.substring(body.lastIndexOf("/") + 1, body.length());
        return body;
    }

    private OnClickListener mOnClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            String rcsPath = mCursor.getString(mCursor.getColumnIndexOrThrow("rcs_path"));
            if(TextUtils.isEmpty(rcsPath)){
                if(mMsgType == RcsUtils.RCS_MSG_TYPE_IMAGE){
                    Toast.makeText(mContext, R.string.not_download_image, Toast.LENGTH_SHORT)
                    .show();
                }else if(mMsgType == RcsUtils.RCS_MSG_TYPE_VIDEO){
                    Toast.makeText(mContext, R.string.not_download_video, Toast.LENGTH_SHORT)
                    .show();
                }else{
                    Toast.makeText(mContext, R.string.file_path_null, Toast.LENGTH_SHORT)
                    .show();
                }
                return;
            }
            int rcsId = mCursor.getInt(mCursor.getColumnIndexOrThrow("rcs_id"));
            String filepath = RcsUtils.getFilePath(rcsId, rcsPath);
            String rcsMimeType = mCursor.getString(mCursor.getColumnIndexOrThrow("rcs_mime_type"));

            File file = new File(filepath);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(file), mContentType.toLowerCase());
            if (rcsMimeType != null && rcsMimeType.endsWith("image/gif")) {
                intent.setAction("com.android.gallery3d.VIEW_GIF");
            }
            switch (mMsgType) {
                case RcsUtils.RCS_MSG_TYPE_AUDIO:
                    try {
                        intent.setDataAndType(Uri.parse("file://" + rcsPath), "audio/*");
                        mContext.startActivity(intent);
                    } catch (Exception e) {
                    }
                    break;
                case RcsUtils.RCS_MSG_TYPE_VIDEO:
                case RcsUtils.RCS_MSG_TYPE_IMAGE:
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.putExtra("SingleItemOnly", true);
                    mContext.startActivity(intent);
                    break;
                case RcsUtils.RCS_MSG_TYPE_VCARD:
                    showOpenRcsVcardDialog();
                    break;
                case RcsUtils.RCS_MSG_TYPE_MAP:
                    openMapMessage(filepath);
                    break;
                default:
                    break;
            }
        }
    };

    private void openMapMessage(String path){
        try {
            Intent intent_map = new Intent();
            GeoLocation geo = RcsUtils.readMapXml(path);
            String geourl = "geo:" + geo.getLat() + "," + geo.getLng() +
                    "?q=" + getMapMsgBody();
            Uri uri = Uri.parse(geourl);
            Intent it = new Intent(Intent.ACTION_VIEW, uri);
            mContext.startActivity(it);
        } catch (NullPointerException e) {
            Log.w("RCS_UI", e);
        } catch (ActivityNotFoundException ae) {
            Toast.makeText(mContext,
                    R.string.toast_install_map, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w("RCS_UI", e);
        }
    }

    private void showOpenRcsVcardDialog(){
        int rcsId = mCursor.getInt(mCursor.getColumnIndexOrThrow("rcs_id"));
        String filePath = mCursor.getString(mCursor.getColumnIndexOrThrow("rcs_path"));
        final String vcardFilePath = RcsUtils.getFilePath(rcsId, filePath);
        final String[] openVcardItems = new String[] {
                mContext.getString(R.string.vcard_detail_info),
                mContext.getString(R.string.vcard_import)
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        builder.setItems(openVcardItems, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case 0:
                        ArrayList<PropertyNode> propList = RcsMessageOpenUtils.
                                openRcsVcardDetail(mContext, vcardFilePath);
                        RcsMessageOpenUtils.showDetailVcard(mContext, propList);
                        break;
                    case 1:
                        try {
                          File file = new File(vcardFilePath);
                          Intent intent = new Intent(Intent.ACTION_VIEW);
                          intent.setDataAndType(Uri.fromFile(file), mContentType
                                  .toLowerCase());
                          intent.putExtra("VIEW_VCARD_FROM_MMS", true);
                          mContext.startActivity(intent);
                      } catch (Exception e) {
                      }
                        break;
                    default:
                        break;
                }
            }
        });
        builder.create().show();
    }

}
