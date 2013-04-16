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
package com.android.mms.model;

import java.util.ArrayList;

import android.content.ContentResolver;
import android.util.Log;

import com.google.android.mms.ContentType;
import com.android.mms.ContentRestrictionException;
import com.android.mms.ExceedMessageSizeException;
import com.android.mms.LogTag;
import com.android.mms.MmsConfig;
import com.android.mms.ResolutionException;
import com.android.mms.UnsupportContentTypeException;

public class CarrierContentRestriction implements ContentRestriction {
    private static final ArrayList<String> sSupportedImageTypes;
    private static final ArrayList<String> sSupportedAudioTypes;
    private static final ArrayList<String> sSupportedVideoTypes;

    private static final ArrayList<String> sRestrictedImageTypes = new ArrayList<String>();
    private static final ArrayList<String> sRestrictedAudioTypes = new ArrayList<String>();
    private static final ArrayList<String> sRestrictedVideoTypes = new ArrayList<String>();

    private static final boolean DEBUG = true;

    static {
        sSupportedImageTypes = ContentType.getImageTypes();
        sSupportedAudioTypes = ContentType.getAudioTypes();
        sSupportedVideoTypes = ContentType.getVideoTypes();
        sSupportedImageTypes.add("image/x-ms-bmp");
        sSupportedAudioTypes.add("audio/amr-wb");
        sSupportedAudioTypes.add("audio/mobile-xmf");
        sSupportedAudioTypes.add("audio/qcp");
        sSupportedAudioTypes.add("audio/wav");
        sSupportedAudioTypes.add("audio/x-wav");
        sSupportedAudioTypes.add("audio/ogg");
        sSupportedAudioTypes.add("audio/mp4");

        // TODO:  Shall we move this code to ContentType?
        // Image types for Restricted Mode.
        sRestrictedImageTypes.add(ContentType.IMAGE_JPEG);
        sRestrictedImageTypes.add(ContentType.IMAGE_JPG);
        sRestrictedImageTypes.add(ContentType.IMAGE_GIF);
        sRestrictedImageTypes.add(ContentType.IMAGE_WBMP);

        // Audio types for Restricted Mode.
        sRestrictedAudioTypes.add(ContentType.AUDIO_AMR);
        sRestrictedAudioTypes.add(ContentType.AUDIO_MID);
        sRestrictedAudioTypes.add(ContentType.AUDIO_MIDI);
        sRestrictedAudioTypes.add(ContentType.AUDIO_X_MID);
        sRestrictedAudioTypes.add(ContentType.AUDIO_X_MIDI);
        sRestrictedAudioTypes.add(ContentType.AUDIO_3GPP);

        // Video types for Restricted Mode.
        sRestrictedVideoTypes.add(ContentType.VIDEO_3GPP);
        sRestrictedVideoTypes.add(ContentType.VIDEO_3G2);
        sRestrictedVideoTypes.add(ContentType.VIDEO_H263);
        }

    public CarrierContentRestriction() {
    }

    public void checkMessageSize(int messageSize, int increaseSize, ContentResolver resolver)
            throws ContentRestrictionException {
        if (DEBUG) {
            Log.d(LogTag.APP, "CarrierContentRestriction.checkMessageSize messageSize: " +
                        messageSize + " increaseSize: " + increaseSize +
                        " MmsConfig.getMaxMessageSize: " + MmsConfig.getMaxMessageSize());
        }
        if ( (messageSize < 0) || (increaseSize < 0) ) {
            throw new ContentRestrictionException("Negative message size"
                    + " or increase size");
        }
        int newSize = messageSize + increaseSize;

        if ( (newSize < 0) || (newSize > MmsConfig.getMaxMessageSize()) ) {
            throw new ExceedMessageSizeException("Exceed message size limitation");
        }
    }

    public void checkResolution(int width, int height) throws ContentRestrictionException {
        if ( (width > MmsConfig.getMaxImageWidth()) || (height > MmsConfig.getMaxImageHeight()) ) {
            throw new ResolutionException("content resolution exceeds restriction.");
        }
    }

    public void checkImageContentType(String contentType)
            throws ContentRestrictionException {
        if (null == contentType) {
            throw new ContentRestrictionException("Null content type to be check");
        }

        if (MmsConfig.isRestrictedMode()) { // Restricted mode
            if (!sRestrictedImageTypes.contains(contentType)) {
                throw new UnsupportContentTypeException("Unsupported restricted image content type : "
                        + contentType);
            }
        } else {
            if (!sSupportedImageTypes.contains(contentType)) {
                throw new UnsupportContentTypeException("Unsupported image content type : "
                        + contentType);
            }
        }
    }

    public void checkAudioContentType(String contentType)
            throws ContentRestrictionException {
        if (null == contentType) {
            throw new ContentRestrictionException("Null content type to be check");
        }

        if (MmsConfig.isRestrictedMode()) { // Restricted mode
            if (!sRestrictedAudioTypes.contains(contentType)) {
                throw new UnsupportContentTypeException("Unsupported restricted audio content type : "
                        + contentType);
            }
        } else {
            if (!sSupportedAudioTypes.contains(contentType)) {
                throw new UnsupportContentTypeException("Unsupported audio content type : "
                        + contentType);
            }
        }
    }

    public void checkVideoContentType(String contentType)
            throws ContentRestrictionException {
        if (null == contentType) {
            throw new ContentRestrictionException("Null content type to be check");
        }

        if (MmsConfig.isRestrictedMode()) { // Restricted mode
            if (!sRestrictedVideoTypes.contains(contentType)) {
                throw new UnsupportContentTypeException("Unsupported restricted video content type : "
                        + contentType);
            }
        } else {
            if (!sSupportedVideoTypes.contains(contentType)) {
                throw new UnsupportContentTypeException("Unsupported video content type : "
                        + contentType);
            }
        }
    }
}
