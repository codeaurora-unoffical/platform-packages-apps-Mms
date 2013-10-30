/*
 * Copyright (C) 2007-2008 Esmertec AG.
 * Copyright (C) 2007-2008 The Android Open Source Project
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
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.RingtonePreference;
import android.provider.SearchRecentSuggestions;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.MSimTelephonyManager;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;
import android.telephony.MSimSmsManager;
import android.util.Log;

import com.android.internal.telephony.MSimConstants;
import com.android.mms.MmsApp;
import com.android.mms.MmsConfig;
import com.android.mms.R;
import com.android.mms.transaction.TransactionService;

import android.telephony.TelephonyManager;
import android.telephony.MSimTelephonyManager;

import com.android.mms.util.Recycler;
//import static com.android.internal.telephony.MSimConstants.MAX_PHONE_COUNT_DS;

import java.util.ArrayList;

import static com.android.internal.telephony.MSimConstants.MAX_PHONE_COUNT_DUAL_SIM;

/**
 * With this activity, users can set preferences for MMS and SMS and
 * can access and manipulate SMS messages stored on the SIM.
 */
public class MessagingPreferenceActivity extends PreferenceActivity
            implements OnPreferenceChangeListener {
    // Symbolic names for the keys used for preference lookup
    public static final String MMS_DELIVERY_REPORT_MODE = "pref_key_mms_delivery_reports";
    public static final String EXPIRY_TIME              = "pref_key_mms_expiry";
    public static final String PRIORITY                 = "pref_key_mms_priority";
    public static final String READ_REPORT_MODE         = "pref_key_mms_read_reports";
    public static final String SMS_DELIVERY_REPORT_MODE = "pref_key_sms_delivery_reports";
    public static final String NOTIFICATION_ENABLED     = "pref_key_enable_notifications";
    public static final String NOTIFICATION_VIBRATE     = "pref_key_vibrate";
    public static final String NOTIFICATION_VIBRATE_WHEN= "pref_key_vibrateWhen";
    public static final String NOTIFICATION_RINGTONE    = "pref_key_ringtone";
    public static final String AUTO_RETRIEVAL           = "pref_key_mms_auto_retrieval";
    public static final String RETRIEVAL_DURING_ROAMING = "pref_key_mms_retrieval_during_roaming";
    public static final String AUTO_DELETE              = "pref_key_auto_delete";
    public static final String GROUP_MMS_MODE           = "pref_key_mms_group_mms";
    public static final String SMS_CDMA_PRIORITY        = "pref_key_sms_cdma_priority";

    // AirPlane mode flag
    private final static int AIR_PLANE_MODE_CHANGED = 1;
    private final static int AIR_PLANE_MODE_ENABLE = 2;
    private final static int AIR_PLANE_MODE_DISABLE = 3;

    // Expiry of MMS
    private final static String EXPIRY_ONE_WEEK = "604800"; // 7 * 24 * 60 * 60
    private final static String EXPIRY_TWO_DAYS = "172800"; // 2 * 24 * 60 * 60

    private static final String TAG = "MessagingPreferenceActivity";
    // Menu entries
    private static final int MENU_RESTORE_DEFAULTS    = 1;

    private Preference mSmsLimitPref;
    private Preference mSmsDeliveryReportPref;
    private Preference mMmsLimitPref;
    private Preference mMmsDeliveryReportPref;
    private Preference mMmsGroupMmsPref;
    private Preference mMmsReadReportPref;
    private Preference mManageSimPref;
    private Preference mClearHistoryPref;
    private CheckBoxPreference mVibratePref;
    private CheckBoxPreference mEnableNotificationsPref;
    private CheckBoxPreference mMmsAutoRetrievialPref;
    private ListPreference mMmsExpiryPref;
    private RingtonePreference mRingtonePref;
    private ListPreference mSmsStorePref;
    private ListPreference mSmsStoreCard1Pref;
    private ListPreference mSmsStoreCard2Pref;
    private Recycler mSmsRecycler;
    private Recycler mMmsRecycler;
    private Preference mSmsTemplate;
    private CheckBoxPreference mSmsSignaturePref;
    private EditTextPreference mSmsSignatureEditPref;
    private PreferenceCategory mSmscPrefCate;
    private ArrayList<Preference> mSmscPrefList = new ArrayList<Preference>();
    private static final int CONFIRM_CLEAR_SEARCH_HISTORY_DIALOG = 3;

    private static final String TARGET_PACKAGE = "com.android.mms";
    private static final String TARGET_CLASS = "com.android.mms.ui.ManageSimMessages";
    private static final int ALL_SUB = -1;
    private static final String TITLE = "title";
    private static final String SMSC  = "smsc";
    private static final String SUB   = "sub";
    private static final String COMMAND_GET_SMSC    = "com.android.smsc.cmd.get";
    private static final String COMMAND_SET_SMSC    = "com.android.smsc.cmd.set";
    private static final String NOTIFY_SMSC_UPDATE  = "com.android.smsc.notify.update";
    private static final String NOTIFY_SMSC_ERROR   = "com.android.smsc.notify.error";
    private static final String NOTIFY_SMSC_SUCCESS = "com.android.smsc.notify.success";

    private BroadcastReceiver mReceiver = null;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        loadPrefs();

        ActionBar actionBar = getActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Since the enabled notifications pref can be changed outside of this activity,
        // we have to reload it whenever we resume.
        setEnabledNotificationsPref();
        // Initialize the sms signature
        updateSignatureStatus();
        registerListeners();
        boolean airplaneModeOn = Settings.System.getInt(getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) != 0;
        // Caused by the ICC card state maybe changed outside of this activity,
        // we need to update the state of the SMSC preference.
        updateSMSCPref(ALL_SUB, airplaneModeOn);
    }

    @Override
    protected void onDestroy() {
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
        }
        super.onDestroy();
    }

    private void loadPrefs() {
        addPreferencesFromResource(R.xml.preferences);

        mManageSimPref = findPreference("pref_key_manage_sim_messages");
        mSmsLimitPref = findPreference("pref_key_sms_delete_limit");
        mSmsDeliveryReportPref = findPreference("pref_key_sms_delivery_reports");
        mMmsDeliveryReportPref = findPreference("pref_key_mms_delivery_reports");
        mMmsGroupMmsPref = findPreference("pref_key_mms_group_mms");
        mMmsReadReportPref = findPreference("pref_key_mms_read_reports");
        mMmsLimitPref = findPreference("pref_key_mms_delete_limit");
        mClearHistoryPref = findPreference("pref_key_mms_clear_history");
        mEnableNotificationsPref = (CheckBoxPreference) findPreference(NOTIFICATION_ENABLED);
        mMmsAutoRetrievialPref = (CheckBoxPreference) findPreference(AUTO_RETRIEVAL);
        mMmsExpiryPref = (ListPreference) findPreference("pref_key_mms_expiry");
        mVibratePref = (CheckBoxPreference) findPreference(NOTIFICATION_VIBRATE);
        mSmsSignaturePref = (CheckBoxPreference) findPreference("pref_key_enable_signature");
        mSmsSignatureEditPref = (EditTextPreference) findPreference("pref_key_edit_signature");
        mRingtonePref = (RingtonePreference) findPreference(NOTIFICATION_RINGTONE);
        mSmsStorePref = (ListPreference) findPreference("pref_key_sms_store");
        mSmsStoreCard1Pref = (ListPreference) findPreference("pref_key_sms_store_card1");
        mSmsStoreCard2Pref = (ListPreference) findPreference("pref_key_sms_store_card2");

        if (!SystemProperties.getBoolean("persist.env.mms.smspriority", false)) {
            Preference priorotySettings =  findPreference(SMS_CDMA_PRIORITY);
            PreferenceScreen prefSet = getPreferenceScreen();
            prefSet.removePreference(priorotySettings);
        }

        setMessagePreferences();
    }

    private void restoreDefaultPreferences() {
        PreferenceManager.getDefaultSharedPreferences(this).edit().clear().apply();
        setPreferenceScreen(null);
        // reset the SMSC preference.
        mSmscPrefList.clear();
        mSmscPrefCate.removeAll();
        loadPrefs();
        registerListeners();

        // NOTE: After restoring preferences, the auto delete function (i.e. message recycler)
        // will be turned off by default. However, we really want the default to be turned on.
        // Because all the prefs are cleared, that'll cause:
        // ConversationList.runOneTimeStorageLimitCheckForLegacyMessages to get executed the
        // next time the user runs the Messaging app and it will either turn on the setting
        // by default, or if the user is over the limits, encourage them to turn on the setting
        // manually.
    }

    private void setMessagePreferences() {
        mManageSimPref = findPreference("pref_key_manage_sim_messages");
        mSmsLimitPref = findPreference("pref_key_sms_delete_limit");
        mSmsDeliveryReportPref = findPreference("pref_key_sms_delivery_reports");
        mMmsDeliveryReportPref = findPreference("pref_key_mms_delivery_reports");
        mMmsReadReportPref = findPreference("pref_key_mms_read_reports");
        mMmsLimitPref = findPreference("pref_key_mms_delete_limit");
        mClearHistoryPref = findPreference("pref_key_mms_clear_history");
        mEnableNotificationsPref = (CheckBoxPreference) findPreference(NOTIFICATION_ENABLED);
        mSmsTemplate = findPreference("pref_key_message_template");
        mSmscPrefCate = (PreferenceCategory) findPreference("pref_key_smsc");

        updateSignatureStatus();
        showSmscPref();

        if (!MmsApp.getApplication().getTelephonyManager().hasIccCard()) {
            // No SIM card, remove the SIM-related prefs
            PreferenceCategory smsCategory =
                (PreferenceCategory)findPreference("pref_key_sms_settings");
            smsCategory.removePreference(mManageSimPref);
        }

        if (!MmsConfig.getSMSDeliveryReportsEnabled()) {
            PreferenceCategory smsCategory =
                (PreferenceCategory)findPreference("pref_key_sms_settings");
            smsCategory.removePreference(mSmsDeliveryReportPref);
            if (!MmsApp.getApplication().getTelephonyManager().hasIccCard()) {
                getPreferenceScreen().removePreference(smsCategory);
            }
        }

        if (!MmsConfig.getMmsEnabled()) {
            // No Mms, remove all the mms-related preferences
            PreferenceCategory mmsOptions =
                (PreferenceCategory)findPreference("pref_key_mms_settings");
            getPreferenceScreen().removePreference(mmsOptions);

            PreferenceCategory storageOptions =
                (PreferenceCategory)findPreference("pref_key_storage_settings");
            storageOptions.removePreference(findPreference("pref_key_mms_delete_limit"));
        } else {
            PreferenceCategory mmsOptions =
                    (PreferenceCategory)findPreference("pref_key_mms_settings");
            if (!MmsConfig.getMMSDeliveryReportsEnabled()) {
                mmsOptions.removePreference(mMmsDeliveryReportPref);
            }
            if (!MmsConfig.getMMSReadReportsEnabled()) {
                mmsOptions.removePreference(mMmsReadReportPref);
            }
            // If the phone's SIM doesn't know it's own number, disable group mms.
            if (!MmsConfig.getGroupMmsEnabled() ||
                    TextUtils.isEmpty(MessageUtils.getLocalNumber())) {
                mmsOptions.removePreference(mMmsGroupMmsPref);
            }
        }

        setEnabledNotificationsPref();

        if (SystemProperties.getBoolean("persist.env.mms.savelocation", false)) {
            if (MessageUtils.isMultiSimEnabledMms()) {
                PreferenceCategory storageOptions =
                    (PreferenceCategory)findPreference("pref_key_storage_settings");
                storageOptions.removePreference(mSmsStorePref);

                if (!MessageUtils.hasIccCard(MessageUtils.CARD_SUB1)) {
                    storageOptions.removePreference(mSmsStoreCard1Pref);
                } else {
                    setSmsPreferStoreSummary(MessageUtils.CARD_SUB1);
                }
                if (!MessageUtils.hasIccCard(MessageUtils.CARD_SUB2)) {
                    storageOptions.removePreference(mSmsStoreCard2Pref);
                } else {
                    setSmsPreferStoreSummary(MessageUtils.CARD_SUB2);
                }
            } else {
                PreferenceCategory storageOptions =
                    (PreferenceCategory)findPreference("pref_key_storage_settings");
                storageOptions.removePreference(mSmsStoreCard1Pref);
                storageOptions.removePreference(mSmsStoreCard2Pref);

                if (!MessageUtils.hasIccCard()) {
                    storageOptions.removePreference(mSmsStorePref);
                } else {
                    setSmsPreferStoreSummary();
                }
            }
        } else {
            PreferenceCategory storageOptions =
                    (PreferenceCategory)findPreference("pref_key_storage_settings");
            storageOptions.removePreference(mSmsStorePref);
            storageOptions.removePreference(mSmsStoreCard1Pref);
            storageOptions.removePreference(mSmsStoreCard2Pref);
        }

        // If needed, migrate vibration setting from the previous tri-state setting stored in
        // NOTIFICATION_VIBRATE_WHEN to the boolean setting stored in NOTIFICATION_VIBRATE.
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (sharedPreferences.contains(NOTIFICATION_VIBRATE_WHEN)) {
            String vibrateWhen = sharedPreferences.
                    getString(MessagingPreferenceActivity.NOTIFICATION_VIBRATE_WHEN, null);
            boolean vibrate = "always".equals(vibrateWhen);
            SharedPreferences.Editor prefsEditor = sharedPreferences.edit();
            prefsEditor.putBoolean(NOTIFICATION_VIBRATE, vibrate);
            prefsEditor.remove(NOTIFICATION_VIBRATE_WHEN);  // remove obsolete setting
            prefsEditor.apply();
            mVibratePref.setChecked(vibrate);
        }

        mSmsRecycler = Recycler.getSmsRecycler();
        mMmsRecycler = Recycler.getMmsRecycler();

        // Fix up the recycler's summary with the correct values
        setSmsDisplayLimit();
        setMmsDisplayLimit();
        setMmsExpirySummary();

        String soundValue = sharedPreferences.getString(NOTIFICATION_RINGTONE, null);
        setRingtoneSummary(soundValue);
    }

    private void setRingtoneSummary(String soundValue) {
        Uri soundUri = TextUtils.isEmpty(soundValue) ? null : Uri.parse(soundValue);
        Ringtone tone = soundUri != null ? RingtoneManager.getRingtone(this, soundUri) : null;
        mRingtonePref.setSummary(tone != null ? tone.getTitle(this)
                : getResources().getString(R.string.silent_ringtone));
    }

    private void showSmscPref() {
        int count = MSimTelephonyManager.getDefault().getPhoneCount();
        boolean airplaneModeOn = Settings.System.getInt(getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) != 0;
        for (int i = 0; i < count; i++) {
            final Preference pref = new Preference(this);
            pref.setKey(String.valueOf(i));
            pref.setTitle(getResources().getQuantityString(R.plurals.pref_title_smsc, count,
                    i + 1));

            pref.setOnPreferenceClickListener(new OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    MyEditDialogFragment dialog = MyEditDialogFragment.newInstance(
                            MessagingPreferenceActivity.this,
                            preference.getTitle(),
                            preference.getSummary(),
                            Integer.valueOf(preference.getKey()));
                    dialog.show(getFragmentManager(), "dialog");
                    return true;
                }
            });

            mSmscPrefCate.addPreference(pref);
            mSmscPrefList.add(pref);
            updateSMSCPref(i, airplaneModeOn);
        }
        registerReceiver();
    }

    private void setSmsPreferStoreSummary() {
        mSmsStorePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final String summary = newValue.toString();
                int index = mSmsStorePref.findIndexOfValue(summary);
                mSmsStorePref.setSummary(mSmsStorePref.getEntries()[index]);
                mSmsStorePref.setValue(summary);
                return true;
            }
        });
        mSmsStorePref.setSummary(mSmsStorePref.getEntry());
    }

    private void setSmsPreferStoreSummary(int subscription) {
        if (MessageUtils.CARD_SUB1 == subscription) {
            mSmsStoreCard1Pref.setOnPreferenceChangeListener(
                    new Preference.OnPreferenceChangeListener() {
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    final String summary = newValue.toString();
                    int index = mSmsStoreCard1Pref.findIndexOfValue(summary);
                    mSmsStoreCard1Pref.setSummary(mSmsStoreCard1Pref.getEntries()[index]);
                    mSmsStoreCard1Pref.setValue(summary);
                    return false;
                }
            });
            mSmsStoreCard1Pref.setSummary(mSmsStoreCard1Pref.getEntry());
        } else {
            mSmsStoreCard2Pref.setOnPreferenceChangeListener(
                    new Preference.OnPreferenceChangeListener() {
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    final String summary = newValue.toString();
                    int index = mSmsStoreCard2Pref.findIndexOfValue(summary);
                    mSmsStoreCard2Pref.setSummary(mSmsStoreCard2Pref.getEntries()[index]);
                    mSmsStoreCard2Pref.setValue(summary);
                    //setSmsPreferStorage(Integer.parseInt(summary),MessageUtils.CARD_SUB2);
                    return false;
                }
            });
            mSmsStoreCard2Pref.setSummary(mSmsStoreCard2Pref.getEntry());
        }
    }

    private void setEnabledNotificationsPref() {
        // The "enable notifications" setting is really stored in our own prefs. Read the
        // current value and set the checkbox to match.
        mEnableNotificationsPref.setChecked(getNotificationEnabled(this));
    }

    private void setSmsDisplayLimit() {
        mSmsLimitPref.setSummary(
                getString(R.string.pref_summary_delete_limit,
                        mSmsRecycler.getMessageLimit(this)));
    }

    private void setMmsDisplayLimit() {
        mMmsLimitPref.setSummary(
                getString(R.string.pref_summary_delete_limit,
                        mMmsRecycler.getMessageLimit(this)));
    }

    private void setMmsExpirySummary() {
        mMmsExpiryPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final String value = newValue.toString();
                mMmsExpiryPref.setValue(value);

                if (value.equals(EXPIRY_ONE_WEEK)) {
                    mMmsExpiryPref.setSummary(getString(R.string.mms_one_week));
                } else if (value.equals(EXPIRY_TWO_DAYS)) {
                    mMmsExpiryPref.setSummary(getString(R.string.mms_two_days));
                } else {
                    mMmsExpiryPref.setSummary(getString(R.string.mms_max));
                }
                return false;
            }
        });

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String expiry = prefs.getString("pref_key_mms_expiry", "");

        if (expiry.equals(EXPIRY_ONE_WEEK)) {
            mMmsExpiryPref.setSummary(getString(R.string.mms_one_week));
        } else if (expiry.equals(EXPIRY_TWO_DAYS)) {
            mMmsExpiryPref.setSummary(getString(R.string.mms_two_days));
        } else {
            mMmsExpiryPref.setSummary(getString(R.string.mms_max));
        }
    }

    private void updateSignatureStatus() {
        // If the signature CheckBox is checked, we should set the signature EditText
        // enable, and disable when it's not checked.
        boolean isChecked = mSmsSignaturePref.isChecked();
        mSmsSignatureEditPref.setEnabled(isChecked);
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.clear();
        menu.add(0, MENU_RESTORE_DEFAULTS, 0, R.string.restore_default);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_RESTORE_DEFAULTS:
                restoreDefaultPreferences();
                return true;

            case android.R.id.home:
                // The user clicked on the Messaging icon in the action bar. Take them back from
                // wherever they came from
                finish();
                return true;
        }
        return false;
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen,
            Preference preference) {
        if (preference == mSmsLimitPref) {
            new NumberPickerDialog(this,
                    mSmsLimitListener,
                    mSmsRecycler.getMessageLimit(this),
                    mSmsRecycler.getMessageMinLimit(),
                    mSmsRecycler.getMessageMaxLimit(),
                    R.string.pref_title_sms_delete).show();
        } else if (preference == mMmsLimitPref) {
            new NumberPickerDialog(this,
                    mMmsLimitListener,
                    mMmsRecycler.getMessageLimit(this),
                    mMmsRecycler.getMessageMinLimit(),
                    mMmsRecycler.getMessageMaxLimit(),
                    R.string.pref_title_mms_delete).show();
        } else if (preference == mSmsTemplate) {
            startActivity(new Intent(this, MessageTemplate.class));
        } else if (preference == mManageSimPref) {
            if (!MSimTelephonyManager.getDefault().isMultiSimEnabled()
                    || MessageUtils.getActivatedIccCardCount() < 2) {
                Intent intent = new Intent(this, ManageSimMessages.class);
                if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
                    intent.putExtra(MSimConstants.SUBSCRIPTION_KEY,
                            MessageUtils.isIccCardActivated(MessageUtils.SUB1) ? MessageUtils.SUB1
                                    : MessageUtils.SUB2);
                } else {
                    intent.putExtra(MSimConstants.SUBSCRIPTION_KEY, MessageUtils.SUB_INVALID);
                }
                startActivity(intent);
            } else {
                Intent intent = new Intent(this, SelectSubscription.class);
                intent.putExtra(SelectSubscription.PACKAGE, TARGET_PACKAGE);
                intent.putExtra(SelectSubscription.TARGET_CLASS, TARGET_CLASS);
                startActivity(intent);
            }
        } else if (preference == mClearHistoryPref) {
            showDialog(CONFIRM_CLEAR_SEARCH_HISTORY_DIALOG);
            return true;
        } else if (preference == mEnableNotificationsPref) {
            // Update the actual "enable notifications" value that is stored in secure settings.
            enableNotifications(mEnableNotificationsPref.isChecked(), this);
        } else if (preference == mSmsSignaturePref) {
            updateSignatureStatus();
        } else if (preference == mMmsAutoRetrievialPref) {
            if (mMmsAutoRetrievialPref.isChecked()) {
                startMmsDownload();
            }
        }

        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    /**
     * Trigger the TransactionService to download any outstanding messages.
     */
    private void startMmsDownload() {
        startService(new Intent(TransactionService.ACTION_ENABLE_AUTO_RETRIEVE, null, this,
                TransactionService.class));
    }

    NumberPickerDialog.OnNumberSetListener mSmsLimitListener =
        new NumberPickerDialog.OnNumberSetListener() {
            public void onNumberSet(int limit) {
                mSmsRecycler.setMessageLimit(MessagingPreferenceActivity.this, limit);
                setSmsDisplayLimit();
            }
    };

    NumberPickerDialog.OnNumberSetListener mMmsLimitListener =
        new NumberPickerDialog.OnNumberSetListener() {
            public void onNumberSet(int limit) {
                mMmsRecycler.setMessageLimit(MessagingPreferenceActivity.this, limit);
                setMmsDisplayLimit();
            }
    };

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case CONFIRM_CLEAR_SEARCH_HISTORY_DIALOG:
                return new AlertDialog.Builder(MessagingPreferenceActivity.this)
                    .setTitle(R.string.confirm_clear_search_title)
                    .setMessage(R.string.confirm_clear_search_text)
                    .setPositiveButton(android.R.string.ok, new AlertDialog.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            SearchRecentSuggestions recent =
                                ((MmsApp)getApplication()).getRecentSuggestions();
                            if (recent != null) {
                                recent.clearHistory();
                            }
                            dialog.dismiss();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .create();
        }
        return super.onCreateDialog(id);
    }

    public static boolean getNotificationEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean notificationsEnabled =
            prefs.getBoolean(MessagingPreferenceActivity.NOTIFICATION_ENABLED, true);
        return notificationsEnabled;
    }

    public static void enableNotifications(boolean enabled, Context context) {
        // Store the value of notifications in SharedPreferences
        SharedPreferences.Editor editor =
            PreferenceManager.getDefaultSharedPreferences(context).edit();

        editor.putBoolean(MessagingPreferenceActivity.NOTIFICATION_ENABLED, enabled);

        editor.apply();
    }

    private void registerListeners() {
        mRingtonePref.setOnPreferenceChangeListener(this);
    }

    public boolean onPreferenceChange(Preference preference, Object newValue) {
        boolean result = false;
        if (preference == mRingtonePref) {
            setRingtoneSummary((String)newValue);
            result = true;
        }
        return result;
    }

    // Add this handler to update the ui when AirPlane mode changed
    Handler mAirPlaneModeHandler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            super.handleMessage(msg);
            if (msg.what == AIR_PLANE_MODE_CHANGED) {
                PreferenceCategory smsCategory =
                        (PreferenceCategory) findPreference("pref_key_sms_settings");
                if (msg.arg1 == AIR_PLANE_MODE_ENABLE) {
                    // is AirPlaneMode, remove the SIM-related prefs
                    smsCategory.removePreference(mManageSimPref);
                } else {
                    // Not AirPlaneMode, add the SIM-related prefs
                    smsCategory.addPreference(mManageSimPref);
                }
            }
        };
    };

    private void registerReceiver() {
        if (mReceiver != null) return;
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                    // set the default as the airplane mode is off
                    boolean on = intent.getBooleanExtra("state", false);
                    updateSMSCPref(ALL_SUB, on);
                    Message msg = new Message();
                    msg.what = AIR_PLANE_MODE_CHANGED;
                    msg.arg1 = (on ? AIR_PLANE_MODE_ENABLE : AIR_PLANE_MODE_DISABLE);
                    mAirPlaneModeHandler.sendMessage(msg);
                } else if (NOTIFY_SMSC_ERROR.equals(action)) {
                    showToast(R.string.set_smsc_error);
                } else if (NOTIFY_SMSC_SUCCESS.equals(action)) {
                    showToast(R.string.set_smsc_success);
                } else if (NOTIFY_SMSC_UPDATE.equals(action)) {
                    int sub = intent.getIntExtra(SUB, 0);
                    String summary = intent.getStringExtra(SMSC);
                    mSmscPrefList.get(sub).setSummary(summary);
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(NOTIFY_SMSC_ERROR);
        filter.addAction(NOTIFY_SMSC_SUCCESS);
        filter.addAction(NOTIFY_SMSC_UPDATE);
        registerReceiver(mReceiver, filter);
    }

    private void showToast(int id) {
        Toast.makeText(this, id, Toast.LENGTH_SHORT).show();
    }

    /**
     * Set the SMSC preference enable or disable.
     *
     * @param id  the subscription of the slot, if the value is ALL_SUB, update all the SMSC
     *            preference
     * @param airplaneModeIsOn  the state of the airplane mode
     */
    private void updateSMSCPref(int id, boolean airplaneModeIsOn) {
        if (mSmscPrefList == null || mSmscPrefList.size() < 1) return;

        int count = MSimTelephonyManager.getDefault().getPhoneCount();
        boolean multiSimEnable = count > 1;
        MSimTelephonyManager multiTm = null;
        TelephonyManager tm = null;

        if (!airplaneModeIsOn) {
            if (multiSimEnable) {
                multiTm = (MSimTelephonyManager) getSystemService(Context.MSIM_TELEPHONY_SERVICE);
            } else {
                tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            }
        }

        switch (id) {
            case ALL_SUB:
                for (int i = 0; i < count; i++) {
                    boolean enabled = !airplaneModeIsOn && (multiSimEnable ? multiTm.hasIccCard(i)
                            : tm.hasIccCard());
                    setSMSCPrefState(i, enabled);
                }
                break;
            default:
                boolean enabled = !airplaneModeIsOn && (multiSimEnable ? multiTm.hasIccCard(id)
                        : tm.hasIccCard());
                setSMSCPrefState(id, enabled);
                break;
        }
    }

    private void setSMSCPrefState(int id, boolean prefEnabled) {
        // We need update the preference summary.
        if (prefEnabled) {
            Intent get = new Intent();
            get.setComponent(new ComponentName("com.android.phonefeature",
                    "com.android.phonefeature.smsc.SmscService"));
            get.setAction(COMMAND_GET_SMSC);
            get.putExtra(SUB, id);
            startService(get);
        } else {
            mSmscPrefList.get(id).setSummary(null);
        }
        mSmscPrefList.get(id).setEnabled(prefEnabled);
    }

    public static class MyEditDialogFragment extends DialogFragment {
        private MessagingPreferenceActivity mActivity;

        public static MyEditDialogFragment newInstance(MessagingPreferenceActivity activity,
                CharSequence title, CharSequence smsc, int sub) {
            MyEditDialogFragment dialog = new MyEditDialogFragment();
            dialog.mActivity = activity;

            Bundle args = new Bundle();
            args.putCharSequence(TITLE, title);
            args.putCharSequence(SMSC, smsc);
            args.putInt(SUB, sub);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final int sub = getArguments().getInt(SUB);
            if (null == mActivity) {
                mActivity = (MessagingPreferenceActivity) getActivity();
                dismiss();
            }
            final EditText edit = new EditText(mActivity);
            edit.setPadding(15, 15, 15, 15);
            edit.setText(getArguments().getCharSequence(SMSC));

            Dialog alert = new AlertDialog.Builder(mActivity)
                    .setTitle(getArguments().getCharSequence(TITLE))
                    .setView(edit)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            MyAlertDialogFragment newFragment = MyAlertDialogFragment.newInstance(
                                    mActivity, sub, edit.getText().toString());
                            newFragment.show(getFragmentManager(), "dialog");
                            dismiss();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .setCancelable(true)
                    .create();
            alert.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            return alert;
        }
    }

    // All subclasses of Fragment must include a public empty constructor. The
    // framework will often re-instantiate a fragment class when needed, in
    // particular during state restore, and needs to be able to find this
    // constructor to instantiate it. If the empty constructor is not available,
    // a runtime exception will occur in some cases during state restore.
    public static class MyAlertDialogFragment extends DialogFragment {
        private MessagingPreferenceActivity mActivity;

        public static MyAlertDialogFragment newInstance(MessagingPreferenceActivity activity,
                                                        int sub, String smsc) {
            MyAlertDialogFragment dialog = new MyAlertDialogFragment();
            dialog.mActivity = activity;

            Bundle args = new Bundle();
            args.putInt(SUB, sub);
            args.putString(SMSC, smsc);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final int sub = getArguments().getInt(SUB);
            final String displayedSMSC = getArguments().getString(SMSC);

            // When framework re-instantiate this fragment by public empty
            // constructor and call onCreateDialog(Bundle savedInstanceState) ,
            // we should make sure mActivity not null.
            if (null == mActivity) {
                mActivity = (MessagingPreferenceActivity) getActivity();
            }

            return new AlertDialog.Builder(mActivity)
                    .setIcon(android.R.drawable.ic_dialog_alert).setMessage(
                            R.string.set_smsc_confirm_message)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            Intent intent = new Intent();
                            intent.setComponent(new ComponentName("com.android.phonefeature",
                                    "com.android.phonefeature.smsc.SmscService"));
                            intent.setAction(COMMAND_SET_SMSC);
                            intent.putExtra(SUB, sub);
                            intent.putExtra(SMSC, displayedSMSC);
                            mActivity.startService(intent);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .setCancelable(true)
                    .create();
        }
    }

    // For the group mms feature to be enabled, the following must be true:
    //  1. the feature is enabled in mms_config.xml (currently on by default)
    //  2. the feature is enabled in the mms settings page
    //  3. the SIM knows its own phone number
    public static boolean getIsGroupMmsEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean groupMmsPrefOn = prefs.getBoolean(
                MessagingPreferenceActivity.GROUP_MMS_MODE, true);
        return MmsConfig.getGroupMmsEnabled() &&
                groupMmsPrefOn &&
                !TextUtils.isEmpty(MessageUtils.getLocalNumber());
    }
}
