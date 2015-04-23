/*
 * Copyright (c) 2014 pci-suntektech Technologies, Inc.  All Rights Reserved.
 * pci-suntektech Technologies Proprietary and Confidential.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to
 * deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */

package com.android.mms.rcs;

import com.android.mms.MmsApp;
import com.android.mms.R;
import com.android.mms.ui.MessageItem;
import com.android.mms.ui.MessageListItem;
import com.android.vcard.VCardParser;
import com.android.vcard.VCardParser_V21;
import com.android.vcard.exception.VCardException;
import com.suntek.mway.rcs.client.api.im.impl.MessageApi;
import com.suntek.mway.rcs.client.api.mcloud.McloudFileApi;
import com.suntek.mway.rcs.client.aidl.provider.model.CloudFileMessage;
import com.suntek.mway.rcs.client.aidl.plugin.entity.emoticon.EmoticonConstant;
import com.suntek.mway.rcs.client.aidl.plugin.entity.mcloudfile.TransNode;
import com.suntek.mway.rcs.client.aidl.provider.model.ChatMessage;
import com.suntek.mway.rcs.client.api.util.ServiceDisconnectedException;
import com.suntek.mway.rcs.client.aidl.plugin.callback.IMcloudOperationCtrl;

import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Intents.Insert;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

public class RcsMessageOpenUtils {
    private static final String LOG_TAG = "RCS_UI";
    private static final int VIEW_VCARD_DETAIL = 0;
    private static final int IMPORT_VCARD = 1;
    private static final int MERGE_VCARD_CONTACTS = 2;

    public static void openRcsSlideShowMessage(MessageListItem messageListItem) {
        MessageItem messageItem = messageListItem.getMessageItem();
        if (messageItem.mRcsMsgState == RcsUtils.MESSAGE_FAIL
                && messageItem.mRcsType != RcsUtils.RCS_MSG_TYPE_TEXT) {
            retransmisMessage(messageItem);
            return;
        }

        String filepath = RcsUtils.getFilePath(messageItem.mRcsId, messageItem.mRcsPath);
        File File = new File(filepath);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(File),
                messageListItem.getRcsContentType().toLowerCase());
        if (!messageItem.isMe() && messageItem.mRcsIsDownload == 0) {
            try {
                MessageApi messageApi = RcsApiManager.getMessageApi();
                ChatMessage message = messageApi.getMessageById(String.valueOf(messageItem.mRcsId));
                if (messageListItem.isDownloading() && !messageListItem.getRcsIsStopDown()) {
                    messageListItem.setRcsIsStopDown(true);
                    messageApi.interruptFile(message);
                    messageListItem.setDateViewText(R.string.stop_down_load);
                    Log.i(LOG_TAG, "STOP LOAD");
                } else {
                    messageListItem.setRcsIsStopDown(false);
                    messageApi.acceptFile(message);
                    messageListItem.setDateViewText(R.string.rcs_downloading);
                }
            } catch (Exception e) {
                Log.w(LOG_TAG, e);
            }
        } else {
            messageListItem.getContext().startActivity(intent);
        }
    }

    public static void retransmisMessage(MessageItem messageItem) {
        try {
            RcsApiManager.getMessageApi().retransmitMessageById(String.valueOf(messageItem.mRcsId));
        } catch (ServiceDisconnectedException e) {
            Log.w(LOG_TAG, e);
        }
    }

    public static void setRcsImageViewClickListener(
            ImageView imageView, final MessageListItem messageListItem){
        imageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resendOrOpenRcsMessage(messageListItem);
            }
        });
    }

    private static void resendOrOpenRcsMessage(MessageListItem messageListItem) {
        MessageItem messageItem = messageListItem.getMessageItem();
        if (messageItem.mRcsMsgState == RcsUtils.MESSAGE_FAIL
                && messageItem.mRcsType != RcsUtils.RCS_MSG_TYPE_TEXT) {
            retransmisMessage(messageItem);
        } else {
            openRcsMessage(messageListItem);
        }
    }

    private static void openRcsMessage(MessageListItem messageListItem) {
        MessageItem messageItem = messageListItem.getMessageItem();
        switch (messageItem.mRcsType) {
            case RcsUtils.RCS_MSG_TYPE_AUDIO:
                openRcsAudioMessage(messageListItem);
                break;
            case RcsUtils.RCS_MSG_TYPE_IMAGE:
                openRcsImageMessage(messageListItem);
                break;
            case RcsUtils.RCS_MSG_TYPE_VCARD:
                openRcsVCardMessage(messageListItem);
                break;
            case RcsUtils.RCS_MSG_TYPE_MAP:
                openRcsLocationMessage(messageListItem);
                break;
            case RcsUtils.RCS_MSG_TYPE_PAID_EMO:
                openRcsEmojiMessage(messageListItem);
                break;
            case RcsUtils.RCS_MSG_TYPE_CAIYUNFILE:
                openRcsCaiYunFile(messageListItem);
                break;
            default:
                break;
        }
    }

    private static void openRcsCaiYunFile(MessageListItem messageListItem) {
        MessageItem mMessageItem = messageListItem.getMessageItem();
        ChatMessage msg = null;
        boolean isFileDownload = false;
        CloudFileMessage cMessage = null;
        McloudFileApi api = null;
        IMcloudOperationCtrl operation = null;
        TransNode.TransOper transOper = TransNode.TransOper.NEW;
        try {
            msg = RcsApiManager.getMessageApi().getMessageById(String.valueOf(mMessageItem.mRcsId));
            cMessage = msg.getCloudFileMessage();
            api = RcsApiManager.getMcloudFileApi();
            if (msg != null)
                isFileDownload = RcsUtils.isFileDownLoadoK(mMessageItem);
            if(messageListItem.isDownloading()){
                transOper  = TransNode.TransOper.RESUME;
            }
            if (messageListItem.isDownloading() && !messageListItem.getRcsIsStopDown()) {
                messageListItem.setRcsIsStopDown(true);
                operation.pause();
                messageListItem.setDateViewText(R.string.stop_down_load);
                Log.i(LOG_TAG, "STOP LOAD");
            } else if(!isFileDownload) {
                messageListItem.setRcsIsStopDown(false);
                operation = api.downloadFileFromUrl(cMessage.getShareUrl(), cMessage.getFileName(),
                        transOper,mMessageItem.mRcsId);
                messageListItem.setDateViewText(R.string.rcs_downloading);
            }

            if (isFileDownload) {
                String path = api.getLocalRootPath() + cMessage.getFileName();
                Log.i("RCS_UI","PATH="+path);
                Intent intent2 = RcsUtils.OpenFile(path);
                messageListItem.getContext().startActivity(intent2);
            }
        } catch (Exception e) {
            if(e instanceof ActivityNotFoundException){
                Toast.makeText(messageListItem.getContext(), R.string.please_install_application,
                        Toast.LENGTH_LONG).show();
                Log.w(LOG_TAG, e);
            }
        }

    }

    private static void openRcsEmojiMessage(MessageListItem messageListItem){
        MessageItem messageItem = messageListItem.getMessageItem();
        String[] body = messageItem.mBody.split(",");
        byte[] data = null;
        try {
            data = RcsApiManager
                    .getEmoticonApi()
                    .decrypt2Bytes(body[0],
                            EmoticonConstant.EMO_DYNAMIC_FILE);
        } catch (ServiceDisconnectedException e) {
            e.printStackTrace();
            return;
        }
        if(data == null || data.length <= 0){
            return;
        }
        Context context = messageListItem.getContext();
        View view = messageListItem.getImageView();
        RcsUtils.openPopupWindow(context, view, data);
    }

    private static void openRcsAudioMessage(MessageListItem messageListItem) {
        try {
            MessageItem messageItem = messageListItem.getMessageItem();
            String rcsContentType = messageListItem.getRcsContentType();
            String filePath = RcsUtils.getFilePath(messageItem.mRcsId, messageItem.mRcsPath);
            File file = new File(filePath);
            if (!file.exists()) {
                Toast.makeText(messageListItem.getContext(), "no exists file",
                        Toast.LENGTH_LONG).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(file),
                    rcsContentType.toLowerCase());
            messageListItem.getContext().startActivity(intent);
        } catch (Exception e) {
            Log.w(LOG_TAG, e);
        }
    }

    private static void openRcsImageMessage(MessageListItem messageListItem) {
        MessageItem messageItem = messageListItem.getMessageItem();

        String filePath = RcsUtils.getFilePath(messageItem.mRcsId, messageItem.mRcsPath);
        File file = new File(filePath);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (messageItem.mRcsMimeType != null && messageItem.mRcsMimeType.endsWith("image/bmp")) {
            intent.setDataAndType(Uri.fromFile(file), "image/bmp");
        } else {
            intent.setDataAndType(Uri.fromFile(file), "image/*");
        }
        if (messageItem.mRcsMimeType != null && messageItem.mRcsMimeType.endsWith("image/gif")) {
            intent.setAction("com.android.gallery3d.VIEW_GIF");
        }
        ChatMessage msg = null;
        boolean isFileDownload = false;
        try {
            msg = RcsApiManager.getMessageApi().getMessageById(String.valueOf(messageItem.mRcsId));
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (msg != null)
            isFileDownload = RcsChatMessageUtils.isFileDownload(filePath, msg.getFilesize());
        if (!messageItem.isMe() && !isFileDownload) {
            try {
                MessageApi messageApi = RcsApiManager.getMessageApi();
                ChatMessage message = messageApi.getMessageById(String.valueOf(messageItem.mRcsId));
                if (messageListItem.isDownloading() && !messageListItem.getRcsIsStopDown()) {
                    messageListItem.setRcsIsStopDown(true);
                    messageApi.interruptFile(message);
                    messageListItem.setDateViewText(R.string.stop_down_load);
                    Log.i(LOG_TAG, "STOP LOAD");
                } else {
                    messageListItem.setRcsIsStopDown(false);
                    messageApi.acceptFile(message);
                    messageListItem.setDateViewText(R.string.rcs_downloading);
                }
            } catch (Exception e) {
                Log.w(LOG_TAG, e);
            }
            return;
        }
        if (messageItem.isMe() || isFileDownload) {
            messageListItem.getContext().startActivity(intent);
        }
    }

    private static void openRcsVCardMessage(MessageListItem messageListItem) {
            Context context = messageListItem.getContext();
            showOpenRcsVcardDialog(context,messageListItem);
    }

    private static void showOpenRcsVcardDialog(final Context context,final MessageListItem messageListItem){
        final String[] openVcardItems = new String[] {
                context.getString(R.string.vcard_detail_info),
                context.getString(R.string.vcard_import),
                context.getString(R.string.merge_contacts)
        };
       final MessageItem messageItem = messageListItem.getMessageItem();
        AlertDialog.Builder builder = new AlertDialog.Builder(messageListItem.getContext());
        builder.setItems(openVcardItems, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case VIEW_VCARD_DETAIL:
                        String vcardFilePath = RcsUtils.getFilePath(messageItem.mRcsId, messageItem.mRcsPath);
                        ArrayList<PropertyNode> propList = openRcsVcardDetail(context,vcardFilePath);
                        showDetailVcard(context, propList);
                        break;
                    case IMPORT_VCARD:
                        try {
                          String filePath = RcsUtils.getFilePath(messageItem.mRcsId, messageItem.mRcsPath);
                          File file = new File(filePath);
                          Intent intent = new Intent(Intent.ACTION_VIEW);
                          intent.setDataAndType(Uri.fromFile(file), messageListItem.getRcsContentType()
                                  .toLowerCase());
                          intent.putExtra("VIEW_VCARD_FROM_MMS", true);
                          messageListItem.getContext().startActivity(intent);
                      } catch (Exception e) {
                          Log.w(LOG_TAG, e);
                      }
                        break;
                    case MERGE_VCARD_CONTACTS:
                        String mergeVcardFilePath = RcsUtils.getFilePath(
                                messageItem.mRcsId, messageItem.mRcsPath);
                        ArrayList<PropertyNode> mergePropList
                                = openRcsVcardDetail(context,mergeVcardFilePath);
                        mergeVcardDetail(context, mergePropList);
                        break;
                    default:
                        break;
                }
            }
        });
        builder.create().show();
    }

    public static ArrayList<PropertyNode> openRcsVcardDetail(Context context,String filePath){
        if (TextUtils.isEmpty(filePath)){
            return null;
        }
        try {
            File file = new File(filePath);
            FileInputStream fis = new FileInputStream(file);

            VNodeBuilder builder = new VNodeBuilder();
            VCardParser parser = new VCardParser_V21();
            parser.addInterpreter(builder);
            parser.parse(fis);
            List<VNode> vNodeList = builder.getVNodeList();
            ArrayList<PropertyNode> propList = vNodeList.get(0).propList;
            return propList;
        } catch (Exception e) {
            Log.w(LOG_TAG,e);
            return null;
        }
    }

    public static void showDetailVcard(Context context,
            ArrayList<PropertyNode> propList) {
        AlertDialog.Builder builder = new Builder(context);
        LayoutInflater inflater = LayoutInflater.from(context);
        View vcardView = inflater.inflate(R.layout.rcs_vcard_detail, null);

        ImageView photoView = (ImageView) vcardView
                .findViewById(R.id.vcard_photo);
        TextView nameView, priNumber, addrText, comName, positionText;
        nameView = (TextView) vcardView.findViewById(R.id.vcard_name);
        priNumber = (TextView) vcardView.findViewById(R.id.vcard_number);
        addrText = (TextView) vcardView.findViewById(R.id.vcard_addre);
        positionText = (TextView) vcardView.findViewById(R.id.vcard_position);
        comName = (TextView) vcardView.findViewById(R.id.vcard_com_name);

        ArrayList<String> numberList = new ArrayList<String>();
        for (PropertyNode propertyNode : propList) {
            if ("FN".equals(propertyNode.propName)) {
                if (!TextUtils.isEmpty(propertyNode.propValue)) {
                    nameView.setText(context.getString(R.string.vcard_name)
                            + propertyNode.propValue);
                }
            } else if ("TEL".equals(propertyNode.propName)) {
                if (!TextUtils.isEmpty(propertyNode.propValue)) {
                    String numberTypeStr =
                            RcsUtils.getPhoneNumberTypeStr(context, propertyNode);
                    if(!TextUtils.isEmpty(numberTypeStr)){
                        numberList.add(numberTypeStr);
                    }
                }
            } else if ("ADR".equals(propertyNode.propName)) {
                if (!TextUtils.isEmpty(propertyNode.propValue)) {
                    String address = propertyNode.propValue;
                    address = address.replaceAll(";", "");
                    addrText.setText(context
                            .getString(R.string.vcard_compony_addre)
                            + ":"
                            + address);
                }
            } else if ("ORG".equals(propertyNode.propName)) {
                if (!TextUtils.isEmpty(propertyNode.propValue)) {
                    comName.setText(context
                            .getString(R.string.vcard_compony_name)
                            + ":"
                            + propertyNode.propValue);
                }
            } else if ("TITLE".equals(propertyNode.propName)) {
                if (!TextUtils.isEmpty(propertyNode.propValue)) {
                    positionText.setText(context
                            .getString(R.string.vcard_compony_position)
                            + ":"
                            + propertyNode.propValue);
                }
            } else if ("PHOTO".equals(propertyNode.propName)) {
                if (propertyNode.propValue_bytes != null) {
                    byte[] bytes = propertyNode.propValue_bytes;
                    final Bitmap vcardBitmap = BitmapFactory.decodeByteArray(
                            bytes, 0, bytes.length);
                    photoView.setImageBitmap(vcardBitmap);
                }
            }
        }
        vcardView.findViewById(R.id.vcard_middle).setVisibility(View.GONE);
        if (numberList.size() > 0) {
            priNumber.setText(numberList.get(0));
            numberList.remove(0);
        }
        if (numberList.size() > 0) {
            vcardView.findViewById(R.id.vcard_middle).setVisibility(
                    View.VISIBLE);
            LinearLayout linearLayout = (LinearLayout)vcardView.findViewById(R.id.other_number_layout);
            addNumberTextView(context, numberList, linearLayout);
        }
        builder.setTitle(R.string.vcard_detail_info);
        builder.setView(vcardView);
        builder.create();
        builder.show();
    }

    private static void mergeVcardDetail(Context context,
            ArrayList<PropertyNode> propList) {
        Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
        intent.setType(Contacts.CONTENT_ITEM_TYPE);
        ArrayList<ContentValues> phoneValue = new ArrayList<ContentValues>();
        for (PropertyNode propertyNode : propList) {
            if ("FN".equals(propertyNode.propName)) {
                if (!TextUtils.isEmpty(propertyNode.propValue)) {
                    intent.putExtra(ContactsContract.Intents.Insert.NAME,
                            propertyNode.propValue);
                }
            } else if ("TEL".equals(propertyNode.propName)) {
                if (!TextUtils.isEmpty(propertyNode.propValue)) {
                    ContentValues value = new ContentValues();
                    value.put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE);
                    value.put(Phone.TYPE, RcsUtils.getVcardNumberType(propertyNode));
                    value.put(Phone.NUMBER, propertyNode.propValue);
                    phoneValue.add(value);
                }
            } else if ("ADR".equals(propertyNode.propName)) {
                if (!TextUtils.isEmpty(propertyNode.propValue)) {
                    intent.putExtra(ContactsContract.Intents.Insert.POSTAL,
                            propertyNode.propValue);
                    intent.putExtra(ContactsContract.Intents.Insert.POSTAL_TYPE,
                            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK);
                }
            } else if ("ORG".equals(propertyNode.propName)) {
                if (!TextUtils.isEmpty(propertyNode.propValue)) {
                    intent.putExtra(ContactsContract.Intents.Insert.COMPANY,
                            propertyNode.propValue);
                }
            } else if ("TITLE".equals(propertyNode.propName)) {
                if (!TextUtils.isEmpty(propertyNode.propValue)) {
                        intent.putExtra(ContactsContract.Intents.Insert.JOB_TITLE,
                                propertyNode.propValue);
                }
//            } else if ("PHOTO".equals(propertyNode.propName)) {
//                if (propertyNode.propValue_bytes != null) {
//                    byte[] bytes = propertyNode.propValue_bytes;
//                    final Bitmap vcardBitmap = BitmapFactory.decodeByteArray(
//                            bytes, 0, bytes.length);
//                    
//                    intent.putExtra(ContactsContract.Intents.ATTACH_IMAGE, vcardBitmap);
//                }
            }
        }
        if (phoneValue.size() > 0) {
            intent.putParcelableArrayListExtra(Insert.DATA, phoneValue);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        context.startActivity(intent);
    }

    private static void addNumberTextView(Context context,
            ArrayList<String> numberList, LinearLayout linearLayout) {
        for (int i = 0; i < numberList.size(); i++) {
            TextView textView = new TextView(context);
            textView.setText(numberList.get(i));
            linearLayout.addView(textView);
        }
    }

    private static void openRcsLocationMessage(MessageListItem messageListItem) {
        MessageItem messageItem = messageListItem.getMessageItem();
        String filePath = RcsUtils.getFilePath(messageItem.mRcsId, messageItem.mRcsPath);
        String messageItemBody = messageItem.mBody;
        try {
            GeoLocation geo = RcsUtils.readMapXml(filePath);
            String messageStr = messageItemBody.substring(messageItemBody.
                    lastIndexOf("/") + 1, messageItemBody.length());
            String geourl = "geo:" + geo.getLat() + "," + geo.getLng()+ "?q=" + messageStr;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(geourl));
            messageListItem.getContext().startActivity(intent);
        } catch (NullPointerException e) {
            Log.w(LOG_TAG, e);
        } catch (ActivityNotFoundException ae) {
            Toast.makeText(messageListItem.getContext(),
                    R.string.toast_install_map, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w(LOG_TAG, e);
        }
    }

    public static boolean isCaiYunFileDown(MessageItem messageItem){
        ChatMessage msg = null;
        boolean isFileDownload = false;
        CloudFileMessage cMessage = null;
        McloudFileApi api = null;

        try {
            msg = RcsApiManager.getMessageApi().getMessageById(String.valueOf(messageItem.mRcsId));
            cMessage = msg.getCloudFileMessage();
            api = RcsApiManager.getMcloudFileApi();
            if (msg != null)
                isFileDownload = RcsChatMessageUtils.isFileDownload(api.getLocalRootPath()
                                    + cMessage.getFileName(), cMessage.getFileSize());
        } catch (Exception e) {
            Log.w("RCS_UI",e);
        }
        return isFileDownload;
    }
}
