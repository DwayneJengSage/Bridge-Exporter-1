package org.sagebionetworks.bridge.exporter.util;


import com.amazonaws.services.dynamodbv2.document.Item;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import org.jsoup.Jsoup;
import org.jsoup.safety.Whitelist;
import org.sagebionetworks.bridge.exporter.synapse.ColumnDefinition;
import org.sagebionetworks.bridge.exporter.synapse.TransferMethod;
import org.sagebionetworks.repo.model.table.ColumnModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sagebionetworks.bridge.rest.model.UploadFieldDefinition;
import org.sagebionetworks.bridge.rest.model.UploadSchema;
import org.sagebionetworks.bridge.schema.UploadSchemaKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Various static utility methods that don't neatly fit anywhere else. */
public class BridgeExporterUtil {
    private static final Logger LOG = LoggerFactory.getLogger(BridgeExporterUtil.class);

    public static final Joiner STRING_SET_JOINER = Joiner.on(',').useForNull("");

    public static final Joiner COMMA_SPACE_JOINER = Joiner.on(", ").useForNull("");
    public static final String CONFIG_KEY_ATTACHMENT_S3_BUCKET = "attachment.bucket";
    public static final String CONFIG_KEY_TIME_ZONE_NAME = "time.zone.name";
    public static final String CONFIG_KEY_RECORD_ID_OVERRIDE_BUCKET = "record.id.override.bucket";
    public static final String CONFIG_KEY_SQS_QUEUE_URL = "exporter.request.sqs.queue.url";
    public static final String DEFAULT_TABLE_NAME = "Default Health Data Record Table";
    public static final String KEY_CUSTOM_CONTENT_MD5 = "Custom-Content-MD5";

    public static final Joiner PIPE_JOINER = Joiner.on("|");

    /**
     * Helper method to get a field definition map from a schema, keyed by field name
     *
     * @param schema
     *         schema to get field definition map from
     * @return field definition map
     */
    public static Map<String, UploadFieldDefinition> getFieldDefMapFromSchema(UploadSchema schema) {
        Map<String, UploadFieldDefinition> fieldDefMap = new HashMap<>();
        for (UploadFieldDefinition oneFieldDef : schema.getFieldDefinitions()) {
            fieldDefMap.put(oneFieldDef.getName(), oneFieldDef);
        }
        return fieldDefMap;
    }

    /**
     * Helper method to get the schema key for a DDB health data record
     *
     * @param record
     *         DDB Item representing a health data record
     * @return the schema key associated with that record
     */
    public static UploadSchemaKey getSchemaKeyForRecord(Item record) {
        if (!record.hasAttribute("schemaId") || !record.hasAttribute("schemaRevision")) {
            // This is a schemaless record.
            return null;
        }

        String studyId = record.getString("studyId");
        String schemaId = record.getString("schemaId");
        int schemaRev = record.getInt("schemaRevision");
        return new UploadSchemaKey.Builder().withStudyId(studyId).withSchemaId(schemaId).withRevision(schemaRev)
                .build();
    }

    /**
     * Helper method to get an UploadSchemaKey object from a schema.
     *
     * @param schema
     *         schema to get key from
     * @return schema key
     */
    public static UploadSchemaKey getSchemaKeyFromSchema(UploadSchema schema) {
        // In the new JavaSDK, revision is a Long. However, in bridge-base UploadSchemaKey, revision is an Integer.
        // There is no null-safe way to convert this. So do a null check and throw an IllegalArgumentException, so that
        // we don't have to deal with a NullPointerException.
        if (schema.getRevision() == null) {
            throw new IllegalArgumentException("revision can't be null");
        }
        return new UploadSchemaKey.Builder().withStudyId(schema.getStudyId()).withSchemaId(schema.getSchemaId())
                .withRevision(schema.getRevision().intValue()).build();
    }

    /**
     * Helper method to extract and sanitize a DDB string value, given a Dynamo DB item and a field name.
     *
     * @param item
     *         Dynamo DB item
     * @param fieldName
     *         DDB field name
     * @param maxLength
     *         max length of the column
     * @param recordId
     *         record ID, for logging purposes
     * @return sanitized DDB string value
     */
    public static String sanitizeDdbValue(Item item, String fieldName, Integer maxLength, String recordId) {
        String value = item.getString(fieldName);
        String studyId = item.getString("studyId");
        return sanitizeString(value, fieldName, maxLength, recordId, studyId);
    }

    /**
     * Helper method to extract and sanitize a JSON string value, given a JsonNode and a field name.
     *
     * @param node
     *         JsonNode to extract the value from
     * @param fieldName
     *         JSON field name
     * @param maxLength
     *         max length of the column
     * @param recordId
     *         record ID, for logging purposes
     * @param studyId
     *         study ID, for logging purposes
     * @return sanitized JSON string value
     */
    public static String sanitizeJsonValue(JsonNode node, String fieldName, int maxLength, String recordId,
            String studyId) {
        if (!node.hasNonNull(fieldName)) {
            return null;
        }
        return sanitizeString(node.get(fieldName).textValue(), fieldName, maxLength, recordId, studyId);
    }

    /**
     * <p>
     * Sanitizes the given string to make it acceptable for a TSV to upload to Synapse. This involves truncating
     * columns that are too wide. This will log an error if truncation happens, so this can be detected post-run.
     * </p>
     * <p>
     * Also strips HTML to defend against HTML Injection attacks.
     * </p>
     * <p>
     * We don't handle newlines, carriage returns, tabs, or escaping strings, as we now use CSVWriter to take care of
     * that for us.
     * </p>
     *
     * @param in
     *         value to sanitize
     * @param fieldName
     *         name of the field we're sanitizing, for logging purposes
     * @param maxLength
     *         max length of the column, null if the string can be unbounded
     * @param recordId
     *         record ID, for logging purposes
     * @param studyId
     *         study ID, for logging purposes
     * @return sanitized string
     */
    public static String sanitizeString(String in, String fieldName, Integer maxLength, String recordId,
            String studyId) {
        if (in == null) {
            return null;
        }

        // Strip HTML.
        // As it turns out, Jsoup also flattens all whitespace (tabs, newlines, carriage returns, etc).
        in = Jsoup.clean(in, Whitelist.none());

        // Check against max length, truncating and warning as necessary.
        if (maxLength != null && in.length() > maxLength) {
            LOG.error("Truncating string for field " + fieldName + " in record " + recordId + " in study " + studyId +
                    ", original length " + in.length() + " to max length " + maxLength);
            in = in.substring(0, maxLength);
        }

        return in;
    }

    /**
     * Helper method to convert a list of ColumnDefinition to a ColumnModel list.
     */
    public static List<ColumnModel> convertToColumnList(final List<ColumnDefinition> columnDefinitions) {
        ImmutableList.Builder<ColumnModel> columnListBuilder = ImmutableList.builder();

        for (ColumnDefinition columnDefinition : columnDefinitions) {
            ColumnModel columnModel = new ColumnModel();
            columnModel.setName(columnDefinition.getName());
            columnModel.setColumnType(columnDefinition.getTransferMethod().getColumnType());

            // For some reason, ColumnModel.maximumSize is a long, but we have ints. It can't ever be more than 1000
            // anyway.
            if (columnDefinition.getMaximumSize() != null) {
                columnModel.setMaximumSize(columnDefinition.getMaximumSize().longValue());
            }

            columnListBuilder.add(columnModel);
        }

        return columnListBuilder.build();
    }

    public static void getRowValuesFromRecordBasedOnColumnDefinition(Map<String, String> rowMap, final Item record, final List<ColumnDefinition> columnDefinitions, final String recordId) {

        for (ColumnDefinition columnDefinition : columnDefinitions) {
            // use name if there is no ddbName
            final String ddbName = columnDefinition.getDdbName() == null? columnDefinition.getName() : columnDefinition.getDdbName();

            String valueToAdd;
            if (columnDefinition.getSanitize()) {
                valueToAdd = sanitizeDdbValue(record, ddbName, columnDefinition.getMaximumSize(), recordId);
            } else {
                TransferMethod transferMethod = columnDefinition.getTransferMethod();
                valueToAdd = transferMethod.transfer(ddbName, record);
            }

            rowMap.put(columnDefinition.getName(), valueToAdd);
        }
    }
    
    public static String serializeSubstudyMemberships(Map<String, String> substudyMemberships) {
        if (substudyMemberships == null || substudyMemberships.isEmpty()) {
            return null;
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : substudyMemberships.entrySet()) {
            String key = entry.getKey();
            String value = "<none>".equals(entry.getValue()) ? "" : entry.getValue();
            pairs.add(key + "=" + value);
        }
        Collections.sort(pairs);
        return "|" + PIPE_JOINER.join(pairs) + "|";
    }
}
