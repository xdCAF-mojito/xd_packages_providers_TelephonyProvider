/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (C) 2010-2012, The Linux Foundation. All rights reserved.
 *
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

package com.android.providers.telephony;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.UriMatcher;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.Contacts;
import android.provider.Telephony;
import android.provider.Telephony.Mms;
import android.provider.Telephony.MmsSms;
import android.provider.Telephony.Sms;
import android.provider.Telephony.TextBasedSmsColumns;
import android.provider.Telephony.Threads;
import android.telephony.MSimSmsManager;
import android.telephony.MSimTelephonyManager;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import android.text.format.Time;

import java.util.ArrayList;
import java.util.HashMap;

public class SmsProvider extends ContentProvider {
    private static final Uri NOTIFICATION_URI = Uri.parse("content://sms");
    private static final Uri ICC_URI = Uri.parse("content://sms/icc");
    private static final Uri ICC1_URI = Uri.parse("content://sms/icc1");
    private static final Uri ICC2_URI = Uri.parse("content://sms/icc2");
    static final String TABLE_SMS = "sms";
    static final String TABLE_ICC_SMS = "iccsms";
    private static final String TABLE_RAW = "raw";
    private static final String TABLE_SR_PENDING = "sr_pending";
    private static final String TABLE_WORDS = "words";

    /** Free space (TS 51.011 10.5.3). */
    static public final int STATUS_ON_SIM_FREE      = 0;
    /** Received and read (TS 51.011 10.5.3). */
    static public final int STATUS_ON_SIM_READ      = 1;
    /** Received and unread (TS 51.011 10.5.3). */
    static public final int STATUS_ON_SIM_UNREAD    = 3;
    /** Stored and sent (TS 51.011 10.5.3). */
    static public final int STATUS_ON_SIM_SENT      = 5;
    /** Stored and unsent (TS 51.011 10.5.3). */
    static public final int STATUS_ON_SIM_UNSENT    = 7;

    private static final Integer ONE = Integer.valueOf(1);

    private static final String[] CONTACT_QUERY_PROJECTION =
            new String[] { Contacts.Phones.PERSON_ID };
    private static final int PERSON_ID_COLUMN = 0;
    private static boolean mHasReadIcc = false;
    private static boolean mHasReadIcc1 = false;
    private static boolean mHasReadIcc2 = false;

    /**
     * These are the columns that are available when reading SMS
     * messages from the ICC.  Columns whose names begin with "is_"
     * have either "true" or "false" as their values.
     */
    private final static String[] ICC_COLUMNS = new String[] {
        // N.B.: These columns must appear in the same order as the
        // calls to add appear in convertIccToSms.
        "service_center_address",       // getServiceCenterAddress
        "address",                      // getDisplayOriginatingAddress
        "message_class",                // getMessageClass
        "body",                         // getDisplayMessageBody
        "date",                         // getTimestampMillis
        "status",                       // getStatusOnIcc
        "index_on_icc",                 // getIndexOnIcc
        "is_status_report",             // isStatusReportMessage
        "transport_type",               // Always "sms".
        "type",                         // Always MESSAGE_TYPE_ALL.
        "locked",                       // Always 0 (false).
        "error_code",                   // Always 0
        "_id",
        "sub_id"
    };

    @Override
    public boolean onCreate() {
        mOpenHelper = MmsSmsDatabaseHelper.getInstance(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri url, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        if (true || Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, "query : url = " + url);
        }

        // Generate the body of the query.
        int match = sURLMatcher.match(url);
        switch (match) {
            case SMS_ALL:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_ALL);
                break;

            case SMS_UNDELIVERED:
                constructQueryForUndelivered(qb);
                break;

            case SMS_FAILED:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_FAILED);
                break;

            case SMS_QUEUED:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_QUEUED);
                break;

            case SMS_INBOX:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_INBOX);
                break;

            case SMS_SENT:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_SENT);
                break;

            case SMS_DRAFT:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_DRAFT);
                break;

            case SMS_OUTBOX:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_OUTBOX);
                break;

            case SMS_INBOX_SUB1:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_INBOX_SUB1);
                break;

            case SMS_INBOX_SUB2:
                constructQueryForBox(qb, Sms.MESSAGE_TYPE_INBOX_SUB2);
                break;

            case SMS_ALL_ID:
                qb.setTables(TABLE_SMS);
                qb.appendWhere("(_id = " + url.getPathSegments().get(0) + ")");
                break;

            case SMS_INBOX_ID:
            case SMS_FAILED_ID:
            case SMS_SENT_ID:
            case SMS_DRAFT_ID:
            case SMS_OUTBOX_ID:
                qb.setTables(TABLE_SMS);
                qb.appendWhere("(_id = " + url.getPathSegments().get(1) + ")");
                break;

            case SMS_CONVERSATIONS_ID:
                int threadID;

                try {
                    threadID = Integer.parseInt(url.getPathSegments().get(1));
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.d(TAG, "query conversations: threadID=" + threadID);
                    }
                }
                catch (Exception ex) {
                    Log.e(TAG,
                          "Bad conversation thread id: "
                          + url.getPathSegments().get(1));
                    return null;
                }

                qb.setTables(TABLE_SMS);
                qb.appendWhere("thread_id = " + threadID);
                break;

            case SMS_CONVERSATIONS:
                qb.setTables("sms, (SELECT thread_id AS group_thread_id, MAX(date)AS group_date,"
                       + "COUNT(*) AS msg_count FROM sms GROUP BY thread_id) AS groups");
                qb.appendWhere("sms.thread_id = groups.group_thread_id AND sms.date ="
                       + "groups.group_date");
                qb.setProjectionMap(sConversationProjectionMap);
                break;

            case SMS_RAW_MESSAGE:
                qb.setTables("raw");
                break;

            case SMS_STATUS_PENDING:
                qb.setTables("sr_pending");
                break;

            case SMS_ATTACHMENT:
                qb.setTables("attachments");
                break;

            case SMS_ATTACHMENT_ID:
                qb.setTables("attachments");
                qb.appendWhere(
                        "(sms_id = " + url.getPathSegments().get(1) + ")");
                break;

            case SMS_QUERY_THREAD_ID:
                qb.setTables("canonical_addresses");
                if (projectionIn == null) {
                    projectionIn = sIDProjection;
                }
                break;

            case SMS_STATUS_ID:
                qb.setTables(TABLE_SMS);
                qb.appendWhere("(_id = " + url.getPathSegments().get(1) + ")");
                break;

            case SMS_ALL_ICC:
                return getAllMessagesFromIcc();

            case SMS_ICC: {
                String messageIndexString = url.getPathSegments().get(1);
                return getSingleMessageFromIcc(messageIndexString);
            }

            case SMS_ALL_ICC1:
                return getAllMessagesFromIcc(SUB1);

            case SMS_ICC1: {
                String messageIndexString = url.getPathSegments().get(1);
                return getSingleMessageFromIcc(messageIndexString, SUB1);
                }

            case SMS_ALL_ICC2:
                return getAllMessagesFromIcc(SUB2);

            case SMS_ICC2: {
                String messageIndexString = url.getPathSegments().get(1);
                return getSingleMessageFromIcc(messageIndexString, SUB2);
                }

            default:
                Log.e(TAG, "Invalid request: " + url);
                return null;
        }

        String orderBy = null;

        if (!TextUtils.isEmpty(sort)) {
            orderBy = sort;
        } else if (qb.getTables().equals(TABLE_SMS)) {
            orderBy = Sms.DEFAULT_SORT_ORDER;
        }

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor ret = qb.query(db, projectionIn, selection, selectionArgs,
                              null, null, orderBy);

        // TODO: Since the URLs are a mess, always use content://sms
        ret.setNotificationUri(getContext().getContentResolver(),
                NOTIFICATION_URI);
        return ret;
    }

    private Object[] convertIccToSms(SmsMessage message, int id) {
        return convertIccToSms(message, id, MSimSmsManager.getDefault().getPreferredSmsSubscription());
    }
    
    private Object[] convertIccToSms(SmsMessage message, int id, int subscription) {
        int statusOnIcc = message.getStatusOnIcc();
        int type = Sms.MESSAGE_TYPE_ALL;
        switch (statusOnIcc) {
            case SmsManager.STATUS_ON_ICC_READ:
            case SmsManager.STATUS_ON_ICC_UNREAD:
                type = Sms.MESSAGE_TYPE_INBOX;
                break;
            case SmsManager.STATUS_ON_ICC_SENT:
                type = Sms.MESSAGE_TYPE_SENT;
                break;
            case SmsManager.STATUS_ON_ICC_UNSENT:
                type = Sms.MESSAGE_TYPE_OUTBOX;
                break;
        }

        String displayOriginatingAddress = message.getDisplayOriginatingAddress();
        // N.B.: These calls must appear in the same order as the
        // columns appear in ICC_COLUMNS.
        Object[] row = new Object[14];
        row[0] = message.getServiceCenterAddress();
        //row[1] = (type == Sms.MESSAGE_TYPE_INBOX) ? displayOriginatingAddress
        //            : message.getRecipientddress();
        row[1] = displayOriginatingAddress;
        row[2] = String.valueOf(message.getMessageClass());
        row[3] = message.getDisplayMessageBody();
        row[4] = message.getTimestampMillis();
        row[5] = message.getStatusOnIcc();//Sms.STATUS_NONE;
        row[6] = message.getIndexOnIcc();
        row[7] = message.isStatusReportMessage();
        row[8] = "sms";
        row[9] = type;
        row[10] = 0;      // locked
        row[11] = 0;      // error_code
        row[12] = id;
        row[13] = subscription;
        return row;
    }

    private Uri getIccUri(int subscription) {
        switch (subscription) {
            case SUB1:
                return ICC1_URI;
            case SUB2:
                return ICC2_URI;
            default:
                Log.e(TAG, "Invalid subscription: " + subscription);
                return ICC_URI;
        }
    }

    /**
     * Return a Cursor listing all the messages stored on the ICC.
     */
    private Cursor getAllMessagesFromIcc() {
        return getAllMessagesFromIcc(SUB_INVALID, ICC_URI);
    }

    /**
     * Return a Cursor containing just one message from the ICC.
     */
    private Cursor getSingleMessageFromIcc(String messageIndexString) {
        return getSingleMessageFromIcc(messageIndexString,
                    SUB_INVALID, ICC_URI);
    }

    /**
     * Return a Cursor containing just one message from the ICC.
     */
    private Cursor getSingleMessageFromIcc(String messageIndexString, int subscription) {
        return getSingleMessageFromIcc(messageIndexString,
                    subscription, getIccUri(subscription));
    }

    /**
     * Return a Cursor containing just one message from the ICC.
     */
    private Cursor getSingleMessageFromIcc(String messageIndexString, int subscription, Uri iccUri) {
        try {
            int messageIndex = Integer.parseInt(messageIndexString);
            ArrayList<SmsMessage> messages;
            if (TelephonyManager.getDefault().isMultiSimEnabled()) {
                MSimSmsManager smsManager = MSimSmsManager.getDefault();
                messages = smsManager.getAllMessagesFromIcc(subscription);
            } else {
                messages = SmsManager.getAllMessagesFromIcc();
            }

            SmsMessage message = messages.get(messageIndex);
            if (message == null) {
                throw new IllegalArgumentException(
                        "Message not retrieved. ID: " + messageIndexString);
            }
            MatrixCursor cursor = new MatrixCursor(ICC_COLUMNS, 1);
            cursor.addRow(convertIccToSms(message, 0, subscription));
            return withIccNotificationUri(cursor,iccUri);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Bad SMS ICC ID: " + messageIndexString);
        }
    }

    /**
     * Return a Cursor listing all the messages stored on the ICC.
     */
    private Cursor getAllMessagesFromIcc(int subscription, Uri iccUri) {
        
        ArrayList<SmsMessage> messages = null;
        if (TelephonyManager.getDefault().isMultiSimEnabled()) {
            if((subscription == SUB1 && !mHasReadIcc1)
                || (subscription == SUB2 && !mHasReadIcc2))
            {
                
                MSimSmsManager smsManager = MSimSmsManager.getDefault();
                messages = smsManager.getAllMessagesFromIcc(subscription);
                Log.d(TAG, "getAllMessagesFromIcc : messages.size() ="+messages.size());
                if(messages.size() != 0)
                {
                    if(subscription == SUB1)
                    {
                        mHasReadIcc1 = true;
                    }
                    else if(subscription == SUB2)
                    {
                        mHasReadIcc2 = true;
                    }
                }

            }
        } else {
            if(!mHasReadIcc)
            {
                messages = SmsManager.getAllMessagesFromIcc();
                if(messages.size() != 0)
                {
                    mHasReadIcc = true;
                }                   

            }
        }

        if(messages != null)
        {
            final int count = messages.size();
            //MatrixCursor cursor = new MatrixCursor(ICC_COLUMNS, count);
            for (int i = 0; i < count; i++) {
                SmsMessage message = messages.get(i);
                if (message != null) {
                    //cursor.addRow(convertIccToSms(message, i, subscription));
                    insertSmsMessageToIccDatabase(message, subscription);
                }
            }
        }

        return withIccNotificationUri(querySmsOnIccDatabase(subscription, iccUri), iccUri);
    }

    private Cursor querySmsOnIccDatabase(int subscription, Uri iccUri)
    {
        String selectionStr = null;
        if(TelephonyManager.getDefault().isMultiSimEnabled())
        {
            selectionStr = "sub_id = " + subscription;
        }
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor ret = db.query(TABLE_ICC_SMS, null, selectionStr, null,
                              null, null, Sms.DEFAULT_SORT_ORDER);
        ret.setNotificationUri(getContext().getContentResolver(), iccUri);
        return ret;
    }
    
    private Uri insertSmsMessageToIccDatabase(SmsMessage smsMessage, int subscription)
    {
        ContentValues values = new ContentValues(1);
        String address = null;       
        int index = smsMessage.getIndexOnIcc();
        int status = smsMessage.getStatusOnSim();

        int mailboxId = TextBasedSmsColumns.MESSAGE_TYPE_ALL;
        if (status == STATUS_ON_SIM_READ
            || status == STATUS_ON_SIM_UNREAD)
        {
            address = smsMessage.getDisplayOriginatingAddress();
            mailboxId = TextBasedSmsColumns.MESSAGE_TYPE_INBOX;
        }
        else if (status == STATUS_ON_SIM_SENT)
        {
            address = smsMessage.getRecipientddress();
            mailboxId = TextBasedSmsColumns.MESSAGE_TYPE_SENT;
        }
        else
        {
            address = smsMessage.getRecipientddress();
            mailboxId = TextBasedSmsColumns.MESSAGE_TYPE_DRAFT;
        }

        values.put("service_center_address", smsMessage.getServiceCenterAddress());
        values.put(Sms.ADDRESS, address);
        values.put("message_class", String.valueOf(smsMessage.getMessageClass()));
        values.put(Sms.BODY, smsMessage.getDisplayMessageBody());
        values.put(Sms.DATE, smsMessage.getTimestampMillis()== 0 ? new Long(System.currentTimeMillis()): smsMessage.getTimestampMillis());
        values.put(Sms.STATUS, status);
        values.put("is_status_report", -1);        
        values.put("transport_type", "sms");
        values.put(Sms.TYPE, mailboxId);
        values.put("status_on_icc", status);

        return insertMessageToIccDatabase(index, values, subscription);
    }

    /**
      * Insert the message at index from SIM Sms Cache. 
      * Return the inserted uri if successful.
      */
    private Uri insertMessageToIccDatabase(int index,
                                           ContentValues values, int subscription)
    {
        if (index < 0)
        {
            return null;
        }
        String table = TABLE_ICC_SMS;
        long rowID;
        
        values.put(Sms.SUB_ID, subscription);  // -1 for SUB_INVALID , 0 for SUB1, 1 for SUB2
        values.put("index_on_icc", index);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        rowID = db.insert(table, "body", values);
        if (rowID > 0)
        {
            Uri uri = Uri.parse("content://sms/" + table + "/" + rowID);
            ContentResolver cr = getContext().getContentResolver();
            cr.notifyChange(uri, null);
            if (subscription == SUB1)
            {
                cr.notifyChange(ICC1_URI, null);
            }
            else if (subscription == SUB2)
            {
                cr.notifyChange(ICC2_URI, null);
            }
            else
            {
                cr.notifyChange(ICC_URI, null);
            }

            return uri;
        }
        else
        {
            Log.e(TAG, "insertMessageToIccDatabase : failed! " + values.toString());
            return null;
        }
    }

    private Cursor getAllMessagesFromIcc(int subscription) {
        return getAllMessagesFromIcc(subscription, getIccUri(subscription));
    }

    private Cursor withIccNotificationUri(Cursor cursor, Uri iccUri) {
        cursor.setNotificationUri(getContext().getContentResolver(), iccUri);
        return cursor;
    }

    private void constructQueryForBox(SQLiteQueryBuilder qb, int type) {
        qb.setTables(TABLE_SMS);

        if (type != Sms.MESSAGE_TYPE_ALL) {
            qb.appendWhere("type=" + type);
        }
    }

    private void constructQueryForUndelivered(SQLiteQueryBuilder qb) {
        qb.setTables(TABLE_SMS);

        qb.appendWhere("(type=" + Sms.MESSAGE_TYPE_OUTBOX +
                       " OR type=" + Sms.MESSAGE_TYPE_FAILED +
                       " OR type=" + Sms.MESSAGE_TYPE_QUEUED + ")");
    }

    @Override
    public String getType(Uri url) {
        switch (url.getPathSegments().size()) {
        case 0:
            return VND_ANDROID_DIR_SMS;
            case 1:
                try {
                    Integer.parseInt(url.getPathSegments().get(0));
                    return VND_ANDROID_SMS;
                } catch (NumberFormatException ex) {
                    return VND_ANDROID_DIR_SMS;
                }
            case 2:
                // TODO: What about "threadID"?
                if (url.getPathSegments().get(0).equals("conversations")) {
                    return VND_ANDROID_SMSCHAT;
                } else {
                    return VND_ANDROID_SMS;
                }
        }
        return null;
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues) {
        ContentValues values;
        long rowID;
        int type = Sms.MESSAGE_TYPE_ALL;

        int match = sURLMatcher.match(url);
        String table = TABLE_SMS;

        switch (match) {
            case SMS_ALL:
                Integer typeObj = initialValues.getAsInteger(Sms.TYPE);
                if (typeObj != null) {
                    type = typeObj.intValue();
                } else {
                    // default to inbox
                    type = Sms.MESSAGE_TYPE_INBOX;
                }
                break;

            case SMS_INBOX:
                type = Sms.MESSAGE_TYPE_INBOX;
                break;

            case SMS_INBOX_SUB1:
                type = Sms.MESSAGE_TYPE_INBOX_SUB1;
                break;

            case SMS_INBOX_SUB2:
                type = Sms.MESSAGE_TYPE_INBOX_SUB2;
                break;

            case SMS_FAILED:
                type = Sms.MESSAGE_TYPE_FAILED;
                break;

            case SMS_QUEUED:
                type = Sms.MESSAGE_TYPE_QUEUED;
                break;

            case SMS_SENT:
                type = Sms.MESSAGE_TYPE_SENT;
                break;

            case SMS_DRAFT:
                type = Sms.MESSAGE_TYPE_DRAFT;
                break;

            case SMS_OUTBOX:
                type = Sms.MESSAGE_TYPE_OUTBOX;
                break;

            case SMS_RAW_MESSAGE:
                table = "raw";
                break;
            case SMS_STATUS_PENDING:
                table = "sr_pending";
                break;

            case SMS_ATTACHMENT:
                table = "attachments";
                break;

            case SMS_NEW_THREAD_ID:
                table = "canonical_addresses";
                break;

            case SMS_ALL_ICC:
                return insertSmsToCard(initialValues, SUB_INVALID);  

            case SMS_ALL_ICC1:
                return insertSmsToCard(initialValues, SUB1);

            case SMS_ALL_ICC2:
                return insertSmsToCard(initialValues, SUB2);

            default:
                Log.e(TAG, "Invalid request: " + url);
                return null;
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        if (table.equals(TABLE_SMS)) {
            boolean addDate = false;
            boolean addType = false;

            // Make sure that the date and type are set
            if (initialValues == null) {
                values = new ContentValues(1);
                addDate = true;
                addType = true;
            } else {
                values = new ContentValues(initialValues);

                if (!initialValues.containsKey(Sms.DATE)) {
                    addDate = true;
                }

                if (!initialValues.containsKey(Sms.TYPE)) {
                    addType = true;
                }
            }

            if (addDate) {
                values.put(Sms.DATE, new Long(System.currentTimeMillis()));
            }

            if (addType && (type != Sms.MESSAGE_TYPE_ALL)) {
                values.put(Sms.TYPE, Integer.valueOf(type));
            }

            // thread_id
            Long threadId = values.getAsLong(Sms.THREAD_ID);
            String address = values.getAsString(Sms.ADDRESS);

            if (((threadId == null) || (threadId == 0)) && (!TextUtils.isEmpty(address))) {
                values.put(Sms.THREAD_ID, Threads.getOrCreateThreadId(
                                   getContext(), address));
            }

            // If this message is going in as a draft, it should replace any
            // other draft messages in the thread.  Just delete all draft
            // messages with this thread ID.  We could add an OR REPLACE to
            // the insert below, but we'd have to query to find the old _id
            // to produce a conflict anyway.
            if (values.getAsInteger(Sms.TYPE) == Sms.MESSAGE_TYPE_DRAFT) {
                db.delete(TABLE_SMS, "thread_id=? AND type=?",
                        new String[] { values.getAsString(Sms.THREAD_ID),
                                       Integer.toString(Sms.MESSAGE_TYPE_DRAFT) });
            }

            // Give the sms preferred sub id for third party app.
            if (values.getAsInteger(Sms.SUB_ID) == null) {
                values.put(Sms.SUB_ID, MSimSmsManager.getDefault().getPreferredSmsSubscription());
            }

            if (type == Sms.MESSAGE_TYPE_INBOX) {
                // Look up the person if not already filled in.
                if ((values.getAsLong(Sms.PERSON) == null) && (!TextUtils.isEmpty(address))) {
                    Cursor cursor = null;
                    Uri uri = Uri.withAppendedPath(Contacts.Phones.CONTENT_FILTER_URL,
                            Uri.encode(address));
                    try {
                        cursor = getContext().getContentResolver().query(
                                uri,
                                CONTACT_QUERY_PROJECTION,
                                null, null, null);

                        if (cursor.moveToFirst()) {
                            Long id = Long.valueOf(cursor.getLong(PERSON_ID_COLUMN));
                            values.put(Sms.PERSON, id);
                        }
                    } catch (Exception ex) {
                        Log.e(TAG, "insert: query contact uri " + uri + " caught ", ex);
                    } finally {
                        if (cursor != null) {
                            cursor.close();
                        }
                    }
                }
            } else {
                // Mark all non-inbox messages read.
                values.put(Sms.READ, ONE);
            }
        } else {
            if (initialValues == null) {
                values = new ContentValues(1);
            } else {
                values = initialValues;
            }
        }

        rowID = db.insert(table, "body", values);

        // Don't use a trigger for updating the words table because of a bug
        // in FTS3.  The bug is such that the call to get the last inserted
        // row is incorrect.
        if (table == TABLE_SMS) {
            // Update the words table with a corresponding row.  The words table
            // allows us to search for words quickly, without scanning the whole
            // table;
            ContentValues cv = new ContentValues();
            cv.put(Telephony.MmsSms.WordsTable.ID, rowID);
            cv.put(Telephony.MmsSms.WordsTable.INDEXED_TEXT, values.getAsString("body"));
            cv.put(Telephony.MmsSms.WordsTable.SOURCE_ROW_ID, rowID);
            cv.put(Telephony.MmsSms.WordsTable.TABLE_ID, 1);
            db.insert(TABLE_WORDS, Telephony.MmsSms.WordsTable.INDEXED_TEXT, cv);
        }
        if (rowID > 0) {
            Uri uri = Uri.parse("content://" + table + "/" + rowID);

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "insert " + uri + " succeeded");
            }
            notifyChange(uri);
            return uri;
        } else {
            Log.e(TAG,"insert: failed! " + values.toString());
        }

        return null;
    }

    /**
      * Insert the message at index from SIM.  Return the Uri.
      */
    private Uri insertSmsToCard(ContentValues values, int subscription)
    {
        ContentValues modValues = new ContentValues(values);
        Uri iccUri = ICC_URI;

        if(TelephonyManager.getDefault().isMultiSimEnabled())
        {
            if(subscription == SUB1 && mHasReadIcc1)
            {
                iccUri = ICC1_URI;
            }
            else if(subscription == SUB2 && mHasReadIcc2)
            {
                iccUri = ICC2_URI;
            }
            else
            {
                return null;
            }
        }
        else
        {
            if(!mHasReadIcc)
            {
                return null;
            }
        }

        int validIccSmsCount = getValidSmsCount(subscription);
        int iccSmsCountAll = -1;

        if(TelephonyManager.getDefault().isMultiSimEnabled())
        {
            iccSmsCountAll = MSimSmsManager.getDefault().getSmsCapCountOnIcc(subscription);
        }
        else
        {
            iccSmsCountAll = SmsManager.getDefault().getSmsCapCountOnIcc();
        }

        Log.d(TAG, "insertSmsToCard: validIccSmsCount = "
            +validIccSmsCount + ", iccSmsCountAll = " + iccSmsCountAll);
        
        if (iccSmsCountAll <= 0)
        {
            return null;
        }
        if (validIccSmsCount >= iccSmsCountAll)
        {
            return Uri.parse("content://sms/sim/full/failure");
        }
        Long date = values.getAsLong(Sms.DATE);
        String body = values.getAsString(Sms.BODY);        
        int type = values.getAsInteger(Sms.TYPE);
        String address = values.getAsString(Sms.ADDRESS);
        int read = values.getAsInteger(Sms.READ);
        int subId = values.getAsInteger(Sms.SUB_ID);  // -1 for SUB_INVALID , 0 for SUB1, 1 for SUB2 
        
        try
        {
            byte[] smsPdu = null;
            byte[] smscPdu = null;
            int status = STATUS_ON_SIM_READ;
            if (type == Sms.MESSAGE_TYPE_INBOX)
            {
                Time then = new Time();
                then.set(date);
                byte[] datepdu = formatDateToPduGSM(then);

                if (read == 0)
                {
                    status = STATUS_ON_SIM_UNREAD;
                }

                SmsMessage.DeliveryPdu pdus = SmsMessage.getDeliveryPdu(null, 
                    address, body, false, null, datepdu, subId);

                if (pdus == null)
                {
                    return null;
                }
                smsPdu = pdus.encodedMessage;
                smscPdu = pdus.encodedScAddress;
            }
            else if (type == Sms.MESSAGE_TYPE_SENT)
            {
                Time then = new Time();
                then.set(date);
                byte[] datepdu = formatDateToPduGSM(then);

                SmsMessage.SubmitPdu pdus = SmsMessage.getSubmitPdu(null, 
                    address, body, false, datepdu, subId);
                
                if (pdus == null)
                {
                    return null;
                }
                smsPdu = pdus.encodedMessage;
                smscPdu = pdus.encodedScAddress;
                status = STATUS_ON_SIM_SENT;
            }
            else
            {
                SmsMessage.SubmitPdu pdus = SmsMessage.getSubmitPdu(null, 
                    address, body, false, null, subId);

                if (pdus == null)
                {
                    return null;
                }
                smsPdu = pdus.encodedMessage;
                smscPdu = pdus.encodedScAddress;
                status = STATUS_ON_SIM_UNSENT;
            }

            if (smscPdu == null)
            {
                smscPdu = getSmsCenterZero();
            }

            int cmgwIndex = -1;
            if(TelephonyManager.getDefault().isMultiSimEnabled())
            {
                cmgwIndex = MSimSmsManager.getDefault().copyMessageToIccGetIndex(null, smsPdu, status, subId);
            }
            else
            {
                cmgwIndex = SmsManager.getDefault().copyMessageToIccGetIndex(null, smsPdu, status);
            }

            Log.d(TAG, "insertSmsToCard: cmgwIndex = " + cmgwIndex);
            if (cmgwIndex < 0)
            {
                return null;
            }

            ByteArrayOutputStream bo = new ByteArrayOutputStream(smsPdu.length + smscPdu.length);
            bo.write(smscPdu, 0, smscPdu.length);
            bo.write(smsPdu, 0, smsPdu.length);

            modValues.remove(Sms.READ);
            modValues.put("status_on_icc", status);
            insertMessageToIccDatabase(cmgwIndex, modValues, subId);
                
            if ((validIccSmsCount + 1) == iccSmsCountAll)
            {
                return Uri.parse("content://sms/sim/full/success");
            }
            else
            {
                return Uri.parse("content://sms/sim");
            }

            }
            catch (NumberFormatException exception)
            {
                throw new IllegalArgumentException(
                "Bad SMS SIM ID: ");
            }
            finally
            {
                ContentResolver cr = getContext().getContentResolver();
                cr.notifyChange(iccUri, null);
        }
    }    

    private byte[] formatDateToPduGSM(Time then)
    {
        byte tArr[];
        tArr = new byte[7];

        tArr[0] = (byte)((then.year > 2000)?(then.year - 2000):(then.year - 1900));
        tArr[1] = (byte)(then.month + 1);
        tArr[2] = (byte)then.monthDay;
        tArr[3] = (byte)then.hour;
        tArr[4] = (byte)then.minute;
        tArr[5] = (byte)then.second;
        tArr[6] = (byte)0x00;
        for (int i = 0; i < 7; i++)
        {
            tArr[i] = (byte) ((((tArr[i]/10)%10) & 0x0F)
                              | (((tArr[i]%10) & 0x0F)<<4));
        }

        return tArr;
    }    

    //return 0x0000 if sms center is null
    private byte[] getSmsCenterZero()
    {
        byte tArr[];
        tArr = new byte[1];
        tArr[0] = (byte)0x00;
        return tArr;
    }

    private int getValidSmsCount(int subscription)
    {
        int msgCount = 0;
        String unionQuery = "select count(_id) AS count, 1 AS _id "
            + "from " + TABLE_ICC_SMS;
        
        if(TelephonyManager.getDefault().isMultiSimEnabled())
        {
            unionQuery += " where sub_id = " + subscription;
        }

        Cursor c = mOpenHelper.getReadableDatabase().rawQuery(unionQuery, new String[0]);

        if (c == null)
        {
            return msgCount;
        }
        if (c.moveToFirst())
        {
            msgCount = c.getInt(0);
            c.close();
            return msgCount;
        }
        c.close();
        return msgCount;
    }
    
    @Override
    public int delete(Uri url, String where, String[] whereArgs) {
        int count;
        int match = sURLMatcher.match(url);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        switch (match) {
            case SMS_ALL:
                count = db.delete(TABLE_SMS, where, whereArgs);
                if (count != 0) {
                    // Don't update threads unless something changed.
                    MmsSmsDatabaseHelper.updateAllThreads(db, where, whereArgs);
                }
                break;

            case SMS_ALL_ID:
                try {
                    int message_id = Integer.parseInt(url.getPathSegments().get(0));
                    count = MmsSmsDatabaseHelper.deleteOneSms(db, message_id);
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                        "Bad message id: " + url.getPathSegments().get(0));
                }
                break;

            case SMS_CONVERSATIONS_ID:
                int threadID;

                try {
                    threadID = Integer.parseInt(url.getPathSegments().get(1));
                } catch (Exception ex) {
                    throw new IllegalArgumentException(
                            "Bad conversation thread id: "
                            + url.getPathSegments().get(1));
                }

                // delete the messages from the sms table
                where = DatabaseUtils.concatenateWhere("thread_id=" + threadID, where);
                count = db.delete(TABLE_SMS, where, whereArgs);
                MmsSmsDatabaseHelper.updateThread(db, threadID);
                break;

            case SMS_RAW_MESSAGE:
                count = db.delete("raw", where, whereArgs);
                break;

            case SMS_STATUS_PENDING:
                count = db.delete("sr_pending", where, whereArgs);
                break;

            case SMS_ICC: {
                String messageIndexString = url.getPathSegments().get(1);
                return deleteMessageFromIcc(messageIndexString);
            }

            case SMS_ICC1: {
                String messageIndexString = url.getPathSegments().get(1);
                return deleteMessageFromIcc(messageIndexString, SUB1);
            }

            case SMS_ICC2: {
                String messageIndexString = url.getPathSegments().get(1);
                return deleteMessageFromIcc(messageIndexString, SUB2);
            }

            case SMS_ALL_ICC: {
                return deleteAllFromIccDatabase(SUB_INVALID);
            }

            case SMS_ALL_ICC1: {
                return deleteAllFromIccDatabase(SUB1);
            }

            case SMS_ALL_ICC2: {
                return deleteAllFromIccDatabase(SUB2);
            }
            
            default:
                throw new IllegalArgumentException("Unknown URL");
        }

        if (count > 0) {
            notifyChange(url);
        }
        return count;
    }

    /**
     * Delete the message at index from ICC.  Return true iff
     * successful.
     */
    private int deleteMessageFromIcc(String messageIndexString) {
        return deleteMessageFromIcc(messageIndexString, SUB_INVALID, ICC_URI);
    }

     /**
     * Delete the message at index from ICC with subscription.  Return true iff
     * successful.
     */
    private int deleteMessageFromIcc(String messageIndexString, int subscription) {
        return deleteMessageFromIcc(messageIndexString, subscription, getIccUri(subscription));
    }

    /**
     * Delete the message at index from ICC.  Return true if
     * successful.
     */
    private int deleteMessageFromIcc(String messageIndexString, int subscription, Uri iccUri) {
        try {
            boolean success = false;
            if (TelephonyManager.getDefault().isMultiSimEnabled()) {
                MSimSmsManager smsManager = MSimSmsManager.getDefault();
                success = smsManager.deleteMessageFromIcc(
                        Integer.parseInt(messageIndexString), subscription);

            } else {
                SmsManager smsManager = SmsManager.getDefault();
                success = smsManager.deleteMessageFromIcc(
                        Integer.parseInt(messageIndexString));
            }

            if (success)
            {
                deleteMessageFromIccDatabase(messageIndexString, subscription);
                return 1;
            }
            else
            {
                return 0;                
            }

        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Bad SMS ICC ID: " + messageIndexString);
        } finally {
            ContentResolver cr = getContext().getContentResolver();

            cr.notifyChange(iccUri, null);
        }
    }

    /**
     * Delete the message at index from ICC table.  Return true if
     * successful.
     */
    private int deleteMessageFromIccDatabase(String messageIndexString, int subscription)
    {
        String table = TABLE_ICC_SMS;
        String where = "index_on_icc = " + messageIndexString;

        if(TelephonyManager.getDefault().isMultiSimEnabled())
        {
            where += " AND sub_id = " + subscription;
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        return db.delete(table, where, null);
    }

    private int deleteAllFromIccDatabase(int subscription)
    {
        String table = TABLE_ICC_SMS;
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        String where = "sub_id = " + subscription;
        if (subscription == SUB_INVALID)
        {
            where = null;
        }

        int result = db.delete(table, where, null);
        
        ContentResolver cr = getContext().getContentResolver();
        if (SUB2 == subscription)
        {       
            cr.notifyChange(ICC2_URI, null);
        }
        else if (SUB1 == subscription)
        {        
            cr.notifyChange(ICC1_URI, null);
        }
        else
        {                    
            cr.notifyChange(ICC_URI, null);
        }     
        return result;
    }
    
    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs) {
        int count = 0;
        String table = TABLE_SMS;
        String extraWhere = null;
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        switch (sURLMatcher.match(url)) {
            case SMS_RAW_MESSAGE:
                table = TABLE_RAW;
                break;

            case SMS_STATUS_PENDING:
                table = TABLE_SR_PENDING;
                break;

            case SMS_ALL:
            case SMS_FAILED:
            case SMS_QUEUED:
            case SMS_INBOX:
            case SMS_SENT:
            case SMS_DRAFT:
            case SMS_OUTBOX:
            case SMS_CONVERSATIONS:
                break;

            case SMS_ALL_ID:
                extraWhere = "_id=" + url.getPathSegments().get(0);
                break;

            case SMS_INBOX_ID:
            case SMS_FAILED_ID:
            case SMS_SENT_ID:
            case SMS_DRAFT_ID:
            case SMS_OUTBOX_ID:
                extraWhere = "_id=" + url.getPathSegments().get(1);
                break;

            case SMS_CONVERSATIONS_ID: {
                String threadId = url.getPathSegments().get(1);

                try {
                    Integer.parseInt(threadId);
                } catch (Exception ex) {
                    Log.e(TAG, "Bad conversation thread id: " + threadId);
                    break;
                }

                extraWhere = "thread_id=" + threadId;
                break;
            }

            case SMS_STATUS_ID:
                extraWhere = "_id=" + url.getPathSegments().get(1);
                break;

            default:
                throw new UnsupportedOperationException(
                        "URI " + url + " not supported");
        }

        where = DatabaseUtils.concatenateWhere(where, extraWhere);
        count = db.update(table, values, where, whereArgs);

        if (count > 0) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "update " + url + " succeeded");
            }
            notifyChange(url);
        }
        return count;
    }

    private void notifyChange(Uri uri) {
        ContentResolver cr = getContext().getContentResolver();
        cr.notifyChange(uri, null);
        cr.notifyChange(MmsSms.CONTENT_URI, null);
        cr.notifyChange(Uri.parse("content://mms-sms/conversations/"), null);
    }

    private SQLiteOpenHelper mOpenHelper;

    private final static String TAG = "SmsProvider";
    private final static String VND_ANDROID_SMS = "vnd.android.cursor.item/sms";
    private final static String VND_ANDROID_SMSCHAT =
            "vnd.android.cursor.item/sms-chat";
    private final static String VND_ANDROID_DIR_SMS =
            "vnd.android.cursor.dir/sms";

    private static final HashMap<String, String> sConversationProjectionMap =
            new HashMap<String, String>();
    private static final String[] sIDProjection = new String[] { "_id" };

    private static final int SUB_INVALID = -1;  //  for single card product
    private static final int SUB1 = 0;  // for DSDS product of slot one
    private static final int SUB2 = 1;  // for DSDS product of slot two

    private static final int SMS_ALL = 0;
    private static final int SMS_ALL_ID = 1;
    private static final int SMS_INBOX = 2;
    private static final int SMS_INBOX_ID = 3;
    private static final int SMS_SENT = 4;
    private static final int SMS_SENT_ID = 5;
    private static final int SMS_DRAFT = 6;
    private static final int SMS_DRAFT_ID = 7;
    private static final int SMS_OUTBOX = 8;
    private static final int SMS_OUTBOX_ID = 9;
    private static final int SMS_CONVERSATIONS = 10;
    private static final int SMS_CONVERSATIONS_ID = 11;
    private static final int SMS_RAW_MESSAGE = 15;
    private static final int SMS_ATTACHMENT = 16;
    private static final int SMS_ATTACHMENT_ID = 17;
    private static final int SMS_NEW_THREAD_ID = 18;
    private static final int SMS_QUERY_THREAD_ID = 19;
    private static final int SMS_STATUS_ID = 20;
    private static final int SMS_STATUS_PENDING = 21;
    private static final int SMS_ALL_ICC = 22;
    private static final int SMS_ICC = 23;
    private static final int SMS_FAILED = 24;
    private static final int SMS_FAILED_ID = 25;
    private static final int SMS_QUEUED = 26;
    private static final int SMS_UNDELIVERED = 27;
    private static final int SMS_INBOX_SUB1 = 28;
    private static final int SMS_INBOX_SUB2 = 29;
    private static final int SMS_ALL_ICC1 = 30;
    private static final int SMS_ICC1 = 31;
    private static final int SMS_ALL_ICC2 = 32;
    private static final int SMS_ICC2 = 33;

    private static final UriMatcher sURLMatcher =
            new UriMatcher(UriMatcher.NO_MATCH);

    static {
        sURLMatcher.addURI("sms", null, SMS_ALL);
        sURLMatcher.addURI("sms", "#", SMS_ALL_ID);
        sURLMatcher.addURI("sms", "inbox", SMS_INBOX);
        sURLMatcher.addURI("sms", "inbox/#", SMS_INBOX_ID);
        sURLMatcher.addURI("sms", "sent", SMS_SENT);
        sURLMatcher.addURI("sms", "sent/#", SMS_SENT_ID);
        sURLMatcher.addURI("sms", "draft", SMS_DRAFT);
        sURLMatcher.addURI("sms", "draft/#", SMS_DRAFT_ID);
        sURLMatcher.addURI("sms", "outbox", SMS_OUTBOX);
        sURLMatcher.addURI("sms", "outbox/#", SMS_OUTBOX_ID);
        sURLMatcher.addURI("sms", "undelivered", SMS_UNDELIVERED);
        sURLMatcher.addURI("sms", "failed", SMS_FAILED);
        sURLMatcher.addURI("sms", "failed/#", SMS_FAILED_ID);
        sURLMatcher.addURI("sms", "queued", SMS_QUEUED);
        sURLMatcher.addURI("sms", "conversations", SMS_CONVERSATIONS);
        sURLMatcher.addURI("sms", "conversations/*", SMS_CONVERSATIONS_ID);
        sURLMatcher.addURI("sms", "raw", SMS_RAW_MESSAGE);
        sURLMatcher.addURI("sms", "attachments", SMS_ATTACHMENT);
        sURLMatcher.addURI("sms", "attachments/#", SMS_ATTACHMENT_ID);
        sURLMatcher.addURI("sms", "threadID", SMS_NEW_THREAD_ID);
        sURLMatcher.addURI("sms", "threadID/*", SMS_QUERY_THREAD_ID);
        sURLMatcher.addURI("sms", "status/#", SMS_STATUS_ID);
        sURLMatcher.addURI("sms", "sr_pending", SMS_STATUS_PENDING);
        sURLMatcher.addURI("sms", "icc", SMS_ALL_ICC);
        sURLMatcher.addURI("sms", "icc/#", SMS_ICC);
        //we keep these for not breaking old applications
        sURLMatcher.addURI("sms", "sim", SMS_ALL_ICC);
        sURLMatcher.addURI("sms", "sim/#", SMS_ICC);
        //URLs for the sms on sim card1 and sim card2
        sURLMatcher.addURI("sms", "icc1", SMS_ALL_ICC1);
        sURLMatcher.addURI("sms", "icc1/#", SMS_ICC1);
        sURLMatcher.addURI("sms", "sim1", SMS_ALL_ICC1);
        sURLMatcher.addURI("sms", "sim1/#", SMS_ICC1);
        sURLMatcher.addURI("sms", "icc2", SMS_ALL_ICC2);
        sURLMatcher.addURI("sms", "icc2/#", SMS_ICC2);
        sURLMatcher.addURI("sms", "sim2", SMS_ALL_ICC2);
        sURLMatcher.addURI("sms", "sim2/#", SMS_ICC2);
        //URLs for the sms belongs to sub1 and sub2
        sURLMatcher.addURI("sms", "inbox/sub1", SMS_INBOX_SUB1);
        sURLMatcher.addURI("sms", "inbox/sub2", SMS_INBOX_SUB2);

        sConversationProjectionMap.put(Sms.Conversations.SNIPPET,
            "sms.body AS snippet");
        sConversationProjectionMap.put(Sms.Conversations.THREAD_ID,
            "sms.thread_id AS thread_id");
        sConversationProjectionMap.put(Sms.Conversations.MESSAGE_COUNT,
            "groups.msg_count AS msg_count");
        sConversationProjectionMap.put("delta", null);
    }
}
